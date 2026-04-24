package io.raaz.messenger.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.AEAD
import io.raaz.messenger.util.AppLogger
import java.security.SecureRandom

/**
 * Encrypts/decrypts file chunks using a shared content key (XChaCha20-Poly1305 AEAD).
 *
 * Chunk wire format: nonce[24] ++ ciphertext ++ tag[16]
 *
 * The content key itself is sent to recipient inside a regular text message
 * (encrypted via MessageCrypto), so server never sees plaintext content key or chunks.
 */
object FileCrypto {

    private const val TAG = "FileCrypto"
    const val CONTENT_KEY_SIZE = 32
    const val CHUNK_SIZE = 256 * 1024  // 256 KB per chunk

    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }
    private val random = SecureRandom()

    fun generateContentKey(): ByteArray {
        val key = ByteArray(CONTENT_KEY_SIZE)
        random.nextBytes(key)
        return key
    }

    /**
     * Encrypt a chunk of plaintext bytes with the content key.
     * Returns: nonce(24) ++ ciphertext ++ tag(16)
     */
    fun encryptChunk(plaintext: ByteArray, contentKey: ByteArray): ByteArray {
        require(contentKey.size == CONTENT_KEY_SIZE) { "Invalid content key size" }
        val nonce = ByteArray(AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        random.nextBytes(nonce)

        val ct = ByteArray(plaintext.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ct, null, plaintext, plaintext.size.toLong(),
            null, 0L, null, nonce, contentKey
        )
        if (!ok) throw IllegalStateException("Chunk encryption failed")

        return nonce + ct
    }

    /**
     * Decrypt a chunk. Input: nonce(24) ++ ciphertext ++ tag(16)
     * Returns the plaintext bytes or null if decryption fails.
     */
    fun decryptChunk(encryptedChunk: ByteArray, contentKey: ByteArray): ByteArray? {
        return try {
            val nonceLen = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES
            val tagLen = AEAD.XCHACHA20POLY1305_IETF_ABYTES
            if (encryptedChunk.size < nonceLen + tagLen) return null

            val nonce = encryptedChunk.sliceArray(0 until nonceLen)
            val ct = encryptedChunk.sliceArray(nonceLen until encryptedChunk.size)

            val pt = ByteArray(ct.size - tagLen)
            val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                pt, null, null, ct, ct.size.toLong(),
                null, 0L, nonce, contentKey
            )
            if (!ok) {
                AppLogger.w(TAG, "Chunk decryption failed (tag mismatch)")
                null
            } else pt
        } catch (e: Exception) {
            AppLogger.e(TAG, "decryptChunk exception: ${e.message}", e)
            null
        }
    }

    /**
     * Encrypt a short string (filename/mimetype) as a standalone payload.
     * Same format as chunks but expected to be small.
     */
    fun encryptString(text: String, contentKey: ByteArray): String {
        val encrypted = encryptChunk(text.toByteArray(Charsets.UTF_8), contentKey)
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

    fun decryptString(encryptedB64: String, contentKey: ByteArray): String? {
        return try {
            val bytes = android.util.Base64.decode(encryptedB64, android.util.Base64.NO_WRAP)
            decryptChunk(bytes, contentKey)?.let { String(it, Charsets.UTF_8) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "decryptString exception: ${e.message}", e)
            null
        }
    }
}
