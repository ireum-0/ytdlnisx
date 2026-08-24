package com.ireum.ytdl.work

import android.content.Context
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.HistoryRedownloadMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Application-lifecycle recovery for rows whose worker carrier disappeared.
 * The Download row is the durable source of truth; the small synchronous
 * journal makes the exceptional cleanup handoff explicit and observable.  A
 * cold application start invokes this independently of WorkManager and of a
 * DownloadWorker already being alive.
 */
internal object DownloadExecutionRecovery {
    private const val PREFS_NAME = "download-execution-recovery"

    fun recordPending(context: Context, item: DownloadItem): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(item.id.toString(), item.executionId)
            .commit()

    private fun clearPending(context: Context, id: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(id.toString())
            .apply()
    }

    suspend fun reconcile(
        context: Context,
        dbManager: DBManager = DBManager.getInstance(context),
    ) = withContext(Dispatchers.IO + NonCancellable) {
        val repository = DownloadRepository(dbManager)
        val candidates = withDownloadWorkerExecutionLock {
            val running = dbManager.downloadDao.getActiveAndPostProcessingDownloadsList()
            val committed = dbManager.downloadDao.getCommittedHistoryReplacementDownloads()
            (running + committed).distinctBy { it.id }
        }
        var firstFailure: Exception? = null

        candidates.forEach { snapshot ->
            try {
                withDownloadWorkerExecutionSideEffectLease(
                    downloadId = snapshot.id,
                    executionId = snapshot.executionId,
                ) {
                    var clearJournal = false
                    withDownloadWorkerExecutionLock {
                        val current = dbManager.downloadDao.getNullableDownloadById(snapshot.id)
                        if (current == null) {
                            clearJournal = true
                        } else if (current.executionId != snapshot.executionId) {
                            // E1 must never reclassify E2.  Leave the journal
                            // for the next lifecycle pass to observe E2.
                        } else {
                            val committed = isCommittedHistoryReplacement(dbManager, current)
                            val owned = current.executionId.isNotBlank() &&
                                DownloadWorkerExecutionOwners.isOwnedBy(
                                    current.id,
                                    current.executionId,
                                )
                            if (committed && !owned) {
                                repository.completeAndDelete(
                                    id = current.id,
                                    expectedExecutionId = current.executionId,
                                )
                                clearJournal = true
                            } else if (
                                !owned && current.status in setOf(
                                    DownloadRepository.Status.Active.name,
                                    DownloadRepository.Status.PostProcessing.name,
                                )
                            ) {
                                when (repository.requeueRunningDownload(current.id, current.executionId)) {
                                    DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
                                    DownloadRepository.RunningDownloadRequeueResult.REFUSAL_CONVERGED -> {
                                        clearJournal = true
                                    }
                                    DownloadRepository.RunningDownloadRequeueResult.COMMITTED_HISTORY_FINALIZATION_DEBT -> {
                                        repository.completeAndDelete(
                                            id = current.id,
                                            expectedExecutionId = current.executionId,
                                        )
                                        clearJournal = true
                                    }
                                    DownloadRepository.RunningDownloadRequeueResult.OWNERSHIP_LOST -> Unit
                                    DownloadRepository.RunningDownloadRequeueResult.NOT_RUNNING -> {
                                        clearJournal = true
                                    }
                                }
                            } else if (!owned) {
                                clearJournal = true
                            }
                        }
                    }
                    if (clearJournal) clearPending(context, snapshot.id)
                }
            } catch (failure: Exception) {
                firstFailure = firstFailure.addOrSuppress(failure)
            }
        }

        firstFailure?.let { throw it }
    }

    private fun isCommittedHistoryReplacement(
        dbManager: DBManager,
        item: DownloadItem,
    ): Boolean {
        val marker = HistoryRedownloadMarker.parse(item.playlistURL) ?: return false
        return dbManager.historyDao.getNullableItem(marker.historyId)?.downloadId == item.id
    }
}
