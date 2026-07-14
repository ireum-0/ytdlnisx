package com.ireum.ytdl.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.LogItem
import com.ireum.ytdl.database.repository.LogRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.ui.more.terminal.TerminalActivity
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.SensitiveTextRedactor
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.terminal.TerminalCommandPlanFactory
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import java.io.File


class TerminalDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    private var itemId : Int = 0
    private var shouldCleanupTerminalCache = false

    private suspend fun cleanupStoppedWorker() = withContext(Dispatchers.IO + NonCancellable) {
        if (itemId == 0) return@withContext

        val processId = itemId.toString()
        YoutubeDL.getInstance().destroyProcessById(processId)
        YoutubeDLCompat.destroyProcessById(processId)
        runCatching {
            NotificationUtil(context).cancelDownloadNotification(itemId)
        }
        if (shouldCleanupTerminalCache) runCatching {
            File(FileUtil.getCachePath(context), "TERMINAL/$itemId").deleteRecursively()
        }
        runCatching {
            DBManager.getInstance(context).terminalDao.delete(itemId.toLong())
        }
        Log.i(TAG, "Stopped terminal worker cleanup completed for itemId=$itemId")
    }

    override suspend fun doWork(): Result {
        return try {
            doWorkInternal()
        } finally {
            if (isStopped) {
                cleanupStoppedWorker()
            }
        }
    }

    private suspend fun doWorkInternal(): Result {
        itemId = inputData.getInt("id", 0)
        val command = inputData.getString("command")
        val dao = DBManager.getInstance(context).terminalDao
        if (itemId == 0) return Result.failure()
        if (command.isNullOrBlank()) return Result.failure()

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val terminalPlan = TerminalCommandPlanFactory.create(
            context = context,
            preferences = sharedPreferences,
            command = command,
            taskId = itemId.toString()
        )
        val dbManager = DBManager.getInstance(context)
        val logRepo = LogRepository(dbManager.logDao)
        val notificationUtil = NotificationUtil(context)
        val handler = Handler(Looper.getMainLooper())
        val redactedCommand = SensitiveTextRedactor.redactCommand(terminalPlan.sanitizedConfig)
        val notificationTitle = SensitiveTextRedactor.safeNotificationTitle(redactedCommand)

        val intent = Intent(context, TerminalActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = notificationUtil.createDownloadServiceNotification(pendingIntent, notificationTitle, NotificationUtil.DOWNLOAD_TERMINAL_RUNNING_NOTIFICATION_ID)
        val foregroundInfo = if (Build.VERSION.SDK_INT >= 33) {
            ForegroundInfo(itemId, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }else{
            ForegroundInfo(itemId, notification)
        }
        runCatching {
            setForeground(foregroundInfo)
            delay(500)
        }.onFailure {
            Log.e(TAG, "Failed to enter foreground", it)
            return Result.retry()
        }

        val downloadLocation = terminalPlan.downloadLocation
        val removedOptionWarning = if (terminalPlan.removedOptions.isNotEmpty()) {
            "Warning: Removed unsafe or unsupported yt-dlp option(s): " +
                    terminalPlan.removedOptions.joinToString(", ") + "\n\n"
        } else {
            ""
        }
        val configFile = File(context.cacheDir.absolutePath + "/config-TERMINAL[${System.currentTimeMillis()}].txt").apply {
            writeText(terminalPlan.sanitizedConfig)
        }
        val request = terminalPlan.createRequest(configFile)
        val noCache = !terminalPlan.usesAppCache
        shouldCleanupTerminalCache = !noCache





        val logDownloads = sharedPreferences.getBoolean("log_downloads", false) && !sharedPreferences.getBoolean("incognito", false)

        val initialLogDetails = "Terminal Task\n" +
                "Command:\n${redactedCommand.trim()}\n\n" +
                removedOptionWarning
        val logItem = LogItem(
            0,
            "Terminal Task",
            initialLogDetails,
            Format(),
            DownloadType.command,
            System.currentTimeMillis(),
        )

        val eventBus = EventBus.getDefault()

        try {
            if (logDownloads){
                logItem.id = logRepo.insert(logItem)
            }
            if (removedOptionWarning.isNotBlank()) {
                Log.w(TAG, removedOptionWarning.trim())
                eventBus.post(DownloadWorker.WorkerProgress(0, removedOptionWarning.trim(), itemId.toLong(), logItem.id))
                dao.updateLog(removedOptionWarning, itemId.toLong())
            }

            YoutubeDL.getInstance().destroyProcessById(itemId.toString())
            YoutubeDLCompat.destroyProcessById(itemId.toString())
            val response = YoutubeDLCompat.execute(applicationContext, request, itemId.toString(), true){ progress, _, line ->
                val redactedLine = SensitiveTextRedactor.redactOutput(line)
                eventBus.post(DownloadWorker.WorkerProgress(progress.toInt(), redactedLine, itemId.toLong(), logItem.id))

                notificationUtil.updateTerminalDownloadNotification(
                    itemId,
                    redactedLine, progress.toInt(), notificationTitle,
                    NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID
                )
                runBlocking(Dispatchers.IO) {
                    if (logDownloads) logRepo.update(redactedLine, logItem.id)
                    dao.updateLog(redactedLine, itemId.toLong())
                }
            }

            withContext(Dispatchers.IO) {
                if(!noCache){
                    //move file from internal to set download directory
                    FileUtil.moveFile(
                        File(FileUtil.getCachePath(context) + "/TERMINAL/" + itemId),
                        context,
                        downloadLocation,
                        false
                    ){ p ->
                        eventBus.post(DownloadWorker.WorkerProgress(p, "", itemId.toLong(), logItem.id))
                    }
                }
            }
            val redactedOutput = SensitiveTextRedactor.redactOutput(response.out)
            if (logDownloads) logRepo.update(initialLogDetails + redactedOutput, logItem.id, true)
            dao.updateLog(redactedOutput, itemId.toLong())
            notificationUtil.cancelDownloadNotification(itemId)
            delay(1000)
            dao.delete(itemId.toLong())
            return Result.success()
        } catch (it: Exception) {
            if (isStopped || it is YoutubeDL.CanceledException) {
                notificationUtil.cancelDownloadNotification(itemId)
                if (!noCache) {
                    File(FileUtil.getCachePath(context), "TERMINAL/$itemId").deleteRecursively()
                }
                runCatching {
                    dao.delete(itemId.toLong())
                }
                Log.i(TAG, "Terminal worker stopped or cancelled itemId=$itemId")
                return Result.success()
            }
            val redactedMessage = it.message?.let { message ->
                SensitiveTextRedactor.redactOutput(message)
            }
            val userMessage = redactedMessage ?: it::class.java.simpleName
            handler.postDelayed({
                Toast.makeText(context, userMessage, Toast.LENGTH_SHORT).show()
            }, 1000)
            if (redactedMessage != null){
                if (logDownloads) logRepo.update(redactedMessage, logItem.id)
                dao.updateLog(redactedMessage, itemId.toLong())
            }
            notificationUtil.cancelDownloadNotification(itemId)
            if (!noCache) {
                File(FileUtil.getCachePath(context), "TERMINAL/$itemId").deleteRecursively()
            }
            Log.e(TAG, "${context.getString(R.string.failed_download)} $userMessage")
            delay(1000)
            dao.delete(itemId.toLong())
            return Result.failure()
        } finally {
            FileUtil.deleteConfigFiles(request)
        }
        return Result.success()
    }

    companion object {
        const val TAG = "DownloadWorker"
    }

}
