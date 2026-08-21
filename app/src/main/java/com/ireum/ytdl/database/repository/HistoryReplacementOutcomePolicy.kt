package com.ireum.ytdl.database.repository

import com.ireum.ytdl.util.storage.HistoryDeletionSummary

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

sealed interface HistoryReplacementCleanupResult {
    val authorization: HistoryReplacementAuthorization
    val cleanupCompleted: Boolean

    data class Completed(
        override val authorization: HistoryReplacementAuthorization,
        val deletionSummary: HistoryDeletionSummary? = null,
    ) : HistoryReplacementCleanupResult {
        override val cleanupCompleted: Boolean = true
    }

    data class Incomplete(
        override val authorization: HistoryReplacementAuthorization,
        val deletionSummary: HistoryDeletionSummary,
    ) : HistoryReplacementCleanupResult {
        override val cleanupCompleted: Boolean = false
    }

    data class Failed(
        override val authorization: HistoryReplacementAuthorization,
        val error: Exception,
        val deletionSummary: HistoryDeletionSummary? = null,
    ) : HistoryReplacementCleanupResult {
        override val cleanupCompleted: Boolean = false
    }
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

    fun terminalAction(cleanupAction: HistoryReplacementCleanupAction): HistoryReplacementTerminalAction =
        when (cleanupAction) {
            HistoryReplacementCleanupAction.AUTHORIZED_CLEANUP ->
                HistoryReplacementTerminalAction.COMPLETE
            HistoryReplacementCleanupAction.TARGET_MISSING ->
                HistoryReplacementTerminalAction.TARGET_DELETED
            HistoryReplacementCleanupAction.PRESERVE_FAILED ->
                HistoryReplacementTerminalAction.PRESERVE_FAILED
        }

    fun mergeTerminalAction(
        current: HistoryReplacementTerminalAction?,
        next: HistoryReplacementTerminalAction,
    ): HistoryReplacementTerminalAction = when {
        current == HistoryReplacementTerminalAction.PRESERVE_FAILED ||
            next == HistoryReplacementTerminalAction.PRESERVE_FAILED ->
            HistoryReplacementTerminalAction.PRESERVE_FAILED
        current == HistoryReplacementTerminalAction.TARGET_DELETED ||
            next == HistoryReplacementTerminalAction.TARGET_DELETED ->
            HistoryReplacementTerminalAction.TARGET_DELETED
        else -> HistoryReplacementTerminalAction.COMPLETE
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

    fun cleanupResult(
        authorization: HistoryReplacementAuthorization,
        deletionSummary: HistoryDeletionSummary,
    ): HistoryReplacementCleanupResult = if (deletionSummary.hasFileFailures) {
        HistoryReplacementCleanupResult.Incomplete(authorization, deletionSummary)
    } else {
        HistoryReplacementCleanupResult.Completed(authorization, deletionSummary)
    }

    fun allowsPartialSuccess(
        hasCreatedOutputs: Boolean,
        cleanupAction: HistoryReplacementCleanupAction?,
        authoritativeAction: HistoryReplacementTerminalAction? =
            cleanupAction?.let { cleanup -> terminalAction(cleanup) },
        cleanupCompleted: Boolean = true,
    ): Boolean = hasCreatedOutputs &&
        cleanupCompleted &&
        (authoritativeAction == null || authoritativeAction == HistoryReplacementTerminalAction.COMPLETE) &&
        cleanupAction != HistoryReplacementCleanupAction.PRESERVE_FAILED
}
