package io.raaz.messenger.ui.welcome

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import io.raaz.messenger.R
import io.raaz.messenger.databinding.FragmentWelcomeBinding
import io.raaz.messenger.util.LocaleManager

class WelcomeFragment : Fragment() {

    private var _binding: FragmentWelcomeBinding? = null
    private val binding get() = _binding!!

    override fun onAttach(context: Context) {
        super.onAttach(LocaleManager.applyLocale(context))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWelcomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val savedLang = LocaleManager.getLanguage(requireContext())
        if (savedLang == LocaleManager.LANG_EN) {
            binding.rgLanguage.check(R.id.rb_en)
        } else {
            binding.rgLanguage.check(R.id.rb_fa)
        }

        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val lang = if (checkedId == R.id.rb_en) LocaleManager.LANG_EN else LocaleManager.LANG_FA
            LocaleManager.setLanguage(requireContext(), lang)
            requireActivity().recreate()
        }

        binding.btnContinue.setOnClickListener {
            findNavController().navigate(R.id.action_welcome_to_setup)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
