package io.raaz.messenger.ui.lock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.raaz.messenger.data.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LockViewModel(app: Application) : AndroidViewModel(app) {

    private val authRepo = AuthRepository(app)

    private val _state = MutableLiveData<LockState>(LockState.Idle)
    val state: LiveData<LockState> = _state

    val isSetupComplete: Boolean get() = authRepo.isSetupComplete()

    fun unlock(password: String) {
        if (password.isBlank()) return
        _state.value = LockState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { authRepo.unlock(password) }
            _state.value = when (result) {
                is AuthRepository.UnlockResult.Success -> LockState.Unlocked(result.dbKey)
                is AuthRepository.UnlockResult.Wiped -> LockState.Wiped
                is AuthRepository.UnlockResult.WrongPassword -> LockState.WrongPassword(
                    result.attemptsRemaining, result.lockoutUntil
                )
                is AuthRepository.UnlockResult.LockedOut -> LockState.LockedOut(result.until)
            }
        }
    }

    sealed class LockState {
        object Idle : LockState()
        object Loading : LockState()
        data class Unlocked(val dbKey: String) : LockState()
        object Wiped : LockState()
        data class WrongPassword(val attemptsRemaining: Int, val lockoutUntil: Long?) : LockState()
        data class LockedOut(val until: Long) : LockState()
    }
}
