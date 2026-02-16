package com.ireum.ytdl.database.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.Base64
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.BuildConfig
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.dao.KeywordGroupDao
import com.ireum.ytdl.database.dao.PlaylistDao
import com.ireum.ytdl.database.dao.PlaylistGroupDao
import com.ireum.ytdl.database.dao.YoutuberGroupDao
import com.ireum.ytdl.database.dao.YoutuberMetaDao
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.BackupCustomThumbItem
import com.ireum.ytdl.database.repository.CommandTemplateRepository
import com.ireum.ytdl.database.repository.CookieRepository
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.repository.SearchHistoryRepository
import com.ireum.ytdl.util.BackupSettingsUtil
import com.ireum.ytdl.util.FileUtil
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit


class SettingsViewModel(private val application: Application) : AndroidViewModel(application) {
    private val prefVisibleChildYoutuberGroupsKey = "history_visible_child_youtuber_groups"
    private val prefVisibleChildYoutubersKey = "history_visible_child_youtubers"
    private val prefVisibleChildKeywordsKey = "history_visible_child_keywords"

    private val workManager : WorkManager = WorkManager.getInstance(application)
    private val preferences : SharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)

    private val historyRepository : HistoryRepository
    private val downloadRepository : DownloadRepository
    private val cookieRepository : CookieRepository
    private val commandTemplateRepository : CommandTemplateRepository
    private val searchHistoryRepository : SearchHistoryRepository
    private val observeSourcesRepository : ObserveSourcesRepository
    private val keywordGroupDao: KeywordGroupDao
    private val youtuberGroupDao: YoutuberGroupDao
    private val youtuberMetaDao: YoutuberMetaDao

    init {
        val dbManager = DBManager.getInstance(application)
        historyRepository = HistoryRepository(dbManager.historyDao, dbManager.playlistDao)
        downloadRepository = DownloadRepository(dbManager.downloadDao)
        cookieRepository = CookieRepository(dbManager.cookieDao)
        commandTemplateRepository = CommandTemplateRepository(dbManager.commandTemplateDao)
        searchHistoryRepository = SearchHistoryRepository(dbManager.searchHistoryDao)
        observeSourcesRepository = ObserveSourcesRepository(dbManager.observeSourcesDao, workManager, preferences)
        keywordGroupDao = dbManager.keywordGroupDao
        youtuberGroupDao = dbManager.youtuberGroupDao
        youtuberMetaDao = dbManager.youtuberMetaDao
    }

    suspend fun backup(items: List<String> = listOf()) : Result<String> {
        var list = items
        if (list.isEmpty()) {
            list = listOf("settings", "downloads", "keywordData", "youtuberData", "queued", "scheduled", "cancelled", "errored", "saved", "cookies", "templates", "shortcuts", "searchHistory", "observeSources")
        }

        val json = JsonObject()
        json.addProperty("app", "YTDLnisX_backup")
        json.addProperty("backup_format_version", 2)
        list.forEach {
            runCatching {
                when(it){
                    "settings" -> json.add("settings", BackupSettingsUtil.backupSettings(preferences))
                    "downloads" -> json.add("downloads", BackupSettingsUtil.backupHistory(historyRepository))
                    "keywordData" -> {
                        json.add("keyword_groups", BackupSettingsUtil.backupKeywordGroups(keywordGroupDao))
                        json.add("keyword_group_members", BackupSettingsUtil.backupKeywordGroupMembers(keywordGroupDao))
                        val visibleChildKeywords = preferences.getStringSet(prefVisibleChildKeywordsKey, emptySet()).orEmpty()
                        json.add("history_visible_child_keywords", Gson().toJsonTree(visibleChildKeywords).asJsonArray)
                    }
                    "youtuberData" -> {
                        json.add("youtuber_groups", BackupSettingsUtil.backupYoutuberGroups(youtuberGroupDao))
                        json.add("youtuber_group_members", BackupSettingsUtil.backupYoutuberGroupMembers(youtuberGroupDao))
                        json.add("youtuber_group_relations", BackupSettingsUtil.backupYoutuberGroupRelations(youtuberGroupDao))
                        val visibleChildYoutuberGroups = preferences
                            .getStringSet(prefVisibleChildYoutuberGroupsKey, emptySet())
                            .orEmpty()
                        json.add("history_visible_child_youtuber_groups", Gson().toJsonTree(visibleChildYoutuberGroups).asJsonArray)
                        val visibleChildYoutubers = preferences
                            .getStringSet(prefVisibleChildYoutubersKey, emptySet())
                            .orEmpty()
                        json.add("history_visible_child_youtubers", Gson().toJsonTree(visibleChildYoutubers).asJsonArray)
                        json.add("youtuber_meta", BackupSettingsUtil.backupYoutuberMeta(youtuberMetaDao))
                    }
                    "queued" -> json.add("queued", BackupSettingsUtil.backupQueuedDownloads(downloadRepository))
                    "scheduled" -> json.add("scheduled", BackupSettingsUtil.backupScheduledDownloads(downloadRepository))
                    "cancelled" -> json.add("cancelled", BackupSettingsUtil.backupCancelledDownloads(downloadRepository))
                    "errored" -> json.add("errored", BackupSettingsUtil.backupErroredDownloads(downloadRepository))
                    "saved" -> json.add("saved", BackupSettingsUtil.backupSavedDownloads(downloadRepository))
                    "cookies" -> json.add("cookies", BackupSettingsUtil.backupCookies(cookieRepository))
                    "templates" -> json.add("templates", BackupSettingsUtil.backupCommandTemplates(commandTemplateRepository))
                    "shortcuts" -> json.add("shortcuts", BackupSettingsUtil.backupShortcuts(commandTemplateRepository))
                    "searchHistory" -> json.add("search_history", BackupSettingsUtil.backupSearchHistory(searchHistoryRepository))
                    "observeSources" -> json.add("observe_sources", BackupSettingsUtil.backupObserveSources(observeSourcesRepository))
                }
            }.onFailure {err ->
                return Result.failure(err)
            }
        }

        if (list.contains("downloads")) {
            val customThumbItems = backupCustomThumbnails()
            if (customThumbItems.isNotEmpty()) {
                json.add("custom_thumbnails", Gson().toJsonTree(customThumbItems).asJsonArray)
            }
        }

        val currentTime = Calendar.getInstance()
        val dir = File(FileUtil.getCachePath(application) + "/Backups")
        dir.mkdirs()

        val saveFile = File("${dir.absolutePath}/YTDLnisX_Backup_${BuildConfig.VERSION_NAME}_${currentTime.get(
            Calendar.YEAR)}-${currentTime.get(Calendar.MONTH) + 1}-${currentTime.get(
            Calendar.DAY_OF_MONTH)}_${currentTime.get(Calendar.HOUR)}-${currentTime.get(Calendar.MINUTE)}-${currentTime.get(Calendar.SECOND)}.json")

        saveFile.delete()
        withContext(Dispatchers.IO) {
            saveFile.createNewFile()
        }
        saveFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(json))

        val res = withContext(Dispatchers.IO) {
            FileUtil.moveFile(saveFile.parentFile!!, application, FileUtil.getBackupPath(application), false) {}
        }

        return Result.success(res[0])
    }

    suspend fun restoreData(data: RestoreAppDataItem, context: Context, resetData: Boolean = false) : Boolean {
        val result = kotlin.runCatching {
            val restoredCustomThumbByOldHistoryId = restoreCustomThumbnails(data.customThumbnails)

            data.settings?.apply {
                val prefs = this
                PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true){
                    if (resetData) clear()
                    prefs.forEach {
                        val key = it.key
                        val prefValue = it.value
                        when(it.type){
                            "String" -> {
                                putString(key, prefValue)
                            }
                            "Boolean" -> {
                                putBoolean(key, prefValue.toBoolean())
                            }
                            "Int" -> {
                                putInt(key, prefValue.toInt())
                            }
                            else -> {
                                if (it.type?.contains("Set", ignoreCase = true) != true) return@forEach
                                val parsedSet = runCatching {
                                    JsonParser.parseString(prefValue)
                                        .asJsonArray
                                        .mapNotNull { entry ->
                                            runCatching { entry.asString }.getOrNull()
                                        }
                                        .toSet()
                                }.getOrElse {
                                    prefValue
                                        .replace("(\")|(\\[)|(])|([ \\t])".toRegex(), "")
                                        .split(",")
                                        .filter { value -> value.isNotBlank() }
                                        .toSet()
                                }
                                putStringSet(key, parsedSet)
                            }
                        }
                    }
                }
            }


            val importedHistoryIdMap = linkedMapOf<Long, Long>()
            data.downloads?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) historyRepository.deleteAll(false)
                    data.downloads!!.forEach { historyItem ->
                        val oldHistoryId = historyItem.id
                        val newHistoryId = historyRepository.insertAndGetId(
                            historyItem.copy(
                                id = 0L,
                                customThumb = restoredCustomThumbByOldHistoryId[oldHistoryId]
                                    ?: historyItem.customThumb
                            )
                        )
                        importedHistoryIdMap[oldHistoryId] = newHistoryId
                    }
                }
            }

            if (data.keywordGroups != null || data.keywordGroupMembers != null) {
                withContext(Dispatchers.IO) {
                    if (resetData) {
                        keywordGroupDao.clearMembers()
                        keywordGroupDao.clearGroups()
                    }

                    val keywordGroupIdMap = linkedMapOf<Long, Long>()
                    data.keywordGroups?.forEach { group ->
                        val newGroupId = keywordGroupDao.insertGroup(group.copy(id = 0L))
                        keywordGroupIdMap[group.id] = if (newGroupId > 0L) {
                            newGroupId
                        } else {
                            keywordGroupDao.getGroupByName(group.name)?.id ?: 0L
                        }
                    }

                    data.keywordGroupMembers?.mapNotNull { member ->
                        val mappedGroupId = keywordGroupIdMap[member.groupId] ?: member.groupId
                        if (mappedGroupId <= 0L) null
                        else com.ireum.ytdl.database.models.KeywordGroupMember(
                            groupId = mappedGroupId,
                            keyword = member.keyword
                        )
                    }?.also { mappedMembers ->
                        if (mappedMembers.isNotEmpty()) {
                            keywordGroupDao.insertMembers(mappedMembers)
                        }
                    }
                }
            }

            if (
                data.youtuberGroups != null ||
                data.youtuberGroupMembers != null ||
                data.youtuberGroupRelations != null ||
                data.historyVisibleChildYoutuberGroups != null ||
                data.historyVisibleChildYoutubers != null ||
                data.youtuberMeta != null
            ) {
                withContext(Dispatchers.IO) {
                    if (resetData) {
                        youtuberGroupDao.clearMembers()
                        youtuberGroupDao.clearRelations()
                        youtuberGroupDao.clearGroups()
                        youtuberMetaDao.clearAll()
                    }

                    val youtuberGroupIdMap = linkedMapOf<Long, Long>()
                    data.youtuberGroups?.forEach { group ->
                        val newGroupId = youtuberGroupDao.insertGroup(group.copy(id = 0L))
                        youtuberGroupIdMap[group.id] = if (newGroupId > 0L) {
                            newGroupId
                        } else {
                            youtuberGroupDao.getGroupByName(group.name)?.id ?: 0L
                        }
                    }

                    data.youtuberGroupMembers?.mapNotNull { member ->
                        val mappedGroupId = youtuberGroupIdMap[member.groupId] ?: member.groupId
                        if (mappedGroupId <= 0L) null
                        else com.ireum.ytdl.database.models.YoutuberGroupMember(
                            groupId = mappedGroupId,
                            author = member.author
                        )
                    }?.also { mappedMembers ->
                        if (mappedMembers.isNotEmpty()) {
                            youtuberGroupDao.insertMembers(mappedMembers)
                        }
                    }

                    data.youtuberGroupRelations?.mapNotNull { relation ->
                        val mappedParentId = youtuberGroupIdMap[relation.parentGroupId] ?: relation.parentGroupId
                        val mappedChildId = youtuberGroupIdMap[relation.childGroupId] ?: relation.childGroupId
                        if (mappedParentId <= 0L || mappedChildId <= 0L || mappedParentId == mappedChildId) {
                            null
                        } else {
                            com.ireum.ytdl.database.models.YoutuberGroupRelation(
                                parentGroupId = mappedParentId,
                                childGroupId = mappedChildId
                            )
                        }
                    }?.also { mappedRelations ->
                        if (mappedRelations.isNotEmpty()) {
                            youtuberGroupDao.insertRelations(mappedRelations)
                        }
                    }

                    data.youtuberMeta?.forEach { meta ->
                        youtuberMetaDao.upsert(meta)
                    }

                    data.historyVisibleChildYoutuberGroups?.let { visible ->
                        preferences.edit(commit = true) {
                            putStringSet(
                                prefVisibleChildYoutuberGroupsKey,
                                visible.map { it.toString() }.toSet()
                            )
                        }
                    }

                    data.historyVisibleChildYoutubers?.let { visible ->
                        preferences.edit(commit = true) {
                            putStringSet(prefVisibleChildYoutubersKey, visible.toSet())
                        }
                    }
                }
            }

            data.historyVisibleChildKeywords?.let { visible ->
                withContext(Dispatchers.IO) {
                    preferences.edit(commit = true) {
                        putStringSet(prefVisibleChildKeywordsKey, visible.toSet())
                    }
                }
            }

            data.queued?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) downloadRepository.deleteQueued()
                    data.queued!!.forEach {
                        downloadRepository.insert(it)
                    }
                    downloadRepository.startDownloadWorker(listOf(), application)
                }
            }

            data.scheduled?.apply {
                withContext(Dispatchers.IO) {
                    if (resetData) downloadRepository.deleteScheduled()
                    data.scheduled!!.forEach {
                        downloadRepository.insert(it)
                    }
                    downloadRepository.startDownloadWorker(data.scheduled!!, application)
                }
            }

            data.cancelled?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) downloadRepository.deleteCancelled()
                    data.cancelled!!.forEach {
                        downloadRepository.insert(it)
                    }
                }
            }

            data.errored?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) downloadRepository.deleteErrored()
                    data.errored!!.forEach {
                        downloadRepository.insert(it)
                    }
                }
            }

            data.saved?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) downloadRepository.deleteSaved()
                    data.saved!!.forEach {
                        downloadRepository.insert(it)
                    }
                }
            }

            data.cookies?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) cookieRepository.deleteAll()
                    data.cookies!!.forEach {
                        cookieRepository.insert(it)
                    }
                }
            }

            data.templates?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) commandTemplateRepository.deleteAll()
                    data.templates!!.forEach {
                        commandTemplateRepository.insert(it)
                    }
                }
            }

            data.shortcuts?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) commandTemplateRepository.deleteAllShortcuts()
                    data.shortcuts!!.forEach {
                        commandTemplateRepository.insertShortcut(it)
                    }
                }
            }

            data.searchHistory?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) searchHistoryRepository.deleteAll()
                    data.searchHistory!!.forEach {
                        searchHistoryRepository.insert(it.query)
                    }
                }
            }

            data.observeSources?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) observeSourcesRepository.deleteAll()
                    data.observeSources!!.forEach {
                        observeSourcesRepository.insert(it)
                    }
                }
            }

        }

        return result.isSuccess
    }

    private suspend fun backupCustomThumbnails(): List<BackupCustomThumbItem> {
        return withContext(Dispatchers.IO) {
            historyRepository.getAll()
                .mapNotNull { historyItem ->
                    val path = historyItem.customThumb
                    if (path.isBlank()) return@mapNotNull null
                    val file = File(path)
                    if (!file.exists() || !file.isFile || !file.canRead()) return@mapNotNull null

                    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
                    val ext = file.extension.lowercase().ifBlank { "jpg" }
                    BackupCustomThumbItem(
                        historyId = historyItem.id,
                        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        extension = ext
                    )
                }
        }
    }

    private suspend fun restoreCustomThumbnails(
        customThumbs: List<BackupCustomThumbItem>?
    ): Map<Long, String> {
        if (customThumbs.isNullOrEmpty()) return emptyMap()

        return withContext(Dispatchers.IO) {
            val baseDir = application.getExternalFilesDir(null) ?: application.filesDir
            val thumbDir = File(baseDir, "custom_thumbs")
            if (!thumbDir.exists()) {
                thumbDir.mkdirs()
            }

            val restored = linkedMapOf<Long, String>()
            customThumbs.forEach { item ->
                val decoded = runCatching { Base64.decode(item.base64, Base64.DEFAULT) }.getOrNull()
                    ?: return@forEach
                val extension = item.extension
                    .lowercase()
                    .replace(Regex("[^a-z0-9]"), "")
                    .ifBlank { "jpg" }
                val outFile = File(thumbDir, "restored_${item.historyId}.$extension")
                val written = runCatching {
                    outFile.writeBytes(decoded)
                    outFile.absolutePath
                }.getOrNull()
                if (!written.isNullOrBlank()) {
                    restored[item.historyId] = written
                }
            }
            restored
        }
    }

}

