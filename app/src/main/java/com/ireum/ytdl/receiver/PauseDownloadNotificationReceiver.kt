package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.DownloadWorker
import com.ireum.ytdl.work.withDownloadWorkerExecutionLock
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
                    withDownloadWorkerExecutionLock {
                        val item = dbManager.downloadDao.getDownloadById(id.toLong())
                        val expectedExecutionId = intent.getStringExtra("executionId").orEmpty()
                        if (
                            expectedExecutionId.isBlank() ||
                            item.executionId != expectedExecutionId
                        ) {
                            return@withDownloadWorkerExecutionLock
                        }
                        DownloadRepository(dbManager).setDownloadStatus(
                            item.id,
                            DownloadRepository.Status.Paused,
                        )
                        notificationUtil.cancelDownloadNotification(id)
                        DownloadWorker.cancelProcessesForExecution(
                            item.id,
                            expectedExecutionId,
                        )
                    }
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
