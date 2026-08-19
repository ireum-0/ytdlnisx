package com.ireum.ytdl.util

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ireum.ytdl.MainActivity
import com.ireum.ytdl.R
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.receiver.CancelLowQualityRedownloadReceiver

class LowQualityRedownloadNotification(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private var lastScanNotificationAt = 0L

    fun build(progress: LowQualityRedownloadProgress): Notification {
        val notificationState = LowQualityRedownloadNotificationPolicy.state(progress)
        val ongoing = !progress.isTerminal &&
            notificationState != LowQualityRedownloadNotificationState.READY_TO_REVIEW
        val builder = NotificationCompat.Builder(context, NotificationUtil.DOWNLOAD_WORKER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.low_quality_redownload_title))
            .setContentText(contentText(progress, notificationState))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(contentText(progress, notificationState))
            )
            .setContentIntent(openHistoryIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        when (notificationState) {
            LowQualityRedownloadNotificationState.SCANNING -> builder.setProgress(
                progress.scanTotal.coerceAtLeast(1),
                progress.scanProcessed.coerceAtMost(progress.scanTotal.coerceAtLeast(1)),
                progress.scanTotal <= 0
            )
            LowQualityRedownloadNotificationState.PREPARING -> builder.setProgress(
                progress.selected.coerceAtLeast(1),
                progress.qualificationProcessed.coerceAtMost(progress.selected.coerceAtLeast(1)),
                progress.selected <= 0
            )
            LowQualityRedownloadNotificationState.DOWNLOADING -> builder.setProgress(
                progress.selected.coerceAtLeast(1),
                progress.completed.coerceAtMost(progress.selected.coerceAtLeast(1)),
                false
            )
            else -> builder.setProgress(0, 0, false)
        }

        if (LowQualityRedownloadNotificationPolicy.allowCancel(progress)) {
            builder.addAction(
                0,
                context.getString(R.string.cancel),
                cancelIntent(progress.operationId)
            )
        }
        if (ongoing) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    fun update(progress: LowQualityRedownloadProgress, force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (
            !force && progress.phase == com.ireum.ytdl.database.models.LowQualityRedownloadPhase.SCANNING &&
            !LowQualityRedownloadNotificationPolicy.shouldNotify(
                previousAt = lastScanNotificationAt,
                now = now,
                phaseChanged = false,
                stateChanged = false
            )
        ) return
        if (progress.phase == com.ireum.ytdl.database.models.LowQualityRedownloadPhase.SCANNING) {
            lastScanNotificationAt = now
        }
        runCatching { manager.notify(NOTIFICATION_ID, build(progress)) }
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun contentText(
        progress: LowQualityRedownloadProgress,
        state: LowQualityRedownloadNotificationState
    ): String = when (state) {
        LowQualityRedownloadNotificationState.SCANNING -> context.getString(
            R.string.low_quality_scan_progress,
            progress.scanProcessed,
            progress.scanTotal
        )
        LowQualityRedownloadNotificationState.READY_TO_REVIEW -> context.getString(
            R.string.low_quality_ready_to_review,
            progress.provisional
        )
        LowQualityRedownloadNotificationState.PREPARING -> context.getString(
            R.string.low_quality_verify_progress,
            progress.qualificationProcessed,
            progress.selected
        )
        LowQualityRedownloadNotificationState.DOWNLOADING -> context.getString(
            R.string.low_quality_download_progress,
            progress.completed,
            progress.selected,
            progress.queued,
            progress.active,
            progress.waiting
        )
        LowQualityRedownloadNotificationState.COMPLETED -> context.getString(
            R.string.low_quality_batch_completed,
            progress.succeeded,
            progress.skipped
        )
        LowQualityRedownloadNotificationState.PARTIAL_FAILURE -> context.getString(
            R.string.low_quality_batch_partial,
            progress.succeeded,
            progress.failed,
            progress.skipped
        )
        LowQualityRedownloadNotificationState.CANCELLED -> context.getString(
            R.string.low_quality_batch_cancelled,
            progress.succeeded,
            progress.cancelled
        )
        LowQualityRedownloadNotificationState.FAILED -> if (
            progress.terminalReason == LowQualityRedownloadRepository.REASON_SCAN_FAILED
        ) {
            context.getString(R.string.low_quality_scan_failed)
        } else {
            context.getString(R.string.low_quality_batch_failed, progress.failed)
        }
        LowQualityRedownloadNotificationState.NO_CANDIDATES -> if (progress.scanFailures > 0) {
            context.getString(R.string.low_quality_scan_none_with_failures, progress.scanFailures)
        } else {
            context.getString(R.string.low_quality_scan_none)
        }
        LowQualityRedownloadNotificationState.UNRECOVERABLE ->
            context.getString(R.string.low_quality_batch_unrecoverable)
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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelIntent(operationId: String): PendingIntent {
        val intent = Intent(context, CancelLowQualityRedownloadReceiver::class.java).apply {
            action = CancelLowQualityRedownloadReceiver.ACTION_CANCEL
            putExtra(CancelLowQualityRedownloadReceiver.EXTRA_OPERATION_ID, operationId)
        }
        return PendingIntent.getBroadcast(
            context,
            operationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val NOTIFICATION_ID = 1000000003
    }
}
