package com.ireum.ytdl.util.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheMaintenanceAuthorityTest {
    @Test
    fun executionAdmissionWaitsForMaintenanceWindow() = runBlocking {
        val enteredMaintenance = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val maintenance = launch {
            CacheMaintenanceAuthority.withMaintenanceWindow {
                enteredMaintenance.complete(Unit)
                releaseMaintenance.await()
            }
        }
        enteredMaintenance.await()

        var executionEntered = false
        val execution = launch {
            CacheMaintenanceAuthority.withExecutionAdmission {
                executionEntered = true
            }
        }
        yield()
        assertFalse(executionEntered)

        releaseMaintenance.complete(Unit)
        maintenance.join()
        execution.join()
        assertTrue(executionEntered)
    }
}
