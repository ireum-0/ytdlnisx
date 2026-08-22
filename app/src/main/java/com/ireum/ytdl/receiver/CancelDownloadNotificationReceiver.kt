package com.ireum.ytdl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.DownloadWorker
import com.ireum.ytdl.work.LowQualityRedownloadLedger
import com.ireum.ytdl.work.withDownloadWorkerExecutionSideEffectLease
import com.ireum.ytdl.work.withDownloadWorkerExecutionLock
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
                        val expectedExecutionId = intent.getStringExtra("executionId").orEmpty()
                        if (expectedExecutionId.isBlank()) return@launch
                        val cancel = suspend {
                            withDownloadWorkerExecutionLock {
                                val item = dbManager.downloadDao.getNullableDownloadById(id.toLong())
                            if (
                                item?.executionId != expectedExecutionId
                            ) {
                                return@withDownloadWorkerExecutionLock
                            }
                            val affectedOperationIds = DownloadRepository(dbManager).cancelByUser(
                                id.toLong(),
                                expectedExecutionId,
                            )
                            val committed = dbManager.downloadDao.getNullableDownloadById(id.toLong())?.let {
                                        it.status == DownloadRepository.Status.Cancelled.name &&
                                    it.executionId == expectedExecutionId
                            } == true
                            if (!committed) {
                                return@withDownloadWorkerExecutionLock
                            }
                            if (expectedExecutionId.isNotBlank()) {
                                DownloadWorker.cancelProcessesForExecution(
                                    id.toLong(),
                                    expectedExecutionId,
                                )
                            }
                            LowQualityRedownloadLedger.refresh(c, affectedOperationIds)
                            notificationUtil.cancelRunningDownloadNotification(id)
                            runCatching {
                                dbManager.terminalDao.delete(id.toLong())
                            }
                            }
                        }
                        withDownloadWorkerExecutionSideEffectLease(id.toLong(), expectedExecutionId) {
                            cancel()
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
