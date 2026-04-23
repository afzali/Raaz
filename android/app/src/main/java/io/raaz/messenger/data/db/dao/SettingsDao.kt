package io.raaz.messenger.data.db.dao

import android.content.ContentValues
import io.raaz.messenger.data.model.AppSettings
import net.zetetic.database.sqlcipher.SQLiteDatabase

class SettingsDao(private val db: SQLiteDatabase) {

    fun get(): AppSettings {
        val c = db.rawQuery(
            "SELECT lock_timeout_ms, language, theme, server_url, notif_enabled, setup_complete, sync_interval_minutes, sync_enabled, biometric_enabled, user_id, device_id, public_key, private_key_encrypted FROM app_settings WHERE id=1",
            null
        )
        return c.use {
            if (it.moveToFirst()) AppSettings(
                lockTimeoutMs = it.getLong(0),
                language = it.getString(1),
                theme = it.getInt(2),
                serverUrl = it.getString(3),
                notifEnabled = it.getInt(4) == 1,
                setupComplete = it.getInt(5) == 1,
                syncIntervalMinutes = it.getInt(6),
                syncEnabled = it.getInt(7) == 1,
                biometricEnabled = it.getInt(8) == 1,
                userId = if (it.isNull(9)) null else it.getString(9),
                deviceId = if (it.isNull(10)) null else it.getString(10),
                publicKey = if (it.isNull(11)) null else it.getString(11),
                privateKeyEncrypted = if (it.isNull(12)) null else it.getString(12)
            ) else AppSettings()
        }
    }

    fun save(settings: AppSettings) {
        val cv = ContentValues().apply {
            put("lock_timeout_ms", settings.lockTimeoutMs)
            put("language", settings.language)
            put("theme", settings.theme)
            put("server_url", settings.serverUrl)
            put("notif_enabled", if (settings.notifEnabled) 1 else 0)
            put("setup_complete", if (settings.setupComplete) 1 else 0)
            put("sync_interval_minutes", settings.syncIntervalMinutes)
            put("sync_enabled", if (settings.syncEnabled) 1 else 0)
            put("biometric_enabled", if (settings.biometricEnabled) 1 else 0)
            settings.userId?.let { put("user_id", it) }
            settings.deviceId?.let { put("device_id", it) }
            settings.publicKey?.let { put("public_key", it) }
            settings.privateKeyEncrypted?.let { put("private_key_encrypted", it) }
        }
        db.update("app_settings", cv, "id=1", null)
    }

    fun updateSyncInterval(minutes: Int) {
        val cv = ContentValues().apply { put("sync_interval_minutes", minutes) }
        db.update("app_settings", cv, "id=1", null)
    }

    fun updateSyncEnabled(enabled: Boolean) {
        val cv = ContentValues().apply { put("sync_enabled", if (enabled) 1 else 0) }
        db.update("app_settings", cv, "id=1", null)
    }

    fun updateBiometricEnabled(enabled: Boolean) {
        val cv = ContentValues().apply { put("biometric_enabled", if (enabled) 1 else 0) }
        db.update("app_settings", cv, "id=1", null)
    }

    fun updateLanguage(lang: String) {
        val cv = ContentValues().apply { put("language", lang) }
        db.update("app_settings", cv, "id=1", null)
    }

    fun updateLockTimeout(ms: Long) {
        val cv = ContentValues().apply { put("lock_timeout_ms", ms) }
        db.update("app_settings", cv, "id=1", null)
    }

    fun updateServerUrl(url: String) {
        val cv = ContentValues().apply { put("server_url", url) }
        db.update("app_settings", cv, "id=1", null)
    }

    fun markSetupComplete(userId: String, deviceId: String, publicKey: String, privateKeyEnc: String, serverUrl: String) {
        val cv = ContentValues().apply {
            put("setup_complete", 1)
            put("user_id", userId)
            put("device_id", deviceId)
            put("public_key", publicKey)
            put("private_key_encrypted", privateKeyEnc)
            put("server_url", serverUrl)
        }
        db.update("app_settings", cv, "id=1", null)
    }
}
