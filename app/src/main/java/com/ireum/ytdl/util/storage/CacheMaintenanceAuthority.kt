package com.ireum.ytdl.util.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The process-local ordering authority shared by cache maintenance and the
 * short window in which an execution publishes its live owner.  The mutex is
 * deliberately only a coordination window: durable markers and the exact
 * process-local owner registries remain the identity authority after the
 * window is released.
 *
 * Lock order is one-way:
 *
 *   cache-maintenance window -> Download/Terminal admission coordination
 *
 * Maintenance never acquires either execution mutex, and the admission
 * paths do not re-enter this mutex after they release it.  This avoids an
 * AB/BA cycle with the existing per-download and Terminal locks.
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
