package io.raaz.messenger.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.raaz.messenger.data.repository.AuthRepository
import io.raaz.messenger.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = AppLogger.Cat.AUTH
    private val authRepo = AuthRepository(app)

    private val _state = MutableLiveData<SetupState>(SetupState.Idle)
    val state: LiveData<SetupState> = _state

    fun setup(password: String, confirmPassword: String, serverUrl: String) {
        AppLogger.i(TAG, "Setup requested — server=$serverUrl")
        if (password.length < 6) {
            AppLogger.w(TAG, "Setup rejected: password too short")
            _state.value = SetupState.Error("password_short")
            return
        }
        if (password != confirmPassword) {
            AppLogger.w(TAG, "Setup rejected: password mismatch")
            _state.value = SetupState.Error("password_mismatch")
            return
        }
        val url = serverUrl.ifBlank { "http://relay.rahejanan.ir" }
        AppLogger.i(TAG, "Starting setup with server: $url")
        _state.value = SetupState.Loading
        viewModelScope.launch {
            val result = authRepo.setup(password, url)
            _state.value = if (result.isSuccess) {
                AppLogger.i(TAG, "Setup VM: done, navigating to chats")
                SetupState.Done(result.getOrThrow())
            } else {
                AppLogger.e(TAG, "Setup VM: failed — ${result.exceptionOrNull()?.message}")
                SetupState.Error(result.exceptionOrNull()?.message ?: "unknown")
            }
        }
    }

    private val _connectionState = MutableLiveData<ConnectionState>(ConnectionState.Idle)
    val connectionState: LiveData<ConnectionState> = _connectionState

    fun testConnection(url: String) {
        val baseUrl = url.ifBlank { "http://relay.rahejanan.ir" }.trimEnd('/')
        AppLogger.i(TAG, "Testing server connection: $baseUrl/api/v1/health")
        _connectionState.value = ConnectionState.Testing
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val conn = URL("$baseUrl/api/v1/health").openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    AppLogger.i(TAG, "Health check response: HTTP $code")
                    code in 200..299
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Health check failed: ${e.message}")
                    false
                }
            }
            _connectionState.value = if (ok) ConnectionState.Ok else ConnectionState.Fail
        }
    }

    sealed class ConnectionState {
        object Idle : ConnectionState()
        object Testing : ConnectionState()
        object Ok : ConnectionState()
        object Fail : ConnectionState()
    }

    sealed class SetupState {
        object Idle : SetupState()
        object Loading : SetupState()
        data class Done(val dbKey: String) : SetupState()
        data class Error(val reason: String) : SetupState()
    }
}
