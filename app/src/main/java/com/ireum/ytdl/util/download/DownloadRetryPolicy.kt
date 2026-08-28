package com.ireum.ytdl.util.download

enum class DownloadRetryStrategy {
    ORIGINAL,
    SAME_SETTINGS,
    RECONFIGURED
}

enum class DownloadRetryItemState {
    ERROR,
    CANCELED,
    OTHER
}

enum class DownloadRetryBlockReason {
    NOT_FAILED,
    CANCELED,
    VALID_OUTPUT_EXISTS,
    NOT_RETRYABLE,
    ATTEMPT_LIMIT,
    SETTINGS_CONFIRMATION_REQUIRED,
    HISTORY_REPLACEMENT_MISMATCH,
    NATIVE_RECOVERY_PENDING,
}

data class DownloadRetryMetadata(
    val operationId: String,
    val attempt: Int,
    val strategy: DownloadRetryStrategy
)

sealed interface DownloadRetryDecision {
    data class Allowed(val metadata: DownloadRetryMetadata) : DownloadRetryDecision
    data class Blocked(val reason: DownloadRetryBlockReason) : DownloadRetryDecision
}

object DownloadRetryPolicy {
    const val MAX_TOTAL_RETRY_ATTEMPTS = 3
    const val MAX_SAME_SETTINGS_ATTEMPTS = 2

    fun canOffer(
        current: DownloadRetryMetadata,
        requestedStrategy: DownloadRetryStrategy,
        issueRetryable: Boolean = true
    ): Boolean {
        if (current.attempt >= MAX_TOTAL_RETRY_ATTEMPTS) return false
        return when (requestedStrategy) {
            DownloadRetryStrategy.SAME_SETTINGS -> issueRetryable && !(
                current.strategy == DownloadRetryStrategy.SAME_SETTINGS &&
                    current.attempt >= MAX_SAME_SETTINGS_ATTEMPTS
                )
            DownloadRetryStrategy.RECONFIGURED ->
                current.strategy != DownloadRetryStrategy.RECONFIGURED
            DownloadRetryStrategy.ORIGINAL -> false
        }
    }

    fun prepare(
        current: DownloadRetryMetadata,
        itemState: DownloadRetryItemState,
        requestedStrategy: DownloadRetryStrategy,
        issueRetryable: Boolean,
        hasValidOutput: Boolean,
        settingsConfirmed: Boolean,
        operationIdFactory: () -> String,
        historyReplacementMismatch: Boolean = false,
    ): DownloadRetryDecision {
        if (itemState == DownloadRetryItemState.CANCELED) {
            return DownloadRetryDecision.Blocked(DownloadRetryBlockReason.CANCELED)
        }
        if (itemState != DownloadRetryItemState.ERROR) {
            return DownloadRetryDecision.Blocked(DownloadRetryBlockReason.NOT_FAILED)
        }
        if (historyReplacementMismatch) {
            return DownloadRetryDecision.Blocked(
                DownloadRetryBlockReason.HISTORY_REPLACEMENT_MISMATCH
            )
        }
        if (hasValidOutput) {
            return DownloadRetryDecision.Blocked(DownloadRetryBlockReason.VALID_OUTPUT_EXISTS)
        }
        if (current.attempt >= MAX_TOTAL_RETRY_ATTEMPTS) {
            return DownloadRetryDecision.Blocked(DownloadRetryBlockReason.ATTEMPT_LIMIT)
        }
        if (requestedStrategy == DownloadRetryStrategy.SAME_SETTINGS) {
            if (!issueRetryable) {
                return DownloadRetryDecision.Blocked(DownloadRetryBlockReason.NOT_RETRYABLE)
            }
            if (!canOffer(current, requestedStrategy, issueRetryable)) {
                return DownloadRetryDecision.Blocked(DownloadRetryBlockReason.ATTEMPT_LIMIT)
            }
        }
        if (requestedStrategy == DownloadRetryStrategy.RECONFIGURED && !settingsConfirmed) {
            return DownloadRetryDecision.Blocked(
                DownloadRetryBlockReason.SETTINGS_CONFIRMATION_REQUIRED
            )
        }
        if (
            requestedStrategy == DownloadRetryStrategy.RECONFIGURED &&
            !canOffer(current, requestedStrategy)
        ) {
            return DownloadRetryDecision.Blocked(DownloadRetryBlockReason.ATTEMPT_LIMIT)
        }

        return DownloadRetryDecision.Allowed(
            DownloadRetryMetadata(
                operationId = current.operationId.ifBlank(operationIdFactory),
                attempt = current.attempt + 1,
                strategy = requestedStrategy
            )
        )
    }
}
