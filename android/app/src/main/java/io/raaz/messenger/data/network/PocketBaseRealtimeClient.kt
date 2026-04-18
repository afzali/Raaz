package io.raaz.messenger.data.network

import io.raaz.messenger.util.AppLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PocketBaseRealtimeClient {

    private val TAG = AppLogger.Cat.NET
    private var eventSource: EventSource? = null

    private val _events = Channel<Unit>(Channel.CONFLATED)
    val newMessageEvents: Flow<Unit> = _events.receiveAsFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // no timeout for SSE stream
        .build()

    fun connect(serverUrl: String, token: String, deviceId: String) {
        disconnect()

        // PocketBase realtime: GET /api/realtime — SSE stream
        // Events arrive as JSON: {"action":"create","record":{...}}
        val url = serverUrl.trimEnd('/') + "/api/realtime"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        AppLogger.i(TAG, "Connecting SSE to $url")

        eventSource = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {

            override fun onOpen(eventSource: EventSource, response: Response) {
                AppLogger.i(TAG, "PocketBase SSE connected")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.isBlank() || data == "\"\"") return
                AppLogger.d(TAG, "SSE event type=$type data=${data.take(120)}")
                try {
                    val json = JSONObject(data)
                    val action = json.optString("action")
                    if (action == "create") {
                        val record = json.optJSONObject("record")
                        val recipientDeviceId = record?.optString("recipient_device_id") ?: ""
                        if (recipientDeviceId == deviceId) {
                            AppLogger.i(TAG, "SSE: new message for this device — triggering sync")
                            _events.trySend(Unit)
                        }
                    }
                } catch (_: Exception) {
                    // non-JSON system events (clientId assignment etc.) — ignore
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                AppLogger.w(TAG, "SSE failure: ${t?.message ?: response?.code}")
            }

            override fun onClosed(eventSource: EventSource) {
                AppLogger.i(TAG, "SSE closed")
            }
        })
    }

    fun disconnect() {
        eventSource?.cancel()
        eventSource = null
        AppLogger.d(TAG, "SSE disconnected")
    }
}
