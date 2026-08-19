package com.ireum.ytdl.work

import com.ireum.ytdl.database.repository.DownloadRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowQualityReplacementDuplicatePolicyTest {
    @Test
    fun allPendingVideoStatesBlockSameSourceReplacement() {
        listOf(
            DownloadRepository.Status.Active,
            DownloadRepository.Status.PostProcessing,
            DownloadRepository.Status.Queued,
            DownloadRepository.Status.WaitingForMembership,
            DownloadRepository.Status.Scheduled,
            DownloadRepository.Status.Paused,
            DownloadRepository.Status.Processing,
        ).forEach { status ->
            assertTrue(
                status.name,
                isBlockingLowQualityReplacementDuplicate(
                    status = status.name,
                    isVideo = true,
                    sameSource = true,
                )
            )
        }
    }

    @Test
    fun terminalStatesDoNotBlockLaterReplacement() {
        listOf(
            DownloadRepository.Status.Saved,
            DownloadRepository.Status.Error,
            DownloadRepository.Status.Cancelled,
            DownloadRepository.Status.Duplicate,
        ).forEach { status ->
            assertFalse(
                status.name,
                isBlockingLowQualityReplacementDuplicate(
                    status = status.name,
                    isVideo = true,
                    sameSource = true,
                )
            )
        }
    }

    @Test
    fun differentSourceOrNonVideoDownloadDoesNotBlockReplacement() {
        assertFalse(
            isBlockingLowQualityReplacementDuplicate(
                status = DownloadRepository.Status.Active.name,
                isVideo = false,
                sameSource = true,
            )
        )
        assertFalse(
            isBlockingLowQualityReplacementDuplicate(
                status = DownloadRepository.Status.Active.name,
                isVideo = true,
                sameSource = false,
            )
        )
    }
}
