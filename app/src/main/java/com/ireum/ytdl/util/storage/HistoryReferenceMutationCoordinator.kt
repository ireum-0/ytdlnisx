package com.ireum.ytdl.util.storage

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes History reference changes with the final filesystem deletion
 * decision.  Room CAS protects database rows; this gate also covers the
 * external file operation that follows a retained-reference snapshot.
 */
object HistoryReferenceMutationCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    fun <T> withLockBlocking(block: () -> T): T = runBlocking {
        mutex.withLock { block() }
    }
}
