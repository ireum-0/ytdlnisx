package com.ireum.ytdl.ui.more

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.AutomaticKeywordRuleSummary
import com.ireum.ytdl.database.models.AutomaticKeywordSyncError
import com.ireum.ytdl.database.models.AutomaticKeywordSyncStatus
import com.ireum.ytdl.database.repository.AutomaticKeywordRuleInput
import com.ireum.ytdl.database.repository.AutomaticKeywordRuleRepository
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class AutomaticKeywordRulesFragment : Fragment() {
    private lateinit var repository: AutomaticKeywordRuleRepository
    private lateinit var container: LinearLayout
    private lateinit var empty: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        state: Bundle?
    ): View = inflater.inflate(R.layout.fragment_automatic_keyword_rules, parent, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        repository = AutomaticKeywordRuleRepository(requireContext())
        container = view.findViewById(R.id.automaticKeywordRulesContainer)
        empty = view.findViewById(R.id.automaticKeywordRulesEmpty)
        view.findViewById<MaterialButton>(R.id.addAutomaticKeywordRule).setOnClickListener {
            showEditor(null)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.summaries.collect(::render)
            }
        }
    }

    private fun render(rules: List<AutomaticKeywordRuleSummary>) {
        empty.isVisible = rules.isEmpty()
        container.removeAllViews()
        rules.forEach { summary ->
            val card = MaterialCardView(requireContext()).apply {
                val margin = dp(6)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, margin, 0, margin) }
                addView(ruleContent(summary))
            }
            container.addView(card)
        }
    }

    private fun ruleContent(summary: AutomaticKeywordRuleSummary) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(TextView(context).apply {
            text = summary.playlistName
            textSize = 18f
        })
        addView(TextView(context).apply {
            text = getString(
                R.string.automatic_keyword_rule_keywords_value,
                summary.keywordsCsv.orEmpty()
            )
        })
        addView(TextView(context).apply {
            text = getString(
                R.string.automatic_keyword_rule_matches_value,
                summary.matchedHistoryCount
            )
        })
        addView(TextView(context).apply {
            text = statusText(summary)
        })
        addView(CheckBox(context).apply {
            text = getString(R.string.enabled)
            isChecked = summary.enabled
            setOnCheckedChangeListener { _, checked ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    repository.setEnabled(summary.id, checked)
                }
            }
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton(R.string.edit) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val rule = withContext(Dispatchers.IO) { repository.getRule(summary.id) }
                    showEditor(rule)
                }
            })
            addView(actionButton(R.string.automatic_keyword_sync_now) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    if (repository.syncNow(summary.id)) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                R.string.automatic_keyword_sync_queued,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            })
            addView(actionButton(R.string.delete) { confirmDelete(summary.id) })
        })
    }

    private fun statusText(summary: AutomaticKeywordRuleSummary): String {
        val useDiscovery = summary.discoveryAt > summary.manualSyncAt
        val selectedStatus = if (useDiscovery) summary.discoveryStatus else summary.manualSyncStatus
        val selectedError = if (useDiscovery) summary.discoveryError else summary.manualSyncError
        val selectedAt = if (useDiscovery) summary.discoveryAt else summary.manualSyncAt
        val status = when (selectedStatus) {
            AutomaticKeywordSyncStatus.NEVER -> getString(R.string.automatic_keyword_status_never)
            AutomaticKeywordSyncStatus.QUEUED -> getString(R.string.automatic_keyword_status_queued)
            AutomaticKeywordSyncStatus.RUNNING -> getString(R.string.automatic_keyword_status_running)
            AutomaticKeywordSyncStatus.SUCCESS -> getString(R.string.automatic_keyword_status_success)
            AutomaticKeywordSyncStatus.PARTIAL -> getString(R.string.automatic_keyword_status_partial)
            else -> getString(R.string.automatic_keyword_status_failed)
        }
        val error = when (selectedError) {
            AutomaticKeywordSyncError.NETWORK -> getString(R.string.automatic_keyword_error_network)
            AutomaticKeywordSyncError.AUTH_REQUIRED -> getString(R.string.automatic_keyword_error_auth)
            AutomaticKeywordSyncError.PRIVATE_PLAYLIST -> getString(R.string.automatic_keyword_error_private)
            AutomaticKeywordSyncError.UNAVAILABLE -> getString(R.string.automatic_keyword_error_unavailable)
            AutomaticKeywordSyncError.EXTRACTION -> getString(R.string.automatic_keyword_error_extraction)
            AutomaticKeywordSyncError.DATABASE_PARTIAL -> getString(R.string.automatic_keyword_error_partial)
            AutomaticKeywordSyncError.UNKNOWN -> getString(R.string.automatic_keyword_error_unknown)
            else -> ""
        }
        val time = selectedAt.takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        } ?: getString(R.string.automatic_keyword_not_yet)
        return getString(
            R.string.automatic_keyword_rule_status_value,
            status,
            time,
            error
        )
    }

    private fun showEditor(rule: AutomaticKeywordRule?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = DBManager.getInstance(requireContext())
            val existingKeywords = if (rule == null) emptyList() else {
                withContext(Dispatchers.IO) { repository.getKeywords(rule.id) }
            }
            val sources = withContext(Dispatchers.IO) { db.observeSourcesDao.getAllSources() }
            val sourceChoices = sources.map {
                "${it.name} — ${it.url}" to it
            }
            val sourceAdapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                sourceChoices.map { it.first }
            )
            val layout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(4), dp(20), 0)
            }
            val sourcePicker = AutoCompleteTextView(requireContext()).apply {
                hint = getString(R.string.automatic_keyword_select_playlist)
                setAdapter(sourceAdapter)
            }
            val urlInput = TextInputEditText(requireContext()).apply {
                hint = getString(R.string.automatic_keyword_playlist_url)
                setText(rule?.conditionValue.orEmpty())
            }
            val nameInput = TextInputEditText(requireContext()).apply {
                hint = getString(R.string.automatic_keyword_playlist_name)
                setText(rule?.playlistName.orEmpty())
            }
            sourcePicker.setOnItemClickListener { _, _, position, _ ->
                val selectedLabel = sourceAdapter.getItem(position)
                sourceChoices.firstOrNull { it.first == selectedLabel }?.second?.let {
                    urlInput.setText(it.url)
                    nameInput.setText(it.name)
                }
            }
            val keywordsInput = TextInputEditText(requireContext()).apply {
                hint = getString(R.string.automatic_keyword_keywords_hint)
                setText(existingKeywords.joinToString(", "))
            }
            val applyExisting = CheckBox(requireContext()).apply {
                text = getString(R.string.automatic_keyword_apply_existing)
                isChecked = rule == null || rule.pendingApplyToExisting
            }
            val enabled = CheckBox(requireContext()).apply {
                text = getString(R.string.enabled)
                isChecked = rule?.enabled ?: true
            }
            layout.addView(wrapInput(sourcePicker))
            layout.addView(wrapInput(urlInput))
            layout.addView(wrapInput(nameInput))
            layout.addView(wrapInput(keywordsInput))
            layout.addView(applyExisting)
            layout.addView(enabled)

            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (rule == null) R.string.automatic_keyword_add_rule else R.string.automatic_keyword_edit_rule)
                .setView(layout)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.automatic_keyword_save, null)
                .create()
            dialog.setOnShowListener {
                val saveButton =
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                saveButton.setOnClickListener {
                    val url = urlInput.text?.toString().orEmpty()
                    val keywords = AutomaticKeywordNormalizer.parseKeywords(
                        keywordsInput.text?.toString().orEmpty()
                    )
                    if (AutomaticKeywordNormalizer.playlistConditionKey(url) == null) {
                        urlInput.error = getString(R.string.automatic_keyword_invalid_playlist_url)
                        return@setOnClickListener
                    }
                    if (keywords.isEmpty()) {
                        keywordsInput.error = getString(R.string.automatic_keyword_keywords_required)
                        return@setOnClickListener
                    }
                    saveButton.isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            repository.save(
                                AutomaticKeywordRuleInput(
                                    id = rule?.id ?: 0,
                                    playlistUrl = url,
                                    playlistName = nameInput.text?.toString().orEmpty(),
                                    keywords = keywords,
                                    enabled = enabled.isChecked,
                                    applyToExistingVideos = applyExisting.isChecked
                                )
                            )
                        }.onSuccess {
                            withContext(Dispatchers.Main) { dialog.dismiss() }
                        }.onFailure {
                            withContext(Dispatchers.Main) {
                                saveButton.isEnabled = true
                                Toast.makeText(
                                    requireContext(),
                                    R.string.automatic_keyword_save_failed,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
            dialog.show()
        }
    }

    private fun confirmDelete(ruleId: Long) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.automatic_keyword_delete_title)
            .setMessage(R.string.automatic_keyword_delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { repository.delete(ruleId) }
            }
            .show()
    }

    private fun actionButton(text: Int, click: () -> Unit) =
        MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(text)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { click() }
        }

    private fun wrapInput(view: View) = TextInputLayout(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(view)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
