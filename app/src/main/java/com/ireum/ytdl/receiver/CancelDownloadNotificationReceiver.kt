package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.work.DownloadWorker
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CancelDownloadNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getIntExtra("itemID", 0)
        if (id > 0) {
            val result = goAsync()
            runCatching {
                val notificationUtil = NotificationUtil(c)
                val dbManager = DBManager.getInstance(c)
                CoroutineScope(Dispatchers.IO).launch{
                    try {
                        runCatching {
                            val item = dbManager.downloadDao.getDownloadById(id.toLong())
                            item.status = DownloadRepository.Status.Cancelled.toString()
                            dbManager.downloadDao.update(item)
                        }
                        YoutubeDL.getInstance().destroyProcessById(id.toString())
                        YoutubeDLCompat.destroyProcessById(id.toString())
                        DownloadWorker.cancelPostProcessingById(id.toLong())
                        notificationUtil.cancelDownloadNotification(id)
                        runCatching {
                            dbManager.terminalDao.delete(id.toLong())
                        }
                    } finally {
                        result.finish()
                    }
                }
            }.onFailure {
                result.finish()
            }

        }
    }
}
