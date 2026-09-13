package com.ireum.ytdl.database.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.Base64
import androidx.room.withTransaction
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
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.AutomaticKeywordRuleKeyword
import com.ireum.ytdl.database.models.AutomaticKeywordRuleVideoMatch
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.HistoryKeywordAssignment
import com.ireum.ytdl.database.models.KeywordGroup
import com.ireum.ytdl.database.models.KeywordGroupMember
import com.ireum.ytdl.database.models.YoutuberGroup
import com.ireum.ytdl.database.models.YoutuberGroupMember
import com.ireum.ytdl.database.models.YoutuberGroupRelation
import com.ireum.ytdl.database.models.YoutuberMeta
import com.ireum.ytdl.database.models.HistoryReplacementBarrier
import com.ireum.ytdl.database.models.AutomaticKeywordRuleTypes
import com.ireum.ytdl.database.models.AutomaticKeywordSyncStatus
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.database.models.Playlist
import com.ireum.ytdl.database.models.PlaylistItemCrossRef
import com.ireum.ytdl.database.models.PlaylistGroup
import com.ireum.ytdl.database.models.PlaylistGroupMember
import com.ireum.ytdl.database.models.observeSources.ObservationPurposes
import com.ireum.ytdl.database.repository.AutomaticKeywordObservationCoverage
import com.ireum.ytdl.database.repository.AutomaticKeywordRuleScheduler
import com.ireum.ytdl.database.repository.CommandTemplateRepository
import com.ireum.ytdl.database.repository.CookieRepository
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.repository.SearchHistoryRepository
import com.ireum.ytdl.util.BackupSettingsUtil
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.LowQualityRedownloadLedger
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit


class SettingsViewModel(private val application: Application) : AndroidViewModel(application) {
    /**
     * Narrow production-boundary observation used by backup consistency tests.
     * The default is null, so normal backup execution has no callback or
     * additional synchronization behavior.
     */
    companion object {
        @Volatile
        internal var backupCaptureReadHookForTesting: ((String) -> Unit)? = null
        @Volatile
        internal var backupStagingWriteHookForTesting: ((File) -> Unit)? = null
    }

    private data class RemappedDownload(
        val item: com.ireum.ytdl.database.models.DownloadItem,
        val barrier: HistoryReplacementBarrier? = null,
    )

    private data class KeywordBackupSnapshot(
        val groups: List<KeywordGroup>,
        val members: List<KeywordGroupMember>,
        val visibleChildKeywords: Set<String>,
    )

    private data class YoutuberBackupSnapshot(
        val groups: List<YoutuberGroup>,
        val members: List<YoutuberGroupMember>,
        val relations: List<YoutuberGroupRelation>,
        val visibleChildGroups: Set<String>,
        val visibleChildYoutubers: Set<String>,
        val metadata: List<YoutuberMeta>,
    )

    private data class DownloadBackupSnapshot(
        val history: List<HistoryItem>,
        val automaticRules: List<AutomaticKeywordRule>,
        val automaticRuleKeywords: List<AutomaticKeywordRuleKeyword>,
        val automaticRuleVideoMatches: List<AutomaticKeywordRuleVideoMatch>,
        val historyKeywordAssignments: List<HistoryKeywordAssignment>,
    )

    private data class PlaylistBackupSnapshot(
        val playlists: List<Playlist>,
        val playlistItemCrossRefs: List<PlaylistItemCrossRef>,
        val playlistGroups: List<PlaylistGroup>,
        val playlistGroupMembers: List<PlaylistGroupMember>,
    )


    private data class RestoredCustomThumbnail(
        val stagedFile: File,
        val extension: String,
    )

    private class RestoredCustomThumbnailStaging(
        val root: File,
        val byOldHistoryId: Map<Long, RestoredCustomThumbnail>,
    ) {
        fun cleanup() {
            runCatching { root.deleteRecursively() }
        }
    }

    private val prefVisibleChildYoutuberGroupsKey = "history_visible_child_youtuber_groups"
    private val prefVisibleChildYoutubersKey = "history_visible_child_youtubers"
    private val prefVisibleChildKeywordsKey = "history_visible_child_keywords"

    private val workManager : WorkManager = WorkManager.getInstance(application)
    private val preferences : SharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val notificationUtil = NotificationUtil(application)
    private val dbManager = DBManager.getInstance(application)

    private val historyRepository : HistoryRepository
    private val historyKeywordAssignments: HistoryKeywordAssignmentRepository
    private val downloadRepository : DownloadRepository
    private val cookieRepository : CookieRepository
    private val commandTemplateRepository : CommandTemplateRepository
    private val searchHistoryRepository : SearchHistoryRepository
    private val observeSourcesRepository : ObserveSourcesRepository
    private val keywordGroupDao: KeywordGroupDao
    private val youtuberGroupDao: YoutuberGroupDao
    private val youtuberMetaDao: YoutuberMetaDao

