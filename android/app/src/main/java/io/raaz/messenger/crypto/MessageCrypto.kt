package io.raaz.messenger.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.AEAD
import io.raaz.messenger.util.AppLogger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ECIES-style per-message encryption using X25519 + HKDF-SHA256 + XChaCha20-Poly1305.
 *
 * Wire format: Base64( eph_pub[32] ++ nonce[24] ++ ciphertext )
 */
object MessageCrypto {

    private const val TAG = "MessageCrypto"
    private const val HKDF_INFO = "raaz-msg-v1"
    private const val KEY_LEN = 32

    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }
    private val random = SecureRandom()

    /**
     * Encrypts [plaintext] for a recipient identified by [recipientPublicKey].
     * Returns a Base64-encoded payload (eph_pub ++ nonce ++ ciphertext).
     */
    fun encrypt(plaintext: String, recipientPublicKey: ByteArray): String {
        val ephKeyPair = KeyManager.generateEphemeralKeyPair()
        val shared = KeyManager.x25519SharedSecret(ephKeyPair.privateKey, recipientPublicKey)
        val msgKey = hkdf(shared, HKDF_INFO, KEY_LEN)

        val nonce24 = ByteArray(AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        random.nextBytes(nonce24)

        val ptBytes = plaintext.encodeToByteArray()
        val ctBytes = ByteArray(ptBytes.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)

        val result = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ctBytes, null, ptBytes, ptBytes.size.toLong(),
            null, 0L, null, nonce24, msgKey
        )
        if (!result) throw IllegalStateException("XChaCha20-Poly1305 encryption failed")

        val payload = ephKeyPair.publicKey + nonce24 + ctBytes
        AppLogger.d(TAG, "Encrypted ${ptBytes.size} bytes → ${payload.size} bytes payload")
        return Base64.getEncoder().encodeToString(payload)
    }

    /**
     * Decrypts a payload (produced by [encrypt]) using the recipient's [privateKey].
     * Returns plaintext string or null if decryption fails.
     */
    fun decrypt(payloadB64: String, privateKey: ByteArray): String? {
        return try {
            val payload = Base64.getDecoder().decode(payloadB64)
            if (payload.size < 32 + AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES + AEAD.XCHACHA20POLY1305_IETF_ABYTES) {
                AppLogger.w(TAG, "Payload too short to decrypt")
                return null
            }
            val nonceLen = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES
            val ephPub = payload.slice(0 until 32).toByteArray()
            val nonce24 = payload.slice(32 until 32 + nonceLen).toByteArray()
            val ctBytes = payload.slice(32 + nonceLen until payload.size).toByteArray()

            val shared = KeyManager.x25519SharedSecret(privateKey, ephPub)
            val msgKey = hkdf(shared, HKDF_INFO, KEY_LEN)

            val ptBytes = ByteArray(ctBytes.size - AEAD.XCHACHA20POLY1305_IETF_ABYTES)
            val result = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                ptBytes, null, null, ctBytes, ctBytes.size.toLong(),
                null, 0L, nonce24, msgKey
            )
            if (!result) {
                AppLogger.w(TAG, "Decryption failed (wrong key or corrupted payload)")
                null
            } else {
                ptBytes.decodeToString()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Decryption exception: ${e.message}", e)
            null
        }
    }

    /**
     * Minimal HKDF-SHA256 (extract + expand).
     */
    private fun hkdf(ikm: ByteArray, info: String, len: Int): ByteArray {
        // Extract: PRK = HMAC-SHA256(salt=0x00*32, IKM)
        val salt = ByteArray(32)
        val prk = hmacSha256(salt, ikm)
        // Expand: T(1) = HMAC-SHA256(PRK, info || 0x01)
        val infoBytes = info.encodeToByteArray()
        val t1Input = infoBytes + byteArrayOf(0x01)
        val t1 = hmacSha256(prk, t1Input)
        return t1.take(len).toByteArray()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
