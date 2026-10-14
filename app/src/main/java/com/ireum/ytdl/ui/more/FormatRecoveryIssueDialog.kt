package com.ireum.ytdl.ui.more

import android.util.Log
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.FormatRecoveryIssue
import com.ireum.ytdl.work.FormatRecoveryIssues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One format-only entry on the existing More screen; no Download-ID error or retry action. */
internal object FormatRecoveryIssueDialog {
    fun bind(fragment: Fragment, entry: TextView) {
        val context = fragment.requireContext().applicationContext
        val owner = fragment.viewLifecycleOwner
        owner.lifecycleScope.launch {
            val db = withContext(Dispatchers.IO) { DBManager.getInstance(context) }
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    withContext(Dispatchers.IO) { FormatRecoveryIssues.discoverRetained(context, db) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Restore may currently bar conversion; opening the entry is another explicit wakeup.
                    Log.w("FormatRecovery", "Retained issue discovery unavailable", failure)
                }
                FormatRecoveryIssues.observeOpen(db).collect { issues ->
                    entry.text = fragment.getString(R.string.format_recovery_issue_count, issues.size)
                }
            }
        }
        entry.setOnClickListener {
            owner.lifecycleScope.launch {
                try {
                    val db = withContext(Dispatchers.IO) { DBManager.getInstance(context) }
                    val issues = withContext(Dispatchers.IO) { FormatRecoveryIssues.discoverRetained(context, db) }
                    if (fragment.isAdded) showList(fragment, db, issues)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    showUnavailable(fragment)
                }
            }
        }
    }

    private fun showList(fragment: Fragment, db: DBManager, issues: List<FormatRecoveryIssue>) {
        val context = fragment.requireContext().applicationContext
        val builder = MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.format_recovery_issues)
            .setNegativeButton(R.string.cancel, null)
        if (issues.isEmpty()) {
            builder.setMessage(R.string.format_recovery_no_issues).show()
            return
        }
        // Labels are presentation only. The selected immutable UUID-bearing issue reaches the CAS.
        builder.setItems(issues.indices.map { index ->
            fragment.getString(R.string.format_recovery_issue_number, index + 1)
        }.toTypedArray()) { _, index ->
            val expected = issues[index]
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(fragment.getString(R.string.format_recovery_issue_number, index + 1))
                .setMessage(R.string.format_recovery_issue_explanation)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.format_recovery_dismiss) { _, _ ->
                    fragment.viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val changed = withContext(Dispatchers.IO) {
                                FormatRecoveryIssues.acknowledge(context, db, expected)
                            }
                            if (!changed) showUnavailable(fragment)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            showUnavailable(fragment)
                        }
                    }
                }.show()
        }.show()
    }

    private fun showUnavailable(fragment: Fragment) {
        if (!fragment.isAdded || fragment.view == null) return
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.format_recovery_issues)
            .setMessage(R.string.format_recovery_unavailable)
            .setPositiveButton(R.string.ok, null)
            .show()
    }
}
