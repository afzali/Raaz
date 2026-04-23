package io.raaz.messenger.data.repository

import android.content.Context
import io.raaz.messenger.crypto.BiometricKeyStore
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.crypto.PasswordManager
import javax.crypto.Cipher
import io.raaz.messenger.data.db.AuthDatabase
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.SettingsDao
import io.raaz.messenger.data.network.RegisterDeviceRequest
import io.raaz.messenger.data.network.RaazApiService
import io.raaz.messenger.data.preferences.RaazPreferences
import io.raaz.messenger.util.AppLogger
import io.raaz.messenger.util.SessionLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AuthRepository(private val context: Context) {

    private val TAG = AppLogger.Cat.AUTH

    private val authDbKey by lazy { PasswordManager.generateAuthDbKey(context) }
    val authDb by lazy { AuthDatabase.getInstance(context, authDbKey) }

    fun isSetupComplete(): Boolean = PasswordManager.hasSalt(context) && RaazDatabase.exists(context)

    suspend fun setup(password: String, serverUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "=== SETUP START ===")
            AppLogger.i(TAG, "Server URL: $serverUrl")
            val dbKey = CryptoManager.setupNewPassword(context, password)
            AppLogger.i(TAG, "Password hashed (Argon2id), DB key derived")
            val raazDb = RaazDatabase.getInstance(context, dbKey)
            val settingsDao = SettingsDao(raazDb.db)
            AppLogger.i(TAG, "Main DB opened with derived key")

            val (userId, deviceId) = CryptoManager.generateDeviceIds()
            val (privateKeyB64, publicKeyB64) = CryptoManager.generateIdentityKeyPair()
            AppLogger.i(TAG, "Identity keypair generated — userId=$userId, deviceId=$deviceId")
            AppLogger.d(TAG, "PublicKey(first12): ${publicKeyB64.take(12)}...")

            settingsDao.markSetupComplete(userId, deviceId, publicKeyB64, privateKeyB64, serverUrl)
            RaazPreferences(context).serverUrl = serverUrl  // cache for background workers
            AppLogger.i(TAG, "Settings saved to DB (serverUrl=$serverUrl)")

            CryptoManager.loadKeys(privateKeyB64, publicKeyB64)
            AppLogger.i(TAG, "Keys loaded into CryptoManager")

            val prefs = RaazPreferences(context)
            prefs.userId = userId
            prefs.deviceId = deviceId
            AppLogger.i(TAG, "userId/deviceId persisted to prefs")

            // Register with server
            AppLogger.i(TAG, "Registering device with server...")
            try {
                val api = RaazApiService.get(serverUrl)
                val resp = api.registerDevice(RegisterDeviceRequest(userId, deviceId, publicKeyB64))
                if (resp.isSuccessful) {
                    val body = resp.body()!!
                    prefs.bearerToken = body.token
                    AppLogger.i(TAG, "Device registered — token received (${body.token.length} chars)")
                } else {
                    AppLogger.w(TAG, "Server registration HTTP ${resp.code()} — will retry later")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Server registration failed (will retry later): ${e.message}")
            }

            authDb.recordSuccessfulUnlock()
            SessionLockManager.unlock()
            AppLogger.i(TAG, "=== SETUP COMPLETE for user $userId ===")
            Result.success(dbKey)
        } catch (e: Exception) {
            AppLogger.e(TAG, "=== SETUP FAILED: ${e.message} ===", e)
            Result.failure(e)
        }
    }

    suspend fun unlock(password: String): UnlockResult = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "=== UNLOCK ATTEMPT ===")
        val lockState = authDb.getLockState()
        AppLogger.d(TAG, "LockState: failStreak=${lockState.failStreak}, lockoutUntil=${lockState.lockoutUntil}")

        // Check brute-force lockout
        if (lockState.lockoutUntil != null && lockState.lockoutUntil > System.currentTimeMillis()) {
            val remainMs = lockState.lockoutUntil - System.currentTimeMillis()
            AppLogger.w(TAG, "Locked out — ${remainMs / 1000}s remaining")
            return@withContext UnlockResult.LockedOut(lockState.lockoutUntil)
        }

        if (authDb.shouldWipe()) {
            AppLogger.w(TAG, "failStreak >= 20 — triggering wipe")
            wipeAll()
            return@withContext UnlockResult.Wiped
        }

        return@withContext try {
            AppLogger.i(TAG, "Deriving DB key (Argon2id)...")
            val dbKey = CryptoManager.deriveDbKey(context, password)
            AppLogger.i(TAG, "DB key derived, opening main DB...")
            val raazDb = RaazDatabase.getInstance(context, dbKey)
            val settings = SettingsDao(raazDb.db).get()
            AppLogger.i(TAG, "Main DB opened — setupComplete=${settings.setupComplete}")

            if (settings.privateKeyEncrypted != null && settings.publicKey != null) {
                CryptoManager.loadKeys(settings.privateKeyEncrypted, settings.publicKey)
                SessionLockManager.updateTimeout(settings.lockTimeoutMs)
                AppLogger.i(TAG, "Keys loaded into CryptoManager, lockTimeout=${settings.lockTimeoutMs}ms")
            } else {
                AppLogger.w(TAG, "No keys in settings (first unlock after setup?)")
            }

            // Re-register if no token (e.g. first launch, server changed, app reinstall)
            val prefs = RaazPreferences(context)
            val savedServerUrl = settings.serverUrl
            prefs.serverUrl = savedServerUrl  // cache for background workers
            AppLogger.i(TAG, "Loaded saved server URL: $savedServerUrl")
            if (prefs.bearerToken == null && settings.userId != null && settings.publicKey != null) {
                val userId = settings.userId
                val deviceId = settings.deviceId ?: prefs.deviceId
                val publicKey = settings.publicKey
                AppLogger.w(TAG, "No bearer token — attempting re-registration with $savedServerUrl")
                try {
                    val api = RaazApiService.get(settings.serverUrl)
                    val resp = api.registerDevice(RegisterDeviceRequest(userId!!, deviceId!!, publicKey!!))
                    if (resp.isSuccessful) {
                        val token = resp.body()!!.token
                        prefs.bearerToken = token
                        if (deviceId != null) prefs.deviceId = deviceId
                        prefs.userId = userId
                        AppLogger.i(TAG, "Re-registration OK — token received (${token.length} chars)")
                    } else {
                        AppLogger.w(TAG, "Re-registration HTTP ${resp.code()} — will need manual server URL fix")
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Re-registration failed: ${e.message}")
                }
            } else if (prefs.bearerToken != null) {
                AppLogger.d(TAG, "Token already present — no re-registration needed")
            }

            authDb.recordSuccessfulUnlock()
            SessionLockManager.unlock()
            AppLogger.i(TAG, "=== UNLOCK SUCCESS ===")
            UnlockResult.Success(dbKey)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Wrong password or DB open failed: ${e.message}")
            val newState = authDb.recordFailedAttempt()
            AppLogger.w(TAG, "Failed attempt recorded — failStreak=${newState.failStreak}/20")
            if (newState.failStreak >= 20) {
                AppLogger.w(TAG, "=== MAX ATTEMPTS REACHED — WIPING ===")
                wipeAll()
                UnlockResult.Wiped
            } else {
                val remaining = 20 - newState.failStreak
                AppLogger.w(TAG, "$remaining attempts remaining before wipe")
                UnlockResult.WrongPassword(remaining, newState.lockoutUntil)
            }
        }
    }

    private fun wipeAll() {
        AppLogger.w(TAG, "WIPE: closing DB and clearing keys...")
        RaazDatabase.close()
        CryptoManager.clearKeys()
        context.deleteDatabase(RaazDatabase.DB_NAME)
        PasswordManager.saveSalt(context, ByteArray(0))
        AppLogger.w(TAG, "WIPE: all user data deleted")
    }

    fun lock() {
        CryptoManager.clearKeys()
        RaazDatabase.close()
        authDb.setLocked(true)
        SessionLockManager.lock()
        AppLogger.i(TAG, "=== APP LOCKED ===")
    }

    /**
     * Check if biometric unlock has been set up (encrypted password exists).
     */
    fun isBiometricSetup(): Boolean = BiometricKeyStore.isBiometricKeySetup(context)

    /**
     * Enable biometric unlock: encrypt current password with biometric-protected key.
     * Must be called with an authenticated cipher from BiometricKeyStore.getEncryptCipher()
     * after biometric prompt succeeds.
     */
    fun storeBiometricPassword(cipher: Cipher, password: String) {
        BiometricKeyStore.storeEncryptedPassword(context, cipher, password)
        AppLogger.i(TAG, "Biometric unlock enabled")
    }

    /**
     * Get cipher needed for encrypting password (before biometric prompt).
     */
    fun getBiometricEncryptCipher(): Cipher = BiometricKeyStore.getEncryptCipher()

    /**
     * Get cipher needed for decrypting stored password (before biometric prompt).
     */
    fun getBiometricDecryptCipher(): Cipher? = BiometricKeyStore.getDecryptCipher(context)

    /**
     * Disable biometric unlock: clear stored keystore key and encrypted password.
     */
    fun disableBiometric() {
        BiometricKeyStore.clear(context)
        AppLogger.i(TAG, "Biometric unlock disabled")
    }

    /**
     * Unlock using biometric: decrypt stored password with authenticated cipher,
     * then perform normal unlock flow.
     */
    suspend fun unlockWithBiometric(cipher: Cipher): UnlockResult = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "=== BIOMETRIC UNLOCK ATTEMPT ===")
        val password = BiometricKeyStore.retrieveDecryptedPassword(context, cipher)
        if (password == null) {
            AppLogger.w(TAG, "Failed to decrypt stored password via biometric")
            return@withContext UnlockResult.WrongPassword(0, null)
        }
        AppLogger.i(TAG, "Password decrypted via biometric, proceeding to unlock")
        unlock(password)
    }

    sealed class UnlockResult {
        data class Success(val dbKey: String) : UnlockResult()
        object Wiped : UnlockResult()
        data class WrongPassword(val attemptsRemaining: Int, val lockoutUntil: Long?) : UnlockResult()
        data class LockedOut(val until: Long) : UnlockResult()
    }
}
