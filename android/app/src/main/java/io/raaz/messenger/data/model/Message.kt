package io.raaz.messenger.data.model

data class Message(
    val id: String,
    val sessionId: String,
    val direction: Int,   // 0 = outgoing, 1 = incoming
    val ciphertext: String,
    val plaintextCache: String?,
    val status: Int,      // 0=queued,1=sent,2=delivered,3=confirmed,4=expired
    val createdAt: Long,
    val expiresAt: Long?,
    val serverMsgId: String?,
    val nonce: String = ""
) {
    val isOutgoing: Boolean get() = direction == 0
    val displayText: String get() = plaintextCache ?: ""

    companion object {
        const val DIR_OUTGOING = 0
        const val DIR_INCOMING = 1
        const val STATUS_QUEUED = 0
        const val STATUS_SENT = 1
        const val STATUS_DELIVERED = 2
        const val STATUS_CONFIRMED = 3
        const val STATUS_EXPIRED = 4
    }
}
