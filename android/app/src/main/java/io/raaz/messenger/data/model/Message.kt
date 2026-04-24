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
    val nonce: String = "",
    val mediaType: Int = MEDIA_TEXT,
    val fileId: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val mimeType: String? = null,
    val localPath: String? = null,
    val uploadProgress: Int = 0,
    val downloadProgress: Int = 0,
    val durationMs: Long? = null
) {
    val isOutgoing: Boolean get() = direction == 0
    val displayText: String get() = plaintextCache ?: ""
    val isMedia: Boolean get() = mediaType != MEDIA_TEXT
    val isAudio: Boolean get() = mediaType == MEDIA_AUDIO
    val isFile: Boolean get() = mediaType == MEDIA_FILE
    val isImage: Boolean get() = mediaType == MEDIA_IMAGE

    companion object {
        const val DIR_OUTGOING = 0
        const val DIR_INCOMING = 1
        const val STATUS_QUEUED = 0
        const val STATUS_SENT = 1
        const val STATUS_DELIVERED = 2
        const val STATUS_CONFIRMED = 3
        const val STATUS_EXPIRED = 4
        const val STATUS_FAILED = 5

        // Media types
        const val MEDIA_TEXT = 0
        const val MEDIA_IMAGE = 1
        const val MEDIA_AUDIO = 2
        const val MEDIA_FILE = 3
    }
}
