package io.raaz.messenger.data.network

import io.raaz.messenger.util.LoggingInterceptor
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface RaazApi {

    @POST("api/v1/devices/register")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): Response<RegisterDeviceResponse>

    @POST("api/v1/messages")
    suspend fun pushMessage(
        @Header("Authorization") auth: String,
        @Body request: PushMessageRequest
    ): Response<PushMessageResponse>

    @GET("api/v1/messages")
    suspend fun pullMessages(
        @Header("Authorization") auth: String
    ): Response<PullMessagesResponse>

    @DELETE("api/v1/messages/{id}")
    suspend fun ackMessage(
        @Header("Authorization") auth: String,
        @Path("id") serverMessageId: String
    ): Response<Unit>

    @GET("api/v1/receipts")
    suspend fun pullReceipts(
        @Header("Authorization") auth: String
    ): Response<ReceiptsResponse>

    @GET("api/v1/health")
    suspend fun health(): Response<HealthResponse>
}

object RaazApiService {

    private var instance: RaazApi? = null
    private var currentBaseUrl: String = ""

    fun get(baseUrl: String): RaazApi {
        val url = baseUrl.trimEnd('/') + "/"
        if (instance == null || url != currentBaseUrl) {
            currentBaseUrl = url
            instance = build(url)
        }
        return instance!!
    }

    private fun build(baseUrl: String): RaazApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(LoggingInterceptor())
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RaazApi::class.java)
    }
}
