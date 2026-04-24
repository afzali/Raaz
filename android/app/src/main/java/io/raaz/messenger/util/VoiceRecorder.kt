package io.raaz.messenger.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Thin wrapper over MediaRecorder for Telegram-style voice notes.
 * Records AAC/M4A to app's cache directory. Call [start], then [stop] to get the result file.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0

    val isRecording: Boolean get() = recorder != null

    fun start(): File? {
        if (recorder != null) return null
        val dir = File(context.cacheDir, "voice_recordings").apply { mkdirs() }
        val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }

        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(64_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            outputFile = file
            startTimeMs = System.currentTimeMillis()
            AppLogger.i("VoiceRecorder", "Recording started → ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            AppLogger.e("VoiceRecorder", "Failed to start: ${e.message}", e)
            try { mr.release() } catch (_: Exception) {}
            return null
        }
    }

    /**
     * Stop and return the recorded file plus duration in ms.
     * If [discard] is true, the file is deleted.
     */
    fun stop(discard: Boolean = false): Pair<File, Long>? {
        val r = recorder ?: return null
        val file = outputFile ?: return null
        val durationMs = System.currentTimeMillis() - startTimeMs

        return try {
            r.stop()
            r.release()
            recorder = null
            outputFile = null

            if (discard || durationMs < 500L) {
                file.delete()
                AppLogger.i("VoiceRecorder", "Recording discarded (duration=${durationMs}ms)")
                null
            } else {
                AppLogger.i("VoiceRecorder", "Recording stopped (duration=${durationMs}ms, size=${file.length()}B)")
                file to durationMs
            }
        } catch (e: Exception) {
            AppLogger.e("VoiceRecorder", "Failed to stop: ${e.message}", e)
            try { r.release() } catch (_: Exception) {}
            recorder = null
            outputFile = null
            file.delete()
            null
        }
    }

    fun cancel() {
        stop(discard = true)
    }
}
