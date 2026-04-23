package io.raaz.messenger.data.model

data class PendingMessage(
    val id: Long = 0,
    val serverMsgId: String,
    val senderDeviceId: String,
    val ciphertext: String,
    val plaintextCache: String,
    val createdAt: Long,
    val expiresAt: Long?,
    val receivedAt: Long = System.currentTimeMillis()
) {
    // Preview text for notification/list display
    val previewText: String get() = if (plaintextCache.length > 50) 
        plaintextCache.take(50) + "..." 
    else 
        plaintextCache
}