    init {
        historyRepository = HistoryRepository(dbManager.historyDao, dbManager.playlistDao)
        historyKeywordAssignments = HistoryKeywordAssignmentRepository(dbManager)
        downloadRepository = DownloadRepository(dbManager)
        cookieRepository = CookieRepository(dbManager.cookieDao)
        commandTemplateRepository = CommandTemplateRepository(dbManager.commandTemplateDao)
        searchHistoryRepository = SearchHistoryRepository(dbManager.searchHistoryDao)
        observeSourcesRepository = ObserveSourcesRepository(dbManager.observeSourcesDao, workManager, preferences)
        keywordGroupDao = dbManager.keywordGroupDao
        youtuberGroupDao = dbManager.youtuberGroupDao
        youtuberMetaDao = dbManager.youtuberMetaDao
    }

    suspend fun backup(items: List<String> = listOf()): Result<String> {
        return try {
            Result.success(backupInternal(items))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun backupInternal(items: List<String>): String {
        val list = if (items.isEmpty()) {
            listOf(
                "settings",
                "downloads",
                "keywordData",
                "youtuberData",
                "queued",
                "paused",
                "scheduled",
                "cancelled",
                "errored",
                "saved",
                "cookies",
                "templates",
                "shortcuts",
                "searchHistory",
                "observeSources",
                "playlistData",
            )
        } else {
            items
        }

        val json = JsonObject().apply {
            addProperty("app", "YTDLnisX_backup")
            addProperty("backup_format_version", 4)
        }

        list.forEach { item ->
            when (item) {
                "settings" -> json.add(
                    "settings",
                    BackupSettingsUtil.backupSettings(preferences).getOrThrow(),
                )

                "downloads" -> {
                    val snapshot = captureDownloadBackupSnapshot()
                    val customThumbItems = backupCustomThumbnails(snapshot.history)
                    json.add("downloads", BackupSettingsUtil.toJsonArray(snapshot.history))
                    if (customThumbItems.isNotEmpty()) {
                        json.add("custom_thumbnails", Gson().toJsonTree(customThumbItems).asJsonArray)
                    }
                    json.add(
                        "automatic_keyword_rules",
                        BackupSettingsUtil.toJsonArray(snapshot.automaticRules),
                    )
                    json.add(
                        "automatic_keyword_rule_keywords",
                        BackupSettingsUtil.toJsonArray(snapshot.automaticRuleKeywords),
                    )
                    json.add(
                        "automatic_keyword_rule_video_matches",
                        BackupSettingsUtil.toJsonArray(snapshot.automaticRuleVideoMatches),
                    )
                    json.add(
                        "history_keyword_assignments",
                        BackupSettingsUtil.toJsonArray(snapshot.historyKeywordAssignments),
                    )
                }

                "keywordData" -> {
                    val snapshot = captureKeywordBackupSnapshot()
                    json.add("keyword_groups", BackupSettingsUtil.toJsonArray(snapshot.groups))
                    json.add("keyword_group_members", BackupSettingsUtil.toJsonArray(snapshot.members))
                    json.add(
                        "history_visible_child_keywords",
                        Gson().toJsonTree(snapshot.visibleChildKeywords).asJsonArray,
                    )
                }

                "youtuberData" -> {
                    val snapshot = captureYoutuberBackupSnapshot()
                    json.add("youtuber_groups", BackupSettingsUtil.toJsonArray(snapshot.groups))
                    json.add("youtuber_group_members", BackupSettingsUtil.toJsonArray(snapshot.members))
                    json.add("youtuber_group_relations", BackupSettingsUtil.toJsonArray(snapshot.relations))
                    json.add(
                        "history_visible_child_youtuber_groups",
                        Gson().toJsonTree(snapshot.visibleChildGroups).asJsonArray,
                    )
                    json.add(
                        "history_visible_child_youtubers",
                        Gson().toJsonTree(snapshot.visibleChildYoutubers).asJsonArray,
                    )
                    json.add("youtuber_meta", BackupSettingsUtil.toJsonArray(snapshot.metadata))
                }

                "queued" -> json.add(
                    "queued",
                    BackupSettingsUtil.backupQueuedDownloads(downloadRepository).getOrThrow(),
                )

                "paused" -> json.add(
                    "paused",
                    BackupSettingsUtil.backupPausedDownloads(downloadRepository).getOrThrow(),
                )

                "scheduled" -> json.add(
                    "scheduled",
                    BackupSettingsUtil.backupScheduledDownloads(downloadRepository).getOrThrow(),
                )

                "cancelled" -> json.add(
                    "cancelled",
                    BackupSettingsUtil.backupCancelledDownloads(downloadRepository).getOrThrow(),
                )

                "errored" -> json.add(
                    "errored",
                    BackupSettingsUtil.backupErroredDownloads(downloadRepository).getOrThrow(),
                )

                "saved" -> json.add(
                    "saved",
                    BackupSettingsUtil.backupSavedDownloads(downloadRepository).getOrThrow(),
                )

                "cookies" -> json.add(
                    "cookies",
                    BackupSettingsUtil.backupCookies(cookieRepository).getOrThrow(),
                )

                "templates" -> json.add(
                    "templates",
                    BackupSettingsUtil.backupCommandTemplates(commandTemplateRepository).getOrThrow(),
                )

                "shortcuts" -> json.add(
                    "shortcuts",
                    BackupSettingsUtil.backupShortcuts(commandTemplateRepository).getOrThrow(),
                )

                "searchHistory" -> json.add(
                    "search_history",
                    BackupSettingsUtil.backupSearchHistory(searchHistoryRepository).getOrThrow(),
                )

                "observeSources" -> json.add(
                    "observe_sources",
                    BackupSettingsUtil.backupObserveSources(observeSourcesRepository).getOrThrow(),
                )

                "playlistData" -> {
                    val snapshot = capturePlaylistBackupSnapshot()
                    json.add("playlists", BackupSettingsUtil.toJsonArray(snapshot.playlists))
                    json.add(
                        "playlist_item_cross_refs",
                        BackupSettingsUtil.toJsonArray(snapshot.playlistItemCrossRefs),
                    )
                    json.add("playlist_groups", BackupSettingsUtil.toJsonArray(snapshot.playlistGroups))
                    json.add(
                        "playlist_group_members",
                        BackupSettingsUtil.toJsonArray(snapshot.playlistGroupMembers),
                    )
                }

            }
        }

        val currentTime = Calendar.getInstance()
        val dir = File(FileUtil.getCachePath(application), "Backups")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Could not create backup directory ${dir.absolutePath}")
        }
        if (!dir.isDirectory || !dir.canWrite()) {
            throw IOException("Backup directory is not writable: ${dir.absolutePath}")
        }

        val saveFile = File(
            dir,
            "YTDLnisX_Backup_${BuildConfig.VERSION_NAME}_${currentTime.get(Calendar.YEAR)}-" +
                "${currentTime.get(Calendar.MONTH) + 1}-${currentTime.get(Calendar.DAY_OF_MONTH)}_" +
                "${currentTime.get(Calendar.HOUR)}-${currentTime.get(Calendar.MINUTE)}-" +
                "${currentTime.get(Calendar.SECOND)}_${UUID.randomUUID()}.json",
        )

        if (saveFile.exists() && !saveFile.delete()) {
            throw IOException("Could not replace existing backup file ${saveFile.absolutePath}")
        }
        if (!saveFile.createNewFile()) {
            throw IOException("Could not create backup file ${saveFile.absolutePath}")
        }
        withContext(Dispatchers.IO) {
            backupStagingWriteHookForTesting?.invoke(saveFile)
            saveFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(json))
        }

        val moveResult = withContext(Dispatchers.IO) {
            FileUtil.moveFileWithResult(
                originDir = saveFile.parentFile!!,
                context = application,
                destDir = FileUtil.getBackupPath(application),
                keepCache = false,
                progress = {},
                sourceFiles = listOf(saveFile),
            )
        }
        if (moveResult.failures.isNotEmpty()) {
            throw IOException(
                "Backup publication was incomplete: ${moveResult.failures.joinToString(" | ")}",
            )
        }
        return moveResult.paths.firstOrNull()
            ?: throw IOException("Backup file publication produced no destination")
    }

