package com.ireum.ytdl.util.storage

import com.ireum.ytdl.App
import com.ireum.ytdl.database.RestoreMutationAdmission

/**
 * Serializes History reference changes with the final filesystem deletion
 * decision.  Room CAS protects database rows; this gate also covers the
 * external file operation that follows a retained-reference snapshot.
 */
object HistoryReferenceMutationCoordinator {
    suspend fun <T> withLock(block: suspend () -> T): T =
        RestoreMutationAdmission.withOrdinaryMutation(App.instance, block)

    fun <T> withLockBlocking(block: () -> T): T =
        RestoreMutationAdmission.withOrdinaryMutationBlocking(App.instance, block)

    /**
     * Restore owns the same relationship lock while its outer Room
     * transaction is active.  This bypass is deliberately narrow: only the
     * restore coordinator may use it, while ordinary History/UI writers fail
     * closed at the shared admission boundary above.
     */
    suspend fun <T> withRestoreLock(block: suspend () -> T): T =
        RestoreMutationAdmission.withRestoreMutation(block)
}
