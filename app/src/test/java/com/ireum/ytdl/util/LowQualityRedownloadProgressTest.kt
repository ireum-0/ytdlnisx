package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.LowQualityRedownloadOperationState
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowQualityRedownloadProgressTest {
    @Test
    fun aggregationCountsMixedTransferStatesAndCompletedItems() {
        val progress = LowQualityRedownloadProgress.from(
            operation(phase = LowQualityRedownloadPhase.DOWNLOADING, processed = 8, failures = 1),
            listOf(
                item(1, LowQualityRedownloadItemState.QUEUED),
                item(2, LowQualityRedownloadItemState.ACTIVE),
                item(3, LowQualityRedownloadItemState.WAITING),
                item(4, LowQualityRedownloadItemState.SUCCEEDED),
                item(5, LowQualityRedownloadItemState.FAILED),
                item(6, LowQualityRedownloadItemState.SKIPPED),
                item(7, LowQualityRedownloadItemState.CANCELLED),
                item(8, LowQualityRedownloadItemState.NOT_SELECTED, selected = false)
            )
        )

        assertEquals(7, progress.selected)
        assertEquals(1, progress.queued)
        assertEquals(1, progress.active)
        assertEquals(1, progress.waiting)
        assertEquals(1, progress.succeeded)
        assertEquals(1, progress.failed)
        assertEquals(1, progress.skipped)
        assertEquals(1, progress.cancelled)
        assertEquals(4, progress.completed)
        assertEquals(1, progress.scanFailures)
    }

    @Test
    fun candidatesAreDefaultUnselectedAndSelectionIsReflected() {
        val provisional = LowQualityRedownloadItem(operationId = ID, historyId = 1)
        assertFalse(provisional.selected)
        assertEquals(LowQualityRedownloadItemState.PROVISIONAL, provisional.stateValue)

        val selected = provisional.copy(selected = true, itemState = LowQualityRedownloadItemState.PENDING.name)
        val progress = LowQualityRedownloadProgress.from(operation(), listOf(selected))
        assertEquals(1, progress.selected)
    }

    @Test
    fun completionWaitsForEverySelectedItemAndAllowsPartialFailure() {
        val operation = operation(phase = LowQualityRedownloadPhase.DOWNLOADING)
        assertNull(
            LowQualityRedownloadCompletionPolicy.terminalState(
                operation,
                listOf(
                    item(1, LowQualityRedownloadItemState.SUCCEEDED),
                    item(2, LowQualityRedownloadItemState.ACTIVE)
                )
            )
        )
        assertEquals(
            LowQualityRedownloadOperationState.PARTIAL_FAILURE,
            LowQualityRedownloadCompletionPolicy.terminalState(
                operation,
                listOf(
                    item(1, LowQualityRedownloadItemState.SUCCEEDED),
                    item(2, LowQualityRedownloadItemState.FAILED),
                    item(3, LowQualityRedownloadItemState.SKIPPED)
                )
            )
        )
    }

    @Test
    fun cancellationWinsButSuccessfulReplacementsRemainCounted() {
        val cancelledOperation = operation(
            phase = LowQualityRedownloadPhase.DOWNLOADING,
            cancelRequested = true
        )
        val items = listOf(
            item(1, LowQualityRedownloadItemState.SUCCEEDED),
            item(2, LowQualityRedownloadItemState.CANCELLED)
        )
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            LowQualityRedownloadCompletionPolicy.terminalState(cancelledOperation, items)
        )
        assertEquals(1, LowQualityRedownloadProgress.from(cancelledOperation, items).succeeded)
    }

    @Test
    fun itemCancellationIsNotReportedAsSuccessfulCompletion() {
        val operation = operation(phase = LowQualityRedownloadPhase.DOWNLOADING)
        assertEquals(
            LowQualityRedownloadOperationState.PARTIAL_FAILURE,
            LowQualityRedownloadCompletionPolicy.terminalState(
                operation,
                listOf(
                    item(1, LowQualityRedownloadItemState.SUCCEEDED),
                    item(2, LowQualityRedownloadItemState.CANCELLED)
                )
            )
        )
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            LowQualityRedownloadCompletionPolicy.terminalState(
                operation,
                listOf(item(1, LowQualityRedownloadItemState.CANCELLED))
            )
        )
    }

    @Test
    fun awaitingSelectionDoesNotCompleteWhenObserverDetaches() {
        val awaiting = operation(phase = LowQualityRedownloadPhase.AWAITING_SELECTION)
        assertNull(
            LowQualityRedownloadCompletionPolicy.terminalState(
                awaiting,
                listOf(item(1, LowQualityRedownloadItemState.PROVISIONAL, selected = false))
            )
        )
    }

    @Test
    fun notificationPolicyCoversEveryOperationStateAndActionPolicy() {
        fun progress(
            phase: LowQualityRedownloadPhase,
            state: LowQualityRedownloadOperationState = LowQualityRedownloadOperationState.RUNNING,
            reason: String = "",
            selected: Boolean = true
        ): LowQualityRedownloadProgress = LowQualityRedownloadProgress.from(
            operation(phase = phase, state = state, reason = reason),
            if (selected) listOf(item(1, LowQualityRedownloadItemState.SUCCEEDED)) else emptyList()
        )

        assertEquals(LowQualityRedownloadNotificationState.SCANNING, LowQualityRedownloadNotificationPolicy.state(progress(LowQualityRedownloadPhase.SCANNING)))
        val ready = progress(LowQualityRedownloadPhase.AWAITING_SELECTION)
        assertEquals(LowQualityRedownloadNotificationState.READY_TO_REVIEW, LowQualityRedownloadNotificationPolicy.state(ready))
        assertTrue(LowQualityRedownloadNotificationPolicy.allowCancel(ready))
        assertEquals(LowQualityRedownloadNotificationState.PREPARING, LowQualityRedownloadNotificationPolicy.state(progress(LowQualityRedownloadPhase.PREPARING)))
        assertEquals(LowQualityRedownloadNotificationState.DOWNLOADING, LowQualityRedownloadNotificationPolicy.state(progress(LowQualityRedownloadPhase.DOWNLOADING)))
        assertEquals(LowQualityRedownloadNotificationState.COMPLETED, LowQualityRedownloadNotificationPolicy.state(progress(LowQualityRedownloadPhase.FINALIZING, LowQualityRedownloadOperationState.COMPLETED)))
        assertEquals(LowQualityRedownloadNotificationState.PARTIAL_FAILURE, LowQualityRedownloadNotificationPolicy.state(progress(LowQualityRedownloadPhase.FINALIZING, LowQualityRedownloadOperationState.PARTIAL_FAILURE)))
        assertEquals(LowQualityRedownloadNotificationState.CANCELLED, LowQualityRedownloadNotificationPolicy.state(progress(LowQualityRedownloadPhase.FINALIZING, LowQualityRedownloadOperationState.CANCELLED)))
        assertEquals(LowQualityRedownloadNotificationState.FAILED, LowQualityRedownloadNotificationPolicy.state(progress(LowQualityRedownloadPhase.FINALIZING, LowQualityRedownloadOperationState.FAILED)))
        assertEquals(LowQualityRedownloadNotificationState.UNRECOVERABLE, LowQualityRedownloadNotificationPolicy.state(progress(LowQualityRedownloadPhase.FINALIZING, LowQualityRedownloadOperationState.UNRECOVERABLE)))
        assertEquals(LowQualityRedownloadNotificationState.NO_CANDIDATES, LowQualityRedownloadNotificationPolicy.state(progress(LowQualityRedownloadPhase.FINALIZING, LowQualityRedownloadOperationState.COMPLETED, selected = false)))
        assertTrue(LowQualityRedownloadNotificationPolicy.allowCancel(progress(LowQualityRedownloadPhase.DOWNLOADING)))
    }

    @Test
    fun notificationThrottleAllowsAtMostOneRoutineScanUpdatePerSecond() {
        assertFalse(LowQualityRedownloadNotificationPolicy.shouldNotify(1_000, 1_999, false, false))
        assertTrue(LowQualityRedownloadNotificationPolicy.shouldNotify(1_000, 2_000, false, false))
        assertTrue(LowQualityRedownloadNotificationPolicy.shouldNotify(1_999, 2_000, true, false))
        assertTrue(LowQualityRedownloadNotificationPolicy.shouldNotify(1_999, 2_000, false, true))
    }

    @Test
    fun scanCheckpointAdvancesWithoutRepeatingCompletedRows() {
        val checkpoint = LowQualityScanCheckpoint(cursor = 10, processed = 4, failures = 1)
            .advance(historyId = 12, failed = true)
        assertEquals(12L, checkpoint.cursor)
        assertEquals(5, checkpoint.processed)
        assertEquals(2, checkpoint.failures)
        assertTrue(runCatching { checkpoint.advance(12, false) }.isFailure)
    }

    private fun operation(
        phase: LowQualityRedownloadPhase = LowQualityRedownloadPhase.SCANNING,
        state: LowQualityRedownloadOperationState = LowQualityRedownloadOperationState.RUNNING,
        processed: Int = 0,
        failures: Int = 0,
        cancelRequested: Boolean = false,
        reason: String = ""
    ) = LowQualityRedownloadOperation(
        operationId = ID,
        phase = phase.name,
        state = state.name,
        scanTotal = 8,
        scanProcessed = processed,
        scanFailures = failures,
        cancelRequested = cancelRequested,
        terminalReason = reason
    )

    private fun item(
        historyId: Long,
        state: LowQualityRedownloadItemState,
        selected: Boolean = true
    ) = LowQualityRedownloadItem(
        operationId = ID,
        historyId = historyId,
        selected = selected,
        itemState = state.name
    )

    companion object {
        private const val ID = "operation"
    }
}
