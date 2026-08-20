package com.ireum.ytdl.util.download

import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun completionWithoutIssues_isSuccess() {
        val outcome = composeCompletionOutcome(
            createdFileCount = 1,
            issues = emptyList(),
            forceFailure = false,
        )

        assertEquals(DownloadOutcomeStatus.SUCCESS, outcome.status)
        assertTrue(outcome.issues.isEmpty())
    }

    @Test
    fun notificationFailure_isRetainedAsSuccessWithWarnings() {
        val notificationIssue = DownloadIssue.create(
            stage = DownloadIssueStage.NOTIFICATION,
            code = DownloadIssueCode.NOTIFICATION_FAILED,
            severity = DownloadIssueSeverity.WARNING,
            details = "Notification creation failed"
        )

        val outcome = composeCompletionOutcome(
            createdFileCount = 1,
            issues = listOf(notificationIssue),
            forceFailure = false,
        )

        assertEquals(DownloadOutcomeStatus.SUCCESS_WITH_WARNINGS, outcome.status)
        assertEquals(
            listOf(DownloadIssueCode.NOTIFICATION_FAILED),
            outcome.issues.map(DownloadIssue::code)
        )
    }

    @Test
    fun unauthorizedHistoryReplacement_isFinalFailure() {
        val historyIssue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)

        val outcome = composeCompletionOutcome(
            createdFileCount = 1,
            issues = listOf(historyIssue),
            forceFailure = true,
        )

        assertEquals(DownloadOutcomeStatus.FINAL_FAILURE, outcome.status)
        assertEquals(
            listOf(DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH),
            outcome.issues.map(DownloadIssue::code)
        )
    }

    @Test
    fun unauthorizedReplacementWithNotificationFailure_remainsFinalFailureWithBothDiagnostics() {
        val historyIssue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.TYPE)
        val notificationIssue = DownloadIssue.create(
            stage = DownloadIssueStage.NOTIFICATION,
            code = DownloadIssueCode.NOTIFICATION_FAILED,
            severity = DownloadIssueSeverity.WARNING,
            details = "Error notification creation failed"
        )

        val outcome = composeCompletionOutcome(
            createdFileCount = 1,
            issues = listOf(historyIssue, notificationIssue),
            forceFailure = true,
        )

        assertEquals(DownloadOutcomeStatus.FINAL_FAILURE, outcome.status)
        assertEquals(
            setOf(
                DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH,
                DownloadIssueCode.NOTIFICATION_FAILED,
            ),
            outcome.issues.map(DownloadIssue::code).toSet()
        )
    }
}
