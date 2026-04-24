package io.raaz.messenger.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import io.raaz.messenger.R
import io.raaz.messenger.data.model.Message
import io.raaz.messenger.databinding.FragmentChatBinding
import io.raaz.messenger.ui.SharedViewModel
import io.raaz.messenger.notification.RaazNotificationManager
import io.raaz.messenger.util.AppLogger
import io.raaz.messenger.util.ForegroundSyncManager
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.VoicePlayer
import io.raaz.messenger.util.VoiceRecorder
import io.raaz.messenger.util.hideKeyboard
import io.raaz.messenger.util.toast
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.io.File

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val sharedViewModel: SharedViewModel by activityViewModels()
    private val viewModel: ChatViewModel by viewModels()
    private val args: ChatFragmentArgs by navArgs()

    private lateinit var adapter: MessageAdapter
    private var recorder: VoiceRecorder? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioPositions = mutableMapOf<String, Pair<Int, Int>>()
    private var recStartMs: Long = 0
    private val recTimerRunnable = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - recStartMs
            val s = elapsed / 1000
            _binding?.tvRecTimer?.text = "%d:%02d".format(s / 60, s % 60)
            mainHandler.postDelayed(this, 250)
        }
    }

    // ── File picker launcher ──
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onFilePicked(it) } }

    // ── Microphone permission ──
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast(getString(R.string.chat_voice_permission_required))
    }

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
                icon.setAutoMirrored(true)
            }
        }

        adapter = MessageAdapter(buildAdapterCallbacks())
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

        // Toggle mic ↔ send icon based on text presence
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val empty = s.isNullOrBlank()
                binding.btnMic.visibility = if (empty) View.VISIBLE else View.GONE
                binding.btnSend.visibility = if (empty) View.GONE else View.VISIBLE
            }
        })

        binding.btnAttach.setOnClickListener { pickFileLauncher.launch("*/*") }

        // Hold-to-record behavior
        binding.btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startVoiceRecording(); true }
                MotionEvent.ACTION_UP -> { finishVoiceRecording(cancel = false); true }
                MotionEvent.ACTION_CANCEL -> { finishVoiceRecording(cancel = true); true }
                else -> false
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

    // ─── Voice recording ────────────────────────────────────────────────

    private fun startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (recorder?.isRecording == true) return
        val r = VoiceRecorder(requireContext())
        val file = r.start()
        if (file == null) {
            toast(getString(R.string.chat_voice_permission_required))
            return
        }
        recorder = r
        recStartMs = System.currentTimeMillis()
        binding.rowRecording.visibility = View.VISIBLE
        binding.rowCompose.visibility = View.INVISIBLE
        mainHandler.post(recTimerRunnable)
    }

    private fun finishVoiceRecording(cancel: Boolean) {
        val r = recorder ?: return
        mainHandler.removeCallbacks(recTimerRunnable)
        binding.rowRecording.visibility = View.GONE
        binding.rowCompose.visibility = View.VISIBLE
        val result = r.stop(discard = cancel)
        recorder = null
        if (cancel || result == null) return

        val (file, durationMs) = result
        viewModel.sendAttachment(
            localFile = file,
            mediaType = Message.MEDIA_AUDIO,
            fileName = file.name,
            mimeType = "audio/m4a",
            durationMs = durationMs
        )
    }

    // ─── File picker ────────────────────────────────────────────────────

    private fun onFilePicked(uri: Uri) {
        lifecycleScope.launch {
            val copied = copyUriToCache(uri)
            if (copied == null) {
                toast(getString(R.string.chat_file_failed))
                return@launch
            }
            val name = queryDisplayName(uri) ?: copied.name
            val mime = requireContext().contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', ""))
                ?: "application/octet-stream"
            val mediaType = if (mime.startsWith("image/")) Message.MEDIA_IMAGE else Message.MEDIA_FILE
            viewModel.sendAttachment(copied, mediaType, name, mime, null)
        }
    }

    private suspend fun copyUriToCache(uri: Uri): File? = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        try {
            val outDir = File(requireContext().cacheDir, "attach_outgoing").apply { mkdirs() }
            val out = File(outDir, "att_${System.currentTimeMillis()}")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            out
        } catch (e: Exception) {
            AppLogger.e("ChatFragment", "copyUriToCache failed: ${e.message}", e)
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }

    // ─── Adapter callbacks (audio playback + file download/open) ────────

    private fun buildAdapterCallbacks(): MessageAdapter.Callbacks = object : MessageAdapter.Callbacks() {
        override fun onAudioPlayToggle(message: Message) {
            val path = message.localPath
            if (path.isNullOrBlank()) {
                // not downloaded yet — trigger download
                viewModel.downloadAttachment(message)
                return
            }
            if (VoicePlayer.isPlaying(message.id)) {
                VoicePlayer.stop()
                adapter.notifyDataSetChanged()
            } else {
                val ok = VoicePlayer.play(
                    id = message.id,
                    path = path,
                    onProgress = { id, pos, dur ->
                        audioPositions[id] = pos to dur
                        _binding?.rvMessages?.post { adapter.notifyDataSetChanged() }
                    },
                    onComplete = { id ->
                        audioPositions.remove(id)
                        _binding?.rvMessages?.post { adapter.notifyDataSetChanged() }
                    }
                )
                if (ok) adapter.notifyDataSetChanged()
            }
        }

        override fun onFileAction(message: Message) {
            val path = message.localPath
            if (path.isNullOrBlank()) {
                viewModel.downloadAttachment(message)
            } else {
                openFile(File(path), message.mimeType ?: "application/octet-stream")
            }
        }

        override fun onRetry(message: Message) {
            toast(getString(R.string.chat_retrying))
            viewModel.retryMessage(message)
        }

        override fun isAudioPlaying(messageId: String): Boolean = VoicePlayer.isPlaying(messageId)

        override fun audioPosition(messageId: String): Pair<Int, Int>? = audioPositions[messageId]
    }

    private fun openFile(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, null))
        } catch (e: Exception) {
            AppLogger.e("ChatFragment", "openFile failed: ${e.message}", e)
            toast(getString(R.string.chat_file_cannot_open))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacks(recTimerRunnable)
        recorder?.cancel()
        recorder = null
        VoicePlayer.stop()
        _binding = null
    }
}
