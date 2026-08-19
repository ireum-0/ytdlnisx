package com.ireum.ytdl.work

import androidx.work.ExistingWorkPolicy
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class LowQualityRedownloadEnqueuePolicyTest {
    @Test
    fun confirmationAppendsSuccessorBehindRunningScan() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            LowQualityRedownloadEnqueuePolicy.CONFIRMATION.workPolicy
        )
    }

    @Test
    fun startAndRecoveryRetainKeepPolicy() {
        assertEquals(
            ExistingWorkPolicy.KEEP,
            LowQualityRedownloadEnqueuePolicy.RECOVERY.workPolicy
        )
    }

    @Test
    fun onlySuccessfulConditionalTransitionGetsTheConfirmationEnqueueRight() = runBlocking {
        var awaitingSelection = true
        val enqueued = mutableListOf<Pair<String, ExistingWorkPolicy>>()
        val transition: suspend (String) -> Boolean = {
            if (awaitingSelection) {
                awaitingSelection = false
                true
            } else {
                false
            }
        }
        val enqueue: (String, ExistingWorkPolicy) -> Unit = { id, policy ->
            enqueued += id to policy
        }

        assertTrue(confirmAndEnqueueLowQualityRedownload("operation", transition, enqueue))
        assertFalse(confirmAndEnqueueLowQualityRedownload("operation", transition, enqueue))
        assertEquals(
            listOf("operation" to ExistingWorkPolicy.APPEND_OR_REPLACE),
            enqueued
        )
    }

    @Test
    fun persistedCancellationIsDispatchedBeforeDownloadingRecovery() = runBlocking {
        var cancellationCompleted = false
        var phaseEnqueued = false
        var downloadsReconciled = false
        var notificationRefreshed = false
        val operation = LowQualityRedownloadOperation(
            operationId = "operation",
            phase = LowQualityRedownloadPhase.DOWNLOADING.name,
            cancelRequested = true
        )

        dispatchLowQualityRedownloadRecovery(
            operation = operation,
            completeCancellation = { cancellationCompleted = true },
            enqueuePhase = { _, _ -> phaseEnqueued = true },
            reconcileDownloads = { downloadsReconciled = true },
            refreshNotification = { notificationRefreshed = true }
        )

        assertTrue(cancellationCompleted)
        assertFalse(phaseEnqueued)
        assertFalse(downloadsReconciled)
        assertFalse(notificationRefreshed)
    }

    @Test
    fun unavailableExactAlarmPermissionDisablesSchedulerAndStartsNow() {
        assertEquals(
            LowQualityQueueStartDecision.START_NOW_DISABLE_SCHEDULER,
            lowQualityQueueStartDecision(
                useScheduler = true,
                isDuringScheduledTime = false,
                canScheduleExactAlarm = false
            )
        )
        assertEquals(
            LowQualityQueueStartDecision.SCHEDULE,
            lowQualityQueueStartDecision(
                useScheduler = true,
                isDuringScheduledTime = false,
                canScheduleExactAlarm = true
            )
        )
        assertEquals(
            LowQualityQueueStartDecision.START_NOW,
            lowQualityQueueStartDecision(
                useScheduler = true,
                isDuringScheduledTime = true,
                canScheduleExactAlarm = false
            )
        )
    }
}
