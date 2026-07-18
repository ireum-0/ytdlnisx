

package com.ireum.ytdl.work

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ireum.ytdl.App
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.receiver.ObserveRetryDecisionReceiver
import com.ireum.ytdl.util.Extensions.calculateNextTimeForObserving
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.LinkUtil
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.ireum.ytdl.util.storage.AndroidHistoryFileDeletionGateway
import com.ireum.ytdl.util.storage.HistoryDeletionRecord
import com.ireum.ytdl.util.storage.HistoryFileDeletionEngine
import com.ireum.ytdl.util.storage.HistoryFileDeletionGateway
import com.ireum.ytdl.util.storage.referencesSameFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit


class ObserveSourceWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    companion object {
        const val INPUT_SOURCE_ID = "id"
        const val INPUT_CONFIRMED_URL = "confirmedUrl"
        const val INPUT_CONFIRMATION_DECISION = "confirmationDecision"
        private const val OBS_DUP_LOG_TAG = "ObserveDuplicate"
    }

    private fun canonicalUrl(url: String): String {
        return LinkUtil.canonicalYoutubeVideoUrlOrSelf(url)
    }

    private fun deletionTargets(
        item: HistoryItem,
        gateway: HistoryFileDeletionGateway
    ): List<String> {
        return (item.downloadPath + trustedResolvedTreeTargets(item, gateway))
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun trustedResolvedTreeTargets(
        item: HistoryItem,
        gateway: HistoryFileDeletionGateway
    ): List<String> {
        val resolvedTreeTarget = if (item.localTreeUri.isNotBlank() && item.localTreePath.isNotBlank()) {
            FileUtil.resolveTreeDocumentUri(item.localTreeUri, item.localTreePath)?.toString()
        } else {
            null
        }
        return listOfNotNull(resolvedTreeTarget).filter { treeTarget ->
            item.downloadPath.any { storedTarget ->
                gateway.referencesSameFile(treeTarget, storedTarget)
            }
        }
    }

    private fun areSameSourceUrl(a: String, b: String): Boolean {
        return canonicalUrl(a) == canonicalUrl(b)
    }

    private fun equivalentUrls(url: String): List<String> {
        return LinkUtil.equivalentYoutubeVideoUrls(url)
    }

    private fun getHistoryByEquivalentUrl(historyRepo: HistoryRepository, url: String) =
        equivalentUrls(url)
            .flatMap { historyRepo.getItemsByUrl(it) }
            .distinctBy { it.id }

    private suspend fun updateRunStatus(
        repo: ObserveSourcesRepository,
        item: ObserveSourcesItem,
        inProgress: Boolean,
        status: String,
        workerID: Int,
        notificationUtil: NotificationUtil
    ) {
        item.runInProgress = inProgress
        item.currentRunStatus = status
        withContext(Dispatchers.IO) {
            repo.update(item)
        }
        val notification = notificationUtil.createObserveSourcesNotification(item.name, status)
        if (Build.VERSION.SDK_INT >= 33) {
            setForeground(
                ForegroundInfo(
                    workerID,
                    notification,
                    FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            )
        } else {
            setForeground(ForegroundInfo(workerID, notification))
        }
    }

    private fun addRunHistory(item: ObserveSourcesItem, message: String, detail: String = "") {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        item.runHistory.add("$timestamp / $message|||$detail")
        if (item.runHistory.size > 200) {
            val overflow = item.runHistory.size - 200
            repeat(overflow) { item.runHistory.removeAt(0) }
        }
    }

    private fun isShortsItem(result: ResultItem): Boolean {
        val url = result.url.lowercase()
        val playlistUrl = result.playlistURL.orEmpty().lowercase()
        val playlistTitle = result.playlistTitle.lowercase()
        return url.contains("/shorts/") ||
            playlistUrl.contains("/shorts") ||
            playlistTitle.contains("shorts")
    }

    private suspend fun finishRunAndSchedule(
        repo: ObserveSourcesRepository,
        sharedPreferences: SharedPreferences,
        sourceID: Long,
        item: ObserveSourcesItem,
        message: String,
        detail: String = "",
        countRun: Boolean = true
    ): Result {
        addRunHistory(item, message, detail)
        if (countRun) item.runCount += 1
        val currentTime = System.currentTimeMillis()
        val isFinished =
            (item.endsAfterCount > 0 && item.runCount >= item.endsAfterCount) ||
                (item.endsDate > 0 && currentTime >= item.endsDate)

        item.runInProgress = false
        item.currentRunStatus = ""

        if (isFinished) {
            item.status = ObserveSourcesRepository.SourceStatus.STOPPED
            withContext(Dispatchers.IO) {
                repo.update(item)
            }
            return Result.success()
        }

        withContext(Dispatchers.IO) {
            repo.update(item)
        }

        val allowMeteredNetworks = sharedPreferences.getBoolean("metered_networks", true)
        val workConstraints = Constraints.Builder()
        if (!allowMeteredNetworks) {
            workConstraints.setRequiredNetworkType(NetworkType.UNMETERED)
        } else {
            workConstraints.setRequiredNetworkType(NetworkType.CONNECTED)
        }

        val initialDelay = (item.calculateNextTimeForObserving() - System.currentTimeMillis()).coerceAtLeast(0L)
        val workRequest = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
            .addTag("observeSources")
            .addTag("observation_$sourceID")
            .addTag(sourceID.toString())
            .setConstraints(workConstraints.build())
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong(INPUT_SOURCE_ID, sourceID).build())

        WorkManager.getInstance(context).enqueueUniqueWork(
            "OBSERVE$sourceID",
            ExistingWorkPolicy.REPLACE,
            workRequest.build()
        )

        return Result.success()
    }

    override suspend fun doWork(): Result {
        return try {
            runSourceWork()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("ObserveSourceWorker", "Auto Download run failed", error)
            recoverFailedRun(error)
        }
    }

    private suspend fun recoverFailedRun(error: Exception): Result {
        val sourceID = inputData.getLong(INPUT_SOURCE_ID, 0L)
        if (sourceID == 0L) return Result.failure()
        return try {
            val dbManager = DBManager.getInstance(context)
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val repo = ObserveSourcesRepository(
                dbManager.observeSourcesDao,
                WorkManager.getInstance(context),
                sharedPreferences
            )
            val item = withContext(Dispatchers.IO) {
                dbManager.observeSourcesDao.getByIDOrNull(sourceID)
            } ?: return Result.success()
            finishRunAndSchedule(
                repo = repo,
                sharedPreferences = sharedPreferences,
                sourceID = sourceID,
                item = item,
                message = context.getString(com.ireum.ytdl.R.string.observe_log_run_failed),
                detail = error.javaClass.simpleName,
                countRun = false
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun runSourceWork(): Result {
        val sourceID = inputData.getLong(INPUT_SOURCE_ID, 0)
        if (sourceID == 0L) return Result.success()
        val confirmedCanonicalUrl = inputData.getString(INPUT_CONFIRMED_URL)?.let(::canonicalUrl)
        val confirmationDecision = inputData.getString(INPUT_CONFIRMATION_DECISION).orEmpty()

        val notificationUtil = NotificationUtil(App.instance)
        val dbManager = DBManager.getInstance(context)
        val workManager = WorkManager.getInstance(context)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val repo = ObserveSourcesRepository(dbManager.observeSourcesDao, workManager, sharedPreferences)
        val historyRepo = HistoryRepository(dbManager.historyDao, dbManager.playlistDao)
        val downloadRepo = DownloadRepository(dbManager.downloadDao)
        val commandTemplateDao = dbManager.commandTemplateDao
        val resultRepository = ResultRepository(dbManager.resultDao, commandTemplateDao, context)

        val ytdlpUtil = YTDLPUtil(context, commandTemplateDao)

        val item = repo.getByID(sourceID)
        if (item.status == ObserveSourcesRepository.SourceStatus.STOPPED){
            return Result.success()
        }

        val workerID = System.currentTimeMillis().toInt()
        val notification = notificationUtil.createObserveSourcesNotification(item.name)
        if (Build.VERSION.SDK_INT >= 33) {
            setForegroundAsync(ForegroundInfo(workerID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        }else{
            setForegroundAsync(ForegroundInfo(workerID, notification))
        }

        updateRunStatus(
            repo,
            item,
            true,
            context.getString(com.ireum.ytdl.R.string.observe_status_fetching),
            workerID,
            notificationUtil
        )

        val sourceResult = kotlin.runCatching {
            resultRepository.getResultsFromSource(item.url, resetResults = false, addToResults = false, singleItem = false)
        }.onFailure {
            Log.e("observe", it.toString())
        }
        if (sourceResult.isFailure) {
            val error = sourceResult.exceptionOrNull()
            return finishRunAndSchedule(
                repo = repo,
                sharedPreferences = sharedPreferences,
                sourceID = sourceID,
                item = item,
                message = context.getString(com.ireum.ytdl.R.string.observe_log_source_fetch_failed),
                detail = error?.message.orEmpty(),
                countRun = false
            )
        }
        val list = sourceResult.getOrThrow()
        val previouslyObservedCanonicalUrls = item.observedLinks
            .asSequence()
            .map(::canonicalUrl)
            .toSet()
        val observedCanonicalUrls = previouslyObservedCanonicalUrls.toMutableSet()
        list.asSequence()
            .filterNot { item.excludeShorts && isShortsItem(it) }
            .map { canonicalUrl(it.url) }
            .filter { observedCanonicalUrls.add(it) }
            .forEach { item.observedLinks.add(it) }

        //delete downloaded items not present in source if sync is enabled
        if (item.syncWithSource && item.alreadyProcessedLinks.isNotEmpty()){
            val processedLinks = item.alreadyProcessedLinks
            val incomingLinks = list.map { canonicalUrl(it.url) }.toSet()
            Log.d(
                OBS_DUP_LOG_TAG,
                "sync check sourceId=$sourceID processed=${processedLinks.size} incoming=${incomingLinks.size}"
            )

            val linksNotPresentAnymore = processedLinks.filter { !incomingLinks.contains(canonicalUrl(it)) }
            val historyItemsToRemove = linksNotPresentAnymore.flatMap { missingLink ->
                val historyItems = getHistoryByEquivalentUrl(historyRepo, missingLink)
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "sync remove check sourceId=$sourceID historyMatches=${historyItems.size} type=${item.downloadItemTemplate.type}"
                )
                historyItems.filter { history -> history.type == item.downloadItemTemplate.type }
            }.distinctBy { history -> history.id }

            if (historyItemsToRemove.isNotEmpty()) {
                val deletionGateway = AndroidHistoryFileDeletionGateway(context)
                val selectedIds = historyItemsToRemove.mapTo(hashSetOf()) { history -> history.id }
                val retainedTargets = historyRepo.getAll()
                    .asSequence()
                    .filterNot { history -> history.id in selectedIds }
                    .flatMap { history -> deletionTargets(history, deletionGateway).asSequence() }
                val deletionRecords = historyItemsToRemove.map { history ->
                    val trustedDocumentTargets = trustedResolvedTreeTargets(history, deletionGateway)
                    HistoryDeletionRecord(
                        id = history.id,
                        storedTargets = (history.downloadPath + trustedDocumentTargets).distinct(),
                        trustedDocumentTargets = trustedDocumentTargets.toSet()
                    )
                }
                val deletionResult = HistoryFileDeletionEngine(
                    deletionGateway
                ).let { engine ->
                    val validation = engine.excludeTargetsReferencedBy(
                        validation = engine.validate(deletionRecords),
                        retainedStoredTargets = retainedTargets
                    )
                    engine.execute(validation)
                }
                val removableIds = deletionResult.removableRecordIds
                historyRepo.deleteRecords(removableIds.toList())
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "sync remove result sourceId=$sourceID records=${removableIds.size} " +
                        "deleted=${deletionResult.filesDeleted} absent=${deletionResult.filesAlreadyAbsent} " +
                        "skipped=${deletionResult.filesSkipped} failed=${deletionResult.filesPermissionDenied + deletionResult.filesFailed}"
                )
            }
        }

        updateRunStatus(
            repo,
            item,
            true,
            context.getString(com.ireum.ytdl.R.string.observe_status_filtering),
            workerID,
            notificationUtil
        )

        if (item.getOnlyNewUploads && item.runCount == 0) {
            val ignoredCanonicalUrls = item.ignoredLinks.map { canonicalUrl(it) }.toMutableSet()
            list.asSequence()
                .filterNot { item.excludeShorts && isShortsItem(it) }
                .map { canonicalUrl(it.url) }
                .filter { ignoredCanonicalUrls.add(it) }
                .forEach { item.ignoredLinks.add(it) }

            val runMessage = if (list.isEmpty()) {
                context.getString(com.ireum.ytdl.R.string.observe_log_no_downloadable_videos)
            } else {
                context.getString(com.ireum.ytdl.R.string.observe_log_all_already_downloaded)
            }

            return finishRunAndSchedule(repo, sharedPreferences, sourceID, item, runMessage)
        }

        val toProcess = mutableListOf<ResultItem>()
        val confirmationCandidates = mutableListOf<ResultItem>()
        val ignoredCanonicalUrls = item.ignoredLinks.map { canonicalUrl(it) }.toMutableSet()
        val processedCanonicalUrls = item.alreadyProcessedLinks.map { canonicalUrl(it) }.toMutableSet()
        val retryPromptedCanonicalUrls = item.retryPromptedLinks.map { canonicalUrl(it) }.toMutableSet()
        val pendingDownloadCanonicalUrls = withContext(Dispatchers.IO) {
            downloadRepo.getPendingObservationDownloads()
                .asSequence()
                .filter { it.type == item.downloadItemTemplate.type }
                .map { canonicalUrl(it.url) }
                .toSet()
        }
        var ignoredSkipped = 0
        var shortsSkipped = 0
        var processedSkipped = 0

        fun rememberProcessedUrl(canonicalUrl: String) {
            if (processedCanonicalUrls.add(canonicalUrl)) {
                item.alreadyProcessedLinks.add(canonicalUrl)
            }
        }

        fun rememberRetryPromptedUrl(canonicalUrl: String) {
            if (retryPromptedCanonicalUrls.add(canonicalUrl)) {
                item.retryPromptedLinks.add(canonicalUrl)
            }
        }

        //filter what results need to be downloaded, ignored
        for (result in list) {
            val canonicalResultUrl = canonicalUrl(result.url)
            if (ignoredCanonicalUrls.contains(canonicalResultUrl)) {
                ignoredSkipped += 1
                continue
            }
            if (item.excludeShorts && isShortsItem(result)) {
                shortsSkipped += 1
                continue
            }

            val history = getHistoryByEquivalentUrl(historyRepo, result.url)
                .filter { it.type == item.downloadItemTemplate.type }
            Log.d(
                OBS_DUP_LOG_TAG,
                "history lookup sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl equivalentUrls=${equivalentUrls(result.url)} historyCount=${history.size}"
            )

            val hasExistingHistoryFile = history.any { hi ->
                hi.downloadPath.any { path -> FileUtil.exists(path) }
            }
            if (hasExistingHistoryFile) {
                processedSkipped += 1
                rememberProcessedUrl(canonicalResultUrl)
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "skip history-existing sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
                )
                continue
            }

            if (pendingDownloadCanonicalUrls.contains(canonicalResultUrl)) {
                processedSkipped += 1
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "skip pending-download sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
                )
                continue
            }

            val wasPreviouslyProcessed = processedCanonicalUrls.contains(canonicalResultUrl)
            val wasPreviouslyObserved = previouslyObservedCanonicalUrls.contains(canonicalResultUrl)
            val isMissingPreviousDownload = history.isNotEmpty() || wasPreviouslyProcessed || wasPreviouslyObserved
            val isConfirmedTarget = isMissingPreviousDownload && confirmedCanonicalUrl == canonicalResultUrl
            if (isConfirmedTarget && confirmationDecision == ObserveRetryDecisionReceiver.ACTION_DOWNLOAD) {
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "toProcess retry-confirmed sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
                )
                toProcess.add(result)
                continue
            }
            if (isConfirmedTarget && confirmationDecision == ObserveRetryDecisionReceiver.ACTION_IGNORE) {
                rememberRetryPromptedUrl(canonicalResultUrl)
                if (ignoredCanonicalUrls.add(canonicalResultUrl)) {
                    item.ignoredLinks.add(canonicalResultUrl)
                }
                ignoredSkipped += 1
                continue
            }

            // Only ask for an item seen on an earlier successful scan, previously
            // processed, or represented by history whose local file disappeared.
            // Genuinely new uploads remain automatic.
            if (item.retryMissingDownloads && isMissingPreviousDownload) {
                if (retryPromptedCanonicalUrls.contains(canonicalResultUrl)) {
                    processedSkipped += 1
                    continue
                }
                confirmationCandidates.add(result)
                continue
            }

            if (wasPreviouslyObserved && history.isEmpty() && !wasPreviouslyProcessed) {
                processedSkipped += 1
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "skip previously-observed-retry-disabled sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
                )
                continue
            }

            if (history.isNotEmpty()) {
                processedSkipped += 1
                rememberProcessedUrl(canonicalResultUrl)
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "skip history-missing-retry-disabled sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl historyCount=${history.size}"
                )
                continue
            }

            if (processedCanonicalUrls.isEmpty()) {
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "toProcess first-run-no-history sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
                )
                toProcess.add(result)
                continue
            }

            if (processedCanonicalUrls.contains(canonicalResultUrl)) {
                processedSkipped += 1
                continue
            }

            Log.d(
                OBS_DUP_LOG_TAG,
                "toProcess default sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
            )
            toProcess.add(result)
        }
        Log.d(
            OBS_DUP_LOG_TAG,
            "filter summary sourceId=$sourceID total=${list.size} ignored=$ignoredSkipped shorts=$shortsSkipped processed=$processedSkipped confirmations=${confirmationCandidates.size} toProcess=${toProcess.size}"
        )

        var runMessage = if (list.isEmpty()) {
            context.getString(com.ireum.ytdl.R.string.observe_log_no_downloadable_videos)
        } else {
            context.getString(com.ireum.ytdl.R.string.observe_log_all_already_downloaded)
        }
        var runDetail = ""

        val confirmationCandidate = confirmationCandidates.firstOrNull()
        val canShowRetryConfirmation = confirmationCandidate != null &&
            notificationUtil.canShowObserveRetryConfirmation()
        confirmationCandidate?.let { candidate ->
            runMessage = if (canShowRetryConfirmation) {
                context.getString(
                    com.ireum.ytdl.R.string.observe_log_waiting_retry_confirmation,
                    candidate.title.ifBlank { candidate.url }
                )
            } else {
                context.getString(
                    com.ireum.ytdl.R.string.observe_log_retry_confirmation_unavailable,
                    candidate.title.ifBlank { candidate.url }
                )
            }
            runDetail = candidate.url
        }
        val downloadItems = mutableListOf<DownloadItem>()
        val converter = Converters()
        var confirmedRetryHandled = false
        toProcess.forEach {
            val string = converter.downloadItemToString(item.downloadItemTemplate)
            val downloadItem = converter.stringToDownloadItem(string)
            downloadItem.title = it.title
//            downloadItem.author = it.author DONT ADD IT, can conflict with playlist uploader album artist etc etc
            downloadItem.duration = it.duration
            downloadItem.website = it.website
            downloadItem.url = it.url
            downloadItem.thumb = it.thumb
            downloadItem.status = DownloadRepository.Status.Queued.toString()
            downloadItem.playlistTitle = it.playlistTitle
            downloadItem.playlistURL = it.playlistURL
            downloadItem.playlistIndex = it.playlistIndex
            downloadItem.observeSourceId = item.id
            downloadItem.id = 0L
            downloadItems.add(downloadItem)
        }


        if (downloadItems.isNotEmpty()){
            updateRunStatus(
                repo,
                item,
                true,
                context.getString(com.ireum.ytdl.R.string.observe_status_queueing, downloadItems.size),
                workerID,
                notificationUtil
            )
            //QUEUE DOWNLOADS
            val context = App.instance
            val alarmScheduler = AlarmScheduler(context)
            val activeAndQueuedDownloads = downloadRepo.getActiveAndQueuedDownloads().toMutableList()
            val queuedItems = mutableListOf<DownloadItem>()
            val checkDuplicate = sharedPreferences.getString("prevent_duplicate_downloads", "") ?: ""
            val downloadArchive: List<String> = runCatching {
                File(FileUtil.getDownloadArchivePath(context)).useLines { lines ->
                    lines.mapNotNull { line -> line.split(" ").getOrNull(1) }.toList()
                }
            }.getOrElse { emptyList() }

            //if scheduler is on
            val useScheduler = sharedPreferences.getBoolean("use_scheduler", false)

//            if (items.any { it.playlistTitle.isEmpty() } && items.size > 1){
//                items.forEachIndexed { index, it -> it.playlistTitle = "Various[${index+1}]" }
//            }

            downloadItems.forEach {
                it.status = DownloadRepository.Status.Queued.toString()
                val currentCanonicalUrl = canonicalUrl(it.url)
                val isConfirmedRetry =
                    confirmationDecision == ObserveRetryDecisionReceiver.ACTION_DOWNLOAD &&
                        confirmedCanonicalUrl == currentCanonicalUrl
                val currentCommand = ytdlpUtil.buildYoutubeDLRequest(it)
                val parsedCurrentCommand = ytdlpUtil.parseYTDLRequestString(currentCommand)
                var isDuplicate = false

                if (checkDuplicate.isNotEmpty()) {
                    when (checkDuplicate) {
                        "download_archive" -> {
                            if (downloadArchive.any { archiveId -> it.url.contains(archiveId) }) {
                                isDuplicate = true
                                Log.d(
                                    OBS_DUP_LOG_TAG,
                                    "queue skip archive sourceId=$sourceID url=${it.url} canonical=${canonicalUrl(it.url)}"
                                )
                            }
                        }

                        "url_type" -> {
                            val existingDownload = activeAndQueuedDownloads.firstOrNull { d ->
                                d.type == it.type && areSameSourceUrl(d.url, it.url)
                            }
                            if (existingDownload != null) {
                                isDuplicate = true
                                Log.d(
                                    OBS_DUP_LOG_TAG,
                                    "queue skip activeQueued(url_type) sourceId=$sourceID url=${it.url} canonical=${canonicalUrl(it.url)} existingId=${existingDownload.id}"
                                )
                            } else {
                                val history = withContext(Dispatchers.IO) {
                                    getHistoryByEquivalentUrl(historyRepo, it.url)
                                        .filter { item -> item.type == it.type }
                                        .filter { item -> item.downloadPath.any { path -> FileUtil.exists(path) } }
                                }
                                if (history.isNotEmpty()) {
                                    isDuplicate = true
                                    Log.d(
                                        OBS_DUP_LOG_TAG,
                                        "queue skip history(url_type) sourceId=$sourceID url=${it.url} canonical=${canonicalUrl(it.url)} historyId=${history.first().id}"
                                    )
                                }
                            }
                        }

                        "config" -> {
                            val existingDownload = activeAndQueuedDownloads.firstOrNull { d ->
                                val normalized = d.copy(
                                    id = 0,
                                    logID = null,
                                    customFileNameTemplate = it.customFileNameTemplate,
                                    status = DownloadRepository.Status.Queued.toString()
                                )
                                normalized.toString() == it.toString()
                            }
                            if (existingDownload != null) {
                                isDuplicate = true
                                Log.d(
                                    OBS_DUP_LOG_TAG,
                                    "queue skip activeQueued(config) sourceId=$sourceID url=${it.url} canonical=${canonicalUrl(it.url)} existingId=${existingDownload.id}"
                                )
                            } else {
                                val history = withContext(Dispatchers.IO) {
                                    getHistoryByEquivalentUrl(historyRepo, it.url)
                                        .filter { item -> item.downloadPath.any { path -> FileUtil.exists(path) } }
                                }
                                val existingHistory = history.firstOrNull { h ->
                                    h.command.replace("(-P \"(.*?)\")|(--trim-filenames \"(.*?)\")".toRegex(), "") ==
                                        parsedCurrentCommand.replace("(-P \"(.*?)\")|(--trim-filenames \"(.*?)\")".toRegex(), "")
                                }
                                if (existingHistory != null) {
                                    isDuplicate = true
                                    Log.d(
                                        OBS_DUP_LOG_TAG,
                                        "queue skip history(config) sourceId=$sourceID url=${it.url} canonical=${canonicalUrl(it.url)} historyId=${existingHistory.id}"
                                    )
                                }
                            }
                        }
                    }
                }

                if (!isDuplicate) {
                    Log.d(
                        OBS_DUP_LOG_TAG,
                        "queue add sourceId=$sourceID url=${it.url} canonical=${canonicalUrl(it.url)}"
                    )
                    if (it.id == 0L){
                        it.id = downloadRepo.insert(it)
                    }else if (it.status == DownloadRepository.Status.Queued.toString()){
                        downloadRepo.update(it)
                    }
                    queuedItems.add(it)
                    activeAndQueuedDownloads.add(it)
                    if (isConfirmedRetry) {
                        rememberRetryPromptedUrl(currentCanonicalUrl)
                        confirmedRetryHandled = true
                    }
                } else if (isConfirmedRetry) {
                    // The user already made a durable decision. A duplicate policy
                    // rejection is a handled result, not a transient fetch failure.
                    rememberRetryPromptedUrl(currentCanonicalUrl)
                    confirmedRetryHandled = true
                }
            }

            if (confirmedRetryHandled) {
                // Persist only after the confirmed target was queued or deliberately
                // rejected by duplicate policy. A missing fetch result remains retryable.
                withContext(Dispatchers.IO) {
                    repo.update(item)
                }
            }

            if (useScheduler && !alarmScheduler.isDuringTheScheduledTime() && alarmScheduler.canSchedule()){
                alarmScheduler.schedule()
            }else {
                downloadRepo.startDownloadWorker(queuedItems, context)
            }

            runMessage = if (queuedItems.isEmpty() && confirmationCandidates.isNotEmpty()) {
                runMessage
            } else if (queuedItems.isEmpty()) {
                context.getString(com.ireum.ytdl.R.string.observe_log_all_already_downloaded)
            } else if (queuedItems.size == 1) {
                context.getString(com.ireum.ytdl.R.string.observe_log_downloaded_single, queuedItems.first().title.ifBlank { queuedItems.first().url })
            } else {
                context.getString(
                    com.ireum.ytdl.R.string.observe_log_downloaded_multiple,
                    queuedItems.first().title.ifBlank { queuedItems.first().url },
                    queuedItems.size - 1
                )
            }
            if (queuedItems.isNotEmpty() || confirmationCandidates.isEmpty()) {
                runDetail = queuedItems.joinToString("\n") { q -> q.title.ifBlank { q.url } }
            }

            queuedItems.forEach { rememberProcessedUrl(canonicalUrl(it.url)) }
        }

        val result = finishRunAndSchedule(
            repo = repo,
            sharedPreferences = sharedPreferences,
            sourceID = sourceID,
            item = item,
            message = runMessage,
            detail = runDetail,
            countRun = !canShowRetryConfirmation
        )

        if (
            confirmationCandidate != null &&
            canShowRetryConfirmation &&
            item.status == ObserveSourcesRepository.SourceStatus.ACTIVE
        ) {
            val shown = notificationUtil.showObserveRetryConfirmation(
                sourceId = sourceID,
                sourceName = item.name,
                videoTitle = confirmationCandidate.title,
                canonicalUrl = canonicalUrl(confirmationCandidate.url)
            )
            if (!shown) notificationUtil.cancelObserveRetryConfirmation(sourceID)
        } else {
            notificationUtil.cancelObserveRetryConfirmation(sourceID)
        }

        return result
    }

}
