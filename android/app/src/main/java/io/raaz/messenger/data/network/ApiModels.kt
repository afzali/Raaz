package io.raaz.messenger.data.network

import com.google.gson.annotations.SerializedName

// ─── Requests ───────────────────────────────────────────────────────────────

data class RegisterDeviceRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("public_key") val publicKey: String
)

data class PushMessageRequest(
    @SerializedName("message_id") val messageId: String,
    @SerializedName("recipient_device_id") val recipientDeviceId: String,
    @SerializedName("sender_device_id") val senderDeviceId: String,
    @SerializedName("ciphertext") val ciphertext: String,
    @SerializedName("ttl_seconds") val ttlSeconds: Long = 86400
)

// ─── Responses ───────────────────────────────────────────────────────────────

data class RegisterDeviceResponse(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("token") val token: String
)

data class PushMessageResponse(
    @SerializedName("server_message_id") val serverMessageId: String,
    @SerializedName("expires_at") val expiresAt: Long
)

data class PullMessagesResponse(
    @SerializedName("messages") val messages: List<ServerMessage>
)

data class ServerMessage(
    @SerializedName("server_message_id") val serverMessageId: String,
    @SerializedName("sender_device_id") val senderDeviceId: String,
    @SerializedName("ciphertext") val ciphertext: String,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("expires_at") val expiresAt: Long
)

data class HealthResponse(
    @SerializedName("status") val status: String,
    @SerializedName("version") val version: String,
    @SerializedName("time") val time: Long
)

data class ReceiptsResponse(
    @SerializedName("receipts") val receipts: List<DeliveryReceipt>
)

data class DeliveryReceipt(
    @SerializedName("message_id") val messageId: String,
    @SerializedName("acked_at") val ackedAt: Long
)
