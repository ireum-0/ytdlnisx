package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.DownloadWorker
import com.ireum.ytdl.work.withDownloadWorkerExecutionSideEffectLease
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
                var paused = false
                val expectedExecutionId = intent.getStringExtra("executionId").orEmpty()
                try {
                    if (expectedExecutionId.isNotBlank()) {
                        withDownloadWorkerExecutionSideEffectLease(id.toLong(), expectedExecutionId) {
                            val didPause = withDownloadWorkerExecutionLock {
                                val item = dbManager.downloadDao.getDownloadById(id.toLong())
                                if (item.executionId != expectedExecutionId) {
                                    return@withDownloadWorkerExecutionLock false
                                }
                                DownloadRepository(dbManager).setDownloadStatus(
                                    item.id,
                                    DownloadRepository.Status.Paused,
                                    expectedExecutionId,
                                )
                            }
                            paused = didPause
                            if (didPause) {
                                // The durable pause won before the native
                                // process cancellation.  The per-Download
                                // lease prevents a newer attempt from starting
                                // until this exact E1 is quiesced.
                                DownloadWorker.cancelProcessesForExecution(
                                    id.toLong(),
                                    expectedExecutionId,
                                )
                                notificationUtil.cancelRunningDownloadNotification(id)
                            }
                        }
                    }
                }finally {
                    withContext(Dispatchers.Main){
                        if (paused) {
                            notificationUtil.createResumeDownload(
                                itemID = id,
                                title = title,
                                expectedExecutionId = expectedExecutionId,
                            )
                        }
                        result.finish()
                    }
                }
            }
        }.onFailure {
            result.finish()
        }
    }
}
