package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.repository.DownloadRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class LowQualityRedownloadLinkedDownloadPolicyTest {
    @Test
    fun savedDownloadSkipsNonterminalLinkedChild() {
        assertEquals(
            LowQualityRedownloadItemState.SKIPPED,
            LowQualityRedownloadLinkedDownloadPolicy.reconciledState(
                LowQualityRedownloadItemState.QUEUED,
                DownloadRepository.Status.Saved.name,
            )
        )
    }

    @Test
    fun terminalChildStatesAreMonotonic() {
        listOf(
            LowQualityRedownloadItemState.SUCCEEDED,
            LowQualityRedownloadItemState.FAILED,
            LowQualityRedownloadItemState.SKIPPED,
            LowQualityRedownloadItemState.CANCELLED,
            LowQualityRedownloadItemState.NOT_SELECTED,
        ).forEach { terminalState ->
            assertEquals(
                terminalState,
                LowQualityRedownloadLinkedDownloadPolicy.reconciledState(
                    terminalState,
                    DownloadRepository.Status.Saved.name,
                )
            )
        }
    }
}
