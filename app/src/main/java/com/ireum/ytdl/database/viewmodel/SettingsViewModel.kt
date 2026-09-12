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
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.TimeUnit


class SettingsViewModel(private val application: Application) : AndroidViewModel(application) {
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
                "scheduled",
                "cancelled",
                "errored",
                "saved",
                "cookies",
                "templates",
                "shortcuts",
                "searchHistory",
                "observeSources",
            )
        } else {
            items
        }

        val json = JsonObject().apply {
            addProperty("app", "YTDLnisX_backup")
            addProperty("backup_format_version", 3)
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
                "${currentTime.get(Calendar.SECOND)}.json",
        )

        if (saveFile.exists() && !saveFile.delete()) {
            throw IOException("Could not replace existing backup file ${saveFile.absolutePath}")
        }
        if (!saveFile.createNewFile()) {
            throw IOException("Could not create backup file ${saveFile.absolutePath}")
        }
        withContext(Dispatchers.IO) {
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
            KeywordBackupSnapshot(
                groups = keywordGroupDao.getGroups(),
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

    suspend fun restoreData(data: RestoreAppDataItem, context: Context, resetData: Boolean = false) : Boolean {
        val result = kotlin.runCatching {
            val restoredCustomThumbByOldHistoryId = restoreCustomThumbnails(data.customThumbnails)
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
                    if (resetData) historyRepository.deleteAllRecords()
                    data.downloads!!.forEach { historyItem ->
                        val oldHistoryId = historyItem.id
                        val newHistoryId = historyKeywordAssignments.insertHistory(
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
                            managedConditionKey = ""
                        )
                        val insertedId = observeSourcesRepository.insert(restoredUserSource)
                        val restoredSource = if (insertedId > 0L) {
                            restoredUserSource.copy(id = insertedId)
                        } else {
                            observeSourcesRepository.getByURL(source.url)
                        }
                        val restoredId = restoredSource.id
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
                                    ?: assignment.sourceId
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

            val liveObserveSourceIdCache = mutableMapOf<Long, Long?>()
            fun remapRestoredDownload(item: com.ireum.ytdl.database.models.DownloadItem) :
                RemappedDownload {
                val oldSourceId = item.observeSourceId
                val restoredSourceId = if (oldSourceId <= 0L) {
                    0L
                } else {
                    restoredObserveSourceIdMap[oldSourceId]
                        ?: if (data.observeSources == null) {
                            if (liveObserveSourceIdCache.containsKey(oldSourceId)) {
                                liveObserveSourceIdCache[oldSourceId]
                            } else {
                                observeSourcesRepository.getByIDOrNull(oldSourceId)?.id.also {
                                    liveObserveSourceIdCache[oldSourceId] = it
                                }
                            }
                        } else {
                            null
                        }
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

