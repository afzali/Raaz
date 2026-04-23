package io.raaz.messenger.ui.addcontact

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import io.raaz.messenger.databinding.FragmentAddContactBinding
import io.raaz.messenger.ui.SharedViewModel
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.hide
import io.raaz.messenger.util.show
import io.raaz.messenger.util.toast

class AddContactFragment : Fragment() {

    private var _binding: FragmentAddContactBinding? = null
    private val binding get() = _binding!!
    private val sharedViewModel: SharedViewModel by activityViewModels()
    private val viewModel: AddContactViewModel by viewModels()

    override fun onAttach(context: Context) {
        super.onAttach(LocaleManager.applyLocale(context))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedViewModel.getDb()?.let { viewModel.setDb(it) }

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(io.raaz.messenger.R.string.add_contact_enter_code)))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(io.raaz.messenger.R.string.add_contact_scan_qr)))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(io.raaz.messenger.R.string.add_contact_show_my_qr)))

        viewModel.myQrBitmap.observe(viewLifecycleOwner) { bitmap ->
            if (bitmap != null) binding.ivMyQr.setImageBitmap(bitmap)
        }


        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                when (tab.position) {
                    0 -> {
                        // Enter code manually
                        binding.tilCode.show()
                        binding.tilName.show()
                        binding.btnAdd.show()
                        binding.layoutMyQr.hide()
                        binding.cameraPreview.hide()
                    }
                    1 -> {
                        // Scan QR code
                        binding.tilCode.hide()
                        binding.tilName.hide()
                        binding.btnAdd.hide()
                        binding.layoutMyQr.hide()
                        binding.cameraPreview.show()
                        startQrScanner()
                    }
                    2 -> {
                        // Show my QR
                        binding.tilCode.hide()
                        binding.tilName.hide()
                        binding.btnAdd.hide()
                        binding.layoutMyQr.show()
                        binding.cameraPreview.hide()
                        viewModel.loadMyQr()
                    }
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        viewModel.myInviteCode.observe(viewLifecycleOwner) { code ->
            if (code != null) {
                binding.tvInviteCode.text = code
                binding.btnCopyCode.setOnClickListener {
                    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("invite_code", code))
                    toast(getString(io.raaz.messenger.R.string.copy))
                }
                binding.btnShareCode.setOnClickListener {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, code)
                    }
                    startActivity(Intent.createChooser(intent, null))
                }
            }
        }

        binding.btnAdd.setOnClickListener {
            val code = binding.etCode.text?.toString()?.trim() ?: ""
            val name = binding.etName.text?.toString()?.trim() ?: ""
            if (name.isBlank()) {
                binding.tilName.error = getString(io.raaz.messenger.R.string.add_contact_name_hint)
                return@setOnClickListener
            }
            viewModel.addContactFromCode(code, name)
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AddContactViewModel.State.Success -> {
                    toast(getString(io.raaz.messenger.R.string.add_contact_added))
                    findNavController().navigateUp()
                }
                is AddContactViewModel.State.Error -> {
                    val msg = when (state.reason) {
                        "db_not_ready" -> getString(io.raaz.messenger.R.string.error_unknown)
                        "empty_code" -> getString(io.raaz.messenger.R.string.add_contact_code_hint)
                        else -> getString(io.raaz.messenger.R.string.add_contact_invalid_code)
                    }
                    binding.tvError.text = msg
                    binding.tvError.show()
                }
                else -> binding.tvError.hide()
            }
        }
    }

    private fun startQrScanner() {
        // TODO: Implement QR scanner using CameraX
        // For now, show a toast that this feature is coming
        toast("QR Scanner - Coming soon")
        // When QR is scanned, populate the code field and switch back to tab 0
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
