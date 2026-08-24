package com.ireum.ytdl.util.process

import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Turns a native process cancellation request into an explicit lifecycle
 * acknowledgement.  A successful destroy() call alone is not a termination
 * proof, so callers must retain ownership when this returns false.
 */
internal object ProcessQuiescence {
    private const val TERMINATION_WAIT_MILLIS = 4_000L

    fun requestTermination(
        process: Process,
        timeoutMillis: Long = TERMINATION_WAIT_MILLIS,
    ): Boolean {
        if (!isAlive(process)) return true

        return try {
            process.destroy()
            if (awaitTermination(process, timeoutMillis)) {
                true
            } else {
                process.destroyForcibly()
                awaitTermination(process, timeoutMillis)
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
        timeoutMillis: Long,
    ): Boolean {
        if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return !isAlive(process)
        }
        return !isAlive(process)
    }

    private fun isAlive(process: Process): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.isAlive
        } else {
            try {
                process.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }
    }
}
