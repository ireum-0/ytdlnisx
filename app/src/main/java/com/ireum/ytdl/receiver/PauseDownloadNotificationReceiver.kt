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
import kotlinx.coroutines.withContext

class PauseDownloadNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val result = goAsync()
        val id = intent.getIntExtra("itemID", 0)
        if (id == 0) {
            result.finish()
            return
        }
        runCatching {
            val title = intent.getStringExtra("title")
            val notificationUtil = NotificationUtil(c)
            val dbManager = DBManager.getInstance(c)
            CoroutineScope(Dispatchers.IO).launch{
                try {
                    val item = dbManager.downloadDao.getDownloadById(id.toLong())
                    item.status = DownloadRepository.Status.Paused.toString()
                    dbManager.downloadDao.update(item)
                    notificationUtil.cancelDownloadNotification(id)
                    YoutubeDL.getInstance().destroyProcessById(id.toString())
                    YoutubeDLCompat.destroyProcessById(id.toString())
                    DownloadWorker.cancelPostProcessingById(id.toLong())
                }finally {
                    withContext(Dispatchers.Main){
                        notificationUtil.createResumeDownload(id, title)
                        result.finish()
                    }
                }
            }
        }.onFailure {
            result.finish()
        }
    }
}
