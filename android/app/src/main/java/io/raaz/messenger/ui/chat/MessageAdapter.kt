package io.raaz.messenger.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.raaz.messenger.R
import io.raaz.messenger.data.model.Message
import io.raaz.messenger.databinding.ItemDateHeaderBinding
import io.raaz.messenger.databinding.ItemMessageIncomingBinding
import io.raaz.messenger.databinding.ItemMessageOutgoingBinding
import io.raaz.messenger.util.DateFormatter

class MessageAdapter(
    private val callbacks: Callbacks = Callbacks()
) : ListAdapter<MessageAdapter.Item, RecyclerView.ViewHolder>(DIFF) {

    open class Callbacks {
        open fun onAudioPlayToggle(message: Message) {}
        open fun onFileAction(message: Message) {}
        open fun onRetry(message: Message) {}
        open fun isAudioPlaying(messageId: String): Boolean = false
        open fun audioPosition(messageId: String): Pair<Int, Int>? = null  // position, duration in ms
    }

    sealed class Item {
        data class DateHeader(val label: String) : Item()
        data class Msg(
            val message: Message,
            val isTail: Boolean  // last in a consecutive group → show tail corner
        ) : Item()
    }

    // Build display list: inject DateHeader items between day boundaries
    fun submitMessages(messages: List<Message>, context: android.content.Context, commitCallback: Runnable? = null) {
        val items = mutableListOf<Item>()
        for (i in messages.indices) {
            val msg = messages[i]
            val prev = if (i > 0) messages[i - 1] else null

            // New day boundary → insert header
            if (prev == null || !DateFormatter.isSameDay(prev.createdAt, msg.createdAt)) {
                items += Item.DateHeader(buildDateLabel(context, msg.createdAt))
            }

            // Tail = last message from this sender in consecutive run (next differs or is null)
            val next = if (i < messages.size - 1) messages[i + 1] else null
            val isTail = next == null
                || !DateFormatter.isSameDay(msg.createdAt, next.createdAt)
                || next.isOutgoing != msg.isOutgoing

            items += Item.Msg(msg, isTail)
        }
        submitList(items, commitCallback)
    }

    private fun buildDateLabel(context: android.content.Context, ts: Long): String {
        val now = System.currentTimeMillis()
        val todayStart = now - (now % 86_400_000L)  // rough day boundary
        val yesterdayStart = todayStart - 86_400_000L
        return when {
            ts >= todayStart -> context.getString(R.string.date_today)
            ts >= yesterdayStart -> context.getString(R.string.date_yesterday)
            else -> DateFormatter.formatMessageDate(context, ts)
        }
    }

    // ViewHolders

    inner class DateHeaderVH(private val b: ItemDateHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(label: String) { b.tvDate.text = label }
    }

    inner class OutgoingVH(private val b: ItemMessageOutgoingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Item.Msg) {
            val mediaView = b.root.findViewById<android.view.View>(R.id.media_container)
            if (mediaView != null) {
                MediaBinder.bind(mediaView, item.message, callbacks)
            }
            b.tvMessage.visibility = if (item.message.isMedia) android.view.View.GONE else android.view.View.VISIBLE
            b.tvMessage.text = item.message.displayText
            b.tvTime.text = DateFormatter.formatMessageTime(b.root.context, item.message.createdAt)
            val isToday = DateFormatter.isSameDay(item.message.createdAt, System.currentTimeMillis())
            
            // Status logic:
            // 0=queued (clock), 1=sent (1 gray check), 2=delivered (2 blue), 3=confirmed (2 green), 4=expired (1 red)
            val isFailed = item.message.status == Message.STATUS_FAILED
            val (iconRes, tintColor) = when (item.message.status) {
                Message.STATUS_FAILED ->
                    Pair(R.drawable.ic_feather_alert_circle, 0xFFE53935.toInt()) // red alert = failed
                Message.STATUS_EXPIRED -> 
                    Pair(R.drawable.ic_check, 0xFFE53935.toInt()) // 1 red check = expired without delivery
                Message.STATUS_QUEUED ->
                    Pair(R.drawable.ic_clock, 0xCCFFFFFF.toInt()) // clock = waiting to send
                Message.STATUS_SENT ->
                    Pair(R.drawable.ic_check, 0xFF9E9E9E.toInt()) // 1 gray = sent but not delivered
                Message.STATUS_DELIVERED -> 
                    if (isToday) Pair(R.drawable.ic_check_double, 0xFF4FC3F7.toInt()) // 2 blue = delivered today
                    else Pair(R.drawable.ic_check_double, 0xCCFFFFFF.toInt()) // older: white
                Message.STATUS_CONFIRMED ->
                    if (isToday) Pair(R.drawable.ic_check_double, 0xFF4CAF50.toInt()) // 2 green = confirmed today
                    else Pair(R.drawable.ic_check_double, 0xCCFFFFFF.toInt()) // older: white
                else ->
                    Pair(R.drawable.ic_clock, 0xCCFFFFFF.toInt())
            }
            b.ivStatus.setImageResource(iconRes)
            b.ivStatus.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN)
            // Retry: make the whole bubble clickable when failed
            if (isFailed) {
                b.root.setOnClickListener { callbacks.onRetry(item.message) }
            } else {
                b.root.setOnClickListener(null)
                b.root.isClickable = false
            }
            val r = b.root.context.resources
            b.bubble.background = androidx.core.content.ContextCompat.getDrawable(
                b.root.context,
                if (item.isTail) R.drawable.bg_bubble_outgoing else R.drawable.bg_bubble_outgoing_mid
            )
            // margin-bottom: more space after tail (group separator)
            (b.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin =
                if (item.isTail) r.getDimensionPixelSize(R.dimen.bubble_group_margin) else r.getDimensionPixelSize(R.dimen.bubble_seq_margin)
        }
    }

    inner class IncomingVH(private val b: ItemMessageIncomingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: Item.Msg) {
            val mediaView = b.root.findViewById<android.view.View>(R.id.media_container)
            if (mediaView != null) {
                MediaBinder.bind(mediaView, item.message, callbacks)
            }
            b.tvMessage.visibility = if (item.message.isMedia) android.view.View.GONE else android.view.View.VISIBLE
            b.tvMessage.text = item.message.displayText
            b.tvTime.text = DateFormatter.formatMessageTime(b.root.context, item.message.createdAt)
            b.bubble.background = androidx.core.content.ContextCompat.getDrawable(
                b.root.context,
                if (item.isTail) R.drawable.bg_bubble_incoming else R.drawable.bg_bubble_incoming_mid
            )
            val r = b.root.context.resources
            (b.root.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin =
                if (item.isTail) r.getDimensionPixelSize(R.dimen.bubble_group_margin) else r.getDimensionPixelSize(R.dimen.bubble_seq_margin)
        }
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is Item.DateHeader -> TYPE_DATE
        is Item.Msg -> if ((getItem(position) as Item.Msg).message.isOutgoing) TYPE_OUT else TYPE_IN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DATE -> DateHeaderVH(ItemDateHeaderBinding.inflate(inf, parent, false))
            TYPE_OUT  -> OutgoingVH(ItemMessageOutgoingBinding.inflate(inf, parent, false))
            else      -> IncomingVH(ItemMessageIncomingBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.DateHeader -> (holder as DateHeaderVH).bind(item.label)
            is Item.Msg -> when (holder) {
                is OutgoingVH -> holder.bind(item)
                is IncomingVH -> holder.bind(item)
                else -> {}
            }
        }
    }

    companion object {
        private const val TYPE_DATE = 0
        private const val TYPE_OUT  = 1
        private const val TYPE_IN   = 2

        val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(a: Item, b: Item): Boolean = when {
                a is Item.DateHeader && b is Item.DateHeader -> a.label == b.label
                a is Item.Msg && b is Item.Msg -> a.message.id == b.message.id
                else -> false
            }
            override fun areContentsTheSame(a: Item, b: Item) = a == b
        }
    }
}
