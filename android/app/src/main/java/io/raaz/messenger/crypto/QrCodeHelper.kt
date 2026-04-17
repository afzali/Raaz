package io.raaz.messenger.crypto

import android.graphics.Bitmap
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import io.raaz.messenger.data.model.Contact
import java.util.Base64

object QrCodeHelper {

    private val gson = Gson()

    data class ContactPayload(
        val userId: String,
        val deviceId: String,
        val publicKey: String,
        val serverUrl: String
    )

    fun encodeContact(userId: String, deviceId: String, publicKey: String, serverUrl: String): String {
        val payload = ContactPayload(userId, deviceId, publicKey, serverUrl)
        val json = gson.toJson(payload)
        // URL-safe Base64 (no padding) — safe to paste, share, and scan
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    fun decodeContact(encoded: String): ContactPayload? {
        return try {
            // Accept URL-safe (no padding), URL-safe (padded), and standard Base64
            val cleaned = encoded.trim()
            val bytes = try {
                Base64.getUrlDecoder().decode(cleaned)
            } catch (_: Exception) {
                Base64.getDecoder().decode(cleaned)
            }
            val json = String(bytes)
            val payload = gson.fromJson(json, ContactPayload::class.java)
            // Validate required fields
            if (payload.userId.isBlank() || payload.publicKey.isBlank()) null else payload
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
