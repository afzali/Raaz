package io.raaz.messenger.ui.addcontact

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.raaz.messenger.crypto.CryptoManager
import io.raaz.messenger.crypto.QrCodeHelper
import io.raaz.messenger.data.db.RaazDatabase
import io.raaz.messenger.data.db.dao.ContactDao
import io.raaz.messenger.data.db.dao.SessionDao
import io.raaz.messenger.data.db.dao.SettingsDao
import io.raaz.messenger.data.preferences.RaazPreferences
import io.raaz.messenger.data.repository.ContactRepository
import io.raaz.messenger.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddContactViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "AddContactVM"

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    private val _myQrBitmap = MutableLiveData<Bitmap?>()
    val myQrBitmap: LiveData<Bitmap?> = _myQrBitmap

    private val _myInviteCode = MutableLiveData<String?>()
    val myInviteCode: LiveData<String?> = _myInviteCode

    private var db: RaazDatabase? = null

    fun setDb(database: RaazDatabase) {
        db = database
        AppLogger.d(TAG, "DB set: $database")
    }

    fun addContactFromCode(code: String, displayName: String) {
        AppLogger.d(TAG, "addContactFromCode called, code length=${code.length}, db=${db != null}")

        if (code.isBlank()) {
            _state.value = State.Error("empty_code")
            return
        }

        val currentDb = db
        if (currentDb == null) {
            AppLogger.e(TAG, "DB is null — cannot add contact")
            _state.value = State.Error("db_not_ready")
            return
        }

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // Log full code in chunks (logcat truncates long lines)
                    AppLogger.d(TAG, "Code length=${code.length}")
                    code.chunked(200).forEachIndexed { i, chunk ->
                        AppLogger.d(TAG, "Code[$i]: $chunk")
                    }
                    // Log char codes of first/last few chars to detect invisible chars
                    val firstChars = code.take(5).map { it.code }
                    val lastChars = code.takeLast(5).map { it.code }
                    AppLogger.d(TAG, "First char codes: $firstChars")
                    AppLogger.d(TAG, "Last char codes:  $lastChars")

                    // Try decode manually to see exact error
                    try {
                        val cleaned = code.replace(Regex("\\s"), "")
                        AppLogger.d(TAG, "Cleaned length=${cleaned.length}")
                        val bytes = java.util.Base64.getUrlDecoder().decode(cleaned)
                        AppLogger.d(TAG, "Base64 decoded OK, json=${String(bytes).take(100)}")
                    } catch (decodeEx: Exception) {
                        AppLogger.e(TAG, "Base64 decode failed: ${decodeEx.message}")
                        // Try standard decoder
                        try {
                            val cleaned = code.replace(Regex("\\s"), "")
                            val bytes = java.util.Base64.getDecoder().decode(cleaned)
                            AppLogger.d(TAG, "Standard B64 decoded OK, json=${String(bytes).take(100)}")
                        } catch (e2: Exception) {
                            AppLogger.e(TAG, "Standard B64 also failed: ${e2.message}")
                        }
                    }

                    val payload = QrCodeHelper.decodeContact(code)
                    AppLogger.d(TAG, "Decoded payload: $payload")

                    if (payload == null) {
                        AppLogger.e(TAG, "decodeContact returned null for code: ${code.take(80)}")
                        _state.postValue(State.Error("invalid_code"))
                        return@withContext
                    }

                    val contact = QrCodeHelper.payloadToContact(payload, displayName)
                    AppLogger.i(TAG, "Adding contact: ${contact.id} / ${contact.displayName}")

                    ContactRepository(ContactDao(currentDb.db), SessionDao(currentDb.db))
                        .add(contact)

                    _state.postValue(State.Success)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to add contact: ${e.message}", e)
                    _state.postValue(State.Error(e.message ?: "unknown"))
                }
            }
        }
    }

    fun loadMyQr() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val prefs = RaazPreferences(getApplication())
                    val userId = prefs.userId
                    val deviceId = prefs.deviceId

                    AppLogger.d(TAG, "loadMyQr: userId=$userId, deviceId=$deviceId, db=${db != null}")

                    if (userId == null || deviceId == null) {
                        AppLogger.e(TAG, "userId or deviceId is null — prefs not set")
                        return@withContext
                    }

                    val currentDb = db ?: run {
                        AppLogger.e(TAG, "DB is null in loadMyQr")
                        return@withContext
                    }

                    val settings = SettingsDao(currentDb.db).get()
                    val serverUrl = settings.serverUrl
                    AppLogger.d(TAG, "serverUrl=$serverUrl, setupComplete=${settings.setupComplete}")

                    var publicKey = CryptoManager.getPublicKeyB64()
                    AppLogger.d(TAG, "publicKey in memory: ${publicKey?.take(10)}")

                    if (publicKey == null && settings.privateKeyEncrypted != null && settings.publicKey != null) {
                        CryptoManager.loadKeys(settings.privateKeyEncrypted, settings.publicKey)
                        publicKey = CryptoManager.getPublicKeyB64()
                        AppLogger.d(TAG, "publicKey loaded from DB: ${publicKey?.take(10)}")
                    }

                    if (publicKey == null) {
                        AppLogger.e(TAG, "No public key available — cannot generate QR")
                        return@withContext
                    }

                    val encoded = QrCodeHelper.encodeContact(userId, deviceId, publicKey, serverUrl)
                    AppLogger.i(TAG, "QR generated, code length=${encoded.length}")

                    val bitmap = QrCodeHelper.generateQrBitmap(encoded)
                    _myQrBitmap.postValue(bitmap)
                    _myInviteCode.postValue(encoded)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to generate QR: ${e.message}", e)
                }
            }
        }
    }

    sealed class State {
        object Idle : State()
        object Success : State()
        data class Error(val reason: String) : State()
    }
}
