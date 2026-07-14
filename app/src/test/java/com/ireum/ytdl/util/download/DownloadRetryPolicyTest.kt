package com.ireum.ytdl.util.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRetryPolicyTest {
    @Test
    fun exhaustedAttemptsAreNotOffered() {
        val metadata = DownloadRetryMetadata(
            operationId = "operation",
            attempt = DownloadRetryPolicy.MAX_TOTAL_RETRY_ATTEMPTS,
            strategy = DownloadRetryStrategy.SAME_SETTINGS
        )

        assertFalse(
            DownloadRetryPolicy.canOffer(
                metadata,
                DownloadRetryStrategy.SAME_SETTINGS,
                issueRetryable = true
            )
        )
        assertFalse(
            DownloadRetryPolicy.canOffer(
                metadata,
                DownloadRetryStrategy.RECONFIGURED
            )
        )
    }

    @Test
    fun sameSettingsRetry_preservesOperationAndIncrementsAttempt() {
        val decision = DownloadRetryPolicy.prepare(
            current = DownloadRetryMetadata("operation-1", 0, DownloadRetryStrategy.ORIGINAL),
            itemState = DownloadRetryItemState.ERROR,
            requestedStrategy = DownloadRetryStrategy.SAME_SETTINGS,
            issueRetryable = true,
            hasValidOutput = false,
            settingsConfirmed = false,
            operationIdFactory = { "unused" }
        )

        val metadata = (decision as DownloadRetryDecision.Allowed).metadata
        assertEquals("operation-1", metadata.operationId)
        assertEquals(1, metadata.attempt)
        assertEquals(DownloadRetryStrategy.SAME_SETTINGS, metadata.strategy)
    }

    @Test
    fun repeatedSameSettingsStrategy_isBlockedAtLimit() {
        val decision = DownloadRetryPolicy.prepare(
            current = DownloadRetryMetadata(
                "operation-1",
                DownloadRetryPolicy.MAX_SAME_SETTINGS_ATTEMPTS,
                DownloadRetryStrategy.SAME_SETTINGS
            ),
            itemState = DownloadRetryItemState.ERROR,
            requestedStrategy = DownloadRetryStrategy.SAME_SETTINGS,
            issueRetryable = true,
            hasValidOutput = false,
            settingsConfirmed = false,
            operationIdFactory = { "unused" }
        )

        assertEquals(
            DownloadRetryBlockReason.ATTEMPT_LIMIT,
            (decision as DownloadRetryDecision.Blocked).reason
        )
    }

    @Test
    fun reconfiguredRetry_requiresExplicitConfirmation() {
        val decision = DownloadRetryPolicy.prepare(
            current = DownloadRetryMetadata("", 0, DownloadRetryStrategy.ORIGINAL),
            itemState = DownloadRetryItemState.ERROR,
            requestedStrategy = DownloadRetryStrategy.RECONFIGURED,
            issueRetryable = false,
            hasValidOutput = false,
            settingsConfirmed = false,
            operationIdFactory = { "operation-2" }
        )

        assertEquals(
            DownloadRetryBlockReason.SETTINGS_CONFIRMATION_REQUIRED,
            (decision as DownloadRetryDecision.Blocked).reason
        )
    }

    @Test
    fun canceledOrCompletedOutput_isNeverRetried() {
        val canceled = DownloadRetryPolicy.prepare(
            current = DownloadRetryMetadata("operation", 0, DownloadRetryStrategy.ORIGINAL),
            itemState = DownloadRetryItemState.CANCELED,
            requestedStrategy = DownloadRetryStrategy.SAME_SETTINGS,
            issueRetryable = true,
            hasValidOutput = false,
            settingsConfirmed = false,
            operationIdFactory = { "unused" }
        )
        val completed = DownloadRetryPolicy.prepare(
            current = DownloadRetryMetadata("operation", 0, DownloadRetryStrategy.ORIGINAL),
            itemState = DownloadRetryItemState.ERROR,
            requestedStrategy = DownloadRetryStrategy.SAME_SETTINGS,
            issueRetryable = true,
            hasValidOutput = true,
            settingsConfirmed = false,
            operationIdFactory = { "unused" }
        )

        assertTrue(canceled is DownloadRetryDecision.Blocked)
        assertEquals(
            DownloadRetryBlockReason.VALID_OUTPUT_EXISTS,
            (completed as DownloadRetryDecision.Blocked).reason
        )
    }
}
