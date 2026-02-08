package com.ireum.ytdl.database.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
import com.ireum.ytdl.database.dao.PlaylistDao
import com.ireum.ytdl.database.dao.PlaylistGroupDao
import com.ireum.ytdl.database.dao.YoutuberGroupDao
import com.ireum.ytdl.database.dao.YoutuberMetaDao
import com.ireum.ytdl.database.models.RestoreAppDataItem
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
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit


class SettingsViewModel(private val application: Application) : AndroidViewModel(application) {
    private val workManager : WorkManager = WorkManager.getInstance(application)
    private val preferences : SharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)

    private val historyRepository : HistoryRepository
    private val downloadRepository : DownloadRepository
    private val cookieRepository : CookieRepository
    private val commandTemplateRepository : CommandTemplateRepository
    private val searchHistoryRepository : SearchHistoryRepository
    private val observeSourcesRepository : ObserveSourcesRepository
    private val playlistDao: PlaylistDao
    private val playlistGroupDao: PlaylistGroupDao
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
        playlistDao = dbManager.playlistDao
        playlistGroupDao = dbManager.playlistGroupDao
        youtuberGroupDao = dbManager.youtuberGroupDao
        youtuberMetaDao = dbManager.youtuberMetaDao
    }

    suspend fun backup(items: List<String> = listOf()) : Result<String> {
        var list = items
        if (list.isEmpty()) {
            list = listOf("settings", "downloads", "playlistData", "youtuberData", "queued", "scheduled", "cancelled", "errored", "saved", "cookies", "templates", "shortcuts", "searchHistory", "observeSources")
        }

        val json = JsonObject()
        json.addProperty("app", "YTDLnisx_backup")
        list.forEach {
            runCatching {
                when(it){
                    "settings" -> json.add("settings", BackupSettingsUtil.backupSettings(preferences))
                    "downloads" -> json.add("downloads", BackupSettingsUtil.backupHistory(historyRepository))
                    "playlistData" -> {
                        json.add("playlists", BackupSettingsUtil.backupPlaylists(playlistDao))
                        json.add("playlist_items", BackupSettingsUtil.backupPlaylistItems(playlistDao))
                        json.add("playlist_groups", BackupSettingsUtil.backupPlaylistGroups(playlistGroupDao))
                        json.add("playlist_group_members", BackupSettingsUtil.backupPlaylistGroupMembers(playlistGroupDao))
                    }
                    "youtuberData" -> {
                        json.add("youtuber_groups", BackupSettingsUtil.backupYoutuberGroups(youtuberGroupDao))
                        json.add("youtuber_group_members", BackupSettingsUtil.backupYoutuberGroupMembers(youtuberGroupDao))
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

        val currentTime = Calendar.getInstance()
        val dir = File(FileUtil.getCachePath(application) + "/Backups")
        dir.mkdirs()

        val saveFile = File("${dir.absolutePath}/YTDLnisx_Backup_${BuildConfig.VERSION_NAME}_${currentTime.get(
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
            data.settings?.apply {
                val prefs = this
                PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true){
                    clear()
                    prefs.forEach {
                        val key = it.key
                        when(it.type){
                            "String" -> {
                                putString(key, it.value)
                            }
                            "Boolean" -> {
                                putBoolean(key, it.value.toBoolean())
                            }
                            "Int" -> {
                                putInt(key, it.value.toInt())
                            }
                            "HashSet" -> {
                                val value = it.value.replace("(\")|(\\[)|(])|([ \\t])".toRegex(), "").split(",")
                                putStringSet(key, value.toHashSet())
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
                        val newHistoryId = historyRepository.insertAndGetId(historyItem.copy(id = 0L))
                        importedHistoryIdMap[oldHistoryId] = newHistoryId
                    }
                }
            }

            if (
                data.playlists != null ||
                data.playlistItems != null ||
                data.playlistGroups != null ||
                data.playlistGroupMembers != null
            ) {
                withContext(Dispatchers.IO) {
                    if (resetData) {
                        playlistGroupDao.clearMembers()
                        playlistGroupDao.clearGroups()
                        playlistDao.clearPlaylistItems()
                        playlistDao.clearPlaylists()
                    }

                    val playlistIdMap = linkedMapOf<Long, Long>()
                    data.playlists?.forEach { playlist ->
                        val newPlaylistId = playlistDao.insertPlaylist(playlist.copy(id = 0L))
                        playlistIdMap[playlist.id] = newPlaylistId
                    }

                    val playlistGroupIdMap = linkedMapOf<Long, Long>()
                    data.playlistGroups?.forEach { group ->
                        val newGroupId = playlistGroupDao.insertGroup(group.copy(id = 0L))
                        playlistGroupIdMap[group.id] = newGroupId
                    }

                    data.playlistItems?.mapNotNull { item ->
                        val mappedPlaylistId = playlistIdMap[item.playlistId] ?: item.playlistId
                        val mappedHistoryId = importedHistoryIdMap[item.historyItemId] ?: item.historyItemId
                        if (mappedPlaylistId <= 0L || mappedHistoryId <= 0L) null
                        else com.ireum.ytdl.database.models.PlaylistItemCrossRef(
                            playlistId = mappedPlaylistId,
                            historyItemId = mappedHistoryId
                        )
                    }?.also { mappedItems ->
                        if (mappedItems.isNotEmpty()) {
                            playlistDao.insertPlaylistItems(mappedItems)
                        }
                    }

                    data.playlistGroupMembers?.mapNotNull { member ->
                        val mappedGroupId = playlistGroupIdMap[member.groupId] ?: member.groupId
                        val mappedPlaylistId = playlistIdMap[member.playlistId] ?: member.playlistId
                        if (mappedGroupId <= 0L || mappedPlaylistId <= 0L) null
                        else com.ireum.ytdl.database.models.PlaylistGroupMember(
                            groupId = mappedGroupId,
                            playlistId = mappedPlaylistId
                        )
                    }?.also { mappedMembers ->
                        if (mappedMembers.isNotEmpty()) {
                            playlistGroupDao.insertMembers(mappedMembers)
                        }
                    }
                }
            }

            if (
                data.youtuberGroups != null ||
                data.youtuberGroupMembers != null ||
                data.youtuberMeta != null
            ) {
                withContext(Dispatchers.IO) {
                    if (resetData) {
                        youtuberGroupDao.clearMembers()
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

                    data.youtuberMeta?.forEach { meta ->
                        youtuberMetaDao.upsert(meta)
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

}

