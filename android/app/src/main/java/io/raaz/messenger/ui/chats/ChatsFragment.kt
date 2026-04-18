package io.raaz.messenger.ui.chats

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import io.raaz.messenger.R
import io.raaz.messenger.databinding.FragmentChatsBinding
import io.raaz.messenger.ui.SharedViewModel
import io.raaz.messenger.notification.RaazNotificationManager
import io.raaz.messenger.util.ForegroundSyncManager
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.hide
import io.raaz.messenger.util.show
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!
    private val sharedViewModel: SharedViewModel by activityViewModels()
    private val viewModel: ChatsViewModel by viewModels()

    private lateinit var adapter: ChatListAdapter
    private var logoView: View? = null

    override fun onAttach(context: Context) {
        super.onAttach(LocaleManager.applyLocale(context))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ChatListAdapter { session ->
            findNavController().navigate(
                R.id.action_chats_to_chat,
                bundleOf("sessionId" to session.id, "contactName" to session.contactName)
            )
        }

        binding.rvChats.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChats.adapter = adapter

        binding.fabAdd.setOnClickListener {
            findNavController().navigate(R.id.action_chats_to_add_contact)
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    findNavController().navigate(R.id.action_chats_to_settings)
                    true
                }
                R.id.action_sync -> {
                    triggerManualSync()
                    true
                }
                else -> false
            }
        }

        // Wire the custom action layout for the sync/logo button
        binding.toolbar.post {
            val syncItem = binding.toolbar.menu.findItem(R.id.action_sync)
            logoView = syncItem?.actionView?.findViewById(R.id.iv_logo)
            logoView?.setOnClickListener { triggerManualSync() }
        }

        // Animate logo while syncing
        val rotateAnim = AnimationUtils.loadAnimation(requireContext(), R.anim.rotate_sync)
        viewLifecycleOwner.lifecycleScope.launch {
            ForegroundSyncManager.isSyncing.collectLatest { syncing ->
                if (syncing) {
                    logoView?.startAnimation(rotateAnim)
                } else {
                    logoView?.clearAnimation()
                    // Refresh list after sync completes
                    viewModel.refresh()
                }
            }
        }

        viewModel.sessions.observe(viewLifecycleOwner) { sessions ->
            adapter.submitList(sessions)
            if (sessions.isEmpty()) { binding.layoutEmpty.show(); binding.rvChats.hide() }
            else { binding.layoutEmpty.hide(); binding.rvChats.show() }
        }

        // Init sync manager with DB key then start polling
        sharedViewModel.getDbKey()?.let { key ->
            ForegroundSyncManager.init(requireContext(), key)
            viewModel.init(key)
        }

        viewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        RaazNotificationManager.clearMessageNotification(requireContext())
        ForegroundSyncManager.startPolling()
        // Immediate sync on resume + refresh list
        viewLifecycleOwner.lifecycleScope.launch {
            ForegroundSyncManager.syncNow()
            viewModel.refresh()
        }
    }

    override fun onPause() {
        super.onPause()
        ForegroundSyncManager.stopPolling()
    }

    private fun triggerManualSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            ForegroundSyncManager.syncNow()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        logoView = null
        _binding = null
    }
}
