package com.ireum.ytdl.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class YtdlpRequestRegistryTest {
    @Test
    fun parsingFailureRetainsBuiltRequestForCleanup() = runBlocking {
        assertPreparationFailureRetainsRequest(PreparationStep.COMMAND_PARSING)
    }

    @Test
    fun diagnosticFailureRetainsBuiltRequestForCleanup() = runBlocking {
        assertPreparationFailureRetainsRequest(PreparationStep.DIAGNOSTICS)
    }

    @Test
    fun initialLogFailureRetainsBuiltRequestForCleanup() = runBlocking {
        assertPreparationFailureRetainsRequest(PreparationStep.LOG_INSERT)
    }

    @Test
    fun daoFailureRetainsBuiltRequestForCleanup() = runBlocking {
        assertPreparationFailureRetainsRequest(PreparationStep.DAO_UPDATE)
    }

    @Test
    fun cancellationDuringPreparationRetainsRequestAndPreservesIdentity() = runBlocking {
        val cleanedRequests = mutableListOf<String>()
        val expected = CancellationException("cancelled during log insertion")
        val requestOwner = YtdlpPreparationRequestOwner(cleanedRequests::add)

        try {
            requestOwner.register("initial-request")
            try {
                yield()
                throw expected
            } catch (error: Throwable) {
                cleanupYtdlpPreparationAndRethrow(requestOwner, error)
            }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }

        assertEquals(listOf("initial-request"), cleanedRequests)
    }

    @Test
    fun successfulPreparationTransfersOwnershipWithoutCleaningRequest() = runBlocking {
        val cleanedRequests = mutableListOf<String>()
        val requestOwner = YtdlpPreparationRequestOwner(cleanedRequests::add)

        requestOwner.register("initial-request")
        val prepared = "prepared-${requestOwner.snapshot().single()}"

        assertEquals("prepared-initial-request", prepared)
        assertEquals(emptyList<String>(), cleanedRequests)
    }

    private suspend fun assertPreparationFailureRetainsRequest(failingStep: PreparationStep) {
        val cleanedRequests = mutableListOf<String>()
        val expected = IllegalStateException("failure at $failingStep")
        val requestOwner = YtdlpPreparationRequestOwner(cleanedRequests::add)

        try {
            requestOwner.register("initial-request")
            try {
                PreparationStep.entries.forEach { step ->
                    if (step == failingStep) throw expected
                }
            } catch (error: Throwable) {
                cleanupYtdlpPreparationAndRethrow(requestOwner, error)
            }
            fail("Expected preparation failure")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }

        assertEquals(listOf("initial-request"), cleanedRequests)
    }

    private enum class PreparationStep {
        COMMAND_PARSING,
        DIAGNOSTICS,
        LOG_INSERT,
        DAO_UPDATE,
    }
}
