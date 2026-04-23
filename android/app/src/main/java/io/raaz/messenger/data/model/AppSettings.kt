package io.raaz.messenger.data.model

data class AppSettings(
    val lockTimeoutMs: Long = 300_000L,
    val language: String = "fa",
    val theme: Int = 0,
    val serverUrl: String = "http://relay.rahejanan.ir",
    val notifEnabled: Boolean = true,
    val setupComplete: Boolean = false,
    val syncIntervalMinutes: Int = 15,
    val syncEnabled: Boolean = true,
    val biometricEnabled: Boolean = false,
    val userId: String? = null,
    val deviceId: String? = null,
    val publicKey: String? = null,
    val privateKeyEncrypted: String? = null
)
