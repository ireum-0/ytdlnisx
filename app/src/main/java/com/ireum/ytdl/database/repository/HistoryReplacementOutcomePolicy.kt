package com.ireum.ytdl.database.repository

enum class HistoryReplacementTerminalAction {
    COMPLETE,
    TARGET_DELETED,
    PRESERVE_FAILED,
}

enum class HistoryReplacementCleanupAction {
    AUTHORIZED_CLEANUP,
    TARGET_MISSING,
    PRESERVE_FAILED,
}

/**
 * Keeps replacement persistence outcomes separate from the worker's terminal policy.
 * A live target that fails authorization is a failed commit, not a deleted target.
 */
object HistoryReplacementOutcomePolicy {
    fun terminalAction(outcome: HistoryReplacementOutcome): HistoryReplacementTerminalAction =
        when (outcome) {
            is HistoryReplacementOutcome.Updated -> HistoryReplacementTerminalAction.COMPLETE
            HistoryReplacementOutcome.TargetMissing -> HistoryReplacementTerminalAction.TARGET_DELETED
            HistoryReplacementOutcome.SourceMismatch,
            HistoryReplacementOutcome.TypeMismatch ->
                HistoryReplacementTerminalAction.PRESERVE_FAILED
        }

    fun cleanupAction(
        authorization: HistoryReplacementAuthorization
    ): HistoryReplacementCleanupAction = when (authorization) {
        is HistoryReplacementAuthorization.Authorized ->
            HistoryReplacementCleanupAction.AUTHORIZED_CLEANUP
        HistoryReplacementAuthorization.TargetMissing ->
            HistoryReplacementCleanupAction.TARGET_MISSING
        HistoryReplacementAuthorization.SourceMismatch,
        HistoryReplacementAuthorization.TypeMismatch ->
            HistoryReplacementCleanupAction.PRESERVE_FAILED
    }

    fun allowsPartialSuccess(
        hasCreatedOutputs: Boolean,
        cleanupAction: HistoryReplacementCleanupAction?
    ): Boolean = hasCreatedOutputs && cleanupAction != HistoryReplacementCleanupAction.PRESERVE_FAILED
}
