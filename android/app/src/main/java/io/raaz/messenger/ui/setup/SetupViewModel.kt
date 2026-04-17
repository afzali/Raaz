package io.raaz.messenger.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.raaz.messenger.data.repository.AuthRepository
import kotlinx.coroutines.launch

class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val authRepo = AuthRepository(app)

    private val _state = MutableLiveData<SetupState>(SetupState.Idle)
    val state: LiveData<SetupState> = _state

    fun setup(password: String, confirmPassword: String, serverUrl: String) {
        if (password.length < 6) {
            _state.value = SetupState.Error("password_short")
            return
        }
        if (password != confirmPassword) {
            _state.value = SetupState.Error("password_mismatch")
            return
        }
        val url = serverUrl.ifBlank { "https://relay.raaz.io" }
        _state.value = SetupState.Loading
        viewModelScope.launch {
            val result = authRepo.setup(password, url)
            _state.value = if (result.isSuccess)
                SetupState.Done(result.getOrThrow())
            else
                SetupState.Error(result.exceptionOrNull()?.message ?: "unknown")
        }
    }

    sealed class SetupState {
        object Idle : SetupState()
        object Loading : SetupState()
        data class Done(val dbKey: String) : SetupState()
        data class Error(val reason: String) : SetupState()
    }
}
