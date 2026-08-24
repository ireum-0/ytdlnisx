package com.ireum.ytdl.work

import android.content.Context
import android.content.SharedPreferences
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementRefusal
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.download.DownloadIssue
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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
    private const val TERMINAL_ISSUE_CODE_SUFFIX = ":terminal-issue-code"
    private const val TERMINAL_ISSUE_STAGE_SUFFIX = ":terminal-issue-stage"

    internal enum class JournalCommitOperation {
        RECORD,
        MARK_NATIVE_QUIESCENT,
        CLEAR,
    }

    /** Test seam for deterministic SharedPreferences commit failures. */
    @Volatile
    internal var commitOverride:
        ((JournalCommitOperation, SharedPreferences.Editor) -> Boolean)? = null

    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryJobs = ConcurrentHashMap<Long, Job>()

    private data class PendingRecovery(
        val executionId: String,
        val nativeQuiescencePending: Boolean,
        val authoritativeIssue: DownloadIssue?,
    )

    private fun commit(
        operation: JournalCommitOperation,
        editor: SharedPreferences.Editor,
    ): Boolean = commitOverride?.invoke(operation, editor) ?: editor.commit()

    fun recordPending(
        context: Context,
        item: DownloadItem,
        authoritativeIssue: DownloadIssue? = null,
    ): Boolean {
        val refusal = authoritativeIssue?.let(HistoryReplacementRefusal::from)
        val terminalIssue = authoritativeIssue?.takeUnless { refusal != null }
        if (
            terminalIssue != null &&
                terminalIssue.code != DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED
        ) {
            // The refusal fields and the terminal recovery fields are both
            // deliberately closed domains.  Do not serialize an arbitrary
            // worker diagnostic into either carrier.
            return false
        }
        val id = item.id.toString()
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(id, item.executionId)
            .putBoolean(
                id + NATIVE_QUIESCENCE_SUFFIX,
                item.executionId.isNotBlank(),
            )
        if (refusal == null) {
            editor.remove(id + ISSUE_CODE_SUFFIX)
                .remove(id + ISSUE_STAGE_SUFFIX)
        } else {
            editor.putString(id + ISSUE_CODE_SUFFIX, refusal.code.name)
                .putString(id + ISSUE_STAGE_SUFFIX, refusal.stage.name)
        }
        if (terminalIssue == null) {
            editor.remove(id + TERMINAL_ISSUE_CODE_SUFFIX)
                .remove(id + TERMINAL_ISSUE_STAGE_SUFFIX)
        } else {
            editor.putString(id + TERMINAL_ISSUE_CODE_SUFFIX, terminalIssue.code.name)
                .putString(id + TERMINAL_ISSUE_STAGE_SUFFIX, terminalIssue.stage.name)
        }
        return commit(JournalCommitOperation.RECORD, editor)
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
        return commit(
            JournalCommitOperation.MARK_NATIVE_QUIESCENT,
            preferences.edit()
                .putBoolean(id + NATIVE_QUIESCENCE_SUFFIX, false)
        )
    }

    private fun clearPending(
        context: Context,
        id: Long,
        expectedExecutionId: String,
    ): Boolean {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(id.toString(), null) != expectedExecutionId) return false
        val editor = preferences
            .edit()
            .remove(id.toString())
            .remove(id.toString() + NATIVE_QUIESCENCE_SUFFIX)
            .remove(id.toString() + ISSUE_CODE_SUFFIX)
            .remove(id.toString() + ISSUE_STAGE_SUFFIX)
            .remove(id.toString() + TERMINAL_ISSUE_CODE_SUFFIX)
            .remove(id.toString() + TERMINAL_ISSUE_STAGE_SUFFIX)
        return commit(JournalCommitOperation.CLEAR, editor)
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
        val legacyTerminalIssue = issueCode
            ?.takeIf { it == DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED.name }
            ?.let {
                check(issueStage == HistoryReplacementDiagnostic.qualityAuthorityLostIssue().stage.name) {
                    "Unknown legacy terminal recovery stage $issueStage for download $downloadId"
                }
                HistoryReplacementDiagnostic.qualityAuthorityLostIssue()
            }
        val issue = if (issueCode == null || legacyTerminalIssue != null) {
            null
        } else {
            val refusalCode = requireNotNull(issueCode)
            val parsed = HistoryReplacementDiagnostic.persistedHistoryReplacementIssue(refusalCode)
                ?: error("Unknown durable History refusal code $refusalCode for download $downloadId")
            check(issueStage == parsed.stage.name) {
                "Unknown durable History refusal stage $issueStage for download $downloadId"
            }
            parsed
        }
        val terminalIssueCode = preferences.getString(id + TERMINAL_ISSUE_CODE_SUFFIX, null)
        val terminalIssueStage = preferences.getString(id + TERMINAL_ISSUE_STAGE_SUFFIX, null)
        check(legacyTerminalIssue == null || (terminalIssueCode == null && terminalIssueStage == null)) {
            "Recovery journal contains duplicate terminal issue carriers for download $downloadId"
        }
        check((terminalIssueCode == null) == (terminalIssueStage == null)) {
            "Incomplete durable terminal recovery carrier for download $downloadId"
        }
        val terminalIssue = if (legacyTerminalIssue != null) {
            legacyTerminalIssue
        } else if (terminalIssueCode == null) {
            null
        } else {
            check(issue == null) {
                "Recovery journal contains both refusal and terminal issue for download $downloadId"
            }
            check(terminalIssueCode == DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED.name) {
                "Unknown durable terminal recovery code $terminalIssueCode for download $downloadId"
            }
            val parsed = HistoryReplacementDiagnostic.qualityAuthorityLostIssue()
            check(terminalIssueStage == parsed.stage.name) {
                "Unknown durable terminal recovery stage $terminalIssueStage for download $downloadId"
            }
            parsed
        }
        return PendingRecovery(
            executionId = executionId,
            nativeQuiescencePending = preferences.getBoolean(
                id + NATIVE_QUIESCENCE_SUFFIX,
                executionId.isNotBlank(),
            ),
            authoritativeIssue = issue ?: terminalIssue,
        )
    }

    internal fun pendingDownloadIds(context: Context): Set<Long> = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .all
        .keys
        .mapNotNull { it.toLongOrNull() }
        .toSet()

    suspend fun reconcile(
        context: Context,
        dbManager: DBManager = DBManager.getInstance(context),
    ) = withContext(Dispatchers.IO + NonCancellable) {
        YtdlpNativeProcessBarrier.configure(context)
        val repository = DownloadRepository(dbManager)
        val candidates = withDownloadWorkerExecutionLock {
            val running = dbManager.downloadDao.getActiveAndPostProcessingDownloadsList()
            val committed = dbManager.downloadDao.getCommittedHistoryReplacementDownloads()
            val journalRows = pendingDownloadIds(context)
                .takeIf { it.isNotEmpty() }
                ?.toList()
                ?.let(dbManager.downloadDao::getDownloadsByIds)
                .orEmpty()
            val nativeRows = YtdlpNativeProcessBarrier.downloadProcesses(context)
                .mapNotNull { process ->
                    dbManager.downloadDao.getNullableDownloadById(process.downloadId)
                }
            (running + committed + journalRows + nativeRows).distinctBy { it.id }
        }
        var firstFailure: Exception? = null

        candidates.forEach { snapshot ->
            try {
                withDownloadWorkerExecutionSideEffectLease(
                    downloadId = snapshot.id,
                    executionId = snapshot.executionId,
                ) {
                    var clearJournal = false
                    var pending = readPending(context, snapshot.id)
                    var current = withDownloadWorkerExecutionLock {
                        dbManager.downloadDao.getNullableDownloadById(snapshot.id)
                    }

                    suspend fun quiesceExactExecution(
                        executionId: String,
                        nativePending: Boolean,
                    ) {
                        if (
                            DownloadWorkerExecutionOwners.ownerOf(snapshot.id)?.let {
                                it != executionId
                            } == true ||
                                DownloadWorkerProcessOwners.ownerOf(snapshot.id)?.let {
                                    it != executionId
                                } == true
                        ) {
                            throw NativeProcessQuiescenceException(snapshot.id, executionId)
                        }
                        val nativeVisible = nativePending ||
                            DownloadWorker.hasRegisteredNativeProcess(snapshot.id, executionId)
                        if (nativeVisible) {
                            check(
                                DownloadWorker.cancelProcessesForExecution(
                                    snapshot.id,
                                    executionId,
                                )
                            ) {
                                "Native process owner changed while recovering download ${snapshot.id}"
                            }
                        }
                        if (nativePending && !markNativeQuiescent(context, snapshot.id, executionId)) {
                            throw NativeProcessQuiescenceException(snapshot.id, executionId)
                        }
                    }

                    if (current == null) {
                        val orphanedExecutionIds = buildList {
                            pending?.let { add(it.executionId to it.nativeQuiescencePending) }
                            YtdlpNativeProcessBarrier.downloadProcesses(context)
                                .filter { it.downloadId == snapshot.id }
                                .forEach { add(it.executionId to false) }
                        }.distinctBy { it.first }
                        orphanedExecutionIds.forEach { (executionId, nativePending) ->
                            quiesceExactExecution(
                                executionId = executionId,
                                nativePending = nativePending ||
                                    pending?.let {
                                        it.executionId == executionId && it.nativeQuiescencePending
                                    } == true,
                            )
                            DownloadWorkerExecutionOwners.release(snapshot.id, executionId)
                            if (pending?.executionId == executionId) {
                                clearPending(
                                    context = context,
                                    id = snapshot.id,
                                    expectedExecutionId = executionId,
                                )
                            }
                        }
                        clearJournal = pending != null
                    } else {
                        val currentSnapshot = requireNotNull(current)
                        // A journal mismatch is not a reason to stop looking
                        // at the current DB execution forever.  If no live
                        // owner for the current execution exists, first
                        // quiesce the exact journal token (when it is still
                        // addressable), then process the current row.  This
                        // never passes E1's token to an E2 DB mutation.
                        val stalePending = pending?.takeUnless {
                            it.executionId == currentSnapshot.executionId
                        }
                        val staleNativeExecutionIds = YtdlpNativeProcessBarrier
                            .downloadProcesses(context)
                            .asSequence()
                            .filter { it.downloadId == currentSnapshot.id }
                            .map { it.executionId }
                            .filter { it != currentSnapshot.executionId }
                            .distinct()
                            .toList()
                        val staleExecutionIds = buildList {
                            stalePending?.let { add(it.executionId) }
                            addAll(staleNativeExecutionIds)
                        }.distinct()
                        if (staleExecutionIds.isNotEmpty()) {
                            val currentExecutionOwner =
                                DownloadWorkerExecutionOwners.ownerOf(currentSnapshot.id)
                            val currentProcessOwner = DownloadWorkerProcessOwners.ownerOf(currentSnapshot.id)
                            val currentIsLive = currentExecutionOwner == currentSnapshot.executionId ||
                                currentProcessOwner == currentSnapshot.executionId
                            if (!currentIsLive) {
                                staleExecutionIds.forEach { staleExecutionId ->
                                    quiesceExactExecution(
                                        executionId = staleExecutionId,
                                        nativePending = stalePending?.let {
                                            it.executionId == staleExecutionId &&
                                                it.nativeQuiescencePending
                                        } == true,
                                    )
                                    DownloadWorkerExecutionOwners.release(
                                        snapshot.id,
                                        staleExecutionId,
                                    )
                                    if (pending?.executionId == staleExecutionId) {
                                        clearPending(
                                            context = context,
                                            id = snapshot.id,
                                            expectedExecutionId = staleExecutionId,
                                        )
                                    }
                                }
                                pending = readPending(context, snapshot.id)
                            }
                        }

                        current = withDownloadWorkerExecutionLock {
                            dbManager.downloadDao.getNullableDownloadById(snapshot.id)
                        }
                        if (current != null) {
                            val pendingForCurrent = pending?.takeIf {
                                it.executionId == current.executionId
                            }
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
                                    !anotherExecutionOwnsTheRow &&
                                    !anotherExecutionHasNativeProcess
                            ) {
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
                                    dbManager.downloadDao.getNullableDownloadById(current.id)
                                }
                                if (latest == null) {
                                    clearJournal = pending != null
                                } else if (latest.executionId != current.executionId) {
                                    // The exact lease and reread prevent a
                                    // stale recovery token from touching E2.
                                } else if (isCommittedHistoryReplacement(dbManager, latest)) {
                                    repository.completeAndDelete(
                                        id = latest.id,
                                        expectedExecutionId = latest.executionId,
                                    )
                                    clearJournal = pending != null
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
                                        DownloadRepository.RunningDownloadRequeueResult.AUTHORITATIVE_ISSUE_CONVERGED,
                                        DownloadRepository.RunningDownloadRequeueResult.COMMITTED_HISTORY_FINALIZATION_DEBT -> {
                                            clearJournal = pending != null
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
                                            clearJournal = pending != null
                                        }
                                    }
                                } else if (pendingForCurrent?.authoritativeIssue != null) {
                                    val issue = requireNotNull(pendingForCurrent.authoritativeIssue)
                                    val barrier = dbManager.historyReplacementBarrierDao
                                        .getByDownloadId(latest.id)
                                    check(
                                        latest.lastIssueCode == issue.code.name &&
                                            latest.lastIssueStage == issue.stage.name ||
                                            barrier?.issueCode == issue.code.name &&
                                                barrier.issueStage == issue.stage.name
                                    ) {
                                        "Durable authoritative issue carrier was missing for download ${latest.id}"
                                    }
                                    clearJournal = pending != null
                                } else {
                                    clearJournal = pending != null
                                }
                            }
                        }
                    }
                    if (clearJournal) {
                        val expectedJournalExecutionId = pending?.executionId
                            ?: snapshot.executionId
                        clearPending(
                            context = context,
                            id = snapshot.id,
                            expectedExecutionId = expectedJournalExecutionId,
                        )
                    }
                }
            } catch (failure: Exception) {
                scheduleRecovery(context, snapshot.id)
                firstFailure = firstFailure.addOrSuppress(failure)
            }
        }

        firstFailure?.let { throw it }
    }

    /**
     * Keeps same-process recovery alive after a worker has crossed its cleanup
     * boundary.  The DB row/journal remains the durable carrier; this job is
     * only the live retry owner and is never used as the sole restart proof.
     */
    internal fun scheduleRecovery(context: Context, downloadId: Long) {
        val appContext = context.applicationContext
        retryJobs.computeIfAbsent(downloadId) {
            retryScope.launch {
                var retryDelayMillis = 100L
                try {
                    while (true) {
                        val dbManager = DBManager.getInstance(appContext)
                        val current = dbManager.downloadDao.getNullableDownloadById(downloadId)
                        if (current == null) return@launch
                        if (
                            current.executionId.isNotBlank() &&
                                DownloadWorkerExecutionOwners.isOwnedBy(
                                    downloadId,
                                    current.executionId,
                                )
                        ) {
                            // A live worker owns the exact row; its cleanup or
                            // retry protocol remains authoritative.
                            return@launch
                        }
                        runCatching { reconcile(appContext, dbManager) }
                            .onFailure {
                                android.util.Log.w(
                                    "DownloadExecutionRecovery",
                                    "Recovery retry failed id=$downloadId",
                                    it,
                                )
                            }
                        val latest = dbManager.downloadDao.getNullableDownloadById(downloadId)
                        val journalRemains = pendingDownloadIds(appContext).contains(downloadId)
                        val stillRunning = latest?.status in setOf(
                            DownloadRepository.Status.Active.name,
                            DownloadRepository.Status.PostProcessing.name,
                        )
                        if (!journalRemains && !stillRunning) return@launch
                        delay(retryDelayMillis)
                        retryDelayMillis = (retryDelayMillis * 2L).coerceAtMost(5_000L)
                    }
                } finally {
                    retryJobs.remove(downloadId)
                }
            }
        }
    }

    private fun isCommittedHistoryReplacement(
        dbManager: DBManager,
        item: DownloadItem,
    ): Boolean {
        val marker = HistoryRedownloadMarker.parse(item.playlistURL) ?: return false
        return dbManager.historyDao.getNullableItem(marker.historyId)?.downloadId == item.id
    }
}
