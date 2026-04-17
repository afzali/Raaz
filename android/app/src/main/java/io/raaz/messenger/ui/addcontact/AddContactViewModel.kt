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

    fun addContactFromCode(code: String, displayName: String) {
        if (code.isBlank()) {
            _state.value = State.Error("empty_code")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val payload = QrCodeHelper.decodeContact(code)
                    if (payload == null) {
                        _state.postValue(State.Error("invalid_code"))
                        return@withContext
                    }
                    val contact = QrCodeHelper.payloadToContact(payload, displayName)
                    // TODO: get db instance from shared ViewModel
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
                    val publicKey = CryptoManager.getPublicKeyB64() ?: return@withContext
                    val prefs = RaazPreferences(getApplication())
                    val userId = prefs.userId ?: return@withContext
                    val deviceId = prefs.deviceId ?: return@withContext
                    // TODO: get server URL from settings
                    val serverUrl = "https://relay.raaz.io"
                    val encoded = QrCodeHelper.encodeContact(userId, deviceId, publicKey, serverUrl)
                    val bitmap = QrCodeHelper.generateQrBitmap(encoded)
                    _myQrBitmap.postValue(bitmap)
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
