package com.ireum.ytdl.ui.more.settings.advanced

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ireum.ytdl.R
import com.ireum.ytdl.ui.adapter.SortableTextItemAdapter
import com.ireum.ytdl.ui.more.settings.BaseSettingsFragment
import com.ireum.ytdl.util.UiUtil
import com.ireum.ytdl.util.runtime.RuntimeDiagnostics
import com.ireum.ytdl.util.runtime.RuntimeDiagnosticsFormatter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


class AdvancedSettingsFragment : BaseSettingsFragment() {
    override val title: Int = R.string.advanced
    @SuppressLint("RestrictedApi")
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.advanced_preferences, rootKey)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        val editor = prefs.edit()

        findPreference<Preference>("yt_player_client")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.youtubePlayerClientFragment)
            false
        }

        findPreference<Preference>("generate_po_tokens")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.generateYoutubePoTokensFragment)
            false
        }

        findPreference<Preference>("runtime_diagnostics")?.setOnPreferenceClickListener {
            showRuntimeDiagnostics()
            true
        }

        val formatImportanceAudio: Preference? = findPreference("format_importance_audio")
        val formatImportanceVideo: Preference? = findPreference("format_importance_video")

        formatImportanceAudio?.apply {
            title = "${getString(R.string.format_importance)} [${getString(R.string.audio)}]"
            val items = requireContext().resources.getStringArray(R.array.format_importance_audio)
            val itemValues = requireContext().resources.getStringArray(R.array.format_importance_audio_values).toSet()
            val prefVideo = prefs.getString("format_importance_audio", itemValues.joinToString(","))!!
            summary = prefVideo.split(",").mapIndexed { index, s -> "${index + 1}. ${items[itemValues.indexOf(s)]}" }.joinToString("\n")

            setOnPreferenceClickListener {
                val pref = prefs.getString("format_importance_audio", itemValues.joinToString(","))!!
                val prefArr = pref.split(",")
                val itms = itemValues.sortedBy { prefArr.indexOf(it) }.map {
                    Pair(it, items[itemValues.indexOf(it)])
                }.toMutableList()

                showFormatImportanceDialog(title.toString(), itms) { new ->
                    editor.putString("format_importance_audio", new.joinToString(",") { it.first }).apply()
                    formatImportanceAudio.summary = new.map { it.second }.mapIndexed { index, s -> "${index + 1}. $s" }.joinToString("\n")
                }
                true
            }
        }

        formatImportanceVideo?.apply {
            title = "${getString(R.string.format_importance)} [${getString(R.string.video)}]"
            val items = requireContext().resources.getStringArray(R.array.format_importance_video)
            val itemValues = requireContext().resources.getStringArray(R.array.format_importance_video_values).toSet()
            val prefVideo = prefs.getString("format_importance_video", itemValues.joinToString(","))!!
            summary = prefVideo.split(",").mapIndexed { index, s -> "${index + 1}. ${items[itemValues.indexOf(s)]}" }.joinToString("\n")

            setOnPreferenceClickListener {
                val pref = prefs.getString("format_importance_video", itemValues.joinToString(","))!!
                val prefArr = pref.split(",")
                val itms = itemValues.sortedBy { prefArr.indexOf(it) }.map {
                    Pair(it, items[itemValues.indexOf(it)])
                }.toMutableList()

                showFormatImportanceDialog(title.toString(), itms) {new ->
                    editor.putString("format_importance_video", new.joinToString(",") { it.first }).apply()
                    formatImportanceVideo.summary = new.map { it.second }.mapIndexed { index, s -> "${index + 1}. $s" }.joinToString("\n")
                }
                true
            }
        }

        findPreference<Preference>("reset_preferences")?.setOnPreferenceClickListener {
            UiUtil.showGenericConfirmDialog(requireContext(), getString(R.string.reset), getString(R.string.reset_preferences_in_screen)) {
                resetPreferences(editor, R.xml.downloading_preferences)
                requireActivity().recreate()
                val fragmentId = findNavController().currentDestination?.id
                findNavController().popBackStack(fragmentId!!,true)
                findNavController().navigate(fragmentId)
            }
            true
        }

    }

    private fun showRuntimeDiagnostics() {
        var diagnosticJob: Job? = null
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.runtime_diagnostics))
            .setMessage(getString(R.string.runtime_diagnostics_running))
            .setNegativeButton(getString(R.string.cancel), null)
            .setCancelable(false)
            .create()
        progressDialog.setOnShowListener {
            progressDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                diagnosticJob?.cancel()
                progressDialog.dismiss()
            }
        }
        progressDialog.show()
        diagnosticJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val report = RuntimeDiagnostics(requireContext()).run()
                if (!isAdded) {
                    progressDialog.dismiss()
                    return@launch
                }
                progressDialog.dismiss()
                showRuntimeDiagnosticsResult(RuntimeDiagnosticsFormatter.format(requireContext(), report))
            } catch (_: CancellationException) {
                progressDialog.dismiss()
            }
        }
    }

    private fun showRuntimeDiagnosticsResult(result: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.runtime_diagnostics))
            .setMessage(result)
            .setPositiveButton(getString(R.string.ok), null)
            .setNeutralButton(getString(R.string.copy)) { _, _ ->
                UiUtil.copyToClipboard(result, requireActivity())
            }
            .setNegativeButton(getString(R.string.run_again)) { _, _ ->
                showRuntimeDiagnostics()
            }
            .show()
    }



    private fun showFormatImportanceDialog(t: String, items: MutableList<Pair<String, String>>, onChange: (items: List<Pair<String, String>>) -> Unit){
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(t)
        val adapter = SortableTextItemAdapter(items)
        val itemTouchCallback = object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
                return makeMovementFlags(dragFlags, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val itemToMove = adapter.items[viewHolder.absoluteAdapterPosition]
                adapter.items.remove(itemToMove)
                adapter.items.add(target.absoluteAdapterPosition, itemToMove)

                adapter.notifyItemMoved(
                    viewHolder.absoluteAdapterPosition,
                    target.absoluteAdapterPosition
                )
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // do nothing
            }
        }

        val linear = LinearLayout(requireActivity())
        linear.orientation = LinearLayout.VERTICAL

        val note = TextView(requireActivity())
        note.text = getString(R.string.format_importance_note)
        note.textSize = 16f
        note.setTypeface(note.typeface, Typeface.BOLD)
        note.setPadding(20,20,20,20)
        linear.addView(note)

        val recycler = RecyclerView(requireContext())
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        linear.addView(recycler)

        val itemTouchHelper = ItemTouchHelper(itemTouchCallback)
        itemTouchHelper.attachToRecyclerView(recycler)


        builder.setView(linear)
        builder.setPositiveButton(
            getString(android.R.string.ok)
        ) { _: DialogInterface?, _: Int ->
            onChange(adapter.items)
        }

        // handle the negative button of the alert dialog
        builder.setNegativeButton(
            getString(R.string.cancel)
        ) { _: DialogInterface?, _: Int -> }

        val dialog = builder.create()
        dialog.show()
    }
}
