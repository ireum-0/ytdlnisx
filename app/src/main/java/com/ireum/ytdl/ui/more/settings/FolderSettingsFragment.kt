package com.ireum.ytdl.ui.more.settings

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build.VERSION
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.UiUtil
import com.ireum.ytdl.util.storage.AppCacheCategory
import com.ireum.ytdl.util.storage.AppCacheCategorySnapshot
import com.ireum.ytdl.util.storage.AppCacheManager
import com.ireum.ytdl.util.storage.AppCacheScan
import com.ireum.ytdl.util.storage.HistoryReferenceMutationCoordinator
import com.ireum.ytdl.work.MoveCacheFilesWorker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.io.File
import java.text.DateFormat
import java.util.Date


class FolderSettingsFragment : BaseSettingsFragment() {
    override val title: Int = R.string.directories

    private var musicPath: Preference? = null
    private var videoPath: Preference? = null
    private var commandPath: Preference? = null
    private var cachePath: Preference? = null
    private var accessAllFiles : Preference? = null
    private var noFragments: SwitchPreferenceCompat? = null
    private var keepFragments: SwitchPreferenceCompat? = null
    private var cacheDownloads : Preference? = null
    private var audioFilenameTemplate : Preference? = null
    private var videoFilenameTemplate : Preference? = null
    private var migrateDefaultVideoFolder: Preference? = null
    private var clearCache: Preference? = null
    private var clearLogs: Preference? = null
    private var moveCache: Preference? = null
    private lateinit var preferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor

    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var appCacheManager: AppCacheManager
    private var activeDownloadCount = 0

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.folders_preference, rootKey)

        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        editor = preferences.edit()
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        appCacheManager = AppCacheManager(requireContext().applicationContext)

        musicPath = findPreference("music_path")
        videoPath = findPreference("video_path")
        commandPath = findPreference("command_path")
        cachePath = findPreference("cache_path")
        accessAllFiles = findPreference("access_all_files")
        noFragments = findPreference("no_part")
        keepFragments = findPreference("keep_cache")
        cacheDownloads = findPreference("cache_downloads")
        videoFilenameTemplate = findPreference("file_name_template")
        audioFilenameTemplate = findPreference("file_name_template_audio")
        migrateDefaultVideoFolder = findPreference("migrate_default_video_folder")
        clearCache = findPreference("clear_cache")
        clearLogs = findPreference("clear_logs")
        moveCache = findPreference("move_cache")

        if (preferences.getString("music_path", "")!!.isEmpty()) {
            editor.putString("music_path", FileUtil.getDefaultAudioPath()).apply()
        }
        if (preferences.getString("video_path", "")!!.isEmpty()) {
            editor.putString("video_path", FileUtil.getDefaultVideoPath()).apply()
        }
        if (preferences.getString("command_path", "")!!.isEmpty()) {
            editor.putString("command_path", FileUtil.getDefaultCommandPath()).apply()
        }
        if (preferences.getString("cache_path", "")!!.isEmpty()) {
            editor.putString("cache_path", FileUtil.getCachePath(requireContext())).apply()
        }

        if (FileUtil.hasAllFilesAccess()) {
            accessAllFiles!!.isVisible = false
            cacheDownloads!!.isEnabled = true
        }else{
            editor.putBoolean("cache_downloads", true).apply()
            cacheDownloads!!.isEnabled = false
        }

        musicPath!!.summary = FileUtil.formatPath(preferences.getString("music_path", "")!!)
        musicPath!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                musicPathResultLauncher.launch(intent)
                true
            }
        videoPath!!.summary = FileUtil.formatPath(preferences.getString("video_path", "")!!)
        videoPath!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                videoPathResultLauncher.launch(intent)
                true
            }
        commandPath!!.summary = FileUtil.formatPath(preferences.getString("command_path", "")!!)
        commandPath!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                commandPathResultLauncher.launch(intent)
                true
            }

        cachePath!!.summary = FileUtil.formatPath(preferences.getString("cache_path", FileUtil.getCachePath(requireContext()))!!)
        cachePath!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                UiUtil.showGenericConfirmDialog(requireContext(), getString(R.string.cache_directory), getString(R.string.cache_directory_warning)) {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    cachePathResultLauncher.launch(intent)
                }
                true
            }

        if(VERSION.SDK_INT >= 30){
            accessAllFiles!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    val uri = Uri.parse("package:" + requireContext().packageName)
                    intent.data = uri
                    startActivity(intent)
                    true
                }
        }

        if (noFragments!!.isChecked) {
            editor.putBoolean("keep_cache", false).apply()
            keepFragments!!.isChecked = false
            keepFragments!!.isEnabled = false
        }
        noFragments!!.setOnPreferenceChangeListener { _, newValue ->
            if(newValue as Boolean){
                editor.putBoolean("keep_cache", false).apply()
                keepFragments!!.isChecked = false
                keepFragments!!.isEnabled = false
            }else{
                keepFragments!!.isEnabled = true
            }
            true
        }

        videoFilenameTemplate?.title = "${getString(R.string.file_name_template)} [${getString(R.string.video)}]"
        videoFilenameTemplate?.summary = preferences.getString("file_name_template", "%(uploader).30B - %(title).170B")
        audioFilenameTemplate?.title = "${getString(R.string.file_name_template)} [${getString(R.string.audio)}]"
        audioFilenameTemplate?.summary = preferences.getString("file_name_template_audio", "%(uploader).30B - %(title).170B")

        videoFilenameTemplate?.setOnPreferenceClickListener {
            UiUtil.showFilenameTemplateDialog(requireActivity(),videoFilenameTemplate?.summary.toString() ?: "", "${getString(R.string.file_name_template)} [${getString(R.string.video)}]") {
                editor.putString("file_name_template", it).apply()
                videoFilenameTemplate?.summary = it
            }
            false
        }

        audioFilenameTemplate?.setOnPreferenceClickListener {
            UiUtil.showFilenameTemplateDialog(requireActivity(), audioFilenameTemplate?.summary.toString() ?: "", "${getString(R.string.file_name_template)} [${getString(R.string.audio)}]") {
                editor.putString("file_name_template_audio", it).apply()
                audioFilenameTemplate?.summary = it
            }
            false
        }

        migrateDefaultVideoFolder?.setOnPreferenceClickListener {
            UiUtil.showGenericConfirmDialog(
                requireContext(),
                getString(R.string.migrate_default_video_folder),
                getString(R.string.migrate_default_video_folder_confirm)
            ) {
                lifecycleScope.launch {
                    val rootView = requireView()
                    activeDownloadCount = withContext(Dispatchers.IO) {
                        downloadViewModel.getActiveDownloadsCount()
                    }
                    if (activeDownloadCount > 0) {
                        Snackbar.make(rootView, getString(R.string.downloads_running_try_later), Snackbar.LENGTH_SHORT).show()
                        return@launch
                    }
                    val progressSnack = Snackbar.make(
                        rootView,
                        getString(R.string.migrate_default_video_folder_progress, 0, 0),
                        Snackbar.LENGTH_INDEFINITE
                    )
                    progressSnack.show()
                    val result = withContext(Dispatchers.IO) {
                        migrateDefaultVideoFolderInternal { done, total ->
                            withContext(Dispatchers.Main) {
                                progressSnack.setText(
                                    getString(R.string.migrate_default_video_folder_progress, done, total)
                                )
                                showVideoMigrationProgressNotification(done, total)
                            }
                        }
                    }
                    progressSnack.dismiss()
                    result.also {
                        if (result.movedFiles == 0 && result.updatedCards == 0 && result.failedFiles == 0) {
                            cancelVideoMigrationNotification()
                            Snackbar.make(rootView, getString(R.string.migrate_default_video_folder_nothing), Snackbar.LENGTH_LONG).show()
                            return@also
                        }
                        val doneText = getString(
                            R.string.migrate_default_video_folder_done,
                            result.movedFiles,
                            result.updatedCards
                        )
                        if (result.failedFiles > 0) {
                            Snackbar.make(
                                rootView,
                                "$doneText, ${getString(R.string.migrate_default_video_folder_failed, result.failedFiles)}",
                                Snackbar.LENGTH_LONG
                            ).show()
                            showVideoMigrationFinishedNotification(
                                "$doneText, ${getString(R.string.migrate_default_video_folder_failed, result.failedFiles)}"
                            )
                        } else {
                            Snackbar.make(rootView, doneText, Snackbar.LENGTH_LONG).show()
                            showVideoMigrationFinishedNotification(doneText)
                        }
                    }
                }
            }
            true
        }

        refreshCacheSummary()
        clearCache!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            lifecycleScope.launch {
                if (hasActiveDownloads()) {
                    Snackbar.make(
                        requireView(),
                        getString(R.string.downloads_running_try_later),
                        Snackbar.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val scan = withContext(Dispatchers.IO) { appCacheManager.scan() }
                showCacheCategorySelection(scan)
            }
            true
        }

        clearLogs!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            lifecycleScope.launch {
                if (hasActiveDownloads()) {
                    Snackbar.make(
                        requireView(),
                        getString(R.string.downloads_running_try_later),
                        Snackbar.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val estimatedBytes = withContext(Dispatchers.IO) {
                    val databaseBytes = DBManager.getInstance(requireContext()).logDao.getTotalContentSize()
                    val stagedBytes = appCacheManager.scan().categories
                        .firstOrNull { it.category == AppCacheCategory.LOG_EXPORT_CACHE }
                        ?.bytes ?: 0L
                    databaseBytes + stagedBytes
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.clear_download_logs))
                    .setMessage(
                        getString(
                            R.string.confirm_clear_download_logs,
                            readableSize(estimatedBytes)
                        )
                    )
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.ok)) { _, _ -> clearDownloadLogs() }
                    .show()
            }
            true
        }

        moveCache!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                val workRequest = OneTimeWorkRequestBuilder<MoveCacheFilesWorker>()
                    .addTag("cacheFiles")
                    .build()

                WorkManager.getInstance(requireContext()).beginUniqueWork(
                    System.currentTimeMillis().toString(),
                    ExistingWorkPolicy.KEEP,
                    workRequest
                ).enqueue()

                WorkManager.getInstance(requireContext())
                    .getWorkInfosByTagLiveData("cacheFiles")
                    .observe(viewLifecycleOwner){ list ->
                        if (list == null) return@observe
                        if (list.first() == null) return@observe

                        if (list.first().state == WorkInfo.State.SUCCEEDED){
                            refreshCacheSummary()
                        }
                    }

                true
            }


        findPreference<Preference>("reset_preferences")?.setOnPreferenceClickListener {
            UiUtil.showGenericConfirmDialog(requireContext(), getString(R.string.reset), getString(R.string.reset_preferences_in_screen)) {
                resetPreferences(editor, R.xml.folders_preference)
                requireActivity().recreate()
                val fragmentId = findNavController().currentDestination?.id
                findNavController().popBackStack(fragmentId!!,true)
                findNavController().navigate(fragmentId)
            }
            true
        }

    }

    private suspend fun hasActiveDownloads(): Boolean {
        val hasActiveWork = withContext(Dispatchers.IO) {
            activeDownloadCount = downloadViewModel.getActiveDownloadsCount()
            val activeTerminalCount = DBManager.getInstance(requireContext())
                .terminalDao
                .getActiveTerminalsCount()
            activeDownloadCount > 0 || activeTerminalCount > 0
        }
        return hasActiveWork
    }

    private fun refreshCacheSummary() {
        lifecycleScope.launch {
            val scan = withContext(Dispatchers.IO) { appCacheManager.scan() }
            if (!isAdded) return@launch
            val scannedAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(scan.scannedAtMillis))
            val removableBytes = scan.categories
                .filter { it.category != AppCacheCategory.LOG_EXPORT_CACHE }
                .sumOf(AppCacheCategorySnapshot::bytes)
            clearCache?.summary = getString(
                R.string.cache_scan_summary,
                readableSize(removableBytes),
                scannedAt
            )
        }
    }

    private fun showCacheCategorySelection(scan: AppCacheScan) {
        val categories = scan.categories.filter {
            it.available && it.category != AppCacheCategory.LOG_EXPORT_CACHE
        }
        if (categories.isEmpty()) {
            Snackbar.make(requireView(), getString(R.string.cache_delete_failed), Snackbar.LENGTH_LONG).show()
            return
        }
        val checked = BooleanArray(categories.size) { index -> categories[index].bytes > 0L }
        val labels = categories.map { snapshot ->
            "${cacheCategoryName(snapshot.category)} (${readableSize(snapshot.bytes)})"
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.select_cache_categories))
            .setMultiChoiceItems(labels, checked) { _, which, selected -> checked[which] = selected }
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val selected = categories.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    Snackbar.make(
                        requireView(),
                        getString(R.string.select_at_least_one_category),
                        Snackbar.LENGTH_LONG
                    ).show()
                } else {
                    confirmCacheDeletion(selected)
                }
            }
            .show()
    }

    private fun confirmCacheDeletion(categories: List<AppCacheCategorySnapshot>) {
        val categoryNames = categories.joinToString { cacheCategoryName(it.category) }
        val bytes = categories.sumOf(AppCacheCategorySnapshot::bytes)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.clear_temporary_files))
            .setMessage(
                getString(
                    R.string.confirm_cache_deletion,
                    categoryNames,
                    readableSize(bytes)
                )
            )
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                deleteCacheCategories(categories.map { it.category }.toSet())
            }
            .show()
    }

    private fun deleteCacheCategories(categories: Set<AppCacheCategory>) {
        lifecycleScope.launch {
            if (hasActiveDownloads()) {
                Snackbar.make(
                    requireView(),
                    getString(R.string.downloads_running_try_later),
                    Snackbar.LENGTH_SHORT
                ).show()
                return@launch
            }
            val result = withContext(Dispatchers.IO) { appCacheManager.delete(categories) }
            val unresolved = result.failedEntries + result.skippedCategories.size
            val message = when {
                result.isComplete -> getString(
                    R.string.cache_delete_complete,
                    result.deletedFiles,
                    readableSize(result.deletedBytes)
                )
                result.isPartial -> getString(
                    R.string.cache_delete_partial,
                    result.deletedFiles,
                    readableSize(result.deletedBytes),
                    unresolved
                )
                else -> getString(R.string.cache_delete_failed)
            }
            Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG).show()
            refreshCacheSummary()
        }
    }

    private fun clearDownloadLogs() {
        lifecycleScope.launch {
            if (hasActiveDownloads()) {
                Snackbar.make(
                    requireView(),
                    getString(R.string.downloads_running_try_later),
                    Snackbar.LENGTH_SHORT
                ).show()
                return@launch
            }
            val cleared = withContext(Dispatchers.IO) {
                runCatching {
                    val db = DBManager.getInstance(requireContext())
                    db.logDao.deleteAll()
                    db.downloadDao.removeAllLogID()
                    appCacheManager.delete(setOf(AppCacheCategory.LOG_EXPORT_CACHE))
                }.getOrNull()
            }
            val message = when {
                cleared == null -> getString(R.string.cache_delete_failed)
                cleared.isComplete -> getString(R.string.download_logs_cleared)
                else -> getString(R.string.download_logs_cleared_partial)
            }
            Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG).show()
            refreshCacheSummary()
        }
    }

    private fun cacheCategoryName(category: AppCacheCategory): String {
        return getString(
            when (category) {
                AppCacheCategory.APP_CACHE -> R.string.cache_category_app
                AppCacheCategory.EXTERNAL_APP_CACHE -> R.string.cache_category_external
                AppCacheCategory.DOWNLOAD_TEMP -> R.string.cache_category_download_temp
                AppCacheCategory.SHARE_CACHE -> R.string.cache_category_share
                AppCacheCategory.TERMINAL_CACHE -> R.string.cache_category_terminal
                AppCacheCategory.LOG_EXPORT_CACHE -> R.string.cache_category_logs
            }
        )
    }

    private fun readableSize(bytes: Long): String {
        return if (bytes <= 0L) "0 B" else FileUtil.convertFileSize(bytes)
    }

    override fun onResume() {
        if((VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) ||
            VERSION.SDK_INT < 30) {
            accessAllFiles!!.isVisible = false
            cacheDownloads!!.isEnabled = true
        }else{
            editor.putBoolean("cache_downloads", true).apply()
            cacheDownloads!!.isEnabled = false
        }
        super.onResume()
    }

    private var musicPathResultLauncher = registerForActivityResult(
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
            changePath(musicPath, result.data, MUSIC_PATH_CODE)
        }
    }
    private var videoPathResultLauncher = registerForActivityResult(
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
            changePath(videoPath, result.data, VIDEO_PATH_CODE)
        }
    }
    private var commandPathResultLauncher = registerForActivityResult(
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
            changePath(commandPath, result.data, COMMAND_PATH_CODE)
        }
    }
    private var cachePathResultLauncher = registerForActivityResult(
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
            changePath(cachePath, result.data, CACHE_PATH_CODE)
        }
    }

    private fun changePath(p: Preference?, data: Intent?, requestCode: Int) {
        val path = data!!.data.toString()
        p!!.summary = FileUtil.formatPath(data.data.toString())
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val editor = sharedPreferences.edit()
        when (requestCode) {
            MUSIC_PATH_CODE -> editor.putString("music_path", path)
            VIDEO_PATH_CODE -> editor.putString("video_path", path)
            COMMAND_PATH_CODE -> editor.putString("command_path", path)
            CACHE_PATH_CODE -> editor.putString("cache_path", path)
        }
        editor.apply()
    }

    private data class VideoFolderMigrationResult(
        val movedFiles: Int,
        val updatedCards: Int,
        val failedFiles: Int
    )

    private suspend fun migrateDefaultVideoFolderInternal(
        onProgress: suspend (done: Int, total: Int) -> Unit
    ): VideoFolderMigrationResult = withContext(Dispatchers.IO) {
        HistoryReferenceMutationCoordinator.withLock {
        val db = DBManager.getInstance(requireContext())
        val historyDao = db.historyDao
        val sourceRoot = File(FileUtil.getDefaultVideoPath())
        if (!sourceRoot.exists() || !sourceRoot.isDirectory) {
            return@withLock VideoFolderMigrationResult(0, 0, 0)
        }

        val destinationRoot = preferences.getString("video_path", FileUtil.getDefaultVideoPath())
            ?: FileUtil.getDefaultVideoPath()
        val sourceNormalized = sourceRoot.absolutePath.replace('\\', '/')
        val destinationNormalized = destinationRoot.replace('\\', '/')
        if (destinationNormalized.equals(sourceNormalized, ignoreCase = true)) {
            return@withLock VideoFolderMigrationResult(0, 0, 0)
        }

        val historyItems = historyDao.getAll().filter { it.type == DownloadType.video }
        val candidates = historyItems
            .flatMap { item -> item.downloadPath }
            .filter { oldPath ->
                if (oldPath.startsWith("content://")) return@filter false
                val oldFile = File(oldPath)
                if (!oldFile.exists()) return@filter false
                val oldParent = oldFile.parentFile?.absolutePath?.replace('\\', '/') ?: return@filter false
                oldParent.equals(sourceNormalized, ignoreCase = true)
            }
        val totalCandidates = candidates.size
        onProgress(0, totalCandidates)

        var movedFiles = 0
        var updatedCards = 0
        var failedFiles = 0
        val movedPathMap = linkedMapOf<String, String>()
        var processed = 0

        historyItems.forEach { item ->
            var changed = false
            val updatedPaths = item.downloadPath.map { oldPath ->
                movedPathMap[oldPath]?.let {
                    changed = true
                    processed += 1
                    onProgress(processed, totalCandidates)
                    return@map it
                }
                if (oldPath.startsWith("content://")) return@map oldPath
                val oldFile = File(oldPath)
                if (!oldFile.exists()) return@map oldPath
                val oldParent = oldFile.parentFile?.absolutePath?.replace('\\', '/') ?: return@map oldPath
                if (!oldParent.equals(sourceNormalized, ignoreCase = true)) return@map oldPath

                val movedPath = moveFileToDestination(oldFile, destinationRoot)
                if (movedPath != null) {
                    movedPathMap[oldPath] = movedPath
                    movedFiles += 1
                    changed = true
                    processed += 1
                    onProgress(processed, totalCandidates)
                    movedPath
                } else {
                    failedFiles += 1
                    processed += 1
                    onProgress(processed, totalCandidates)
                    oldPath
                }
            }
            if (changed && updatedPaths != item.downloadPath) {
                historyDao.updateDownloadPathById(item.id, updatedPaths)
                updatedCards += 1
            }
        }

        VideoFolderMigrationResult(movedFiles, updatedCards, failedFiles)
        }
    }

    private fun moveFileToDestination(sourceFile: File, destinationRoot: String): String? {
        return if (destinationRoot.startsWith("content://")) {
            moveFileToContentTree(sourceFile, destinationRoot)
        } else {
            moveFileToFileDirectory(sourceFile, destinationRoot)
        }
    }

    private fun moveFileToFileDirectory(sourceFile: File, destinationRoot: String): String? {
        val destinationDir = File(destinationRoot)
        if (!destinationDir.exists() && !destinationDir.mkdirs()) return null
        val destinationFile = resolveUniqueFile(destinationDir, sourceFile.name)
        if (sourceFile.absolutePath.equals(destinationFile.absolutePath, ignoreCase = true)) {
            return sourceFile.absolutePath
        }
        val renamed = runCatching { sourceFile.renameTo(destinationFile) }.getOrDefault(false)
        if (renamed) return destinationFile.absolutePath
        return runCatching {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (!sourceFile.delete()) return null
            destinationFile.absolutePath
        }.getOrNull()
    }

    private fun moveFileToContentTree(sourceFile: File, destinationRoot: String): String? {
        val tree = DocumentFile.fromTreeUri(requireContext(), Uri.parse(destinationRoot)) ?: return null
        val destinationName = resolveUniqueNameForTree(tree, sourceFile.name)
        val mimeType = mimeTypeForName(destinationName)
        val created = tree.createFile(mimeType, destinationName) ?: return null
        return runCatching {
            requireContext().contentResolver.openOutputStream(created.uri)?.use { output ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(output)
                }
            } ?: return null
            if (!sourceFile.delete()) return null
            created.uri.toString()
        }.getOrNull()
    }

    private fun resolveUniqueFile(directory: File, filename: String): File {
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var candidate = File(directory, filename)
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index)$ext")
            index += 1
        }
        return candidate
    }

    private fun resolveUniqueNameForTree(tree: DocumentFile, filename: String): String {
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var candidate = filename
        var index = 1
        while (tree.findFile(candidate) != null) {
            candidate = "$base ($index)$ext"
            index += 1
        }
        return candidate
    }

    private fun mimeTypeForName(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.getDefault())
        if (ext.isBlank()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }


    private fun canPostNotification(): Boolean {
        if (VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission")
    private fun showVideoMigrationProgressNotification(done: Int, total: Int) {
        if (!canPostNotification()) return
        val content = getString(R.string.migrate_default_video_folder_progress, done, total)
        val notification = NotificationCompat.Builder(requireContext(), NotificationUtil.DOWNLOAD_MISC_CHANNEL_ID)
            .setContentTitle(getString(R.string.migrate_default_video_folder))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(if (total > 0) total else 1, done.coerceAtLeast(0), total <= 0)
            .build()
        NotificationManagerCompat.from(requireContext()).notify(VIDEO_MIGRATION_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun showVideoMigrationFinishedNotification(content: String) {
        if (!canPostNotification()) return
        val notification = NotificationCompat.Builder(requireContext(), NotificationUtil.DOWNLOAD_MISC_CHANNEL_ID)
            .setContentTitle(getString(R.string.migrate_default_video_folder))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(R.drawable.ic_launcher_foreground_large)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setProgress(0, 0, false)
            .build()
        NotificationManagerCompat.from(requireContext()).notify(VIDEO_MIGRATION_NOTIFICATION_ID, notification)
    }

    private fun cancelVideoMigrationNotification() {
        if (!canPostNotification()) return
        NotificationManagerCompat.from(requireContext()).cancel(VIDEO_MIGRATION_NOTIFICATION_ID)
    }

    companion object {
        const val MUSIC_PATH_CODE = 33333
        const val VIDEO_PATH_CODE = 55555
        const val COMMAND_PATH_CODE = 77777
        const val CACHE_PATH_CODE = 99999
        private const val VIDEO_MIGRATION_NOTIFICATION_ID = 81234
    }
}


