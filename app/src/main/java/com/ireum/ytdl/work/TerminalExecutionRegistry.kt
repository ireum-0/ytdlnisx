package com.ireum.ytdl.work

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * In-process admission fence for Terminal executions.  A durable journal is
 * the authority across process death; this registry closes the smaller race
 * in which startup reconciliation and a second worker observe the same
 * Terminal subject concurrently.  The token is deliberately execution
 * scoped and is never inferred from the DAO row alone.
 */
internal object TerminalExecutionRegistry {
    internal enum class Admission {
        ACQUIRED,
        ALREADY_COMMITTED,
        TERMINAL_FAILURE,
        BLOCKED,
    }

    private val mutex = Mutex()
    private val activeTokens = mutableMapOf<Long, String>()

    suspend fun admit(
        context: Context,
        cacheRoot: File,
        subjectId: Long,
        executionToken: String,
    ): Admission = mutex.withLock {
        if (activeTokens.containsKey(subjectId)) return@withLock Admission.BLOCKED

        val recoveryDecision = TerminalPublicationRecovery.admit(
            context = context,
            cacheRoot = cacheRoot,
            subjectId = subjectId,
            activeExecution = { token -> activeTokens.values.any { it == token } },
        )
        val decision = when (recoveryDecision) {
            TerminalPublicationRecovery.Admission.ACQUIRED -> Admission.ACQUIRED
            TerminalPublicationRecovery.Admission.ALREADY_COMMITTED -> Admission.ALREADY_COMMITTED
            TerminalPublicationRecovery.Admission.TERMINAL_FAILURE -> Admission.TERMINAL_FAILURE
            TerminalPublicationRecovery.Admission.BLOCKED -> Admission.BLOCKED
        }
        if (decision == Admission.ACQUIRED) {
            activeTokens[subjectId] = executionToken
        }
        decision
    }

    suspend fun release(subjectId: Long, executionToken: String?) = mutex.withLock {
        if (executionToken != null && activeTokens[subjectId] == executionToken) {
            activeTokens.remove(subjectId)
        }
    }

    internal suspend fun isActive(executionToken: String): Boolean = mutex.withLock {
        activeTokens.values.any { it == executionToken }
    }
}
