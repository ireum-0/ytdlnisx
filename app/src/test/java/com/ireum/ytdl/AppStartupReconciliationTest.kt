package com.ireum.ytdl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStartupReconciliationTest {
    @Test
    fun reconciliationWaitsForSuccessfulReadiness() = runBlocking {
        val readiness = CompletableDeferred<Boolean>()
        var reconciliationRan = false

        val reconciliation = async(start = CoroutineStart.UNDISPATCHED) {
            runStartupReconciliation(
                readiness = readiness,
                reconcile = { reconciliationRan = true },
                reportFailure = { throw AssertionError("Unexpected failure", it) }
            )
        }

        assertFalse(reconciliationRan)
        assertFalse(reconciliation.isCompleted)

        readiness.complete(true)
        reconciliation.await()

        assertTrue(reconciliationRan)
    }

    @Test
    fun failedInitializationReadinessSkipsReconciliation() = runBlocking {
        val expected = IllegalStateException("initialization failed")
        val reported = mutableListOf<Exception>()
        var reconciliationRan = false
        val readiness = CompletableDeferred<Boolean>()

        readiness.complete(
            runStartupInitialization(
                initialize = { throw expected },
                reportFailure = reported::add
            )
        )
        runStartupReconciliation(
            readiness = readiness,
            reconcile = { reconciliationRan = true },
            reportFailure = reported::add
        )

        assertEquals(listOf(expected), reported)
        assertFalse(reconciliationRan)
    }

    @Test
    fun initializationCancellationEscapesWithIdentityAndIsNotReported() = runBlocking {
        val expected = CancellationException("initialization cancelled")
        val reported = mutableListOf<Exception>()

        val actual = try {
            runStartupInitialization(
                initialize = { throw expected },
                reportFailure = reported::add
            )
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertSame(expected, actual)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun readinessCancellationPropagatesAndIsNotReported() = runBlocking {
        val expected = CancellationException("readiness cancelled")
        val reported = mutableListOf<Exception>()
        var reconciliationRan = false
        val readiness = CompletableDeferred<Boolean>().apply {
            completeExceptionally(expected)
        }

        val actual = try {
            runStartupReconciliation(
                readiness = readiness,
                reconcile = { reconciliationRan = true },
                reportFailure = reported::add
            )
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertEquals(expected.message, actual?.message)
        assertFalse(reconciliationRan)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun oneReconciliationFailureIsReportedWhileAnotherStillRuns() = runBlocking {
        val expected = IllegalStateException("reconciliation failed")
        val reported = mutableListOf<Exception>()
        var otherReconciliationRan = false
        val readiness = CompletableDeferred(true)

        awaitAll(
            async {
                runStartupReconciliation(
                    readiness = readiness,
                    reconcile = { throw expected },
                    reportFailure = reported::add
                )
            },
            async {
                runStartupReconciliation(
                    readiness = readiness,
                    reconcile = { otherReconciliationRan = true },
                    reportFailure = reported::add
                )
            }
        )

        assertEquals(listOf(expected), reported)
        assertTrue(otherReconciliationRan)
    }

    @Test
    fun reconciliationCancellationEscapesWithIdentityAndIsNotReported() = runBlocking {
        val expected = CancellationException("reconciliation cancelled")
        val reported = mutableListOf<Exception>()

        val actual = try {
            runStartupReconciliation(
                readiness = CompletableDeferred(true),
                reconcile = { throw expected },
                reportFailure = reported::add
            )
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertSame(expected, actual)
        assertTrue(reported.isEmpty())
    }
}
