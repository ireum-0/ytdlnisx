package com.ireum.ytdl.work

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.afollestad.materialdialogs.utils.MDUtil.getStringArray
import com.ireum.ytdl.App
import com.ireum.ytdl.MainActivity
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.LogItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.LogRepository
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.util.Extensions.getIDFromYoutubeURL
import com.ireum.ytdl.util.Extensions.getMediaDuration
import com.ireum.ytdl.util.Extensions.isYoutubeURL
import com.ireum.ytdl.util.Extensions.toStringDuration
import com.ireum.ytdl.util.Extensions.toDurationSeconds
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.PendingDuplicateDownloadStore
import com.ireum.ytdl.util.SensitiveTextRedactor
import com.ireum.ytdl.util.SubtitleFileValidator
import com.ireum.ytdl.util.SubtitleFormatConverter
import com.ireum.ytdl.util.SubtitleSelection
import com.ireum.ytdl.util.download.DownloadIssue
import com.ireum.ytdl.util.download.DownloadIssueClassifier
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueSeverity
import com.ireum.ytdl.util.download.DownloadIssueSource
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.ireum.ytdl.util.download.DownloadIssueStageTracker
import com.ireum.ytdl.util.download.DownloadIssueText
import com.ireum.ytdl.util.download.DownloadOutcome
import com.ireum.ytdl.util.download.DownloadRetryMetadata
import com.ireum.ytdl.util.download.DownloadRetryPolicy
import com.ireum.ytdl.util.download.DownloadRetryStrategy
import com.ireum.ytdl.util.download.DownloadSuggestedAction
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.text.Regex


