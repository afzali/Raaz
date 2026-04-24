package io.raaz.messenger.ui.chat

import android.graphics.PorterDuff
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.raaz.messenger.R
import io.raaz.messenger.data.model.Message

/**
 * Binds a Message into a view_media_content include block.
 * Handles audio, file, and image rendering.
 */
object MediaBinder {

    fun bind(root: View, msg: Message, cb: MessageAdapter.Callbacks) {
        // NOTE: <include android:id="@+id/media"> overrides the root id of view_media_content,
        // so `root` IS the media_container LinearLayout — don't look it up by id.
        val audioRow = root.findViewById<LinearLayout>(R.id.audio_row)
        val fileRow = root.findViewById<LinearLayout>(R.id.file_row)
        val pbMedia = root.findViewById<ProgressBar>(R.id.pb_media)

        if (!msg.isMedia) {
            root.visibility = View.GONE
            return
        }
        root.visibility = View.VISIBLE

        when {
            msg.isAudio -> bindAudio(audioRow, fileRow, pbMedia, msg, cb)
            msg.isFile || msg.isImage -> bindFile(audioRow, fileRow, pbMedia, msg, cb)
        }
    }

    private fun bindAudio(
        audioRow: LinearLayout, fileRow: LinearLayout, pb: ProgressBar,
        msg: Message, cb: MessageAdapter.Callbacks
    ) {
        audioRow.visibility = View.VISIBLE
        fileRow.visibility = View.GONE

        val btn = audioRow.findViewById<ImageButton>(R.id.btn_audio_play)
        val sb = audioRow.findViewById<SeekBar>(R.id.sb_audio)
        val tvDuration = audioRow.findViewById<TextView>(R.id.tv_audio_duration)

        val tint = iconTint(audioRow, msg.isOutgoing)
        btn.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        tvDuration.setTextColor(tint)

        val playing = cb.isAudioPlaying(msg.id)
        btn.setImageResource(if (playing) R.drawable.ic_feather_pause else R.drawable.ic_feather_play)

        val hasLocal = !msg.localPath.isNullOrBlank()

        if (hasLocal) {
            val pos = cb.audioPosition(msg.id)
            if (pos != null && pos.second > 0) {
                sb.progress = (pos.first.toLong() * 1000L / pos.second).toInt().coerceIn(0, 1000)
                tvDuration.text = formatDuration(pos.first.toLong()) + " / " + formatDuration(pos.second.toLong())
            } else {
                sb.progress = 0
                tvDuration.text = formatDuration(msg.durationMs ?: 0)
            }
            btn.isEnabled = true
            btn.setOnClickListener { cb.onAudioPlayToggle(msg) }
            pb.visibility = if (msg.uploadProgress in 1..99) View.VISIBLE else View.GONE
            pb.progress = msg.uploadProgress
        } else {
            // Needs download (incoming) or upload in progress (outgoing queued)
            val pct = if (msg.isOutgoing) msg.uploadProgress else msg.downloadProgress
            sb.progress = 0
            tvDuration.text = formatDuration(msg.durationMs ?: 0)
            if (pct in 1..99) {
                pb.visibility = View.VISIBLE
                pb.progress = pct
                btn.isEnabled = false
            } else {
                pb.visibility = View.GONE
                btn.isEnabled = true
                btn.setOnClickListener { cb.onFileAction(msg) }  // triggers download
            }
        }
    }

    private fun bindFile(
        audioRow: LinearLayout, fileRow: LinearLayout, pb: ProgressBar,
        msg: Message, cb: MessageAdapter.Callbacks
    ) {
        audioRow.visibility = View.GONE
        fileRow.visibility = View.VISIBLE

        val btn = fileRow.findViewById<ImageButton>(R.id.btn_file_action)
        val tvName = fileRow.findViewById<TextView>(R.id.tv_file_name)
        val tvMeta = fileRow.findViewById<TextView>(R.id.tv_file_meta)

        val tint = iconTint(fileRow, msg.isOutgoing)
        btn.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        tvName.setTextColor(tint)
        tvMeta.setTextColor(tint)

        tvName.text = msg.fileName ?: "file"
        val sizeText = formatSize(msg.fileSize ?: 0)
        val ctx = fileRow.context
        val hasLocal = !msg.localPath.isNullOrBlank()
        val uploading = msg.isOutgoing && msg.uploadProgress in 1..99
        val downloading = !msg.isOutgoing && msg.downloadProgress in 1..99

        tvMeta.text = when {
            uploading -> ctx.getString(R.string.chat_file_uploading) + " • " + sizeText
            downloading -> ctx.getString(R.string.chat_file_downloading) + " • " + sizeText
            hasLocal -> ctx.getString(R.string.chat_file_tap_open) + " • " + sizeText
            else -> ctx.getString(R.string.chat_file_tap_download) + " • " + sizeText
        }

        when {
            uploading || downloading -> {
                pb.visibility = View.VISIBLE
                pb.progress = if (uploading) msg.uploadProgress else msg.downloadProgress
                btn.setImageResource(R.drawable.ic_feather_download)
                btn.isEnabled = false
            }
            hasLocal -> {
                pb.visibility = View.GONE
                btn.setImageResource(R.drawable.ic_feather_file)
                btn.isEnabled = true
            }
            else -> {
                pb.visibility = View.GONE
                btn.setImageResource(R.drawable.ic_feather_download)
                btn.isEnabled = true
            }
        }
        btn.setOnClickListener { cb.onFileAction(msg) }
    }

    private fun iconTint(view: View, outgoing: Boolean): Int {
        return if (outgoing) 0xFFFFFFFF.toInt()
        else ContextCompat.getColor(view.context, R.color.icon_stroke)
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
