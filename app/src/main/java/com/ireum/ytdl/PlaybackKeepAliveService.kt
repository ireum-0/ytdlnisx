package com.ireum.ytdl

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ireum.ytdl.util.NotificationUtil

class PlaybackKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank {
                    getString(R.string.app_name)
                }
                val content = intent.getStringExtra(EXTRA_CONTENT).orEmpty().ifBlank {
                    getString(R.string.playback_background_notification)
                }
                val openIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_OPEN_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_OPEN_INTENT)
                }
                startForeground(
                    NotificationUtil.PLAYBACK_NOTIFICATION_ID,
                    buildNotification(title, content, openIntent)
                )
            }
        }
        return START_STICKY
    }

    private fun buildNotification(title: String, content: String, openIntent: Intent?): Notification {
        val resolvedOpenIntent = (openIntent ?: Intent(this, VideoPlayerActivity::class.java)).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            NotificationUtil.PLAYBACK_NOTIFICATION_ID,
            resolvedOpenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotificationUtil.PLAYBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentTitle(title)
            .setContentText(content)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val ACTION_START = "com.ireum.ytdl.playback_keepalive.START"
        private const val ACTION_STOP = "com.ireum.ytdl.playback_keepalive.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CONTENT = "content"
        private const val EXTRA_OPEN_INTENT = "open_intent"

        fun start(context: Context, title: String, content: String, openIntent: Intent?) {
            val intent = Intent(context, PlaybackKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
                if (openIntent != null) {
                    putExtra(EXTRA_OPEN_INTENT, openIntent)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackKeepAliveService::class.java))
        }
    }

    private fun describeIntent(intent: Intent?): String {
        val safeIntent = intent ?: return "null"
        return "action=${safeIntent.action} flags=0x${safeIntent.flags.toString(16)} " +
            "returnDestination=${safeIntent.getStringExtra(VideoPlayerActivity.EXTRA_RETURN_DESTINATION)} " +
            "destination=${safeIntent.getStringExtra("destination")} " +
            "hasRestore=${safeIntent.hasExtra(com.ireum.ytdl.ui.downloads.HistoryFragment.EXTRA_RESTORE_SCROLL_POSITION)} " +
            "hasVideoPath=${safeIntent.hasExtra("video_path")}"
    }
}
