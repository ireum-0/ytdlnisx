package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadLiveCounts
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.LowQualityRedownloadOperationState
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase

data class LowQualityRedownloadProgress(
    val operationId: String,
    val phase: LowQualityRedownloadPhase,
    val state: LowQualityRedownloadOperationState,
    val scanTotal: Int,
    val scanProcessed: Int,
    val provisional: Int,
    val scanFailures: Int,
    val selected: Int,
    val qualificationProcessed: Int,
    val queued: Int,
    val active: Int,
    val waiting: Int,
    val paused: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val cancelled: Int,
    val completed: Int,
    val cancelRequested: Boolean,
    val terminalReason: String
) {
    val isTerminal: Boolean
        get() = state.isTerminal

    companion object {
        fun from(
            operation: LowQualityRedownloadOperation,
            items: List<LowQualityRedownloadItem>,
            liveCounts: LowQualityRedownloadLiveCounts? = null
        ): LowQualityRedownloadProgress {
            fun count(state: LowQualityRedownloadItemState) = items.count { it.stateValue == state }
            val selectedItems = items.filter(LowQualityRedownloadItem::selected)
            val qualificationProcessed = selectedItems.count {
                it.stateValue !in setOf(
                    LowQualityRedownloadItemState.PENDING,
                    LowQualityRedownloadItemState.CHECKING,
                    LowQualityRedownloadItemState.PROVISIONAL
                )
            }
            val succeeded = count(LowQualityRedownloadItemState.SUCCEEDED)
            val failed = count(LowQualityRedownloadItemState.FAILED)
            val skipped = count(LowQualityRedownloadItemState.SKIPPED)
            val cancelled = count(LowQualityRedownloadItemState.CANCELLED)
            return LowQualityRedownloadProgress(
                operationId = operation.operationId,
                phase = operation.phaseValue,
                state = operation.stateValue,
                scanTotal = operation.scanTotal,
                scanProcessed = operation.scanProcessed,
                provisional = items.count {
                    it.stateValue == LowQualityRedownloadItemState.PROVISIONAL ||
                        it.stateValue == LowQualityRedownloadItemState.PENDING
                },
                scanFailures = operation.scanFailures,
                selected = selectedItems.size,
                qualificationProcessed = qualificationProcessed,
                queued = liveCounts?.queued ?: count(LowQualityRedownloadItemState.QUEUED),
                active = liveCounts?.active ?: count(LowQualityRedownloadItemState.ACTIVE),
                waiting = liveCounts?.waiting ?: count(LowQualityRedownloadItemState.WAITING),
                paused = liveCounts?.paused ?: 0,
                succeeded = succeeded,
                failed = failed,
                skipped = skipped,
                cancelled = cancelled,
                completed = succeeded + failed + skipped + cancelled,
                cancelRequested = operation.cancelRequested,
                terminalReason = operation.terminalReason
            )
        }
    }
}

enum class LowQualityRedownloadNotificationState {
    SCANNING,
    READY_TO_REVIEW,
    PREPARING,
    DOWNLOADING,
    COMPLETED,
    PARTIAL_FAILURE,
    CANCELLED,
    FAILED,
    NO_CANDIDATES,
    UNRECOVERABLE
}

object LowQualityRedownloadNotificationPolicy {
    fun state(progress: LowQualityRedownloadProgress): LowQualityRedownloadNotificationState {
        if (progress.isTerminal) {
            if (progress.state == LowQualityRedownloadOperationState.COMPLETED && progress.selected == 0) {
                return LowQualityRedownloadNotificationState.NO_CANDIDATES
            }
            return when (progress.state) {
                LowQualityRedownloadOperationState.COMPLETED -> LowQualityRedownloadNotificationState.COMPLETED
                LowQualityRedownloadOperationState.PARTIAL_FAILURE -> LowQualityRedownloadNotificationState.PARTIAL_FAILURE
                LowQualityRedownloadOperationState.CANCELLED -> LowQualityRedownloadNotificationState.CANCELLED
                LowQualityRedownloadOperationState.FAILED -> LowQualityRedownloadNotificationState.FAILED
                LowQualityRedownloadOperationState.UNRECOVERABLE -> LowQualityRedownloadNotificationState.UNRECOVERABLE
                LowQualityRedownloadOperationState.RUNNING -> error("Nonterminal state")
            }
        }
        return when (progress.phase) {
            LowQualityRedownloadPhase.SCANNING -> LowQualityRedownloadNotificationState.SCANNING
            LowQualityRedownloadPhase.AWAITING_SELECTION -> LowQualityRedownloadNotificationState.READY_TO_REVIEW
            LowQualityRedownloadPhase.PREPARING,
            LowQualityRedownloadPhase.QUEUEING -> LowQualityRedownloadNotificationState.PREPARING
            LowQualityRedownloadPhase.DOWNLOADING,
            LowQualityRedownloadPhase.FINALIZING -> LowQualityRedownloadNotificationState.DOWNLOADING
        }
    }

    fun shouldNotify(previousAt: Long, now: Long, phaseChanged: Boolean, stateChanged: Boolean): Boolean {
        return phaseChanged || stateChanged || now - previousAt >= 1_000L
    }

    fun allowCancel(progress: LowQualityRedownloadProgress): Boolean = !progress.isTerminal
}

object LowQualityRedownloadCompletionPolicy {
    fun terminalState(
        operation: LowQualityRedownloadOperation,
        items: List<LowQualityRedownloadItem>
    ): LowQualityRedownloadOperationState? {
        if (operation.stateValue.isTerminal) return operation.stateValue
        if (
            operation.phaseValue == LowQualityRedownloadPhase.AWAITING_SELECTION &&
            !operation.cancelRequested
        ) return null
        val selected = items.filter(LowQualityRedownloadItem::selected)
        if (selected.any { !it.stateValue.isTerminal }) return null
        return when {
            operation.cancelRequested -> LowQualityRedownloadOperationState.CANCELLED
            selected.isEmpty() && operation.phaseValue == LowQualityRedownloadPhase.SCANNING ->
                LowQualityRedownloadOperationState.COMPLETED
            selected.isEmpty() -> LowQualityRedownloadOperationState.FAILED
            selected.any { it.stateValue == LowQualityRedownloadItemState.FAILED } &&
                selected.any {
                    it.stateValue == LowQualityRedownloadItemState.SUCCEEDED ||
                        it.stateValue == LowQualityRedownloadItemState.SKIPPED
                } -> LowQualityRedownloadOperationState.PARTIAL_FAILURE
            selected.any { it.stateValue == LowQualityRedownloadItemState.FAILED } ->
                LowQualityRedownloadOperationState.FAILED
            selected.any { it.stateValue == LowQualityRedownloadItemState.CANCELLED } &&
                selected.any {
                    it.stateValue == LowQualityRedownloadItemState.SUCCEEDED ||
                        it.stateValue == LowQualityRedownloadItemState.SKIPPED
                } -> LowQualityRedownloadOperationState.PARTIAL_FAILURE
            selected.all { it.stateValue == LowQualityRedownloadItemState.CANCELLED } ->
                LowQualityRedownloadOperationState.CANCELLED
            else -> LowQualityRedownloadOperationState.COMPLETED
        }
    }
}

data class LowQualityScanCheckpoint(
    val cursor: Long,
    val processed: Int,
    val failures: Int
) {
    fun advance(historyId: Long, failed: Boolean): LowQualityScanCheckpoint {
        require(historyId > cursor) { "History scan checkpoints must advance in stable ID order" }
        return copy(
            cursor = historyId,
            processed = processed + 1,
            failures = failures + if (failed) 1 else 0
        )
    }
}
