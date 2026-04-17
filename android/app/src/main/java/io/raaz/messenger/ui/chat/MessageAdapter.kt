package io.raaz.messenger.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.raaz.messenger.R
import io.raaz.messenger.data.model.Message
import io.raaz.messenger.databinding.ItemMessageIncomingBinding
import io.raaz.messenger.databinding.ItemMessageOutgoingBinding
import io.raaz.messenger.util.DateFormatter

class MessageAdapter : ListAdapter<Message, RecyclerView.ViewHolder>(DIFF) {

    inner class OutgoingVH(private val b: ItemMessageOutgoingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(msg: Message) {
            b.tvMessage.text = msg.displayText
            b.tvTime.text = DateFormatter.formatMessageTime(b.root.context, msg.createdAt)
            b.ivStatus.setImageResource(when (msg.status) {
                Message.STATUS_CONFIRMED -> R.drawable.ic_check_double
                Message.STATUS_DELIVERED -> R.drawable.ic_check_double
                Message.STATUS_SENT -> R.drawable.ic_check
                else -> R.drawable.ic_clock
            })
        }
    }

    inner class IncomingVH(private val b: ItemMessageIncomingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(msg: Message) {
            b.tvMessage.text = msg.displayText
            b.tvTime.text = DateFormatter.formatMessageTime(b.root.context, msg.createdAt)
        }
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position).isOutgoing) VIEW_OUTGOING else VIEW_INCOMING

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == VIEW_OUTGOING) {
            OutgoingVH(ItemMessageOutgoingBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            IncomingVH(ItemMessageIncomingBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is OutgoingVH -> holder.bind(msg)
            is IncomingVH -> holder.bind(msg)
        }
    }

    companion object {
        private const val VIEW_OUTGOING = 0
        private const val VIEW_INCOMING = 1

        val DIFF = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(a: Message, b: Message) = a.id == b.id
            override fun areContentsTheSame(a: Message, b: Message) = a == b
        }
    }
}
