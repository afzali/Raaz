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

    private val TAG = AppLogger.Cat.QR

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    private val _myQrBitmap = MutableLiveData<Bitmap?>()
    val myQrBitmap: LiveData<Bitmap?> = _myQrBitmap

    private val _myInviteCode = MutableLiveData<String?>()
    val myInviteCode: LiveData<String?> = _myInviteCode

    private var db: RaazDatabase? = null

    fun setDb(database: RaazDatabase) {
        db = database
        AppLogger.d(TAG, "DB set")
    }

    fun addContactFromCode(code: String, displayName: String) {
        AppLogger.i(TAG, "addContactFromCode: code.length=${code.length}, displayName='$displayName'")

        if (code.isBlank()) {
            AppLogger.w(TAG, "Empty code — rejecting")
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
                    val payload = QrCodeHelper.decodeContact(code)

                    if (payload == null) {
                        AppLogger.e(TAG, "decodeContact returned null — invalid invite code")
                        _state.postValue(State.Error("invalid_code"))
                        return@withContext
                    }

                    AppLogger.i(TAG, "Decoded: userId=${payload.userId.take(8)}..., deviceId=${payload.deviceId.take(8)}..., server=${payload.serverUrl}")
                    val contact = QrCodeHelper.payloadToContact(payload, displayName)
                    AppLogger.i(TAG, "Saving contact: ${contact.displayName} (${contact.id.take(8)}...)")

                    ContactRepository(ContactDao(currentDb.db), SessionDao(currentDb.db))
                        .add(contact)

                    AppLogger.i(TAG, "Contact saved successfully")
                    _state.postValue(State.Success)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to add contact: ${e.message}", e)
                    _state.postValue(State.Error(e.message ?: "unknown"))
                }
            }
        }
    }

    fun loadMyQr() {
        AppLogger.i(TAG, "loadMyQr: generating own QR/invite code")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val prefs = RaazPreferences(getApplication())
                    val userId = prefs.userId
                    val deviceId = prefs.deviceId

                    if (userId == null || deviceId == null) {
                        AppLogger.e(TAG, "userId or deviceId is null — prefs not set after setup?")
                        return@withContext
                    }

                    val currentDb = db ?: run {
                        AppLogger.e(TAG, "DB is null in loadMyQr")
                        return@withContext
                    }

                    val settings = SettingsDao(currentDb.db).get()
                    val serverUrl = settings.serverUrl
                    AppLogger.d(TAG, "serverUrl=$serverUrl")

                    var publicKey = CryptoManager.getPublicKeyB64()

                    if (publicKey == null && settings.privateKeyEncrypted != null && settings.publicKey != null) {
                        CryptoManager.loadKeys(settings.privateKeyEncrypted, settings.publicKey)
                        publicKey = CryptoManager.getPublicKeyB64()
                        AppLogger.d(TAG, "Keys loaded from DB for QR generation")
                    }

                    if (publicKey == null) {
                        AppLogger.e(TAG, "No public key available — cannot generate QR")
                        return@withContext
                    }

                    val encoded = QrCodeHelper.encodeContact(userId, deviceId, publicKey, serverUrl)
                    AppLogger.i(TAG, "Invite code generated (${encoded.length} chars)")

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
