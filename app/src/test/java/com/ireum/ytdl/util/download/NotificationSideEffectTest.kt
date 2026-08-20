package com.ireum.ytdl.util.download

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NotificationSideEffectTest {
    @Test
    fun ordinaryNotificationFailureIsConsumedAndReported() {
        var reported: Exception? = null

        val result = runNonAuthoritativeNotificationSideEffect(
            action = { throw IllegalStateException("notification failed") },
            onFailure = { reported = it },
        )

        assertNull(result)
        assertTrue(reported is IllegalStateException)
    }

    @Test
    fun cancellationRemainsPropagating() {
        val expected = CancellationException("cancel notification")

        try {
            runNonAuthoritativeNotificationSideEffect(
                action = { throw expected },
                onFailure = { fail("CancellationException must not be reported as a normal failure") },
            )
            fail("CancellationException must propagate")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }
}
