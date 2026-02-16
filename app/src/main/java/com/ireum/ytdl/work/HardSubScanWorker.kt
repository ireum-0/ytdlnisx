package com.ireum.ytdl.work

import android.content.pm.ServiceInfo
import android.os.Build
import android.content.Context
import androidx.work.ForegroundInfo
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
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
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.SubtitleLanguageMatcher
import java.io.File

class HardSubScanWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val dbManager = DBManager.getInstance(context)
        val historyDao = dbManager.historyDao
        val downloadDao = dbManager.downloadDao
        val resultRepository = ResultRepository(dbManager.resultDao, dbManager.commandTemplateDao, context)
        val downloadRepository = DownloadRepository(downloadDao)
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val subsLanguages = sharedPreferences.getString("subs_lang", "en.*,.*-orig").orEmpty()
        val notificationUtil = NotificationUtil(context)
        val queuedItems = mutableListOf<DownloadItem>()

        if (sharedPreferences.getBoolean(PREF_HARD_SUB_RESCAN_DONE_ONCE, true)) {
            historyDao.resetHardSubDoneForRescan()
            sharedPreferences.edit().putBoolean(PREF_HARD_SUB_RESCAN_DONE_ONCE, false).apply()
        }

        val candidates = historyDao.getHardSubScanCandidates()
        if (candidates.isEmpty()) return Result.success()

        var processed = 0
        setForeground(createForegroundInfo(notificationUtil, processed, candidates.size))

        candidates.forEach { item ->
            if (isStopped) return Result.success()
            try {
                if (item.url.isBlank() || !item.url.isYoutubeURL()) {
                    historyDao.updateHardSubScanState(item.id, removed = true, done = false)
                    return@forEach
                }

                if (isAlreadyHardSubbed(item.command)) {
                    historyDao.updateHardSubScanState(item.id, removed = true, done = true)
                    return@forEach
                }

                val manualSubs = runCatching {
                    resultRepository
                        .getResultsFromSource(item.url, resetResults = false, addToResults = false, singleItem = true)
                        .firstOrNull()
                        ?.availableSubtitles
                        .orEmpty()
                }.getOrDefault(emptyList())

                val hasRequestedLanguage = SubtitleLanguageMatcher.hasRequestedSubtitle(manualSubs, subsLanguages)
                if (!hasRequestedLanguage) {
                    historyDao.updateHardSubScanState(item.id, removed = true, done = false)
                    return@forEach
                }

                val marker = "$HISTORY_REDOWNLOAD_MARKER${item.id}"
                if (downloadDao.countPendingByPlaylistMarker(marker) > 0) {
                    return@forEach
                }

                val downloadItem = createHardSubDownloadItem(item, manualSubs, subsLanguages, marker, sharedPreferences)
                val insertedId = downloadDao.insert(downloadItem)
                downloadItem.id = insertedId
                queuedItems.add(downloadItem)
            } finally {
                processed += 1
                updateScanNotification(notificationUtil, processed, candidates.size)
            }
        }

        if (queuedItems.isNotEmpty()) {
            downloadRepository.startDownloadWorker(queuedItems, context)
        }
        notificationUtil.cancelDownloadNotification(SCAN_NOTIFICATION_ID)

        return Result.success()
    }

    private fun isAlreadyHardSubbed(command: String): Boolean {
        if (command.isBlank()) return false
        return command.contains("subtitles=\$sub", ignoreCase = true) ||
            command.contains("-vf \"subtitles=", ignoreCase = true)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "hard_sub_scan_worker"
        const val TAG = "hard_sub_scan"
        private const val HISTORY_REDOWNLOAD_MARKER = "history-redownload:"
        private const val SCAN_NOTIFICATION_ID = 1000000002
        private const val PREF_HARD_SUB_RESCAN_DONE_ONCE = "hard_sub_rescan_done_once_v2"
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

        val defaultPath = FileUtil.getDefaultVideoPath()
        val bestPath = historyItem.downloadPath.firstOrNull { FileUtil.exists(it) } ?: historyItem.downloadPath.firstOrNull()
        val path = bestPath?.let { pathCandidate ->
            File(pathCandidate).parent?.takeIf { File(it).exists() }
        } ?: defaultPath

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
            format = historyItem.format,
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
            availableSubtitles = availableSubtitles
        )
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
