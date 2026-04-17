package io.raaz.messenger.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import io.raaz.messenger.R
import io.raaz.messenger.databinding.FragmentSettingsBinding
import io.raaz.messenger.ui.SharedViewModel
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.toast

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val sharedViewModel: SharedViewModel by activityViewModels()
    private val viewModel: SettingsViewModel by viewModels()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateNotifSwitch()
        if (!granted) {
            toast(getString(R.string.notif_permission_denied))
        }
    }

    private val lockTimeoutOptions by lazy {
        listOf(
            Pair(getString(R.string.settings_lock_1min),  60_000L),
            Pair(getString(R.string.settings_lock_5min),  300_000L),
            Pair(getString(R.string.settings_lock_15min), 900_000L),
            Pair(getString(R.string.settings_lock_30min), 1_800_000L),
            Pair(getString(R.string.settings_lock_never), 0L)
        )
    }

    override fun onAttach(context: Context) {
        super.onAttach(LocaleManager.applyLocale(context))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedViewModel.getDb()?.let { viewModel.setDb(it) }

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // Notification switch
        updateNotifSwitch()
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestNotificationPermission()
            } else {
                // Can't revoke programmatically — open system settings
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", requireContext().packageName, null)
                })
                binding.switchNotifications.isChecked = hasNotificationPermission()
            }
        }

        // Language
        val lang = LocaleManager.getLanguage(requireContext())
        if (lang == LocaleManager.LANG_FA) binding.rbLangFa.isChecked = true
        else binding.rbLangEn.isChecked = true

        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val selected = if (checkedId == R.id.rb_lang_fa) LocaleManager.LANG_FA else LocaleManager.LANG_EN
            LocaleManager.setLanguage(requireContext(), selected)
            requireActivity().recreate()
        }

        // Lock timeout spinner
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            lockTimeoutOptions.map { it.first }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerLockTimeout.adapter = spinnerAdapter

        // Populate fields from DB settings — attach listener only after initial selection is set
        viewModel.settings.observe(viewLifecycleOwner) { settings ->
            settings ?: return@observe
            binding.etServerUrl.setText(settings.serverUrl)
            val idx = lockTimeoutOptions.indexOfFirst { it.second == settings.lockTimeoutMs }

            // Remove listener while programmatically setting selection to avoid spurious saves
            binding.spinnerLockTimeout.onItemSelectedListener = null
            if (idx >= 0) binding.spinnerLockTimeout.setSelection(idx)

            // Re-attach after the current layout pass so setSelection doesn't trigger it
            binding.spinnerLockTimeout.post {
                binding.spinnerLockTimeout.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                        viewModel.saveLockTimeout(lockTimeoutOptions[position].second)
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }
        }

        viewModel.saved.observe(viewLifecycleOwner) {
            if (it == true) toast(getString(R.string.save))
        }

        // Save server URL
        binding.btnSaveServer.setOnClickListener {
            val url = binding.etServerUrl.text?.toString()?.trim() ?: ""
            if (url.isNotBlank()) viewModel.saveServerUrl(url)
        }

        // Logs
        binding.btnViewLogs.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_logs)
        }

        // Wipe data
        binding.btnWipeData.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.settings_wipe_data))
                .setMessage(getString(R.string.settings_wipe_confirm))
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    toast(getString(R.string.lock_data_wiped))
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun hasNotificationPermission(): Boolean =
        androidx.core.app.NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()

    private fun updateNotifSwitch() {
        binding.switchNotifications.isChecked = hasNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (hasNotificationPermission()) {
            updateNotifSwitch()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.settings_notifications))
                .setMessage(getString(R.string.settings_notifications_desc))
                .setPositiveButton(getString(R.string.ok)) { _, _ ->
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                    updateNotifSwitch()
                }
                .show()
        } else {
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
