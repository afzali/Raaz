package io.raaz.messenger.ui.setup

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import io.raaz.messenger.R
import io.raaz.messenger.databinding.FragmentSetupBinding
import io.raaz.messenger.ui.SharedViewModel
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.hide
import io.raaz.messenger.util.show

class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!
    private val sharedViewModel: SharedViewModel by activityViewModels()
    private val viewModel: SetupViewModel by viewModels()

    override fun onAttach(context: Context) {
        super.onAttach(LocaleManager.applyLocale(context))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etServerUrl.setText(getString(R.string.setup_server_url_hint))

        binding.btnTestConnection.setOnClickListener {
            val url = binding.etServerUrl.text?.toString() ?: ""
            viewModel.testConnection(url)
        }

        viewModel.connectionState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is SetupViewModel.ConnectionState.Idle -> binding.tvConnectionStatus.visibility = android.view.View.GONE
                is SetupViewModel.ConnectionState.Testing -> {
                    binding.tvConnectionStatus.visibility = android.view.View.VISIBLE
                    binding.tvConnectionStatus.text = getString(R.string.setup_test_connecting)
                    binding.tvConnectionStatus.setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                            binding.tvConnectionStatus, com.google.android.material.R.attr.colorOnSurfaceVariant
                        )
                    )
                }
                is SetupViewModel.ConnectionState.Ok -> {
                    binding.tvConnectionStatus.visibility = android.view.View.VISIBLE
                    binding.tvConnectionStatus.text = getString(R.string.setup_test_ok)
                    binding.tvConnectionStatus.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
                }
                is SetupViewModel.ConnectionState.Fail -> {
                    binding.tvConnectionStatus.visibility = android.view.View.VISIBLE
                    binding.tvConnectionStatus.text = getString(R.string.setup_test_fail)
                    binding.tvConnectionStatus.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                }
            }
        }

        binding.btnSetup.setOnClickListener {
            viewModel.setup(
                password = binding.etPassword.text?.toString() ?: "",
                confirmPassword = binding.etConfirmPassword.text?.toString() ?: "",
                serverUrl = binding.etServerUrl.text?.toString() ?: ""
            )
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is SetupViewModel.SetupState.Idle -> {
                    binding.btnSetup.isEnabled = true
                    binding.tvError.hide()
                }
                is SetupViewModel.SetupState.Loading -> {
                    binding.btnSetup.isEnabled = false
                    binding.tvError.hide()
                    binding.btnSetup.text = getString(R.string.setup_generating_keys)
                }
                is SetupViewModel.SetupState.Done -> {
                    sharedViewModel.setDbKey(state.dbKey)
                    findNavController().navigate(R.id.action_setup_to_chats)
                }
                is SetupViewModel.SetupState.Error -> {
                    binding.btnSetup.isEnabled = true
                    binding.btnSetup.text = getString(R.string.setup_complete)
                    val msg = when (state.reason) {
                        "password_short" -> getString(R.string.setup_password_too_short)
                        "password_mismatch" -> getString(R.string.setup_passwords_dont_match)
                        else -> getString(R.string.error_unknown)
                    }
                    binding.tvError.text = msg
                    binding.tvError.show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
