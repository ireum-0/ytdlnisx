package com.ireum.ytdl.work

import android.content.Context

/**
 * Result of one Terminal cancellation attempt.  Dispatch supersession,
 * WorkManager request revocation, and exact execution/native convergence are
 * separate responsibilities; only the first and third authorize row deletion.
 */
internal data class TerminalCancellationResult(
    val dispatchSuperseded: Boolean,
    val workManagerCancellationAcknowledged: Boolean,
    val executionConverged: Boolean,
) {
    val rowDeletionAuthorized: Boolean
        get() = dispatchSuperseded && executionConverged
}

/**
 * Serializes the durable dispatch revocation and the already-admitted
 * execution cancellation without making either one's success a prerequisite
 * for attempting the other.
 */
internal object TerminalCancellationCoordinator {
    suspend fun cancel(
        context: Context,
        terminalId: Long,
    ): TerminalCancellationResult {
        val dispatch = WorkManagerHandoffRecovery.cancelTerminalDispatch(context, terminalId)
        // A failed WorkManager Operation must not suppress native quiescence.
        val executionConverged = runCatching {
            TerminalExecutionRegistry.cancel(context, terminalId)
        }.getOrDefault(false)
        return TerminalCancellationResult(
            dispatchSuperseded = dispatch.dispatchSuperseded,
            workManagerCancellationAcknowledged = dispatch.workManagerCancellationAcknowledged,
            executionConverged = executionConverged,
        )
    }
}
