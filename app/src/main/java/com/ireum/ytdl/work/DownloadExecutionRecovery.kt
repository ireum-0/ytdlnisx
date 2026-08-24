package com.ireum.ytdl.work

import android.content.Context
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.download.DownloadIssue
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
    private const val NATIVE_QUIESCENCE_SUFFIX = ":native-quiescence"
    private const val ISSUE_CODE_SUFFIX = ":issue-code"
    private const val ISSUE_STAGE_SUFFIX = ":issue-stage"

    private data class PendingRecovery(
        val executionId: String,
        val nativeQuiescencePending: Boolean,
        val authoritativeIssue: DownloadIssue?,
    )

    fun recordPending(
        context: Context,
        item: DownloadItem,
        authoritativeIssue: DownloadIssue? = null,
    ): Boolean {
        val id = item.id.toString()
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(id, item.executionId)
            .putBoolean(
                id + NATIVE_QUIESCENCE_SUFFIX,
                item.executionId.isNotBlank(),
            )
        if (authoritativeIssue == null) {
            editor.remove(id + ISSUE_CODE_SUFFIX)
                .remove(id + ISSUE_STAGE_SUFFIX)
        } else {
            editor.putString(id + ISSUE_CODE_SUFFIX, authoritativeIssue.code.name)
                .putString(id + ISSUE_STAGE_SUFFIX, authoritativeIssue.stage.name)
        }
        return editor.commit()
    }

    /**
     * Marks only the exact recorded execution as native-quiescent.  A failed
     * commit leaves the durable native barrier in place, so startup cannot
     * reinterpret the row as ordinary runnable work.
     */
    fun markNativeQuiescent(
        context: Context,
        downloadId: Long,
        executionId: String,
    ): Boolean {
        val id = downloadId.toString()
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(id, null) != executionId) return false
        return preferences.edit()
            .putBoolean(id + NATIVE_QUIESCENCE_SUFFIX, false)
            .commit()
    }

    private fun clearPending(
        context: Context,
        id: Long,
        expectedExecutionId: String,
    ): Boolean {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(id.toString(), null) != expectedExecutionId) return false
        return preferences
            .edit()
            .remove(id.toString())
            .remove(id.toString() + NATIVE_QUIESCENCE_SUFFIX)
            .remove(id.toString() + ISSUE_CODE_SUFFIX)
            .remove(id.toString() + ISSUE_STAGE_SUFFIX)
            .commit()
    }

    private fun readPending(
        context: Context,
        downloadId: Long,
    ): PendingRecovery? {
        val id = downloadId.toString()
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val executionId = preferences.getString(id, null) ?: return null
        val issueCode = preferences.getString(id + ISSUE_CODE_SUFFIX, null)
        val issueStage = preferences.getString(id + ISSUE_STAGE_SUFFIX, null)
        check((issueCode == null) == (issueStage == null)) {
            "Incomplete durable History refusal carrier for download $downloadId"
        }
        val issue = if (issueCode == null) {
            null
        } else {
            val parsed = HistoryReplacementDiagnostic.persistedHistoryReplacementIssue(issueCode)
                ?: error("Unknown durable History refusal code $issueCode for download $downloadId")
            check(issueStage == parsed.stage.name) {
                "Unknown durable History refusal stage $issueStage for download $downloadId"
            }
            parsed
        }
        return PendingRecovery(
            executionId = executionId,
            nativeQuiescencePending = preferences.getBoolean(
                id + NATIVE_QUIESCENCE_SUFFIX,
                executionId.isNotBlank(),
            ),
            authoritativeIssue = issue,
        )
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
                    val pending = readPending(context, snapshot.id)
                    val current = withDownloadWorkerExecutionLock {
                        dbManager.downloadDao.getNullableDownloadById(snapshot.id)
                    }
                    if (current == null) {
                        clearJournal = true
                    } else if (current.executionId != snapshot.executionId) {
                        // E1 must never reclassify E2.  Leave the journal for
                        // the next lifecycle pass to observe E2.
                    } else {
                        val pendingForCurrent = pending?.takeIf {
                            it.executionId == current.executionId
                        }
                        val journalBelongsToAnotherExecution =
                            pending != null && pendingForCurrent == null
                        val owned = current.executionId.isNotBlank() &&
                            DownloadWorkerExecutionOwners.isOwnedBy(
                                current.id,
                                current.executionId,
                            )
                        val anotherExecutionOwnsTheRow =
                            DownloadWorkerExecutionOwners.ownerOf(current.id)?.let {
                                it != current.executionId
                            } == true
                        val anotherExecutionHasNativeProcess =
                            DownloadWorker.hasConflictingNativeProcess(
                                current.id,
                                current.executionId,
                            )
                        if (
                            !owned &&
                                !journalBelongsToAnotherExecution &&
                                !anotherExecutionOwnsTheRow &&
                                !anotherExecutionHasNativeProcess
                        ) {
                            // The execution-owner map is not a native
                            // quiescence proof.  A Process can remain in the
                            // same application after its coroutine owner has
                            // disappeared, so inspect every exact registry
                            // before making the row reusable.
                            val nativeQuiescenceRequired =
                                pendingForCurrent?.nativeQuiescencePending == true ||
                                    DownloadWorker.hasRegisteredNativeProcess(
                                        current.id,
                                        current.executionId,
                                    )
                            if (nativeQuiescenceRequired) {
                                check(
                                    DownloadWorker.cancelProcessesForExecution(
                                        current.id,
                                        current.executionId,
                                    )
                                ) {
                                    "Native process owner changed while recovering download ${current.id}"
                                }
                                if (
                                    pendingForCurrent?.nativeQuiescencePending == true &&
                                    !markNativeQuiescent(
                                        context,
                                        current.id,
                                        current.executionId,
                                    )
                                ) {
                                    throw NativeProcessQuiescenceException(
                                        current.id,
                                        current.executionId,
                                    )
                                }
                            }

                            val latest = withDownloadWorkerExecutionLock {
                                dbManager.downloadDao.getNullableDownloadById(snapshot.id)
                            }
                            if (latest == null) {
                                clearJournal = true
                            } else if (latest.executionId != snapshot.executionId) {
                                // The exact lease prevented this in normal
                                // operation, but retain the same stale-E1
                                // fail-closed rule if a caller raced us.
                            } else if (isCommittedHistoryReplacement(dbManager, latest)) {
                                repository.completeAndDelete(
                                    id = latest.id,
                                    expectedExecutionId = latest.executionId,
                                )
                                clearJournal = true
                            } else if (
                                latest.status in setOf(
                                    DownloadRepository.Status.Active.name,
                                    DownloadRepository.Status.PostProcessing.name,
                                )
                            ) {
                                when (
                                    cleanupStoppedDownloadExecution(
                                        repository = repository,
                                        downloadId = latest.id,
                                        executionId = latest.executionId,
                                        authoritativeIssue = pendingForCurrent?.authoritativeIssue,
                                    )
                                ) {
                                    DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
                                    DownloadRepository.RunningDownloadRequeueResult.REFUSAL_CONVERGED,
                                    DownloadRepository.RunningDownloadRequeueResult.COMMITTED_HISTORY_FINALIZATION_DEBT -> {
                                        clearJournal = true
                                    }
                                    DownloadRepository.RunningDownloadRequeueResult.OWNERSHIP_LOST -> Unit
                                    DownloadRepository.RunningDownloadRequeueResult.NOT_RUNNING -> {
                                        val after = dbManager.downloadDao
                                            .getNullableDownloadById(latest.id)
                                        check(
                                            after == null ||
                                                after.executionId != latest.executionId ||
                                                after.status !in setOf(
                                                    DownloadRepository.Status.Active.name,
                                                    DownloadRepository.Status.PostProcessing.name,
                                                )
                                        ) {
                                            "Abandoned download execution remained running after recovery " +
                                                "id=${latest.id}"
                                        }
                                        clearJournal = true
                                    }
                                }
                            } else if (pendingForCurrent?.authoritativeIssue != null) {
                                // A non-running row may already have been
                                // converged by a previous pass.  Do not clear
                                // an issue journal until its exact carrier is
                                // visible in Room.
                                val issue = requireNotNull(pendingForCurrent.authoritativeIssue)
                                val barrier = dbManager.historyReplacementBarrierDao
                                    .getByDownloadId(latest.id)
                                check(
                                    latest.lastIssueCode == issue.code.name &&
                                        latest.lastIssueStage == issue.stage.name ||
                                        barrier?.issueCode == issue.code.name &&
                                            barrier.issueStage == issue.stage.name
                                ) {
                                    "Durable History refusal carrier was missing for download ${latest.id}"
                                }
                                clearJournal = true
                            } else {
                                clearJournal = true
                            }
                        }
                    }
                    if (clearJournal) {
                        clearPending(
                            context = context,
                            id = snapshot.id,
                            expectedExecutionId = snapshot.executionId,
                        )
                    }
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
