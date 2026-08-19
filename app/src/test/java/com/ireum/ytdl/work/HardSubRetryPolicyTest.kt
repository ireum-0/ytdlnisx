package com.ireum.ytdl.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardSubRetryPolicyTest {
    @Test
    fun transientForegroundFailuresRetryBelowTheLimit() {
        assertEquals(
            HardSubForegroundAttemptOutcome.RETRY,
            hardSubForegroundAttemptOutcome(runAttemptCount = 0, foregroundStarted = false)
        )
        assertEquals(
            HardSubForegroundAttemptOutcome.RETRY,
            hardSubForegroundAttemptOutcome(runAttemptCount = 1, foregroundStarted = false)
        )
    }

    @Test
    fun exhaustedForegroundFailureIsTerminal() {
        assertEquals(
            HardSubForegroundAttemptOutcome.FAILURE,
            hardSubForegroundAttemptOutcome(runAttemptCount = 2, foregroundStarted = false)
        )
    }

    @Test
    fun foregroundSuccessProceedsBeforeExhaustion() {
        assertEquals(
            HardSubForegroundAttemptOutcome.PROCEED,
            hardSubForegroundAttemptOutcome(runAttemptCount = 1, foregroundStarted = true)
        )
    }

    @Test
    fun lookupRetryBoundaryUsesTheSameThreeAttemptLimit() {
        assertTrue(hardSubAttemptsRemain(runAttemptCount = 0))
        assertTrue(hardSubAttemptsRemain(runAttemptCount = 1))
        assertFalse(hardSubAttemptsRemain(runAttemptCount = 2))
    }
}
