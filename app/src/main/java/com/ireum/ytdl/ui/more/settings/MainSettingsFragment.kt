package com.ireum.ytdl.ui.more.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.LayoutDirection
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.core.text.layoutDirection
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.BuildConfig
import com.ireum.ytdl.MainActivity
import com.ireum.ytdl.R
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.BackupCustomThumbItem
import com.ireum.ytdl.database.models.CommandTemplate
import com.ireum.ytdl.database.models.CookieItem
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.KeywordGroup
import com.ireum.ytdl.database.models.KeywordGroupMember
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.models.SearchHistoryItem
import com.ireum.ytdl.database.models.TemplateShortcut
import com.ireum.ytdl.database.models.YoutuberGroup
import com.ireum.ytdl.database.models.YoutuberGroupMember
import com.ireum.ytdl.database.models.YoutuberGroupRelation
import com.ireum.ytdl.database.models.YoutuberMeta
import com.ireum.ytdl.database.viewmodel.CommandTemplateViewModel
import com.ireum.ytdl.database.viewmodel.CookieViewModel
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.database.viewmodel.HistoryViewModel
import com.ireum.ytdl.database.viewmodel.ObserveSourcesViewModel
import com.ireum.ytdl.database.viewmodel.ResultViewModel
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import com.ireum.ytdl.ui.more.settings.FolderSettingsFragment.Companion.COMMAND_PATH_CODE
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.ThemeUtil
import com.ireum.ytdl.util.UiUtil
import com.ireum.ytdl.util.UpdateUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Calendar
import java.util.Locale


class MainSettingsFragment : PreferenceFragmentCompat() {
    private var backup : Preference? = null
    private var restore : Preference? = null
    private var backupPath : Preference? = null

    private var updateUtil: UpdateUtil? = null
    private var activeDownloadCount = 0

    private lateinit var settingsViewModel: SettingsViewModel

    private lateinit var editor: SharedPreferences.Editor


    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        val navController = findNavController()
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        editor = preferences.edit()