    private suspend fun captureKeywordBackupSnapshot(): KeywordBackupSnapshot = withContext(Dispatchers.IO) {
        dbManager.withTransaction {
            val groups = keywordGroupDao.getGroups()
            backupCaptureReadHookForTesting?.invoke("keyword_groups")
            KeywordBackupSnapshot(
                groups = groups,
                members = keywordGroupDao.getAllMembers(),
                visibleChildKeywords = preferences
                    .getStringSet(prefVisibleChildKeywordsKey, emptySet())
                    .orEmpty(),
            )
        }
    }

    private suspend fun captureYoutuberBackupSnapshot(): YoutuberBackupSnapshot = withContext(Dispatchers.IO) {
        dbManager.withTransaction {
            YoutuberBackupSnapshot(
                groups = youtuberGroupDao.getGroups(),
                members = youtuberGroupDao.getAllMembers(),
                relations = youtuberGroupDao.getAllRelations(),
                visibleChildGroups = preferences
                    .getStringSet(prefVisibleChildYoutuberGroupsKey, emptySet())
                    .orEmpty(),
                visibleChildYoutubers = preferences
                    .getStringSet(prefVisibleChildYoutubersKey, emptySet())
                    .orEmpty(),
                metadata = youtuberMetaDao.getAll(),
            )
        }
    }

    private suspend fun captureDownloadBackupSnapshot(): DownloadBackupSnapshot = withContext(Dispatchers.IO) {
        dbManager.withTransaction {
            DownloadBackupSnapshot(
                history = dbManager.historyDao.getAll(),
                automaticRules = dbManager.automaticKeywordRuleDao.getAllRules(),
                automaticRuleKeywords = dbManager.automaticKeywordRuleDao.getAllRuleKeywords(),
                automaticRuleVideoMatches = dbManager.automaticKeywordRuleDao.getAllVideoMatches(),
                historyKeywordAssignments = dbManager.automaticKeywordRuleDao.getAllAssignmentsRaw(),
            )
        }
    }

    private suspend fun capturePlaylistBackupSnapshot(): PlaylistBackupSnapshot = withContext(Dispatchers.IO) {
        dbManager.withTransaction {
            PlaylistBackupSnapshot(
                playlists = dbManager.playlistDao.getAllPlaylistsSync(),
                playlistItemCrossRefs = dbManager.playlistDao.getAllPlaylistItems(),
                playlistGroups = dbManager.playlistGroupDao.getGroups(),
                playlistGroupMembers = dbManager.playlistGroupDao.getAllMembers(),
            )
        }
    }

