package io.raaz.messenger.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import io.raaz.messenger.R
import io.raaz.messenger.databinding.FragmentSettingsBinding
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.toast

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val lockTimeoutOptions by lazy {
        listOf(
            Pair(getString(R.string.settings_lock_1min), 60_000L),
            Pair(getString(R.string.settings_lock_5min), 300_000L),
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

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

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
                    // TODO: trigger wipe via ViewModel
                    toast(getString(R.string.lock_data_wiped))
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        // Save server URL
        binding.btnSaveServer.setOnClickListener {
            val url = binding.etServerUrl.text?.toString()?.trim() ?: ""
            if (url.isNotBlank()) {
                // TODO: save via ViewModel
                toast(getString(R.string.save))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
