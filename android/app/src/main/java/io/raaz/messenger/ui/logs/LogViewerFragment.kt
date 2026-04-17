package io.raaz.messenger.ui.logs

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import io.raaz.messenger.databinding.FragmentLogViewerBinding
import io.raaz.messenger.util.LocaleManager
import io.raaz.messenger.util.hide
import io.raaz.messenger.util.show

class LogViewerFragment : Fragment() {

    private var _binding: FragmentLogViewerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LogViewerViewModel by viewModels()
    private lateinit var adapter: LogEntryAdapter

    override fun onAttach(context: Context) {
        super.onAttach(LocaleManager.applyLocale(context))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == io.raaz.messenger.R.id.action_clear_logs) {
                viewModel.clearLogs()
                true
            } else false
        }

        adapter = LogEntryAdapter()
        binding.rvLogs.layoutManager = LinearLayoutManager(requireContext()).apply { reverseLayout = false }
        binding.rvLogs.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = viewModel.load(query = s?.toString())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.chipGroupLevel.setOnCheckedStateChangeListener { _, checkedIds ->
            val level = when {
                io.raaz.messenger.R.id.chip_debug in checkedIds -> "DEBUG"
                io.raaz.messenger.R.id.chip_info in checkedIds -> "INFO"
                io.raaz.messenger.R.id.chip_warn in checkedIds -> "WARN"
                io.raaz.messenger.R.id.chip_error in checkedIds -> "ERROR"
                else -> null
            }
            viewModel.load(level = level)
        }

        viewModel.logs.observe(viewLifecycleOwner) { logs ->
            adapter.submitList(logs)
            if (logs.isEmpty()) { binding.tvEmpty.show(); binding.rvLogs.hide() }
            else { binding.tvEmpty.hide(); binding.rvLogs.show() }
        }

        viewModel.load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
