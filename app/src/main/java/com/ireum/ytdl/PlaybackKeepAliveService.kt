package com.ireum.ytdl

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
                startForeground(NotificationUtil.PLAYBACK_NOTIFICATION_ID, buildNotification(title, content))
            }
        }
        return START_STICKY
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, VideoPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            NotificationUtil.PLAYBACK_NOTIFICATION_ID,
            openIntent,
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

        fun start(context: Context, title: String, content: String) {
            val intent = Intent(context, PlaybackKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
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
}
