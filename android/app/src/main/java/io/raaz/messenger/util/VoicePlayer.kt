package io.raaz.messenger.util

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * Singleton-style voice player. Only one voice message can play at a time.
 * Attach a progress listener to get playback position updates.
 */
object VoicePlayer {

    private var player: MediaPlayer? = null
    private var currentId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressListener: ((String, Int, Int) -> Unit)? = null  // (id, positionMs, durationMs)
    private var completeListener: ((String) -> Unit)? = null

    val playingId: String? get() = currentId

    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val id = currentId ?: return
            try {
                val pos = p.currentPosition
                val dur = p.duration
                progressListener?.invoke(id, pos, dur)
            } catch (_: Exception) { }
            handler.postDelayed(this, 100)
        }
    }

    fun play(
        id: String,
        path: String,
        onProgress: (String, Int, Int) -> Unit,
        onComplete: (String) -> Unit
    ): Boolean {
        stop()
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.setOnCompletionListener {
                val finishedId = currentId
                stop()
                if (finishedId != null) onComplete(finishedId)
            }
            mp.prepare()
            mp.start()
            player = mp
            currentId = id
            progressListener = onProgress
            completeListener = onComplete
            handler.post(progressRunnable)
            true
        } catch (e: Exception) {
            AppLogger.e("VoicePlayer", "play failed: ${e.message}", e)
            false
        }
    }

    fun stop() {
        handler.removeCallbacks(progressRunnable)
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) { }
        player = null
        currentId = null
        progressListener = null
        completeListener = null
    }

    fun isPlaying(id: String): Boolean = currentId == id && player?.isPlaying == true
}