    suspend fun restoreData(data: RestoreAppDataItem, context: Context, resetData: Boolean = false) : Boolean {
        var customThumbnailStaging: RestoredCustomThumbnailStaging? = null
        val result = kotlin.runCatching {
            customThumbnailStaging = restoreCustomThumbnails(data.customThumbnails)
            val restoredCustomThumbByOldHistoryId = customThumbnailStaging!!.byOldHistoryId
            val resetAutomaticRules =
                resetData && (data.downloads != null || data.automaticKeywordRules != null)
            if (resetAutomaticRules) {
                withContext(Dispatchers.IO) {
                    dbManager.automaticKeywordRuleDao.getAllRules().forEach {
                        AutomaticKeywordRuleScheduler.cancel(context, it.id)
                        historyKeywordAssignments.deleteRuleAndAssignments(it.id)
                    }
                }
            }

            data.settings?.apply {
                val prefs = this
                PreferenceManager.getDefaultSharedPreferences(context).edit(commit = true){
                    if (resetData) clear()
                    prefs.forEach {
                        val key = it.key
                        if (!BackupSettingsUtil.isPortablePreferenceKey(key)) return@forEach
                        val prefValue = it.value
                        when (it.type) {
                            "String" -> {
                                putString(key, prefValue)
                            }
                            "Boolean" -> {
                                putBoolean(
                                    key,
                                    when (prefValue.lowercase()) {
                                        "true" -> true
                                        "false" -> false
                                        else -> throw IllegalArgumentException(
                                            "Invalid Boolean preference value for $key",
                                        )
                                    },
                                )
                            }
                            "Int" -> {
                                putInt(key, prefValue.toInt())
                            }
                            "Long" -> {
                                putLong(key, prefValue.toLong())
                            }
                            "Float" -> {
                                putFloat(key, prefValue.toFloat())
                            }
                            "StringSet", "Set", "HashSet", "LinkedHashSet", "ArraySet" -> {
                                val parsedSet = JsonParser.parseString(prefValue)
                                    .takeIf { it.isJsonArray }
                                    ?.asJsonArray
                                    ?.map { entry ->
                                        require(entry.isJsonPrimitive && entry.asJsonPrimitive.isString) {
                                            "Invalid StringSet member for $key"
                                        }
                                        entry.asString
                                    }
                                    ?.toSet()
                                    ?: throw IllegalArgumentException(
                                        "Invalid StringSet preference value for $key",
                                    )
                                putStringSet(key, parsedSet)
                            }
                            else -> throw IllegalArgumentException(
                                "Unsupported preference type for $key: ${it.type}",
                            )
                        }
                    }
                }
            }


            val importedHistoryIdMap = linkedMapOf<Long, Long>()
            data.downloads?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) historyRepository.deleteAllRecords()
                    data.downloads!!.forEach { historyItem ->
                        val oldHistoryId = historyItem.id
                        val stagedThumbnail = restoredCustomThumbByOldHistoryId[oldHistoryId]
                        val newHistoryId = historyKeywordAssignments.insertHistory(
                            historyItem.copy(
                                id = 0L,
                                // History.downloadId points at a destination-local
                                // Download row.  No portable Download-ID map exists
                                // during History restore, so retaining the backup ID
                                // would create a false relation to an unrelated row.
                                downloadId = 0L,
                                // A backup-local path is never portable.  The
                                // staged payload is bound only after the new
                                // destination History identity exists.
                                customThumb = "",
                            )
                        )
                        check(newHistoryId > 0L) {
                            "History restore did not allocate a destination identity"
                        }
                        importedHistoryIdMap[oldHistoryId] = newHistoryId
                        stagedThumbnail?.let { thumbnail ->
                            val finalPath = publishRestoredCustomThumbnail(
                                thumbnail = thumbnail,
                                destinationHistoryId = newHistoryId,
                            )
                            try {
                                check(
                                    dbManager.historyDao.updateCustomThumbById(
                                        id = newHistoryId,
                                        customThumb = finalPath,
                                    ) == 1,
                                ) {
                                    "History restore thumbnail binding lost destination $newHistoryId"
                                }
                            } catch (error: Exception) {
                                runCatching { File(finalPath).delete() }
                                throw error
                            }
                        }
                    }
                }
            }

            if (
                data.playlists != null ||
                data.playlistItemCrossRefs != null ||
                data.playlistGroups != null ||
                data.playlistGroupMembers != null
            ) {
                withContext(Dispatchers.IO) {
                    dbManager.withTransaction {
                        if (resetData) {
                            // Relationship rows are removed before their
                            // endpoint rows to respect Room foreign keys.
                            dbManager.playlistDao.clearPlaylistItems()
                            dbManager.playlistGroupDao.clearMembers()
                            dbManager.playlistGroupDao.clearGroups()
                            dbManager.playlistDao.clearPlaylists()
                        }

                        val playlistIdMap = linkedMapOf<Long, Long>()
                        data.playlists.orEmpty().forEach { playlist ->
                            if (playlist.id <= 0L) return@forEach
                            val destinationId = dbManager.playlistDao.insertPlaylist(
                                playlist.copy(id = 0L)
                            )
                            check(destinationId > 0L) {
                                "Playlist restore did not allocate a destination identity"
                            }
                            playlistIdMap[playlist.id] = destinationId
                        }

                        val groupIdMap = linkedMapOf<Long, Long>()
                        data.playlistGroups.orEmpty().forEach { group ->
                            if (group.id <= 0L || group.name.isBlank()) return@forEach
                            val destinationId = dbManager.playlistGroupDao
                                .getGroupByName(group.name)
                                ?.id
                                ?: dbManager.playlistGroupDao.insertGroup(group.copy(id = 0L))
                            if (destinationId > 0L) {
                                groupIdMap[group.id] = destinationId
                            }
                        }

                        val mappedCrossRefs = data.playlistItemCrossRefs.orEmpty()
                            .mapNotNull { relation ->
                                val playlistId = playlistIdMap[relation.playlistId]
                                val historyId = importedHistoryIdMap[relation.historyItemId]
                                if (playlistId == null || historyId == null ||
                                    playlistId <= 0L || historyId <= 0L
                                ) {
                                    null
                                } else {
                                    PlaylistItemCrossRef(playlistId, historyId)
                                }
                            }
                            .distinct()
                        if (mappedCrossRefs.isNotEmpty()) {
                            dbManager.playlistDao.insertPlaylistItems(mappedCrossRefs)
                        }

                        val mappedGroupMembers = data.playlistGroupMembers.orEmpty()
                            .mapNotNull { member ->
                                val groupId = groupIdMap[member.groupId]
                                val playlistId = playlistIdMap[member.playlistId]
                                if (groupId == null || playlistId == null ||
                                    groupId <= 0L || playlistId <= 0L
                                ) {
                                    null
                                } else {
                                    PlaylistGroupMember(groupId, playlistId)
                                }
                            }
                            .distinct()
                        if (mappedGroupMembers.isNotEmpty()) {
                            dbManager.playlistGroupDao.insertMembers(mappedGroupMembers)
                        }
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
                        val mappedGroupId = keywordGroupIdMap[member.groupId]
                            ?: return@mapNotNull null
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
                        val mappedGroupId = youtuberGroupIdMap[member.groupId]
                            ?: return@mapNotNull null
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
                        val mappedParentId = youtuberGroupIdMap[relation.parentGroupId]
                            ?: return@mapNotNull null
                        val mappedChildId = youtuberGroupIdMap[relation.childGroupId]
                            ?: return@mapNotNull null
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
                                visible.mapNotNull { youtuberGroupIdMap[it]?.toString() }.toSet()
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

            val queuedResetHandled = resetData && data.queued != null
            if (queuedResetHandled) {
                withContext(Dispatchers.IO){
                    downloadRepository.getMembershipWaitingDownloads().forEach {
                        notificationUtil.cancelMembershipWaitingNotification(it.id)
                    }
                    LowQualityRedownloadLedger.refresh(
                        application,
                        downloadRepository.deleteQueued()
                    )
                }
            }

            val restoredObserveSourceIdMap = linkedMapOf<Long, Long>()
            data.observeSources?.let { sources ->
                withContext(Dispatchers.IO) {
                    if (resetData) {
                        observeSourcesRepository.deleteAll().forEach {
                            notificationUtil.cancelMembershipWaitingNotification(it)
                        }
                    }
                    sources.forEach { source ->
                        val oldSourceId = source.id
                        val restoredUserSource = source.copy(
                            id = 0L,
                            observationPurpose = ObservationPurposes.USER,
                            managedConditionKey = "",
                            // The embedded template can carry the source's
                            // database-local identity.  It is rebound only
                            // after the destination source row is allocated.
                            downloadItemTemplate = source.downloadItemTemplate.copy(
                                id = 0L,
                                observeSourceId = 0L,
                            ),
                        )
                        val insertedId = observeSourcesRepository.insert(restoredUserSource)
                        var restoredSource = if (insertedId > 0L) {
                            restoredUserSource.copy(id = insertedId)
                        } else {
                            observeSourcesRepository.getByURL(source.url)
                        }
                        val restoredId = restoredSource.id
                        if (insertedId > 0L && restoredId > 0L) {
                            restoredSource = restoredSource.copy(
                                downloadItemTemplate = restoredSource.downloadItemTemplate.copy(
                                    observeSourceId = restoredId,
                                ),
                            )
                            observeSourcesRepository.update(restoredSource)
                        }
                        if (oldSourceId > 0L && restoredId > 0L) {
                            restoredObserveSourceIdMap[oldSourceId] = restoredId
                        }
                        if (
                            restoredSource.status ==
                            ObserveSourcesRepository.SourceStatus.ACTIVE
                        ) {
                            observeSourcesRepository.observeTask(restoredSource)
                        }
                    }
                }
            }

            val restoredAutomaticRuleIdMap = linkedMapOf<Long, Long>()
            data.automaticKeywordRules?.let { rules ->
                withContext(Dispatchers.IO) {
                    rules.forEach { rule ->
                        val hasValidKeyword = data.automaticKeywordRuleKeywords.orEmpty().any {
                            it.ruleId == rule.id &&
                                AutomaticKeywordNormalizer.normalizeKeyword(it.keyword).isNotBlank()
                        }
                        if (!hasValidKeyword) return@forEach
                        val conditionValue =
                            AutomaticKeywordNormalizer.canonicalPlaylistUrl(rule.conditionValue)
                                ?: return@forEach
                        val conditionKey =
                            AutomaticKeywordNormalizer.playlistConditionKey(conditionValue)
                                ?: return@forEach
                        val restoredRule = rule.copy(
                            id = 0L,
                            conditionType = AutomaticKeywordRuleTypes.PLAYLIST,
                            conditionValue = conditionValue,
                            conditionKey = conditionKey,
                            revision = 1L,
                            manualSyncStatus = rule.manualSyncStatus.normalizedRestoredSyncStatus(),
                            discoveryStatus = rule.discoveryStatus.normalizedRestoredSyncStatus()
                        )
                        val restoredId = dbManager.automaticKeywordRuleDao.insertRule(restoredRule)
                        restoredAutomaticRuleIdMap[rule.id] = restoredId
                    }
                    val restoredRuleKeywords = data.automaticKeywordRuleKeywords.orEmpty()
                        .mapNotNull { keyword ->
                            restoredAutomaticRuleIdMap[keyword.ruleId]?.let { restoredRuleId ->
                                val display = keyword.keyword.trim().replace(Regex("\\s+"), " ")
                                val normalized =
                                    AutomaticKeywordNormalizer.normalizeKeyword(display)
                                if (normalized.isBlank()) null else keyword.copy(
                                    ruleId = restoredRuleId,
                                    normalizedKeyword = normalized,
                                    keyword = display
                                )
                            }
                        }
                        .distinctBy { it.ruleId to it.normalizedKeyword }
                    if (restoredRuleKeywords.isNotEmpty()) {
                        dbManager.automaticKeywordRuleDao.insertRuleKeywords(restoredRuleKeywords)
                    }
                    data.automaticKeywordRuleVideoMatches.orEmpty().forEach { match ->
                        restoredAutomaticRuleIdMap[match.ruleId]?.let { restoredRuleId ->
                            val videoKey = AutomaticKeywordNormalizer.videoKey(match.videoUrl)
                            if (videoKey.isBlank()) return@let
                            dbManager.automaticKeywordRuleDao.insertVideoMatch(
                                match.copy(ruleId = restoredRuleId, videoKey = videoKey)
                            )
                        }
                    }
                }
            }

            data.historyKeywordAssignments
                ?.groupBy { it.historyItemId }
                ?.forEach { (oldHistoryId, assignments) ->
                    val restoredHistoryId = importedHistoryIdMap[oldHistoryId]
                        ?: return@forEach
                    val restoredAssignments = assignments.mapNotNull { assignment ->
                        val restoredSourceId = when (assignment.sourceType) {
                            HistoryKeywordAssignmentSources.MANUAL ->
                                HistoryKeywordAssignmentSources.MANUAL_SOURCE_ID
                            HistoryKeywordAssignmentSources.RULE ->
                                restoredAutomaticRuleIdMap[assignment.sourceId]
                                    ?: return@mapNotNull null
                            HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE ->
                                restoredObserveSourceIdMap[assignment.sourceId]
                                    ?: return@mapNotNull null
                            else -> return@mapNotNull null
                        }
                        val display = assignment.keyword.trim().replace(Regex("\\s+"), " ")
                        val normalized = AutomaticKeywordNormalizer.normalizeKeyword(display)
                        if (normalized.isBlank()) return@mapNotNull null
                        assignment.copy(
                            historyItemId = restoredHistoryId,
                            normalizedKeyword = normalized,
                            keyword = display,
                            sourceId = restoredSourceId
                        )
                    }.distinctBy {
                        listOf(it.historyItemId, it.normalizedKeyword, it.sourceType, it.sourceId)
                    }
                    historyKeywordAssignments.restoreAssignments(
                        restoredHistoryId,
                        restoredAssignments,
                        preserveExistingRuleAssignments = !resetData
                    )
                }

            if (
                data.automaticKeywordRules != null ||
                data.observeSources != null ||
                resetAutomaticRules
            ) {
                AutomaticKeywordObservationCoverage(context, dbManager).reconcile()
            }
            if (data.automaticKeywordRules != null) {
                restoredAutomaticRuleIdMap.values.forEach { restoredRuleId ->
                    val rule = dbManager.automaticKeywordRuleDao.getRule(restoredRuleId)
                        ?: return@forEach
                    if (rule.enabled && (!rule.baselineComplete || rule.pendingApplyToExisting)) {
                        AutomaticKeywordRuleScheduler.enqueue(
                            context,
                            restoredRuleId,
                            if (rule.pendingApplyToExisting) {
                                AutomaticKeywordRuleScheduler.Mode.APPLY_EXISTING
                            } else {
                                AutomaticKeywordRuleScheduler.Mode.BASELINE_ONLY
                            }
                        )
                    }
                }
            }

            fun remapRestoredDownload(item: com.ireum.ytdl.database.models.DownloadItem) :
                RemappedDownload {
                val oldSourceId = item.observeSourceId
                val restoredSourceId = if (oldSourceId <= 0L) {
                    0L
                } else {
                    restoredObserveSourceIdMap[oldSourceId]
                }
                val restoredStatus =
                    if (
                        oldSourceId > 0L &&
                        restoredSourceId == null &&
                        item.status == DownloadRepository.Status.WaitingForMembership.toString()
                    ) {
                        DownloadRepository.Status.Error.toString()
                    } else {
                        item.status
                    }
                val remappedItem = item.copy(
                    id = 0L,
                    observeSourceId = restoredSourceId ?: 0L,
                    status = restoredStatus
                )
                val persistedRefusal = HistoryReplacementDiagnostic
                    .persistedHistoryReplacementIssue(remappedItem.lastIssueCode)
                return when (val marker = HistoryRedownloadMarker.remap(
                    remappedItem.playlistURL,
                    importedHistoryIdMap
                )) {
                    com.ireum.ytdl.util.RestoreRemapResult.NotMarker ->
                        if (persistedRefusal == null) {
                            RemappedDownload(remappedItem)
                        } else {
                            RemappedDownload(
                                item = remappedItem.copy(
                                    playlistURL = "",
                                    status = DownloadRepository.Status.Error.toString(),
                                )
                            )
                    }
                    is com.ireum.ytdl.util.RestoreRemapResult.Mapped -> {
                        val mappedItem = remappedItem.copy(playlistURL = marker.encodedMarker)
                        HistoryRedownloadRestorePolicy
                            .revokeOrphanQualityMarker(
                                item = mappedItem,
                                hasPersistedRefusal = persistedRefusal != null,
                            )
                            ?.let { revoked ->
                                return RemappedDownload(item = revoked)
                            }
                        val mappedHistoryId = HistoryRedownloadMarker.parse(marker.encodedMarker)
                            ?.historyId
                        val canReconstructBarrier = persistedRefusal != null &&
                            mappedHistoryId != null &&
                            mappedHistoryId > 0L &&
                            mappedItem.operationId.isNotBlank() &&
                            mappedItem.url.isNotBlank()
                        if (!canReconstructBarrier) {
                            RemappedDownload(
                                item = if (persistedRefusal == null) {
                                    mappedItem
                                } else {
                                    mappedItem.copy(
                                        playlistURL = "",
                                        status = DownloadRepository.Status.Error.toString(),
                                    )
                                }
                            )
                        } else {
                            RemappedDownload(
                                item = mappedItem,
                                barrier = HistoryReplacementBarrier(
                                    downloadId = 0L,
                                    operationId = mappedItem.operationId,
                                    historyId = mappedHistoryId!!,
                                    expectedSourceUrl = mappedItem.url,
                                    expectedType = mappedItem.type.name,
                                    issueCode = persistedRefusal!!.code.name,
                                    issueStage = remappedItem.lastIssueStage.ifBlank {
                                        persistedRefusal.stage.name
                                    },
                                    createdAt = System.currentTimeMillis(),
                                )
                            )
                        }
                    }
                    com.ireum.ytdl.util.RestoreRemapResult.Unmappable -> {
                        val unmappableMarker = HistoryRedownloadMarker.parse(remappedItem.playlistURL)
                        RemappedDownload(
                            item = remappedItem.copy(
                                playlistURL = "",
                                status = DownloadRepository.Status.Error.toString(),
                                lastIssueCode = persistedRefusal?.code?.name
                                    ?: if (unmappableMarker?.isQualityReplacement == true) {
                                        DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED.name
                                    } else {
                                        DownloadIssueCode.HISTORY_TARGET_DELETED.name
                                    },
                                lastIssueStage = remappedItem.lastIssueStage.ifBlank {
                                    persistedRefusal?.stage?.name ?: DownloadIssueStage.HISTORY.name
                                },
                            )
                        )
                    }
                }
            }

            if (resetData && data.paused != null) {
                withContext(Dispatchers.IO) {
                    LowQualityRedownloadLedger.refresh(
                        application,
                        downloadRepository.deletePaused(),
                    )
                }
            }

            data.paused?.let { paused ->
                withContext(Dispatchers.IO) {
                    paused.forEach { item ->
                        val restored = remapRestoredDownload(
                            item.copy(
                                status = DownloadRepository.Status.Paused.toString(),
                                executionId = "",
                            )
                        )
                        // Paused restore is deliberately not passed to
                        // startDownloadWorker: this payload is persistent
                        // state, not runnable work.
                        downloadRepository.insertRestoredDownload(restored.item, restored.barrier)
                    }
                }
            }

            data.queued?.let { queued ->
                withContext(Dispatchers.IO){
                    queued.forEach { item ->
                        val restored = remapRestoredDownload(item)
                        downloadRepository.insertRestoredDownload(restored.item, restored.barrier)
                    }
                    downloadRepository.startDownloadWorker(listOf(), application)
                }
            }

            data.scheduled?.apply {
                withContext(Dispatchers.IO) {
                    if (resetData) {
                        LowQualityRedownloadLedger.refresh(
                            application,
                            downloadRepository.deleteScheduled()
                        )
                    }
                    val restoredScheduled = mutableListOf<com.ireum.ytdl.database.models.DownloadItem>()
                    data.scheduled!!.forEach {
                        val restored = remapRestoredDownload(it)
                        val restoredItem = restored.item
                        restoredItem.id = downloadRepository.insertRestoredDownload(
                            restored.item,
                            restored.barrier,
                        )
                        restoredScheduled.add(restoredItem)
                    }
                    downloadRepository.startDownloadWorker(restoredScheduled, application)
                }
            }

            data.cancelled?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) {
                        LowQualityRedownloadLedger.refresh(
                            application,
                            downloadRepository.deleteCancelled()
                        )
                    }
                    data.cancelled!!.forEach {
                        val restored = remapRestoredDownload(it)
                        downloadRepository.insertRestoredDownload(restored.item, restored.barrier)
                    }
                }
            }

            data.errored?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) downloadRepository.deleteErrored()
                    data.errored!!.forEach {
                        val restored = remapRestoredDownload(it)
                        downloadRepository.insertRestoredDownload(restored.item, restored.barrier)
                    }
                }
            }

            data.saved?.apply {
                withContext(Dispatchers.IO){
                    if (resetData) downloadRepository.deleteSaved()
                    data.saved!!.forEach {
                        val restored = remapRestoredDownload(it)
                        downloadRepository.insertRestoredDownload(restored.item, restored.barrier)
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

        }

        customThumbnailStaging?.cleanup()
        return result.isSuccess
    }

    private fun String.normalizedRestoredSyncStatus(): String =
        if (this == AutomaticKeywordSyncStatus.QUEUED ||
            this == AutomaticKeywordSyncStatus.RUNNING
        ) {
            AutomaticKeywordSyncStatus.NEVER
        } else {
            this
        }

    private suspend fun backupCustomThumbnails(historyItems: List<HistoryItem>): List<BackupCustomThumbItem> {
        return withContext(Dispatchers.IO) {
            val captured = ArrayList<BackupCustomThumbItem>()
            historyItems.forEach { historyItem ->
                val path = historyItem.customThumb
                if (path.isBlank()) return@forEach

                val file = File(path)
                if (!file.exists() || !file.isFile || !file.canRead()) {
                    throw IOException(
                        "Required custom thumbnail for History ${historyItem.id} is unavailable",
                    )
                }

                val bytes = file.readBytes()
                val ext = file.extension.lowercase().ifBlank { "jpg" }
                captured += BackupCustomThumbItem(
                    historyId = historyItem.id,
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    extension = ext,
                )
            }
            captured
        }
    }

    private suspend fun restoreCustomThumbnails(
        customThumbs: List<BackupCustomThumbItem>?
    ): RestoredCustomThumbnailStaging {
        val stagingRoot = File(
            application.filesDir,
            "restore-thumbnail-staging/${UUID.randomUUID()}",
        )
        if (customThumbs.isNullOrEmpty()) {
            return RestoredCustomThumbnailStaging(stagingRoot, emptyMap())
        }

        return withContext(Dispatchers.IO) {
            try {
                if (!stagingRoot.mkdirs() && !stagingRoot.isDirectory) {
                    throw IOException("Could not create thumbnail restore staging directory")
                }
                val restored = linkedMapOf<Long, RestoredCustomThumbnail>()
                customThumbs.forEachIndexed { index, item ->
                    if (item.historyId <= 0L) {
                        throw IOException("Invalid backup History identity for custom thumbnail")
                    }
                    if (restored.containsKey(item.historyId)) {
                        throw IOException(
                            "Duplicate custom thumbnail payload for backup History ${item.historyId}",
                        )
                    }
                    val extension = item.extension
                        .lowercase()
                        .ifBlank { "jpg" }
                        .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
                        ?: throw IOException("Invalid custom thumbnail extension")
                    val decoded = try {
                        Base64.decode(item.base64, Base64.DEFAULT)
                    } catch (error: Exception) {
                        throw IOException(
                            "Could not decode custom thumbnail for backup History ${item.historyId}",
                            error,
                        )
                    }
                    val stagedFile = File(stagingRoot, "payload_$index.$extension")
                    if (!stagedFile.createNewFile()) {
                        throw IOException("Could not create staged custom thumbnail")
                    }
                    try {
                        FileOutputStream(stagedFile).use { output ->
                            output.write(decoded)
                            output.flush()
                            output.fd.sync()
                        }
                    } catch (error: Exception) {
                        runCatching { stagedFile.delete() }
                        throw IOException(
                            "Could not stage custom thumbnail for backup History ${item.historyId}",
                            error,
                        )
                    }
                    restored[item.historyId] = RestoredCustomThumbnail(
                        stagedFile = stagedFile,
                        extension = extension,
                    )
                }
                RestoredCustomThumbnailStaging(stagingRoot, restored)
            } catch (error: Exception) {
                runCatching { stagingRoot.deleteRecursively() }
                throw error
            }
        }
    }

    private fun publishRestoredCustomThumbnail(
        thumbnail: RestoredCustomThumbnail,
        destinationHistoryId: Long,
    ): String {
        val finalDir = File(application.filesDir, "restored_custom_thumbnails")
        if (!finalDir.exists() && !finalDir.mkdirs()) {
            throw IOException("Could not create restored thumbnail directory")
        }
        if (!finalDir.isDirectory || !finalDir.canWrite()) {
            throw IOException("Restored thumbnail directory is not writable")
        }
        val finalFile = File(
            finalDir,
            "history_${destinationHistoryId}_${UUID.randomUUID()}.${thumbnail.extension}",
        )
        if (!finalFile.createNewFile()) {
            throw IOException("Could not reserve restored thumbnail destination")
        }
        try {
            thumbnail.stagedFile.inputStream().use { input ->
                FileOutputStream(finalFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            return finalFile.absolutePath
        } catch (error: Exception) {
            runCatching { finalFile.delete() }
            throw IOException("Could not publish restored thumbnail", error)
        }
    }

}

