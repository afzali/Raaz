package io.raaz.messenger.data.model

data class Session(
    val id: String,
    val contactId: String,
    val createdAt: Long,
    val lastMessageAt: Long? = null,
    val messageTtlMs: Long = 86_400_000L,
    val sensitivity: Int = 0,
    val notifBehavior: Int = 0,
    // Joined fields (not in DB, filled by query)
    val contactName: String = "",
    val contactPublicKey: String = "",
    val contactDeviceId: String = "",
    val lastMessagePreview: String = "",
    val unreadCount: Int = 0
)
