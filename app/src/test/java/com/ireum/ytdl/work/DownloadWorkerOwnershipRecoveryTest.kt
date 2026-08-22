package com.ireum.ytdl.work

import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

class DownloadWorkerOwnershipRecoveryTest {
    @Test
    fun releasingDeadExecutionCannotClearNewerExecutionOwner() {
        val downloadId = 903L
        DownloadWorkerExecutionOwners.claim(downloadId, "E1")
        DownloadWorkerExecutionOwners.claim(downloadId, "E2")

        DownloadWorkerExecutionOwners.release(downloadId, "E1")

        assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, "E2"))
        assertFalse(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, "E1"))
        DownloadWorkerExecutionOwners.release(downloadId, "E2")
    }

    @Test
    fun unrecoverableSourceAndTypeMismatchWritesRequeueWithExactBarrierAndPropagate() = runBlocking {
        listOf(
            HistoryReplacementMismatchKind.SOURCE,
            HistoryReplacementMismatchKind.TYPE,
        ).forEach { mismatchKind ->
            val issue = HistoryReplacementDiagnostic.issue(mismatchKind)
            val row = DurableRow(status = "Active")
            var terminalWrites = 0
            val terminalFailure = IOException("database unavailable")
            val firstResult = persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = {
                    terminalWrites += 1
                    throw terminalFailure
                },
                transitionLinkedDownload = { error("ledger must not run") },
            )
            val recoveryResult = persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = {
                    terminalWrites += 1
                    throw terminalFailure
                },
                transitionLinkedDownload = { error("ledger must not run") },
            )
            val unrecoverable = requireNotNull(
                unrecoverableHistoryReplacementPersistenceFailure(issue, recoveryResult)
            )
            var thrown: Exception? = null

            try {
                rethrowAfterOwnedDownloadCleanup(unrecoverable) {
                    requeueOwnedDownloadRowsPreservingIssues(
                        downloadIds = listOf(1L),
                        authoritativeIssues = mapOf(1L to issue),
                        requeueWithIssue = { _, persistedIssue ->
                            row.status = "Queued"
                            row.lastIssueCode = persistedIssue.code.name
                            row.lastIssueStage = persistedIssue.stage.name
                        },
                        requeueOrdinary = { error("ordinary requeue must not handle mismatch") },
                    )
                }
            } catch (error: Exception) {
                thrown = error
            }

            assertSame(unrecoverable, thrown)
            assertEquals(2, terminalWrites)
            assertEquals(HistoryReplacementPersistenceResult.Failed::class.java, firstResult.javaClass)
            assertEquals("Queued", row.status)
            assertEquals(issue.code.name, row.lastIssueCode)
            assertEquals(DownloadIssueStage.HISTORY.name, row.lastIssueStage)
            assertFalse(row.status == "Active" || row.status == "PostProcessing")
            assertFalse(row.lastIssueCode == DownloadIssueCode.UNKNOWN.name)
        }
    }

    @Test
    fun cleanupFailureIsSuppressedWithoutTurningExceptionalExitIntoSuccess() = runBlocking {
        val failure = IOException("terminal mismatch persistence failed")
        val cleanupFailure = IOException("requeue unavailable")
        var thrown: Exception? = null

        try {
            rethrowAfterOwnedDownloadCleanup(failure) {
                throw cleanupFailure
            }
        } catch (error: Exception) {
            thrown = error
        }

        assertSame(failure, thrown)
        assertEquals(1, failure.suppressed.size)
        assertSame(cleanupFailure, failure.suppressed.single())
    }

    @Test
    fun unrelatedSiblingFinishesBeforeMismatchFailureIsRequeued() = runBlocking {
        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)
        val rows = linkedMapOf(
            1L to DurableRow(status = "Active"),
            2L to DurableRow(status = "Active"),
        )
        val failure = IOException("terminal mismatch persistence failed")
        var thrown: Exception? = null

        try {
            runDownloadItemsIndependently(listOf(1L, 2L)) { id ->
                if (id == 1L) throw failure
                rows.getValue(id).status = "Queued"
            }
        } catch (error: Exception) {
            thrown = error
        }

        try {
            rethrowAfterOwnedDownloadCleanup(failure) {
                requeueOwnedDownloadRowsPreservingIssues(
                    downloadIds = rows.keys,
                    authoritativeIssues = mapOf(1L to issue),
                    requeueWithIssue = { ids, persistedIssue ->
                        ids.forEach { id ->
                            rows.getValue(id).apply {
                                status = "Queued"
                                lastIssueCode = persistedIssue.code.name
                                lastIssueStage = persistedIssue.stage.name
                            }
                        }
                    },
                    requeueOrdinary = { ids -> ids.forEach { rows.getValue(it).status = "Queued" } },
                )
            }
        } catch (error: Exception) {
            assertSame(failure, error)
        }

        assertSame(failure, thrown)
        assertEquals("Queued", rows.getValue(1L).status)
        assertEquals(issue.code.name, rows.getValue(1L).lastIssueCode)
        assertEquals("Queued", rows.getValue(2L).status)
    }

    private data class DurableRow(
        var status: String,
        var lastIssueCode: String = "",
        var lastIssueStage: String = "",
    )
}
