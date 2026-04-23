package io.raaz.messenger.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.raaz.messenger.util.AppLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages a biometric-protected key in Android Keystore for encrypting/decrypting
 * the user password so it can be recovered via biometric authentication.
 */
object BiometricKeyStore {

    private const val TAG = "BiometricKeyStore"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "raaz_biometric_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val PREFS_NAME = "raaz_biometric_prefs"
    private const val PREF_ENCRYPTED_PASSWORD = "encrypted_password"
    private const val PREF_IV = "iv"

    fun isBiometricKeySetup(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(PREF_ENCRYPTED_PASSWORD) && prefs.contains(PREF_IV)
    }

    /**
     * Generate or retrieve the biometric-protected AES key.
     * This key requires user authentication (biometric) to use.
     */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Create a cipher for encryption (no biometric needed for encrypt with fresh IV).
     */
    fun getEncryptCipher(): Cipher {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /**
     * Create a cipher for decryption using stored IV (biometric required to actually use).
     */
    fun getDecryptCipher(context: Context): Cipher? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ivB64 = prefs.getString(PREF_IV, null) ?: return null
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            cipher
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create decrypt cipher: ${e.message}", e)
            null
        }
    }

    /**
     * Encrypt and store the password using the biometric-authenticated cipher.
     */
    fun storeEncryptedPassword(context: Context, cipher: Cipher, password: String) {
        try {
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PREF_ENCRYPTED_PASSWORD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .apply()
            AppLogger.i(TAG, "Password encrypted and stored with biometric key")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to store encrypted password: ${e.message}", e)
            throw e
        }
    }

    /**
     * Decrypt and retrieve the password using the biometric-authenticated cipher.
     */
    fun retrieveDecryptedPassword(context: Context, cipher: Cipher): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val encryptedB64 = prefs.getString(PREF_ENCRYPTED_PASSWORD, null) ?: return null
            val encrypted = Base64.decode(encryptedB64, Base64.NO_WRAP)
            val decrypted = cipher.doFinal(encrypted)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to decrypt password: ${e.message}", e)
            null
        }
    }

    /**
     * Clear stored biometric data (when user disables biometric or wipes data).
     */
    fun clear(context: Context) {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            AppLogger.i(TAG, "Biometric key and encrypted password cleared")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear biometric data: ${e.message}", e)
        }
    }
}
