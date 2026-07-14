package com.ireum.ytdl.util.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RuntimeProbePolicyTest {
    @Test
    fun executableStateDistinguishesMissingAndNonExecutableFiles() {
        assertEquals(
            RuntimeExecutableState.MISSING,
            RuntimeProbePolicy.executableState(exists = false, isFile = false, canExecute = false)
        )
        assertEquals(
            RuntimeExecutableState.NOT_A_FILE,
            RuntimeProbePolicy.executableState(exists = true, isFile = false, canExecute = true)
        )
        assertEquals(
            RuntimeExecutableState.NOT_EXECUTABLE,
            RuntimeProbePolicy.executableState(exists = true, isFile = true, canExecute = false)
        )
        assertEquals(
            RuntimeExecutableState.READY,
            RuntimeProbePolicy.executableState(exists = true, isFile = true, canExecute = true)
        )
    }

    @Test
    fun timedProbeReturnsCompletedValue() = runBlocking {
        val result = RuntimeProbePolicy.runWithTimeout(1_000L) { "ready" }

        assertEquals(TimedProbeResult.Value("ready"), result)
    }

    @Test
    fun hungProbeTimesOut() = runBlocking {
        val result = RuntimeProbePolicy.runWithTimeout(20L) {
            delay(1_000L)
            "late"
        }

        assertSame(TimedProbeResult.TimedOut, result)
    }
}
