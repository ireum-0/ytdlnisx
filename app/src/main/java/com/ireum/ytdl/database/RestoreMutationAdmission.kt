package com.ireum.ytdl.database

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The process-local admission boundary for durable mutations which may race
 * with the file-backed Restore transaction. The boundary is deliberately
 * re-entrant for nested repository calls, but it never spans network/native
 * execution or other long-running work.
 */
internal object RestoreMutationAdmission {
    private class Held : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private val mutex = Mutex()

    @Volatile
    internal var ordinaryAuthorityAcquiredForTesting: (() -> Unit)? = null

    @Volatile
    internal var restorePublicationAuthorityAcquiredForTesting: (() -> Unit)? = null

    private suspend fun <T> withHeld(block: suspend () -> T): T {
        if (coroutineContext[Held] != null) return block()
        return mutex.withLock {
            withContext(Held()) {
                block()
            }
        }
    }

    suspend fun <T> withOrdinaryMutation(
        context: Context,
        block: suspend () -> T,
    ): T = withHeld {
        ordinaryAuthorityAcquiredForTesting?.invoke()
        check(RestoreGate.admission(context.applicationContext) == RestoreAdmission.ALLOWED) {
            "Restore transaction is active"
        }
        block()
    }

    fun <T> withOrdinaryMutationBlocking(
        context: Context,
        block: () -> T,
    ): T = runBlocking {
        withOrdinaryMutation(context) { block() }
    }

    /**
     * Preserves existing cancellation/no-op semantics while making the
     * actual carrier deletion participate in the ordinary admission boundary.
     */
    fun tryOrdinaryMutationBlocking(
        context: Context,
        block: () -> Unit,
    ): Boolean = try {
        withOrdinaryMutationBlocking(context) {
            block()
        }
        true
    } catch (error: IllegalStateException) {
        if (error.message == "Restore transaction is active") false else throw error
    }

    suspend fun <T> withRestorePublication(block: suspend () -> T): T = withHeld {
        restorePublicationAuthorityAcquiredForTesting?.invoke()
        block()
    }

    suspend fun <T> withRestoreMutation(block: suspend () -> T): T = withHeld(block)

    fun applyOrdinaryPreferences(context: Context, editor: SharedPreferences.Editor) {
        withOrdinaryMutationBlocking(context) {
            check(editor.commit()) {
                "Ordinary preference persistence was not durable"
            }
        }
    }
}