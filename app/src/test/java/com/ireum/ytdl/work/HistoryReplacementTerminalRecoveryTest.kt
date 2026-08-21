package com.ireum.ytdl.work

import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.util.download.DownloadIssue
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.ireum.ytdl.util.download.DownloadOutcome
import com.ireum.ytdl.util.download.DownloadOutcomeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HistoryReplacementTerminalRecoveryTest {
    @Test
    fun sourceMismatchSurvivesFirstTerminalWriteFailureAndRecovery() {
        assertMismatchSurvivesRecovery(HistoryReplacementMismatchKind.SOURCE)
    }

    @Test
    fun typeMismatchSurvivesFirstTerminalWriteFailureAndRecovery() {
        assertMismatchSurvivesRecovery(HistoryReplacementMismatchKind.TYPE)
    }

    @Test
    fun successfulTerminalPersistenceKeepsTheMismatchAndFailsLinkedChild() {
        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)
        var persistCalls = 0
        var downloadStatus = "Active"
        var linkedState = "ACTIVE"
        var linkedReason = ""

        val result = runBlocking {
            persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = {
                    persistCalls += 1
                    downloadStatus = "Error"
                },
                transitionLinkedDownload = { reason ->
                    linkedState = "FAILED"
                    linkedReason = reason
                },
            )
        }

        assertEquals(HistoryReplacementPersistenceResult.Persisted, result)
        assertEquals(1, persistCalls)
        assertEquals("Error", downloadStatus)
        assertEquals("FAILED", linkedState)
        assertEquals(issue.code.name, linkedReason)
        assertEquals(issue.code, HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE).code)
    }

    @Test
    fun unrecoverableTerminalWriteCannotBeHandledAsSuccessOrUnknown() {
        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.TYPE)
        val fallback = DownloadIssue.create(
            stage = DownloadIssueStage.HISTORY,
            code = DownloadIssueCode.UNKNOWN,
        )
        var downloadStatus = "Active"
        var calls = 0

        fun persistAttempt() = runBlocking {
            persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = {
                    calls += 1
                    throw IOException("database unavailable")
                },
                transitionLinkedDownload = { error("ledger must not run") },
            )
        }

        val firstFailure = persistAttempt()
        val recoveryFailure = persistAttempt()
        val recoveredIssue = authoritativeDownloadIssue(issue, fallback)
        val unrecoverable = unrecoverableHistoryReplacementPersistenceFailure(
            establishedHistoryIssue = issue,
            result = recoveryFailure,
        )

        assertEquals(2, calls)
        assertEquals(DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH, recoveredIssue.code)
        assertEquals(DownloadIssueStage.HISTORY, recoveredIssue.stage)
        assertTrue(firstFailure is HistoryReplacementPersistenceResult.Failed)
        assertTrue(recoveryFailure is HistoryReplacementPersistenceResult.Failed)
        assertNotNull(unrecoverable)
        assertEquals("Active", downloadStatus)
        assertEquals(DownloadOutcomeStatus.FINAL_FAILURE, DownloadOutcome.failed(recoveredIssue).status)
        assertFalse(DownloadOutcome.failed(recoveredIssue).issues.any { it.code == DownloadIssueCode.UNKNOWN })
    }

    @Test
    fun cancellationFromDownloadOrLedgerPersistenceRemainsCancellation() {
        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)

        assertCancellation {
            persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = { throw CancellationException("cancelled during queue write") },
                transitionLinkedDownload = { error("ledger must not run") },
            )
        }
        assertCancellation {
            persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = { Unit },
                transitionLinkedDownload = { throw CancellationException("cancelled during ledger write") },
            )
        }
    }

    @Test
    fun ledgerFailureDoesNotEraseDurableMismatchWrite() {
        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.TYPE)
        var downloadStatus = "Active"

        val result = runBlocking {
            persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = { downloadStatus = "Error" },
                transitionLinkedDownload = { throw IOException("ledger unavailable") },
            )
        }

        assertEquals(HistoryReplacementPersistenceResult.Persisted, result)
        assertEquals("Error", downloadStatus)
    }

    @Test
    fun aPersistedMismatchRemainsAuthoritativeAfterALaterPreHistoryFailure() {
        val fallback = DownloadIssue.create(
            stage = DownloadIssueStage.PREFLIGHT,
            code = DownloadIssueCode.UNKNOWN,
        )

        listOf(
            DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH,
            DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH,
        ).forEach { persistedCode ->
            val restored = HistoryReplacementDiagnostic.persistedMismatchIssue(persistedCode.name)
            assertNotNull(restored)
            val authoritative = authoritativeDownloadIssue(restored, fallback)
            assertEquals(persistedCode, authoritative.code)
            assertEquals(DownloadIssueStage.HISTORY, authoritative.stage)
            assertFalse(authoritative.code == DownloadIssueCode.UNKNOWN)
        }
    }

    private fun assertMismatchSurvivesRecovery(kind: HistoryReplacementMismatchKind) {
        val issue = HistoryReplacementDiagnostic.issue(kind)
        val fallback = DownloadIssue.create(
            stage = DownloadIssueStage.HISTORY,
            code = DownloadIssueCode.UNKNOWN,
        )
        var persistCalls = 0
        var downloadStatus = "Active"
        var linkedState = "ACTIVE"
        var linkedReason = ""

        val firstResult = runBlocking {
            persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = {
                    persistCalls += 1
                    if (persistCalls == 1) {
                        throw IOException("first terminal write failed")
                    }
                    downloadStatus = "Error"
                },
                transitionLinkedDownload = { reason ->
                    linkedState = "FAILED"
                    linkedReason = reason
                },
            )
        }
        val recoveredIssue = authoritativeDownloadIssue(issue, fallback)

        assertTrue(firstResult is HistoryReplacementPersistenceResult.Failed)
        assertEquals(issue.code, recoveredIssue.code)
        assertEquals(DownloadIssueStage.HISTORY, recoveredIssue.stage)
        assertEquals(DownloadOutcomeStatus.FINAL_FAILURE, DownloadOutcome.failed(recoveredIssue).status)
        assertFalse(DownloadOutcome.failed(recoveredIssue).issues.any { it.code == DownloadIssueCode.UNKNOWN })

        val recoveryResult = runBlocking {
            persistHistoryReplacementTerminalState(
                issue = recoveredIssue,
                persistDownload = {
                    persistCalls += 1
                    downloadStatus = "Error"
                },
                transitionLinkedDownload = { reason ->
                    linkedState = "FAILED"
                    linkedReason = reason
                },
            )
        }

        assertEquals(HistoryReplacementPersistenceResult.Persisted, recoveryResult)
        assertEquals(2, persistCalls)
        assertEquals("Error", downloadStatus)
        assertEquals("FAILED", linkedState)
        assertEquals(issue.code.name, linkedReason)
    }

    private fun assertCancellation(block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            throw AssertionError("CancellationException was swallowed")
        } catch (_: CancellationException) {
            // Expected: cancellation never becomes a mismatch persistence failure.
        }
    }
}
