package com.ireum.ytdl.util

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ireum.ytdl.MainActivity
import com.ireum.ytdl.R
import com.ireum.ytdl.database.models.HistoryDateFetchOperationState
import com.ireum.ytdl.receiver.CancelHistoryDateFetchReceiver

class HistoryDateFetchNotification(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun buildActive(progress: HistoryDateFetchProgress): Notification {
        val counts = progress.counts
        return NotificationCompat.Builder(context, NotificationUtil.DOWNLOAD_WORKER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.fetch_missing_source_dates))
            .setContentText(
                context.getString(R.string.fetch_source_dates_progress, counts.processed, counts.total)
            )
            .setContentIntent(openHistoryIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(
                counts.total.coerceAtLeast(1),
                counts.processed.coerceAtMost(counts.total.coerceAtLeast(1)),
                counts.total == 0,
            )
            .addAction(
                0,
                context.getString(R.string.cancel),
                cancelIntent(progress.operation.operationId),
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateActive(progress: HistoryDateFetchProgress) {
        if (progress.isTerminal) return
        runCatching { manager.notify(NOTIFICATION_ID, buildActive(progress)) }
    }

    fun notifyTerminal(progress: HistoryDateFetchProgress) {
        if (!progress.isTerminal) return
        val counts = progress.counts
        val text = when (progress.operation.stateValue) {
            HistoryDateFetchOperationState.CANCELLED ->
                context.getString(R.string.fetch_source_dates_cancelled, counts.updated)
            HistoryDateFetchOperationState.FAILED ->
                context.getString(R.string.fetch_source_dates_failed)
            HistoryDateFetchOperationState.COMPLETED ->
                context.getString(R.string.fetch_source_dates_result, counts.updated, counts.total)
            HistoryDateFetchOperationState.RUNNING -> return
        }
        val notification = NotificationCompat.Builder(
            context,
            NotificationUtil.DOWNLOAD_WORKER_CHANNEL_ID,
        )
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.fetch_missing_source_dates))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openHistoryIntent())
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun openHistoryIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("destination", "Downloads")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(operationId: String): PendingIntent {
        val intent = Intent(context, CancelHistoryDateFetchReceiver::class.java).apply {
            action = CancelHistoryDateFetchReceiver.ACTION_CANCEL
            putExtra(CancelHistoryDateFetchReceiver.EXTRA_OPERATION_ID, operationId)
        }
        return PendingIntent.getBroadcast(
            context,
            operationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val NOTIFICATION_ID = 1000000004
    }
}
