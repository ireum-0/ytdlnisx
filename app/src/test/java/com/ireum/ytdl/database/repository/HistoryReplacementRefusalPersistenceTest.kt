package com.ireum.ytdl.database.repository

import com.ireum.ytdl.util.download.DownloadIssue
import com.ireum.ytdl.util.download.DownloadIssueStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class HistoryReplacementRefusalPersistenceTest {
    @Test
    fun firstCarrierInsertFailureRetainsEveryTypedRefusal() = runBlocking {
        refusals().forEach { (authorization, issue) ->
            val persistenceFailure = IOException("insert failed")
            assertTypedPersistenceFailure(
                authorization = authorization,
                issue = issue,
                persist = { throw persistenceFailure },
                verify = { error("verification must not run") },
                persistenceFailure = persistenceFailure,
            )
        }
    }

    @Test
    fun firstCarrierVerificationFailureRetainsEveryTypedRefusal() = runBlocking {
        refusals().forEach { (authorization, issue) ->
            val persistenceFailure = IOException("verification failed")
            assertTypedPersistenceFailure(
                authorization = authorization,
                issue = issue,
                persist = { Unit },
                verify = { throw persistenceFailure },
                persistenceFailure = persistenceFailure,
            )
        }
    }

    @Test
    fun cancellationDuringFirstCarrierPersistenceIsNotReclassified() = runBlocking {
        val cancellation = CancellationException("cancelled")
        try {
            persistHistoryReplacementRefusalOrThrow(
                authorization = HistoryReplacementAuthorization.TargetMissing,
                issue = HistoryReplacementDiagnostic.targetDeletedIssue(),
                persist = { throw cancellation },
                verify = { error("verification must not run") },
            )
            fail("CancellationException was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private suspend fun assertTypedPersistenceFailure(
        authorization: HistoryReplacementAuthorization,
        issue: DownloadIssue,
        persist: suspend () -> Unit,
        verify: suspend () -> HistoryReplacementAuthorization,
        persistenceFailure: Exception,
    ) {
        try {
            persistHistoryReplacementRefusalOrThrow(
                authorization = authorization,
                issue = issue,
                persist = persist,
                verify = verify,
            )
            fail("typed refusal persistence failure was swallowed")
        } catch (actual: HistoryReplacementRefusalPersistenceException) {
            assertEquals(authorization, actual.authorization)
            assertEquals(issue.code, actual.issue.code)
            assertEquals(DownloadIssueStage.HISTORY, actual.issue.stage)
            assertSame(persistenceFailure, actual.persistenceFailure)
        }
    }

    private fun refusals(): List<Pair<HistoryReplacementAuthorization, DownloadIssue>> = listOf(
        HistoryReplacementAuthorization.SourceMismatch to
            HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE),
        HistoryReplacementAuthorization.TypeMismatch to
            HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.TYPE),
        HistoryReplacementAuthorization.TargetMissing to
            HistoryReplacementDiagnostic.targetDeletedIssue(),
    )
}
