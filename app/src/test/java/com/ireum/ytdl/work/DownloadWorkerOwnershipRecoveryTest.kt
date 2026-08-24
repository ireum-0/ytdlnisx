package com.ireum.ytdl.work

import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun staleExecutionCannotCancelNewerWorkerOrProcessOwner() {
        val downloadId = 907L
        DownloadWorkerExecutionOwners.claim(downloadId, "E1")
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, "E1"))
        DownloadWorkerExecutionOwners.claim(downloadId, "E2")

        assertFalse(DownloadWorkerProcessOwners.claim(downloadId, "E2"))
        assertFalse(canCancelExecutionProcess(downloadId, "E1"))
        assertFalse(canCancelExecutionProcess(downloadId, "E2"))

        DownloadWorkerExecutionOwners.release(downloadId, "E2")
        DownloadWorkerProcessOwners.release(downloadId, "E1")
        DownloadWorkerProcessOwners.claim(downloadId, "E2")
        DownloadWorkerExecutionOwners.claim(downloadId, "E2")
        assertFalse(canCancelExecutionProcess(downloadId, "E1"))
        assertTrue(canCancelExecutionProcess(downloadId, "E2"))

        DownloadWorkerProcessOwners.release(downloadId, "E2")
        DownloadWorkerProcessOwners.release(downloadId, "E1")
    }

    @Test
    fun differentExecutionsCannotOverlapDownloadSideEffects() = runBlocking {
        val downloadId = 908L
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = launch {
            withDownloadWorkerExecutionSideEffectLease(downloadId, "E1") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val second = launch {
            withDownloadWorkerExecutionSideEffectLease(downloadId, "E2") {
                secondEntered = true
            }
        }
        yield()
        assertFalse(secondEntered)

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered)
    }

    @Test
    fun abandonedOrdinaryExecutionIsRequeuedWithItsExactToken() = runBlocking {
        val row = AbandonedDownloadExecution(904L, "E1", "Active")
        var persistedToken = ""
        var status = row.status

        recoverAbandonedDownloadExecutions(
            rows = listOf(row),
            isOwnedBy = { _, _ -> false },
            requeue = { id, executionId ->
                assertEquals(row.downloadId, id)
                persistedToken = executionId
                status = "Queued"
                1
            },
            readCurrent = { AbandonedDownloadExecution(row.downloadId, persistedToken, status) },
        )

        assertEquals("E1", persistedToken)
        assertEquals("Queued", status)
    }

    @Test
    fun oneAbandonedRecoveryFailureDoesNotPreventUnrelatedRowsFromRecovering() = runBlocking {
        val firstFailure = IOException("E1 requeue unavailable")
        val rows = listOf(
            AbandonedDownloadExecution(914L, "E1", "Active"),
            AbandonedDownloadExecution(915L, "E2", "PostProcessing"),
        )
        var secondRecovered = false
        var thrown: Exception? = null

        try {
            recoverAbandonedDownloadExecutions(
                rows = rows,
                isOwnedBy = { _, _ -> false },
                requeue = { id, _ ->
                    if (id == 914L) throw firstFailure
                    secondRecovered = true
                    1
                },
                readCurrent = { downloadId ->
                    AbandonedDownloadExecution(downloadId, "", "Queued")
                },
            )
        } catch (error: Exception) {
            thrown = error
        }

        assertSame(firstFailure, thrown)
        assertTrue(secondRecovered)
    }

    @Test
    fun abandonedExecutionCasDoesNotTouchNewerExecution() = runBlocking {
        val stale = AbandonedDownloadExecution(905L, "E1", "PostProcessing")
        val newer = AbandonedDownloadExecution(905L, "E2", "Active")
        var requeueAttempts = 0

        recoverAbandonedDownloadExecutions(
            rows = listOf(stale),
            isOwnedBy = { id, executionId -> id == newer.downloadId && executionId == newer.executionId },
            requeue = { _, _ ->
                requeueAttempts += 1
                0
            },
            readCurrent = { newer },
        )

        assertEquals(1, requeueAttempts)
        assertEquals("E2", newer.executionId)
        assertEquals("Active", newer.status)
    }

    @Test
    fun staleRecoveryWaitsForClaimOwnerPublication() = runBlocking {
        val downloadId = 906L
        var requeued = false

        val recovery = withDownloadWorkerExecutionLock {
            // The DB claim has completed, but publication of E2 is deliberately
            // paused while a stopped-worker recovery attempts to enter.
            val job = launch {
                withDownloadWorkerExecutionLock {
                    recoverAbandonedDownloadExecutions(
                        rows = listOf(AbandonedDownloadExecution(downloadId, "E2", "Active")),
                        isOwnedBy = DownloadWorkerExecutionOwners::isOwnedBy,
                        requeue = { _, _ ->
                            requeued = true
                            1
                        },
                        readCurrent = { AbandonedDownloadExecution(downloadId, "E2", "Active") },
                    )
                }
            }
            yield()
            assertFalse(requeued)
            DownloadWorkerExecutionOwners.claim(downloadId, "E2")
            job
        }

        // The recovery sees the published owner after the shared lock opens.
        recovery.join()
        assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, "E2"))
        DownloadWorkerExecutionOwners.release(downloadId, "E2")
        assertFalse(requeued)
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