class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    private val workerDownloadIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val workerCleanupDownloadIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val workNotif = NotificationUtil(App.instance).createDefaultWorkerNotification()

        return ForegroundInfo(
            1000000000,
            workNotif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private suspend fun cleanupStoppedWorker() = withContext(Dispatchers.IO + NonCancellable) {
        val dao = DBManager.getInstance(context).downloadDao
        val workerOwnedIds = workerCleanupDownloadIds.toList()
        val staleDbIds = runCatching {
            dao.getActiveAndPostProcessingDownloadsList()
                .map { it.id }
                .filter { it !in ownedDownloadIds }
        }.getOrDefault(emptyList())
        val activeIds = (workerOwnedIds + staleDbIds).distinct()
        if (activeIds.isEmpty()) return@withContext

        activeIds.forEach { downloadId ->
            cancelYtdlpProcess(downloadId)
            cancelPostProcessingById(downloadId)
            runCatching {
                NotificationUtil(context).cancelDownloadNotification(downloadId.toInt())
            }
        }
        runCatching {
            dao.setStatusMultipleFromStatus(
                activeIds,
                DownloadRepository.Status.Active.toString(),
                DownloadRepository.Status.Queued.toString()
            )
            dao.setStatusMultipleFromStatus(
                activeIds,
                DownloadRepository.Status.PostProcessing.toString(),
                DownloadRepository.Status.Queued.toString()
            )
        }.onFailure {
            Log.w(TAG, "Failed to requeue stopped active downloads ids=$activeIds", it)
        }
        val activeIdSet = activeIds.toSet()
        workerDownloadIds.removeAll(activeIdSet)
        workerCleanupDownloadIds.removeAll(activeIdSet)
        ownedDownloadIds.removeAll(activeIdSet)
        runningYTDLInstances.removeAll(activeIdSet)
        Log.i(TAG, "Stopped worker cleanup completed for ${activeIds.size} active download(s)")
    }

    override suspend fun doWork(): Result {
        return try {
            doWorkSerialized()
        } finally {
            if (isStopped) {
                cleanupStoppedWorker()
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @SuppressLint("RestrictedApi")
    private suspend fun doWorkSerialized(): Result {
        if (isStopped) return Result.Failure()

        if (!setForegroundSafely()) return Result.retry()

        val notificationUtil = NotificationUtil(App.instance)
        val dbManager = DBManager.getInstance(context)
        val dao = dbManager.downloadDao
        val historyDao = dbManager.historyDao
        val observeSourcesDao = dbManager.observeSourcesDao
        val commandTemplateDao = dbManager.commandTemplateDao
        val logRepo = LogRepository(dbManager.logDao)
        val resultRepo = ResultRepository(dbManager.resultDao, commandTemplateDao, context)
        val ytdlpUtil = YTDLPUtil(context, commandTemplateDao)
        val handler = Handler(Looper.getMainLooper())
        val alarmScheduler = AlarmScheduler(context)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val time = System.currentTimeMillis() + 6000
        val priorityItemIDs = (inputData.getLongArray("priority_item_ids") ?: longArrayOf()).toMutableList()
        val continueAfterPriorityIds = inputData.getBoolean("continue_after_priority_ids", true)
        val queuedItems = if (priorityItemIDs.isEmpty()) {
            dao.getQueuedScheduledDownloadsUntil(time)
        }else {
            dao.getQueuedScheduledDownloadsUntilWithPriority(time, priorityItemIDs)
        }

        // this is needed for observe sources call, so it wont create result items
        // [removed]
        //val createResultItem = inputData.getBoolean("createResultItem", true)

        val confTmp = Configuration(context.resources.configuration)
        val locale = if (Build.VERSION.SDK_INT < 33) {
            sharedPreferences.getString("app_language", "")!!.ifEmpty { Locale.getDefault().language }
        }else{
            Locale.getDefault().language
        }.run {
            split("-")
        }.run {
            if (this.size == 1) Locale(this[0]) else Locale(this[0], this[1])
        }
        confTmp.setLocale(locale)
        val metrics = DisplayMetrics()
        val resources = Resources(context.assets, metrics, confTmp)

        val openQueueIntent = Intent(context, MainActivity::class.java)
        openQueueIntent.setAction(Intent.ACTION_VIEW)
        openQueueIntent.putExtra("destination", "Queue")
        val openDownloadQueue = PendingIntent.getActivity(
            context,
            1000000000,
            openQueueIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        resetHardSubProgress()

        queuedItems.collect { items ->
            if (this@DownloadWorker.isStopped) return@collect

            val eligibleDownloads = downloadWorkerMutex.withLock {
                runningYTDLInstances.clear()
                val activeDownloads = dao.getActiveDownloadsList()
                activeDownloads.forEach {
                    runningYTDLInstances.add(it.id)
                }

                val running = ArrayList(runningYTDLInstances)
                val useScheduler = sharedPreferences.getBoolean("use_scheduler", false)
                if (items.isEmpty() && running.isEmpty()) {
                    WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                    return@collect
                }

                if (useScheduler){
                    if (items.none{it.downloadStartTime > 0L} && running.isEmpty() && !alarmScheduler.isDuringTheScheduledTime()) {
                        WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                        return@collect
                    }
                }

                if (priorityItemIDs.isEmpty() && !continueAfterPriorityIds) {
                    WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                    return@collect
                }

                val concurrentDownloads = sharedPreferences.getInt("concurrent_downloads", 1) - running.size
                val baseEligibleDownloads = if (priorityItemIDs.isNotEmpty()) {
                    val tmp = priorityItemIDs.take(concurrentDownloads)
                    items.filter { it.id !in running && tmp.contains(it.id) }
                }else{
                    items.take(concurrentDownloads).filter {  it.id !in running }
                }
                val hasRunningHardSub = activeDownloads.any { isHardSubRedownload(it) }
                val selectedDownloads = if (hasRunningHardSub) {
                    baseEligibleDownloads.filterNot { isHardSubRedownload(it) }
                } else {
                    val hardSubs = baseEligibleDownloads.filter { isHardSubRedownload(it) }
                    if (hardSubs.size <= 1) {
                        baseEligibleDownloads
                    } else {
                        val firstHardSubId = hardSubs.first().id
                        baseEligibleDownloads.filter { !isHardSubRedownload(it) || it.id == firstHardSubId }
                    }
                }

                if (selectedDownloads.isNotEmpty()){
                    selectedDownloads.forEach {
                        workerDownloadIds.add(it.id)
                        workerCleanupDownloadIds.add(it.id)
                        ownedDownloadIds.add(it.id)
                        it.status = DownloadRepository.Status.Active.toString()
                        priorityItemIDs.remove(it.id)
                    }
                    dao.updateMultiple(selectedDownloads)
                }
                selectedDownloads
            }

            coroutineScope {
                eligibleDownloads.forEach{downloadItem ->
                    launch(Dispatchers.IO) {
                    workerDownloadIds.add(downloadItem.id)
                    var requestsToCleanup = mutableListOf<YoutubeDLRequest>()
                    var tempFileDir = File(FileUtil.getCachePath(context), downloadItem.id.toString())
                    var validatedTempFileDir: File? = null
                    var effectiveCommandString = ""
                    val recentYtdlpOutput = ArrayDeque<String>()
                    var logDownloads = false
                    var initialLogDetails = ""
                    var retryLogDetails = ""
                    var hardSubPostProcessLockHeld = false
                    var currentIssueStage = DownloadIssueStage.PREFLIGHT
                    var createdOutputPaths: List<String> = emptyList()
                    var preserveQueueRecord = false
                    var downloadOutcome: DownloadOutcome? = null
                    fun recordCreatedOutputs(paths: List<String>) {
                        createdOutputPaths = (createdOutputPaths + paths)
                            .distinct()
                            .filter { path ->
                                val file = File(path)
                                file.exists() && file.isFile
                            }
                    }
                    fun shouldStopForUserRequest(): Boolean {
                        val latestStatus = runCatching { dao.checkStatus(downloadItem.id) }.getOrNull()
                        return this@DownloadWorker.isStopped ||
                            latestStatus == DownloadRepository.Status.Paused ||
                            latestStatus == DownloadRepository.Status.Cancelled
                    }
                    try {
                    if (isHardSubRedownload(downloadItem)) {
                        registerHardSubTarget(downloadItem.id)
                        updateHardSubWorkerNotification(notificationUtil)
                    }
                    val notificationTitle = SensitiveTextRedactor.redactOutput(
                        downloadItem.title.ifEmpty { downloadItem.url }
                    )
                    val notification = notificationUtil.createDownloadServiceNotification(openDownloadQueue, notificationTitle)
                    notificationUtil.notify(downloadItem.id.toInt(), notification)

                    val writtenPath = downloadItem.format.format_note.contains("-P ")
                    var shouldBurnHardSub = downloadItem.type == DownloadType.video && downloadItem.videoPreferences.embedSubs
                    val noCache = writtenPath || (!sharedPreferences.getBoolean("cache_downloads", true) && FileUtil.canWriteToDestination(downloadItem.downloadPath, context))

                    var request = ytdlpUtil.buildYoutubeDLRequest(downloadItem)
                    requestsToCleanup = mutableListOf(request)

                    // DISABLED BECAUSE YT_DLP CONSIDERS DOWNLOAD FAILURE IF -U PART FAILS, ytdlnisx #1043
//                    val updateYTDLP = sharedPreferences.getBoolean("update_ytdlp_while_downloading", false)
//                    if (updateYTDLP) {
//                        request.addOption("-U")
//                    }

                    downloadItem.status = DownloadRepository.Status.Active.toString()
                    if (downloadItem.operationId.isBlank()) {
                        downloadItem.operationId = "download-${downloadItem.id}"
                    }
                    launch {
                        delay(1500)
                        //update item if its incomplete
                        resultRepo.updateDownloadItem(downloadItem)?.apply {
                            val status = dao.checkStatus(this.id)
                            if (status == DownloadRepository.Status.Active){
                                dao.updateWithoutUpsert(this)
                            }
                        }
                    }

                    val rawTempFileDir = tempFileDir

                    val downloadLocation = downloadItem.downloadPath
                    val keepCache = sharedPreferences.getBoolean("keep_cache", false)
                    val noKeepSubs = sharedPreferences.getBoolean("no_keep_subs", false)
                    logDownloads = sharedPreferences.getBoolean("log_downloads", false) && !downloadItem.incognito


                    val commandString = ytdlpUtil.parseYTDLRequestString(request)
                    effectiveCommandString = commandString
                    val requestDiagnostics = ytdlpUtil.buildRequestDiagnostics(downloadItem, request, commandString)
                    initialLogDetails = SensitiveTextRedactor.redactOutput("Downloading:\n" +
                            "Title: ${downloadItem.title}\n" +
                            "URL: ${downloadItem.url}\n" +
                            "Type: ${downloadItem.type}\n" +
                            "Command:\n${SensitiveTextRedactor.redactCommand(commandString)} \n" +
                            "$requestDiagnostics\n")
                    val logString = StringBuilder(initialLogDetails)
                    val currentLogItem = LogItem(
                        0,
                        SensitiveTextRedactor.redactOutput(downloadItem.title.ifBlank { downloadItem.url }),
                        logString.toString(),
                        downloadItem.format,
                        downloadItem.type,
                        System.currentTimeMillis(),
                    )


                    if (logDownloads) {
                        currentLogItem.id = logRepo.insert(currentLogItem)
                        downloadItem.logID = currentLogItem.id
                    } else {
                        downloadItem.logID = null
                    }
                    dao.update(downloadItem)

                    val eventBus = EventBus.getDefault()
                    var lastNotificationUpdateAt = 0L
                    var lastNotificationProgress = -1
                    val downloadStartedAt = System.currentTimeMillis()

                    try {
                        val cacheRoot = File(FileUtil.getCachePath(context)).canonicalFile
                        val canonicalTempFileDir = File(cacheRoot, downloadItem.id.toString()).canonicalFile
                        if (canonicalTempFileDir.parentFile != cacheRoot || canonicalTempFileDir.name != downloadItem.id.toString()) {
                            throw IOException("Unsafe temporary download directory: ${canonicalTempFileDir.absolutePath}")
                        }
                        tempFileDir = canonicalTempFileDir
                        validatedTempFileDir = canonicalTempFileDir
                        if (tempFileDir.exists() && !tempFileDir.deleteRecursively()) {
                            throw IOException("Failed to clean temporary download directory: ${tempFileDir.absolutePath}")
                        }
                        if (!tempFileDir.mkdirs() && !tempFileDir.isDirectory) {
                            throw IOException("Failed to create temporary download directory: ${tempFileDir.absolutePath}")
                        }

                        fun executeYtdlpRequest(requestToRun: YoutubeDLRequest): YoutubeDLResponse {
                            currentIssueStage = DownloadIssueStage.EXTRACT
                            YoutubeDL.getInstance().destroyProcessById(downloadItem.id.toString())
                            YoutubeDLCompat.destroyProcessById(downloadItem.id.toString())
                            return YoutubeDLCompat.execute(applicationContext, requestToRun, downloadItem.id.toString(), true){ progress, _, line ->
                                currentIssueStage = DownloadIssueStageTracker.update(currentIssueStage, line)
                                val redactedLine = SensitiveTextRedactor.redactOutput(line)
                                if (downloadItem.type == DownloadType.video && downloadItem.videoPreferences.embedSubs) {
                                    val lowerLine = line.lowercase(Locale.US)
                                    if (
                                        lowerLine.contains("downloading subtitles") ||
                                        lowerLine.contains("writing video subtitles to:") ||
                                        lowerLine.contains("subtitle") ||
                                        lowerLine.contains("subtitlesconvertor")
                                    ) {
                                        Log.i(TAG, "HardSub sub log id=${downloadItem.id}: $redactedLine")
                                    }
                                }
                                eventBus.post(WorkerProgress(progress.toInt(), redactedLine, downloadItem.id, downloadItem.logID))
                                val title = SensitiveTextRedactor.redactOutput(
                                    downloadItem.title.ifEmpty { downloadItem.url }
                                )
                                val now = System.currentTimeMillis()
                                val intProgress = progress.toInt()
                                val progressAdvancedEnough = lastNotificationProgress < 0 || (intProgress - lastNotificationProgress) >= 2
                                if (now - lastNotificationUpdateAt >= 800L || progressAdvancedEnough || intProgress >= 100) {
                                    notificationUtil.updateDownloadNotification(
                                        downloadItem.id.toInt(),
                                        redactedLine, intProgress, 0, title,
                                        NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID,
                                        getHardSubStatusText(resources)
                                    )
                                    lastNotificationUpdateAt = now
                                    lastNotificationProgress = intProgress
                                }
                                runBlocking(Dispatchers.IO) {
                                    if (logDownloads) {
                                        logRepo.update(redactedLine, currentLogItem.id)
                                    }
                                    logString.append("$redactedLine\n")
                                    recentYtdlpOutput.addLast(redactedLine)
                                    while (recentYtdlpOutput.size > FAILURE_YTDLP_TAIL_LINES) {
                                        recentYtdlpOutput.removeFirst()
                                    }
                                }
                            }
                        }
                        fun resetTempDirectoryForRetry() {
                            if (tempFileDir.exists() && !tempFileDir.deleteRecursively()) {
                                throw IOException("Failed to clean temporary download directory before retry: ${tempFileDir.absolutePath}")
                            }
                            if (!tempFileDir.mkdirs() && !tempFileDir.isDirectory) {
                                throw IOException("Failed to recreate temporary download directory before retry: ${tempFileDir.absolutePath}")
                            }
                        }
                        val response = try {
                            executeYtdlpRequest(request)
                        } catch (firstError: Exception) {
                            val retryProbeText = buildYtdlpRetryProbeText(firstError, recentYtdlpOutput)
                            val retryWithoutCachedInfoJson = shouldRetryWithoutCachedInfoJson(retryProbeText, commandString)
                            val retryWithoutYoutubeAuthentication = shouldRetryYoutube403WithoutAuthentication(
                                retryProbeText = retryProbeText,
                                commandString = commandString,
                                item = downloadItem
                            )
                            if (!retryWithoutCachedInfoJson && !retryWithoutYoutubeAuthentication) {
                                throw firstError
                            }
                            val retryKeepingYoutubePoTokens =
                                retryWithoutYoutubeAuthentication &&
                                    commandHasYtdlpOption(commandString, "--cookies") &&
                                    commandString.contains("po_token=")

                            val retryNotice = when {
                                retryWithoutCachedInfoJson && retryWithoutYoutubeAuthentication -> {
                                    deleteLoadedAppInfoJson(commandString)
                                    if (retryKeepingYoutubePoTokens) {
                                        "Cached authenticated YouTube media request returned 403; retrying without cached metadata or cookies while keeping PO tokens"
                                    } else {
                                        "Cached authenticated YouTube media request returned 403; retrying without cached metadata or authentication"
                                    }
                                }
                                retryWithoutCachedInfoJson -> {
                                    deleteLoadedAppInfoJson(commandString)
                                    "Cached info JSON media URL returned 403; retrying without --load-info-json"
                                }
                                retryKeepingYoutubePoTokens -> {
                                    "Authenticated YouTube media request returned 403; retrying without cookies while keeping PO tokens"
                                }
                                else -> {
                                    "Authenticated YouTube media request returned 403; retrying once with public player clients"
                                }
                            }
                            Log.w(TAG, "$retryNotice id=${downloadItem.id}", firstError)
                            eventBus.post(WorkerProgress(0, retryNotice, downloadItem.id, downloadItem.logID))
                            notificationUtil.updateDownloadNotification(
                                downloadItem.id.toInt(),
                                retryNotice, 0, 0,
                                notificationTitle,
                                NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID,
                                getHardSubStatusText(resources)
                            )
                            resetTempDirectoryForRetry()

                            request = ytdlpUtil.buildYoutubeDLRequest(
                                downloadItem = downloadItem,
                                useCachedInfoJson = false,
                                includeYoutubeAuthentication = !retryWithoutYoutubeAuthentication,
                                includeYoutubeCookies = !retryWithoutYoutubeAuthentication,
                                includeYoutubePoTokens = retryKeepingYoutubePoTokens || !retryWithoutYoutubeAuthentication
                            )
                            requestsToCleanup.add(request)
                            val retryCommandString = ytdlpUtil.parseYTDLRequestString(request)
                            effectiveCommandString = retryCommandString
                            val retryRequestDiagnostics = ytdlpUtil.buildRequestDiagnostics(downloadItem, request, retryCommandString)
                            retryLogDetails = SensitiveTextRedactor.redactOutput("\nRetry:\n" +
                                "Reason: $retryNotice\n" +
                                "First error:\n${firstError.message.orEmpty().takeLast(4000)}\n" +
                                "Command:\n${SensitiveTextRedactor.redactCommand(retryCommandString)} \n" +
                                "$retryRequestDiagnostics\n")
                            if (logDownloads) {
                                logRepo.update(retryLogDetails, currentLogItem.id)
                            }
                            logString.append(retryLogDetails)

                            try {
                                executeYtdlpRequest(request)
                            } catch (retryError: Exception) {
                                if (!retryKeepingYoutubePoTokens) {
                                    throw retryError
                                }

                                val publicRetryNotice = "PO-token YouTube media retry failed; retrying once with public player clients"
                                Log.w(TAG, "$publicRetryNotice id=${downloadItem.id}", retryError)
                                eventBus.post(WorkerProgress(0, publicRetryNotice, downloadItem.id, downloadItem.logID))
                                notificationUtil.updateDownloadNotification(
                                    downloadItem.id.toInt(),
                                    publicRetryNotice, 0, 0,
                                    notificationTitle,
                                    NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID,
                                    getHardSubStatusText(resources)
                                )
                                resetTempDirectoryForRetry()

                                request = ytdlpUtil.buildYoutubeDLRequest(
                                    downloadItem = downloadItem,
                                    useCachedInfoJson = false,
                                    includeYoutubeAuthentication = false,
                                    includeYoutubeCookies = false,
                                    includeYoutubePoTokens = false
                                )
                                requestsToCleanup.add(request)
                                val publicRetryCommandString = ytdlpUtil.parseYTDLRequestString(request)
                                effectiveCommandString = publicRetryCommandString
                                val publicRetryRequestDiagnostics = ytdlpUtil.buildRequestDiagnostics(downloadItem, request, publicRetryCommandString)
                                val publicRetryLogDetails = SensitiveTextRedactor.redactOutput("\nRetry:\n" +
                                    "Reason: $publicRetryNotice\n" +
                                    "Previous retry error:\n${retryError.message.orEmpty().takeLast(4000)}\n" +
                                    "Command:\n${SensitiveTextRedactor.redactCommand(publicRetryCommandString)} \n" +
                                    "$publicRetryRequestDiagnostics\n")
                                if (logDownloads) {
                                    logRepo.update(publicRetryLogDetails, currentLogItem.id)
                                }
                                logString.append(publicRetryLogDetails)

                                executeYtdlpRequest(request)
                            }
                        }

                        resultRepo.updateDownloadItem(downloadItem)?.apply {
                            val status = dao.checkStatus(this.id)
                            if (status == DownloadRepository.Status.Active){
                                dao.updateWithoutUpsert(this)
                            }
                        }
                        //val wasQuickDownloaded = resultDao.getCountInt() == 0
                        var finalPaths = mutableListOf<String>()
                        var hardSubBurned = false

                        val hardSubSkipReason = if (shouldBurnHardSub) resolveHardSubSkipReason(response.out) else null
                        if (hardSubSkipReason != null) {
                            shouldBurnHardSub = false
                            Log.w(TAG, "HardSub skipped id=${downloadItem.id} reason=$hardSubSkipReason")
                            eventBus.post(WorkerProgress(100, hardSubSkipReason, downloadItem.id, downloadItem.logID))
                        }
                        if (shouldBurnHardSub && sharedPreferences.getBoolean("parallel_hardsub_postprocessing", false)) {
                            downloadItem.status = DownloadRepository.Status.PostProcessing.toString()
                            dao.update(downloadItem)
                            runningYTDLInstances.remove(downloadItem.id)
                            val postProcessingMessage = "Post-processing hard subtitles"
                            eventBus.post(WorkerProgress(100, postProcessingMessage, downloadItem.id, downloadItem.logID))
                            notificationUtil.updateDownloadNotification(
                                downloadItem.id.toInt(),
                                postProcessingMessage,
                                100,
                                0,
                                notificationTitle,
                                NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID,
                                getHardSubStatusText(resources)
                            )
                            Log.i(TAG, "HardSub post-processing slot released id=${downloadItem.id}")
                            DownloadRepository(dao).startDownloadWorker(emptyList(), context)
                            hardSubPostProcessMutex.lock()
                            hardSubPostProcessLockHeld = true
                        }
                        if (shouldStopForUserRequest()) return@launch

                        var deferBurnUntilPostMove = false
                        val forceDeferBurn = shouldBurnHardSub && shouldForceHardSubFailpoint("force_hardsub_defer")
                        val forceMoveUnresolved = shouldBurnHardSub && shouldForceHardSubFailpoint("force_hardsub_move_unresolved")
                        if (shouldBurnHardSub) {
                            Log.i(
                                TAG,
                                "HardSub session id=${downloadItem.id} noCache=$noCache keepCache=$keepCache downloadLocation=${FileUtil.formatPath(downloadLocation)} tempDir=${tempFileDir.absolutePath} forceDeferBurn=$forceDeferBurn forceMoveUnresolved=$forceMoveUnresolved"
                            )
                        }
                        if (!noCache && shouldBurnHardSub) {
                            var preMoveBurnPaths = extractPathsFromYtdlpOutput(response.out).toMutableList()
                            logPathCandidates("HardSub pre-move parsed", downloadItem.id, preMoveBurnPaths)
                            if (preMoveBurnPaths.isEmpty()) {
                                preMoveBurnPaths = recoverPathsFromDirectory(tempFileDir.absolutePath, downloadStartedAt).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub pre-move temp fallback used id=${downloadItem.id} recovered=${preMoveBurnPaths.size} dir=${tempFileDir.absolutePath}"
                                )
                            }
                            val preMoveHasMedia = preMoveBurnPaths.any { path ->
                                val ext = File(path).extension.lowercase(Locale.US)
                                ext !in setOf("ass", "srv3", "json3", "ttml", "vtt", "srt")
                            }
                            if (!preMoveHasMedia) {
                                val recoveredMedia = resolvePreviousHistoryMediaPaths(downloadItem, historyDao)
                                if (recoveredMedia.isNotEmpty()) {
                                    preMoveBurnPaths.addAll(recoveredMedia)
                                    Log.w(
                                        TAG,
                                        "HardSub pre-move history-media fallback used id=${downloadItem.id} recovered=${recoveredMedia.size}"
                                    )
                                }
                            }
                            Log.i(TAG, "HardSub pre-move remap input=${preMoveBurnPaths.size}")
                            preMoveBurnPaths = remapPathsForBurnIn(
                                preMoveBurnPaths,
                                tempFileDir.absolutePath,
                                tempFileDir.absolutePath
                            ).toMutableList()
                            Log.i(TAG, "HardSub pre-move remap output=${preMoveBurnPaths.size}")
                            val beforeTempOnlyFilter = preMoveBurnPaths.size
                            preMoveBurnPaths = preMoveBurnPaths
                                .filter { candidate ->
                                    isPathInsideDirectory(candidate, tempFileDir)
                                }
                                .toMutableList()
                            if (beforeTempOnlyFilter != preMoveBurnPaths.size) {
                                Log.w(
                                    TAG,
                                    "HardSub pre-move filtered non-temp paths removed=${beforeTempOnlyFilter - preMoveBurnPaths.size}"
                                )
                            }
                            if (preMoveBurnPaths.isEmpty()) {
                                throw IOException("HardSub aborted: no temporary media files found for burn-in")
                            }
                            var hasMediaForPreMove = preMoveBurnPaths.any { path ->
                                val ext = File(path).extension.lowercase(Locale.US)
                                ext !in setOf("ass", "srv3", "json3", "ttml", "vtt", "srt")
                            }
                            if (!hasMediaForPreMove) {
                                val recoveredFromTemp = recoverPathsFromDirectory(tempFileDir.absolutePath, downloadStartedAt)
                                if (recoveredFromTemp.isNotEmpty()) {
                                    preMoveBurnPaths.addAll(recoveredFromTemp)
                                    preMoveBurnPaths = remapPathsForBurnIn(
                                        preMoveBurnPaths,
                                        tempFileDir.absolutePath,
                                        tempFileDir.absolutePath
                                    ).filter { candidate ->
                                        isPathInsideDirectory(candidate, tempFileDir)
                                    }.distinct().toMutableList()
                                    hasMediaForPreMove = preMoveBurnPaths.any { path ->
                                        val ext = File(path).extension.lowercase(Locale.US)
                                        ext !in setOf("ass", "srv3", "json3", "ttml", "vtt", "srt")
                                    }
                                    Log.w(
                                        TAG,
                                        "HardSub pre-move temp rescan used id=${downloadItem.id} recovered=${recoveredFromTemp.size} mediaPresent=$hasMediaForPreMove"
                                    )
                                }
                                if (!hasMediaForPreMove) {
                                    val recoveredAllFromTemp = recoverAllPathsFromDirectory(tempFileDir.absolutePath)
                                    if (recoveredAllFromTemp.isNotEmpty()) {
                                        preMoveBurnPaths.addAll(recoveredAllFromTemp)
                                        preMoveBurnPaths = remapPathsForBurnIn(
                                            preMoveBurnPaths,
                                            tempFileDir.absolutePath,
                                            tempFileDir.absolutePath
                                        ).filter { candidate ->
                                            isPathInsideDirectory(candidate, tempFileDir)
                                        }.distinct().toMutableList()
                                        hasMediaForPreMove = preMoveBurnPaths.any { path ->
                                            val ext = File(path).extension.lowercase(Locale.US)
                                            ext !in setOf("ass", "srv3", "json3", "ttml", "vtt", "srt")
                                        }
                                        Log.w(
                                            TAG,
                                            "HardSub pre-move temp fullscan used id=${downloadItem.id} recovered=${recoveredAllFromTemp.size} mediaPresent=$hasMediaForPreMove"
                                        )
                                    }
                                }
                            }
                            if (hasMediaForPreMove) {
                                if (forceDeferBurn) {
                                    deferBurnUntilPostMove = true
                                    Log.w(TAG, "HardSub pre-move forced defer id=${downloadItem.id} marker=force_hardsub_defer")
                                } else {
                                    if (shouldStopForUserRequest()) return@launch
                                    Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${preMoveBurnPaths.size} mode=pre-move")
                                    eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                                    currentIssueStage = DownloadIssueStage.HARD_SUB
                                    val burned = burnSubtitlesInPlace(preMoveBurnPaths, noKeepSubs, downloadItem.id, downloadItem.logID, downloadItem.videoPreferences.subsLanguages)
                                    hardSubBurned = hardSubBurned || burned
                                    if (burned) {
                                        eventBus.post(WorkerProgress(100, "Subtitle burn-in completed", downloadItem.id, downloadItem.logID))
                                        Log.i(TAG, "HardSub completed id=${downloadItem.id} mode=pre-move")
                                    } else {
                                        Log.w(TAG, "HardSub pre-move produced no burned media id=${downloadItem.id}")
                                    }
                                }
                            } else {
                                deferBurnUntilPostMove = true
                                Log.w(
                                    TAG,
                                    "HardSub pre-move deferred id=${downloadItem.id} reason=no-media-in-temp tempSnapshot=${describeDirectorySnapshot(tempFileDir)}"
                                )
                            }
                        }

                        if (shouldBurnHardSub && !noCache && deferBurnUntilPostMove) {
                            var latePreMoveBurnPaths = recoverAllPathsFromDirectory(tempFileDir.absolutePath).toMutableList()
                            if (latePreMoveBurnPaths.isNotEmpty()) {
                                latePreMoveBurnPaths = remapPathsForBurnIn(
                                    latePreMoveBurnPaths,
                                    tempFileDir.absolutePath,
                                    tempFileDir.absolutePath
                                ).filter { candidate ->
                                    isPathInsideDirectory(candidate, tempFileDir)
                                }.distinct().toMutableList()
                            }
                            val lateHasMedia = latePreMoveBurnPaths.any { path ->
                                val ext = File(path).extension.lowercase(Locale.US)
                                ext !in setOf("ass", "srv3", "json3", "ttml", "vtt", "srt")
                            }
                            if (lateHasMedia) {
                                if (shouldStopForUserRequest()) return@launch
                                Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${latePreMoveBurnPaths.size} mode=pre-move-late")
                                eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                                currentIssueStage = DownloadIssueStage.HARD_SUB
                                val burned = burnSubtitlesInPlace(latePreMoveBurnPaths, noKeepSubs, downloadItem.id, downloadItem.logID, downloadItem.videoPreferences.subsLanguages)
                                hardSubBurned = hardSubBurned || burned
                                if (burned) {
                                    deferBurnUntilPostMove = false
                                    eventBus.post(WorkerProgress(100, "Subtitle burn-in completed", downloadItem.id, downloadItem.logID))
                                    Log.i(TAG, "HardSub completed id=${downloadItem.id} mode=pre-move-late")
                                } else {
                                    Log.w(TAG, "HardSub pre-move-late produced no burned media id=${downloadItem.id}")
                                }
                            } else {
                                Log.w(TAG, "HardSub pre-move-late skipped id=${downloadItem.id} reason=no-media-in-temp")
                            }
                        }

                            if (noCache){
                            eventBus.post(WorkerProgress(100, "Scanning Files", downloadItem.id, downloadItem.logID))
                            finalPaths = extractPathsFromYtdlpOutput(response.out).toMutableList()
                            logPathCandidates("HardSub no-cache parsed", downloadItem.id, finalPaths)

                            if (finalPaths.isEmpty()) {
                                finalPaths = recoverPathsFromDirectory(downloadLocation, downloadStartedAt).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub path recovery used id=${downloadItem.id} recovered=${finalPaths.size} dir=$downloadLocation"
                                )
                            }

                            finalPaths.sortBy { File(it).lastModified() }
                            finalPaths = finalPaths.distinct().toMutableList()
                            Log.i(
                                TAG,
                                "HardSub no-cache output paths id=${downloadItem.id} count=${finalPaths.size} sample=${
                                    finalPaths.joinToString(limit = 3)
                                }"
                            )
                            FileUtil.scanMedia(finalPaths, context)
                        }else{
                            //move file from internal to set download directory
                            currentIssueStage = DownloadIssueStage.MOVE
                            eventBus.post(WorkerProgress(100, "Moving file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                            Log.i(
                                TAG,
                                "HardSub move start id=${downloadItem.id} from=${tempFileDir.absolutePath} to=${FileUtil.formatPath(downloadLocation)} tempSnapshot=${describeDirectorySnapshot(tempFileDir)}"
                            )
                            val expectedMovedNames = runCatching {
                                tempFileDir.walkTopDown()
                                    .filter { it.isFile && it.length() > 0L }
                                    .map { it.name }
                                    .toSet()
                            }.getOrDefault(emptySet())
                            if (expectedMovedNames.isNotEmpty()) {
                                Log.i(
                                    TAG,
                                    "HardSub move expected names id=${downloadItem.id} count=${expectedMovedNames.size} sample=${expectedMovedNames.joinToString(limit = 5)}"
                                )
                            }
                            try {
                                finalPaths = withContext(Dispatchers.IO){
                                    FileUtil.moveFile(tempFileDir.absoluteFile,context, downloadLocation, keepCache){ p ->
                                        eventBus.post(WorkerProgress(p, "Moving file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                                    }
                                }.filter { !it.matches("\\.(description)|(txt)\$".toRegex()) }.toMutableList()
                                if (forceMoveUnresolved) {
                                    Log.w(TAG, "HardSub move forcing unresolved outputs id=${downloadItem.id} marker=force_hardsub_move_unresolved")
                                    finalPaths.clear()
                                }
                                logPathCandidates("HardSub move returned", downloadItem.id, finalPaths)

                                if (finalPaths.isNotEmpty()){
                                    eventBus.post(WorkerProgress(100, "Moved file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                                    Log.i(
                                        TAG,
                                        "HardSub move done id=${downloadItem.id} count=${finalPaths.size} sample=${
                                            finalPaths.joinToString(limit = 3)
                                        } destSnapshot=${describeDirectorySnapshot(File(downloadLocation))}"
                                    )
                                } else {
                                    if (expectedMovedNames.isNotEmpty()) {
                                        val recoveredByName = recoverPathsByFileNames(downloadLocation, expectedMovedNames.toList())
                                            .filter { File(it).exists() && File(it).isFile }
                                            .toMutableList()
                                        if (recoveredByName.isNotEmpty()) {
                                            finalPaths = recoveredByName
                                            Log.w(
                                                TAG,
                                                "HardSub move name-hint fallback used id=${downloadItem.id} recovered=${finalPaths.size} dir=$downloadLocation"
                                            )
                                        }
                                    }
                                    val recoveredAfterMove = recoverPathsFromDirectory(downloadLocation, downloadStartedAt)
                                        .filter { File(it).exists() && File(it).isFile }
                                        .toMutableList()
                                    if (finalPaths.isEmpty() && recoveredAfterMove.isNotEmpty()) {
                                        finalPaths = recoveredAfterMove
                                        Log.w(
                                            TAG,
                                            "HardSub move output fallback used id=${downloadItem.id} recovered=${finalPaths.size} dir=$downloadLocation"
                                        )
                                    }
                                    Log.w(
                                        TAG,
                                        "HardSub move done id=${downloadItem.id} but no output paths detected destSnapshot=${describeDirectorySnapshot(File(downloadLocation))} tempSnapshot=${describeDirectorySnapshot(tempFileDir)}"
                                    )
                                    if (shouldBurnHardSub && !noCache && finalPaths.isEmpty()) {
                                        throw IOException("HardSub move completed but output paths are unresolved after burn-in")
                                    }
                                }
                            }catch (e: Exception){
                                e.printStackTrace()
                                Log.e(TAG, "HardSub move failed id=${downloadItem.id}", e)
                                val recoveredAfterFailure = buildList {
                                    if (expectedMovedNames.isNotEmpty()) {
                                        addAll(recoverPathsByFileNames(downloadLocation, expectedMovedNames.toList()))
                                    }
                                    addAll(recoverPathsFromDirectory(downloadLocation, downloadStartedAt))
                                }
                                    .distinct()
                                    .filter { File(it).exists() && File(it).isFile }
                                    .toMutableList()

                                if (recoveredAfterFailure.isNotEmpty()) {
                                    finalPaths = recoveredAfterFailure
                                    Log.w(
                                        TAG,
                                        "HardSub move failure recovery used id=${downloadItem.id} recovered=${finalPaths.size}"
                                    )
                                } else {
                                    val recoveredTempPaths = recoverPathsFromDirectory(tempFileDir.absolutePath, downloadStartedAt)
                                        .filter { File(it).exists() && File(it).isFile }
                                    if (recoveredTempPaths.isNotEmpty()) {
                                        Log.w(
                                            TAG,
                                            "HardSub move failure left temp outputs id=${downloadItem.id} recovered=${recoveredTempPaths.size}; retrying move"
                                        )
                                        finalPaths = retryMoveFromTempDirectory(
                                            tempFileDir = tempFileDir,
                                            downloadLocation = downloadLocation,
                                            keepCache = keepCache,
                                            downloadItemId = downloadItem.id,
                                            downloadLogId = downloadItem.logID,
                                            eventBus = eventBus
                                        ).toMutableList()
                                    }
                                }

                                if (shouldBurnHardSub && finalPaths.isEmpty()) {
                                    throw IOException(
                                        "HardSub move failed: output files are missing after burn-in",
                                        e
                                    )
                                }
                                Log.w(
                                    TAG,
                                    "HardSub move failure state id=${downloadItem.id} destSnapshot=${describeDirectorySnapshot(File(downloadLocation))} tempSnapshot=${describeDirectorySnapshot(tempFileDir)}"
                                )
                                if (e.message?.isNotBlank() == true) {
                                    handler.postDelayed({
                                        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                    }, 1000)
                                }

                            }
                        }

                        recordCreatedOutputs(finalPaths)

                        if (shouldBurnHardSub && !noCache && deferBurnUntilPostMove) {
                            var postMoveBurnPaths = finalPaths.toMutableList()
                            if (postMoveBurnPaths.isEmpty()) {
                                val fromOutput = extractPathsFromYtdlpOutput(response.out)
                                if (fromOutput.isNotEmpty()) {
                                    postMoveBurnPaths = remapPathsForBurnIn(
                                        fromOutput,
                                        downloadLocation,
                                        tempFileDir.absolutePath
                                    ).toMutableList()
                                    if (postMoveBurnPaths.isNotEmpty()) {
                                        Log.w(
                                            TAG,
                                            "HardSub post-move output-remap used id=${downloadItem.id} recovered=${postMoveBurnPaths.size}"
                                        )
                                    }
                                }
                            }
                            if (postMoveBurnPaths.isEmpty()) {
                                postMoveBurnPaths = recoverPathsFromDirectory(downloadLocation, downloadStartedAt).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub post-move fallback used id=${downloadItem.id} recovered=${postMoveBurnPaths.size} dir=$downloadLocation"
                                )
                            }
                            postMoveBurnPaths = remapPathsForBurnIn(
                                postMoveBurnPaths,
                                downloadLocation,
                                tempFileDir.absolutePath
                            ).toMutableList()
                            if (postMoveBurnPaths.isEmpty()) {
                                val directRecovered = recoverPathsFromDirectory(downloadLocation, downloadStartedAt)
                                    .filter { File(it).exists() && File(it).isFile }
                                    .toMutableList()
                                if (directRecovered.isNotEmpty()) {
                                    postMoveBurnPaths = directRecovered
                                    Log.w(
                                        TAG,
                                        "HardSub post-move direct recovery used id=${downloadItem.id} recovered=${postMoveBurnPaths.size} dir=$downloadLocation"
                                    )
                                }
                            }
                            if (postMoveBurnPaths.isEmpty()) {
                                val recoveredHistoryMedia = resolvePreviousHistoryMediaPaths(downloadItem, historyDao)
                                    .filter { path ->
                                        val file = File(path)
                                        file.exists() && file.isFile
                                    }
                                if (recoveredHistoryMedia.isNotEmpty()) {
                                    postMoveBurnPaths = recoveredHistoryMedia.toMutableList()
                                    Log.w(
                                        TAG,
                                        "HardSub post-move history-media fallback used id=${downloadItem.id} recovered=${postMoveBurnPaths.size}"
                                    )
                                }
                            }
                            if (postMoveBurnPaths.isEmpty()) {
                                val nameHints = extractPathsFromYtdlpOutput(response.out)
                                    .map { File(it).name }
                                    .filter { it.isNotBlank() }
                                if (nameHints.isNotEmpty()) {
                                    val nameRecovered = recoverPathsByFileNames(downloadLocation, nameHints)
                                    if (nameRecovered.isNotEmpty()) {
                                        postMoveBurnPaths = nameRecovered.toMutableList()
                                        Log.w(
                                            TAG,
                                            "HardSub post-move name-hint recovery used id=${downloadItem.id} recovered=${postMoveBurnPaths.size}"
                                        )
                                    }
                                }
                            }
                            if (postMoveBurnPaths.isEmpty()) {
                                if (isHardSubRedownload(downloadItem)) {
                                    throw IOException("HardSub aborted: no files found after move for deferred burn-in")
                                }
                                Log.w(
                                    TAG,
                                    "HardSub post-move skipped id=${downloadItem.id} reason=no-files-found-after-move destSnapshot=${describeDirectorySnapshot(File(downloadLocation))}"
                                )
                            } else {
                                if (shouldStopForUserRequest()) return@launch
                                Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${postMoveBurnPaths.size} mode=post-move")
                                eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                                currentIssueStage = DownloadIssueStage.HARD_SUB
                                val burned = burnSubtitlesInPlace(postMoveBurnPaths, noKeepSubs, downloadItem.id, downloadItem.logID, downloadItem.videoPreferences.subsLanguages)
                                hardSubBurned = hardSubBurned || burned
                                if (!burned && isHardSubRedownload(downloadItem)) {
                                    throw IOException("HardSub aborted: no media was burned in post-move stage")
                                }
                                if (burned) {
                                    eventBus.post(WorkerProgress(100, "Subtitle burn-in completed", downloadItem.id, downloadItem.logID))
                                    Log.i(TAG, "HardSub completed id=${downloadItem.id} mode=post-move")
                                } else {
                                    Log.w(TAG, "HardSub post-move produced no burned media id=${downloadItem.id}")
                                }
                            }
                        }


                        if (shouldBurnHardSub && noCache) {
                            if (finalPaths.isEmpty()) {
                                finalPaths = extractPathsFromYtdlpOutput(response.out).toMutableList()
                                if (finalPaths.isNotEmpty()) {
                                    Log.w(
                                        TAG,
                                        "HardSub pre-burn output-parse fallback used id=${downloadItem.id} recovered=${finalPaths.size}"
                                    )
                                }
                            }
                            if (finalPaths.isEmpty()) {
                                finalPaths = recoverPathsFromDirectory(downloadLocation, downloadStartedAt).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub pre-burn fallback used id=${downloadItem.id} recovered=${finalPaths.size} dir=$downloadLocation"
                                )
                            }
                            if (finalPaths.isEmpty()) {
                                finalPaths = recoverPathsFromDirectory(tempFileDir.absolutePath, downloadStartedAt).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub pre-burn temp fallback used id=${downloadItem.id} recovered=${finalPaths.size} dir=${tempFileDir.absolutePath}"
                                )
                            }
                            if (finalPaths.isEmpty()) {
                                throw IOException("HardSub aborted: no output files detected for burn-in")
                            }
                            Log.i(TAG, "HardSub pre-remap paths=${finalPaths.size}")
                            finalPaths = remapPathsForBurnIn(finalPaths, downloadLocation, tempFileDir.absolutePath).toMutableList()
                            if (finalPaths.isEmpty()) {
                                finalPaths = recoverPathsFromDirectory(tempFileDir.absolutePath, downloadStartedAt).toMutableList()
                                Log.w(
                                    TAG,
                                    "HardSub remap-empty temp fallback used id=${downloadItem.id} recovered=${finalPaths.size} dir=${tempFileDir.absolutePath}"
                                )
                            }
                            Log.i(TAG, "HardSub post-remap paths=${finalPaths.size}")
                            if (shouldStopForUserRequest()) return@launch
                            Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${finalPaths.size}")
                            eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                            currentIssueStage = DownloadIssueStage.HARD_SUB
                            val burned = burnSubtitlesInPlace(finalPaths, noKeepSubs, downloadItem.id, downloadItem.logID, downloadItem.videoPreferences.subsLanguages)
                            hardSubBurned = hardSubBurned || burned
                            if (!burned && isHardSubRedownload(downloadItem)) {
                                throw IOException("HardSub aborted: no media was burned")
                            }
                            if (burned) {
                                eventBus.post(WorkerProgress(100, "Subtitle burn-in completed", downloadItem.id, downloadItem.logID))
                                Log.i(TAG, "HardSub completed id=${downloadItem.id}")
                            } else {
                                Log.w(TAG, "HardSub produced no burned media id=${downloadItem.id}")
                            }
                        }

                        if (
                            downloadItem.type == DownloadType.video &&
                            !downloadItem.videoPreferences.embedSubs &&
                            (
                                commandHasYtdlpOption(effectiveCommandString, "--write-subs") ||
                                    commandHasYtdlpOption(effectiveCommandString, "--write-auto-subs")
                            )
                        ) {
                            validateSavedSubtitleSidecars(downloadItem, finalPaths, downloadLocation, downloadStartedAt)
                        }

                        val nonMediaExtensions = mutableSetOf<String>().apply {
                            addAll(context.getStringArray(R.array.thumbnail_containers_values).map { it.lowercase(Locale.US) })
                            addAll(context.getStringArray(R.array.sub_formats_values).filter { it.isNotBlank() }.map { it.lowercase(Locale.US) })
                            // Hard-sub flow can download rich subtitle sidecars not present in sub_formats_values.
                            addAll(listOf("srv3", "json3", "json", "ttml"))
                            add("description")
                            add("txt")
                        }
                        finalPaths = finalPaths.filter { path ->
                            val file = File(path)
                            file.exists() &&
                                file.isFile &&
                                file.extension.lowercase(Locale.US) !in nonMediaExtensions
                        }.toMutableList()
                        if (!noCache) {
                            val beforeTempFilter = finalPaths.size
                            finalPaths = finalPaths.filterNot { path ->
                                isPathInsideDirectory(path, tempFileDir)
                            }.toMutableList()
                            if (beforeTempFilter != finalPaths.size) {
                                Log.w(
                                    TAG,
                                    "HardSub filtered temp output paths id=${downloadItem.id} removed=${beforeTempFilter - finalPaths.size}"
                                )
                            }
                            if (finalPaths.isEmpty()) {
                                val strandedTempMedia = recoverPathsFromDirectory(tempFileDir.absolutePath, downloadStartedAt)
                                    .filter { candidate ->
                                        val file = File(candidate)
                                        file.exists() &&
                                            file.isFile &&
                                            file.extension.lowercase(Locale.US) !in nonMediaExtensions
                                    }
                                if (strandedTempMedia.isNotEmpty()) {
                                    val moveFailureDetails = FileUtil.consumeLastMoveFailureDetails()
                                    throw IOException(
                                        "Downloaded files remain in temporary storage after move failure" +
                                            (moveFailureDetails?.let { ": $it" } ?: "")
                                    )
                                }
                            }
                        }
                        finalPaths = prioritizePrimaryMediaPath(finalPaths, downloadItem.type)
                        if (shouldStopForUserRequest()) return@launch
                        if (finalPaths.isNotEmpty()) {
                            val summary = finalPaths.joinToString(limit = 5) { path ->
                                val file = File(path)
                                "${file.name}(size=${file.length()},mtime=${file.lastModified()})"
                            }
                            Log.i(TAG, "HardSub final paths id=${downloadItem.id} count=${finalPaths.size} sample=$summary")
                        }
                        requestsToCleanup.forEach { requestToCleanup ->
                            runCatching { FileUtil.deleteConfigFiles(requestToCleanup) }
                                .onFailure { cleanupError ->
                                    Log.w(TAG, "Config cleanup failed id=${downloadItem.id}", cleanupError)
                                }
                        }
                        recordCreatedOutputs(finalPaths)
                        val completionIssues = mutableListOf<DownloadIssue>()

                        //put download in history
                        currentIssueStage = DownloadIssueStage.HISTORY
                        try {
                            if (!downloadItem.incognito) {
                                if (request.hasOption("--download-archive") && finalPaths.isEmpty()) {
                                    handler.postDelayed({
                                        Toast.makeText(context, resources.getString(R.string.download_already_exists), Toast.LENGTH_LONG).show()
                                    }, 100)
                                } else if (finalPaths.isNotEmpty()) {
                                    val unixTime = System.currentTimeMillis() / 1000
                                    finalPaths.first().apply {
                                        val file = File(this)
                                        var duration = downloadItem.duration
                                        val d = file.getMediaDuration(context)
                                        if (d > 0) duration = d.toStringDuration(Locale.US)

                                        downloadItem.format.filesize = file.length()
                                        downloadItem.format.container = file.extension
                                        downloadItem.duration = duration
                                    }

                                    val replacedHistoryId = downloadItem.playlistURL
                                        ?.takeIf { it.startsWith(HISTORY_REDOWNLOAD_MARKER) }
                                        ?.removePrefix(HISTORY_REDOWNLOAD_MARKER)
                                        ?.toLongOrNull() ?: 0L
                                    val isHistoryRedownload = replacedHistoryId > 0L

                                    val previousHistoryItem = if (replacedHistoryId > 0L) {
                                        runCatching { historyDao.getItem(replacedHistoryId) }.getOrNull()
                                    } else null
                                    val completedHardSub = hardSubBurned
                                    val restoredPlaybackPositionMs = if (completedHardSub) 0 else (previousHistoryItem?.playbackPositionMs ?: 0)
                                    val observeKeyword = if (downloadItem.observeSourceId > 0L) {
                                        runCatching { observeSourcesDao.getByID(downloadItem.observeSourceId).autoAddKeyword.trim() }.getOrDefault("")
                                    } else {
                                        ""
                                    }
                                    val existingDuplicateHistoryItem = if (!isHistoryRedownload) {
                                        findExistingHistoryForDownloadedItem(downloadItem, historyDao)
                                    } else {
                                        null
                                    }

                                    val preferredThumbPath = pickLocalThumbnailPath(finalPaths) ?: downloadItem.thumb
                                    val historyItem = HistoryItem(
                                        id = replacedHistoryId,
                                        url = downloadItem.url,
                                        title = downloadItem.title,
                                        author = downloadItem.author,
                                        artist = previousHistoryItem?.artist ?: "",
                                        duration = downloadItem.duration,
                                        durationSeconds = downloadItem.duration.toDurationSeconds(),
                                        thumb = preferredThumbPath,
                                        type = downloadItem.type,
                                        time = unixTime,
                                        lastWatched = previousHistoryItem?.lastWatched ?: 0,
                                        downloadPath = finalPaths,
                                        website = downloadItem.website,
                                        format = downloadItem.format,
                                        filesize = downloadItem.format.filesize,
                                        downloadId = downloadItem.id,
                                        command = commandString,
                                        playbackPositionMs = restoredPlaybackPositionMs,
                                        localTreeUri = if (isHistoryRedownload) "" else (previousHistoryItem?.localTreeUri ?: ""),
                                        localTreePath = if (isHistoryRedownload) "" else (previousHistoryItem?.localTreePath ?: ""),
                                        keywords = mergeKeywords(previousHistoryItem?.keywords.orEmpty(), observeKeyword),
                                        customThumb = previousHistoryItem?.customThumb ?: "",
                                        hardSubScanRemoved = if (completedHardSub) true else previousHistoryItem?.hardSubScanRemoved ?: false,
                                        hardSubDone = if (completedHardSub) true else previousHistoryItem?.hardSubDone ?: false
                                    )
                                    val insertedHistoryId = if (replacedHistoryId > 0L) {
                                        historyDao.insert(historyItem)
                                        replacedHistoryId
                                    } else {
                                        historyDao.insertAndGetId(historyItem)
                                    }
                                    if (replacedHistoryId > 0L) {
                                        deleteReplacedHistoryMedia(previousHistoryItem, finalPaths)
                                    } else if (existingDuplicateHistoryItem != null) {
                                        PendingDuplicateDownloadStore.add(
                                            sharedPreferences,
                                            newHistoryId = insertedHistoryId,
                                            existingHistoryId = existingDuplicateHistoryItem.id
                                        )
                                        Log.i(
                                            TAG,
                                            "Duplicate download needs user choice newHistoryId=$insertedHistoryId existingHistoryId=${existingDuplicateHistoryItem.id} url=${downloadItem.url}"
                                        )
                                    }
                                }
                            }
                        } catch (historyError: Exception) {
                            preserveQueueRecord = true
                            downloadItem.status = DownloadRepository.Status.Error.toString()
                            downloadItem.lastIssueCode = DownloadIssueCode.HISTORY_WRITE_FAILED.name
                            downloadItem.lastIssueStage = DownloadIssueStage.HISTORY.name
                            runCatching { dao.update(downloadItem) }
                                .onFailure { queueError ->
                                    Log.e(
                                        TAG,
                                        "Failed to mark history recovery record id=${downloadItem.id}",
                                        queueError
                                    )
                                }
                            val historyIssue = DownloadIssue.create(
                                stage = DownloadIssueStage.HISTORY,
                                code = DownloadIssueCode.HISTORY_WRITE_FAILED,
                                severity = DownloadIssueSeverity.WARNING,
                                suggestedActions = setOf(
                                    DownloadSuggestedAction.VIEW_LOG,
                                    DownloadSuggestedAction.COPY_SUMMARY
                                ),
                                details = historyError.message.orEmpty(),
                                source = DownloadIssueSource.TYPED_EXCEPTION
                            )
                            completionIssues += historyIssue
                            Log.e(TAG, "History update failed after file creation id=${downloadItem.id}", historyError)
                        }

                        if (isHardSubRedownload(downloadItem)) {
                            markHardSubProcessed(downloadItem.id)
                            updateHardSubWorkerNotification(notificationUtil)
                        }
                        currentIssueStage = DownloadIssueStage.NOTIFICATION
                        val warningBeforeNotification = completionIssues.firstOrNull()?.let { issue ->
                            DownloadIssueText.formatted(resources, issue)
                        }
                        try {
                            withContext(Dispatchers.Main) {
                                notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                                notificationUtil.createDownloadFinished(
                                    downloadItem.id,
                                    notificationTitle,
                                    downloadItem.type,
                                    if (finalPaths.isEmpty()) null else finalPaths,
                                    resources,
                                    warningBeforeNotification
                                )
                            }
                        } catch (notificationError: Exception) {
                            completionIssues += DownloadIssue.create(
                                stage = DownloadIssueStage.NOTIFICATION,
                                code = DownloadIssueCode.NOTIFICATION_FAILED,
                                severity = DownloadIssueSeverity.WARNING,
                                suggestedActions = setOf(DownloadSuggestedAction.VIEW_LOG),
                                details = notificationError.message.orEmpty(),
                                source = DownloadIssueSource.TYPED_EXCEPTION
                            )
                            Log.e(TAG, "Finished notification failed after file creation id=${downloadItem.id}", notificationError)
                        }
                        val completedOutcome = DownloadOutcome.completed(
                            createdFileCount = createdOutputPaths.size,
                            issues = completionIssues
                        )
                        downloadOutcome = completedOutcome
                        val outcomeSummary = completedOutcome.issues
                            .joinToString(separator = "\n") { issue ->
                                DownloadIssueText.formatted(resources, issue)
                            }
                            .takeIf { it.isNotBlank() }
                        outcomeSummary?.let { summary ->
                            eventBus.post(
                                WorkerProgress(100, summary, downloadItem.id, downloadItem.logID)
                            )
                        }

//                        if (wasQuickDownloaded && createResultItem){
//                            runCatching {
//                                eventBus.post(WorkerProgress(100, "Creating Result Items", downloadItem.id))
//                                runBlocking {
//                                    infoUtil.getFromYTDL(downloadItem.url).forEach { res ->
//                                        if (res != null) {
//                                            resultDao.insert(res)
//                                        }
//                                    }
//                                }
//                            }
//                        }

                        if (!preserveQueueRecord) {
                            dao.delete(downloadItem.id)
                        }

                        if (logDownloads){
                            val structuredOutcomeLog = outcomeSummary?.let {
                                "\nStructured outcome: ${completedOutcome.status}\n$it\n"
                            }.orEmpty()
                            logRepo.update(
                                initialLogDetails + retryLogDetails +
                                    SensitiveTextRedactor.redactOutput(response.out) + structuredOutcomeLog,
                                currentLogItem.id,
                                true
                            )
                        }

                    } catch (it: Exception) {
                        if (downloadItem.type == DownloadType.video && downloadItem.videoPreferences.embedSubs) {
                            Log.e(TAG, "HardSub failed id=${downloadItem.id} type=${it.javaClass.simpleName}")
                        }
                        requestsToCleanup.forEach { requestToCleanup ->
                            runCatching { FileUtil.deleteConfigFiles(requestToCleanup) }
                                .onFailure { cleanupError ->
                                    Log.w(TAG, "Failure cleanup failed id=${downloadItem.id}", cleanupError)
                                }
                        }
                        withContext(Dispatchers.Main){
                            notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                        }
                        val latestStatus = runCatching { dao.checkStatus(downloadItem.id) }.getOrNull()
                        if (
                            this@DownloadWorker.isStopped ||
                            it is YoutubeDL.CanceledException ||
                            latestStatus == DownloadRepository.Status.Paused ||
                            latestStatus == DownloadRepository.Status.Cancelled
                        ) {
                            downloadOutcome = DownloadOutcome.canceled()
                            return@launch
                        }
                        val destinationWritable = if (
                            currentIssueStage == DownloadIssueStage.PREFLIGHT ||
                            currentIssueStage == DownloadIssueStage.MOVE
                        ) {
                            runCatching {
                                FileUtil.canWriteToDestination(downloadItem.downloadPath, context)
                            }.getOrNull()
                        } else {
                            null
                        }
                        val classifiedIssues = DownloadIssueClassifier.classify(
                            DownloadIssueClassifier.Input(
                                stage = currentIssueStage,
                                exceptionClassName = it.javaClass.name,
                                message = it.message.orEmpty(),
                                output = recentYtdlpOutput.joinToString("\n"),
                                destinationWritable = destinationWritable
                            )
                        )
                        val primaryIssue = classifiedIssues.first()
                        val structuredFailureSummary = classifiedIssues.joinToString("\n") { issue ->
                            DownloadIssueText.formatted(resources, issue)
                        }

                        createdOutputPaths = createdOutputPaths.filter { path ->
                            val file = File(path)
                            file.exists() && file.isFile
                        }
                        if (createdOutputPaths.isNotEmpty()) {
                            val warningIssue = DownloadIssue.create(
                                stage = primaryIssue.stage,
                                code = primaryIssue.code,
                                severity = DownloadIssueSeverity.WARNING,
                                suggestedActions = setOf(
                                    DownloadSuggestedAction.VIEW_LOG,
                                    DownloadSuggestedAction.COPY_SUMMARY
                                ),
                                details = primaryIssue.redactedDetails,
                                source = primaryIssue.source
                            )
                            val partialOutcome = DownloadOutcome.completed(
                                createdFileCount = createdOutputPaths.size,
                                issues = listOf(warningIssue)
                            )
                            downloadOutcome = partialOutcome
                            val warningSummary = DownloadIssueText.formatted(resources, warningIssue)
                            if (logDownloads) {
                                logRepo.update(
                                    "\nStructured outcome: ${partialOutcome.status}\n$warningSummary\n",
                                    currentLogItem.id
                                )
                            }
                            if (!preserveQueueRecord) {
                                runCatching { dao.delete(downloadItem.id) }
                                    .onFailure { deleteError ->
                                        preserveQueueRecord = true
                                        downloadItem.status = DownloadRepository.Status.Error.toString()
                                        downloadItem.lastIssueCode = primaryIssue.code.name
                                        downloadItem.lastIssueStage = primaryIssue.stage.name
                                        runCatching { dao.update(downloadItem) }
                                        Log.e(
                                            TAG,
                                            "Failed to delete completed queue record id=${downloadItem.id}",
                                            deleteError
                                        )
                                    }
                            }
                            runCatching {
                                withContext(Dispatchers.Main) {
                                    notificationUtil.createDownloadFinished(
                                        downloadItem.id,
                                        notificationTitle,
                                        downloadItem.type,
                                        createdOutputPaths,
                                        resources,
                                        warningSummary
                                    )
                                }
                            }
                            eventBus.post(
                                WorkerProgress(100, warningSummary, downloadItem.id, downloadItem.logID)
                            )
                            return@launch
                        }
                        val failedOutcome = DownloadOutcome.failed(primaryIssue)
                        downloadOutcome = failedOutcome
                        if (it.message?.contains("JSONDecodeError") == true) {
                            val cachePath = "${FileUtil.getCachePath(context)}infojsons"
                            val infoJsonName = MessageDigest.getInstance("MD5").digest(downloadItem.url.toByteArray()).toHexString()
                            FileUtil.deleteFile("${cachePath}/${infoJsonName}.info.json")
                        }

                        val failureDiagnostics = buildFailureDiagnostics(
                            error = it,
                            item = downloadItem,
                            requestCommand = effectiveCommandString,
                            tempDir = validatedTempFileDir ?: tempFileDir,
                            recentOutput = recentYtdlpOutput.toList()
                        ) + "\nStructured failure:\n$structuredFailureSummary\n"
                        if (logDownloads){
                            logRepo.update(failureDiagnostics, currentLogItem.id)
                        }


                        validatedTempFileDir?.delete()

                        Log.e(
                            TAG,
                            "${context.getString(R.string.failed_download)} id=${downloadItem.id} type=${it.javaClass.simpleName}",
                            it
                        )
                        notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())

                        downloadItem.status = DownloadRepository.Status.Error.toString()
                        downloadItem.lastIssueCode = primaryIssue.code.name
                        downloadItem.lastIssueStage = primaryIssue.stage.name
                        dao.update(downloadItem)
                        if (isHardSubRedownload(downloadItem)) {
                            markHardSubProcessed(downloadItem.id)
                            updateHardSubWorkerNotification(notificationUtil)
                        }

                        val retryMetadata = DownloadRetryMetadata(
                            operationId = downloadItem.operationId,
                            attempt = downloadItem.retryAttempt,
                            strategy = runCatching {
                                DownloadRetryStrategy.valueOf(downloadItem.retryStrategy)
                            }.getOrDefault(DownloadRetryStrategy.ORIGINAL)
                        )

                        notificationUtil.createDownloadErrored(
                            downloadItem.id,
                            notificationTitle,
                            structuredFailureSummary,
                            downloadItem.logID,
                            resources,
                            retryable = DownloadRetryPolicy.canOffer(
                                retryMetadata,
                                DownloadRetryStrategy.SAME_SETTINGS,
                                primaryIssue.retryable
                            ),
                            allowReconfigure =
                                DownloadSuggestedAction.RECONFIGURE in primaryIssue.suggestedActions &&
                                    DownloadRetryPolicy.canOffer(
                                        retryMetadata,
                                        DownloadRetryStrategy.RECONFIGURED
                                    )
                        )

                        eventBus.post(
                            WorkerProgress(
                                100,
                                structuredFailureSummary,
                                downloadItem.id,
                                downloadItem.logID
                            )
                        )
                    }
                    } finally {
                        downloadOutcome?.let { outcome ->
                            Log.i(
                                TAG,
                                "Download outcome id=${downloadItem.id} status=${outcome.status} issues=${outcome.issues.map { it.code }}"
                            )
                        }
                        if (hardSubPostProcessLockHeld) {
                            hardSubPostProcessMutex.unlock()
                        }
                        workerDownloadIds.remove(downloadItem.id)
                        val latestStatus = runCatching { dao.checkStatus(downloadItem.id) }.getOrNull()
                        if (
                            latestStatus != DownloadRepository.Status.Active &&
                            latestStatus != DownloadRepository.Status.PostProcessing
                        ) {
                            workerCleanupDownloadIds.remove(downloadItem.id)
                            ownedDownloadIds.remove(downloadItem.id)
                        }
                    }
                }
            }
        }
        }

        return Result.success()
    }



    companion object {
        val runningYTDLInstances: MutableList<Long> = mutableListOf()
        const val TAG = "DownloadWorker"
        const val HISTORY_REDOWNLOAD_MARKER = "history-redownload:"
        private val downloadWorkerMutex = Mutex()
        private val hardSubH264Containers = setOf(
            "mp4", "m4v", "mov", "mkv", "avi", "flv",
            "ts", "m2ts", "mts", "m2t", "3gp", "3g2"
        )

        private val hardSubTargetIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        private val hardSubProcessedIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        private val ownedDownloadIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        private val hardSubDisabledFfmpegSources: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val hardSubFilterSupportCache: MutableMap<String, Set<String>> = ConcurrentHashMap()
        private val hardSubPostProcessMutex = Mutex()
        private val runningFfmpegProcesses: MutableMap<Long, MutableSet<Process>> = ConcurrentHashMap()
        private val loadInfoJsonOptionRegex = Regex("""--load-info-json\s+(?:"([^"]+)"|'([^']+)'|(\S+))""")
        private const val FAILURE_YTDLP_TAIL_LINES = 160
        private const val FAILURE_FILE_LIST_LIMIT = 80
        private const val FAILURE_STACK_TRACE_LIMIT = 6000

        private fun cancelYtdlpProcess(downloadId: Long) {
            val processId = downloadId.toString()
            YoutubeDL.getInstance().destroyProcessById(processId)
            YoutubeDLCompat.destroyProcessById(processId)
        }

        fun cancelPostProcessingById(downloadId: Long) {
            runningFfmpegProcesses[downloadId]?.toList().orEmpty().forEach { process ->
                runCatching { process.destroy() }
            }
        }
    }

    private fun commandHasYtdlpOption(commandString: String, option: String): Boolean {
        return Regex("""(?:^|\s)${Regex.escape(option)}(?:\s|$)""").containsMatchIn(commandString)
    }

    private fun buildFailureDiagnostics(
        error: Exception,
        item: DownloadItem,
        requestCommand: String,
        tempDir: File,
        recentOutput: List<String>
    ): String {
        val stackTrace = ByteArrayOutputStream().use { out ->
            PrintStream(out).use { ps -> error.printStackTrace(ps) }
            redactFailureDiagnosticValue(out.toString(Charsets.UTF_8.name())).take(FAILURE_STACK_TRACE_LIMIT)
        }
        val outputTail = recentOutput
            .takeLast(FAILURE_YTDLP_TAIL_LINES)
            .joinToString("\n")
            .let { redactFailureDiagnosticValue(it) }
            .ifBlank { "<none>" }
        val errorMessage = redactFailureDiagnosticValue(error.message.orEmpty()).takeLast(4000)

        return buildString {
            appendLine()
            appendLine("Failure Diagnostics:")
            appendLine("exceptionType: ${error.javaClass.name}")
            appendLine("exceptionMessage: $errorMessage")
            appendLine("downloadId: ${item.id}")
            appendLine("type: ${item.type}")
            appendLine("formatId: ${item.format.format_id}")
            appendLine("formatContainer: ${item.format.container}")
            appendLine("formatVcodec: ${item.format.vcodec}")
            appendLine("formatAcodec: ${item.format.acodec}")
            appendLine("selectedContainer: ${item.container}")
            appendLine("videoRecode: ${item.videoPreferences.recodeVideo}")
            appendLine("videoCompatibilityMode: ${item.videoPreferences.compatibilityMode}")
            appendLine("videoEmbedSubs: ${item.videoPreferences.embedSubs}")
            appendLine("videoWriteSubs: ${item.videoPreferences.writeSubs}")
            appendLine("videoWriteAutoSubs: ${item.videoPreferences.writeAutoSubs}")
            appendLine("hasLoadInfoJson: ${commandHasYtdlpOption(requestCommand, "--load-info-json")}")
            appendLine("mergeOutputFormat: ${requestCommand.firstYtdlpOptionValue("--merge-output-format") ?: "<none>"}")
            appendLine("formatSelector: ${requestCommand.firstYtdlpOptionValue("-f") ?: "<none>"}")
            appendLine("sortSelector: ${requestCommand.firstYtdlpOptionValue("-S") ?: "<none>"}")
            appendLine("tempDir: ${tempDir.absolutePath}")
            appendLine(buildTempDirectoryDiagnostics(tempDir))
            appendLine("ytDlpRecentOutputLast${FAILURE_YTDLP_TAIL_LINES}:")
            appendLine(outputTail)
            appendLine("stackTrace:")
            appendLine(stackTrace)
        }
    }

    private fun redactFailureDiagnosticValue(value: String): String {
        return SensitiveTextRedactor.redactOutput(value)
            .replace(Regex("""po_token=[^;"\s]+"""), "po_token=<redacted>")
            .replace(Regex("""pot=[^&;"\s]+"""), "pot=<redacted>")
            .replace(Regex("""visitor_data=[^;"\s]+"""), "visitor_data=<redacted>")
            .replace(Regex("""data_sync_id=[^;"\s]+"""), "data_sync_id=<redacted>")
            .replace(Regex("""(?i)(authorization|cookie):\s*[^\r\n]+"""), "$1: <redacted>")
            .replace(Regex("""https?://[^\s"'<>]*googlevideo\.com/[^\s"'<>]+"""), "<googlevideo-url-redacted>")
    }

    private fun buildTempDirectoryDiagnostics(tempDir: File): String {
        if (!tempDir.exists()) return "tempFiles: <temp-dir-missing>"
        if (!tempDir.isDirectory) return "tempFiles: <not-a-directory size=${tempDir.length()}>"

        val files = runCatching {
            tempDir.walkTopDown()
                .filter { it.isFile }
                .sortedByDescending { it.lastModified() }
                .take(FAILURE_FILE_LIST_LIMIT)
                .toList()
        }.getOrElse {
            return "tempFiles: <scan-failed ${it.javaClass.simpleName}:${it.message.orEmpty().take(200)}>"
        }

        if (files.isEmpty()) return "tempFiles: <empty>"
        return buildString {
            appendLine("tempFiles:")
            files.forEach { file ->
                val relativePath = runCatching { file.relativeTo(tempDir).path }.getOrDefault(file.name)
                appendLine(" - ${relativePath} size=${file.length()} modified=${file.lastModified()} ext=${file.extension.ifBlank { "<none>" }}")
            }
            val totalFiles = runCatching { tempDir.walkTopDown().count { it.isFile } }.getOrDefault(files.size)
            if (totalFiles > files.size) {
                appendLine(" - <${totalFiles - files.size} more files omitted>")
            }
        }.trimEnd()
    }

    private fun String.firstYtdlpOptionValue(option: String): String? {
        val pattern = Regex("""(?:^|\s)${Regex.escape(option)}\s+(?:"([^"]+)"|'([^']+)'|(\S+))""")
        return pattern.find(this)
            ?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotBlank() }
    }

    private fun isHardSubRedownload(item: DownloadItem): Boolean {
        return item.type == com.ireum.ytdl.database.enums.DownloadType.video &&
            item.videoPreferences.embedSubs &&
            item.playlistURL?.startsWith(HISTORY_REDOWNLOAD_MARKER) == true
    }

    private fun buildYtdlpRetryProbeText(error: Exception, recentOutput: List<String>): String {
        return buildString {
            appendLine(error.message.orEmpty())
            recentOutput.takeLast(FAILURE_YTDLP_TAIL_LINES).forEach(::appendLine)
        }
    }

    private fun shouldRetryWithoutCachedInfoJson(retryProbeText: String, commandString: String): Boolean {
        if (!commandString.contains("--load-info-json")) return false
        return retryProbeText.contains("HTTP Error 403", ignoreCase = true) ||
            (
                retryProbeText.contains("Forbidden", ignoreCase = true) &&
                    retryProbeText.contains("unable to download video data", ignoreCase = true)
            )
    }

    private fun shouldRetryYoutube403WithoutAuthentication(
        retryProbeText: String,
        commandString: String,
        item: DownloadItem
    ): Boolean {
        if (
            item.type != DownloadType.video ||
            !item.url.isYoutubeURL() ||
            item.url.getIDFromYoutubeURL() == null
        ) return false
        val hasAuthentication = commandHasYtdlpOption(commandString, "--cookies") ||
            commandString.contains("po_token=") ||
            commandString.contains("data_sync_id=") ||
            commandString.contains("visitor_data=")
        if (!hasAuthentication) return false
        return retryProbeText.contains("HTTP Error 403", ignoreCase = true) ||
            retryProbeText.contains("403: Forbidden", ignoreCase = true) ||
            (
                retryProbeText.contains("Forbidden", ignoreCase = true) &&
                    retryProbeText.contains("unable to download video data", ignoreCase = true)
            )
    }

    private fun deleteLoadedAppInfoJson(commandString: String) {
        val infoJsonRoot = runCatching {
            File(FileUtil.getCachePath(context), "infojsons").canonicalFile
        }.getOrNull() ?: return

        loadInfoJsonOptionRegex.findAll(commandString)
            .mapNotNull { match ->
                match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
            }
            .forEach { path ->
                runCatching {
                    val file = File(path).canonicalFile
                    if (
                        file.parentFile == infoJsonRoot &&
                        file.name.endsWith(".info.json") &&
                        file.exists()
                    ) {
                        if (file.delete()) {
                            Log.i(TAG, "Deleted stale cached info JSON: ${file.name}")
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "Failed to delete stale cached info JSON from retry command", it)
                }
            }
    }

    private fun resolveHardSubSkipReason(output: String): String? {
        val normalized = output.lowercase(Locale.US)
        return when {
            normalized.contains("there are no subtitles for the requested languages") -> "Subtitle burn-in skipped: requested subtitles are unavailable"
            normalized.contains("no subtitles for the requested languages") -> "Subtitle burn-in skipped: requested subtitles are unavailable"
            else -> null
        }
    }

    private fun pickLocalThumbnailPath(paths: List<String>): String? {
        if (paths.isEmpty()) return null
        val thumbExts = setOf("jpg", "jpeg", "png", "webp", "avif")
        return paths.firstOrNull { path ->
            val file = File(path)
            file.exists() && thumbExts.contains(file.extension.lowercase(Locale.US))
        }
    }

    private fun resetHardSubProgress() {
        hardSubTargetIds.clear()
        hardSubProcessedIds.clear()
        hardSubDisabledFfmpegSources.clear()
        hardSubFilterSupportCache.clear()
    }

    private fun registerHardSubTarget(downloadId: Long) {
        hardSubTargetIds.add(downloadId)
    }

    private fun markHardSubProcessed(downloadId: Long) {
        if (hardSubTargetIds.contains(downloadId)) {
            hardSubProcessedIds.add(downloadId)
        }
    }

    private fun getHardSubStatusText(resources: Resources): String? {
        val total = hardSubTargetIds.size
        if (total <= 0) return null
        val done = hardSubProcessedIds.size.coerceAtMost(total)
        return resources.getString(R.string.hard_sub_progress, done, total)
    }

    private fun updateHardSubWorkerNotification(notificationUtil: NotificationUtil) {
        val status = getHardSubStatusText(context.resources)
        if (status.isNullOrBlank()) {
            notificationUtil.notify(1000000000, notificationUtil.createDefaultWorkerNotification())
        } else {
            notificationUtil.notify(1000000000, notificationUtil.createHardSubWorkerNotification(status))
        }
    }

    private fun validateSubtitleFilesForUse(
        subtitleFiles: List<File>,
        subtitleRequest: SubtitleSelection.Request,
        downloadItemId: Long?,
        downloadLogId: Long?,
        fatal: Boolean = true
    ): List<File> {
        if (subtitleFiles.isEmpty()) {
            val message = "Subtitle validation found no subtitle files for ${subtitleRequest.subLanguages}"
            if (fatal) {
                val error = "Subtitle validation failed: no subtitle files found for ${subtitleRequest.subLanguages}"
                Log.e(TAG, "HardSub $error")
                EventBus.getDefault().post(WorkerProgress(100, error, downloadItemId ?: -1L, downloadLogId))
                throw IOException(error)
            }
            Log.w(TAG, message)
            EventBus.getDefault().post(WorkerProgress(100, message, downloadItemId ?: -1L, downloadLogId))
            return emptyList()
        }

        val validated = subtitleFiles.filter { file ->
            val result = SubtitleFileValidator.validate(file, subtitleRequest.liveChatOnly)
            val status = if (result.valid) "ok" else "failed"
            val message = "Subtitle validation $status file=${file.name} size=${file.length()} cues=${result.cueCount} reason=${result.reason} selected=${subtitleRequest.subLanguages} liveChat=${subtitleRequest.liveChatOnly}"
            if (result.valid) {
                Log.i(TAG, message)
            } else {
                Log.w(TAG, "$message zeroByte=${file.length() == 0L}")
            }
            result.valid
        }

        if (validated.isEmpty()) {
            val failed = subtitleFiles.joinToString { "${it.name}:${it.length()}B" }
            if (fatal) {
                val error = "Subtitle validation failed: no usable subtitle body for ${subtitleRequest.subLanguages}; files=$failed"
                Log.e(TAG, "HardSub $error")
                EventBus.getDefault().post(WorkerProgress(100, error, downloadItemId ?: -1L, downloadLogId))
                throw IOException(error)
            }
            val warning = "Subtitle validation found no usable subtitle body for ${subtitleRequest.subLanguages}; files=$failed"
            Log.w(TAG, warning)
            EventBus.getDefault().post(WorkerProgress(100, warning, downloadItemId ?: -1L, downloadLogId))
            return emptyList()
        }

        return validated
    }

    private fun validateSavedSubtitleSidecars(
        downloadItem: DownloadItem,
        finalPaths: List<String>,
        downloadLocation: String,
        downloadStartedAt: Long
    ) {
        val subtitleRequest = SubtitleSelection.normalize(downloadItem.videoPreferences.subsLanguages)
        val subtitleExts = setOf("srv3", "json3", "json", "ttml", "ass", "vtt", "srt")
        val fromPaths = finalPaths
            .map { File(it) }
            .filter { it.exists() && it.isFile && it.extension.lowercase(Locale.US) in subtitleExts }
        val fromDirectory = runCatching {
            File(downloadLocation)
                .walkTopDown()
                .filter { it.isFile && it.extension.lowercase(Locale.US) in subtitleExts }
                .filter { it.lastModified() >= downloadStartedAt }
                .toList()
        }.getOrDefault(emptyList())

        val selected = (fromPaths + fromDirectory)
            .distinctBy { it.absolutePath }
            .filter { SubtitleSelection.isSelectedSubtitleFile(it, subtitleRequest) }

        Log.i(
            TAG,
            "Subtitle sidecar scan id=${downloadItem.id} selected=${subtitleRequest.subLanguages} liveChat=${subtitleRequest.liveChatOnly} candidates=${selected.size}"
        )
        validateSubtitleFilesForUse(
            selected,
            subtitleRequest,
            downloadItem.id,
            downloadItem.logID,
            fatal = false
        )
    }

    private fun burnSubtitlesInPlace(
        paths: List<String>,
        removeSubsAfterBurnIn: Boolean,
        downloadItemId: Long? = null,
        downloadLogId: Long? = null,
        selectedSubtitleLanguages: String = ""
    ): Boolean {
        val ffmpegRuntime = resolveFfmpegRuntime()
        val supportedFilters = probeSubtitleFilters(ffmpegRuntime)
        val dedicatedSrv3ConverterPath = resolveSrv3ConverterPath()
        val subtitleExts = listOf("srv3", "json3", "json", "ttml", "ass", "vtt", "srt")
        val subtitleRequest = SubtitleSelection.normalize(selectedSubtitleLanguages)
        val existingFiles = paths
            .map { File(it) }
            .filter { it.exists() && it.isFile }
        val siblingFiles = existingFiles
            .mapNotNull { it.parentFile }
            .distinctBy { it.absolutePath }
            .flatMap { it.listFiles().orEmpty().asList() }
        val subtitleFiles = (existingFiles + siblingFiles)
            .distinctBy { it.absolutePath }
            .filter { file -> subtitleExts.any { ext -> file.extension.equals(ext, ignoreCase = true) } }
            .filter { file -> SubtitleSelection.isSelectedSubtitleFile(file, subtitleRequest) }
            .let { validateSubtitleFilesForUse(it, subtitleRequest, downloadItemId, downloadLogId) }
        if (!removeSubsAfterBurnIn) {
            convertSubtitleFilesToSrt(subtitleFiles, ffmpegRuntime, dedicatedSrv3ConverterPath)
        }
        val canonicalSubtitle = createCanonicalHardSubSubtitle(subtitleFiles, subtitleExts)
        val subtitleCandidates = canonicalSubtitle?.let { listOf(it.file) } ?: subtitleFiles
        var mediaFiles = existingFiles
            .filterNot { file -> subtitleExts.any { ext -> file.extension.equals(ext, ignoreCase = true) } }
        if (mediaFiles.isEmpty() && subtitleCandidates.isNotEmpty()) {
            mediaFiles = findSiblingMediaForSubtitles(subtitleCandidates, subtitleExts)
            if (mediaFiles.isNotEmpty()) {
                Log.w(TAG, "HardSub media fallback from subtitle siblings used recovered=${mediaFiles.size}")
            }
        }
        mediaFiles = mergeSeparatedVideoAudioIfNeeded(mediaFiles, ffmpegRuntime)

        Log.i(
            TAG,
            "HardSub burn scan mediaFiles=${mediaFiles.size} converter=${if (dedicatedSrv3ConverterPath != null) "yttml" else "ffmpeg-only"}"
        )
        Log.i(
            TAG,
            "HardSub ffmpeg runtime exec=${ffmpegRuntime.executablePath} linker=${ffmpegRuntime.linkerPath ?: "<direct>"} source=${ffmpegRuntime.source} libs=${ffmpegRuntime.libraryPath ?: "<default>"}"
        )
        if (supportedFilters.isNotEmpty()) {
            Log.i(TAG, "HardSub ffmpeg subtitle filters=${supportedFilters.joinToString(",")}")
        } else {
            Log.w(TAG, "HardSub ffmpeg subtitle filter probe unavailable; using runtime fallback attempts")
        }
        if (mediaFiles.isEmpty()) {
            Log.w(TAG, "HardSub burn input paths=${paths.joinToString(limit = 5)}")
            Log.w(TAG, "HardSub no media files found for burn-in after path resolution")
            throw IOException("HardSub aborted: no media files found for burn-in")
        }
        var burnedMediaCount = 0
        val consumedSubtitles = linkedSetOf<String>()
        var unwritableOutputDir: String? = null
        mediaFiles.forEach { media ->
            if (!hasVideoStream(media, ffmpegRuntime)) {
                Log.w(TAG, "HardSub skip media=${media.name} reason=no-video-stream")
                return@forEach
            }
            val mediaHadAudioBeforeBurn = hasAudioStream(media, ffmpegRuntime)
            val mediaParent = media.parentFile
            if (mediaParent == null || !canCreateSiblingOutput(mediaParent)) {
                if (unwritableOutputDir == null) {
                    unwritableOutputDir = mediaParent?.absolutePath ?: "unknown"
                }
                Log.w(
                    TAG,
                    "HardSub skip media=${media.name} reason=output-directory-not-writable dir=${mediaParent?.absolutePath ?: "unknown"}"
                )
                return@forEach
            }
            val subtitle = prepareSubtitleForBurnIn(media, subtitleExts, subtitleCandidates, ffmpegRuntime, dedicatedSrv3ConverterPath, subtitleRequest)
            if (subtitle == null) {
                Log.w(TAG, "HardSub skip media=${media.name} reason=no-matching-subtitle")
                return@forEach
            }
            consumedSubtitles.add(subtitle.file.absolutePath)
            val progressTarget = if (downloadItemId != null) {
                FfmpegProgressTarget(downloadItemId, downloadLogId, media.name)
            } else {
                null
            }
            val output = File(media.parentFile, "${media.nameWithoutExtension}.burnin.${media.extension}")
            val filterCandidates = buildFilterCandidatesForMedia(subtitle.isAss, supportedFilters)
            if (filterCandidates.isEmpty()) {
                throw IOException(
                    "ffmpeg runtime missing required subtitle filter (available=${supportedFilters.joinToString(",").ifBlank { "none" }})"
                )
            }
            var ffmpegResult: FfmpegExecResult? = null
            var usedFilter: String? = null
            for (filter in filterCandidates) {
                val filterArgs = buildSubtitleFilterArgs(filter, subtitle.file.absolutePath)
                var result: FfmpegExecResult = FfmpegExecResult(1, "ffmpeg burn-in returned no result")
                for (subtitleArg in filterArgs) {
                    Log.i(
                        TAG,
                        "HardSub burn file media=${media.name} subtitle=${subtitle.file.name} filter=$filter temp=${subtitle.isTemporary}"
                    )
                    result = executeFfmpegWithAutoPatch(
                        ffmpegRuntime,
                        listOf(
                            "-y",
                            "-i",
                            media.absolutePath,
                            "-vf",
                            subtitleArg
                        ) + hardSubVideoEncodeArgs(media) + listOf(
                            "-c:a",
                            "copy",
                            output.absolutePath
                        ),
                        progressTarget
                    )
                    if (result.exitCode != 0 && media.extension.equals("webm", ignoreCase = true) && shouldTryWebmFallback(result.output)) {
                        val skipLibvpx = result.output.contains("ABI version mismatch", ignoreCase = true)
                        val encoderFallbacks = if (skipLibvpx) {
                            listOf("vp9", "vp8")
                        } else {
                            listOf("libvpx-vp9", "libvpx", "vp9", "vp8")
                        }
                        for (encoder in encoderFallbacks) {
                            Log.w(TAG, "HardSub webm encoder fallback media=${media.name} encoder=$encoder")
                            result = executeFfmpegWithAutoPatch(
                                ffmpegRuntime,
                                listOf(
                                    "-y",
                                    "-i",
                                    media.absolutePath,
                                    "-vf",
                                    subtitleArg,
                                    "-c:v",
                                    encoder,
                                    "-c:a",
                                    "copy",
                                    output.absolutePath
                                ),
                                progressTarget
                            )
                            if (result.exitCode == 0) break
                        }
                        if (result.exitCode != 0) {
                            // Some Android ffmpeg builds can burn subtitles but cannot encode WebM video.
                            // Fallback to Matroska muxer with broadly available encoders.
                            val mkvOutput = File(media.parentFile, "${media.nameWithoutExtension}.burnin.mkv")
                            val mkvFallbacks = listOf(
                                listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "copy"),
                                listOf("-c:v", "mpeg4", "-q:v", "2", "-c:a", "copy")
                            )
                            for (fallback in mkvFallbacks) {
                                val encoderLabel = fallback.joinToString(" ")
                                Log.w(TAG, "HardSub webm container fallback media=${media.name} args=$encoderLabel")
                                result = executeFfmpegWithAutoPatch(
                                    ffmpegRuntime,
                                    listOf(
                                        "-y",
                                        "-i",
                                        media.absolutePath,
                                        "-vf",
                                        subtitleArg
                                    ) + fallback + listOf(
                                        "-f",
                                        "matroska",
                                        mkvOutput.absolutePath
                                    ),
                                    progressTarget
                                )
                                if (result.exitCode == 0) {
                                    if (output.exists()) output.delete()
                                    if (mkvOutput.exists()) {
                                        mkvOutput.renameTo(output)
                                    }
                                    break
                                }
                                if (mkvOutput.exists()) mkvOutput.delete()
                            }
                        }
                    }
                    // If this runtime does not include the requested filter, stop retrying this filter variant immediately.
                    if (result.output.contains("No such filter: '$filter'", ignoreCase = true)) {
                        break
                    }
                    if (result.exitCode == 0) break
                }
                if (result.exitCode == 0) {
                    ffmpegResult = result
                    usedFilter = filter
                    break
                }

                // Fallback when ffmpeg build does not include the ass filter.
                if (filter == "ass" && result.output.contains("No such filter: 'ass'", ignoreCase = true)) {
                    ffmpegResult = result
                    Log.w(TAG, "HardSub ass filter unavailable, fallback to subtitles filter media=${media.name}")
                    continue
                }
                if (filter == "subtitles" && result.output.contains("No such filter: 'subtitles'", ignoreCase = true)) {
                    ffmpegResult = result
                    Log.w(TAG, "HardSub subtitles filter unavailable media=${media.name}")
                    continue
                }

                ffmpegResult = result
                break
            }

            val finalResult = ffmpegResult ?: FfmpegExecResult(1, "ffmpeg burn-in returned no result")
            if (finalResult.exitCode != 0) {
                Log.e(TAG, "HardSub ffmpeg burn failed code=${finalResult.exitCode} media=${media.name}")
                if (subtitle.isTemporary) subtitle.file.delete()
                throw IOException("ffmpeg burn-in failed (code=${finalResult.exitCode}): ${finalResult.output.takeLast(1200)}")
            }
            if (!output.exists()) {
                Log.e(TAG, "HardSub ffmpeg output missing media=${media.name}")
                if (subtitle.isTemporary) subtitle.file.delete()
                throw IOException("ffmpeg burn-in failed: output was not created")
            }
            if (mediaHadAudioBeforeBurn && !hasAudioStream(output, ffmpegRuntime)) {
                Log.e(TAG, "HardSub ffmpeg output missing-audio media=${media.name}")
                if (output.exists()) output.delete()
                if (subtitle.isTemporary) subtitle.file.delete()
                throw IOException("ffmpeg burn-in failed: output lost audio stream")
            }

            Log.i(
                TAG,
                "HardSub replace start media=${media.absolutePath} exists=${media.exists()} size=${media.length()} mtime=${media.lastModified()} output=${output.absolutePath} outSize=${output.length()}"
            )

            if (media.exists() && !media.delete()) {
                Log.e(TAG, "HardSub replace delete failed media=${media.absolutePath}")
                if (subtitle.isTemporary) subtitle.file.delete()
                throw IOException("failed to replace original media file after burn-in")
            }
            if (!output.renameTo(media)) {
                Log.e(TAG, "HardSub replace rename failed from=${output.absolutePath} to=${media.absolutePath}")
                if (subtitle.isTemporary) subtitle.file.delete()
                throw IOException("failed to rename burn-in output file")
            }
            // Keep filesystem mtime aligned with hard-sub completion time so
            // re-downloaded/re-encoded files are distinguishable from originals.
            runCatching { media.setLastModified(System.currentTimeMillis()) }
            Log.i(
                TAG,
                "HardSub replace done media=${media.absolutePath} exists=${media.exists()} size=${media.length()} mtime=${media.lastModified()}"
            )

            if (removeSubsAfterBurnIn) {
                deleteSubtitleSidecars(media, subtitleExts)
            }
            if (subtitle.isTemporary) {
                subtitle.file.delete()
            }
            burnedMediaCount += 1
            Log.i(TAG, "HardSub burn success media=${media.name} filter=${usedFilter ?: "unknown"}")
        }
        if (burnedMediaCount == 0) {
            if (canonicalSubtitle?.isTemporary == true) canonicalSubtitle.file.delete()
            if (!unwritableOutputDir.isNullOrBlank()) {
                throw IOException("HardSub aborted: output directory is not writable for ffmpeg ($unwritableOutputDir)")
            }
            Log.w(TAG, "HardSub skipped: no media was burned")
            return false
        }
        if (removeSubsAfterBurnIn) {
            // Remove downloaded subtitle sidecars that participated in this burn-in run.
            val removableSubtitlePaths = linkedSetOf<String>().apply {
                addAll(subtitleFiles.map { it.absolutePath })
                addAll(consumedSubtitles)
            }
            removableSubtitlePaths.forEach { subtitlePath ->
                runCatching {
                    val subtitleFile = File(subtitlePath)
                    if (subtitleFile.exists() && subtitleFile.isFile) {
                        subtitleFile.delete()
                    }
                }
            }
        }
        if (canonicalSubtitle?.isTemporary == true) canonicalSubtitle.file.delete()
        return true
    }

    private fun hardSubVideoEncodeArgs(media: File): List<String> {
        if (media.extension.lowercase(Locale.ROOT) !in hardSubH264Containers) {
            return emptyList()
        }
        return listOf(
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "18",
            "-pix_fmt",
            "yuv420p"
        )
    }

    private fun hasVideoStream(media: File, ffmpegRuntime: FfmpegRuntime): Boolean {
        val probe = executeFfmpegWithAutoPatch(
            ffmpegRuntime,
            listOf("-hide_banner", "-i", media.absolutePath)
        )
        // ffmpeg -i returns non-zero without output target, so parse stream info from output.
        return Regex("""(?m)^\s*Stream #\d+:\d+.*:\s*Video:\s*""").containsMatchIn(probe.output)
    }

    private fun hasAudioStream(media: File, ffmpegRuntime: FfmpegRuntime): Boolean {
        val probe = executeFfmpegWithAutoPatch(
            ffmpegRuntime,
            listOf("-hide_banner", "-i", media.absolutePath)
        )
        // ffmpeg -i returns non-zero without output target, so parse stream info from output.
        return Regex("""(?m)^\s*Stream #\d+:\d+.*:\s*Audio:\s*""").containsMatchIn(probe.output)
    }

    private fun mergeSeparatedVideoAudioIfNeeded(mediaFiles: List<File>, ffmpegRuntime: FfmpegRuntime): List<File> {
        if (mediaFiles.size < 2) {
            val single = mediaFiles.firstOrNull() ?: return mediaFiles
            val likelySplitVideo = normalizeMuxStem(single.nameWithoutExtension) != single.nameWithoutExtension
            if (likelySplitVideo && hasVideoStream(single, ffmpegRuntime) && !hasAudioStream(single, ffmpegRuntime)) {
                throw IOException("HardSub AV merge required but companion audio file is missing for video=${single.name}")
            }
            return mediaFiles
        }

        val videoCandidates = mediaFiles.filter { hasVideoStream(it, ffmpegRuntime) }
        if (videoCandidates.isEmpty()) return mediaFiles
        val audioOnlyCandidates = mediaFiles.filter { !hasVideoStream(it, ffmpegRuntime) && hasAudioStream(it, ffmpegRuntime) }

        val primaryVideo = videoCandidates.maxByOrNull { it.length() } ?: return mediaFiles
        if (hasAudioStream(primaryVideo, ffmpegRuntime)) return mediaFiles
        if (audioOnlyCandidates.isEmpty()) {
            throw IOException(
                "HardSub AV merge required but no audio-only file found for video=${primaryVideo.name}"
            )
        }

        val normalizedVideoStem = normalizeMuxStem(primaryVideo.nameWithoutExtension)
        val matchedAudioCandidates = audioOnlyCandidates
            .filter { normalizeMuxStem(it.nameWithoutExtension) == normalizedVideoStem }
        if (matchedAudioCandidates.isEmpty()) {
            throw IOException(
                "HardSub AV merge required but matching audio file not found for video=${primaryVideo.name}"
            )
        }
        val primaryAudio = matchedAudioCandidates.maxByOrNull { it.length() }
            ?: throw IOException("HardSub AV merge failed selecting audio candidate for video=${primaryVideo.name}")

        val mergedVideo = if (canCreateSiblingOutput(primaryVideo.parentFile ?: return mediaFiles)) {
            mergeVideoAudioPairInDirectory(primaryVideo, primaryAudio, ffmpegRuntime)
        } else {
            Log.w(
                TAG,
                "HardSub AV merge staging fallback video=${primaryVideo.name} audio=${primaryAudio.name} dir=${primaryVideo.parentFile?.absolutePath ?: "unknown"}"
            )
            mergeVideoAudioPairViaWritableStage(primaryVideo, primaryAudio, ffmpegRuntime)
        } ?: throw IOException(
            "HardSub AV merge failed video=${primaryVideo.name} audio=${primaryAudio.name}"
        )

        val removed = setOf(primaryVideo.absolutePath, primaryAudio.absolutePath)
        return mediaFiles
            .asSequence()
            .filter { it.absolutePath !in removed }
            .filter { it.exists() && it.isFile }
            .plus(sequenceOf(mergedVideo))
            .distinctBy { it.absolutePath }
            .toList()
    }

    private fun mergeVideoAudioPairInDirectory(primaryVideo: File, primaryAudio: File, ffmpegRuntime: FfmpegRuntime): File? {
        val parent = primaryVideo.parentFile ?: return null
        val mergedTemp = File(parent, "${primaryVideo.nameWithoutExtension}.muxed.${primaryVideo.extension}")
        val mergeResult = executeFfmpegWithAutoPatch(
            ffmpegRuntime,
            listOf(
                "-y",
                "-i", primaryVideo.absolutePath,
                "-i", primaryAudio.absolutePath,
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c", "copy",
                mergedTemp.absolutePath
            )
        )
        if (mergeResult.exitCode != 0 || !mergedTemp.exists() || mergedTemp.length() == 0L) {
            if (mergedTemp.exists()) mergedTemp.delete()
            Log.w(
                TAG,
                "HardSub AV merge skipped video=${primaryVideo.name} audio=${primaryAudio.name} code=${mergeResult.exitCode} dir=${parent.absolutePath}"
            )
            return null
        }

        val replaceOk = runCatching {
            if (primaryVideo.exists() && !primaryVideo.delete()) return@runCatching false
            if (!mergedTemp.renameTo(primaryVideo)) return@runCatching false
            primaryAudio.delete()
            true
        }.getOrDefault(false)
        if (!replaceOk) {
            if (mergedTemp.exists()) mergedTemp.delete()
            Log.w(TAG, "HardSub AV merge replace failed video=${primaryVideo.name} audio=${primaryAudio.name}")
            return null
        }

        Log.i(
            TAG,
            "HardSub AV merged video=${primaryVideo.name} audio=${primaryAudio.name} size=${primaryVideo.length()}"
        )
        return primaryVideo
    }

    private fun mergeVideoAudioPairViaWritableStage(primaryVideo: File, primaryAudio: File, ffmpegRuntime: FfmpegRuntime): File? {
        val sourceParent = primaryVideo.parentFile ?: return null
        val stageRoot = File(
            FileUtil.getCachePath(context),
            "hardsub_mux_stage/${System.currentTimeMillis()}_${primaryVideo.nameWithoutExtension.hashCode().toString().replace('-', 'n')}"
        )
        if (!stageRoot.exists() && !stageRoot.mkdirs()) {
            Log.w(TAG, "HardSub AV merge staging create failed dir=${stageRoot.absolutePath}")
            return null
        }
        return try {
            val stagedVideo = File(stageRoot, primaryVideo.name)
            val stagedAudio = File(stageRoot, primaryAudio.name)
            runCatching { primaryVideo.copyTo(stagedVideo, overwrite = true) }.getOrElse { error ->
                Log.w(TAG, "HardSub AV merge staging copy failed file=${primaryVideo.name} reason=${error.message}")
                return null
            }
            runCatching { primaryAudio.copyTo(stagedAudio, overwrite = true) }.getOrElse { error ->
                Log.w(TAG, "HardSub AV merge staging copy failed file=${primaryAudio.name} reason=${error.message}")
                return null
            }

            val mergedInStage = mergeVideoAudioPairInDirectory(stagedVideo, stagedAudio, ffmpegRuntime) ?: return null
            val publishDir = File(stageRoot, "publish")
            if (!publishDir.exists() && !publishDir.mkdirs()) {
                Log.w(TAG, "HardSub AV merge staging publish dir create failed dir=${publishDir.absolutePath}")
                return null
            }
            val stagedPublish = File(publishDir, primaryVideo.name)
            mergedInStage.copyTo(stagedPublish, overwrite = true)

            val movedBack = runBlocking {
                runCatching {
                    FileUtil.moveFile(
                        publishDir,
                        context,
                        sourceParent.absolutePath,
                        keepCache = false
                    ) { _ -> }
                }.getOrElse { error ->
                    Log.w(TAG, "HardSub AV merge staging move-back failed dir=${sourceParent.absolutePath} reason=${error.message}")
                    emptyList()
                }
            }
            val movedPath = movedBack
                .firstOrNull { File(it).name == primaryVideo.name }
                ?: movedBack.firstOrNull { moved ->
                    val movedFile = File(moved)
                    movedFile.extension.equals(primaryVideo.extension, ignoreCase = true) &&
                        movedFile.nameWithoutExtension.startsWith(primaryVideo.nameWithoutExtension)
                }
                ?: return null

            runCatching { primaryAudio.delete() }
            runCatching { primaryVideo.delete() }
            val mergedFile = File(movedPath)
            if (!mergedFile.exists() || !mergedFile.isFile) return null
            Log.i(
                TAG,
                "HardSub AV merged via staging video=${primaryVideo.name} audio=${primaryAudio.name} output=${mergedFile.absolutePath}"
            )
            mergedFile
        } finally {
            runCatching { stageRoot.deleteRecursively() }
        }
    }

    private fun normalizeMuxStem(name: String): String {
        // Match yt-dlp split stream suffixes like ".f248", ".f248-sr", ".f140-drc", etc.
        return name.replace(Regex("""\.f\d+(?:-[A-Za-z0-9_]+)*$""", RegexOption.IGNORE_CASE), "")
    }

    private fun prepareSubtitleForBurnIn(
        media: File,
        subtitleExts: List<String>,
        providedSubtitles: List<File>,
        ffmpegRuntime: FfmpegRuntime,
        dedicatedSrv3ConverterPath: String?,
        subtitleRequest: SubtitleSelection.Request
    ): BurnInSubtitle? {
        val candidates = findSubtitleCandidatesForMedia(media, subtitleExts, providedSubtitles, subtitleRequest)
        if (candidates.isEmpty()) return null

        candidates.forEach { selectedSubtitle ->
            if (selectedSubtitle.extension.equals("ass", ignoreCase = true)) {
                return BurnInSubtitle(selectedSubtitle, isAss = true, isTemporary = false)
            }

            val convertedAss = convertSubtitleToAss(selectedSubtitle, ffmpegRuntime, dedicatedSrv3ConverterPath)
            if (convertedAss != null) {
                return BurnInSubtitle(convertedAss, isAss = true, isTemporary = true)
            }

            if (setOf("srv3", "json3", "ttml").contains(selectedSubtitle.extension.lowercase(Locale.US))) {
                Log.w(
                    TAG,
                    "HardSub rich subtitle convert failed source=${selectedSubtitle.name} trying-next-candidate"
                )
                return@forEach
            }

            return BurnInSubtitle(selectedSubtitle, isAss = false, isTemporary = false)
        }

        return null
    }

    private fun convertSubtitleToAss(subtitle: File, ffmpegRuntime: FfmpegRuntime, dedicatedSrv3ConverterPath: String?): File? {
        val richSubtitleExts = setOf("srv3", "json3", "ttml")
        val ext = subtitle.extension.lowercase(Locale.US)
        if (ext in setOf("json", "json3")) {
            SubtitleFormatConverter.convertJson3ToAss(subtitle, createHardSubTempAssFile())?.let {
                Log.i(TAG, "HardSub json3 subtitle converted to ass source=${subtitle.name} output=${it.name}")
                return it
            }
        }
        if (
            dedicatedSrv3ConverterPath != null &&
            richSubtitleExts.contains(ext)
        ) {
            convertSrv3ToAssWithDedicatedConverter(subtitle, dedicatedSrv3ConverterPath)?.let { return it }
            createNormalizedRichSubtitleForConverter(subtitle)?.let { normalized ->
                try {
                    convertSrv3ToAssWithDedicatedConverter(normalized, dedicatedSrv3ConverterPath)?.let { converted ->
                        Log.i(
                            TAG,
                            "HardSub dedicated rich subtitle conversion succeeded after normalization source=${subtitle.name}"
                        )
                        return converted
                    }
                } finally {
                    runCatching { normalized.delete() }
                }
            }
        }

        val output = createHardSubTempAssFile()
        val result = executeFfmpegWithAutoPatch(
            ffmpegRuntime,
            listOf(
                "-y",
                "-i",
                subtitle.absolutePath,
                output.absolutePath
            )
        )
        val exitCode = result.exitCode
        if (exitCode != 0 || !output.exists() || output.length() == 0L) {
            if (output.exists()) output.delete()
            return null
        }
        return output
    }

    private data class FfmpegRuntime(
        val executablePath: String,
        val linkerPath: String?,
        val libraryPath: String?,
        val preloadLibraryPath: String?,
        val source: String
    )

    private data class FfmpegExecResult(
        val exitCode: Int,
        val output: String
    )

    private data class FfmpegProgressTarget(
        val downloadItemId: Long,
        val logItemId: Long?,
        val mediaName: String
    )

    private fun resolveFfmpegRuntime(excludedSources: Set<String> = emptySet()): FfmpegRuntime {
        runCatching { App.instance.ensureRuntimeToolsInstalled() }
            .onFailure { Log.w(TAG, "Failed to ensure bundled runtime tools before ffmpeg runtime resolution", it) }
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val extractedLibDir = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        val executableCandidates = listOf(
            File(nativeLibDir, "libffmpeg.so") to "wrapper-native-libffmpeg"
        )

        val selected = executableCandidates.firstOrNull { (file, source) ->
            if (source in excludedSources || source in hardSubDisabledFfmpegSources) return@firstOrNull false
            isUsableFfmpegExecutable(file)
        } ?: executableCandidates.first()
        val executable = selected.first
        val source = selected.second

        val libraryDirs = mutableListOf<String>()
        if (extractedLibDir.exists() && extractedLibDir.isDirectory) {
            libraryDirs.add(extractedLibDir.absolutePath)
        }
        val preloadLibraryPath: String? = null
        val linkerPath: String? = null
        return FfmpegRuntime(
            executablePath = executable.absolutePath,
            linkerPath = linkerPath,
            libraryPath = libraryDirs.distinct().joinToString(":").ifBlank { null },
            preloadLibraryPath = preloadLibraryPath,
            source = source
        )
    }

    private fun resolveSystemLinkerPath(): String? {
        val preferred = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "/system/bin/linker64" else "/system/bin/linker"
        val preferredFile = File(preferred)
        if (preferredFile.exists() && preferredFile.isFile) return preferredFile.absolutePath
        return listOf("/system/bin/linker64", "/system/bin/linker")
            .map(::File)
            .firstOrNull { it.exists() && it.isFile }
            ?.absolutePath
    }

    private fun systemLibrarySearchDirs(): List<String> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val candidates = mutableListOf<String>()
        if (abi.contains("64")) {
            listOf(
                "/apex/com.android.runtime/lib64",
                "/system/lib64",
                "/vendor/lib64"
            )
        } else {
            listOf(
                "/apex/com.android.runtime/lib",
                "/system/lib",
                "/vendor/lib"
            )
        }.let { candidates.addAll(it) }
        // Optional: reuse Termux-provided runtime libs when available.
        candidates.addAll(
            listOf(
                "/data/data/com.termux/files/usr/lib",
                "/data/user/0/com.termux/files/usr/lib"
            )
        )
        return candidates.filter { path ->
            val dir = File(path)
            dir.exists() && dir.isDirectory
        }
    }

    private fun buildFfmpegProcess(runtime: FfmpegRuntime, args: List<String>): ProcessBuilder {
        val command = mutableListOf<String>()
        runtime.linkerPath?.let { command.add(it) }
        command.add(runtime.executablePath)
        command.addAll(args)
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        configureFfmpegEnvironment(builder)
        runtime.libraryPath?.let { libs ->
            val env = builder.environment()
            val current = env["LD_LIBRARY_PATH"].orEmpty()
            env["LD_LIBRARY_PATH"] = if (current.isBlank()) libs else "$libs:$current"
            runtime.preloadLibraryPath?.let { preload ->
                val currentPreload = env["LD_PRELOAD"].orEmpty()
                env["LD_PRELOAD"] = if (currentPreload.isBlank()) preload else "$preload:$currentPreload"
            }
        }
        return builder
    }

    private fun configureFfmpegEnvironment(builder: ProcessBuilder) {
        val env = builder.environment()
        val fontconfigDir = File(context.cacheDir, "fontconfig")
        val fontconfigCacheDir = File(fontconfigDir, "cache")
        val fontsConfig = File(fontconfigDir, "fonts.conf")
        runCatching {
            fontconfigCacheDir.mkdirs()
            if (!fontsConfig.exists()) {
                fontsConfig.writeText(
                    """
                    <?xml version="1.0"?>
                    <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
                    <fontconfig>
                        <dir>/system/fonts</dir>
                        <cachedir>${fontconfigCacheDir.absolutePath}</cachedir>
                    </fontconfig>
                    """.trimIndent(),
                    Charsets.UTF_8
                )
            }
            env["FONTCONFIG_FILE"] = fontsConfig.absolutePath
            env["FONTCONFIG_PATH"] = fontconfigDir.absolutePath
            env["XDG_CACHE_HOME"] = context.cacheDir.absolutePath
            env["HOME"] = context.filesDir.absolutePath
        }.onFailure { error ->
            Log.w(TAG, "HardSub fontconfig environment setup failed reason=${error.message}")
        }
    }

    private fun executeFfmpegWithAutoPatch(
        runtime: FfmpegRuntime,
        args: List<String>,
        progressTarget: FfmpegProgressTarget? = null
    ): FfmpegExecResult {
        var activeRuntime = runtime
        if (activeRuntime.source in hardSubDisabledFfmpegSources) {
            activeRuntime = resolveFfmpegRuntime(setOf(activeRuntime.source))
        }
        var last = runFfmpeg(activeRuntime, args, progressTarget)
        if (last.exitCode == 0) return last

        if (last.output.contains("Permission denied", ignoreCase = true)) {
            hardSubDisabledFfmpegSources.add(activeRuntime.source)
            val fallback = resolveFfmpegRuntime(setOf(activeRuntime.source))
            if (fallback.executablePath != activeRuntime.executablePath) {
                Log.w(
                    TAG,
                    "HardSub ffmpeg permission denied source=${activeRuntime.source}; fallback source=${fallback.source}"
                )
                activeRuntime = fallback
                last = runFfmpeg(activeRuntime, args, progressTarget)
                if (last.exitCode == 0) return last
            }
        }

        if (isLikelyInvalidFfmpegBinaryOutput(last.output)) {
            hardSubDisabledFfmpegSources.add(activeRuntime.source)
            val fallback = resolveFfmpegRuntime(setOf(activeRuntime.source))
            if (fallback.executablePath != activeRuntime.executablePath) {
                Log.w(
                    TAG,
                    "HardSub ffmpeg invalid runtime output source=${activeRuntime.source}; fallback source=${fallback.source}"
                )
                activeRuntime = fallback
                last = runFfmpeg(activeRuntime, args, progressTarget)
                if (last.exitCode == 0) return last
            }
        }

        val patchedLibs = mutableSetOf<String>()
        repeat(20) {
            val missingLib = extractMissingRuntimeLibraryName(last.output).orEmpty()
            if (missingLib.isBlank()) return last
            if (!patchedLibs.add(missingLib)) return last
            if (!patchMissingRuntimeLibrary(missingLib)) return last

            Log.w(TAG, "HardSub ffmpeg retry after runtime patch lib=$missingLib")
            last = runFfmpeg(activeRuntime, args, progressTarget)
            if (last.exitCode == 0) return last
        }

        return last
    }

    private fun extractMissingRuntimeLibraryName(output: String): String? {
        Regex("""library "([^"]+)" not found""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        Regex("""cannot find "([^"]+)" from verneed""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        Regex(""""([^"]+)" is too small to be an ELF executable""")
            .find(output)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                return try {
                    File(raw).name.takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            }

        return null
    }

    private fun isUsableFfmpegExecutable(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        if (!file.canExecute()) return false
        if (!file.name.endsWith(".so")) return true
        return hasElfHeader(file)
    }

    private fun hasElfHeader(file: File): Boolean {
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                val read = input.read(header)
                read == 4 &&
                    header[0] == 0x7F.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun isLikelyInvalidFfmpegBinaryOutput(output: String): Boolean {
        val lowered = output.lowercase(Locale.US)
        return lowered.contains("exec format error") ||
            lowered.contains("syntax error: unexpected") ||
            Regex("""(?m)\[\d+]:\s*PK""").containsMatchIn(output)
    }

    private fun needsExplicitWebmEncoder(output: String): Boolean {
        val lowered = output.lowercase(Locale.US)
        return lowered.contains("automatic encoder selection failed") ||
            lowered.contains("error selecting an encoder for stream")
    }

    private fun shouldTryWebmFallback(output: String): Boolean {
        if (needsExplicitWebmEncoder(output)) return true
        val lowered = output.lowercase(Locale.US)
        return lowered.contains("abi version mismatch") ||
            lowered.contains("failed to initialize encoder") ||
            lowered.contains("error while opening encoder") ||
            lowered.contains("nothing was written into output file")
    }

    private fun buildFilterCandidatesForMedia(subtitleIsAss: Boolean, supportedFilters: Set<String>): List<String> {
        if (supportedFilters.isEmpty()) {
            return if (subtitleIsAss) listOf("ass", "subtitles") else listOf("subtitles", "ass")
        }
        val candidates = mutableListOf<String>()
        if (subtitleIsAss && supportedFilters.contains("ass")) {
            candidates.add("ass")
        }
        if (supportedFilters.contains("subtitles")) {
            candidates.add("subtitles")
        }
        if (subtitleIsAss && !candidates.contains("ass") && supportedFilters.contains("ass")) {
            candidates.add("ass")
        }
        return candidates.distinct()
    }

    private fun probeSubtitleFilters(runtime: FfmpegRuntime): Set<String> {
        val cacheKey = "${runtime.source}|${runtime.executablePath}"
        hardSubFilterSupportCache[cacheKey]?.let { return it }

        val probe = executeFfmpegWithAutoPatch(
            runtime,
            listOf("-hide_banner", "-filters")
        )
        if (probe.exitCode != 0) {
            hardSubFilterSupportCache[cacheKey] = emptySet()
            return emptySet()
        }
        val output = probe.output
        val support = mutableSetOf<String>()
        if (Regex("""(?m)^\s*[.A-Z]{3}\s+ass\s""").containsMatchIn(output)) {
            support.add("ass")
        }
        if (Regex("""(?m)^\s*[.A-Z]{3}\s+subtitles\s""").containsMatchIn(output)) {
            support.add("subtitles")
        }
        hardSubFilterSupportCache[cacheKey] = support
        return support
    }

    private fun runFfmpeg(
        runtime: FfmpegRuntime,
        args: List<String>,
        progressTarget: FfmpegProgressTarget? = null
    ): FfmpegExecResult {
        return runCatching {
            val process = buildFfmpegProcess(runtime, args).start()
            val processSet = progressTarget?.downloadItemId?.let { downloadItemId ->
                runningFfmpegProcesses.computeIfAbsent(downloadItemId) { ConcurrentHashMap.newKeySet() }
            }
            processSet?.add(process)
            val outputBuilder = StringBuilder()
            val startedAt = System.currentTimeMillis()
            var lastLiveLogAt = 0L
            var detectedDurationSec: Double? = null
            var lastProgressPercent = -1
            var lastProgressPostAt = 0L
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    outputBuilder.append(line).append('\n')
                    val now = System.currentTimeMillis()
                    if (shouldLogFfmpegLiveLine(line, now, lastLiveLogAt)) {
                        Log.i(TAG, "HardSub ffmpeg live source=${runtime.source} line=$line")
                        lastLiveLogAt = now
                    }
                    if (progressTarget != null) {
                        parseDurationSecondsFromFfmpegLine(line)?.let { parsed ->
                            if (parsed > 0.0) detectedDurationSec = parsed
                        }
                        val currentSec = parseCurrentSecondsFromFfmpegLine(line)
                        val totalSec = detectedDurationSec
                        if (currentSec != null && totalSec != null && totalSec > 0.0) {
                            val percent = ((currentSec / totalSec) * 100.0).toInt().coerceIn(1, 99)
                            if (percent > lastProgressPercent && (now - lastProgressPostAt) >= 700L) {
                                EventBus.getDefault().post(
                                    WorkerProgress(
                                        percent,
                                        "Burning subtitles $percent%",
                                        progressTarget.downloadItemId,
                                        progressTarget.logItemId
                                    )
                                )
                                lastProgressPercent = percent
                                lastProgressPostAt = now
                            }
                        }
                    }
                }
            }
            try {
                val exitCode = process.waitFor()
                val elapsed = System.currentTimeMillis() - startedAt
                Log.i(TAG, "HardSub ffmpeg end source=${runtime.source} code=$exitCode elapsedMs=$elapsed")
                FfmpegExecResult(exitCode = exitCode, output = outputBuilder.toString())
            } finally {
                processSet?.remove(process)
                progressTarget?.downloadItemId?.let { downloadItemId ->
                    if (processSet?.isEmpty() == true) runningFfmpegProcesses.remove(downloadItemId)
                }
            }
        }.getOrElse { error ->
            Log.e(TAG, "HardSub ffmpeg process start failed source=${runtime.source}", error)
            FfmpegExecResult(exitCode = 1, output = error.message ?: error.toString())
        }
    }

    private fun parseDurationSecondsFromFfmpegLine(line: String): Double? {
        val match = Regex("""Duration:\s*([0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?)""").find(line) ?: return null
        return parseClockToSeconds(match.groupValues[1])
    }

    private fun parseCurrentSecondsFromFfmpegLine(line: String): Double? {
        val match = Regex("""time=([0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?)""").find(line) ?: return null
        return parseClockToSeconds(match.groupValues[1])
    }

    private fun parseClockToSeconds(value: String): Double? {
        val parts = value.split(":")
        if (parts.size != 3) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val second = parts[2].toDoubleOrNull() ?: return null
        return hour * 3600.0 + minute * 60.0 + second
    }

    private fun shouldLogFfmpegLiveLine(line: String, now: Long, lastLoggedAt: Long): Boolean {
        if (line.isBlank()) return false
        val lowered = line.lowercase(Locale.US)
        val important = lowered.contains("frame=") ||
            lowered.contains("time=") ||
            lowered.contains("speed=") ||
            lowered.contains("stream mapping") ||
            lowered.contains("error") ||
            lowered.contains("failed") ||
            lowered.contains("conversion failed") ||
            lowered.contains("press [q]")
        if (!important) return false
        if (lastLoggedAt == 0L) return true
        return (now - lastLoggedAt) >= 1200L
    }

    private fun patchMissingRuntimeLibrary(missingLibName: String): Boolean {
        val ffmpegLibDir = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        if (!ffmpegLibDir.exists() || !ffmpegLibDir.isDirectory) return false

        val target = File(ffmpegLibDir, missingLibName)
        if (target.exists()) {
            if (hasElfHeader(target)) return true
            val normalized = resolveAliasLibraryTarget(target)
            if (normalized != null) {
                return runCatching {
                    normalized.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.setReadable(true, true)
                    Log.i(TAG, "HardSub ffmpeg runtime normalized alias lib=${target.name} source=${normalized.name}")
                    true
                }.getOrDefault(false)
            }
        }

        val baseName = missingLibName.substringBefore(".so", missingLibName) + ".so"
        val inPayload = selectBestLibraryCandidate(
            ffmpegLibDir.listFiles().orEmpty().filter { it.isFile },
            missingLibName,
            baseName
        )
        if (inPayload != null) {
            return runCatching {
                inPayload.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.setReadable(true, true)
                Log.i(TAG, "HardSub ffmpeg runtime patched lib=${target.name} source=${inPayload.absolutePath}")
                true
            }.getOrDefault(false)
        }

        val searchDirs = buildList {
            addAll(systemLibrarySearchDirs())
            add(context.applicationInfo.nativeLibraryDir)
        }.distinct()

        val source = findLibrarySource(searchDirs, missingLibName, baseName)
            ?: run {
                Log.w(TAG, "HardSub ffmpeg runtime patch source not found for $missingLibName")
                return false
            }

        return runCatching {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, true)
            Log.i(TAG, "HardSub ffmpeg runtime patched lib=${target.name} source=${source.absolutePath}")
            true
        }.getOrElse {
            Log.e(TAG, "HardSub ffmpeg runtime patch failed lib=$missingLibName", it)
            false
        }
    }

    private fun selectBestLibraryCandidate(candidates: List<File>, missingLibName: String, baseName: String): File? {
        val elfCandidates = candidates.filter { hasElfHeader(it) }
        if (elfCandidates.isEmpty()) return null

        elfCandidates.firstOrNull { it.name == missingLibName }?.let { return it }
        elfCandidates.firstOrNull { it.name.startsWith("$missingLibName.") }?.let { return it }

        val major = Regex("""\.so\.(\d+)$""")
            .find(missingLibName)
            ?.groupValues
            ?.getOrNull(1)
        if (major != null) {
            elfCandidates.firstOrNull { it.name.matches(Regex("^${Regex.escape(baseName)}\\.${Regex.escape(major)}(\\..+)?$")) }
                ?.let { return it }
        }

        return elfCandidates.firstOrNull { it.name.startsWith("$baseName.") }
            ?: elfCandidates.firstOrNull { it.name == baseName }
    }

    private fun resolveAliasLibraryTarget(aliasFile: File): File? {
        if (!aliasFile.exists() || !aliasFile.isFile) return null
        if (hasElfHeader(aliasFile)) return aliasFile
        return runCatching {
            val targetName = aliasFile.readBytes()
                .toString(Charsets.UTF_8)
                .replace("\u0000", "")
                .trim()
            if (!targetName.matches(Regex("^[A-Za-z0-9._+\\-]+$"))) return null
            val target = File(aliasFile.parentFile, targetName)
            if (!target.exists() || !target.isFile) return null
            if (!hasElfHeader(target)) return null
            target
        }.getOrNull()
    }

    private fun findLibrarySource(searchDirs: List<String>, missingLibName: String, baseName: String): File? {
        val direct = searchDirs
            .asSequence()
            .map(::File)
            .filter { it.exists() && it.isDirectory }
            .mapNotNull { dir ->
                sequenceOf(
                    File(dir, missingLibName),
                    File(dir, baseName)
                ).firstOrNull { it.exists() && it.isFile }
                    ?: dir.listFiles().orEmpty().firstOrNull { it.isFile && it.name.startsWith(baseName) }
            }
            .firstOrNull()
        if (direct != null) return direct

        val recursiveRoots = listOf("/apex", "/system", "/vendor")
            .map(::File)
            .filter { it.exists() && it.isDirectory }
        recursiveRoots.forEach { root ->
            root.walkTopDown()
                .onEnter { dir ->
                    // Limit traversal depth to keep patch lookup fast on-device.
                    val depth = dir.absolutePath.count { it == '/' } - root.absolutePath.count { it == '/' }
                    depth <= 6
                }
                .firstOrNull { file ->
                    file.isFile && (file.name == missingLibName || file.name == baseName || file.name.startsWith(baseName))
                }?.let { return it }
        }
        return null
    }

    private fun convertSrv3ToAssWithDedicatedConverter(subtitle: File, converterPath: String): File? {
        val output = createHardSubTempAssFile()
        return runDedicatedSubtitleConverter(
            input = subtitle,
            output = output,
            converterPath = converterPath,
            format = "ass",
            startFailLogPrefix = "Dedicated srv3->ass converter start failed",
            failLogPrefix = "Dedicated srv3->ass converter failed"
        )
    }

    private fun convertSubtitleToSrtWithDedicatedConverter(subtitle: File, converterPath: String): File? {
        val parent = subtitle.parentFile ?: return null
        val output = File(parent, "${subtitle.nameWithoutExtension}.srt")
        if (output.exists() && output.length() > 0L) return output
        runDedicatedSubtitleConverter(
            input = subtitle,
            output = output,
            converterPath = converterPath,
            format = "srt",
            startFailLogPrefix = "Dedicated subtitle->srt converter start failed",
            failLogPrefix = "Dedicated subtitle->srt converter failed"
        )?.let { return it }

        val ext = subtitle.extension.lowercase(Locale.US)
        if (ext !in setOf("srv3", "json3", "ttml")) return null

        createNormalizedRichSubtitleForConverter(subtitle)?.let { normalized ->
            try {
                runDedicatedSubtitleConverter(
                    input = normalized,
                    output = output,
                    converterPath = converterPath,
                    format = "srt",
                    startFailLogPrefix = "Dedicated subtitle->srt converter (normalized) start failed",
                    failLogPrefix = "Dedicated subtitle->srt converter (normalized) failed"
                )?.let {
                    Log.i(
                        TAG,
                        "HardSub dedicated subtitle->srt conversion succeeded after normalization source=${subtitle.name}"
                    )
                    return it
                }
            } finally {
                runCatching { normalized.delete() }
            }
        }
        return null
    }

    private fun runDedicatedSubtitleConverter(
        input: File,
        output: File,
        converterPath: String,
        format: String,
        startFailLogPrefix: String,
        failLogPrefix: String
    ): File? {
        val process = runCatching {
            ProcessBuilder(
                converterPath,
                "parse",
                input.absolutePath,
                "--format",
                format,
                "--save",
                "file",
                "--output",
                output.absolutePath
            ).redirectErrorStream(true).start()
        }.getOrElse { error ->
            Log.w(
                TAG,
                "$startFailLogPrefix path=$converterPath source=${input.name} reason=${error.message}"
            )
            return null
        }

        val converterOutput = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0 || !output.exists() || output.length() == 0L) {
            if (output.exists()) output.delete()
            Log.w(TAG, "$failLogPrefix (code=$exitCode): ${converterOutput.takeLast(800)}")
            return null
        }
        return output
    }

    private fun createNormalizedRichSubtitleForConverter(subtitle: File): File? {
        val ext = subtitle.extension.lowercase(Locale.US)
        if (ext !in setOf("srv3", "json3", "ttml")) return null
        val parent = subtitle.parentFile ?: return null
        val raw = runCatching { subtitle.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        if (raw.isBlank()) return null

        var normalized = raw
        // Remove empty rich-sub nodes that can cause strict parsers to fail on missing value fields.
        normalized = normalized.replace(Regex("""<s(\s+[^>]*)?/\s*>"""), "")
        normalized = normalized.replace(Regex("""<s(\s+[^>]*)?>\s*</s>"""), "")
        normalized = normalized.replace(Regex("""<p(\s+[^>]*)?>\s*</p>"""), "")

        if (normalized == raw) return null
        val normalizedFile = File(parent, "__hardsub_normalized.$ext")
        return runCatching {
            normalizedFile.writeText(normalized, Charsets.UTF_8)
            Log.i(TAG, "HardSub rich subtitle normalized source=${subtitle.name} output=${normalizedFile.name}")
            normalizedFile
        }.getOrNull()
    }

    private fun createHardSubTempAssFile(): File {
        val dir = File(context.cacheDir, "hardsub")
        val parent = if (dir.mkdirs() || dir.isDirectory) dir else context.cacheDir
        return File(parent, "ytdlnisx_hardsub_${java.util.UUID.randomUUID()}.ass")
    }

    private fun convertSubtitleFilesToSrt(
        subtitleFiles: List<File>,
        ffmpegRuntime: FfmpegRuntime,
        dedicatedSrv3ConverterPath: String?
    ) {
        subtitleFiles.forEach { subtitle ->
            val ext = subtitle.extension.lowercase(Locale.US)
            if (ext == "srt") return@forEach
            val target = File(subtitle.parentFile ?: return@forEach, "${subtitle.nameWithoutExtension}.srt")
            if (target.exists() && target.length() > 0L) return@forEach

            val converted = if (ext in setOf("json", "json3")) {
                SubtitleFormatConverter.convertJson3ToSrt(subtitle)
            } else if (dedicatedSrv3ConverterPath != null && ext in setOf("srv3", "json3", "ttml")) {
                convertSubtitleToSrtWithDedicatedConverter(subtitle, dedicatedSrv3ConverterPath)
            } else {
                val result = executeFfmpegWithAutoPatch(
                    ffmpegRuntime,
                    listOf(
                        "-y",
                        "-i",
                        subtitle.absolutePath,
                        target.absolutePath
                    )
                )
                if (result.exitCode != 0 || !target.exists() || target.length() == 0L) {
                    if (target.exists()) target.delete()
                    null
                } else {
                    target
                }
            }

            if (converted != null) {
                Log.i(TAG, "HardSub subtitle sidecar converted to srt source=${subtitle.name} output=${converted.name}")
            } else {
                Log.w(TAG, "HardSub subtitle sidecar srt conversion failed source=${subtitle.name}")
            }
        }
    }

    private fun resolveSrv3ConverterPath(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val configuredPath = prefs.getString("hard_sub_srv3_converter_path", "").orEmpty().trim()
        if (configuredPath.isNotEmpty()) {
            val configuredFile = File(configuredPath)
            if (configuredFile.exists() && configuredFile.canExecute()) return configuredFile.absolutePath
        }

        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val nativeCandidates = listOf(
            File(nativeLibDir, "libyttml_exec.so"),
            File(nativeLibDir, "libyttml.so")
        )
        nativeCandidates.firstOrNull { it.exists() && it.isFile }?.let { candidate ->
            Log.i(TAG, "HardSub using native yttml converter path=${candidate.absolutePath}")
            return candidate.absolutePath
        }

        val bundledCandidate = File(context.filesDir, "bin/yttml")
        if (bundledCandidate.exists() && bundledCandidate.canExecute()) {
            // filesDir can be mounted noexec on some devices/ROMs; avoid hard failure path.
            val binaryDir = File(context.filesDir, "bin")
            if (isPathInsideDirectory(bundledCandidate.absolutePath, binaryDir)) {
                Log.w(TAG, "HardSub yttml bundled path may be noexec; skip dedicated converter path=${bundledCandidate.absolutePath}")
            } else {
                return bundledCandidate.absolutePath
            }
        }

        return null
    }

    private fun findSubtitleCandidatesForMedia(
        media: File,
        subtitleExts: List<String>,
        providedSubtitles: List<File>,
        subtitleRequest: SubtitleSelection.Request
    ): List<File> {
        val parent = media.parentFile ?: return emptyList()
        val prefix = "${media.nameWithoutExtension}."
        val files = parent.listFiles().orEmpty()
        val allCandidates = (files.asList() + providedSubtitles)
            .asSequence()
            .filter { it.isFile }
            .filter { SubtitleSelection.isSelectedSubtitleFile(it, subtitleRequest) }
            .distinctBy { it.absolutePath }
            .toList()

        val orderedByExt = mutableListOf<File>()
        subtitleExts.forEach { ext ->
            allCandidates
                .asSequence()
                .filter { candidate ->
                    candidate.name.startsWith(prefix) &&
                        candidate.extension.equals(ext, ignoreCase = true)
                }
                .sortedByDescending { candidate -> candidate.lastModified() }
                .forEach { candidate ->
                    if (orderedByExt.none { it.absolutePath == candidate.absolutePath }) {
                        orderedByExt.add(candidate)
                    }
                }
        }
        if (orderedByExt.isNotEmpty()) return orderedByExt

        val sameDirSubtitles = allCandidates.filter { candidate ->
            candidate.parentFile?.absolutePath == parent.absolutePath &&
                subtitleExts.any { ext -> candidate.extension.equals(ext, ignoreCase = true) }
        }
        if (sameDirSubtitles.size == 1) {
            Log.w(TAG, "HardSub subtitle fallback media=${media.name} matched=${sameDirSubtitles.first().name} reason=single-subtitle-in-dir")
            return listOf(sameDirSubtitles.first())
        }
        if (providedSubtitles.size == 1) {
            val only = providedSubtitles.first()
            Log.w(TAG, "HardSub subtitle fallback media=${media.name} matched=${only.name} reason=single-provided-subtitle")
            return listOf(only)
        }
        return emptyList()
    }

    private fun findSiblingMediaForSubtitles(subtitleFiles: List<File>, subtitleExts: List<String>): List<File> {
        val recovered = linkedSetOf<File>()
        subtitleFiles.forEach { subtitle ->
            val parent = subtitle.parentFile ?: return@forEach
            val subtitleStem = subtitle.nameWithoutExtension
            val stemWithoutLang = subtitleStem.substringBeforeLast('.', subtitleStem)
            parent.listFiles().orEmpty().forEach { candidate ->
                if (!candidate.isFile) return@forEach
                val isSubtitle = subtitleExts.any { ext -> candidate.extension.equals(ext, ignoreCase = true) }
                if (isSubtitle) return@forEach
                val mediaStem = candidate.nameWithoutExtension
                if (mediaStem == subtitleStem || mediaStem == stemWithoutLang) {
                    recovered.add(candidate)
                }
            }
        }
        return recovered.toList()
    }

    private fun resolvePreviousHistoryMediaPaths(
        downloadItem: DownloadItem,
        historyDao: com.ireum.ytdl.database.dao.HistoryDao
    ): List<String> {
        val historyId = downloadItem.playlistURL
            ?.takeIf { it.startsWith(HISTORY_REDOWNLOAD_MARKER) }
            ?.removePrefix(HISTORY_REDOWNLOAD_MARKER)
            ?.toLongOrNull()
            ?: return emptyList()
        val previous = runCatching { historyDao.getItem(historyId) }.getOrNull() ?: return emptyList()
        return previous.downloadPath
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { path ->
                val file = File(path)
                file.exists() && file.isFile
            }
            .toList()
    }

    private fun deleteReplacedHistoryMedia(previousHistoryItem: HistoryItem?, finalPaths: List<String>) {
        if (previousHistoryItem == null) return

        val retainedPaths = finalPaths
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        if (retainedPaths.isEmpty()) return

        val stalePaths = previousHistoryItem.downloadPath
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { oldPath -> oldPath !in retainedPaths }
            .toList()
        if (stalePaths.isEmpty()) return

        stalePaths.forEach { oldPath ->
            runCatching { FileUtil.deleteFile(oldPath) }
                .onSuccess {
                    Log.i(TAG, "HardSub redownload cleanup deleted old media path=$oldPath")
                }
                .onFailure { error ->
                    Log.w(TAG, "HardSub redownload cleanup failed path=$oldPath reason=${error.message}")
            }
        }
    }

    private fun findExistingHistoryForDownloadedItem(
        downloadItem: DownloadItem,
        historyDao: com.ireum.ytdl.database.dao.HistoryDao
    ): HistoryItem? {
        return equivalentDownloadUrls(downloadItem.url)
            .flatMap { url -> historyDao.getItemsByUrl(url) }
            .distinctBy { it.id }
            .firstOrNull { item ->
                item.type == downloadItem.type &&
                    item.downloadPath.any { path -> FileUtil.exists(path) }
            }
    }

    private fun canonicalDownloadUrl(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.isYoutubeURL()) return trimmed
        val id = trimmed.getIDFromYoutubeURL() ?: return trimmed
        return "https://youtu.be/$id"
    }

    private fun equivalentDownloadUrls(url: String): List<String> {
        val canonical = canonicalDownloadUrl(url)
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

    private suspend fun retryMoveFromTempDirectory(
        tempFileDir: File,
        downloadLocation: String,
        keepCache: Boolean,
        downloadItemId: Long,
        downloadLogId: Long?,
        eventBus: EventBus
    ): List<String> {
        return runCatching {
            withContext(Dispatchers.IO) {
                FileUtil.moveFile(tempFileDir, context, downloadLocation, keepCache) { progress ->
                    eventBus.post(
                        WorkerProgress(
                            progress,
                            "Retrying move to ${FileUtil.formatPath(downloadLocation)}",
                            downloadItemId,
                            downloadLogId
                        )
                    )
                }
            }.filter { !it.matches("\\.(description)|(txt)\$".toRegex()) }
        }.onSuccess { recovered ->
            if (recovered.isNotEmpty()) {
                Log.w(
                    TAG,
                    "HardSub temp move retry succeeded id=$downloadItemId recovered=${recovered.size}"
                )
            }
        }.onFailure { error ->
            Log.e(TAG, "HardSub temp move retry failed id=$downloadItemId", error)
        }.getOrDefault(emptyList())
    }

    private data class BurnInSubtitle(
        val file: File,
        val isAss: Boolean,
        val isTemporary: Boolean
    )

    private data class CanonicalSubtitle(
        val file: File,
        val isTemporary: Boolean
    )

    private fun createCanonicalHardSubSubtitle(subtitleFiles: List<File>, subtitleExts: List<String>): CanonicalSubtitle? {
        if (subtitleFiles.isEmpty()) return null
        val priority = subtitleExts.withIndex().associate { it.value.lowercase(Locale.US) to it.index }
        val selected = subtitleFiles.sortedWith(
            compareBy<File> { file -> priority[file.extension.lowercase(Locale.US)] ?: Int.MAX_VALUE }
                .thenByDescending { file -> file.lastModified() }
        ).first()
        val parent = selected.parentFile ?: return CanonicalSubtitle(selected, isTemporary = false)
        val canonical = File(parent, "__hardsub_input.${selected.extension.lowercase(Locale.US)}")
        if (selected.absolutePath == canonical.absolutePath) {
            return CanonicalSubtitle(selected, isTemporary = false)
        }
        return runCatching {
            selected.copyTo(canonical, overwrite = true)
            Log.i(TAG, "HardSub subtitle canonicalized from=${selected.name} to=${canonical.name}")
            CanonicalSubtitle(canonical, isTemporary = true)
        }.getOrElse {
            Log.w(TAG, "HardSub subtitle canonicalize failed source=${selected.name} reason=${it.message}")
            CanonicalSubtitle(selected, isTemporary = false)
        }
    }

    private fun deleteSubtitleSidecars(media: File, subtitleExts: List<String>) {
        val parent = media.parentFile ?: return
        val prefix = "${media.nameWithoutExtension}."
        parent.listFiles().orEmpty().forEach { file ->
            val isSubtitle = subtitleExts.any { ext -> file.extension.equals(ext, ignoreCase = true) }
            if (file.isFile && file.name.startsWith(prefix) && isSubtitle) {
                file.delete()
            }
        }
    }

    private fun extractPathsFromYtdlpOutput(output: String): List<String> {
        val lines = output.lines()
        val paths = mutableListOf<String>()

        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.contains("Destination: ") -> {
                    val path = trimmed.substringAfter("Destination: ").trim().trim('"', '\'')
                    if (path.startsWith("/")) paths.add(path)
                }
                trimmed.contains("Merging formats into ") -> {
                    val path = trimmed.substringAfter("Merging formats into ").trim().trim('"', '\'')
                    if (path.startsWith("/")) paths.add(path)
                }
                trimmed.startsWith("'/") && trimmed.endsWith("'") -> {
                    paths.add(trimmed.trim('\''))
                }
            }
        }
        return paths
    }

    private fun recoverPathsFromDirectory(downloadLocation: String, startedAtMillis: Long): List<String> {
        val dir = File(downloadLocation)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val minTime = startedAtMillis - 120_000L
        val rootDepth = dir.absolutePath.count { it == File.separatorChar }
        val scopedFiles = dir.walkTopDown()
            .onEnter { subDir ->
                val depth = subDir.absolutePath.count { it == File.separatorChar } - rootDepth
                depth <= 4
            }
            .asSequence()
            .filter { it.isFile }
            .toList()

        val recent = scopedFiles
            .asSequence()
            .filter { it.lastModified() >= minTime }
            .map { it.absolutePath }
            .sortedBy { File(it).lastModified() }
            .toList()
        return recent
    }

    private fun recoverAllPathsFromDirectory(downloadLocation: String): List<String> {
        val dir = File(downloadLocation)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val rootDepth = dir.absolutePath.count { it == File.separatorChar }
        return dir.walkTopDown()
            .onEnter { subDir ->
                val depth = subDir.absolutePath.count { it == File.separatorChar } - rootDepth
                depth <= 4
            }
            .asSequence()
            .filter { it.isFile }
            .map { it.absolutePath }
            .sortedBy { File(it).lastModified() }
            .toList()
    }

    private fun recoverPathsByFileNames(downloadLocation: String, fileNames: List<String>): List<String> {
        val dir = File(downloadLocation)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        if (fileNames.isEmpty()) return emptyList()
        val wanted = fileNames.toSet()
        val rootDepth = dir.absolutePath.count { it == File.separatorChar }
        return dir.walkTopDown()
            .onEnter { subDir ->
                val depth = subDir.absolutePath.count { it == File.separatorChar } - rootDepth
                depth <= 5
            }
            .asSequence()
            .filter { it.isFile && wanted.contains(it.name) }
            .map { it.absolutePath }
            .distinct()
            .toList()
    }

    private fun logPathCandidates(label: String, downloadId: Long, paths: List<String>) {
        if (paths.isEmpty()) {
            Log.i(TAG, "$label id=$downloadId count=0")
            return
        }
        val sample = paths.joinToString(limit = 5) { candidate ->
            val file = File(candidate)
            val exists = file.exists()
            val size = if (exists && file.isFile) file.length() else -1L
            "${file.name}[exists=$exists,size=$size]"
        }
        Log.i(TAG, "$label id=$downloadId count=${paths.size} sample=$sample")
    }

    private fun describeDirectorySnapshot(directory: File?): String {
        if (directory == null) return "<null>"
        if (!directory.exists()) return "${directory.absolutePath}[missing]"
        if (!directory.isDirectory) return "${directory.absolutePath}[not-directory]"

        val entries = runCatching {
            directory.walkTopDown()
                .maxDepth(2)
                .filter { it.isFile }
                .sortedByDescending { it.lastModified() }
                .take(8)
                .map { file ->
                    val relative = runCatching {
                        file.absolutePath.removePrefix(directory.absolutePath).trimStart(File.separatorChar)
                    }.getOrDefault(file.name)
                    "$relative(size=${file.length()},mtime=${file.lastModified()})"
                }
                .toList()
        }.getOrDefault(emptyList())

        return "${directory.absolutePath}[files=${entries.size}${if (entries.isNotEmpty()) ",sample=${entries.joinToString()}" else ""}]"
    }

    private fun shouldForceHardSubFailpoint(markerName: String): Boolean {
        val marker = File(FileUtil.getCachePath(context), "debug/$markerName")
        val enabled = marker.exists()
        if (enabled) {
            Log.w(TAG, "HardSub failpoint enabled marker=${marker.absolutePath}")
        }
        return enabled
    }

    private fun remapPathsForBurnIn(paths: List<String>, downloadLocation: String, tempLocation: String): List<String> {
        val downloadDir = File(downloadLocation)
        val tempDir = File(tempLocation)
        return paths
            .asSequence()
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotBlank() }
            .mapNotNull { raw ->
                val direct = File(raw)
                if (direct.exists() && direct.isFile) return@mapNotNull direct.absolutePath

                val fileName = direct.name
                if (fileName.isBlank()) return@mapNotNull null

                val fromDownload = File(downloadDir, fileName)
                if (fromDownload.exists() && fromDownload.isFile) return@mapNotNull fromDownload.absolutePath

                val fromTemp = File(tempDir, fileName)
                if (fromTemp.exists() && fromTemp.isFile) return@mapNotNull fromTemp.absolutePath

                null
            }
            .distinct()
            .toList()
    }

    private fun isPathInsideDirectory(path: String, directory: File): Boolean {
        return runCatching {
            val normalizedPath = File(path).canonicalFile.toPath().normalize()
            val normalizedDirectory = directory.canonicalFile.toPath().normalize()
            normalizedPath.startsWith(normalizedDirectory)
        }.getOrDefault(false)
    }

    private fun prioritizePrimaryMediaPath(paths: List<String>, downloadType: DownloadType): MutableList<String> {
        val normalized = paths
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()
        if (normalized.size <= 1) return normalized

        val primary = selectPrimaryMediaPath(normalized, downloadType) ?: return normalized
        if (normalized.firstOrNull() == primary) return normalized

        val reordered = mutableListOf(primary)
        reordered.addAll(normalized.filterNot { it == primary })
        return reordered
    }

    private fun selectPrimaryMediaPath(paths: List<String>, downloadType: DownloadType): String? {
        val files = paths
            .map { File(it) }
            .filter { it.exists() && it.isFile }
        if (files.isEmpty()) return null

        if (downloadType == DownloadType.video) {
            files.firstOrNull { fileHasVideoTrack(it) }?.let { return it.absolutePath }
            files.maxByOrNull { it.length() }?.let { return it.absolutePath }
        }
        return files.first().absolutePath
    }

    private fun fileHasVideoTrack(file: File): Boolean {
        var retriever: MediaMetadataRetriever? = null
        return runCatching {
            retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
            val hasVideo = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                ?.lowercase(Locale.US)
                ?.let { it == "yes" || it == "1" || it == "true" } ?: false
            if (hasVideo) return@runCatching true
            val width = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            width > 0 && height > 0
        }.getOrDefault(false).also {
            runCatching { retriever?.release() }
        }
    }

    private fun fileHasAudioTrack(file: File): Boolean {
        var retriever: MediaMetadataRetriever? = null
        return runCatching {
            retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                ?.lowercase(Locale.US)
                ?.let { it == "yes" || it == "1" || it == "true" } ?: false
        }.getOrDefault(false).also {
            runCatching { retriever?.release() }
        }
    }

    private fun canCreateSiblingOutput(directory: File): Boolean {
        if (!directory.exists() || !directory.isDirectory) return false
        return runCatching {
            val probe = File.createTempFile(".hardsub_probe_", ".tmp", directory)
            probe.delete()
            true
        }.getOrDefault(false)
    }

    private fun escapeForFfmpegFilterArg(path: String): String {
        return path
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "\\'")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace(",", "\\,")
            .replace(";", "\\;")
    }

    private fun buildSubtitleFilterArgs(filterName: String, path: String): List<String> {
        val escaped = escapeForFfmpegFilterArg(path)
        return if (filterName.equals("ass", ignoreCase = true)) {
            // Different ffmpeg versions parse ass filter arguments differently.
            listOf(
                "ass='$escaped'",
                "ass=$escaped",
                "ass=filename='$escaped'"
            )
        } else {
            listOf(
                "$filterName=filename='$escaped'",
                "$filterName='$escaped'"
            )
        }
    }

    private fun mergeKeywords(existing: String, appended: String): String {
        if (appended.isBlank()) return existing.trim()

        val merged = linkedSetOf<String>()
        val seen = hashSetOf<String>()

        fun addRaw(raw: String) {
            val token = raw.trim()
            if (token.isBlank()) return
            val key = token.lowercase(Locale.getDefault())
            if (seen.add(key)) merged.add(token)
        }

        existing.split(',').forEach { addRaw(it) }
        appended.split(',').forEach { addRaw(it) }

        return merged.joinToString(", ")
    }

    class WorkerProgress(
        val progress: Int,
        val output: String,
        val downloadItemID: Long,
        val logItemID: Long?
    )

}