        val appearance = findPreference<Preference>("appearance")
        val separator = if (Locale(preferences.getString("app_language", "en")!!).layoutDirection == LayoutDirection.RTL) "?" else ","
        appearance?.summary = "${if (Build.VERSION.SDK_INT < 33) getString(R.string.language) + "$separator " else ""}${getString(R.string.Theme)}$separator ${getString(R.string.accents)}$separator ${getString(R.string.preferred_search_engine)}"
        appearance?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_appearanceSettingsFragment)
            true
        }

        val folders = findPreference<Preference>("folders")
        folders?.summary = "${getString(R.string.music_directory)}$separator ${getString(R.string.video_directory)}$separator ${getString(R.string.command_directory)}"
        folders?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_folderSettingsFragment)
            true
        }

        val downloading = findPreference<Preference>("downloading")
        downloading?.summary = "${getString(R.string.quick_download)}$separator ${getString(R.string.concurrent_downloads)}$separator ${getString(R.string.limit_rate)}"
        downloading?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_downloadSettingsFragment)
            true
        }

        val processing = findPreference<Preference>("processing")
        processing?.summary = "${getString(R.string.sponsorblock)}$separator ${getString(R.string.embed_subtitles)}$separator ${getString(R.string.add_chapters)}"
        processing?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_processingSettingsFragment)
            true
        }

        val updating = findPreference<Preference>("updating")
        updating?.summary = "${getString(R.string.update_ytdl)}$separator ${getString(R.string.update_app)}"
        updating?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_updateSettingsFragment)
            true
        }

        val advanced = findPreference<Preference>("advanced")
        advanced?.summary = "PO Token$separator ${getString(R.string.other_youtube_extractor_args)}"
        advanced?.setOnPreferenceClickListener {
            navController.navigate(R.id.action_mainSettingsFragment_to_advancedSettingsFragment)
            true
        }

        //about section -------------------------
        updateUtil = UpdateUtil(requireContext())

        WorkManager.getInstance(requireContext()).getWorkInfosByTagLiveData("download").observe(this){
            activeDownloadCount = 0
            it.forEach {w ->
                if (w.state == WorkInfo.State.RUNNING) activeDownloadCount++
            }
        }
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        backup = findPreference("backup")
        restore = findPreference("restore")
        backupPath = findPreference("backup_path")

        findPreference<Preference>("package_name")?.apply {
            summary = BuildConfig.APPLICATION_ID
            setOnPreferenceClickListener {
                UiUtil.copyToClipboard(summary.toString(), requireActivity())
                true
            }
        }

        backup!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val builder = MaterialAlertDialogBuilder(requireContext())
                builder.setTitle(getString(R.string.select_backup_categories))
                val values = resources.getStringArray(R.array.backup_category_values)
                val entries = resources.getStringArray(R.array.backup_category_entries)
                val checkedItems : ArrayList<Boolean> = arrayListOf()
                values.forEach { _ ->
                    checkedItems.add(true)
                }

                builder.setMultiChoiceItems(
                    entries,
                    checkedItems.toBooleanArray()
                ) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }

                builder.setPositiveButton(
                    getString(R.string.ok)
                ) { _: DialogInterface?, _: Int ->
                    lifecycleScope.launch {
                        if (checkedItems.all { !it }){
                            Snackbar.make(requireView(), R.string.select_backup_categories, Snackbar.LENGTH_SHORT).show()
                            return@launch
                        }

                        val selectedItems = values.mapIndexed { idx, it -> Pair<String, Boolean>(it, checkedItems[idx]) }.filter { it.second }.map { it.first }
                        val pathResult = withContext(Dispatchers.IO){
                            settingsViewModel.backup(selectedItems)
                        }

                        if (pathResult.isFailure) {
                            val errorMessage = pathResult.exceptionOrNull()?.message ?: requireContext().getString(R.string.errored)
                            val snack = Snackbar.make(requireView(), errorMessage, Snackbar.LENGTH_LONG)
                            val snackbarView: View = snack.view
                            val snackTextView = snackbarView.findViewById<View>(com.google.android.material.R.id.snackbar_text) as TextView
                            snackTextView.maxLines = 9999999
                            snack.setAction(android.R.string.copy){
                                UiUtil.copyToClipboard(errorMessage ?: requireContext().getString(R.string.errored), requireActivity())
                            }
                            snack.show()
                        }else {
                            val s = Snackbar.make(
                                requireView(),
                                getString(R.string.backup_created_successfully),
                                Snackbar.LENGTH_LONG
                            )
                            s.setAction(R.string.Open_File) {
                                FileUtil.openFileIntent(requireActivity(), pathResult.getOrNull()!!)
                            }
                            s.show()
                        }
                    }
                }

                // handle the negative button of the alert dialog
                builder.setNegativeButton(
                    getString(R.string.cancel)
                ) { _: DialogInterface?, _: Int -> }


                val dialog = builder.create()
                dialog.show()
                true
            }
        restore!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                appRestoreResultLauncher.launch(intent)
                true
            }

        backupPath?.apply {
            summary = FileUtil.formatPath(FileUtil.getBackupPath(requireContext()))
            setOnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                backupPathResultLauncher.launch(intent)
                true
            }
        }
    }

    private var backupPathResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let {
                activity?.contentResolver?.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

            val path = result.data!!.data.toString()
            backupPath!!.summary = FileUtil.formatPath(path)
            editor.putString("backup_path", path)
            editor.apply()
        }
    }



    private var appRestoreResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let {
                activity?.contentResolver?.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            lifecycleScope.launch {
                runCatching {
                    val ip = requireContext().contentResolver.openInputStream(result.data!!.data!!)
                    val r = BufferedReader(InputStreamReader(ip))
                    val total: java.lang.StringBuilder = java.lang.StringBuilder()
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        total.append(line).append('\n')
                    }

                    //PARSE RESTORE JSON
                    val gson = Gson()
                    val json = gson.fromJson(total.toString(), JsonObject::class.java)
                    val restoreData = RestoreAppDataItem()
                    val parsedDataMessage = StringBuilder()
                    val backupFormatVersion = runCatching {
                        if (json.has("backup_format_version")) json.get("backup_format_version").asInt else 1
                    }.getOrDefault(1)
                    parsedDataMessage.appendLine("Backup format version: $backupFormatVersion")

                    if (json.has("settings")) {
                        restoreData.settings = json.getAsJsonArray("settings").map {
                            gson.fromJson(it, BackupSettingsItem::class.java)
                        }
                        parsedDataMessage.appendLine("${getString(R.string.settings)}: ${restoreData.settings!!.size}")
                    }

                    if (json.has("downloads")) {
                        restoreData.downloads = json.getAsJsonArray("downloads").map {
                            gson.fromJson(it, HistoryItem::class.java)
                        }
                        parsedDataMessage.appendLine("${getString(R.string.downloads)}: ${restoreData.downloads!!.size}")

                    }

                    if (json.has("custom_thumbnails")) {
                        restoreData.customThumbnails = json.getAsJsonArray("custom_thumbnails").map {
                            gson.fromJson(it, BackupCustomThumbItem::class.java)
                        }
                        parsedDataMessage.appendLine("Custom thumbnails: ${restoreData.customThumbnails!!.size}")
                    }

                    if (json.has("keyword_groups")) {
                        restoreData.keywordGroups = json.getAsJsonArray("keyword_groups").map {
                            gson.fromJson(it, KeywordGroup::class.java)
                        }
                        parsedDataMessage.appendLine("${getString(R.string.keywords)} Groups: ${restoreData.keywordGroups!!.size}")
                    }

                    if (json.has("keyword_group_members")) {
                        restoreData.keywordGroupMembers = json.getAsJsonArray("keyword_group_members").map {
                            gson.fromJson(it, KeywordGroupMember::class.java)
                        }
                        parsedDataMessage.appendLine("${getString(R.string.keywords)} Group Members: ${restoreData.keywordGroupMembers!!.size}")
                    }

                    if (json.has("history_visible_child_keywords")) {
                        restoreData.historyVisibleChildKeywords =
                            json.getAsJsonArray("history_visible_child_keywords")
                                .mapNotNull {
                                    runCatching { it.asString }.getOrNull()
                                }
                                .toSet()
                        parsedDataMessage.appendLine("Visible child keywords: ${restoreData.historyVisibleChildKeywords!!.size}")
                    }

                    if (json.has("youtuber_groups")) {
                        restoreData.youtuberGroups = json.getAsJsonArray("youtuber_groups").map {
                            gson.fromJson(it, YoutuberGroup::class.java)
                        }
                        parsedDataMessage.appendLine("Youtuber Groups: ${restoreData.youtuberGroups!!.size}")
                    }

                    if (json.has("youtuber_group_members")) {
                        restoreData.youtuberGroupMembers = json.getAsJsonArray("youtuber_group_members").map {
                            gson.fromJson(it, YoutuberGroupMember::class.java)
                        }
                        parsedDataMessage.appendLine("Youtuber Group Members: ${restoreData.youtuberGroupMembers!!.size}")
                    }

                    if (json.has("youtuber_group_relations")) {
                        restoreData.youtuberGroupRelations = json.getAsJsonArray("youtuber_group_relations").map {
                            gson.fromJson(it, YoutuberGroupRelation::class.java)
                        }
                        parsedDataMessage.appendLine("Youtuber Group Relations: ${restoreData.youtuberGroupRelations!!.size}")
                    }

                    if (json.has("history_visible_child_youtuber_groups")) {
                        restoreData.historyVisibleChildYoutuberGroups =
                            json.getAsJsonArray("history_visible_child_youtuber_groups")
                                .mapNotNull {
                                    runCatching { it.asString.toLong() }.getOrNull()
                                }
                                .toSet()
                        parsedDataMessage.appendLine("Visible child youtuber groups: ${restoreData.historyVisibleChildYoutuberGroups!!.size}")
                    }

                    if (json.has("youtuber_meta")) {
                        restoreData.youtuberMeta = json.getAsJsonArray("youtuber_meta").map {
                            gson.fromJson(it, YoutuberMeta::class.java)
                        }
                        parsedDataMessage.appendLine("Youtuber Metadata: ${restoreData.youtuberMeta!!.size}")
                    }

                    if (json.has("queued")) {
                        restoreData.queued = json.getAsJsonArray("queued").map {
                            val item =
                                gson.fromJson(it, DownloadItem::class.java)
                            item.id = 0L
                            item
                        }
                        parsedDataMessage.appendLine("${getString(R.string.queue)}: ${restoreData.queued!!.size}")
                    }

                    if (json.has("scheduled")) {
                        restoreData.scheduled = json.getAsJsonArray("scheduled").map {
                            val item =
                                gson.fromJson(it, DownloadItem::class.java)
                            item.id = 0L
                            item
                        }
                        parsedDataMessage.appendLine("${getString(R.string.scheduled)}: ${restoreData.scheduled!!.size}")
                    }

                    if (json.has("cancelled")) {
                        restoreData.cancelled = json.getAsJsonArray("cancelled").map {
                            val item =
                                gson.fromJson(it, DownloadItem::class.java)
                            item.id = 0L
                            item
                        }
                        parsedDataMessage.appendLine("${getString(R.string.cancelled)}: ${restoreData.cancelled!!.size}")
                    }

                    if (json.has("errored")) {
                        restoreData.errored = json.getAsJsonArray("errored").map {
                            val item =
                                gson.fromJson(it, DownloadItem::class.java)
                            item.id = 0L
                            item
                        }
                        parsedDataMessage.appendLine("${getString(R.string.errored)}: ${restoreData.errored!!.size}")
                    }

                    if (json.has("saved")) {
                        restoreData.saved = json.getAsJsonArray("saved").map {
                            val item =
                                gson.fromJson(it, DownloadItem::class.java)
                            item.id = 0L
                            item
                        }
                        parsedDataMessage.appendLine("${getString(R.string.saved)}: ${restoreData.saved!!.size}")
                    }

                    if (json.has("cookies")) {
                        restoreData.cookies = json.getAsJsonArray("cookies").map {
                            val item =
                                gson.fromJson(it, CookieItem::class.java)
                            item.id = 0L
                            item
                        }
                        parsedDataMessage.appendLine("${getString(R.string.cookies)}: ${restoreData.cookies!!.size}")
                    }

                    if (json.has("templates")) {
                        restoreData.templates = json.getAsJsonArray("templates").map {
                            val item = gson.fromJson(
                                it,
                                CommandTemplate::class.java
                            )
                            item.id = 0L
                            item
                        }
                        parsedDataMessage.appendLine("${getString(R.string.command_templates)}: ${restoreData.templates!!.size}")
                    }

                    if (json.has("shortcuts")) {
                        restoreData.shortcuts = json.getAsJsonArray("shortcuts").map {
                            val item = gson.fromJson(
                                it,
                                TemplateShortcut::class.java
                            )
                            item.id = 0L
                            item
                        }

                        parsedDataMessage.appendLine("${getString(R.string.shortcuts)}: ${restoreData.shortcuts!!.size}")

                    }

                    if (json.has("search_history")) {
                        restoreData.searchHistory = json.getAsJsonArray("search_history").map {
                            val item = gson.fromJson(
                                it,
                                SearchHistoryItem::class.java
                            )
                            item.id = 0L
                            item
                        }

                        parsedDataMessage.appendLine("${getString(R.string.search_history)}: ${restoreData.searchHistory!!.size}")
                    }

                    if (json.has("observe_sources")) {
                        restoreData.observeSources = json.getAsJsonArray("observe_sources").map {
                            val item = gson.fromJson(
                                it,
                                ObserveSourcesItem::class.java
                            )
                            item.id = 0L
                            item
                        }

                        parsedDataMessage.appendLine("${getString(R.string.observe_sources)}: ${restoreData.observeSources!!.size}")
                    }

                    showAppRestoreInfoDialog(
                        onMerge = {
                            lifecycleScope.launch {
                                val res = withContext(Dispatchers.IO){
                                    settingsViewModel.restoreData(restoreData, requireContext())
                                }
                                if (res) {
                                    showRestoreFinishedDialog(restoreData, parsedDataMessage.toString())
                                }else{
                                    throw Error()
                                }
                            }
                        },
                        onReset =  {
                            lifecycleScope.launch {
                                val res = withContext(Dispatchers.IO){
                                    settingsViewModel.restoreData(restoreData, requireContext(),true)
                                }
                                if (res) {
                                    showRestoreFinishedDialog(restoreData, parsedDataMessage.toString())
                                }else{
                                    throw Error()
                                }
                            }
                        }
                    )

                }.onFailure {
                    it.printStackTrace()
                    Snackbar.make(requireView(), it.message.toString(), Snackbar.LENGTH_INDEFINITE).show()
                }
            }

        }
    }



    @SuppressLint("RestrictedApi")
    private fun showAppRestoreInfoDialog(onMerge: () -> Unit, onReset: () -> Unit){
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(getString(R.string.restore))
        builder.setMessage(getString(R.string.restore_info))

        builder.setNegativeButton(getString(R.string.cancel)) { dialog : DialogInterface?, _: Int ->
            dialog?.dismiss()
        }

        builder.setPositiveButton(getString(R.string.restore)) { dialog : DialogInterface?, _: Int ->
            onMerge()
            dialog?.dismiss()
        }

        builder.setNeutralButton(getString(R.string.reset)) { dialog : DialogInterface?, _: Int ->
            onReset()
            dialog?.dismiss()
        }


        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).gravity = Gravity.START
    }

    private fun showRestoreFinishedDialog(data: RestoreAppDataItem, restoreDataMessage: String) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(getString(R.string.restore))
        builder.setMessage("${getString(R.string.restore_complete)}\n\n$restoreDataMessage")
        builder.setCancelable(false)
        builder.setPositiveButton(
            getString(R.string.ok)
        ) { _: DialogInterface?, _: Int ->
            if(data.settings != null){
                val languageValue = preferences.getString("app_language", "en")
                if (languageValue == "system") {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(null))
                }else{
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageValue.toString()))
                }
            }
            ThemeUtil.recreateAllActivities()
        }

        val dialog = builder.create()
        dialog.show()
    }
}


