package com.ireum.ytdl.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ireum.ytdl.App
import com.ireum.ytdl.MainActivity
import com.ireum.ytdl.R
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.storage.CacheImportArtifact
import com.ireum.ytdl.util.storage.CacheMaintenanceAuthority
import com.ireum.ytdl.util.storage.CacheImportPlanner
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking


class MoveCacheFilesWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {
    override fun doWork(): Result {
        val notificationUtil = NotificationUtil(App.instance)
        val id = System.currentTimeMillis().toInt()

        val cacheRoot = File(FileUtil.getCachePath(context)).canonicalFile
        val destination = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath +
                File.separator +
                "YTDLnisx/CACHE_IMPORT"
        ).canonicalFile

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = notificationUtil.createMoveCacheFilesNotification(pendingIntent, NotificationUtil.DOWNLOAD_MISC_CHANNEL_ID)

        if (Build.VERSION.SDK_INT >= 33) {
            setForegroundAsync(ForegroundInfo(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        }else{
            setForegroundAsync(ForegroundInfo(id, notification))
        }

        return runCatching {
            runBlocking(Dispatchers.IO) {
                CacheMaintenanceAuthority.withMaintenanceWindow {
                    val manifest = CacheImportPlanner.collect(cacheRoot)
                    val totalFiles = manifest.size
                    if (manifest.isEmpty()) {
                        notificationUtil.updateCacheMovingNotification(id, 0, 0)
                    }
                    if (!destination.exists() && !destination.mkdirs() && !destination.isDirectory) {
                        throw IllegalStateException("Could not create cache import destination")
                    }
                    manifest.forEachIndexed { index, artifact ->
                        notificationUtil.updateCacheMovingNotification(id, index + 1, totalFiles)
                        moveExact(artifact, destination)
                    }
                }
            }
        }.fold(
            onSuccess = {
                showCompletionToast()
                Result.success()
            },
            onFailure = { error ->
                android.util.Log.e(TAG, "Cache import stopped without overwriting unknown files", error)
                Result.failure()
            }
        )
    }

    private fun moveExact(artifact: CacheImportArtifact, destinationRoot: File) {
        val source = artifact.source.canonicalFile
        if (!source.isFile) throw IllegalStateException("Cache import source disappeared: ${source.absolutePath}")
        val destination = CacheImportPlanner.collisionSafeDestination(destinationRoot, artifact.relativePath)
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw IllegalStateException("Could not create cache import parent: ${parent.absolutePath}")
            }
        }
        if (destination.exists()) {
            throw IllegalStateException("Cache import destination appeared during move: ${destination.absolutePath}")
        }
        if (Build.VERSION.SDK_INT >= 26) {
            Files.move(source.toPath(), destination.toPath())
        } else if (!source.renameTo(destination)) {
            throw IllegalStateException("Could not move cache import source: ${source.absolutePath}")
        }
    }

    private fun showCompletionToast() {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            Toast.makeText(context, context.getString(R.string.ok), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val TAG = "MoveCacheFilesWorker"
    }

}

