package io.raaz.messenger.crypto

import android.content.Context
import io.raaz.messenger.util.AppLogger

object CryptoManager {

    private val TAG = AppLogger.Cat.CRYPTO

    private var cachedPrivateKey: ByteArray? = null
    private var cachedPublicKey: ByteArray? = null

    fun loadKeys(privateKeyB64: String, publicKeyB64: String) {
        cachedPrivateKey = KeyManager.keyFromB64(privateKeyB64)
        cachedPublicKey = KeyManager.keyFromB64(publicKeyB64)
        AppLogger.i(TAG, "Identity keys loaded into memory (pub: ${publicKeyB64.take(8)}...)")
    }

    fun clearKeys() {
        cachedPrivateKey?.fill(0)
        cachedPublicKey?.fill(0)
        cachedPrivateKey = null
        cachedPublicKey = null
        AppLogger.i(TAG, "Keys wiped from memory")
    }

    fun isReady(): Boolean = cachedPrivateKey != null

    fun getPublicKeyB64(): String? = cachedPublicKey?.let {
        java.util.Base64.getEncoder().encodeToString(it)
    }

    fun encryptMessage(plaintext: String, recipientPublicKeyB64: String): String {
        AppLogger.d(TAG, "Encrypting message (${plaintext.length} chars) for ${recipientPublicKeyB64.take(8)}...")
        val recipientPub = KeyManager.publicKeyFromB64(recipientPublicKeyB64)
        val ct = MessageCrypto.encrypt(plaintext, recipientPub)
        AppLogger.d(TAG, "Encryption OK — payload ${ct.length} chars")
        return ct
    }

    fun decryptMessage(payloadB64: String): String? {
        val priv = cachedPrivateKey ?: run {
            AppLogger.e(TAG, "Decrypt failed — no private key loaded (app locked?)")
            return null
        }
        AppLogger.d(TAG, "Decrypting payload (${payloadB64.length} chars)...")
        val pt = MessageCrypto.decrypt(payloadB64, priv)
        if (pt == null) AppLogger.e(TAG, "Decryption returned null — bad ciphertext or wrong key")
        else AppLogger.d(TAG, "Decryption OK — plaintext ${pt.length} chars")
        return pt
    }

    fun generateIdentityKeyPair(): Pair<String, String> {
        AppLogger.i(TAG, "Generating new X25519 identity keypair...")
        val kp = KeyManager.generateIdentityKeyPair()
        AppLogger.i(TAG, "Keypair generated (pub: ${kp.publicKeyB64.take(8)}...)")
        return Pair(kp.privateKeyB64, kp.publicKeyB64)
    }

    suspend fun deriveDbKey(context: Context, password: String): String {
        AppLogger.i(TAG, "Deriving DB key (Argon2id) from password...")
        val salt = PasswordManager.loadSalt(context)
            ?: throw IllegalStateException("No salt found — run setup first")
        val key = PasswordManager.deriveKey(password, salt)
        AppLogger.i(TAG, "DB key derived (${key.length} chars)")
        return key
    }

    suspend fun setupNewPassword(context: Context, password: String): String {
        AppLogger.i(TAG, "Generating new Argon2id salt and deriving DB key...")
        val salt = PasswordManager.generateSalt()
        PasswordManager.saveSalt(context, salt)
        val key = PasswordManager.deriveKey(password, salt)
        AppLogger.i(TAG, "Setup: DB key ready (${key.length} chars)")
        return key
    }

    fun generateDeviceIds(): Pair<String, String> {
        val ids = Pair(KeyManager.generateUserId(), KeyManager.generateDeviceId())
        AppLogger.i(TAG, "Generated userId=${ids.first.take(8)}..., deviceId=${ids.second.take(8)}...")
        return ids
    }

    fun generateMessageId(): String = KeyManager.generateMessageId()
    fun generateSessionId(): String = KeyManager.generateSessionId()
}
