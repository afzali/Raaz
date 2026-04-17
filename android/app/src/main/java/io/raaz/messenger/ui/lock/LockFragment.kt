package io.raaz.messenger.ui.lock

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
import io.raaz.messenger.databinding.FragmentLockBinding
import io.raaz.messenger.ui.SharedViewModel
import io.raaz.messenger.util.DateFormatter
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.hide
import io.raaz.messenger.util.show

class LockFragment : Fragment() {

    private var _binding: FragmentLockBinding? = null
    private val binding get() = _binding!!
    private val sharedViewModel: SharedViewModel by activityViewModels()
    private val viewModel: LockViewModel by viewModels()

    override fun onAttach(context: Context) {
        super.onAttach(LocaleManager.applyLocale(context))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!viewModel.isSetupComplete) {
            findNavController().navigate(R.id.action_lock_to_welcome)
            return
        }

        binding.btnUnlock.setOnClickListener {
            val password = binding.etPassword.text?.toString() ?: ""
            viewModel.unlock(password)
        }

        binding.etPassword.setOnEditorActionListener { _, _, _ ->
            binding.btnUnlock.performClick()
            true
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            handleState(state)
        }
    }

    private fun handleState(state: LockViewModel.LockState) {
        when (state) {
            is LockViewModel.LockState.Idle -> {
                binding.progress.hide()
                binding.btnUnlock.isEnabled = true
                binding.tvError.invisible()
            }
            is LockViewModel.LockState.Loading -> {
                binding.progress.show()
                binding.btnUnlock.isEnabled = false
                binding.tvError.invisible()
            }
            is LockViewModel.LockState.Unlocked -> {
                binding.progress.hide()
                sharedViewModel.setDbKey(state.dbKey)
                findNavController().navigate(R.id.action_lock_to_chats)
            }
            is LockViewModel.LockState.WrongPassword -> {
                binding.progress.hide()
                binding.btnUnlock.isEnabled = true
                binding.etPassword.text?.clear()
                val msg = if (state.lockoutUntil != null) {
                    val time = DateFormatter.formatLockoutTime(requireContext(), state.lockoutUntil)
                    getString(R.string.lock_locked_until, time)
                } else {
                    "${getString(R.string.lock_wrong_password)} — ${getString(R.string.lock_attempts_remaining, state.attemptsRemaining)}"
                }
                binding.tvError.text = msg
                binding.tvError.show()
            }
            is LockViewModel.LockState.LockedOut -> {
                binding.progress.hide()
                binding.btnUnlock.isEnabled = false
                val time = DateFormatter.formatLockoutTime(requireContext(), state.until)
                binding.tvError.text = getString(R.string.lock_locked_until, time)
                binding.tvError.show()
            }
            is LockViewModel.LockState.Wiped -> {
                binding.progress.hide()
                binding.tvError.text = getString(R.string.lock_data_wiped)
                binding.tvError.show()
                binding.btnUnlock.isEnabled = false
                findNavController().navigate(R.id.action_lock_to_welcome)
            }
        }
    }

    private fun View.invisible() { visibility = View.INVISIBLE }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
