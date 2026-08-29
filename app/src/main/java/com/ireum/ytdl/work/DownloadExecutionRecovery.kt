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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    private const val NATIVE_GENERATION_SUFFIX = ":native-generation"
    private const val NATIVE_GENERATION_KIND_SUFFIX = ":native-generation-kind"
    private const val ISSUE_CODE_SUFFIX = ":issue-code"
    private const val ISSUE_STAGE_SUFFIX = ":issue-stage"
    private const val TERMINAL_ISSUE_CODE_SUFFIX = ":terminal-issue-code"
    private const val TERMINAL_ISSUE_STAGE_SUFFIX = ":terminal-issue-stage"
    private const val DISPOSITION_SUFFIX = ":disposition"
    private const val PHASE_SUFFIX = ":phase"

    /**
     * The reason a recovery carrier owns an exact Download execution.  The
     * GENERIC value is also the compatibility interpretation for journals
     * written before operation identity was persisted.
     */
    internal enum class RecoveryDisposition {
        GENERIC,
        USER_CANCEL,
        USER_PAUSE,
        HISTORY_FINALIZATION,
    }

    /**
     * User-stop carriers record the semantic Room decision separately from
     * the native quiescence obligation.  The distinction is durable because
     * a mutable Download row cannot tell whether an earlier stop write ever
     * committed after the process died.
     */
    internal enum class RecoveryPhase {
        SEMANTIC_STOP_PENDING,
        NATIVE_QUIESCENCE_PENDING,
        NATIVE_QUIESCENT,
    }

    internal enum class UserStopPreparation {
        NOT_PENDING,
        READY_FOR_NATIVE_QUIESCENCE,
        COMMITTED_HISTORY_ALREADY_WON,
        BLOCKED,
    }

    internal enum class JournalCommitOperation {
        RECORD,
        MARK_NATIVE_QUIESCENT,
        CLEAR,
    }

    /**
     * Startup recovery is a batch over independent Download identities. A
     * deferred item retains its own durable/live recovery owner and must not
     * turn a healthy queue observation into a global admission failure.
     * Exceptions during discovery of the shared DB/marker namespace still
     * escape reconcile as global failures.
     */
    internal data class ReconcileResult(
        val deferredDownloadIds: Set<Long>,
        val failuresByDownload: Map<Long, Exception>,
    ) {
        val completedCleanly: Boolean
            get() = deferredDownloadIds.isEmpty()
    }

    /** Test seam for deterministic SharedPreferences commit failures. */
    @Volatile
    internal var commitOverride:
        ((JournalCommitOperation, SharedPreferences.Editor) -> Boolean)? = null

    /** Deterministic recovery-owner DB-read fault seam for production-path tests. */
    private val recoveryReadFailureCount = AtomicInteger(0)

    /** Deterministic committed-History finalization fault seam. */
    @Volatile
    internal var failCommittedHistoryFinalizationForTesting: Boolean = false

    /**
     * Test-only boundary hook between recovery discovery and candidate
     * mutation.  It is intentionally outside the per-Download lease so a
     * production admission can win this race and the candidate's exact
     * reread remains the authority.
     */
    @Volatile
    internal var beforeCandidateRecoveryLeaseForTesting: ((Long) -> Unit)? = null

    internal var recoveryReadFailureCountForTesting: Int
        get() = recoveryReadFailureCount.get()
        set(value) {
            recoveryReadFailureCount.set(value.coerceAtLeast(0))
        }

    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryJobs = ConcurrentHashMap<Long, Job>()

    private data class PendingRecovery(
        val executionId: String,
        val nativeQuiescencePending: Boolean,
        val nativeGenerationObservation: YtdlpNativeProcessBarrier.GenerationObservation,
        val authoritativeIssue: DownloadIssue?,
        val disposition: RecoveryDisposition,
        val phase: RecoveryPhase,
    ) {
        val nativeGenerationToken: String?
            get() = (nativeGenerationObservation as?
                YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION)?.token

        val isUserStop: Boolean
            get() = disposition == RecoveryDisposition.USER_CANCEL ||
                disposition == RecoveryDisposition.USER_PAUSE

        val isHistoryFinalization: Boolean
            get() = disposition == RecoveryDisposition.HISTORY_FINALIZATION
    }

    private fun commit(
        operation: JournalCommitOperation,
        editor: SharedPreferences.Editor,
    ): Boolean = commitOverride?.invoke(operation, editor) ?: editor.commit()

    private fun YtdlpNativeProcessBarrier.GenerationObservation.kindName(): String =
        when (this) {
            YtdlpNativeProcessBarrier.GenerationObservation.ABSENT -> "ABSENT"
            is YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION -> "EXACT_GENERATION"
            is YtdlpNativeProcessBarrier.GenerationObservation.LEGACY_IDENTITY -> "LEGACY_IDENTITY"
            YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN -> "UNKNOWN"
        }

    /**
     * A user-stop carrier is an authority for a still-stoppable execution,
     * not a generic late-status annotation.  Refuse publication after an
     * incompatible terminal result has already won; otherwise the carrier
     * could never satisfy its exact Cancel/Pause CAS and would retry forever.
     * The check is intentionally centralized so notification, ViewModel,
     * bulk, and low-quality producers all share the same validated refusal.
     */
    internal fun canPublishUserStopCarrier(
        item: DownloadItem,
        disposition: RecoveryDisposition,
    ): Boolean {
        if (
            disposition != RecoveryDisposition.USER_CANCEL &&
                disposition != RecoveryDisposition.USER_PAUSE
        ) {
            return true
        }
        if (
            disposition == RecoveryDisposition.USER_PAUSE &&
                item.status == DownloadRepository.Status.Cancelled.name
        ) {
            // A Pause request cannot downgrade a stronger already-committed
            // Cancel decision.
            return false
        }
        return item.status !in setOf(
            DownloadRepository.Status.Error.name,
            DownloadRepository.Status.Saved.name,
            DownloadRepository.Status.Duplicate.name,
        )
    }

    fun recordPending(
        context: Context,
        item: DownloadItem,
        authoritativeIssue: DownloadIssue? = null,
        disposition: RecoveryDisposition = RecoveryDisposition.GENERIC,
        phase: RecoveryPhase? = null,
    ): Boolean {
        YtdlpNativeProcessBarrier.configure(context)
        if (!canPublishUserStopCarrier(item, disposition)) {
            // A terminal Download result won before this publisher acquired
            // the stop boundary.  Do not create an unsatisfiable user-stop
            // carrier; the existing terminal result remains authoritative.
            return false
        }
        val requestedPhase = phase ?: if (
            disposition == RecoveryDisposition.GENERIC ||
                disposition == RecoveryDisposition.HISTORY_FINALIZATION
        ) {
            RecoveryPhase.NATIVE_QUIESCENCE_PENDING
        } else {
            RecoveryPhase.SEMANTIC_STOP_PENDING
        }
        if (
            (
                disposition == RecoveryDisposition.GENERIC ||
                    disposition == RecoveryDisposition.HISTORY_FINALIZATION
                ) &&
                requestedPhase != RecoveryPhase.NATIVE_QUIESCENCE_PENDING
        ) {
            return false
        }
        if (
            disposition != RecoveryDisposition.GENERIC &&
                disposition != RecoveryDisposition.HISTORY_FINALIZATION &&
                phase != null &&
                phase != RecoveryPhase.SEMANTIC_STOP_PENDING
        ) {
            // User operations enter through the semantic phase only.  The
            // native-pending and proven phases can be reached only by their
            // proof-producing transition methods below.
            return false
        }
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
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingExecutionId = preferences.getString(id, null)
        if (
            existingExecutionId == null &&
                (
                    preferences.contains(id + DISPOSITION_SUFFIX) ||
                        preferences.contains(id + PHASE_SUFFIX)
                    )
        ) {
            // Do not overwrite a partially written operation-aware carrier.
            // It is safer to leave the malformed durable authority for the
            // recovery owner to report than to reinterpret it as a new stop.
            return false
        }
        if (existingExecutionId != null && existingExecutionId != item.executionId) {
            // This single-entry carrier cannot represent two execution
            // identities.  Refuse an E2 overwrite while stale E1 recovery is
            // still discoverable; losing E1 here would make its native
            // authority unrecoverable and would let a newer action revoke the
            // old proof obligation.
            return false
        }
        val existingDisposition = if (existingExecutionId == null) {
            null
        } else {
            val raw = preferences.getString(id + DISPOSITION_SUFFIX, null)
            val rawPhase = preferences.getString(id + PHASE_SUFFIX, null)
            if ((raw == null) != (rawPhase == null)) return false
            raw?.let { runCatching { RecoveryDisposition.valueOf(it) }.getOrNull() }
                ?: if (raw == null) RecoveryDisposition.GENERIC else return false
        }
        val existingPhase = if (existingExecutionId == null) {
            null
        } else {
            val raw = preferences.getString(id + PHASE_SUFFIX, null)
            raw?.let { runCatching { RecoveryPhase.valueOf(it) }.getOrNull() }
                ?: when {
                    raw == null && existingDisposition == RecoveryDisposition.GENERIC ->
                        // Journals from the pre-disposition schema were
                        // written after the worker had already decided to
                        // stop. Preserve their established generic behavior.
                        RecoveryPhase.NATIVE_QUIESCENCE_PENDING
                    raw == null -> return false
                    else -> return false
                }
        }
        if (
            existingDisposition == RecoveryDisposition.USER_CANCEL &&
                disposition == RecoveryDisposition.USER_PAUSE
        ) {
            // A replayed/weaker Pause may never downgrade an authoritative
            // Cancel for the same exact execution.
            return false
        }
        val effectiveDisposition = when {
            existingDisposition == RecoveryDisposition.HISTORY_FINALIZATION ->
                RecoveryDisposition.HISTORY_FINALIZATION
            disposition == RecoveryDisposition.HISTORY_FINALIZATION ->
                RecoveryDisposition.HISTORY_FINALIZATION
            existingDisposition == RecoveryDisposition.USER_CANCEL ->
                RecoveryDisposition.USER_CANCEL
            disposition == RecoveryDisposition.USER_CANCEL ->
                RecoveryDisposition.USER_CANCEL
            existingDisposition == RecoveryDisposition.USER_PAUSE ->
                RecoveryDisposition.USER_PAUSE
            disposition == RecoveryDisposition.USER_PAUSE ->
                RecoveryDisposition.USER_PAUSE
            else -> RecoveryDisposition.GENERIC
        }
        val effectivePhase = when {
            disposition == RecoveryDisposition.HISTORY_FINALIZATION -> requestedPhase
            existingDisposition == RecoveryDisposition.HISTORY_FINALIZATION ->
                requireNotNull(existingPhase)
            // An explicit Cancel superseding Pause must revisit the semantic
            // Cancel write before native termination, even when Pause had
            // already reached its native-pending phase.
            disposition == RecoveryDisposition.USER_CANCEL &&
                existingDisposition == RecoveryDisposition.USER_PAUSE -> requestedPhase
            disposition != RecoveryDisposition.GENERIC &&
                existingDisposition == RecoveryDisposition.GENERIC -> requestedPhase
            disposition == RecoveryDisposition.GENERIC &&
                existingDisposition != null &&
                existingDisposition != RecoveryDisposition.GENERIC ->
                requireNotNull(existingPhase)
            existingPhase == RecoveryPhase.NATIVE_QUIESCENT ->
                RecoveryPhase.NATIVE_QUIESCENT
            existingPhase == RecoveryPhase.NATIVE_QUIESCENCE_PENDING ->
                RecoveryPhase.NATIVE_QUIESCENCE_PENDING
            else -> requestedPhase
        }
        val nativeGenerationObservation = YtdlpNativeProcessBarrier.observeDownloadExecution(
            downloadId = item.id,
            executionId = item.executionId,
        )
        val existingGenerationKind = preferences
            .getString(id + NATIVE_GENERATION_KIND_SUFFIX, null)
            ?.uppercase()
        val existingGenerationToken = preferences.getString(
            id + NATIVE_GENERATION_SUFFIX,
            null,
        )
        val durableNativeGenerationObservation = when {
            existingGenerationKind == "EXACT_GENERATION" &&
                !existingGenerationToken.isNullOrBlank() -> {
                if (
                    nativeGenerationObservation is
                        YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION &&
                        nativeGenerationObservation.token != existingGenerationToken
                ) {
                    // Never replace an exact E1 generation with a newer
                    // same-execution marker. The old proof obligation must be
                    // recovered independently.
                    return false
                }
                YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION(
                    existingGenerationToken,
                )
            }
            existingGenerationKind == "LEGACY_IDENTITY" &&
                !existingGenerationToken.isNullOrBlank() ->
                YtdlpNativeProcessBarrier.GenerationObservation.LEGACY_IDENTITY(
                    existingGenerationToken,
                )
            existingGenerationKind == "UNKNOWN" &&
                nativeGenerationObservation is
                    YtdlpNativeProcessBarrier.GenerationObservation.ABSENT ->
                YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN
            else -> nativeGenerationObservation
        }
        val nativeQuiescencePending = if (
            effectivePhase == RecoveryPhase.NATIVE_QUIESCENT &&
                nativeGenerationObservation is
                    YtdlpNativeProcessBarrier.GenerationObservation.ABSENT
        ) {
            false
        } else {
            durableNativeGenerationObservation !is
                YtdlpNativeProcessBarrier.GenerationObservation.ABSENT
        }
        val editor = preferences.edit()
            .putString(id, item.executionId)
            .putBoolean(
                id + NATIVE_QUIESCENCE_SUFFIX,
                nativeQuiescencePending,
            )
            .putString(id + DISPOSITION_SUFFIX, effectiveDisposition.name)
            .putString(id + PHASE_SUFFIX, effectivePhase.name)
        editor.putString(
            id + NATIVE_GENERATION_KIND_SUFFIX,
            durableNativeGenerationObservation.kindName(),
        )
        when (durableNativeGenerationObservation) {
            YtdlpNativeProcessBarrier.GenerationObservation.ABSENT ->
                editor.remove(id + NATIVE_GENERATION_SUFFIX)
            is YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION ->
                editor.putString(id + NATIVE_GENERATION_SUFFIX, durableNativeGenerationObservation.token)
            is YtdlpNativeProcessBarrier.GenerationObservation.LEGACY_IDENTITY ->
                editor.putString(
                    id + NATIVE_GENERATION_SUFFIX,
                    durableNativeGenerationObservation.processId,
                )
            YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN ->
                editor.remove(id + NATIVE_GENERATION_SUFFIX)
        }
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
        expectedGenerationToken: String? = null,
        exactGenerationProof: Boolean = false,
    ): Boolean {
        YtdlpNativeProcessBarrier.configure(context)
        val id = downloadId.toString()
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(id, null) != executionId) return false
        val recordedGenerationToken = preferences.getString(id + NATIVE_GENERATION_SUFFIX, null)
        val recordedKind = preferences
            .getString(id + NATIVE_GENERATION_KIND_SUFFIX, null)
            ?.uppercase()
            ?: if (recordedGenerationToken != null) "EXACT_GENERATION" else "UNKNOWN"
        if (expectedGenerationToken != null && recordedGenerationToken != expectedGenerationToken) {
            return false
        }
        val currentObservation = YtdlpNativeProcessBarrier.observeDownloadExecution(
            downloadId,
            executionId,
        )
        when (recordedKind) {
            "ABSENT" -> if (currentObservation !is
                YtdlpNativeProcessBarrier.GenerationObservation.ABSENT
            ) return false
            "EXACT_GENERATION" -> {
                val token = recordedGenerationToken ?: return false
                if (!YtdlpNativeProcessBarrier.proveGenerationAbsent(token)) return false
                when (currentObservation) {
                    YtdlpNativeProcessBarrier.GenerationObservation.ABSENT -> Unit
                    is YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION -> {
                        if (currentObservation.token != token) return false
                    }
                    else -> return false
                }
            }
            // A legacy processId-only marker has no anti-reuse authority.
            // Even if the carrier later disappears, recovery cannot prove
            // that the old external process was absent rather than merely
            // unrecorded. Keep the owner fail-closed.
            "LEGACY_IDENTITY" -> return false
            // UNKNOWN is a durable observation failure, not a negative
            // observation. It may be acknowledged only when the caller has
            // supplied an exact per-marker recovery proof and no opaque
            // unresolved carrier remains; marker disappearance alone is not
            // proof.
            "UNKNOWN" -> {
                if (!exactGenerationProof) return false
                if (YtdlpNativeProcessBarrier.hasUnresolvedDownloadExecution(
                        downloadId,
                        executionId,
                    )
                ) return false
            }
        }
        return commit(
            JournalCommitOperation.MARK_NATIVE_QUIESCENT,
            preferences.edit()
                .putBoolean(id + NATIVE_QUIESCENCE_SUFFIX, false)
                .putString(id + PHASE_SUFFIX, RecoveryPhase.NATIVE_QUIESCENT.name)
        )
    }

    /**
     * Advances an exact user-stop carrier only after the caller has verified
     * the matching durable Cancelled/Paused Room state.  This is deliberately
     * separate from markNativeQuiescent: a Room status write is not native
     * quiescence proof, and native cleanup may not begin while this phase is
     * still pending.
     */
    internal fun markUserStopSemanticCommitted(
        context: Context,
        downloadId: Long,
        executionId: String,
        disposition: RecoveryDisposition,
    ): Boolean {
        if (
            disposition != RecoveryDisposition.USER_CANCEL &&
                disposition != RecoveryDisposition.USER_PAUSE
        ) {
            return false
        }
        val id = downloadId.toString()
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(id, null) != executionId) return false
        val recordedDisposition = preferences
            .getString(id + DISPOSITION_SUFFIX, null)
            ?.let { runCatching { RecoveryDisposition.valueOf(it) }.getOrNull() }
            ?: return false
        if (recordedDisposition != disposition) return false
        val rawPhase = preferences.getString(id + PHASE_SUFFIX, null)
            ?: return false
        val recordedPhase = runCatching { RecoveryPhase.valueOf(rawPhase) }.getOrNull()
            ?: return false
        if (
            recordedPhase == RecoveryPhase.NATIVE_QUIESCENCE_PENDING ||
                recordedPhase == RecoveryPhase.NATIVE_QUIESCENT
        ) return true
        if (recordedPhase != RecoveryPhase.SEMANTIC_STOP_PENDING) return false
        return commit(
            JournalCommitOperation.RECORD,
            preferences.edit().putString(
                id + PHASE_SUFFIX,
                RecoveryPhase.NATIVE_QUIESCENCE_PENDING.name,
            ),
        )
    }

    /**
     * Exact worker-side revocation visibility.  This is intentionally a
     * synchronous journal read: a worker authority gate must not wait for a
     * recovery coroutine to publish the same decision.
     */
    internal fun hasPendingUserStopForExecution(
        context: Context,
        downloadId: Long,
        executionId: String,
    ): Boolean = readPending(context, downloadId)?.let {
        it.isUserStop && it.executionId == executionId
    } == true

    internal fun hasPendingUserStopSemanticForExecution(
        context: Context,
        downloadId: Long,
        executionId: String,
    ): Boolean = readPending(context, downloadId)?.let {
        it.isUserStop &&
            it.executionId == executionId &&
            it.phase == RecoveryPhase.SEMANTIC_STOP_PENDING
    } == true

    internal fun hasPendingHistoryFinalizationForExecution(
        context: Context,
        downloadId: Long,
        executionId: String,
    ): Boolean = readPending(context, downloadId)?.let {
        it.isHistoryFinalization && it.executionId == executionId
    } == true

    /**
     * A committed History replacement outranks a later speculative user
     * stop.  Preserve the exact native observation, but change the carrier's
     * durable disposition so recovery performs History finalization rather
     * than retrying a semantic Cancel/Pause that can no longer win.
     */
    internal suspend fun supersedeUserStopForCommittedHistory(
        context: Context,
        dbManager: DBManager,
        downloadId: Long,
        executionId: String,
    ): Boolean {
        val pending = readPending(context, downloadId)
            ?.takeIf { it.executionId == executionId && it.isUserStop }
            ?: return false
        val committed = withDownloadWorkerExecutionLock {
            dbManager.downloadDao.getNullableDownloadById(downloadId)?.let { current ->
                current.executionId == executionId &&
                    HistoryRedownloadMarker.parse(current.playlistURL)?.let { marker ->
                        dbManager.historyDao.getNullableItem(marker.historyId)?.downloadId ==
                            downloadId
                    } == true
            } ?: run {
                // The worker may have completed the authoritative History
                // result and deleted the replacement Download before this
                // late user-stop carrier was consumed.  In that durable
                // process-death window the carrier's exact E1 token and the
                // History row keyed to this replacement are the remaining
                // operation identity.  Only accept the row-absent form; a
                // present row with another execution must never be treated
                // as E1's History authority.
                dbManager.historyDao.getItemByDownloadId(downloadId) != null
            }
        }
        if (!committed) return false
        val id = downloadId.toString()
        val nextPhase = if (pending.phase == RecoveryPhase.NATIVE_QUIESCENT) {
            RecoveryPhase.NATIVE_QUIESCENT
        } else {
            RecoveryPhase.NATIVE_QUIESCENCE_PENDING
        }
        return commit(
            JournalCommitOperation.RECORD,
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(id + DISPOSITION_SUFFIX, RecoveryDisposition.HISTORY_FINALIZATION.name)
                .putString(id + PHASE_SUFFIX, nextPhase.name)
                .putBoolean(
                    id + NATIVE_QUIESCENCE_SUFFIX,
                    nextPhase != RecoveryPhase.NATIVE_QUIESCENT,
                ),
        )
    }

    /**
     * Reconciles the semantic half of a user stop while the exact
     * per-Download side-effect lease is held.  No native/process operation is
     * attempted until the exact Cancelled/Paused CAS is observable and the
     * phase transition itself is durable.
     */
    internal suspend fun prepareUserStopBeforeNative(
        context: Context,
        dbManager: DBManager,
        downloadId: Long,
        executionId: String,
    ): UserStopPreparation {
        val pending = readPending(context, downloadId) ?: return UserStopPreparation.NOT_PENDING
        if (!pending.isUserStop) return UserStopPreparation.NOT_PENDING
        if (pending.executionId != executionId) {
            retainRecoveryResponsibility(
                context = context,
                downloadId = downloadId,
                dbManager = dbManager,
                failure = IllegalStateException(
                    "User-stop recovery execution mismatch for $downloadId: " +
                        "carrier=${pending.executionId}, caller=$executionId",
                ),
            )
            return UserStopPreparation.BLOCKED
        }

        return try {
            val semanticResult = DownloadRepository(dbManager).convergeUserStopSemantic(
                id = downloadId,
                expectedExecutionId = executionId,
                disposition = pending.disposition,
            )
            when (val outcome = semanticResult.outcome) {
                DownloadRepository.UserStopSemanticOutcome.USER_STOP_COMMITTED,
                DownloadRepository.UserStopSemanticOutcome.USER_STOP_ALREADY_SATISFIED -> {
                    if (!markUserStopSemanticCommitted(
                            context = context,
                            downloadId = downloadId,
                            executionId = executionId,
                            disposition = pending.disposition,
                        )
                    ) {
                        retainRecoveryResponsibility(
                            context = context,
                            downloadId = downloadId,
                            dbManager = dbManager,
                            failure = IllegalStateException(
                                "User-stop semantic phase could not be durably advanced for " +
                                    "$downloadId/$executionId",
                            ),
                        )
                        UserStopPreparation.BLOCKED
                    } else {
                        UserStopPreparation.READY_FOR_NATIVE_QUIESCENCE
                    }
                }
                DownloadRepository.UserStopSemanticOutcome.STRONGER_USER_CANCEL_ALREADY_WON -> {
                    // A Cancel committed after an earlier Pause carrier is a
                    // stronger same-execution decision. Promote the durable
                    // carrier to USER_CANCEL before quiescing; never rewrite
                    // the terminal Cancelled row back to Paused.
                    val current = withDownloadWorkerExecutionLock {
                        dbManager.downloadDao.getNullableDownloadById(downloadId)
                    }
                    if (
                        current == null ||
                            current.executionId != executionId ||
                            current.status != DownloadRepository.Status.Cancelled.name ||
                            !recordPending(
                                context = context,
                                item = current,
                                disposition = RecoveryDisposition.USER_CANCEL,
                                phase = RecoveryPhase.SEMANTIC_STOP_PENDING,
                            ) ||
                            !markUserStopSemanticCommitted(
                                context = context,
                                downloadId = downloadId,
                                executionId = executionId,
                                disposition = RecoveryDisposition.USER_CANCEL,
                            )
                    ) {
                        retainRecoveryResponsibility(
                            context = context,
                            downloadId = downloadId,
                            dbManager = dbManager,
                            failure = IllegalStateException(
                                "Stronger Cancel could not supersede Pause for " +
                                    "$downloadId/$executionId",
                            ),
                        )
                        UserStopPreparation.BLOCKED
                    } else {
                        UserStopPreparation.READY_FOR_NATIVE_QUIESCENCE
                    }
                }
                DownloadRepository.UserStopSemanticOutcome.COMMITTED_HISTORY_ALREADY_WON -> {
                    if (supersedeUserStopForCommittedHistory(
                            context = context,
                            dbManager = dbManager,
                            downloadId = downloadId,
                            executionId = executionId,
                        )
                    ) {
                        UserStopPreparation.COMMITTED_HISTORY_ALREADY_WON
                    } else {
                        retainRecoveryResponsibility(
                            context = context,
                            downloadId = downloadId,
                            dbManager = dbManager,
                            failure = IllegalStateException(
                                "Committed History authority could not supersede user stop for " +
                                    "$downloadId/$executionId",
                            ),
                        )
                        UserStopPreparation.BLOCKED
                    }
                }
                DownloadRepository.UserStopSemanticOutcome.OWNERSHIP_LOST -> {
                    retainRecoveryResponsibility(
                        context = context,
                        downloadId = downloadId,
                        dbManager = dbManager,
                        failure = IllegalStateException(
                            "User-stop recovery lost exact execution $downloadId/$executionId",
                        ),
                    )
                    UserStopPreparation.BLOCKED
                }
                is DownloadRepository.UserStopSemanticOutcome.RETRYABLE_PERSISTENCE_FAILURE -> {
                    retainRecoveryResponsibility(
                        context = context,
                        downloadId = downloadId,
                        dbManager = dbManager,
                        failure = outcome.error,
                    )
                    UserStopPreparation.BLOCKED
                }
            }
        } catch (cancelled: CancellationException) {
            retainRecoveryResponsibility(
                context = context,
                downloadId = downloadId,
                dbManager = dbManager,
                failure = cancelled,
            )
            throw cancelled
        } catch (failure: Exception) {
            retainRecoveryResponsibility(
                context = context,
                downloadId = downloadId,
                dbManager = dbManager,
                failure = failure,
            )
            UserStopPreparation.BLOCKED
        }
    }

    /**
     * Finishes a committed History replacement after a late user stop lost
     * the semantic race. The carrier is first converted to the dedicated
     * History-finalization disposition; it is cleared only after exact native
     * quiescence and Download-row finalization are both proven.
     */
    internal suspend fun prepareCommittedHistoryFinalization(
        context: Context,
        dbManager: DBManager,
        downloadId: Long,
        executionId: String,
    ): Boolean {
        var pending = readPending(context, downloadId)
            ?.takeIf { it.executionId == executionId }
            ?: return true
        if (pending.isUserStop) {
            check(
                supersedeUserStopForCommittedHistory(
                    context = context,
                    dbManager = dbManager,
                    downloadId = downloadId,
                    executionId = executionId,
                )
            ) {
                "Late user stop could not be superseded by committed History for $downloadId"
            }
            pending = readPending(context, downloadId)
                ?.takeIf { it.executionId == executionId }
                ?: return false
        }
        if (!pending.isHistoryFinalization) return true
        val current = withDownloadWorkerExecutionLock {
            dbManager.downloadDao.getNullableDownloadById(downloadId)
        }
        val repository = DownloadRepository(dbManager)
        val committedHistoryStillPresent = if (current != null) {
            repository.isCommittedHistoryReplacement(
                id = downloadId,
                expectedExecutionId = executionId,
            )
        } else {
            // Once finalization has deleted the Download row, the committed
            // History row keyed by this exact replacement Download is the
            // remaining durable proof that this carrier still names a real
            // History-finalization operation.  Do not clear a converted
            // user-stop carrier merely because its mutable row disappeared.
            withDownloadWorkerExecutionLock {
                dbManager.historyDao.getItemByDownloadId(downloadId) != null
            }
        }
        if (!committedHistoryStillPresent) {
            retainRecoveryResponsibility(
                context = context,
                downloadId = downloadId,
                dbManager = dbManager,
                failure = IllegalStateException(
                    "History-finalization carrier lost its committed History authority for " +
                        "$downloadId/$executionId",
                ),
            )
            return false
        }
        if (
            pending.phase != RecoveryPhase.NATIVE_QUIESCENT ||
                pending.nativeQuiescencePending
        ) {
            check(
                quiesceAfterDurableStop(
                    context = context,
                    downloadId = downloadId,
                    executionId = executionId,
                    dbManager = dbManager,
                    clearCarrierAfterNativeQuiescence = false,
                )
            ) {
                "Committed History native quiescence remained unresolved for $downloadId"
            }
            pending = readPending(context, downloadId)
                ?.takeIf { it.executionId == executionId && it.isHistoryFinalization }
                ?: return false
        }
        return true
    }

    internal suspend fun convergeCommittedHistoryFinalization(
        context: Context,
        dbManager: DBManager,
        downloadId: Long,
        executionId: String,
    ): Boolean {
        if (!prepareCommittedHistoryFinalization(context, dbManager, downloadId, executionId)) {
            return false
        }
        val repository = DownloadRepository(dbManager)
        val latest = withDownloadWorkerExecutionLock {
            dbManager.downloadDao.getNullableDownloadById(downloadId)
        }
        if (latest != null) {
            check(
                repository.isCommittedHistoryReplacement(
                    id = downloadId,
                    expectedExecutionId = executionId,
                )
            ) {
                "Committed History authority changed before finalization for $downloadId"
            }
            repository.completeAndDelete(
                id = downloadId,
                expectedExecutionId = executionId,
            )
        }
        return clearAfterCommittedHistoryFinalization(
            context = context,
            downloadId = downloadId,
            executionId = executionId,
        )
    }

    /**
     * Completes a user-stop carrier for callers that previously performed a
     * generic native cleanup attempt.  A durable native-quiescent phase may
     * be cleared directly; otherwise the strengthened helper is consumed here
     * only after prepareUserStopBeforeNative has established semantic intent.
     */
    internal suspend fun convergeUserStopBeforeGenericCleanup(
        context: Context,
        dbManager: DBManager,
        downloadId: Long,
        executionId: String,
    ): Boolean {
        val pending = readPending(context, downloadId) ?: return false
        if (!pending.isUserStop) return false
        if (pending.executionId != executionId) {
            throw NativeProcessQuiescenceException(downloadId, executionId)
        }
        when (
            prepareUserStopBeforeNative(
                context = context,
                dbManager = dbManager,
                downloadId = downloadId,
                executionId = executionId,
            )
        ) {
            UserStopPreparation.NOT_PENDING -> return false
            UserStopPreparation.BLOCKED -> {
                throw NativeProcessQuiescenceException(
                    downloadId,
                    executionId,
                )
            }
            UserStopPreparation.COMMITTED_HISTORY_ALREADY_WON -> {
                return convergeCommittedHistoryFinalization(
                    context = context,
                    dbManager = dbManager,
                    downloadId = downloadId,
                    executionId = executionId,
                )
            }
            UserStopPreparation.READY_FOR_NATIVE_QUIESCENCE -> Unit
        }
        val afterPreparation = readPending(context, downloadId)
            ?.takeIf { it.executionId == executionId }
            ?: return false
        val expectedStatus = if (
            afterPreparation.disposition == RecoveryDisposition.USER_CANCEL
        ) {
            DownloadRepository.Status.Cancelled.name
        } else {
            DownloadRepository.Status.Paused.name
        }
        val semanticStillCommitted = dbManager.downloadDao
            .getNullableDownloadById(downloadId)
            ?.let {
                it.executionId == executionId && it.status == expectedStatus
            } == true
        if (
            afterPreparation.phase == RecoveryPhase.NATIVE_QUIESCENT &&
                !afterPreparation.nativeQuiescencePending &&
                semanticStillCommitted &&
                clearAfterProvenUserStop(
                    context = context,
                    downloadId = downloadId,
                    executionId = executionId,
                )
        ) {
            return true
        }
        check(
            quiesceAfterDurableStop(
                context = context,
                downloadId = downloadId,
                executionId = executionId,
                dbManager = dbManager,
            )
        ) {
            "User-stop native quiescence remained unresolved for $downloadId"
        }
        return true
    }

    /**
     * Consumes the strengthened native-quiescence contract for a user stop.
     * The journal must already have been recorded before this is called.  A
     * false result or exception retains the exact Download/execution carrier
     * and installs the same-process retry owner; neither is normal stop
     * completion.
     */
    internal fun quiesceAfterDurableStop(
        context: Context,
        downloadId: Long,
        executionId: String,
        dbManager: DBManager = DBManager.getInstance(context),
        clearCarrierAfterNativeQuiescence: Boolean = true,
    ): Boolean {
        return try {
            val pending = readPending(context, downloadId)
                ?.takeIf { it.executionId == executionId }
            if (
                pending?.isUserStop == true &&
                pending.phase == RecoveryPhase.SEMANTIC_STOP_PENDING
            ) {
                retainRecoveryResponsibility(
                    context = context,
                    downloadId = downloadId,
                    dbManager = dbManager,
                    failure = IllegalStateException(
                        "Native quiescence was attempted before user-stop semantic commit for " +
                            "$downloadId/$executionId",
                    ),
                )
                false
            } else if (!DownloadWorker.cancelProcessesForExecution(downloadId, executionId)) {
                retainRecoveryResponsibility(
                    context = context,
                    downloadId = downloadId,
                    dbManager = dbManager,
                    failure = IllegalStateException(
                        "Native quiescence was not proven for download $downloadId " +
                            "executionId=$executionId",
                    ),
                )
                false
            } else if (!markNativeQuiescent(
                    context = context,
                    downloadId = downloadId,
                    executionId = executionId,
                    exactGenerationProof = true,
                )
            ) {
                retainRecoveryResponsibility(
                    context = context,
                    downloadId = downloadId,
                    dbManager = dbManager,
                    failure = IllegalStateException(
                        "Native quiescence carrier could not be acknowledged for download $downloadId " +
                            "executionId=$executionId",
                    ),
                )
                false
            } else if (!clearCarrierAfterNativeQuiescence) {
                true
            } else if (
                if (pending?.isUserStop == true) {
                    val expectedStatus = if (
                        pending.disposition == RecoveryDisposition.USER_CANCEL
                    ) {
                        DownloadRepository.Status.Cancelled.name
                    } else {
                        DownloadRepository.Status.Paused.name
                    }
                    val current = dbManager.downloadDao.getNullableDownloadById(downloadId)
                    current?.executionId == executionId &&
                        current.status == expectedStatus &&
                        clearAfterProvenUserStop(
                            context = context,
                            downloadId = downloadId,
                            executionId = executionId,
                        )
                } else {
                    clearPending(
                        context = context,
                        id = downloadId,
                        expectedExecutionId = executionId,
                    )
                }
            ) {
                true
            } else {
                retainRecoveryResponsibility(
                    context = context,
                    downloadId = downloadId,
                    dbManager = dbManager,
                    failure = IllegalStateException(
                        "Native quiescence carrier could not be cleared for download $downloadId " +
                            "executionId=$executionId",
                    ),
                )
                false
            }
        } catch (cancelled: CancellationException) {
            retainRecoveryResponsibility(
                context = context,
                downloadId = downloadId,
                dbManager = dbManager,
                failure = cancelled,
            )
            throw cancelled
        } catch (failure: Exception) {
            retainRecoveryResponsibility(
                context = context,
                downloadId = downloadId,
                dbManager = dbManager,
                failure = failure,
            )
            false
        }
    }

    /** Installs the live retry owner without making it the restart carrier. */
    internal fun retainRecoveryResponsibility(
        context: Context,
        downloadId: Long,
        dbManager: DBManager = DBManager.getInstance(context),
        failure: Throwable? = null,
    ) {
        runCatching { scheduleRecovery(context, downloadId, dbManager) }
            .onFailure { schedulingFailure ->
                failure?.addSuppressed(schedulingFailure)
                android.util.Log.e(
                    "DownloadExecutionRecovery",
                    "Could not install recovery owner for download $downloadId; durable carrier remains required",
                    schedulingFailure,
                )
            }
    }

    /**
     * Replaces a previously durable UNKNOWN observation only after a later
     * readable pass has supplied the exact generation token. This is a
     * monotonic journal observation upgrade; it never turns UNKNOWN into
     * ABSENT.
     */
    private fun bindExactGenerationObservation(
        context: Context,
        downloadId: Long,
        executionId: String,
        generationToken: String,
    ): Boolean {
        val id = downloadId.toString()
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(id, null) != executionId) return false
        val kind = preferences
            .getString(id + NATIVE_GENERATION_KIND_SUFFIX, null)
            ?.uppercase()
        if (kind != null && kind != "UNKNOWN" && kind != "EXACT_GENERATION") return false
        val existingToken = preferences.getString(id + NATIVE_GENERATION_SUFFIX, null)
        if (kind == "EXACT_GENERATION" && existingToken != generationToken) return false
        return commit(
            JournalCommitOperation.RECORD,
            preferences.edit()
                .putBoolean(id + NATIVE_QUIESCENCE_SUFFIX, true)
                .putString(id + NATIVE_GENERATION_KIND_SUFFIX, "EXACT_GENERATION")
                .putString(id + NATIVE_GENERATION_SUFFIX, generationToken),
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
            .remove(id.toString() + NATIVE_GENERATION_SUFFIX)
            .remove(id.toString() + NATIVE_GENERATION_KIND_SUFFIX)
            .remove(id.toString() + ISSUE_CODE_SUFFIX)
            .remove(id.toString() + ISSUE_STAGE_SUFFIX)
            .remove(id.toString() + TERMINAL_ISSUE_CODE_SUFFIX)
            .remove(id.toString() + TERMINAL_ISSUE_STAGE_SUFFIX)
            .remove(id.toString() + DISPOSITION_SUFFIX)
            .remove(id.toString() + PHASE_SUFFIX)
        return commit(JournalCommitOperation.CLEAR, editor)
    }

    internal fun clearAfterProvenUserStop(
        context: Context,
        downloadId: Long,
        executionId: String,
    ): Boolean {
        val pending = readPending(context, downloadId)
            ?.takeIf { it.executionId == executionId }
            ?: return false
        if (
            !pending.isUserStop ||
                pending.phase != RecoveryPhase.NATIVE_QUIESCENT ||
                pending.nativeQuiescencePending
        ) {
            return false
        }
        return clearPending(
            context = context,
            id = downloadId,
            expectedExecutionId = executionId,
        )
    }

    /** Clears only the dedicated carrier after History finalization. */
    internal fun clearAfterCommittedHistoryFinalization(
        context: Context,
        downloadId: Long,
        executionId: String,
    ): Boolean {
        val pending = readPending(context, downloadId)
            ?.takeIf { it.executionId == executionId }
            ?: return true
        if (!pending.isHistoryFinalization) return false
        return clearPending(
            context = context,
            id = downloadId,
            expectedExecutionId = executionId,
        )
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
        val rawDisposition = preferences.getString(id + DISPOSITION_SUFFIX, null)
        val rawPhase = preferences.getString(id + PHASE_SUFFIX, null)
        check((rawDisposition == null) == (rawPhase == null)) {
            "Incomplete recovery disposition/phase carrier for download $downloadId"
        }
        val disposition = rawDisposition?.let {
            runCatching { RecoveryDisposition.valueOf(it) }.getOrNull()
        } ?: if (rawDisposition == null) {
            // Compatibility for the original generic worker-cleanup carrier.
            // Missing operation identity is never inferred as a user action.
            RecoveryDisposition.GENERIC
        } else {
            error("Unknown recovery disposition $rawDisposition for download $downloadId")
        }
        val phase = rawPhase?.let {
            runCatching { RecoveryPhase.valueOf(it) }.getOrNull()
        } ?: when {
            rawPhase == null && disposition == RecoveryDisposition.GENERIC ->
                // A legacy carrier was created after generic stop ownership
                // was established; retain its old generic interpretation.
                RecoveryPhase.NATIVE_QUIESCENCE_PENDING
            rawPhase == null ->
                error("Incomplete user-stop recovery phase for download $downloadId")
            else -> error("Unknown recovery phase $rawPhase for download $downloadId")
        }
        val nativeGenerationToken = preferences.getString(
            id + NATIVE_GENERATION_SUFFIX,
            null,
        )
        val nativeGenerationObservation = when (
            preferences.getString(id + NATIVE_GENERATION_KIND_SUFFIX, null)
                ?.uppercase()
        ) {
            "ABSENT" -> YtdlpNativeProcessBarrier.GenerationObservation.ABSENT
            "EXACT_GENERATION" -> nativeGenerationToken
                ?.takeIf { it.isNotBlank() }
                ?.let { YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION(it) }
                ?: YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN
            "LEGACY_IDENTITY" -> nativeGenerationToken
                ?.takeIf { it.isNotBlank() }
                ?.let { YtdlpNativeProcessBarrier.GenerationObservation.LEGACY_IDENTITY(it) }
                ?: YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN
            "UNKNOWN" -> YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN
            null -> if (nativeGenerationToken != null) {
                YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION(
                    nativeGenerationToken,
                )
            } else if (executionId.isBlank()) {
                YtdlpNativeProcessBarrier.observeDownloadExecution(downloadId, executionId)
            } else {
                // A pre-kind journal with no token is not proof that no
                // marker existed when it was recorded.
                YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN
            }
            else -> YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN
        }
        return PendingRecovery(
            executionId = executionId,
            nativeQuiescencePending = preferences.getBoolean(
                id + NATIVE_QUIESCENCE_SUFFIX,
                executionId.isNotBlank(),
            ),
            nativeGenerationObservation = nativeGenerationObservation,
            authoritativeIssue = issue ?: terminalIssue,
            disposition = disposition,
            phase = phase,
        )
    }

    internal fun pendingDispositionForExecution(
        context: Context,
        downloadId: Long,
    ): RecoveryDisposition? = readPending(context, downloadId)?.disposition

    internal fun pendingPhaseForTesting(
        context: Context,
        downloadId: Long,
    ): RecoveryPhase? = readPending(context, downloadId)?.phase

    internal fun pendingDownloadIds(context: Context): Set<Long> = context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .all
        .keys
        .mapNotNull { it.toLongOrNull() }
        .toSet()

    /**
     * Resume admission must also honor process-local native ownership.  A
     * process-owner-only debt can exist before the journal is written (for
     * example during an interrupted pause handoff), so the journal alone is
     * not a complete barrier.
     */
    internal fun hasPendingRecovery(
        context: Context,
        downloadId: Long,
    ): Boolean {
        YtdlpNativeProcessBarrier.configure(context)
        return pendingDownloadIds(context).contains(downloadId) ||
            DownloadWorker.hasAnyRegisteredNativeProcess(downloadId)
    }

    /**
     * Current queue-worker liveness proof.  This is intentionally evaluated
     * from current durable/process state on every admission cycle rather than
     * from the startup ReconcileResult.  An unowned running row remains
     * recovery-owned, while a later exact E2 owner is live work and therefore
     * is not kept alive by stale E1 bookkeeping.
     */
    internal fun hasRecoveryResponsibility(
        context: Context,
        dbManager: DBManager,
    ): Boolean {
        YtdlpNativeProcessBarrier.configure(context)
        if (pendingDownloadIds(context).isNotEmpty()) return true

        return dbManager.downloadDao
            .getActiveAndPostProcessingDownloadsList()
            .any { item ->
                val hasCurrentWorkerOwner = item.executionId.isNotBlank() &&
                    DownloadWorkerExecutionOwners.isOwnedBy(item.id, item.executionId)
                !hasCurrentWorkerOwner ||
                    YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(
                        item.id,
                        item.executionId,
                    )
            }
    }

    suspend fun reconcile(
        context: Context,
        dbManager: DBManager = DBManager.getInstance(context),
    ) = withContext(Dispatchers.IO + NonCancellable) {
        YtdlpNativeProcessBarrier.configure(context)
        val repository = DownloadRepository(dbManager)
        data class Discovery(
            val candidates: List<DownloadItem>,
            val orphanNativeProcesses: List<YtdlpNativeProcessBarrier.DurableDownloadProcess>,
            val orphanJournalIds: List<Long>,
            val markerCandidates: List<Pair<Long, String>>,
        )
        val discoveredRecovery = withDownloadWorkerExecutionLock {
            val running = dbManager.downloadDao.getActiveAndPostProcessingDownloadsList()
            val committed = dbManager.downloadDao.getCommittedHistoryReplacementDownloads()
            val journalIds = pendingDownloadIds(context)
            val journalRows = journalIds
                .takeIf { it.isNotEmpty() }
                ?.toList()
                ?.let(dbManager.downloadDao::getDownloadsByIds)
                .orEmpty()
            val nativeProcesses = YtdlpNativeProcessBarrier.downloadProcesses(context)
            val nativeRows = nativeProcesses
                .mapNotNull { process ->
                    dbManager.downloadDao.getNullableDownloadById(process.downloadId)
                }
            Discovery(
                (running + committed + journalRows + nativeRows).distinctBy { it.id },
                nativeProcesses.filter { process ->
                    dbManager.downloadDao.getNullableDownloadById(process.downloadId) == null
                },
                journalIds.filter { id ->
                    dbManager.downloadDao.getNullableDownloadById(id) == null
                },
                YtdlpNativeProcessBarrier.downloadMarkerCandidates(context),
            )
        }
        val candidates = discoveredRecovery.candidates
        val orphanNativeProcesses = discoveredRecovery.orphanNativeProcesses
        val orphanJournalIds = discoveredRecovery.orphanJournalIds
        val markerCandidates = discoveredRecovery.markerCandidates
        val failuresByDownload = linkedMapOf<Long, Exception>()

        fun deferRecovery(downloadId: Long, failure: Exception) {
            try {
                scheduleRecovery(context, downloadId, dbManager)
            } catch (schedulingFailure: Exception) {
                failure.addSuppressed(schedulingFailure)
                android.util.Log.e(
                    "DownloadExecutionRecovery",
                    "Could not install live recovery owner id=$downloadId; durable carrier remains required",
                    schedulingFailure,
                )
            }
            failuresByDownload[downloadId] = failuresByDownload[downloadId]
                ?.also { existing ->
                    if (existing !== failure) existing.addSuppressed(failure)
                }
                ?: failure
            android.util.Log.w(
                "DownloadExecutionRecovery",
                "Deferred per-Download recovery id=$downloadId",
                failure,
            )
        }

        suspend fun convergeOrphanExecution(
            downloadId: Long,
            markerExecutionId: String?,
        ) {
            var pending = readPending(context, downloadId)
            val executionId = markerExecutionId ?: pending?.executionId ?: return
            withDownloadWorkerExecutionSideEffectLease(
                downloadId = downloadId,
                executionId = executionId,
            ) {
                val current = withDownloadWorkerExecutionLock {
                    dbManager.downloadDao.getNullableDownloadById(downloadId)
                }
                if (
                    current?.let {
                        it.executionId == executionId &&
                            it.status in setOf(
                                DownloadRepository.Status.Active.name,
                                DownloadRepository.Status.PostProcessing.name,
                            )
                    } == true
                ) {
                    // The initial discovery raced a row recreation. The
                    // normal row candidate will perform its exact cleanup;
                    // this path never invents a replacement row.
                    return@withDownloadWorkerExecutionSideEffectLease
                }
                if (
                    pending?.let {
                        it.executionId == executionId &&
                            it.isUserStop &&
                            it.phase == RecoveryPhase.SEMANTIC_STOP_PENDING
                    } == true
                ) {
                    val historyAlreadyWon = if (current == null) {
                        // After a committed History replacement deletes the
                        // Download row, the exact replacement History row is
                        // the remaining durable identity for this carrier.
                        // Do not retry the losing semantic user stop forever
                        // during cold-start/orphan-journal recovery.
                        withDownloadWorkerExecutionLock {
                            dbManager.historyDao.getItemByDownloadId(downloadId) != null
                        }
                    } else {
                        current.executionId == executionId &&
                            isCommittedHistoryReplacement(dbManager, current)
                    }
                    if (historyAlreadyWon) {
                        check(
                            convergeCommittedHistoryFinalization(
                                context = context,
                                dbManager = dbManager,
                                downloadId = downloadId,
                                executionId = executionId,
                            )
                        ) {
                            "Committed History finalization did not converge for download $downloadId"
                        }
                    } else {
                    check(
                        convergeUserStopBeforeGenericCleanup(
                            context = context,
                            dbManager = dbManager,
                            downloadId = downloadId,
                            executionId = executionId,
                        )
                    ) {
                        "User-stop semantic recovery did not converge for download $downloadId"
                    }
                    }
                    return@withDownloadWorkerExecutionSideEffectLease
                }
                val processId = if (executionId.isBlank()) null else
                    YtdlpProcessIdentity.download(downloadId, executionId)
                val currentObservation = YtdlpNativeProcessBarrier.observeDownloadExecution(
                    downloadId,
                    executionId,
                )
                var recordedObservation = pending
                    ?.takeIf { it.executionId == executionId }
                    ?.nativeGenerationObservation
                var exactGenerationProof = false
                if (
                    recordedObservation is
                        YtdlpNativeProcessBarrier.GenerationObservation.ABSENT &&
                        currentObservation !is
                            YtdlpNativeProcessBarrier.GenerationObservation.ABSENT
                ) {
                    throw NativeProcessQuiescenceException(downloadId, executionId)
                }
                if (currentObservation is YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN) {
                    val recordedToken = (recordedObservation as?
                        YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION)?.token
                    exactGenerationProof = if (recordedToken != null) {
                        check(
                            YtdlpNativeProcessBarrier.recoverGeneration(
                                processId = processId ?: "download:$downloadId:$executionId",
                                generationToken = recordedToken,
                            )
                        ) {
                            "Orphan native generation could not be recovered for download $downloadId"
                        }
                        true
                    } else {
                        YtdlpNativeProcessBarrier.recoverDownloadExecution(
                            downloadId = downloadId,
                            executionId = executionId,
                        ).also { recovered ->
                            if (!recovered) {
                                throw NativeProcessQuiescenceException(downloadId, executionId)
                            }
                        }
                    }
                }
                if (
                    recordedObservation is
                        YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN &&
                        currentObservation is
                            YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION
                ) {
                    val exactToken =
                        (currentObservation as?
                            YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION)
                            ?.token
                        ?: throw NativeProcessQuiescenceException(downloadId, executionId)
                    check(
                        bindExactGenerationObservation(
                            context = context,
                            downloadId = downloadId,
                            executionId = executionId,
                            generationToken = exactToken,
                        )
                    ) {
                        "Orphan native generation identity could not be durably rebound for download $downloadId"
                    }
                    pending = readPending(context, downloadId)
                    recordedObservation = pending
                        ?.takeIf { it.executionId == executionId }
                        ?.nativeGenerationObservation
                }
                if (
                    recordedObservation is
                        YtdlpNativeProcessBarrier.GenerationObservation.ABSENT &&
                        currentObservation !is
                            YtdlpNativeProcessBarrier.GenerationObservation.ABSENT
                ) {
                    throw NativeProcessQuiescenceException(downloadId, executionId)
                }
                val recordedGenerationToken = (recordedObservation as?
                    YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION)?.token
                if (
                    recordedGenerationToken != null &&
                        (
                            executionId.isBlank() ||
                                currentObservation !is
                                    YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION ||
                                currentObservation.token != recordedGenerationToken
                            )
                ) {
                    check(
                        YtdlpNativeProcessBarrier.recoverGeneration(
                            processId = processId ?: "download:$downloadId:$executionId",
                            generationToken = recordedGenerationToken,
                        )
                    ) {
                        "Orphan native generation changed for download $downloadId"
                    }
                    exactGenerationProof = true
                } else if (!exactGenerationProof) {
                    check(
                        YtdlpNativeProcessBarrier.recoverDownloadExecution(
                            downloadId = downloadId,
                            executionId = executionId,
                        )
                    ) {
                        "Orphan native marker set remained unresolved for download $downloadId"
                    }
                    exactGenerationProof = true
                }
                check(
                    DownloadWorker.cancelProcessesForExecution(downloadId, executionId)
                ) {
                    "Orphan native process owner changed for download $downloadId"
                }
                val nativePendingForExecution = pending
                    ?.takeIf { it.executionId == executionId }
                    ?.nativeQuiescencePending == true
                if (
                    nativePendingForExecution &&
                        !markNativeQuiescent(
                            context,
                            downloadId,
                            executionId,
                            recordedGenerationToken,
                            exactGenerationProof,
                        )
                ) {
                    throw NativeProcessQuiescenceException(downloadId, executionId)
                }
                DownloadWorkerExecutionOwners.release(downloadId, executionId)
                if (pending?.executionId == executionId) {
                    val cleared = if (pending?.isHistoryFinalization == true) {
                        // A missing Download row is not, by itself, proof
                        // that History finalization completed.  Keep this
                        // carrier until the exact replacement History row
                        // confirms that the committed primary result still
                        // exists; otherwise a malformed/deleted carrier
                        // would erase the only finalization responsibility.
                        val historyStillExists = withDownloadWorkerExecutionLock {
                            dbManager.historyDao.getItemByDownloadId(downloadId) != null
                        }
                        historyStillExists && clearPending(
                            context = context,
                            id = downloadId,
                            expectedExecutionId = executionId,
                        )
                    } else if (pending?.isUserStop == true) {
                        clearAfterProvenUserStop(
                            context = context,
                            downloadId = downloadId,
                            executionId = executionId,
                        )
                    } else {
                        clearPending(
                            context = context,
                            id = downloadId,
                            expectedExecutionId = executionId,
                        )
                    }
                    check(
                        cleared
                    ) {
                        "Orphan Download recovery journal could not be cleared for $downloadId"
                    }
                }
            }
        }

        candidates.forEach { snapshot ->
            try {
                beforeCandidateRecoveryLeaseForTesting?.invoke(snapshot.id)
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
                        nativeGenerationToken: String? = pending
                            ?.takeIf { it.executionId == executionId }
                            ?.nativeGenerationToken,
                    ): Boolean {
                        if (
                            pending?.let {
                                it.executionId == executionId &&
                                    it.isUserStop &&
                                    it.phase == RecoveryPhase.SEMANTIC_STOP_PENDING
                            } == true
                        ) {
                            throw NativeProcessQuiescenceException(
                                snapshot.id,
                                executionId,
                            )
                        }
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
                        val processId = executionId
                            .takeIf { it.isNotBlank() }
                            ?.let { YtdlpProcessIdentity.download(snapshot.id, it) }
                        val currentObservation = YtdlpNativeProcessBarrier
                            .observeDownloadExecution(snapshot.id, executionId)
                        var recordedObservation = pending
                            ?.takeIf { it.executionId == executionId }
                            ?.nativeGenerationObservation
                        var exactGenerationProof = false
                        if (
                            recordedObservation is
                                YtdlpNativeProcessBarrier.GenerationObservation.ABSENT &&
                                currentObservation !is
                                    YtdlpNativeProcessBarrier.GenerationObservation.ABSENT
                        ) {
                            throw NativeProcessQuiescenceException(snapshot.id, executionId)
                        }
                        if (currentObservation is
                            YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN
                        ) {
                            val recordedToken = (recordedObservation as?
                                YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION)
                                ?.token
                            exactGenerationProof = if (recordedToken != null) {
                                check(
                                    YtdlpNativeProcessBarrier.recoverGeneration(
                                        processId = processId
                                            ?: "download:${snapshot.id}:$executionId",
                                        generationToken = recordedToken,
                                    )
                                ) {
                                    "Native generation could not be recovered for download ${snapshot.id}"
                                }
                                true
                            } else {
                                YtdlpNativeProcessBarrier.recoverDownloadExecution(
                                    downloadId = snapshot.id,
                                    executionId = executionId,
                                ).also { recovered ->
                                    if (!recovered) {
                                        throw NativeProcessQuiescenceException(
                                            snapshot.id,
                                            executionId,
                                        )
                                    }
                                }
                            }
                        }
                        if (recordedObservation is
                            YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN &&
                            currentObservation is
                                YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION
                        ) {
                            val exactToken =
                                (currentObservation as?
                                    YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION)
                                    ?.token
                                ?: throw NativeProcessQuiescenceException(snapshot.id, executionId)
                            check(
                                bindExactGenerationObservation(
                                    context = context,
                                    downloadId = snapshot.id,
                                    executionId = executionId,
                                    generationToken = exactToken,
                                )
                            ) {
                                "Native generation identity could not be durably rebound for download ${snapshot.id}"
                            }
                            pending = readPending(context, snapshot.id)
                            recordedObservation = pending
                                ?.takeIf { it.executionId == executionId }
                                ?.nativeGenerationObservation
                        }
                        val currentExactToken =
                            (currentObservation as?
                                YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION)
                                ?.token
                        val journalGenerationAppearedAfterRecord =
                            pending?.executionId == executionId &&
                                recordedObservation is
                                    YtdlpNativeProcessBarrier.GenerationObservation.ABSENT &&
                                currentObservation !is
                                    YtdlpNativeProcessBarrier.GenerationObservation.ABSENT
                        if (journalGenerationAppearedAfterRecord) {
                            throw NativeProcessQuiescenceException(snapshot.id, executionId)
                        }
                        val expectedGenerationToken = nativeGenerationToken ?:
                            (recordedObservation as?
                                YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION)
                                ?.token
                        if (
                            expectedGenerationToken != null &&
                                (
                                    executionId.isBlank() ||
                                        currentExactToken != expectedGenerationToken
                                    )
                        ) {
                            // The journal names an older native generation.
                            // Recover only that token; a newer same-processId
                            // marker is never passed through the generic
                            // process-id cancellation path.
                            check(
                                YtdlpNativeProcessBarrier.recoverGeneration(
                                    processId = processId
                                        ?: "download:${snapshot.id}:$executionId",
                                    generationToken = expectedGenerationToken,
                                )
                            ) {
                                "Native generation owner changed while recovering download ${snapshot.id}"
                            }
                            exactGenerationProof = true
                        } else if (!exactGenerationProof) {
                            check(
                                YtdlpNativeProcessBarrier.recoverDownloadExecution(
                                    downloadId = snapshot.id,
                                    executionId = executionId,
                                )
                            ) {
                                "Native marker set remained unresolved while recovering download ${snapshot.id}"
                            }
                            exactGenerationProof = true
                            val nativeVisible = nativePending ||
                                DownloadWorker.hasRegisteredNativeProcess(snapshot.id, executionId) ||
                                processId?.let {
                                    YtdlpNativeProcessBarrier.hasUnresolved(it)
                                } == true ||
                                YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(
                                    snapshot.id,
                                    executionId,
                                )
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
                        }
                        if (
                            nativePending &&
                                !markNativeQuiescent(
                                    context,
                                    snapshot.id,
                                    executionId,
                                    expectedGenerationToken,
                                    exactGenerationProof,
                                )
                        ) {
                            throw NativeProcessQuiescenceException(snapshot.id, executionId)
                        }
                        return exactGenerationProof
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
                                val cleared = if (pending?.isHistoryFinalization == true) {
                                    // A missing Download row is not, by
                                    // itself, proof that History finalization
                                    // completed. The exact replacement
                                    // History row is the remaining durable
                                    // confirmation for this carrier.
                                    val historyStillExists = withDownloadWorkerExecutionLock {
                                        dbManager.historyDao.getItemByDownloadId(snapshot.id) != null
                                    }
                                    historyStillExists && clearPending(
                                        context = context,
                                        id = snapshot.id,
                                        expectedExecutionId = executionId,
                                    )
                                } else if (pending?.isUserStop == true) {
                                    clearAfterProvenUserStop(
                                        context = context,
                                        downloadId = snapshot.id,
                                        executionId = executionId,
                                    )
                                } else {
                                    clearPending(
                                        context = context,
                                        id = snapshot.id,
                                        expectedExecutionId = executionId,
                                    )
                                }
                                check(
                                    cleared
                                ) {
                                    "Download recovery journal could not be cleared for ${snapshot.id}"
                                }
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
                        if (
                            stalePending?.let {
                                it.isUserStop &&
                                    it.phase == RecoveryPhase.SEMANTIC_STOP_PENDING
                            } == true
                        ) {
                            // The semantic stop belongs to stale E1.  Until
                            // that exact Room decision is durable, neither
                            // native E1 nor the current E2 may be mutated or
                            // reclassified by generic recovery.
                            return@withDownloadWorkerExecutionSideEffectLease
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
                                    val staleCarrier = pending?.takeIf {
                                        it.executionId == staleExecutionId
                                    }
                                    val exactGenerationProof = quiesceExactExecution(
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
                                        if (staleCarrier?.isUserStop == true) {
                                            check(
                                                markNativeQuiescent(
                                                    context = context,
                                                    downloadId = snapshot.id,
                                                    executionId = staleExecutionId,
                                                    expectedGenerationToken = staleCarrier.nativeGenerationToken,
                                                    exactGenerationProof = exactGenerationProof,
                                                )
                                            ) {
                                                "Stale user-stop native carrier could not be acknowledged for " +
                                                    "${snapshot.id}"
                                            }
                                        }
                                        val cleared = if (staleCarrier?.isUserStop == true) {
                                            clearAfterProvenUserStop(
                                                context = context,
                                                downloadId = snapshot.id,
                                                executionId = staleExecutionId,
                                            )
                                        } else {
                                            clearPending(
                                                context = context,
                                                id = snapshot.id,
                                                expectedExecutionId = staleExecutionId,
                                            )
                                        }
                                        if (!cleared) {
                                            // Exact stale E1 quiescence has
                                            // already been proven.  A failed
                                            // carrier deletion must remain
                                            // discoverable, but it must not
                                            // suppress independent recovery
                                            // of the current E2.
                                            android.util.Log.w(
                                                "DownloadExecutionRecovery",
                                                "Retaining stale recovery carrier after clear failure " +
                                                    "id=${snapshot.id} executionId=$staleExecutionId",
                                            )
                                        }
                                    }
                                }
                                pending = readPending(context, snapshot.id)
                            }
                        }

                        current = withDownloadWorkerExecutionLock {
                            dbManager.downloadDao.getNullableDownloadById(snapshot.id)
                        }
                        if (current != null) {
                            var pendingForCurrent = pending?.takeIf {
                                it.executionId == current.executionId
                            }
                            val owned = current.executionId.isNotBlank() &&
                                DownloadWorkerExecutionOwners.isOwnedBy(
                                    current.id,
                                    current.executionId,
                                )
                            if (owned) {
                                // Exact process-local worker ownership is
                                // positive liveness evidence.  Recovery may
                                // not reinterpret this row as abandoned,
                                // even when discovery included it because a
                                // journal, marker, or committed History row
                                // also exists.  Leave all current/stale debt
                                // for a later pass after the worker releases
                                // this exact execution token.
                                return@withDownloadWorkerExecutionSideEffectLease
                            }
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
                                pendingForCurrent?.isUserStop == true &&
                                    isCommittedHistoryReplacement(dbManager, current) &&
                                    !anotherExecutionOwnsTheRow &&
                                    !anotherExecutionHasNativeProcess
                            ) {
                                check(
                                    supersedeUserStopForCommittedHistory(
                                        context = context,
                                        dbManager = dbManager,
                                        downloadId = current.id,
                                        executionId = current.executionId,
                                    )
                                ) {
                                    "Committed History could not supersede late user stop for ${current.id}"
                                }
                                pending = readPending(context, current.id)
                                pendingForCurrent = pending?.takeIf {
                                    it.executionId == current.executionId
                                }
                            }
                            val userStopPendingForCurrent = pendingForCurrent?.isUserStop == true
                            val historyFinalizationPendingForCurrent =
                                pendingForCurrent?.isHistoryFinalization == true
                            if (
                                userStopPendingForCurrent &&
                                    (
                                        anotherExecutionOwnsTheRow ||
                                            anotherExecutionHasNativeProcess
                                    )
                            ) {
                                throw NativeProcessQuiescenceException(
                                    current.id,
                                    current.executionId,
                                )
                            }
                            if (
                                !owned &&
                                    !anotherExecutionOwnsTheRow &&
                                    !anotherExecutionHasNativeProcess &&
                                    historyFinalizationPendingForCurrent
                            ) {
                                check(
                                    convergeCommittedHistoryFinalization(
                                        context = context,
                                        dbManager = dbManager,
                                        downloadId = current.id,
                                        executionId = current.executionId,
                                    )
                                ) {
                                    "Committed History finalization remained unresolved for ${current.id}"
                                }
                                return@withDownloadWorkerExecutionSideEffectLease
                            }
                            if (
                                !owned &&
                                    !anotherExecutionOwnsTheRow &&
                                    !anotherExecutionHasNativeProcess
                            ) {
                                if (userStopPendingForCurrent) {
                                    check(
                                        convergeUserStopBeforeGenericCleanup(
                                            context = context,
                                            dbManager = dbManager,
                                            downloadId = current.id,
                                            executionId = current.executionId,
                                        )
                                    ) {
                                        "User-stop recovery did not converge for download ${current.id}"
                                    }
                                    return@withDownloadWorkerExecutionSideEffectLease
                                }
                                val currentProcessId = current.executionId
                                    .takeIf { it.isNotBlank() }
                                    ?.let { YtdlpProcessIdentity.download(current.id, it) }
                                val nativeMarkerDebt =
                                    YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(
                                        current.id,
                                        current.executionId,
                                    )
                                val nativeQuiescenceRequired =
                                    pendingForCurrent?.nativeQuiescencePending == true ||
                                        DownloadWorker.hasRegisteredNativeProcess(
                                            current.id,
                                            current.executionId,
                                        ) ||
                                        currentProcessId?.let {
                                            YtdlpNativeProcessBarrier.hasUnresolved(it)
                                        } == true ||
                                        nativeMarkerDebt
                                if (nativeQuiescenceRequired) {
                                    val currentObservation =
                                        YtdlpNativeProcessBarrier.observeDownloadExecution(
                                            current.id,
                                            current.executionId,
                                        )
                                    var recordedObservation = pendingForCurrent
                                        ?.nativeGenerationObservation
                                    var exactGenerationProof = false
                                    if (
                                        pendingForCurrent != null &&
                                            recordedObservation is
                                                YtdlpNativeProcessBarrier.GenerationObservation.ABSENT &&
                                            currentObservation !is
                                                YtdlpNativeProcessBarrier.GenerationObservation.ABSENT
                                    ) {
                                        throw NativeProcessQuiescenceException(
                                            current.id,
                                            current.executionId,
                                        )
                                    }
                                    if (currentObservation is
                                        YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN
                                    ) {
                                        val recordedToken = (recordedObservation as?
                                            YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION)
                                            ?.token
                                        exactGenerationProof = if (recordedToken != null) {
                                            check(
                                                YtdlpNativeProcessBarrier.recoverGeneration(
                                                    processId = currentProcessId
                                                        ?: "download:${current.id}:${current.executionId}",
                                                    generationToken = recordedToken,
                                                )
                                            ) {
                                                "Native generation could not be recovered for download ${current.id}"
                                            }
                                            true
                                        } else {
                                            YtdlpNativeProcessBarrier.recoverDownloadExecution(
                                                downloadId = current.id,
                                                executionId = current.executionId,
                                            ).also { recovered ->
                                                if (!recovered) {
                                                    throw NativeProcessQuiescenceException(
                                                        current.id,
                                                        current.executionId,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (recordedObservation is
                                        YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN &&
                                        currentObservation is
                                            YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION
                                    ) {
                                        val exactToken =
                                            (currentObservation as?
                                                YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION)
                                                ?.token
                                            ?: throw NativeProcessQuiescenceException(
                                                current.id,
                                                current.executionId,
                                            )
                                        check(
                                            bindExactGenerationObservation(
                                                context = context,
                                                downloadId = current.id,
                                                executionId = current.executionId,
                                                generationToken = exactToken,
                                            )
                                        ) {
                                            "Native generation identity could not be durably rebound for download ${current.id}"
                                        }
                                        pending = readPending(context, current.id)
                                        pendingForCurrent = pending?.takeIf {
                                            it.executionId == current.executionId
                                        }
                                        recordedObservation = pendingForCurrent
                                            ?.nativeGenerationObservation
                                    }
                                    val recordedGenerationToken = pendingForCurrent
                                        ?.nativeGenerationToken
                                    val currentExactToken =
                                        (currentObservation as?
                                            YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION)
                                            ?.token
                                    if (
                                        pendingForCurrent != null &&
                                            recordedObservation is
                                                YtdlpNativeProcessBarrier.GenerationObservation.ABSENT &&
                                            currentObservation !is
                                                YtdlpNativeProcessBarrier.GenerationObservation.ABSENT
                                    ) {
                                        throw NativeProcessQuiescenceException(
                                            current.id,
                                            current.executionId,
                                        )
                                    }
                                    if (
                                        recordedGenerationToken != null &&
                                            (
                                                current.executionId.isBlank() ||
                                                    currentExactToken != recordedGenerationToken
                                                )
                                    ) {
                                        check(
                                            YtdlpNativeProcessBarrier.recoverGeneration(
                                                processId = currentProcessId
                                                    ?: "download:${current.id}:${current.executionId}",
                                                generationToken = recordedGenerationToken,
                                            )
                                        ) {
                                            "Native generation owner changed while recovering download ${current.id}"
                                        }
                                        exactGenerationProof = true
                                    } else if (
                                        current.executionId.isBlank() &&
                                            currentExactToken != null
                                    ) {
                                        check(
                                            YtdlpNativeProcessBarrier.recoverGeneration(
                                                processId = "download:${current.id}:${current.executionId}",
                                                generationToken = currentExactToken,
                                            )
                                        ) {
                                            "Legacy Download native generation could not be recovered " +
                                                "for ${current.id}"
                                        }
                                        exactGenerationProof = true
                                    } else {
                                        if (nativeMarkerDebt) {
                                            check(
                                                YtdlpNativeProcessBarrier.recoverDownloadExecution(
                                                    downloadId = current.id,
                                                    executionId = current.executionId,
                                                )
                                            ) {
                                                "Native marker set remained unresolved while recovering download ${current.id}"
                                            }
                                            exactGenerationProof = true
                                        }
                                        check(
                                            DownloadWorker.cancelProcessesForExecution(
                                                current.id,
                                                current.executionId,
                                            )
                                        ) {
                                            "Native process owner changed while recovering download ${current.id}"
                                        }
                                    }
                                    if (
                                        pendingForCurrent?.nativeQuiescencePending == true &&
                                            !markNativeQuiescent(
                                                context,
                                                current.id,
                                                current.executionId,
                                                recordedGenerationToken,
                                                exactGenerationProof,
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
                                if (pendingForCurrent?.isHistoryFinalization == true) {
                                    check(
                                        convergeCommittedHistoryFinalization(
                                            context = context,
                                            dbManager = dbManager,
                                            downloadId = current.id,
                                            executionId = current.executionId,
                                        )
                                    ) {
                                        "Committed History finalization remained unresolved for ${current.id}"
                                    }
                                    pending = readPending(context, current.id)
                                    clearJournal = pending != null
                                } else if (pendingForCurrent?.isUserStop == true) {
                                    // A user-stop carrier is operation-specific
                                    // authority.  It may not be cleared by a
                                    // generic non-running branch; re-read and
                                    // converge the exact semantic/native
                                    // protocol first.
                                    check(
                                        convergeUserStopBeforeGenericCleanup(
                                            context = context,
                                            dbManager = dbManager,
                                            downloadId = current.id,
                                            executionId = current.executionId,
                                        )
                                    ) {
                                        "User-stop recovery did not converge for download ${current.id}"
                                    }
                                    pending = readPending(context, current.id)
                                    clearJournal = pending != null
                                } else if (latest == null) {
                                    clearJournal = pending != null
                                } else if (latest.executionId != current.executionId) {
                                    // The exact lease and reread prevent a
                                    // stale recovery token from touching E2.
                                } else if (isCommittedHistoryReplacement(dbManager, latest)) {
                                    if (failCommittedHistoryFinalizationForTesting) {
                                        failCommittedHistoryFinalizationForTesting = false
                                        error("Injected committed History finalization failure")
                                    }
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
                                            recoveryContext = context,
                                            dbManager = dbManager,
                                        )
                                    ) {
                                        DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
                                        DownloadRepository.RunningDownloadRequeueResult.REFUSAL_CONVERGED,
                                        DownloadRepository.RunningDownloadRequeueResult.AUTHORITATIVE_ISSUE_CONVERGED,
                                        DownloadRepository.RunningDownloadRequeueResult.COMMITTED_HISTORY_FINALIZATION_DEBT -> {
                                            clearJournal = pending != null
                                        }
                                        DownloadRepository.RunningDownloadRequeueResult.USER_STOP_CONVERGED -> {
                                            pending = readPending(context, latest.id)
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
                        val carrier = readPending(context, snapshot.id)
                            ?.takeIf { it.executionId == expectedJournalExecutionId }
                        val cleared = if (carrier?.isUserStop == true) {
                            clearAfterProvenUserStop(
                                context = context,
                                downloadId = snapshot.id,
                                executionId = expectedJournalExecutionId,
                            )
                        } else {
                            clearPending(
                                context = context,
                                id = snapshot.id,
                                expectedExecutionId = expectedJournalExecutionId,
                            )
                        }
                        check(cleared) {
                            "Download recovery journal could not be cleared for ${snapshot.id}"
                        }
                    }
                }
            } catch (failure: Exception) {
                deferRecovery(snapshot.id, failure)
            }
        }

        orphanNativeProcesses.forEach { process ->
            try {
                convergeOrphanExecution(
                    downloadId = process.downloadId,
                    markerExecutionId = process.executionId,
                )
            } catch (failure: Exception) {
                deferRecovery(process.downloadId, failure)
            }
        }
        markerCandidates
            .filterNot { (downloadId, executionId) ->
                orphanNativeProcesses.any {
                    it.downloadId == downloadId && it.executionId == executionId
                }
            }
            .forEach { (downloadId, executionId) ->
                try {
                    convergeOrphanExecution(
                        downloadId = downloadId,
                        markerExecutionId = executionId,
                    )
                } catch (failure: Exception) {
                    deferRecovery(downloadId, failure)
                }
            }
        orphanJournalIds
            .filterNot { id -> orphanNativeProcesses.any { it.downloadId == id } }
            .forEach { downloadId ->
                try {
                    convergeOrphanExecution(downloadId, markerExecutionId = null)
                } catch (failure: Exception) {
                    deferRecovery(downloadId, failure)
                }
            }

        val durableDebtIds = buildSet {
            addAll(candidates.map { it.id })
            addAll(orphanNativeProcesses.map { it.downloadId })
            addAll(markerCandidates.map { it.first })
            addAll(orphanJournalIds)
        }
        durableDebtIds.forEach { downloadId ->
            val row = withDownloadWorkerExecutionLock {
                dbManager.downloadDao.getNullableDownloadById(downloadId)
            }
            val debtRemains = pendingDownloadIds(context).contains(downloadId) ||
                YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(downloadId) ||
                row?.status in setOf(
                    DownloadRepository.Status.Active.name,
                    DownloadRepository.Status.PostProcessing.name,
                )
            if (debtRemains) {
                runCatching { scheduleRecovery(context, downloadId, dbManager) }
                    .onFailure { schedulingFailure ->
                        val failure = IllegalStateException(
                            "Could not install live recovery owner for durable download $downloadId",
                            schedulingFailure,
                        )
                        failuresByDownload[downloadId] = failuresByDownload[downloadId]
                            ?.also { existing -> existing.addSuppressed(failure) }
                            ?: failure
                        android.util.Log.e(
                            "DownloadExecutionRecovery",
                            "Durable recovery owner installation failed id=$downloadId",
                            schedulingFailure,
                        )
                    }
            }
        }

        return@withContext ReconcileResult(
            deferredDownloadIds = failuresByDownload.keys.toSet(),
            failuresByDownload = failuresByDownload.toMap(),
        )
    }

    /**
     * Keeps same-process recovery alive after a worker has crossed its cleanup
     * boundary.  The DB row/journal remains the durable carrier; this job is
     * only the live retry owner and is never used as the sole restart proof.
     */
    internal fun scheduleRecovery(
        context: Context,
        downloadId: Long,
        dbManager: DBManager = DBManager.getInstance(context),
    ) {
        val appContext = context.applicationContext
        retryJobs.computeIfAbsent(downloadId) {
            retryScope.launch {
                val ownerJob = coroutineContext[Job]
                var retryDelayMillis = 100L
                try {
                    while (true) {
                        try {
                            val current = readRecoveryDownloadForRetry(dbManager, downloadId)
                            val journalRemains = pendingDownloadIds(appContext).contains(downloadId)
                            val nativeMarkerRemains =
                                YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(downloadId)
                            if (
                                current != null &&
                                current.executionId.isNotBlank() &&
                                    DownloadWorkerExecutionOwners.isOwnedBy(
                                        downloadId,
                                        current.executionId,
                                    ) &&
                                    !journalRemains &&
                                    !nativeMarkerRemains
                            ) {
                                // A live worker owns the exact row; its cleanup or
                                // retry protocol remains authoritative.
                                return@launch
                            }

                            // Reconcile may itself perform ordinary Room/marker
                            // reads and writes.  Keep those failures inside the
                            // same owner boundary so the durable carrier retains
                            // this retry responsibility.
                            reconcile(appContext, dbManager)

                            val latest = readRecoveryDownloadForRetry(dbManager, downloadId)
                            val latestJournalRemains =
                                pendingDownloadIds(appContext).contains(downloadId)
                            val latestNativeMarkerRemains =
                                YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(downloadId)
                            val stillRunning = latest?.status in setOf(
                                DownloadRepository.Status.Active.name,
                                DownloadRepository.Status.PostProcessing.name,
                            )
                            if (
                                !latestJournalRemains &&
                                    !latestNativeMarkerRemains &&
                                    !stillRunning
                            ) {
                                return@launch
                            }
                            delay(retryDelayMillis)
                            retryDelayMillis = (retryDelayMillis * 2L).coerceAtMost(5_000L)
                        } catch (cancelled: CancellationException) {
                            // A real owner cancellation must end this coroutine;
                            // it is not recoverable debt.
                            throw cancelled
                        } catch (failure: Exception) {
                            android.util.Log.w(
                                "DownloadExecutionRecovery",
                                "Recovery retry iteration failed id=$downloadId",
                                failure,
                            )
                            delay(retryDelayMillis)
                            retryDelayMillis = (retryDelayMillis * 2L).coerceAtMost(5_000L)
                        }
                    }
                } finally {
                    if (ownerJob != null) retryJobs.remove(downloadId, ownerJob)
                }
            }
        }
    }

    private fun readRecoveryDownloadForRetry(
        dbManager: DBManager,
        downloadId: Long,
    ): DownloadItem? {
        while (true) {
            val remaining = recoveryReadFailureCount.get()
            if (remaining <= 0) break
            if (recoveryReadFailureCount.compareAndSet(remaining, remaining - 1)) {
                throw IllegalStateException("Injected transient recovery DB read failure")
            }
        }
        return dbManager.downloadDao.getNullableDownloadById(downloadId)
    }

    /** Deterministic visibility for the opaque-marker retry-owner test. */
    internal fun isRecoveryJobActiveForTesting(downloadId: Long): Boolean =
        retryJobs[downloadId]?.isActive == true

    /** Keeps test-created retry owners from leaking into later cases. */
    internal fun cancelRecoveryJobForTesting(downloadId: Long) {
        retryJobs.remove(downloadId)?.cancel()
    }

    internal fun cancelAllRecoveryJobsForTesting() {
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
    }

    /** Test-only teardown for the durable recovery carrier. */
    internal fun clearForTesting(context: Context) {
        beforeCandidateRecoveryLeaseForTesting = null
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun isCommittedHistoryReplacement(
        dbManager: DBManager,
        item: DownloadItem,
    ): Boolean {
        val marker = HistoryRedownloadMarker.parse(item.playlistURL) ?: return false
        return dbManager.historyDao.getNullableItem(marker.historyId)?.downloadId == item.id
    }
}
