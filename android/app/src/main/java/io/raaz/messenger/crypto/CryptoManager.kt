package io.raaz.messenger.crypto

import android.content.Context
import io.raaz.messenger.util.AppLogger

/**
 * Central façade for all cryptographic operations.
 * All callers use this object — direct use of KeyManager/MessageCrypto/PasswordManager
 * is only for internal crypto implementation.
 */
object CryptoManager {

    private const val TAG = "CryptoManager"

    // Cached private key (in-memory only, cleared on lock)
    private var cachedPrivateKey: ByteArray? = null
    private var cachedPublicKey: ByteArray? = null

    fun loadKeys(privateKeyB64: String, publicKeyB64: String) {
        cachedPrivateKey = KeyManager.keyFromB64(privateKeyB64)
        cachedPublicKey = KeyManager.keyFromB64(publicKeyB64)
        AppLogger.d(TAG, "Keys loaded into memory")
    }

    fun clearKeys() {
        cachedPrivateKey?.fill(0)
        cachedPublicKey?.fill(0)
        cachedPrivateKey = null
        cachedPublicKey = null
        AppLogger.d(TAG, "Keys cleared from memory")
    }

    fun isReady(): Boolean = cachedPrivateKey != null

    fun getPublicKeyB64(): String? = cachedPublicKey?.let {
        java.util.Base64.getEncoder().encodeToString(it)
    }

    /**
     * Encrypt a message for a given recipient's Base64 public key.
     */
    fun encryptMessage(plaintext: String, recipientPublicKeyB64: String): String {
        val recipientPub = KeyManager.publicKeyFromB64(recipientPublicKeyB64)
        return MessageCrypto.encrypt(plaintext, recipientPub)
    }

    /**
     * Decrypt a message payload using the currently-loaded private key.
     */
    fun decryptMessage(payloadB64: String): String? {
        val priv = cachedPrivateKey ?: run {
            AppLogger.e(TAG, "Cannot decrypt — no private key loaded (app locked?)")
            return null
        }
        return MessageCrypto.decrypt(payloadB64, priv)
    }

    /**
     * Generate new identity keypair and return as (privateKeyB64, publicKeyB64).
     */
    fun generateIdentityKeyPair(): Pair<String, String> {
        val kp = KeyManager.generateIdentityKeyPair()
        return Pair(kp.privateKeyB64, kp.publicKeyB64)
    }

    /**
     * Derive DB key from password + salt stored in context preferences.
     */
    suspend fun deriveDbKey(context: Context, password: String): String {
        val salt = PasswordManager.loadSalt(context)
            ?: throw IllegalStateException("No salt found — run setup first")
        return PasswordManager.deriveKey(password, salt)
    }

    /**
     * Create a new salt, derive DB key, return it. Called once during setup.
     */
    suspend fun setupNewPassword(context: Context, password: String): String {
        val salt = PasswordManager.generateSalt()
        PasswordManager.saveSalt(context, salt)
        return PasswordManager.deriveKey(password, salt)
    }

    fun generateDeviceIds(): Pair<String, String> =
        Pair(KeyManager.generateUserId(), KeyManager.generateDeviceId())

    fun generateMessageId(): String = KeyManager.generateMessageId()
    fun generateSessionId(): String = KeyManager.generateSessionId()
}
