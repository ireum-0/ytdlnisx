package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun qualityCleanupAuthorizationKeepsMismatchFailureDistinctFromTargetMissing() {
        assertEquals(
            HistoryReplacementCleanupAction.AUTHORIZED_CLEANUP,
            HistoryReplacementOutcomePolicy.cleanupAction(
                HistoryReplacementAuthorization.Authorized(historyItem())
            )
        )
        assertEquals(
            HistoryReplacementTerminalAction.COMPLETE,
            HistoryReplacementOutcomePolicy.terminalAction(
                HistoryReplacementCleanupAction.AUTHORIZED_CLEANUP
            )
        )
        assertEquals(
            HistoryReplacementCleanupAction.TARGET_MISSING,
            HistoryReplacementOutcomePolicy.cleanupAction(
                HistoryReplacementAuthorization.TargetMissing
            )
        )
        assertEquals(
            HistoryReplacementTerminalAction.TARGET_DELETED,
            HistoryReplacementOutcomePolicy.terminalAction(
                HistoryReplacementCleanupAction.TARGET_MISSING
            )
        )
        assertEquals(
            HistoryReplacementCleanupAction.PRESERVE_FAILED,
            HistoryReplacementOutcomePolicy.cleanupAction(
                HistoryReplacementAuthorization.SourceMismatch
            )
        )
        assertEquals(
            HistoryReplacementCleanupAction.PRESERVE_FAILED,
            HistoryReplacementOutcomePolicy.cleanupAction(
                HistoryReplacementAuthorization.TypeMismatch
            )
        )
        assertEquals(
            HistoryReplacementTerminalAction.PRESERVE_FAILED,
            HistoryReplacementOutcomePolicy.terminalAction(
                HistoryReplacementCleanupAction.PRESERVE_FAILED
            )
        )
    }

    @Test
    fun refusedQualityCleanupCannotBecomePartialSuccess() {
        listOf(
            HistoryReplacementAuthorization.SourceMismatch,
            HistoryReplacementAuthorization.TypeMismatch,
        ).forEach { authorization ->
            assertFalse(
                HistoryReplacementOutcomePolicy.allowsPartialSuccess(
                    hasCreatedOutputs = true,
                    cleanupAction = HistoryReplacementOutcomePolicy.cleanupAction(authorization),
                )
            )
        }
        assertTrue(
            HistoryReplacementOutcomePolicy.allowsPartialSuccess(
                hasCreatedOutputs = true,
                cleanupAction = HistoryReplacementCleanupAction.AUTHORIZED_CLEANUP,
            )
        )
        assertFalse(
            HistoryReplacementOutcomePolicy.allowsPartialSuccess(
                hasCreatedOutputs = true,
                cleanupAction = HistoryReplacementCleanupAction.TARGET_MISSING,
            )
        )
        assertFalse(
            HistoryReplacementOutcomePolicy.allowsPartialSuccess(
                hasCreatedOutputs = false,
                cleanupAction = HistoryReplacementCleanupAction.TARGET_MISSING,
            )
        )
        assertTrue(
            HistoryReplacementOutcomePolicy.allowsPartialSuccess(
                hasCreatedOutputs = true,
                cleanupAction = null,
            )
        )
        assertFalse(
            HistoryReplacementOutcomePolicy.allowsPartialSuccess(
                hasCreatedOutputs = true,
                cleanupAction = null,
                authoritativeAction = HistoryReplacementTerminalAction.PRESERVE_FAILED,
            )
        )
        assertFalse(
            HistoryReplacementOutcomePolicy.allowsPartialSuccess(
                hasCreatedOutputs = true,
                cleanupAction = null,
                authoritativeAction = HistoryReplacementTerminalAction.TARGET_DELETED,
            )
        )
        assertFalse(
            HistoryReplacementOutcomePolicy.allowsPartialSuccess(
                hasCreatedOutputs = false,
                cleanupAction = null,
            )
        )
    }

    @Test
    fun establishedHistoryFailureCannotBeDowngradedByLaterCleanupDisposition() {
        val firstSourceMismatch = HistoryReplacementOutcomePolicy.terminalAction(
            HistoryReplacementOutcomePolicy.cleanupAction(
                HistoryReplacementAuthorization.SourceMismatch
            )
        )
        val firstTypeMismatch = HistoryReplacementOutcomePolicy.terminalAction(
            HistoryReplacementOutcomePolicy.cleanupAction(
                HistoryReplacementAuthorization.TypeMismatch
            )
        )
        val laterTargetMissing = HistoryReplacementOutcomePolicy.terminalAction(
            HistoryReplacementOutcomePolicy.cleanupAction(
                HistoryReplacementAuthorization.TargetMissing
            )
        )
        assertEquals(
            HistoryReplacementTerminalAction.PRESERVE_FAILED,
            HistoryReplacementOutcomePolicy.mergeTerminalAction(
                HistoryReplacementTerminalAction.PRESERVE_FAILED,
                HistoryReplacementTerminalAction.COMPLETE
            )
        )
        assertEquals(
            HistoryReplacementTerminalAction.PRESERVE_FAILED,
            HistoryReplacementOutcomePolicy.mergeTerminalAction(
                HistoryReplacementTerminalAction.COMPLETE,
                HistoryReplacementTerminalAction.PRESERVE_FAILED
            )
        )
        assertEquals(
            HistoryReplacementTerminalAction.PRESERVE_FAILED,
            HistoryReplacementOutcomePolicy.mergeTerminalAction(
                firstSourceMismatch,
                laterTargetMissing
            )
        )
        assertEquals(
            HistoryReplacementTerminalAction.PRESERVE_FAILED,
            HistoryReplacementOutcomePolicy.mergeTerminalAction(
                firstTypeMismatch,
                HistoryReplacementTerminalAction.COMPLETE
            )
        )
        assertFalse(
            HistoryReplacementOutcomePolicy.allowsPartialSuccess(
                hasCreatedOutputs = true,
                cleanupAction = null,
                authoritativeAction = HistoryReplacementOutcomePolicy.mergeTerminalAction(
                    HistoryReplacementTerminalAction.PRESERVE_FAILED,
                    HistoryReplacementTerminalAction.COMPLETE
                ),
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
