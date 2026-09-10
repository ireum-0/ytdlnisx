package com.ireum.ytdl.work

import com.ireum.ytdl.database.repository.DownloadRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPrimarySuccessStopPolicyTest {
    @Test
    fun lateWorkerStopCannotOverridePrimarySuccess() {
        assertFalse(
            shouldStopForDownloadExecution(
                workerStopped = true,
                lostExecutionOwnership = false,
                primarySuccessCommitted = true,
                durableUserStop = true,
                lowQualityCancellationRequested = true,
                latestStatus = DownloadRepository.Status.Cancelled.name,
            ),
        )
    }

    @Test
    fun latePausedRowCannotOverridePrimarySuccess() {
        assertFalse(
            shouldStopForDownloadExecution(
                workerStopped = false,
                lostExecutionOwnership = false,
                primarySuccessCommitted = true,
                durableUserStop = false,
                lowQualityCancellationRequested = false,
                latestStatus = DownloadRepository.Status.Paused.name,
            ),
        )
    }

    @Test
    fun stopBeforePrimarySuccessStillWins() {
        assertTrue(
            shouldStopForDownloadExecution(
                workerStopped = true,
                lostExecutionOwnership = false,
                primarySuccessCommitted = false,
                durableUserStop = false,
                lowQualityCancellationRequested = false,
                latestStatus = DownloadRepository.Status.Active.name,
            ),
        )
    }

    @Test
    fun lostExecutionOwnershipAlwaysStopsStaleWorker() {
        assertTrue(
            shouldStopForDownloadExecution(
                workerStopped = false,
                lostExecutionOwnership = true,
                primarySuccessCommitted = true,
                durableUserStop = false,
                lowQualityCancellationRequested = false,
                latestStatus = null,
            ),
        )
    }
}
