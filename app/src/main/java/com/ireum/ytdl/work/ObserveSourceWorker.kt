
package com.ireum.ytdl.work

import android.content.Context
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
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.util.Extensions.calculateNextTimeForObserving
import com.ireum.ytdl.util.Extensions.getIDFromYoutubeURL
import com.ireum.ytdl.util.Extensions.isYoutubeURL
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.google.gson.Gson
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
    private companion object {
        const val OBS_DUP_LOG_TAG = "ObserveDuplicate"
    }

    private fun canonicalUrl(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.isYoutubeURL()) return trimmed
        val id = trimmed.getIDFromYoutubeURL() ?: return trimmed
        return "https://youtu.be/$id"
    }

    private fun areSameSourceUrl(a: String, b: String): Boolean {
        return canonicalUrl(a) == canonicalUrl(b)
    }

    private fun equivalentUrls(url: String): List<String> {
        val canonical = canonicalUrl(url)
        if (!canonical.startsWith("https://youtu.be/")) return listOf(url)
        val id = canonical.removePrefix("https://youtu.be/")
        return listOf(
            canonical,
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id"
        ).distinct()
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

    override suspend fun doWork(): Result {
        val sourceID = inputData.getLong("id", 0)
        if (sourceID == 0L) return Result.success()

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

        val list = kotlin.runCatching {
            resultRepository.getResultsFromSource(item.url, resetResults = false, addToResults = false, singleItem = false)
        }.onFailure {
            Log.e("observe", it.toString())
        }.getOrElse { listOf() }

        //delete downloaded items not present in source if sync is enabled
        if (item.syncWithSource && item.alreadyProcessedLinks.isNotEmpty()){
            val processedLinks = item.alreadyProcessedLinks
            val incomingLinks = list.map { canonicalUrl(it.url) }
            Log.d(
                OBS_DUP_LOG_TAG,
                "sync check sourceId=$sourceID processed=${processedLinks.size} incoming=${incomingLinks.size}"
            )

            val linksNotPresentAnymore = processedLinks.filter { !incomingLinks.contains(canonicalUrl(it)) }
            linksNotPresentAnymore.forEach {
                val historyItems = getHistoryByEquivalentUrl(historyRepo, it)
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "sync remove check sourceId=$sourceID url=$it canonical=${canonicalUrl(it)} historyMatches=${historyItems.size} type=${item.downloadItemTemplate.type}"
                )
                historyItems.filter { h -> h.type == item.downloadItemTemplate.type }.forEach { h ->
                    historyRepo.delete(h, true)
                }
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

        val toProcess = mutableListOf<ResultItem>()
        //filter what results need to be downloaded, ignored
        for (result in list) {
            val canonicalResultUrl = canonicalUrl(result.url)
            if (item.ignoredLinks.any { areSameSourceUrl(it, result.url) }) {
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "skip ignored sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
                )
                continue
            }
            if (item.excludeShorts && isShortsItem(result)) {
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "skip shorts sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
                )
                continue
            }

            // if first run and get only new items, ignore
            if (item.getOnlyNewUploads && item.runCount == 0) {
                item.ignoredLinks.add(canonicalResultUrl)
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "skip first-run-only-new sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
                )
                continue
            }

            val history = getHistoryByEquivalentUrl(historyRepo, result.url)
                .filter { it.type == item.downloadItemTemplate.type }
            Log.d(
                OBS_DUP_LOG_TAG,
                "history lookup sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl equivalentUrls=${equivalentUrls(result.url)} historyCount=${history.size}"
            )
            //if history is empty or all history items are deleted, add for retry
            if (item.retryMissingDownloads && (history.isEmpty() || history.none { hi -> hi.downloadPath.any { path -> FileUtil.exists(path) } })) {
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "toProcess retryMissing sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl historyCount=${history.size}"
                )
                toProcess.add(result)
                continue
            }

            if (item.alreadyProcessedLinks.isEmpty()) {
                if (history.isEmpty()) {
                    Log.d(
                        OBS_DUP_LOG_TAG,
                        "toProcess first-run-no-history sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
                    )
                    toProcess.add(result)
                    continue
                }
            }

            if (item.alreadyProcessedLinks.any { areSameSourceUrl(it, result.url) }) {
                Log.d(
                    OBS_DUP_LOG_TAG,
                    "skip alreadyProcessed sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
                )
                continue
            }

            Log.d(
                OBS_DUP_LOG_TAG,
                "toProcess default sourceId=$sourceID url=${result.url} canonical=$canonicalResultUrl"
            )
            toProcess.add(result)
        }

        var runMessage = if (list.isEmpty()) {
            context.getString(com.ireum.ytdl.R.string.observe_log_no_downloadable_videos)
        } else {
            context.getString(com.ireum.ytdl.R.string.observe_log_all_already_downloaded)
        }
        var runDetail = ""

        val downloadItems = mutableListOf<DownloadItem>()
        toProcess.forEach {
            val string = Gson().toJson(item.downloadItemTemplate, DownloadItem::class.java)
            val downloadItem = Gson().fromJson(string, DownloadItem::class.java)
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
                }
            }

            if (useScheduler && !alarmScheduler.isDuringTheScheduledTime() && alarmScheduler.canSchedule()){
                alarmScheduler.schedule()
            }else {
                downloadRepo.startDownloadWorker(queuedItems, context)
            }

            runMessage = if (queuedItems.isEmpty()) {
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
            runDetail = queuedItems.joinToString("\n") { q -> q.title.ifBlank { q.url } }

            item.alreadyProcessedLinks.addAll(downloadItems.map { canonicalUrl(it.url) })
        }

        addRunHistory(item, runMessage, runDetail)
        item.runCount += 1
        val currentTime = System.currentTimeMillis()
        val isFinished =
            (item.endsAfterCount > 0 && item.runCount >= item.endsAfterCount) ||
            (item.endsDate > 0 && currentTime >= item.endsDate)

        if (isFinished) {
            item.status = ObserveSourcesRepository.SourceStatus.STOPPED
            item.runInProgress = false
            item.currentRunStatus = ""
            withContext(Dispatchers.IO){
                repo.update(item)
            }
            return Result.success()
        }

        item.runInProgress = false
        item.currentRunStatus = ""
        withContext(Dispatchers.IO){
            repo.update(item)
        }

        //schedule for next time
        val allowMeteredNetworks = sharedPreferences.getBoolean("metered_networks", true)

        val workConstraints = Constraints.Builder()
        if (!allowMeteredNetworks) workConstraints.setRequiredNetworkType(NetworkType.UNMETERED)
        else {
            workConstraints.setRequiredNetworkType(NetworkType.CONNECTED)
        }

        val workRequest = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
            .addTag("observeSources")
            .addTag(sourceID.toString())
            .setConstraints(workConstraints.build())
            .setInitialDelay(item.calculateNextTimeForObserving() - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong("id", sourceID).build())

        WorkManager.getInstance(context).enqueueUniqueWork(
            "OBSERVE$sourceID",
            ExistingWorkPolicy.REPLACE,
            workRequest.build()
        )

        return Result.success()
    }

}

