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

    private val syncIntervalOptions by lazy {
        listOf(
            Pair(getString(R.string.settings_sync_5min), 5),
            Pair(getString(R.string.settings_sync_15min), 15),
            Pair(getString(R.string.settings_sync_30min), 30),
            Pair(getString(R.string.settings_sync_1hour), 60),
            Pair(getString(R.string.settings_sync_disabled), 0)
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

        // Sync interval spinner
        val syncAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            syncIntervalOptions.map { it.first }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerSyncInterval.adapter = syncAdapter

        // Populate fields from DB settings — attach listener only after initial selection is set
        viewModel.settings.observe(viewLifecycleOwner) { settings ->
            settings ?: return@observe
            binding.etServerUrl.setText(settings.serverUrl)
            
            // Lock timeout
            val lockIdx = lockTimeoutOptions.indexOfFirst { it.second == settings.lockTimeoutMs }
            binding.spinnerLockTimeout.onItemSelectedListener = null
            if (lockIdx >= 0) binding.spinnerLockTimeout.setSelection(lockIdx)
            binding.spinnerLockTimeout.post {
                binding.spinnerLockTimeout.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                        viewModel.saveLockTimeout(lockTimeoutOptions[position].second)
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }

            // Sync interval
            val syncIdx = syncIntervalOptions.indexOfFirst { it.second == settings.syncIntervalMinutes }
            binding.spinnerSyncInterval.onItemSelectedListener = null
            if (syncIdx >= 0) binding.spinnerSyncInterval.setSelection(syncIdx)
            binding.spinnerSyncInterval.post {
                binding.spinnerSyncInterval.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                        viewModel.saveSyncInterval(syncIntervalOptions[position].second)
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
            }

            // Biometric switch
            binding.switchBiometric.isChecked = settings.biometricEnabled
        }

        // Biometric switch listener
        binding.switchBiometric.setOnCheckedChangeListener { btn, isChecked ->
            if (!btn.isPressed) return@setOnCheckedChangeListener // Ignore programmatic changes
            if (isChecked) {
                promptPasswordAndEnableBiometric()
            } else {
                viewModel.disableBiometric()
                toast(getString(R.string.save))
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

        // Check if biometric is available
        val isBiometricAvailable = io.raaz.messenger.util.BiometricHelper.isAvailable(requireContext())
        if (!isBiometricAvailable) {
            binding.switchBiometric.isEnabled = false
            binding.tvBiometricDesc.text = getString(R.string.biometric_not_available)
        }
    }

    private fun promptPasswordAndEnableBiometric() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.lock_enter_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.settings_biometric))
            .setMessage(getString(R.string.biometric_enable_password_prompt))
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val password = input.text?.toString() ?: ""
                if (password.isBlank()) {
                    binding.switchBiometric.isChecked = false
                    return@setPositiveButton
                }
                authenticateAndStorePassword(password)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                binding.switchBiometric.isChecked = false
            }
            .setOnCancelListener {
                binding.switchBiometric.isChecked = false
            }
            .show()
    }

    private fun authenticateAndStorePassword(password: String) {
        val cipher = try {
            viewModel.getBiometricEncryptCipher()
        } catch (e: Exception) {
            toast(getString(R.string.biometric_not_available))
            binding.switchBiometric.isChecked = false
            return
        }

        io.raaz.messenger.util.BiometricHelper.showBiometricPrompt(
            activity = requireActivity(),
            cipher = cipher,
            onSuccess = { authenticatedCipher ->
                if (authenticatedCipher != null) {
                    val ok = viewModel.enableBiometric(authenticatedCipher, password)
                    if (ok) {
                        toast(getString(R.string.save))
                    } else {
                        toast(getString(R.string.error_unknown))
                        binding.switchBiometric.isChecked = false
                    }
                } else {
                    binding.switchBiometric.isChecked = false
                }
            },
            onError = { error ->
                toast(error)
                binding.switchBiometric.isChecked = false
            },
            onCancel = {
                binding.switchBiometric.isChecked = false
            }
        )
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
