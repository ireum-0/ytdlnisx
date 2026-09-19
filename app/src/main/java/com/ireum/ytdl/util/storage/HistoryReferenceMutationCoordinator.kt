package com.ireum.ytdl.util.storage

import com.ireum.ytdl.App
import com.ireum.ytdl.database.RestoreGate
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

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock {
        check(!restoreIsActive()) { "Restore transaction is active" }
        block()
    }

    fun <T> withLockBlocking(block: () -> T): T = runBlocking {
        mutex.withLock {
            check(!restoreIsActive()) { "Restore transaction is active" }
            block()
        }
    }

    /**
     * Restore owns the same relationship lock while its outer Room
     * transaction is active.  This bypass is deliberately narrow: only the
     * restore coordinator may use it, while ordinary History/UI writers fail
     * closed at the shared admission boundary above.
     */
    suspend fun <T> withRestoreLock(block: suspend () -> T): T = mutex.withLock { block() }

    private fun restoreIsActive(): Boolean = runCatching {
        RestoreGate.isRestoreInProgress(App.instance)
    }.getOrDefault(false)
}
