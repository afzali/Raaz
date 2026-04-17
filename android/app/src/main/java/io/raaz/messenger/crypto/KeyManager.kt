package io.raaz.messenger.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import io.raaz.messenger.util.AppLogger
import java.util.Base64
import java.util.UUID

object KeyManager {

    private const val TAG = "KeyManager"
    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

    data class KeyPair(
        val publicKey: ByteArray,
        val privateKey: ByteArray
    ) {
        val publicKeyB64: String get() = Base64.getEncoder().encodeToString(publicKey)
        val privateKeyB64: String get() = Base64.getEncoder().encodeToString(privateKey)
    }

    fun generateIdentityKeyPair(): KeyPair {
        val pubKey = ByteArray(sodium.cryptoBoxPublicKeyBytes())
        val prvKey = ByteArray(sodium.cryptoBoxSecretKeyBytes())
        sodium.cryptoBoxKeypair(pubKey, prvKey)
        AppLogger.i(TAG, "Identity X25519 keypair generated")
        return KeyPair(pubKey, prvKey)
    }

    fun generateEphemeralKeyPair(): KeyPair {
        val pubKey = ByteArray(sodium.cryptoBoxPublicKeyBytes())
        val prvKey = ByteArray(sodium.cryptoBoxSecretKeyBytes())
        sodium.cryptoBoxKeypair(pubKey, prvKey)
        return KeyPair(pubKey, prvKey)
    }

    fun generateUserId(): String = UUID.randomUUID().toString()
    fun generateDeviceId(): String = UUID.randomUUID().toString()
    fun generateMessageId(): String = UUID.randomUUID().toString()
    fun generateSessionId(): String = UUID.randomUUID().toString()

    fun publicKeyFromB64(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    fun x25519SharedSecret(myPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray {
        val shared = ByteArray(sodium.cryptoScalarmultBytes())
        val result = sodium.cryptoScalarmult(shared, myPrivateKey, theirPublicKey)
        if (!result) throw IllegalStateException("X25519 ECDH failed")
        return shared
    }
}
