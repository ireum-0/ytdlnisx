package com.ireum.ytdl.work

import android.content.pm.ServiceInfo
import android.os.Build
import android.content.Context
import android.util.Log
import androidx.work.ForegroundInfo
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.util.Extensions.isYoutubeURL
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.SubtitleLanguageMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale

class HardSubScanWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val dbManager = DBManager.getInstance(context)
        val historyDao = dbManager.historyDao
        val downloadDao = dbManager.downloadDao
        val resultRepository = ResultRepository(dbManager.resultDao, dbManager.commandTemplateDao, context)
        val downloadRepository = DownloadRepository(dbManager)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val subsLanguages = sharedPreferences.getString("subs_lang", "en.*,.*-orig").orEmpty()
        val notificationUtil = NotificationUtil(context)
        val queuedItems = mutableListOf<DownloadItem>()

        val resetForRescan = sharedPreferences.getBoolean(PREF_HARD_SUB_RESCAN_DONE_ONCE, true)
        if (resetForRescan) {
            historyDao.resetHardSubDoneForRescan()
        }

        val candidates = historyDao.getHardSubScanCandidates()
        if (candidates.isEmpty()) {
            if (resetForRescan) {
                sharedPreferences.edit().putBoolean(PREF_HARD_SUB_RESCAN_DONE_ONCE, false).apply()
            }
            return Result.success()
        }

        var processed = 0
        var failedLookups = 0
        val foregroundOutcome = try {
            setForeground(createForegroundInfo(notificationUtil, processed, candidates.size))
            hardSubForegroundAttemptOutcome(runAttemptCount, foregroundStarted = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Unable to start hard-sub scan in the foreground", error)
            hardSubForegroundAttemptOutcome(runAttemptCount, foregroundStarted = false)
        }
        when (foregroundOutcome) {
            HardSubForegroundAttemptOutcome.PROCEED -> Unit
            HardSubForegroundAttemptOutcome.RETRY -> return Result.retry()
            HardSubForegroundAttemptOutcome.FAILURE -> return Result.failure()
        }
        if (resetForRescan) {
            sharedPreferences.edit().putBoolean(PREF_HARD_SUB_RESCAN_DONE_ONCE, false).apply()
        }

        candidates.forEach { item ->
            currentCoroutineContext().ensureActive()
            if (isStopped) throw CancellationException("Hard-sub scan worker stopped")
            try {
                if (item.url.isBlank() || !item.url.isYoutubeURL()) {
                    historyDao.updateHardSubScanState(item.id, removed = true, done = false)
                    return@forEach
                }

                if (isAlreadyHardSubbed(item.command)) {
                    historyDao.updateHardSubScanState(item.id, removed = true, done = true)
                    return@forEach
                }

                val manualSubs = try {
                    resultRepository
                        .getResultsFromSource(item.url, resetResults = false, addToResults = false, singleItem = true)
                        .firstOrNull()
                        ?.availableSubtitles
                        .orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failedLookups += 1
                    Log.w(
                        TAG,
                        "Subtitle lookup failed for history id=${item.id} " +
                            "type=${error.javaClass.simpleName}"
                    )
                    return@forEach
                }

                val hasRequestedLanguage = SubtitleLanguageMatcher.hasRequestedSubtitle(manualSubs, subsLanguages)
                if (!hasRequestedLanguage) {
                    historyDao.updateHardSubScanState(item.id, removed = true, done = false)
                    return@forEach
                }

                val marker = HistoryRedownloadMarker.regular(item.id)
                if (downloadDao.countPendingByPlaylistMarker(marker) > 0) {
                    return@forEach
                }

                val downloadItem = createHardSubDownloadItem(item, manualSubs, subsLanguages, marker, sharedPreferences)
                val insertedId = downloadDao.insert(downloadItem)
                downloadItem.id = insertedId
                queuedItems.add(downloadItem)
            } finally {
                processed += 1
                try {
                    updateScanNotification(notificationUtil, processed, candidates.size)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to update hard-sub scan notification", error)
                }
            }
        }

        if (queuedItems.isNotEmpty()) {
            downloadRepository.startDownloadWorker(queuedItems, context)
        }
        notificationUtil.cancelDownloadNotification(SCAN_NOTIFICATION_ID)

        return if (failedLookups > 0 && hardSubAttemptsRemain(runAttemptCount)) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private fun isAlreadyHardSubbed(command: String): Boolean {
        if (command.isBlank()) return false
        return command.contains("subtitles=\$sub", ignoreCase = true) ||
            command.contains("-vf \"subtitles=", ignoreCase = true)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "hard_sub_scan_worker"
        const val TAG = "hard_sub_scan"
        private const val SCAN_NOTIFICATION_ID = 1000000002
        private const val PREF_HARD_SUB_RESCAN_DONE_ONCE = "hard_sub_rescan_done_once_v2"

        internal fun enqueue(
            context: Context,
            completion: (WorkManagerHandoffRecovery.EnqueueOutcome) -> Unit = {},
        ) = enqueueWithGeneration(context) { _, outcome -> completion(outcome) }

        /**
         * Starts one exact Scan Now generation and reports its id with every
         * completion.  The caller can therefore discard a late result from a
         * superseded REPLACE request before showing user-facing state.
         */
        internal fun enqueueWithGeneration(
            context: Context,
            onPrepared: (String) -> Unit = {},
            completion: (String, WorkManagerHandoffRecovery.EnqueueOutcome) -> Unit = { _, _ -> },
        ): String? {
            val handoffId = try {
                WorkManagerHandoffRecovery.prepareHardSub(context)
            } catch (failure: Throwable) {
                completion(
                    "",
                    WorkManagerHandoffRecovery.EnqueueOutcome(
                        WorkManagerHandoffRecovery.OutcomeKind.FAILED,
                        failure,
                    )
                )
                return null
            }
            onPrepared(handoffId)
            WorkManagerHandoffRecovery.enqueueAndObserve(context, handoffId) { outcome ->
                completion(handoffId, outcome)
            }
            return handoffId
        }
    }

    private fun createHardSubDownloadItem(
        historyItem: HistoryItem,
        availableSubtitles: List<String>,
        subsLanguages: String,
        marker: String,
        sharedPreferences: android.content.SharedPreferences
    ): DownloadItem {
        val recodeVideo = sharedPreferences.getBoolean("recode_video", false)
        val addChapters = sharedPreferences.getBoolean("add_chapters", false)
        val saveThumb = sharedPreferences.getBoolean("write_thumbnail", false)
        val embedThumb = sharedPreferences.getBoolean("embed_thumbnail", false)
        val cropThumb = sharedPreferences.getBoolean("crop_thumbnail", false)
        val sponsorblock = sharedPreferences.getStringSet("sponsorblock_filters", emptySet()).orEmpty()
        val audioBitrate = sharedPreferences.getString("audio_bitrate", "").orEmpty()

        val container = sharedPreferences.getString("video_format", "Default").orEmpty()
        val customFileNameTemplate = sharedPreferences
            .getString("file_name_template", "%(uploader).30B - %(title).170B")
            .orEmpty()

        val defaultPath = sharedPreferences.getString("video_path", FileUtil.getDefaultVideoPath())
            ?: FileUtil.getDefaultVideoPath()
        val isLocalFormatLike = historyItem.format.format_id.isLocalFormatLike()
        val isLocalHistorySource =
            historyItem.localTreeUri.isNotBlank() ||
                historyItem.localTreePath.isNotBlank() ||
                historyItem.downloadPath.any { it.startsWith("content://") } ||
                isLocalFormatLike
        val path = if (isLocalHistorySource) {
            defaultPath
        } else {
            val bestPath = historyItem.downloadPath.firstOrNull { FileUtil.exists(it) } ?: historyItem.downloadPath.firstOrNull()
            bestPath?.let { pathCandidate ->
                File(pathCandidate).parent?.takeIf { File(it).exists() }
            } ?: defaultPath
        }
        val normalizedFormat = historyItem.format.copy().apply {
            if (isLocalHistorySource || format_id.isLocalFormatLike()) {
                format_id = "best"
            }
        }

        val audioPreferences = AudioPreferences(
            embedThumb = embedThumb,
            cropThumb = cropThumb,
            splitByChapters = false,
            sponsorBlockFilters = ArrayList(sponsorblock),
            bitrate = audioBitrate
        )
        val videoPreferences = VideoPreferences(
            embedSubs = true,
            addChapters = addChapters,
            splitByChapters = false,
            sponsorBlockFilters = ArrayList(sponsorblock),
            writeSubs = true,
            writeAutoSubs = false,
            subsLanguages = subsLanguages,
            recodeVideo = recodeVideo
        )

        return DownloadItem(
            id = 0,
            url = historyItem.url,
            title = historyItem.title,
            author = historyItem.author,
            thumb = historyItem.thumb,
            duration = historyItem.duration,
            type = DownloadType.video,
            format = normalizedFormat,
            container = container,
            downloadSections = "",
            allFormats = arrayListOf(),
            downloadPath = path,
            website = historyItem.website,
            downloadSize = "",
            playlistTitle = "",
            audioPreferences = audioPreferences,
            videoPreferences = videoPreferences,
            extraCommands = "",
            customFileNameTemplate = customFileNameTemplate,
            SaveThumb = saveThumb,
            status = DownloadRepository.Status.Queued.toString(),
            downloadStartTime = 0L,
            logID = null,
            playlistURL = marker,
            playlistIndex = null,
            incognito = sharedPreferences.getBoolean("incognito", false),
            availableSubtitles = availableSubtitles,
            mediaPublishedAt = historyItem.mediaPublishedAt
        )
    }

    private fun String.isLocalFormatLike(): Boolean {
        val normalized = trim().lowercase(Locale.getDefault())
        return normalized == "local" || normalized.startsWith("local+")
    }

    private suspend fun updateScanNotification(notificationUtil: NotificationUtil, done: Int, total: Int) {
        setForeground(createForegroundInfo(notificationUtil, done, total))
        val title = context.getString(com.ireum.ytdl.R.string.hard_sub_scan_title)
        val status = context.getString(com.ireum.ytdl.R.string.hard_sub_scan_progress, done.coerceAtMost(total), total)
        notificationUtil.notify(
            SCAN_NOTIFICATION_ID,
            notificationUtil.createHardSubScanWorkerNotification(title, status)
        )
    }

    private fun createForegroundInfo(notificationUtil: NotificationUtil, done: Int, total: Int): ForegroundInfo {
        val title = context.getString(com.ireum.ytdl.R.string.hard_sub_scan_title)
        val status = context.getString(com.ireum.ytdl.R.string.hard_sub_scan_progress, done.coerceAtMost(total), total)
        val notification = notificationUtil.createHardSubScanWorkerNotification(title, status)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                SCAN_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(SCAN_NOTIFICATION_ID, notification)
        }
    }
}

private const val MAX_HARD_SUB_ATTEMPTS = 3

internal enum class HardSubForegroundAttemptOutcome {
    PROCEED,
    RETRY,
    FAILURE,
}

internal fun hardSubAttemptsRemain(runAttemptCount: Int): Boolean =
    runAttemptCount < MAX_HARD_SUB_ATTEMPTS - 1

internal fun hardSubForegroundAttemptOutcome(
    runAttemptCount: Int,
    foregroundStarted: Boolean,
): HardSubForegroundAttemptOutcome = when {
    foregroundStarted -> HardSubForegroundAttemptOutcome.PROCEED
    hardSubAttemptsRemain(runAttemptCount) -> HardSubForegroundAttemptOutcome.RETRY
    else -> HardSubForegroundAttemptOutcome.FAILURE
}
