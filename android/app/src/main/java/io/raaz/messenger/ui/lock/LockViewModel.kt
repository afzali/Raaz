package io.raaz.messenger.ui.lock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.raaz.messenger.data.repository.AuthRepository
import io.raaz.messenger.util.AppLogger
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LockViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = AppLogger.Cat.AUTH
    private val authRepo = AuthRepository(app)

    private val _state = MutableLiveData<LockState>(LockState.Idle)
    val state: LiveData<LockState> = _state

    val isSetupComplete: Boolean get() = authRepo.isSetupComplete()

    /**
     * Check if biometric unlock is set up (encrypted password exists in Keystore).
     * No DB access required.
     */
    fun isBiometricSetup(): Boolean = authRepo.isBiometricSetup()

    /**
     * Get the cipher needed to decrypt the stored password via biometric.
     * This cipher must be passed to BiometricPrompt.
     */
    fun getBiometricDecryptCipher(): Cipher? = authRepo.getBiometricDecryptCipher()

    /**
     * Unlock using authenticated cipher from biometric prompt.
     */
    fun unlockWithBiometric(authenticatedCipher: Cipher) {
        AppLogger.i(TAG, "Biometric unlock attempt")
        _state.value = LockState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { authRepo.unlockWithBiometric(authenticatedCipher) }
            _state.value = when (result) {
                is AuthRepository.UnlockResult.Success -> {
                    AppLogger.i(TAG, "LockVM: biometric unlock success")
                    LockState.Unlocked(result.dbKey)
                }
                else -> {
                    AppLogger.w(TAG, "LockVM: biometric unlock failed")
                    LockState.WrongPassword(0, null)
                }
            }
        }
    }

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
