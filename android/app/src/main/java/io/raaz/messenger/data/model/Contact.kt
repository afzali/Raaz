package io.raaz.messenger.data.model

data class Contact(
    val id: String,
    val displayName: String,
    val publicKey: String,
    val deviceId: String,
    val serverUrl: String,
    val addedAt: Long,
    val lastSeen: Long?,
    val isVerified: Boolean
)
