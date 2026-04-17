package io.raaz.messenger.ui.logs

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.raaz.messenger.databinding.ItemLogEntryBinding
import io.raaz.messenger.util.AppLogger
import io.raaz.messenger.util.DateFormatter

class LogEntryAdapter : ListAdapter<AppLogger.LogEntry, LogEntryAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemLogEntryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(entry: AppLogger.LogEntry) {
            b.tvLevel.text = entry.level.take(1)
            b.tvLevel.setBackgroundColor(levelColor(entry.level))
            b.tvTag.text = entry.tag
            b.tvTime.text = DateFormatter.formatFull(b.root.context, entry.timestamp)
            b.tvMessage.text = entry.message
        }

        private fun levelColor(level: String): Int = when (level) {
            "DEBUG" -> Color.parseColor("#607D8B")
            "INFO"  -> Color.parseColor("#1A6B5A")
            "WARN"  -> Color.parseColor("#F57F17")
            "ERROR" -> Color.parseColor("#C62828")
            else    -> Color.GRAY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemLogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppLogger.LogEntry>() {
            override fun areItemsTheSame(a: AppLogger.LogEntry, b: AppLogger.LogEntry) = a.id == b.id
            override fun areContentsTheSame(a: AppLogger.LogEntry, b: AppLogger.LogEntry) = a == b
        }
    }
}
