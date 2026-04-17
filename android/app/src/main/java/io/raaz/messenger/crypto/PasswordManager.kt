package io.raaz.messenger.crypto

import android.content.Context
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import io.raaz.messenger.util.AppLogger
import java.security.SecureRandom
import java.util.Base64

object PasswordManager {

    private const val TAG = "PasswordManager"

    // Argon2id params — memory-hard to resist GPU brute force
    private const val OPS_LIMIT = 3L
    private const val MEM_LIMIT = 65536L  // 64 MB
    private const val KEY_LEN = 32

    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }
    private val random = SecureRandom()

    // Shared preferences key for the Argon2 salt (not the DB key — salt is public)
    private const val PREF_NAME = "raaz_pw_meta"
    private const val KEY_SALT = "argon2_salt"

    fun generateSalt(): ByteArray {
        val salt = ByteArray(PwHash.SALTBYTES)
        random.nextBytes(salt)
        return salt
    }

    fun saveSalt(context: Context, salt: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(salt)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SALT, encoded).apply()
    }

    fun loadSalt(context: Context): ByteArray? {
        val encoded = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SALT, null) ?: return null
        return Base64.getDecoder().decode(encoded)
    }

    fun hasSalt(context: Context): Boolean = loadSalt(context) != null

    /**
     * Derives a 32-byte key from password using Argon2id.
     * Returns hex string suitable as SQLCipher PRAGMA key.
     */
    fun deriveKey(password: String, salt: ByteArray): String {
        AppLogger.d(TAG, "Deriving key with Argon2id (this may take a moment…)")
        val keyBytes = ByteArray(KEY_LEN)
        val pwBytes = password.toByteArray(Charsets.UTF_8)
        val result = sodium.cryptoPwHash(
            keyBytes, KEY_LEN,
            pwBytes, pwBytes.size,
            salt,
            OPS_LIMIT, NativeLong(MEM_LIMIT),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13
        )
        if (!result) throw IllegalStateException("Argon2id key derivation failed")
        // SQLCipher accepts "x'<hex>'" format for raw key
        val hex = keyBytes.joinToString("") { "%02x".format(it) }
        AppLogger.d(TAG, "Key derived successfully")
        return "x'$hex'"
    }

    fun generateAuthDbKey(context: Context): String {
        // Device-bound key: derived from package name + Android ID (not user password)
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "raaz_fallback_id"
        val seed = "${context.packageName}:auth_db:$androidId"
        val hash = seed.toByteArray().let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it)
        }
        val hex = hash.joinToString("") { "%02x".format(it) }
        return "x'$hex'"
    }
}
