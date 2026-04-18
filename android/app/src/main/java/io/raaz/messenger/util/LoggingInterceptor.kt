package io.raaz.messenger.util

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.http.promisesBody

class LoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val tag = AppLogger.Cat.NET

        // ── Request ──────────────────────────────────────────────────────────
        AppLogger.i(tag, "→ ${request.method} ${request.url}")
        request.headers.forEach { (name, value) ->
            if (name.equals("Authorization", ignoreCase = true)) {
                AppLogger.d(tag, "  Header: $name: Bearer ***")
            } else {
                AppLogger.d(tag, "  Header: $name: $value")
            }
        }
        request.body?.let { body ->
            try {
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                val bodyStr = buffer.readUtf8()
                if (bodyStr.length > 500) {
                    AppLogger.d(tag, "  Body(${bodyStr.length}): ${bodyStr.take(500)}…")
                } else {
                    AppLogger.d(tag, "  Body: $bodyStr")
                }
            } catch (_: Exception) { }
        }

        // ── Execute ──────────────────────────────────────────────────────────
        val startMs = System.currentTimeMillis()
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            AppLogger.e(tag, "✗ ${request.method} ${request.url} — ${e.message}", e)
            throw e
        }
        val elapsed = System.currentTimeMillis() - startMs

        // ── Response ─────────────────────────────────────────────────────────
        val statusEmoji = if (response.isSuccessful) "✓" else "✗"
        AppLogger.i(tag, "$statusEmoji ${response.code} ${request.method} ${request.url} (${elapsed}ms)")

        if (response.promisesBody()) {
            val bodyStr = response.peekBody(4096).string()
            if (bodyStr.length > 500) {
                AppLogger.d(tag, "  Response(${bodyStr.length}): ${bodyStr.take(500)}…")
            } else {
                AppLogger.d(tag, "  Response: $bodyStr")
            }
        }

        if (!response.isSuccessful) {
            AppLogger.w(tag, "  !! Server error ${response.code} for ${request.url}")
        }

        return response
    }
}
