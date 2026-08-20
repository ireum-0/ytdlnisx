package com.ireum.ytdl.database.repository

import com.ireum.ytdl.R
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.ireum.ytdl.util.download.supportsSameSettingsRetry
import com.ireum.ytdl.util.download.summaryResourceId
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

    @Test
    fun persistedMismatchCodesMapToDistinctUserVisibleSummaryResources() {
        assertEquals(
            R.string.download_issue_history_source_mismatch,
            summaryResourceId(DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH)
        )
        assertEquals(
            R.string.download_issue_history_type_mismatch,
            summaryResourceId(DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH)
        )
        assertNotEquals(
            summaryResourceId(DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH),
            summaryResourceId(DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH)
        )
    }
}
