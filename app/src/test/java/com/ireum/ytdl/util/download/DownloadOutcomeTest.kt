package com.ireum.ytdl.util.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadOutcomeTest {

    @Test
    fun completedFileWithIssue_isSuccessWithWarnings() {
        val issue = DownloadIssue.create(
            stage = DownloadIssueStage.HISTORY,
            code = DownloadIssueCode.HISTORY_WRITE_FAILED,
            severity = DownloadIssueSeverity.WARNING,
            details = "History insert failed"
        )

        val outcome = DownloadOutcome.completed(createdFileCount = 1, issues = listOf(issue))

        assertEquals(DownloadOutcomeStatus.SUCCESS_WITH_WARNINGS, outcome.status)
        assertEquals(1, outcome.createdFileCount)
    }

    @Test
    fun cancellation_isDistinctFromFailure() {
        val outcome = DownloadOutcome.canceled()

        assertEquals(DownloadOutcomeStatus.CANCELED, outcome.status)
        assertEquals(emptyList<DownloadIssue>(), outcome.issues)
    }

    @Test
    fun issueFactory_redactsAndBoundsDetails() {
        val issue = DownloadIssue.create(
            stage = DownloadIssueStage.DOWNLOAD,
            code = DownloadIssueCode.UNKNOWN,
            details = "token=secret ${"x".repeat(9_000)}"
        )

        assertFalse(issue.redactedDetails.contains("secret"))
        assertEquals(8_000, issue.redactedDetails.length)
    }
}
