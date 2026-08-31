package com.ireum.ytdl.database.repository

import android.content.Context
import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadLiveCounts
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.LowQualityRedownloadOperationState
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase
import com.ireum.ytdl.util.LowQualityRedownloadProgress
import com.ireum.ytdl.util.LowQualityRedownloadCompletionPolicy
import com.ireum.ytdl.util.LowQualityRedownloadLinkedDownloadPolicy
import com.ireum.ytdl.util.HistoryReplacementSourceIdentity
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.work.DownloadCancellationRegistry
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.withDownloadWorkerExecutionLock
import com.ireum.ytdl.work.withDownloadWorkerExecutionSideEffectLeases
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class LowQualityRedownloadRepository(private val database: DBManager) {
    private val dao = database.lowQualityRedownloadDao

    /** Deterministic hook immediately before final revalidation/action ownership. */
    @Volatile
    internal var beforeFinalLinkedExecutionRevalidationForTesting:
        (suspend (String, List<Pair<Long, String>>) -> Unit)? = null

    /** Deterministic hook while the final revalidation/action lock is held. */
    @Volatile
    internal var beforeFinalLinkedExecutionActionForTesting:
        (suspend (String, List<Pair<Long, String>>) -> Unit)? = null

    val currentOperation: Flow<LowQualityRedownloadOperation?> = dao.observeCurrentOperation()

    fun observeItems(operationId: String): Flow<List<LowQualityRedownloadItem>> =
        dao.observeItems(operationId)

    fun observeLiveCounts(operationId: String): Flow<LowQualityRedownloadLiveCounts> =
        dao.observeLiveCounts(operationId)

    suspend fun getCurrentOperation(): LowQualityRedownloadOperation? = dao.getCurrentOperation()

    suspend fun getActiveOperation(): LowQualityRedownloadOperation? = dao.getActiveOperation()

    suspend fun getOperation(operationId: String): LowQualityRedownloadOperation? =
        dao.getOperation(operationId)

    suspend fun getItems(operationId: String): List<LowQualityRedownloadItem> {
        while (true) {
            val remaining = getItemsFailureCount.get()
            if (remaining <= 0) break
            if (getItemsFailureCount.compareAndSet(remaining, remaining - 1)) {
                throw IllegalStateException(
                    "Injected transient low-quality getItems failure",
                )
            }
        }
        return dao.getItems(operationId)
    }

    suspend fun hasLinkedDownload(downloadId: Long): Boolean =
        dao.getItemByDownloadId(downloadId) != null

    private fun isPendingUserCancellation(item: LowQualityRedownloadItem): Boolean =
        item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED &&
            item.reasonCode.startsWith(DownloadRepository.PENDING_CANCELLATION_TOKEN_PREFIX) &&
            DownloadRepository.isValidPendingCancellationToken(item.reasonCode)

    private fun isLivePendingUserCancellation(item: LowQualityRedownloadItem): Boolean =
        isPendingUserCancellation(item) &&
            DownloadRepository.isProcessLocalPendingUndoAuthority(item.reasonCode)

    /**
     * Runtime-independent owner for durable Undo carriers whose process-local
     * Snackbar authority disappeared.  This is intentionally narrower than
     * normal low-quality reconciliation: it consumes only exact pending user
     * Undo tokens and derives the parent result from the current sibling set.
     */
    suspend fun reconcileAbandonedUndoDebts(): Set<String> {
        val affectedOperationIds = linkedSetOf<String>()
        getPendingUndoItemsForRecovery()
            .filter(::isSupportedAbandonedUndoToken)
            .forEach { item ->
                if (reconcileAbandonedUndoItem(item)) {
                    affectedOperationIds += item.operationId
                }
            }
        return affectedOperationIds
    }

    /**
     * Reconciles one exact abandoned Undo carrier for the process-local
     * successor owner.  A true result means that the exact carrier is no
     * longer pending; false means the caller must retain ownership and retry.
     * The token is the only selection key, so a retry owner cannot consume a
     * different Snackbar token or sibling operation.
     */
    internal suspend fun reconcileAbandonedUndoDebt(token: String): Boolean {
        val item = getPendingUndoItemsForRecovery()
            .firstOrNull { it.reasonCode == token }
            ?: return if (DownloadRepository.isUndoAuthorityKnown(token)) {
                DownloadRepository.resolveRecoveryWithoutCarrier(token)
            } else {
                // Process death clears the registry.  With no durable item,
                // the exact token has already lost its carrier.
                true
            }
        if (!isSupportedAbandonedUndoToken(item)) return true
        val resolved = reconcileAbandonedUndoItem(item)
        if (!resolved) return false
        val downloadId = item.downloadId ?: return false
        val current = dao.getItemByDownloadId(downloadId)
        val stillPending = isSamePendingUndoCarrier(current, token)
        if (!stillPending) {
            DownloadRepository.resolveRecoveryWithoutCarrier(token)
        }
        return !stillPending
    }

    private suspend fun reconcileAbandonedUndoItem(
        item: LowQualityRedownloadItem,
    ): Boolean {
        val downloadId = item.downloadId ?: return false
        when {
            isPendingUserCancellation(item) -> {
                val operation = dao.getOperation(item.operationId)
                val strongerOperationAuthority =
                    operation?.cancelRequested == true ||
                        operation?.stateValue?.isTerminal == true
                if (
                    isLivePendingUserCancellation(item) &&
                        !strongerOperationAuthority
                ) {
                    // A live Snackbar owner is positive authority, not
                    // abandoned debt.  Recovery must preserve its exact
                    // token until that owner resolves or abandons it.
                    return false
                }
                DownloadRepository(database)
                    .commitPendingCancellationForRecovery(downloadId, item.reasonCode)
            }
            item.reasonCode.startsWith(DownloadRepository.PENDING_REMOVAL_TOKEN_PREFIX) -> {
                if (DownloadRepository.isUndoResolverInFlight(item.reasonCode)) {
                    return false
                }
                if (DownloadRepository.isProcessLocalPendingUndoAuthority(item.reasonCode)) {
                    return false
                }
                // A pending-removal carrier is expected to have lost its
                // primary row atomically.  Do not consume a token against a
                // row that may have been restored by a still-live owner.
                if (database.downloadDao.getNullableDownloadById(downloadId) != null) {
                    return false
                }
                commitAbandonedPendingRemoval(item)
            }
            else -> return true
        }
        return !isSamePendingUndoCarrier(
            dao.getItemByDownloadId(downloadId),
            item.reasonCode,
        )
    }

    private fun isSupportedAbandonedUndoToken(item: LowQualityRedownloadItem): Boolean =
        isPendingUserCancellation(item) ||
            item.reasonCode.startsWith(DownloadRepository.PENDING_REMOVAL_TOKEN_PREFIX)

    private fun isSamePendingUndoCarrier(
        item: LowQualityRedownloadItem?,
        token: String,
    ): Boolean =
        item?.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED &&
            item.reasonCode == token

    private suspend fun getPendingUndoItemsForRecovery(): List<LowQualityRedownloadItem> {
        while (true) {
            val remaining = pendingUndoItemsReadFailureCount.get()
            if (remaining <= 0) break
            if (pendingUndoItemsReadFailureCount.compareAndSet(remaining, remaining - 1)) {
                throw IllegalStateException(
                    "Injected transient pending Undo read failure",
                )
            }
        }
        return dao.getPendingUndoItems()
    }

    private suspend fun commitAbandonedPendingRemoval(
        item: LowQualityRedownloadItem,
    ): Set<String> = database.withTransaction {
        val downloadId = item.downloadId ?: return@withTransaction emptySet()
        abandonedPendingRemovalCommitFailureForTesting?.invoke()?.let { throw it }
        if (
            dao.commitUndoableLinkedItem(
                downloadId = downloadId,
                expectedToken = item.reasonCode,
                reason = DownloadRepository.REASON_USER_REMOVED,
                updatedAt = System.currentTimeMillis(),
            ) != 1
        ) {
            return@withTransaction emptySet()
        }
        finalizeIfReadyLocked(item.operationId, System.currentTimeMillis())
        setOf(item.operationId)
    }

    suspend fun createOrReconnect(now: Long = System.currentTimeMillis()): LowQualityRedownloadOperation =
        database.withTransaction {
            dao.getActiveOperation()?.let { return@withTransaction it }
            val upperBound = database.historyDao.getVideoQualityScanUpperBound()
            val operation = LowQualityRedownloadOperation(
                operationId = UUID.randomUUID().toString(),
                scanUpperBoundHistoryId = upperBound,
                scanTotal = database.historyDao.getVideoQualityScanCount(upperBound),
                createdAt = now,
                updatedAt = now
            )
            dao.createOrReconnect(operation)
        }

    suspend fun setSelected(operationId: String, historyId: Long, selected: Boolean) {
        dao.setSelected(operationId, historyId, selected, System.currentTimeMillis())
    }

    suspend fun confirmSelection(operationId: String): Boolean = database.withTransaction {
        val operation = dao.getOperation(operationId) ?: return@withTransaction false
        if (
            operation.stateValue.isTerminal ||
            operation.phaseValue != LowQualityRedownloadPhase.AWAITING_SELECTION ||
            dao.countSelected(operationId) == 0
        ) {
            return@withTransaction false
        }
        // Candidate identity is captured during the scan and must be present
        // before the user-confirmed operation can enter PREPARING.  Legacy
        // rows without it remain fail-closed instead of rebinding by ID.
        if (dao.getSelectedItems(operationId).any {
                it.intendedSourceUrl.isBlank() || it.intendedType.isBlank()
            }
        ) {
            return@withTransaction false
        }
        val now = System.currentTimeMillis()
        dao.markUnselected(operationId, now)
        dao.advancePhase(
            operationId,
            LowQualityRedownloadPhase.AWAITING_SELECTION.name,
            LowQualityRedownloadPhase.PREPARING.name,
            now
        ) == 1
    }

    suspend fun checkpointScan(
        operationId: String,
        historyId: Long,
        candidate: LowQualityRedownloadItem?,
        failed: Boolean
    ) {
        dao.checkpointScanItem(
            operationId = operationId,
            cursor = historyId,
            candidate = candidate,
            failed = failed,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun advancePhase(
        operationId: String,
        expected: LowQualityRedownloadPhase,
        next: LowQualityRedownloadPhase
    ): Boolean = dao.advancePhase(
        operationId,
        expected.name,
        next.name,
        System.currentTimeMillis()
    ) == 1

    suspend fun setItemState(
        operationId: String,
        historyId: Long,
        state: LowQualityRedownloadItemState,
        reason: String = ""
    ) {
        dao.setItemState(operationId, historyId, state.name, reason, System.currentTimeMillis())
    }

    suspend fun linkDownloadAtomically(
        operationId: String,
        historyId: Long,
        downloadItem: DownloadItem
    ): Long? = withDownloadWorkerExecutionLock {
        database.withTransaction {
        val current = dao.getItem(operationId, historyId)
            ?: error("Missing low-quality ledger item")
        current.downloadId?.let { return@withTransaction it }
        check(current.stateValue == LowQualityRedownloadItemState.CHECKING) {
            "Low-quality item is not ready for queue linkage"
        }
        if (dao.getOperation(operationId)?.cancelRequested == true) {
            dao.setItemState(
                operationId,
                historyId,
                LowQualityRedownloadItemState.CANCELLED.name,
                REASON_USER_CANCELLED,
                System.currentTimeMillis(),
            )
            return@withTransaction null
        }
        if (current.intendedSourceUrl.isNotBlank() || current.intendedType.isNotBlank()) {
            val history = database.historyDao.getNullableItem(historyId)
            when {
                history == null -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.SKIPPED.name,
                        "HISTORY_MISSING",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
                current.intendedSourceUrl.isBlank() -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.FAILED.name,
                        "SELECTION_IDENTITY_MISSING",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
                current.intendedType.isBlank() -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.FAILED.name,
                        "SELECTION_TYPE_MISSING",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
                current.intendedType != history.type.name -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.SKIPPED.name,
                        "SELECTION_TYPE_CHANGED",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
                !HistoryReplacementSourceIdentity.matches(
                    current.intendedSourceUrl,
                    history.url
                ) -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.SKIPPED.name,
                        "SELECTION_SOURCE_CHANGED",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
                !HistoryReplacementSourceIdentity.matches(
                    current.intendedSourceUrl,
                    downloadItem.url
                ) || downloadItem.type.name != current.intendedType -> {
                    dao.setItemState(
                        operationId,
                        historyId,
                        LowQualityRedownloadItemState.FAILED.name,
                        "SELECTION_DOWNLOAD_IDENTITY_MISMATCH",
                        System.currentTimeMillis()
                    )
                    return@withTransaction null
                }
            }
        }
        if (
            database.downloadDao.countPendingByPlaylistMarker(
                com.ireum.ytdl.util.HistoryRedownloadMarker.regular(historyId)
            ) > 0
        ) {
            dao.setItemState(
                operationId,
                historyId,
                LowQualityRedownloadItemState.SKIPPED.name,
                "DUPLICATE",
                System.currentTimeMillis()
            )
            return@withTransaction null
        }
        val id = database.downloadDao.insert(downloadItem)
        check(
            dao.linkQueuedDownload(operationId, historyId, id, System.currentTimeMillis()) == 1
        ) { "Low-quality queue linkage lost ownership" }
        id
        }
    }

    suspend fun markDownloadState(
        downloadId: Long,
        state: LowQualityRedownloadItemState,
        reason: String = "",
        expectedExecutionId: String = "",
    ): String? = database.withTransaction {
        if (expectedExecutionId.isNotBlank()) {
            val download = database.downloadDao.getNullableDownloadById(downloadId)
            if (download?.executionId != expectedExecutionId) return@withTransaction null
        }
        val item = dao.getItemByDownloadId(downloadId) ?: return@withTransaction null
        val operation = dao.getOperation(item.operationId) ?: return@withTransaction null
        if (operation.stateValue.isTerminal) return@withTransaction null
        val now = System.currentTimeMillis()
        if (
            isLivePendingUserCancellation(item) &&
                !operation.cancelRequested
        ) {
            // Normal worker/reconciliation progress cannot consume a live
            // item-level Undo authority.  The exact owner must resolve it or
            // lifecycle abandonment must transfer it to recovery first.
            return@withTransaction null
        }
        if (
            operation.cancelRequested ||
            item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED
        ) {
            if (operation.cancelRequested && isPendingUserCancellation(item)) {
                DownloadRepository.releaseLivePendingCancellationToken(item.reasonCode)
            }
            dao.markCancelledByDownloadId(
                downloadId = downloadId,
                reason = REASON_USER_CANCELLED,
                updatedAt = now,
            )
            finalizeIfReadyLocked(item.operationId, now)
            return@withTransaction item.operationId
        }
        if (state == LowQualityRedownloadItemState.CANCELLATION_REQUESTED) {
            return@withTransaction null
        }
        if (dao.setItemStateByDownloadId(downloadId, state.name, reason, now) != 1) {
            return@withTransaction null
        }
        finalizeIfReadyLocked(item.operationId, now)
        item.operationId
    }

    suspend fun finishNoCandidates(operationId: String) = database.withTransaction {
        val operation = dao.getOperation(operationId) ?: return@withTransaction
        if (operation.stateValue.isTerminal) return@withTransaction
        val now = System.currentTimeMillis()
        if (
            !operation.cancelRequested &&
                dao.getItems(operationId).any(::isLivePendingUserCancellation)
        ) {
            // A live item-level Undo keeps the operation nonterminal until
            // that exact owner resolves or abandons its carrier.
            return@withTransaction
        }
        if (operation.cancelRequested) {
            // Cancellation is a durable winner once phase one committed.  Do
            // not let the scan's no-candidate result overwrite it.
            dao.getItems(operationId)
                .filter(::isPendingUserCancellation)
                .forEach { DownloadRepository.releaseLivePendingCancellationToken(it.reasonCode) }
            dao.terminalizeNonterminalItems(
                operationId,
                LowQualityRedownloadItemState.CANCELLED.name,
                REASON_USER_CANCELLED,
                now,
            )
            check(
                dao.finishCancelledOperation(
                    operationId,
                    REASON_USER_CANCELLED,
                    now,
                ) == 1
            ) { "Low-quality cancellation lost the no-candidate terminal race" }
            return@withTransaction
        }
        val allInspectionsFailed =
            operation.scanProcessed > 0 && operation.scanFailures >= operation.scanProcessed
        val state = if (allInspectionsFailed) {
            LowQualityRedownloadOperationState.FAILED
        } else {
            LowQualityRedownloadOperationState.COMPLETED
        }
        val reason = when {
            allInspectionsFailed -> REASON_SCAN_FAILED
            operation.scanFailures > 0 -> REASON_NO_CANDIDATES_WITH_FAILURES
            else -> REASON_NO_CANDIDATES
        }
        check(
            dao.finishOperationIfCancellationNotRequested(
                operationId,
                state.name,
                reason,
                now,
            ) == 1
        ) { "Low-quality no-candidate terminalization lost operation ownership" }
    }

    suspend fun requestCancellation(operationId: String): List<Long> {
        // Phase one is the revocation boundary.  Acquire each currently active
        // child's resource lease before taking the global claim lock so an
        // in-flight destructive side effect finishes before cancellation can
        // become durable.  The short global transaction then prevents a new
        // claim from entering after the revocation wins.
        return withCurrentLinkedExecutionLeases(operationId) {
            dao.requestCancellationAndMarkItems(
                operationId,
                System.currentTimeMillis(),
            )
        }
    }

    suspend fun isCancellationRequestedForDownload(downloadId: Long): Boolean =
        database.withTransaction {
            val item = dao.getItemByDownloadId(downloadId) ?: return@withTransaction false
            val operation = dao.getOperation(item.operationId)
            operation?.cancelRequested == true ||
                item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED
        }

    internal data class CancellationCommitResult(
        val downloadIds: List<Long>,
        val publications: List<DownloadCancellationRegistry.Publication>,
    )

    internal suspend fun completePersistedCancellation(
        operationId: String,
        context: Context? = null,
    ): List<Long> {
        val result = completePersistedCancellationWithPublications(operationId, context)
        DownloadCancellationRegistry.publish(result.publications)
        return result.downloadIds
    }

    internal suspend fun completePersistedCancellationWithPublications(
        operationId: String,
        context: Context? = null,
    ): CancellationCommitResult {
        val pendingTokensToRelease = linkedSetOf<String>()
        val result = withCurrentLinkedExecutionLeases(
            operationId = operationId,
            beforeAction = {
                if (context != null) {
                    val recoveryDisposition = if (dao.getOperation(operationId)?.cancelRequested == true) {
                        DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL
                    } else {
                        DownloadExecutionRecovery.RecoveryDisposition.GENERIC
                    }
                    val ids = dao.getNonterminalDownloadIds(operationId)
                    database.downloadDao.getDownloadsByIdsSuspend(ids)
                        .filter {
                            it.executionId.isNotBlank() &&
                                it.status in setOf(
                                    DownloadRepository.Status.Active.name,
                                    DownloadRepository.Status.PostProcessing.name,
                                )
                        }
                        .forEach { item ->
                            // This is the independent durable native-debt
                            // carrier.  A failed commit is intentionally not
                            // treated as success.  Do not terminalize the
                            // operation until the carrier is durable; the
                            // convergence owner will retry this phase.
                            check(
                                DownloadExecutionRecovery.recordPending(
                                    context = context,
                                    item = item,
                                    disposition = recoveryDisposition,
                                )
                            ) {
                                "Could not publish native cancellation recovery for ${item.id}"
                            }
                        }
                }
            },
        ) {
            val publications = mutableListOf<DownloadCancellationRegistry.Publication>()
            val linkedDownloadIds = database.withTransaction {
                val operation = dao.getOperation(operationId)
                    ?: return@withTransaction emptyList()
                if (operation.stateValue.isTerminal || !operation.cancelRequested) {
                    return@withTransaction emptyList()
                }
                val now = System.currentTimeMillis()
                val linkedDownloadIds = dao.getNonterminalDownloadIds(operationId)
                dao.getItems(operationId)
                    .filter(::isPendingUserCancellation)
                    .mapTo(pendingTokensToRelease) { it.reasonCode }
                if (linkedDownloadIds.isNotEmpty()) {
                    cancelLinkedDownloadsAndCollectOwnership(
                        linkedDownloadIds,
                        publications,
                    )
                }
                dao.terminalizeNonterminalItems(
                    operationId,
                    LowQualityRedownloadItemState.CANCELLED.name,
                    REASON_USER_CANCELLED,
                    now
                )
                val downloadRepository = DownloadRepository(database)
                linkedDownloadIds.forEach { downloadId ->
                    downloadRepository.convergeHistoryReplacementRefusalInCurrentTransaction(
                        id = downloadId,
                        forceError = true,
                    )
                }
                if (dao.getOperation(operationId)?.stateValue == LowQualityRedownloadOperationState.RUNNING) {
                    check(
                        dao.finishOperation(
                            operationId,
                            LowQualityRedownloadOperationState.CANCELLED.name,
                            REASON_USER_CANCELLED,
                            now
                        ) == 1
                    ) { "Low-quality cancellation lost operation ownership" }
                }
                linkedDownloadIds
            }
            CancellationCommitResult(linkedDownloadIds, publications)
        }
        pendingTokensToRelease.forEach {
            DownloadRepository.releaseLivePendingCancellationToken(it)
        }
        scheduleUnpublishedUserStopRecovery(context, result)
        return result
    }

    suspend fun failCoordinator(
        operationId: String,
        reason: String = REASON_COORDINATOR_FAILURE,
        context: Context? = null,
    ): List<Long> {
        val result = failCoordinatorWithPublications(operationId, reason, context)
        DownloadCancellationRegistry.publish(result.publications)
        return result.downloadIds
    }

    internal suspend fun failCoordinatorWithPublications(
        operationId: String,
        reason: String = REASON_COORDINATOR_FAILURE,
        context: Context? = null,
    ): CancellationCommitResult {
        val pendingTokensToRelease = linkedSetOf<String>()
        val result = withCurrentLinkedExecutionLeases(
            operationId = operationId,
            beforeAction = {
                if (context != null) {
                    val recoveryDisposition = if (dao.getOperation(operationId)?.cancelRequested == true) {
                        DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL
                    } else {
                        DownloadExecutionRecovery.RecoveryDisposition.GENERIC
                    }
                    val ids = dao.getNonterminalDownloadIds(operationId)
                    database.downloadDao.getDownloadsByIdsSuspend(ids)
                        .filter {
                            it.executionId.isNotBlank() &&
                                it.status in setOf(
                                    DownloadRepository.Status.Active.name,
                                    DownloadRepository.Status.PostProcessing.name,
                                )
                        }
                        .forEach { item ->
                            check(
                                DownloadExecutionRecovery.recordPending(
                                    context = context,
                                    item = item,
                                    disposition = recoveryDisposition,
                                )
                            ) {
                                "Could not publish native cancellation recovery for ${item.id}"
                            }
                        }
                }
            },
        ) {
            val publications = mutableListOf<DownloadCancellationRegistry.Publication>()
            val linkedDownloadIds = database.withTransaction {
                val operation = dao.getOperation(operationId)
                    ?: return@withTransaction emptyList()
                if (operation.stateValue.isTerminal) return@withTransaction emptyList()
                val now = System.currentTimeMillis()
                val cancelledByUser = operation.cancelRequested
                val itemState = if (cancelledByUser) {
                    LowQualityRedownloadItemState.CANCELLED
                } else {
                    LowQualityRedownloadItemState.FAILED
                }
                val terminalState = if (cancelledByUser) {
                    LowQualityRedownloadOperationState.CANCELLED
                } else {
                    LowQualityRedownloadOperationState.FAILED
                }
                val terminalReason = if (cancelledByUser) REASON_USER_CANCELLED else reason
                val linkedDownloadIds = dao.getNonterminalDownloadIds(operationId)
                dao.getItems(operationId)
                    .filter(::isPendingUserCancellation)
                    .mapTo(pendingTokensToRelease) { it.reasonCode }
                if (linkedDownloadIds.isNotEmpty()) {
                    cancelLinkedDownloadsAndCollectOwnership(
                        linkedDownloadIds,
                        publications,
                    )
                }
                dao.terminalizeNonterminalItems(
                    operationId,
                    itemState.name,
                    terminalReason,
                    now
                )
                val downloadRepository = DownloadRepository(database)
                linkedDownloadIds.forEach { downloadId ->
                    downloadRepository.convergeHistoryReplacementRefusalInCurrentTransaction(
                        id = downloadId,
                        forceError = true,
                    )
                }
                if (dao.getOperation(operationId)?.stateValue == LowQualityRedownloadOperationState.RUNNING) {
                    check(
                        dao.finishOperation(
                            operationId,
                            terminalState.name,
                            terminalReason,
                            now
                        ) == 1
                    ) { "Low-quality coordinator failure lost operation ownership" }
                }
                linkedDownloadIds
            }
            CancellationCommitResult(linkedDownloadIds, publications)
        }
        pendingTokensToRelease.forEach {
            DownloadRepository.releaseLivePendingCancellationToken(it)
        }
        scheduleUnpublishedUserStopRecovery(context, result)
        return result
    }

    /**
     * A committed low-quality cancellation normally returns a publication so
     * its caller can consume native quiescence immediately.  A committed
     * History replacement intentionally has no Download cancellation
     * publication, however; its exact user-stop carrier still needs a live
     * recovery owner so it is converted to History finalization in this
     * process instead of waiting for an unrelated restart.
     */
    private fun scheduleUnpublishedUserStopRecovery(
        context: Context?,
        result: CancellationCommitResult,
    ) {
        if (context == null) return
        val publishedIds = result.publications.mapTo(mutableSetOf()) { it.downloadId }
        result.downloadIds.distinct().forEach { downloadId ->
            if (
                downloadId !in publishedIds &&
                    DownloadExecutionRecovery.pendingDispositionForExecution(
                        context,
                        downloadId,
                    ) == DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL
            ) {
                runCatching {
                    DownloadExecutionRecovery.scheduleRecovery(
                        context = context,
                        downloadId = downloadId,
                        dbManager = database,
                    )
                }.onFailure { schedulingFailure ->
                    android.util.Log.e(
                        "LowQualityRedownload",
                        "Could not schedule unpublished user-stop recovery for $downloadId",
                        schedulingFailure,
                    )
                }
            }
        }
    }

    /**
     * Acquires currently active linked Download resource leases before the
     * short global ownership transaction.  The snapshot is revalidated after
     * the leases are held, so a claim that wins in the gap is either included
     * in the next attempt or cannot race the terminal transition.  The global
     * lock is never held while waiting for a side-effect lease.
     */
    private suspend fun <T : Any> withCurrentLinkedExecutionLeases(
        operationId: String,
        beforeAction: suspend () -> Unit = {},
        action: suspend () -> T,
    ): T {
        while (true) {
            val executions = withDownloadWorkerExecutionLock {
                currentLinkedExecutionTokens(operationId)
            }
            val result = withDownloadWorkerExecutionSideEffectLeases(executions) {
                beforeAction()
                beforeFinalLinkedExecutionRevalidationForTesting?.invoke(
                    operationId,
                    executions,
                )
                // Keep the final token-set revalidation and the terminal or
                // revocation transaction in one global critical section. A
                // queued child claimed after the snapshot is therefore either
                // observed here and retried with its lease, or is prevented
                // from publishing an E2 until this action commits.
                withDownloadWorkerExecutionLock {
                    if (currentLinkedExecutionTokens(operationId) != executions) {
                        null
                    } else {
                        beforeFinalLinkedExecutionActionForTesting?.invoke(
                            operationId,
                            executions,
                        )
                        action()
                    }
                }
            }
            if (result != null) return result
        }
    }

    private suspend fun currentLinkedExecutionTokens(
        operationId: String,
    ): List<Pair<Long, String>> {
        val linkedDownloadIds = dao.getNonterminalDownloadIds(operationId)
        return database.downloadDao
            .getDownloadsByIdsSuspend(linkedDownloadIds)
            .filter {
                it.executionId.isNotBlank() &&
                    it.status in setOf(
                        DownloadRepository.Status.Active.name,
                        DownloadRepository.Status.PostProcessing.name,
                    )
            }
            .map { it.id to it.executionId }
            .sortedBy { it.first }
    }

    /**
     * Bounded low-quality cancellation is item-local to each active child.  The
     * execution token is captured from the same Room transaction that changes
     * the row to Cancelled, before callers destroy the native process.
     */
    private suspend fun cancelLinkedDownloadsAndCollectOwnership(
        ids: List<Long>,
        publications: MutableList<DownloadCancellationRegistry.Publication>,
    ) {
        ids.forEach { id ->
            val item = database.downloadDao.getNullableDownloadById(id) ?: return@forEach
            val historyReplacementCommitted = HistoryRedownloadMarker.parse(item.playlistURL)?.let {
                database.historyDao.getNullableItem(it.historyId)?.downloadId == item.id
            } == true
            if (historyReplacementCommitted) {
                // The committed History replacement is the stronger primary
                // result. A late coordinator cancellation may terminalize its
                // own ledger, but it must not rewrite the Download row that
                // still carries post-commit finalization debt.
                return@forEach
            }
            val changed = if (item.executionId.isBlank()) {
                database.downloadDao.cancelByUser(item.id)
            } else {
                database.downloadDao.cancelIfExecutionOwned(item.id, item.executionId)
            }
            if (
                changed == 1 &&
                item.executionId.isNotBlank() &&
                item.status in setOf(
                    DownloadRepository.Status.Active.name,
                    DownloadRepository.Status.PostProcessing.name,
                )
            ) {
                publications += DownloadCancellationRegistry.Publication(
                    downloadId = item.id,
                    executionId = item.executionId,
                    reason = DownloadCancellationRegistry.Reason.CANCELLED,
                )
            }
        }
    }

    suspend fun markLinkedCancelled(operationId: String, downloadIds: List<Long>) {
        downloadIds.forEach { id ->
            val item = dao.getItemByDownloadId(id)
            val changed = dao.markCancelledByDownloadId(
                id,
                REASON_USER_CANCELLED,
                System.currentTimeMillis()
            )
            if (changed == 1 && item != null && isPendingUserCancellation(item)) {
                // The exact stronger cancellation is durable only after the
                // DAO mutation commits.  This method is also used by callers
                // that can throw after the mutation, so do not revoke the
                // non-transactional Undo owner before that boundary.
                DownloadRepository.releaseLivePendingCancellationToken(item.reasonCode)
            }
        }
        finalizeIfReady(operationId)
    }

    suspend fun reconcileLinkedDownloads(operationId: String): List<DownloadItem> =
        database.withTransaction {
        val items = dao.getItems(operationId).filter { it.downloadId != null && !it.stateValue.isTerminal }
        if (items.isEmpty()) {
            finalizeIfReadyLocked(operationId, System.currentTimeMillis())
            return@withTransaction emptyList()
        }
        val ids = items.mapNotNull(LowQualityRedownloadItem::downloadId)
        val downloads = database.downloadDao.getDownloadsByIdsSuspend(ids)
        val byId = downloads.associateBy(DownloadItem::id)
        val now = System.currentTimeMillis()
        items.forEach { item ->
            val id = item.downloadId ?: return@forEach
            val download = byId[id]
            if (
                download == null &&
                    item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED &&
                    item.reasonCode.startsWith(DownloadRepository.PENDING_REMOVAL_TOKEN_PREFIX)
            ) {
                if (DownloadRepository.isProcessLocalPendingUndoAuthority(item.reasonCode)) {
                    // A live Snackbar Undo token is still authoritative.  A
                    // routine reconcile must not consume it before the exact
                    // Undo action or explicit commit gets to run.
                    return@forEach
                }
                // A process death or coordinator reconciliation commits an
                // unresolved delete-for-Undo token rather than inventing a
                // generic failure for the missing, intentionally hidden row.
                dao.markCancelledByDownloadId(
                    id,
                    DownloadRepository.REASON_USER_REMOVED,
                    now,
                )
                return@forEach
            }
            val operation = dao.getOperation(item.operationId)
            if (
                isLivePendingUserCancellation(item) &&
                    operation?.cancelRequested != true &&
                    operation?.stateValue?.isTerminal != true
            ) {
                // Ordinary linked reconciliation is not allowed to turn a
                // still-live exact Undo token into terminal cancellation.
                return@forEach
            }
            if (
                (operation?.cancelRequested == true || operation?.stateValue?.isTerminal == true) &&
                    isPendingUserCancellation(item)
            ) {
                // Operation-wide cancellation is stronger than item-level
                // Undo and explicitly revokes its process-local authority.
                DownloadRepository.releaseLivePendingCancellationToken(item.reasonCode)
            }
            val terminalState = if (
                operation?.cancelRequested == true || operation?.stateValue?.isTerminal == true
            ) {
                LowQualityRedownloadItemState.CANCELLED
            } else {
                LowQualityRedownloadLinkedDownloadPolicy.reconciledState(
                    currentState = item.stateValue,
                    downloadStatus = download?.status,
                )
            }
            if (terminalState != null) {
                val reason = when {
                    operation?.cancelRequested == true -> REASON_USER_CANCELLED
                    download == null -> REASON_MISSING_DOWNLOAD
                    terminalState == LowQualityRedownloadItemState.SKIPPED ->
                        DownloadRepository.REASON_SAVED_FOR_LATER
                    terminalState == LowQualityRedownloadItemState.CANCELLED &&
                        item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED &&
                        item.reasonCode.startsWith(
                            DownloadRepository.PENDING_CANCELLATION_TOKEN_PREFIX
                        ) -> REASON_USER_CANCELLED
                    else -> ""
                }
                val changed = if (terminalState == LowQualityRedownloadItemState.CANCELLED) {
                    dao.markCancelledByDownloadId(id, reason, now)
                } else {
                    dao.setItemStateByDownloadId(id, terminalState.name, reason, now)
                }
                if (changed == 0 && item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED) {
                    dao.markCancelledByDownloadId(id, REASON_USER_CANCELLED, now)
                }
            }
        }
        finalizeIfReadyLocked(operationId, now)
        downloads
    }

    suspend fun progress(operationId: String): LowQualityRedownloadProgress? {
        val operation = dao.getOperation(operationId) ?: return null
        return LowQualityRedownloadProgress.from(
            operation,
            dao.getItems(operationId),
            dao.getLiveCounts(operationId)
        )
    }

    suspend fun finalizeIfReady(operationId: String): LowQualityRedownloadOperationState? =
        database.withTransaction {
            finalizeIfReadyLocked(operationId, System.currentTimeMillis())
        }

    /**
     * Re-derives one linked child from durable Download/operation state.  This
     * is the same convergence rule used by startup reconciliation, exposed for
     * the live-process recovery debt created when the Download terminal write
     * commits but the independent ledger write fails.
     */
    suspend fun reconcileDownload(downloadId: Long): String? = database.withTransaction {
        val item = dao.getItemByDownloadId(downloadId) ?: return@withTransaction null
        val operation = dao.getOperation(item.operationId) ?: return@withTransaction null
        val download = database.downloadDao.getNullableDownloadById(downloadId)
        val now = System.currentTimeMillis()
        if (
            isLivePendingUserCancellation(item) &&
                !operation.cancelRequested &&
                !operation.stateValue.isTerminal
        ) {
            // Preserve a live exact Snackbar authority across single-item
            // recovery; only abandoned debt may be terminalized here.
            return@withTransaction item.operationId
        }
        if (operation.cancelRequested || item.stateValue == LowQualityRedownloadItemState.CANCELLATION_REQUESTED) {
            if (
                (operation.cancelRequested || operation.stateValue.isTerminal) &&
                    isPendingUserCancellation(item)
            ) {
                DownloadRepository.releaseLivePendingCancellationToken(item.reasonCode)
            }
            dao.markCancelledByDownloadId(downloadId, REASON_USER_CANCELLED, now)
        } else if (isMembershipRetryAuthority(download, item, operation)) {
            // MEMBERSHIP_REQUIRED is a transient waiting/retry authority
            // while the exact Download/child/operation/source tuple is
            // coherent.  It must not be reclassified as terminal debt before
            // the production claim consumes it.
        } else if (
            !download?.lastIssueCode.isNullOrBlank() &&
                !item.stateValue.isTerminal
        ) {
            // The Download's durable terminal issue is the convergence fact;
            // do not infer a fresh operation from a later mutable queue state.
            dao.setItemStateByDownloadId(
                downloadId,
                LowQualityRedownloadItemState.FAILED.name,
                download?.lastIssueCode.orEmpty(),
                now,
            )
        } else if (operation.stateValue.isTerminal && item.stateValue.isTerminal) {
            return@withTransaction item.operationId
        } else {
            LowQualityRedownloadLinkedDownloadPolicy.reconciledState(
                currentState = item.stateValue,
                downloadStatus = download?.status,
            )?.let { state ->
                if (state == LowQualityRedownloadItemState.CANCELLED) {
                    dao.markCancelledByDownloadId(downloadId, REASON_USER_CANCELLED, now)
                } else {
                    dao.setItemStateByDownloadId(downloadId, state.name, "", now)
                }
            }
        }
        finalizeIfReadyLocked(item.operationId, now)
        item.operationId
    }

    /**
     * Distinguishes a real membership retry from terminal convergence debt.
     * The status and linked child state must advance together; a loose issue
     * code or a queued-list observation is not enough authority.
     */
    private suspend fun isMembershipRetryAuthority(
        download: DownloadItem?,
        item: LowQualityRedownloadItem,
        operation: LowQualityRedownloadOperation,
    ): Boolean {
        if (download == null) return false
        if (download.lastIssueCode != DownloadIssueCode.MEMBERSHIP_REQUIRED.name) return false
        if (operation.stateValue != LowQualityRedownloadOperationState.RUNNING) return false
        if (operation.cancelRequested) return false
        if (item.reasonCode.isNotBlank()) return false
        if (database.downloadDao.getHistoryReplacementBarrier(download.id) != null) return false
        val historyMarker = HistoryRedownloadMarker.parse(download.playlistURL)
        if (
            historyMarker != null &&
                database.historyDao.getNullableItem(historyMarker.historyId)?.downloadId == download.id
        ) {
            return false
        }
        val childStateIsCoherent = when (download.status) {
            // Production parking currently leaves the child QUEUED.  A
            // WAITING child is also a durable compensation/legacy topology;
            // both are valid only before the Download is requeued.  The
            // actual claim boundary still accepts Queued + QUEUED only.
            DownloadRepository.Status.WaitingForMembership.name ->
                item.stateValue in setOf(
                    LowQualityRedownloadItemState.WAITING,
                    LowQualityRedownloadItemState.QUEUED,
                )
            DownloadRepository.Status.Queued.name ->
                item.stateValue == LowQualityRedownloadItemState.QUEUED
            else -> return false
        }
        if (!childStateIsCoherent) return false
        return database.observeSourcesDao.getByIDOrNull(download.observeSourceId)?.status ==
            ObserveSourcesRepository.SourceStatus.ACTIVE
    }

    private suspend fun finalizeIfReadyLocked(
        operationId: String,
        now: Long
    ): LowQualityRedownloadOperationState? {
        val operation = dao.getOperation(operationId) ?: return null
        val items = dao.getItems(operationId)
        val finalState = LowQualityRedownloadCompletionPolicy.terminalState(operation, items)
            ?: return null
        if (operation.stateValue.isTerminal) return finalState
        dao.finishOperation(operationId, finalState.name, "", now)
        return finalState
    }

    companion object {
        /** Deterministic Room-read fault seam for the production convergence owner. */
        private val getItemsFailureCount = AtomicInteger(0)
        private val pendingUndoItemsReadFailureCount = AtomicInteger(0)

        /** Test seam for the first abandoned pending-removal commit. */
        @Volatile
        internal var abandonedPendingRemovalCommitFailureForTesting: (() -> Exception?)? = null

        internal var getItemsFailureCountForTesting: Int
            get() = getItemsFailureCount.get()
            set(value) {
                getItemsFailureCount.set(value.coerceAtLeast(0))
            }

        internal var pendingUndoItemsReadFailureCountForTesting: Int
            get() = pendingUndoItemsReadFailureCount.get()
            set(value) {
                pendingUndoItemsReadFailureCount.set(value.coerceAtLeast(0))
            }

        const val REASON_NO_CANDIDATES = "NO_CANDIDATES"
        const val REASON_NO_CANDIDATES_WITH_FAILURES = "NO_CANDIDATES_WITH_FAILURES"
        const val REASON_SCAN_FAILED = "SCAN_FAILED"
        const val REASON_COORDINATOR_FAILURE = "COORDINATOR_FAILURE"
        const val REASON_USER_CANCELLED = "USER_CANCELLED"
        const val REASON_MISSING_DOWNLOAD = "MISSING_DOWNLOAD_RECORD"
    }
}
