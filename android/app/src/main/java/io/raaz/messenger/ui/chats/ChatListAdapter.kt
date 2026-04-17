package io.raaz.messenger.ui.chats

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.raaz.messenger.data.model.Session
import io.raaz.messenger.databinding.ItemChatPreviewBinding
import io.raaz.messenger.util.DateFormatter
import io.raaz.messenger.util.hide
import io.raaz.messenger.util.show
import io.raaz.messenger.util.toInitials

class ChatListAdapter(
    private val onClick: (Session) -> Unit
) : ListAdapter<Session, ChatListAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val binding: ItemChatPreviewBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(session: Session) {
            binding.tvAvatar.text = session.contactName.toInitials().take(2)
            binding.tvName.text = session.contactName
            binding.tvPreview.text = session.lastMessagePreview.ifEmpty { "…" }
            binding.tvTime.text = session.lastMessageAt?.let {
                DateFormatter.formatMessageTime(binding.root.context, it)
            } ?: ""

            if (session.unreadCount > 0) {
                binding.tvUnread.show()
                binding.tvUnread.text = if (session.unreadCount > 99) "99+" else session.unreadCount.toString()
            } else {
                binding.tvUnread.hide()
            }

            binding.root.setOnClickListener { onClick(session) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemChatPreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Session>() {
            override fun areItemsTheSame(a: Session, b: Session) = a.id == b.id
            override fun areContentsTheSame(a: Session, b: Session) = a == b
        }
    }
}
