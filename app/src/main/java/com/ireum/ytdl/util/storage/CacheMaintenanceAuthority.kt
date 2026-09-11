package com.ireum.ytdl.util.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes cache maintenance with the short admission window in which a
 * Download/Terminal execution publishes its live owner. The lock is not a
 * substitute for owner markers or execution registries: maintenance still
 * skips every exact live-owned root while this window is held.
 */
internal object CacheMaintenanceAuthority {
    private val mutex = Mutex()

    suspend inline fun <T> withExecutionAdmission(
        crossinline block: suspend () -> T,
    ): T = mutex.withLock { block() }

    suspend inline fun <T> withMaintenanceWindow(
        crossinline block: suspend () -> T,
    ): T = mutex.withLock { block() }
}
