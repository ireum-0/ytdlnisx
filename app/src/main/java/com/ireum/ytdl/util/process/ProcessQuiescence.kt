package com.ireum.ytdl.util.process

import android.os.Build
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Turns a native process cancellation request into an explicit lifecycle
 * acknowledgement.  A successful destroy() call alone is not a termination
 * proof, so callers must retain ownership when this returns false.
 */
internal object ProcessQuiescence {
    private const val TERMINATION_WAIT_MILLIS = 4_000L
    private const val POLL_NANOS = 50_000_000L

    fun requestTermination(
        process: Process,
        timeoutMillis: Long = TERMINATION_WAIT_MILLIS,
        terminationProof: () -> Boolean = { true },
    ): Boolean {
        return requestTerminationForSdk(
            process = process,
            sdkInt = Build.VERSION.SDK_INT,
            timeoutMillis = timeoutMillis,
            terminationProof = terminationProof,
        )
    }

    /**
     * The Android API level is an explicit seam because Process.waitFor with a
     * timeout and destroyForcibly were added in API 26.  Keeping the legacy
     * strategy separate also prevents an API-24/25 call site from reaching
     * either method through an unguarded helper.
     */
    @Suppress("NewApi")
    internal fun requestTerminationForSdk(
        process: Process,
        sdkInt: Int,
        timeoutMillis: Long,
        terminationProof: () -> Boolean = { true },
    ): Boolean {
        if (!isAlive(process, sdkInt)) return terminationProof()

        return try {
            process.destroy()
            if (awaitTermination(process, sdkInt, timeoutMillis, terminationProof)) {
                true
            } else if (sdkInt >= Build.VERSION_CODES.O) {
                process.destroyForcibly()
                awaitTermination(process, sdkInt, timeoutMillis, terminationProof)
            } else {
                // API 24/25 have no bounded waitFor or destroyForcibly.  A
                // timed exitValue poll is the compatible acknowledgement; a
                // timeout remains fail-closed and leaves the caller's exact
                // native registry entry intact.
                false
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun awaitTermination(
        process: Process,
        sdkInt: Int,
        timeoutMillis: Long,
        terminationProof: () -> Boolean,
    ): Boolean {
        return if (sdkInt >= Build.VERSION_CODES.O) {
            awaitTerminationApi26(process, timeoutMillis, terminationProof)
        } else {
            awaitTerminationPre26(process, timeoutMillis, terminationProof)
        }
    }

    @Suppress("NewApi")
    private fun awaitTerminationApi26(
        process: Process,
        timeoutMillis: Long,
        terminationProof: () -> Boolean,
    ): Boolean {
        if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return !process.isAlive && terminationProof()
        }
        return !process.isAlive && terminationProof()
    }

    private fun awaitTerminationPre26(
        process: Process,
        timeoutMillis: Long,
        terminationProof: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0L) * 1_000_000L
        while (true) {
            if (!isAlivePre26(process)) return terminationProof()
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) return false
            val sleepNanos = min(remainingNanos, POLL_NANOS)
            try {
                Thread.sleep(sleepNanos / 1_000_000L, (sleepNanos % 1_000_000L).toInt())
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    @Suppress("NewApi")
    private fun isAlive(process: Process, sdkInt: Int): Boolean {
        return if (sdkInt >= Build.VERSION_CODES.O) {
            process.isAlive
        } else {
            isAlivePre26(process)
        }
    }

    private fun isAlivePre26(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}
