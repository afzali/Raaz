package io.raaz.messenger.ui.addcontact

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import io.raaz.messenger.databinding.FragmentAddContactBinding
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.hide
import io.raaz.messenger.util.show
import io.raaz.messenger.util.toast

class AddContactFragment : Fragment() {

    private var _binding: FragmentAddContactBinding? = null
    private val binding get() = _binding!!
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

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // Setup tabs
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(io.raaz.messenger.R.string.add_contact_enter_code)))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(io.raaz.messenger.R.string.add_contact_show_my_qr)))

        // Load my QR code
        viewModel.myQrBitmap.observe(viewLifecycleOwner) { bitmap ->
            if (bitmap != null) binding.ivMyQr.setImageBitmap(bitmap)
        }

        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                when (tab.position) {
                    0 -> { binding.tilCode.show(); binding.ivMyQr.hide(); binding.cameraPreview.hide() }
                    1 -> { binding.tilCode.hide(); binding.ivMyQr.show(); binding.cameraPreview.hide(); viewModel.loadMyQr() }
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

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
                    binding.tvError.text = getString(io.raaz.messenger.R.string.add_contact_invalid_code)
                    binding.tvError.show()
                }
                else -> binding.tvError.hide()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
