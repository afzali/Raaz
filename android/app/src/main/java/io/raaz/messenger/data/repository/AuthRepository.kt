package io.raaz.messenger.data.repository

import android.content.Context
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.crypto.PasswordManager
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

    private val TAG = "AuthRepo"

    private val authDbKey by lazy { PasswordManager.generateAuthDbKey(context) }
    val authDb by lazy { AuthDatabase.getInstance(context, authDbKey) }

    fun isSetupComplete(): Boolean = PasswordManager.hasSalt(context) && RaazDatabase.exists(context)

    suspend fun setup(password: String, serverUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dbKey = CryptoManager.setupNewPassword(context, password)
            val raazDb = RaazDatabase.getInstance(context, dbKey)
            val settingsDao = SettingsDao(raazDb.db)

            val (userId, deviceId) = CryptoManager.generateDeviceIds()
            val (privateKeyB64, publicKeyB64) = CryptoManager.generateIdentityKeyPair()

            // Store private key encrypted by the DB key itself (DB is already encrypted)
            settingsDao.markSetupComplete(userId, deviceId, publicKeyB64, privateKeyB64)

            CryptoManager.loadKeys(privateKeyB64, publicKeyB64)

            // Always persist userId/deviceId — server registration may fail/retry later
            val prefs = RaazPreferences(context)
            prefs.userId = userId
            prefs.deviceId = deviceId

            // Register with server
            try {
                val api = RaazApiService.get(serverUrl)
                val resp = api.registerDevice(RegisterDeviceRequest(userId, deviceId, publicKeyB64))
                if (resp.isSuccessful) {
                    val body = resp.body()!!
                    prefs.bearerToken = body.token
                    AppLogger.i(TAG, "Device registered with server")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Server registration failed (will retry later): ${e.message}")
            }

            authDb.recordSuccessfulUnlock()
            SessionLockManager.unlock()
            AppLogger.i(TAG, "Setup complete for user $userId")
            Result.success(dbKey)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Setup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun unlock(password: String): UnlockResult = withContext(Dispatchers.IO) {
        val lockState = authDb.getLockState()

        // Check brute-force lockout
        if (lockState.lockoutUntil != null && lockState.lockoutUntil > System.currentTimeMillis()) {
            return@withContext UnlockResult.LockedOut(lockState.lockoutUntil)
        }

        if (authDb.shouldWipe()) {
            wipeAll()
            return@withContext UnlockResult.Wiped
        }

        return@withContext try {
            val dbKey = CryptoManager.deriveDbKey(context, password)
            val raazDb = RaazDatabase.getInstance(context, dbKey)
            val settings = SettingsDao(raazDb.db).get()

            if (settings.privateKeyEncrypted != null && settings.publicKey != null) {
                CryptoManager.loadKeys(settings.privateKeyEncrypted, settings.publicKey)
                SessionLockManager.updateTimeout(settings.lockTimeoutMs)
            }

            authDb.recordSuccessfulUnlock()
            SessionLockManager.unlock()
            AppLogger.i(TAG, "Unlocked successfully")
            UnlockResult.Success(dbKey)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Wrong password attempt")
            val newState = authDb.recordFailedAttempt()
            if (newState.failStreak >= 9) {
                wipeAll()
                UnlockResult.Wiped
            } else {
                val remaining = 9 - newState.failStreak
                UnlockResult.WrongPassword(remaining, newState.lockoutUntil)
            }
        }
    }

    private fun wipeAll() {
        AppLogger.w(TAG, "Wiping all data due to too many failed attempts")
        RaazDatabase.close()
        CryptoManager.clearKeys()
        context.deleteDatabase(RaazDatabase.DB_NAME)
        PasswordManager.saveSalt(context, ByteArray(0)) // clear salt
        AppLogger.w(TAG, "All data wiped")
    }

    fun lock() {
        CryptoManager.clearKeys()
        RaazDatabase.close()
        authDb.setLocked(true)
        SessionLockManager.lock()
        AppLogger.d(TAG, "App locked")
    }

    sealed class UnlockResult {
        data class Success(val dbKey: String) : UnlockResult()
        object Wiped : UnlockResult()
        data class WrongPassword(val attemptsRemaining: Int, val lockoutUntil: Long?) : UnlockResult()
        data class LockedOut(val until: Long) : UnlockResult()
    }
}
