package io.raaz.messenger.ui.lock

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

class LockViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = AppLogger.Cat.AUTH
    private val authRepo = AuthRepository(app)

    private val _state = MutableLiveData<LockState>(LockState.Idle)
    val state: LiveData<LockState> = _state

    val isSetupComplete: Boolean get() = authRepo.isSetupComplete()

    fun unlock(password: String) {
        if (password.isBlank()) return
        AppLogger.i(TAG, "Unlock attempt initiated from LockScreen")
        _state.value = LockState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { authRepo.unlock(password) }
            _state.value = when (result) {
                is AuthRepository.UnlockResult.Success -> {
                    AppLogger.i(TAG, "LockVM: unlock success — navigating to chats")
                    LockState.Unlocked(result.dbKey)
                }
                is AuthRepository.UnlockResult.Wiped -> {
                    AppLogger.w(TAG, "LockVM: data wiped — navigating to setup")
                    LockState.Wiped
                }
                is AuthRepository.UnlockResult.WrongPassword -> {
                    AppLogger.w(TAG, "LockVM: wrong password — ${result.attemptsRemaining} attempts left")
                    LockState.WrongPassword(result.attemptsRemaining, result.lockoutUntil)
                }
                is AuthRepository.UnlockResult.LockedOut -> {
                    AppLogger.w(TAG, "LockVM: locked out until ${result.until}")
                    LockState.LockedOut(result.until)
                }
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
