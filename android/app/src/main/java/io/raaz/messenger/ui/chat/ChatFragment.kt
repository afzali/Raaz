package io.raaz.messenger.ui.chat

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import io.raaz.messenger.R
import io.raaz.messenger.databinding.FragmentChatBinding
import io.raaz.messenger.ui.SharedViewModel
import io.raaz.messenger.notification.RaazNotificationManager
import io.raaz.messenger.util.ForegroundSyncManager
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.hideKeyboard
import io.raaz.messenger.util.toast
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val sharedViewModel: SharedViewModel by activityViewModels()
    private val viewModel: ChatViewModel by viewModels()
    private val args: ChatFragmentArgs by navArgs()

    private lateinit var adapter: MessageAdapter

    override fun onAttach(context: Context) {
        super.onAttach(LocaleManager.applyLocale(context))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.title = args.contactName
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_rename -> { showRenameDialog(); true }
                R.id.action_rekey  -> { showRekeyDialog(); true }
                R.id.action_clear_history -> { showClearHistoryDialog(); true }
                R.id.action_session_info -> { showSessionInfoDialog(); true }
                R.id.action_delete_contact -> { showDeleteContactDialog(); true }
                else -> false
            }
        }

        // Fix RTL back arrow rotation for Persian
        if (LocaleManager.getLanguage(requireContext()) == LocaleManager.LANG_FA) {
            binding.toolbar.navigationIcon?.let { icon ->
                // Mirror the back arrow for RTL
                icon.setAutoMirrored(true)
            }
        }

        adapter = MessageAdapter()
        val layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = adapter

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isNotBlank()) {
                viewModel.sendMessage(text)
                binding.etMessage.text?.clear()
                binding.etMessage.hideKeyboard()
            }
        }

        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            adapter.submitMessages(messages, requireContext()) {
                if (messages.isNotEmpty()) {
                    binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                }
            }
        }

        viewModel.rekeyResult.observe(viewLifecycleOwner) { result ->
            result ?: return@observe
            val msg = when (result) {
                is ChatViewModel.RekeyResult.Success      -> getString(R.string.rekey_success)
                is ChatViewModel.RekeyResult.UserMismatch -> getString(R.string.rekey_error_mismatch)
                is ChatViewModel.RekeyResult.InvalidCode  -> getString(R.string.rekey_error_invalid)
            }
            toast(msg)
            viewModel.clearRekeyResult()
        }

        val dbKey = sharedViewModel.getDbKey()
        if (dbKey != null) {
            viewModel.init(args.sessionId, dbKey)
        }
        viewModel.syncIncoming()

        // Reload messages whenever a background sync finishes
        viewLifecycleOwner.lifecycleScope.launch {
            ForegroundSyncManager.isSyncing
                .filter { syncing -> !syncing }
                .collect { viewModel.reloadMessages() }
        }
    }

    override fun onResume() {
        super.onResume()
        ForegroundSyncManager.startPolling()
        RaazNotificationManager.clearMessageNotification(requireContext())
        
        // Immediate sync and refresh to get new messages
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.syncIncoming()
            viewModel.reloadMessages()
            // Mark messages as read since user is viewing the chat
            viewModel.markMessagesAsRead()
        }
    }

    override fun onPause() {
        super.onPause()
        ForegroundSyncManager.stopPolling()
    }

    private fun showRenameDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.chat_rename_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(binding.toolbar.title)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.chat_rename))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim() ?: ""
                if (name.isNotBlank()) {
                    viewModel.renameContact(name)
                    binding.toolbar.title = name
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showRekeyDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.rekey_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.rekey_title))
            .setMessage(getString(R.string.rekey_message))
            .setView(input)
            .setPositiveButton(getString(R.string.rekey_confirm)) { _, _ ->
                val code = input.text?.toString()?.trim() ?: ""
                if (code.isNotBlank()) viewModel.rekeyContact(code)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showClearHistoryDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.chat_clear_history)
            .setMessage(R.string.chat_clear_history_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.clearHistory()
                toast(getString(R.string.chat_history_cleared))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSessionInfoDialog() {
        val session = viewModel.session.value
        val info = buildString {
            appendLine(getString(R.string.session_info_session_id, session?.id?.take(8) ?: "-"))
            appendLine(getString(R.string.session_info_contact_id, session?.contactId?.take(8) ?: "-"))
            appendLine(getString(R.string.session_info_name, session?.contactName ?: "-"))
            appendLine(getString(R.string.session_info_public_key, session?.contactPublicKey?.take(20) ?: "-"))
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.chat_session_info)
            .setMessage(info)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showDeleteContactDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.chat_delete_contact)
            .setMessage(R.string.chat_delete_contact_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteContact()
                findNavController().navigateUp()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
