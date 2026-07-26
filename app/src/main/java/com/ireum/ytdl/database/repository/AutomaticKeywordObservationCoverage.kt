package com.ireum.ytdl.database.repository

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.work.WorkManager
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.models.observeSources.ObservationPurposes
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Makes playlist-rule discovery a client of the existing Observe Source schedule.
 * Managed sources are hidden and discovery-only; ObserveSourceWorker returns
 * before any download/deletion path for them.
 */
class AutomaticKeywordObservationCoverage(
    private val context: Context,
    private val db: DBManager = DBManager.getInstance(context)
) {
    suspend fun reconcile() = reconciliationMutex.withLock {
        val observeDao = db.observeSourcesDao
        val repository = ObserveSourcesRepository(
            observeDao,
            WorkManager.getInstance(context),
            PreferenceManager.getDefaultSharedPreferences(context)
        )
        val enabledRules = db.automaticKeywordRuleDao.getAllEnabledRules()
        val requiredByKey = enabledRules.groupBy { it.conditionKey }
        val sources = observeDao.getAllSourcesIncludingManaged()
        val publicActiveKeys = sources.asSequence()
            .filter {
                it.observationPurpose == ObservationPurposes.USER &&
                    it.status == ObserveSourcesRepository.SourceStatus.ACTIVE
            }
            .mapNotNull { AutomaticKeywordNormalizer.playlistConditionKey(it.url) }
            .toSet()

        sources.filter { it.observationPurpose == ObservationPurposes.KEYWORD_DISCOVERY }
            .groupBy { it.managedConditionKey }
            .values
            .forEach { duplicates ->
                duplicates.drop(1).forEach {
                    repository.cancelObservationTaskByID(it.id)
                    observeDao.deleteRecord(it.id)
                }
            }

        observeDao.getAllSourcesIncludingManaged()
            .filter { it.observationPurpose == ObservationPurposes.KEYWORD_DISCOVERY }
            .forEach { managed ->
            if (managed.managedConditionKey !in requiredByKey || managed.managedConditionKey in publicActiveKeys) {
                repository.cancelObservationTaskByID(managed.id)
                observeDao.deleteRecord(managed.id)
            }
        }

        val remaining = observeDao.getAllSourcesIncludingManaged()
        requiredByKey.forEach { (conditionKey, rules) ->
            if (!AutomaticKeywordCoveragePolicy.needsManagedSource(conditionKey, publicActiveKeys)) {
                return@forEach
            }
            val existing = remaining.firstOrNull {
                it.observationPurpose == ObservationPurposes.KEYWORD_DISCOVERY &&
                    it.managedConditionKey == conditionKey
            }
            if (existing == null) {
                managedSource(
                    rules.first().conditionValue,
                    rules.first().playlistName,
                    conditionKey
                ).let { candidate ->
                    val id = observeDao.insert(candidate)
                    if (id > 0) {
                        repository.observeTask(candidate.copy(id = id))
                    }
                }
            } else if (existing.status != ObserveSourcesRepository.SourceStatus.ACTIVE) {
                val active = existing.copy(status = ObserveSourcesRepository.SourceStatus.ACTIVE)
                    .also { observeDao.update(it) }
                repository.observeTask(active)
            }
        }
    }

    private companion object {
        val reconciliationMutex = Mutex()
    }

    private fun managedSource(url: String, playlistName: String, conditionKey: String) =
        ObserveSourcesItem(
            id = 0,
            name = context.getString(
                R.string.automatic_keyword_managed_source_name,
                playlistName.ifBlank { url }
            ),
            url = url,
            downloadItemTemplate = discoveryOnlyTemplate(url),
            everyNr = 1,
            everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
            everyTime = System.currentTimeMillis(),
            weeklyConfig = null,
            monthlyConfig = null,
            status = ObserveSourcesRepository.SourceStatus.ACTIVE,
            startsTime = System.currentTimeMillis(),
            endsDate = 0,
            endsAfterCount = 0,
            runCount = 0,
            getOnlyNewUploads = false,
            retryMissingDownloads = false,
            ignoredLinks = mutableListOf(),
            alreadyProcessedLinks = mutableListOf(),
            syncWithSource = false,
            excludeShorts = false,
            observationPurpose = ObservationPurposes.KEYWORD_DISCOVERY,
            managedConditionKey = conditionKey
        )

    private fun discoveryOnlyTemplate(url: String) = DownloadItem(
        id = 0,
        url = url,
        title = "",
        author = "",
        thumb = "",
        duration = "",
        type = DownloadType.video,
        format = Format(),
        container = "",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = "",
        website = "",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = DownloadRepository.Status.Cancelled.toString(),
        downloadStartTime = 0,
        logID = null
    )
}
