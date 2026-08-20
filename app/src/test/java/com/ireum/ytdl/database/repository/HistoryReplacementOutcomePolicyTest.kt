package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryReplacementOutcomePolicyTest {
    @Test
    fun authorizedReplacementUsesNormalCompletion() {
        assertEquals(
            HistoryReplacementTerminalAction.COMPLETE,
            HistoryReplacementOutcomePolicy.terminalAction(
                HistoryReplacementOutcome.Updated(historyItem())
            )
        )
    }

    @Test
    fun missingTargetRetainsDeletedTargetCompletion() {
        assertEquals(
            HistoryReplacementTerminalAction.TARGET_DELETED,
            HistoryReplacementOutcomePolicy.terminalAction(
                HistoryReplacementOutcome.TargetMissing
            )
        )
    }

    @Test
    fun sourceMismatchPreservesDownloadAndFailsReplacement() {
        assertEquals(
            HistoryReplacementTerminalAction.PRESERVE_FAILED,
            HistoryReplacementOutcomePolicy.terminalAction(
                HistoryReplacementOutcome.SourceMismatch
            )
        )
    }

    @Test
    fun typeMismatchPreservesDownloadAndFailsReplacement() {
        assertEquals(
            HistoryReplacementTerminalAction.PRESERVE_FAILED,
            HistoryReplacementOutcomePolicy.terminalAction(
                HistoryReplacementOutcome.TypeMismatch
            )
        )
    }

    private fun historyItem() = HistoryItem(
        id = 1L,
        url = "https://example.com/video",
        title = "Video",
        author = "Author",
        duration = "1:00",
        thumb = "",
        type = DownloadType.video,
        time = 1L,
        downloadPath = emptyList(),
        website = "example.com",
        format = Format(),
        downloadId = 1L,
    )
}
