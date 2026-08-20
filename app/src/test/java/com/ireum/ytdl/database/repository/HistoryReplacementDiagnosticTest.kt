package com.ireum.ytdl.database.repository

import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.ireum.ytdl.util.download.supportsSameSettingsRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryReplacementDiagnosticTest {
    @Test
    fun sourceMismatchMapsToDistinctHistoryStageNonRetryableDiagnostic() {
        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)

        assertEquals(
            DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH,
            issue.code
        )
        assertEquals(DownloadIssueStage.HISTORY, issue.stage)
        assertFalse(issue.retryable)
        assertFalse(issue.code.supportsSameSettingsRetry())
        assertTrue(issue.redactedDetails.contains("source"))
    }

    @Test
    fun typeMismatchMapsToDistinctHistoryStageNonRetryableDiagnostic() {
        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.TYPE)

        assertEquals(
            DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH,
            issue.code
        )
        assertEquals(DownloadIssueStage.HISTORY, issue.stage)
        assertFalse(issue.retryable)
        assertFalse(issue.code.supportsSameSettingsRetry())
        assertTrue(issue.redactedDetails.contains("media type"))
    }

    @Test
    fun mismatchKindsRemainDistinctAndTargetMissingHasNoMismatchDiagnostic() {
        assertEquals(
            HistoryReplacementMismatchKind.SOURCE,
            HistoryReplacementDiagnostic.mismatchKind(HistoryReplacementOutcome.SourceMismatch)
        )
        assertEquals(
            HistoryReplacementMismatchKind.TYPE,
            HistoryReplacementDiagnostic.mismatchKind(HistoryReplacementOutcome.TypeMismatch)
        )
        assertNotEquals(
            HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE).code,
            HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.TYPE).code
        )
        assertNull(
            HistoryReplacementDiagnostic.mismatchKind(HistoryReplacementOutcome.TargetMissing)
        )
    }
}
