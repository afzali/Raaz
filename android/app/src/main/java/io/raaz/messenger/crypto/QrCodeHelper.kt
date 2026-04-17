package io.raaz.messenger.crypto

import android.graphics.Bitmap
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import io.raaz.messenger.data.model.Contact
import java.util.Base64

object QrCodeHelper {

    private val gson = Gson()

    data class ContactPayload(
        @SerializedName("userId")   val userId: String,
        @SerializedName("deviceId") val deviceId: String,
        @SerializedName("publicKey") val publicKey: String,
        @SerializedName("serverUrl") val serverUrl: String
    )

    fun encodeContact(userId: String, deviceId: String, publicKey: String, serverUrl: String): String {
        val payload = ContactPayload(userId, deviceId, publicKey, serverUrl)
        val json = gson.toJson(payload)
        // URL-safe Base64 (no padding) — safe to paste, share, and scan
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    fun decodeContact(encoded: String): ContactPayload? {
        return try {
            val cleaned = encoded.replace(Regex("\\s"), "")
            if (cleaned.isBlank()) return null

            val bytes = try {
                Base64.getUrlDecoder().decode(cleaned)
            } catch (_: Exception) {
                try {
                    Base64.getDecoder().decode(cleaned)
                } catch (_: Exception) {
                    Base64.getUrlDecoder().decode(cleaned.trimEnd('='))
                }
            }
            val json = String(bytes)
            val tree = gson.fromJson(json, com.google.gson.JsonObject::class.java)

            // Support both new format (userId/deviceId/publicKey/serverUrl)
            // and old minified format (a/b/c/d) produced before @SerializedName fix
            val userId    = (tree.get("userId")    ?: tree.get("a"))?.asString ?: ""
            val deviceId  = (tree.get("deviceId")  ?: tree.get("b"))?.asString ?: ""
            val publicKey = (tree.get("publicKey") ?: tree.get("c"))?.asString ?: ""
            val serverUrl = (tree.get("serverUrl") ?: tree.get("d"))?.asString ?: ""

            if (userId.isBlank() || publicKey.isBlank()) null
            else ContactPayload(userId, deviceId, publicKey, serverUrl)
        } catch (e: Exception) {
            null
        }
    }

    fun generateQrBitmap(content: String, size: Int = 512): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix: BitMatrix = MultiFormatWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size, hints
        )
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bitmap
    }

    fun payloadToContact(payload: ContactPayload, displayName: String): Contact {
        return Contact(
            id = payload.userId,
            displayName = displayName,
            publicKey = payload.publicKey,
            deviceId = payload.deviceId,
            serverUrl = payload.serverUrl,
            addedAt = System.currentTimeMillis(),
            lastSeen = null,
            isVerified = true
        )
    }
}
