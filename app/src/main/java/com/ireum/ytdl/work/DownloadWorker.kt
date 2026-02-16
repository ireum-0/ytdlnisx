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
import com.ireum.ytdl.util.Extensions.getMediaDuration
import com.ireum.ytdl.util.Extensions.toStringDuration
import com.ireum.ytdl.util.Extensions.toDurationSeconds
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.text.Regex


class DownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

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



    @OptIn(ExperimentalStdlibApi::class)
    @SuppressLint("RestrictedApi")
    override suspend fun doWork(): Result {
        val workManager = WorkManager.getInstance(context)
        if (workManager.isRunning("download") || isStopped) return Result.Failure()

        setForegroundSafely()

        val notificationUtil = NotificationUtil(App.instance)
        val dbManager = DBManager.getInstance(context)
        val dao = dbManager.downloadDao
        val historyDao = dbManager.historyDao
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

        queuedItems.collectLatest { items ->
            if (this@DownloadWorker.isStopped) return@collectLatest

            runningYTDLInstances.clear()
            val activeDownloads = dao.getActiveDownloadsList()
            activeDownloads.forEach {
                runningYTDLInstances.add(it.id)
            }

            val running = ArrayList(runningYTDLInstances)
            val useScheduler = sharedPreferences.getBoolean("use_scheduler", false)
            if (items.isEmpty() && running.isEmpty()) {
                WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                return@collectLatest
            }

            if (useScheduler){
                if (items.none{it.downloadStartTime > 0L} && running.isEmpty() && !alarmScheduler.isDuringTheScheduledTime()) {
                    WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                    return@collectLatest
                }
            }

            if (priorityItemIDs.isEmpty() && !continueAfterPriorityIds) {
                WorkManager.getInstance(context).cancelWorkById(this@DownloadWorker.id)
                return@collectLatest
            }

            val concurrentDownloads = sharedPreferences.getInt("concurrent_downloads", 1) - running.size
            val baseEligibleDownloads = if (priorityItemIDs.isNotEmpty()) {
                val tmp = priorityItemIDs.take(concurrentDownloads)
                items.filter { it.id !in running && tmp.contains(it.id) }
            }else{
                items.take(concurrentDownloads).filter {  it.id !in running }
            }
            val hasRunningHardSub = activeDownloads.any { isHardSubRedownload(it) }
            val eligibleDownloads = if (hasRunningHardSub) {
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

            eligibleDownloads.forEach{downloadItem ->
                if (isHardSubRedownload(downloadItem)) {
                    registerHardSubTarget(downloadItem.id)
                    updateHardSubWorkerNotification(notificationUtil)
                }
                val notification = notificationUtil.createDownloadServiceNotification(openDownloadQueue, downloadItem.title.ifEmpty { downloadItem.url })
                notificationUtil.notify(downloadItem.id.toInt(), notification)

                CoroutineScope(Dispatchers.IO).launch {
                    val writtenPath = downloadItem.format.format_note.contains("-P ")
                    val shouldBurnHardSub = downloadItem.type == DownloadType.video && downloadItem.videoPreferences.embedSubs
                    val noCache = writtenPath || (!sharedPreferences.getBoolean("cache_downloads", true) && File(FileUtil.formatPath(downloadItem.downloadPath)).canWrite())

                    val request = ytdlpUtil.buildYoutubeDLRequest(downloadItem)

                    // DISABLED BECAUSE YT_DLP CONSIDERS DOWNLOAD FAILURE IF -U PART FAILS, ytdlnisx #1043
//                    val updateYTDLP = sharedPreferences.getBoolean("update_ytdlp_while_downloading", false)
//                    if (updateYTDLP) {
//                        request.addOption("-U")
//                    }

                    downloadItem.status = DownloadRepository.Status.Active.toString()
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(1500)
                        //update item if its incomplete
                        resultRepo.updateDownloadItem(downloadItem)?.apply {
                            val status = dao.checkStatus(this.id)
                            if (status == DownloadRepository.Status.Active){
                                dao.updateWithoutUpsert(this)
                            }
                        }
                    }

                    val cacheDir = FileUtil.getCachePath(context)
                    val tempFileDir = File(cacheDir, downloadItem.id.toString())
                    tempFileDir.delete()
                    tempFileDir.mkdirs()

                    val downloadLocation = downloadItem.downloadPath
                    val keepCache = sharedPreferences.getBoolean("keep_cache", false)
                    val noKeepSubs = sharedPreferences.getBoolean("no_keep_subs", false)
                    val logDownloads = sharedPreferences.getBoolean("log_downloads", false) && !downloadItem.incognito


                    val commandString = ytdlpUtil.parseYTDLRequestString(request)
                    val initialLogDetails = "Downloading:\n" +
                            "Title: ${downloadItem.title}\n" +
                            "URL: ${downloadItem.url}\n" +
                            "Type: ${downloadItem.type}\n" +
                            "Command:\n$commandString \n\n"
                    val logString = StringBuilder(initialLogDetails)
                    val logItem = LogItem(
                        0,
                        downloadItem.title.ifBlank { downloadItem.url },
                        logString.toString(),
                        downloadItem.format,
                        downloadItem.type,
                        System.currentTimeMillis(),
                    )


                    if (logDownloads) logItem.id = logRepo.insert(logItem)
                    downloadItem.logID = logItem.id
                    dao.update(downloadItem)

                    val eventBus = EventBus.getDefault()
                    var lastNotificationUpdateAt = 0L
                    var lastNotificationProgress = -1
                    val downloadStartedAt = System.currentTimeMillis()

                    try {
                        YoutubeDL.getInstance().destroyProcessById(downloadItem.id.toString())
                        val response = YoutubeDL.getInstance().execute(request, downloadItem.id.toString(), true){ progress, _, line ->
                            if (downloadItem.type == DownloadType.video && downloadItem.videoPreferences.embedSubs) {
                                val lowerLine = line.lowercase(Locale.US)
                                if (
                                    lowerLine.contains("downloading subtitles") ||
                                    lowerLine.contains("writing video subtitles to:") ||
                                    lowerLine.contains("subtitle") ||
                                    lowerLine.contains("subtitlesconvertor")
                                ) {
                                    Log.i(TAG, "HardSub sub log id=${downloadItem.id}: $line")
                                }
                            }
                            eventBus.post(WorkerProgress(progress.toInt(), line, downloadItem.id, downloadItem.logID))
                            val title: String = downloadItem.title.ifEmpty { downloadItem.url }
                            val now = System.currentTimeMillis()
                            val intProgress = progress.toInt()
                            val progressAdvancedEnough = lastNotificationProgress < 0 || (intProgress - lastNotificationProgress) >= 2
                            if (now - lastNotificationUpdateAt >= 800L || progressAdvancedEnough || intProgress >= 100) {
                                notificationUtil.updateDownloadNotification(
                                    downloadItem.id.toInt(),
                                    line, intProgress, 0, title,
                                    NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID,
                                    getHardSubStatusText(resources)
                                )
                                lastNotificationUpdateAt = now
                                lastNotificationProgress = intProgress
                            }
                            CoroutineScope(Dispatchers.IO).launch {
                                if (logDownloads) {
                                    logRepo.update(line, logItem.id)
                                }
                                logString.append("$line\n")
                            }
                        }

                        resultRepo.updateDownloadItem(downloadItem)?.apply {
                            dao.updateWithoutUpsert(this)
                        }
                        //val wasQuickDownloaded = resultDao.getCountInt() == 0
                        var finalPaths = mutableListOf<String>()
                        var hardSubBurned = false

                        var deferBurnUntilPostMove = false
                        if (!noCache && shouldBurnHardSub) {
                            var preMoveBurnPaths = extractPathsFromYtdlpOutput(response.out).toMutableList()
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
                                Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${preMoveBurnPaths.size} mode=pre-move")
                                eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                                val burned = burnSubtitlesInPlace(preMoveBurnPaths, noKeepSubs, downloadItem.id, downloadItem.logID)
                                hardSubBurned = hardSubBurned || burned
                                if (burned) {
                                    eventBus.post(WorkerProgress(100, "Subtitle burn-in completed", downloadItem.id, downloadItem.logID))
                                    Log.i(TAG, "HardSub completed id=${downloadItem.id} mode=pre-move")
                                } else {
                                    Log.w(TAG, "HardSub pre-move produced no burned media id=${downloadItem.id}")
                                }
                            } else {
                                deferBurnUntilPostMove = true
                                Log.w(TAG, "HardSub pre-move deferred id=${downloadItem.id} reason=no-media-in-temp")
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
                                Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${latePreMoveBurnPaths.size} mode=pre-move-late")
                                eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                                val burned = burnSubtitlesInPlace(latePreMoveBurnPaths, noKeepSubs, downloadItem.id, downloadItem.logID)
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
                            eventBus.post(WorkerProgress(100, "Moving file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                            Log.i(
                                TAG,
                                "HardSub move start id=${downloadItem.id} from=${tempFileDir.absolutePath} to=${FileUtil.formatPath(downloadLocation)}"
                            )
                            val expectedMovedNames = runCatching {
                                tempFileDir.walkTopDown()
                                    .filter { it.isFile && it.length() > 0L }
                                    .map { it.name }
                                    .toSet()
                            }.getOrDefault(emptySet())
                            try {
                                finalPaths = withContext(Dispatchers.IO){
                                    FileUtil.moveFile(tempFileDir.absoluteFile,context, downloadLocation, keepCache){ p ->
                                        eventBus.post(WorkerProgress(p, "Moving file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                                    }
                                }.filter { !it.matches("\\.(description)|(txt)\$".toRegex()) }.toMutableList()

                                if (finalPaths.isNotEmpty()){
                                    eventBus.post(WorkerProgress(100, "Moved file to ${FileUtil.formatPath(downloadLocation)}", downloadItem.id, downloadItem.logID))
                                    Log.i(
                                        TAG,
                                        "HardSub move done id=${downloadItem.id} count=${finalPaths.size} sample=${
                                            finalPaths.joinToString(limit = 3)
                                        }"
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
                                        "HardSub move done id=${downloadItem.id} but no output paths detected"
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
                                    addAll(recoverPathsFromDirectory(tempFileDir.absolutePath, downloadStartedAt))
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
                                }

                                if (shouldBurnHardSub && finalPaths.isEmpty()) {
                                    throw IOException(
                                        "HardSub move failed: output files are missing after burn-in",
                                        e
                                    )
                                }
                                if (e.message?.isNotBlank() == true) {
                                    handler.postDelayed({
                                        Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                    }, 1000)
                                }

                            }
                        }

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
                                Log.w(TAG, "HardSub post-move skipped id=${downloadItem.id} reason=no-files-found-after-move")
                            } else {
                                Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${postMoveBurnPaths.size} mode=post-move")
                                eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                                val burned = burnSubtitlesInPlace(postMoveBurnPaths, noKeepSubs, downloadItem.id, downloadItem.logID)
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
                            Log.i(TAG, "HardSub start id=${downloadItem.id} title=${downloadItem.title} paths=${finalPaths.size}")
                            eventBus.post(WorkerProgress(1, "Burning subtitles 1%", downloadItem.id, downloadItem.logID))
                            val burned = burnSubtitlesInPlace(finalPaths, noKeepSubs, downloadItem.id, downloadItem.logID)
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

                        val nonMediaExtensions = mutableListOf<String>().apply {
                            addAll(context.getStringArray(R.array.thumbnail_containers_values))
                            addAll(context.getStringArray(R.array.sub_formats_values).filter { it.isNotBlank() })
                            add("description")
                            add("txt")
                        }
                        finalPaths = finalPaths.filter { path -> !nonMediaExtensions.any { path.endsWith(it) } }.toMutableList()
                        finalPaths = prioritizePrimaryMediaPath(finalPaths, downloadItem.type)
                        if (finalPaths.isNotEmpty()) {
                            val summary = finalPaths.joinToString(limit = 5) { path ->
                                val file = File(path)
                                "${file.name}(size=${file.length()},mtime=${file.lastModified()})"
                            }
                            Log.i(TAG, "HardSub final paths id=${downloadItem.id} count=${finalPaths.size} sample=$summary")
                        }
                        FileUtil.deleteConfigFiles(request)

                        //put download in history
                        if (!downloadItem.incognito) {
                            if (request.hasOption("--download-archive") && finalPaths.isEmpty()) {
                                handler.postDelayed({
                                    Toast.makeText(context, resources.getString(R.string.download_already_exists), Toast.LENGTH_LONG).show()
                                }, 100)
                            }else{
                                if (finalPaths.isNotEmpty()) {
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

                                    val previousHistoryItem = if (replacedHistoryId > 0L) {
                                        runCatching { historyDao.getItem(replacedHistoryId) }.getOrNull()
                                    } else null
                                    val completedHardSub = isHardSubRedownload(downloadItem) && hardSubBurned
                                    val restoredPlaybackPositionMs = if (completedHardSub) 0 else (previousHistoryItem?.playbackPositionMs ?: 0)

                                    val historyItem = HistoryItem(
                                        id = replacedHistoryId,
                                        url = downloadItem.url,
                                        title = downloadItem.title,
                                        author = downloadItem.author,
                                        artist = previousHistoryItem?.artist ?: "",
                                        duration = downloadItem.duration,
                                        durationSeconds = downloadItem.duration.toDurationSeconds(),
                                        thumb = downloadItem.thumb,
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
                                        localTreeUri = previousHistoryItem?.localTreeUri ?: "",
                                        localTreePath = previousHistoryItem?.localTreePath ?: "",
                                        keywords = previousHistoryItem?.keywords ?: "",
                                        customThumb = previousHistoryItem?.customThumb ?: "",
                                        hardSubScanRemoved = if (completedHardSub) true else previousHistoryItem?.hardSubScanRemoved ?: false,
                                        hardSubDone = if (completedHardSub) true else previousHistoryItem?.hardSubDone ?: false
                                    )
                                    historyDao.insert(historyItem)
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (isHardSubRedownload(downloadItem)) {
                                markHardSubProcessed(downloadItem.id)
                                updateHardSubWorkerNotification(notificationUtil)
                            }
                            notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                            notificationUtil.createDownloadFinished(
                                downloadItem.id, downloadItem.title, downloadItem.type,  if (finalPaths.isEmpty()) null else finalPaths, resources
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

                        dao.delete(downloadItem.id)

                        if (logDownloads){
                            logRepo.update(initialLogDetails + response.out, logItem.id, true)
                        }

                    } catch (it: Exception) {
                        if (downloadItem.type == DownloadType.video && downloadItem.videoPreferences.embedSubs) {
                            Log.e(TAG, "HardSub failed id=${downloadItem.id} reason=${it.message}", it)
                        }
                        FileUtil.deleteConfigFiles(request)
                        withContext(Dispatchers.Main){
                            notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())
                        }
                        if (this@DownloadWorker.isStopped || it is YoutubeDL.CanceledException) {
                            return@launch
                        }
                        if (it.message?.contains("JSONDecodeError") == true) {
                            val cachePath = "${FileUtil.getCachePath(context)}infojsons"
                            val infoJsonName = MessageDigest.getInstance("MD5").digest(downloadItem.url.toByteArray()).toHexString()
                            FileUtil.deleteFile("${cachePath}/${infoJsonName}.info.json")
                        }

                        if (logDownloads){
                            logRepo.update(it.message ?: "", logItem.id)
                        }else{
                            logString.append("${it.message ?: it.stackTraceToString()}\n")
                            logItem.content = logString.toString()
                            val logID = logRepo.insert(logItem)
                            downloadItem.logID = logID
                        }


                        tempFileDir.delete()

                        Log.e(TAG, context.getString(R.string.failed_download), it)
                        notificationUtil.cancelDownloadNotification(downloadItem.id.toInt())

                        downloadItem.status = DownloadRepository.Status.Error.toString()
                        dao.update(downloadItem)
                        if (isHardSubRedownload(downloadItem)) {
                            markHardSubProcessed(downloadItem.id)
                            updateHardSubWorkerNotification(notificationUtil)
                        }

                        notificationUtil.createDownloadErrored(
                            downloadItem.id,
                            downloadItem.title.ifEmpty { downloadItem.url },
                            it.message,
                            downloadItem.logID,
                            resources
                        )

                        eventBus.post(WorkerProgress(100, it.toString(), downloadItem.id, downloadItem.logID))
                    }
                }
            }

            if (eligibleDownloads.isNotEmpty()){
                eligibleDownloads.forEach {
                    it.status = DownloadRepository.Status.Active.toString()
                    priorityItemIDs.remove(it.id)
                }
                dao.updateMultiple(eligibleDownloads)
            }
        }

        return Result.success()
    }



    companion object {
        val runningYTDLInstances: MutableList<Long> = mutableListOf()
        const val TAG = "DownloadWorker"
        const val HISTORY_REDOWNLOAD_MARKER = "history-redownload:"

        private val hardSubTargetIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        private val hardSubProcessedIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        private val hardSubDisabledFfmpegSources: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val hardSubFilterSupportCache: MutableMap<String, Set<String>> = ConcurrentHashMap()
    }

    private fun isHardSubRedownload(item: DownloadItem): Boolean {
        return item.type == com.ireum.ytdl.database.enums.DownloadType.video &&
            item.videoPreferences.embedSubs &&
            item.playlistURL?.startsWith(HISTORY_REDOWNLOAD_MARKER) == true
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

    private fun burnSubtitlesInPlace(
        paths: List<String>,
        removeSubsAfterBurnIn: Boolean,
        downloadItemId: Long? = null,
        downloadLogId: Long? = null
    ): Boolean {
        val ffmpegRuntime = resolveFfmpegRuntime()
        val supportedFilters = probeSubtitleFilters(ffmpegRuntime)
        val dedicatedSrv3ConverterPath = resolveSrv3ConverterPath()
        val subtitleExts = if (dedicatedSrv3ConverterPath != null) {
            listOf("ass", "srv3", "json3", "ttml", "vtt", "srt")
        } else {
            // Without a dedicated converter, prefer directly burnable formats first.
            listOf("ass", "vtt", "srt", "srv3", "json3", "ttml")
        }
        val existingFiles = paths
            .map { File(it) }
            .filter { it.exists() && it.isFile }
        val subtitleFiles = existingFiles.filter { file ->
            subtitleExts.any { ext -> file.extension.equals(ext, ignoreCase = true) }
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

        Log.i(
            TAG,
            "HardSub burn scan mediaFiles=${mediaFiles.size} converter=${if (dedicatedSrv3ConverterPath != null) "yttml" else "ffmpeg-only"}"
        )
        Log.i(
            TAG,
            "HardSub ffmpeg runtime exec=${ffmpegRuntime.executablePath} source=${ffmpegRuntime.source} libs=${ffmpegRuntime.libraryPath ?: "<default>"}"
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
        var unwritableOutputDir: String? = null
        mediaFiles.forEach { media ->
            if (!hasVideoStream(media, ffmpegRuntime)) {
                Log.w(TAG, "HardSub skip media=${media.name} reason=no-video-stream")
                return@forEach
            }
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
            val subtitle = prepareSubtitleForBurnIn(media, subtitleExts, subtitleCandidates, ffmpegRuntime, dedicatedSrv3ConverterPath)
            if (subtitle == null) {
                Log.w(TAG, "HardSub skip media=${media.name} reason=no-matching-subtitle")
                return@forEach
            }
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
                            subtitleArg,
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
                                listOf("-c:v", "libx264", "-c:a", "copy"),
                                listOf("-c:v", "mpeg4", "-c:a", "copy")
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
                throw IOException("ffmpeg burn-in failed (code=${finalResult.exitCode}): ${finalResult.output.takeLast(1200)}")
            }
            if (!output.exists()) {
                Log.e(TAG, "HardSub ffmpeg output missing media=${media.name}")
                throw IOException("ffmpeg burn-in failed: output was not created")
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
        if (canonicalSubtitle?.isTemporary == true) canonicalSubtitle.file.delete()
        return true
    }

    private fun hasVideoStream(media: File, ffmpegRuntime: FfmpegRuntime): Boolean {
        val probe = executeFfmpegWithAutoPatch(
            ffmpegRuntime,
            listOf("-hide_banner", "-i", media.absolutePath)
        )
        // ffmpeg -i returns non-zero without output target, so parse stream info from output.
        return Regex("""(?m)^\s*Stream #\d+:\d+.*:\s*Video:\s*""").containsMatchIn(probe.output)
    }

    private fun prepareSubtitleForBurnIn(
        media: File,
        subtitleExts: List<String>,
        providedSubtitles: List<File>,
        ffmpegRuntime: FfmpegRuntime,
        dedicatedSrv3ConverterPath: String?
    ): BurnInSubtitle? {
        val selectedSubtitle = findSubtitleForMedia(media, subtitleExts, providedSubtitles) ?: return null
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
                "HardSub skip subtitle source=${selectedSubtitle.name} reason=rich-subtitle-convert-failed"
            )
            return null
        }

        return BurnInSubtitle(selectedSubtitle, isAss = false, isTemporary = false)
    }

    private fun convertSubtitleToAss(subtitle: File, ffmpegRuntime: FfmpegRuntime, dedicatedSrv3ConverterPath: String?): File? {
        if (
            dedicatedSrv3ConverterPath != null &&
            setOf("srv3", "json3", "ttml").contains(subtitle.extension.lowercase(Locale.US))
        ) {
            convertSrv3ToAssWithDedicatedConverter(subtitle, dedicatedSrv3ConverterPath)?.let { return it }
        }

        val parent = subtitle.parentFile ?: return null
        val output = File(parent, "${subtitle.nameWithoutExtension}.burnin_tmp.ass")
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
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val executableCandidates = mutableListOf<Pair<File, String>>()
        // Native hard-sub runtime only (bundled/package/termux paths removed).
        executableCandidates.add(File(nativeLibDir, "libffmpeg_hardsub_exec.so") to "native-lib-hardsub-exec")
        executableCandidates.add(File(nativeLibDir, "libffmpeg_hardsub.so") to "native-lib-hardsub")

        val selected = executableCandidates.firstOrNull { (file, source) ->
            if (source in excludedSources || source in hardSubDisabledFfmpegSources) return@firstOrNull false
            isUsableFfmpegExecutable(file)
        } ?: (File(nativeLibDir, "libffmpeg_hardsub.so") to "native-lib-hardsub-fallback")
        val executable = selected.first
        val source = selected.second

        val extractedLibDir = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        val libraryDirs = mutableListOf<String>()
        executable.parentFile?.let { parent ->
            if (parent.exists() && parent.isDirectory) {
                libraryDirs.add(parent.absolutePath)
            }
            val siblingUsrLib = File(parent, "../lib").normalize()
            if (siblingUsrLib.exists() && siblingUsrLib.isDirectory) {
                libraryDirs.add(siblingUsrLib.absolutePath)
            }
        }
        if (nativeLibDir.exists() && nativeLibDir.isDirectory) {
            libraryDirs.add(nativeLibDir.absolutePath)
        }
        libraryDirs.addAll(systemLibrarySearchDirs())
        // For the exec-wrapper runtime, extracted ffmpeg libs are required.
        // For other native-lib paths, avoid mixing to reduce namespace collisions.
        val needsExtractedLibs = source == "native-lib-hardsub-exec" || source == "native-lib-hardsub-exec-fallback"
        if ((needsExtractedLibs || !source.startsWith("native-lib")) &&
            extractedLibDir.exists() &&
            extractedLibDir.isDirectory
        ) {
            libraryDirs.add(extractedLibDir.absolutePath)
        }
        val preloadLibraryPath = sequenceOf(
            File(extractedLibDir, "libc++_shared.so"),
            File(nativeLibDir, "libc++_shared.so")
        ).firstOrNull { it.exists() && it.isFile }?.absolutePath
        return FfmpegRuntime(
            executablePath = executable.absolutePath,
            libraryPath = libraryDirs.distinct().joinToString(":").ifBlank { null },
            preloadLibraryPath = preloadLibraryPath,
            source = source
        )
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
        val command = mutableListOf(runtime.executablePath).apply { addAll(args) }
        val builder = ProcessBuilder(command).redirectErrorStream(true)
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
            val exitCode = process.waitFor()
            val elapsed = System.currentTimeMillis() - startedAt
            Log.i(TAG, "HardSub ffmpeg end source=${runtime.source} code=$exitCode elapsedMs=$elapsed")
            FfmpegExecResult(exitCode = exitCode, output = outputBuilder.toString())
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
        val parent = subtitle.parentFile ?: return null
        val output = File(parent, "${subtitle.nameWithoutExtension}.burnin_tmp.ass")
        val process = runCatching {
            ProcessBuilder(
                converterPath,
                "parse",
                subtitle.absolutePath,
                "--format",
                "ass",
                "--save",
                "file",
                "--output",
                output.absolutePath
            ).redirectErrorStream(true).start()
        }.getOrElse { error ->
            Log.w(
                TAG,
                "Dedicated srv3->ass converter start failed path=$converterPath source=${subtitle.name} reason=${error.message}"
            )
            return null
        }

        val converterOutput = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0 || !output.exists() || output.length() == 0L) {
            if (output.exists()) output.delete()
            Log.w(TAG, "Dedicated srv3->ass converter failed (code=$exitCode): ${converterOutput.takeLast(800)}")
            return null
        }
        return output
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

    private fun findSubtitleForMedia(media: File, subtitleExts: List<String>, providedSubtitles: List<File>): File? {
        val parent = media.parentFile ?: return null
        val prefix = "${media.nameWithoutExtension}."
        val files = parent.listFiles().orEmpty()
        val allCandidates = (files.asList() + providedSubtitles)
            .asSequence()
            .filter { it.isFile }
            .distinctBy { it.absolutePath }
            .toList()

        subtitleExts.forEach { ext ->
            allCandidates.firstOrNull { candidate ->
                candidate.isFile &&
                candidate.name.startsWith(prefix) &&
                    candidate.extension.equals(ext, ignoreCase = true)
            }?.let { return it }
        }
        val sameDirSubtitles = allCandidates.filter { candidate ->
            candidate.parentFile?.absolutePath == parent.absolutePath &&
                subtitleExts.any { ext -> candidate.extension.equals(ext, ignoreCase = true) }
        }
        if (sameDirSubtitles.size == 1) {
            Log.w(TAG, "HardSub subtitle fallback media=${media.name} matched=${sameDirSubtitles.first().name} reason=single-subtitle-in-dir")
            return sameDirSubtitles.first()
        }
        if (providedSubtitles.size == 1) {
            val only = providedSubtitles.first()
            Log.w(TAG, "HardSub subtitle fallback media=${media.name} matched=${only.name} reason=single-provided-subtitle")
            return only
        }
        return null
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

    class WorkerProgress(
        val progress: Int,
        val output: String,
        val downloadItemID: Long,
        val logItemID: Long?
    )

}



