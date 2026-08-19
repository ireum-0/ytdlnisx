package com.ireum.ytdl.util.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIssueClassifierTest {

    @Test
    fun classifiesEveryInitialCodeFromHighConfidenceInput() {
        val cases = listOf(
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.EXTRACT,
                output = "ERROR: This video is available to this channel's members"
            ) to DownloadIssueCode.MEMBERSHIP_REQUIRED,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.DOWNLOAD,
                exceptionClassName = "java.net.SocketTimeoutException",
                message = "request failed"
            ) to DownloadIssueCode.NETWORK_TIMEOUT,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.EXTRACT,
                message = "HTTP Error 401: authentication required"
            ) to DownloadIssueCode.AUTH_REQUIRED,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.EXTRACT,
                output = "ERROR: requested format is not available"
            ) to DownloadIssueCode.FORMAT_UNAVAILABLE,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.MOVE,
                message = "write failed: ENOSPC (no space left on device)"
            ) to DownloadIssueCode.STORAGE_FULL,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.PREFLIGHT,
                destinationWritable = false
            ) to DownloadIssueCode.DESTINATION_NOT_WRITABLE,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.MERGE,
                exitCode = 1,
                message = "process ended"
            ) to DownloadIssueCode.FFMPEG_FAILED,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.DOWNLOAD,
                message = "unrecognized extractor failure"
            ) to DownloadIssueCode.UNKNOWN
        )

        cases.forEach { (input, expected) ->
            assertTrue(
                "Expected $expected for $input",
                DownloadIssueClassifier.classify(input).any { it.code == expected }
            )
        }
    }

    @Test
    fun membershipAccessTakesPriorityOverGenericAuthenticationAndIsNotRetryable() {
        val issue = DownloadIssueClassifier.classify(
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.EXTRACT,
                output = "Login required. Join this channel to get access to members-only content."
            )
        ).single()

        assertEquals(DownloadIssueCode.MEMBERSHIP_REQUIRED, issue.code)
        assertFalse(issue.retryable)
        assertFalse(DownloadSuggestedAction.RETRY in issue.suggestedActions)
        assertTrue(DownloadSuggestedAction.OPEN_AUTH_SETTINGS in issue.suggestedActions)
    }

    @Test
    fun plainForbiddenResponseIsNotMisclassifiedAsMembershipOnly() {
        val issue = DownloadIssueClassifier.classify(
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.DOWNLOAD,
                output = "HTTP Error 403: Forbidden"
            )
        ).single()

        assertEquals(DownloadIssueCode.UNKNOWN, issue.code)
    }

    @Test
    fun subscriberOnlyAvailabilityIsClassifiedExplicitly() {
        val issue = DownloadIssueClassifier.classify(
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.EXTRACT,
                output = """{"availability":"subscriber_only"}"""
            )
        ).single()

        assertEquals(DownloadIssueCode.MEMBERSHIP_REQUIRED, issue.code)
    }

    @Test
    fun membershipWordsInDestinationFilenameDoNotTriggerAccessWaiting() {
        val issue = DownloadIssueClassifier.classify(
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.DOWNLOAD,
                output = """
                    [download] Destination: Members Only.webm
                    ERROR: unable to download video data: HTTP Error 403: Forbidden
                """.trimIndent()
            )
        ).single()

        assertEquals(DownloadIssueCode.UNKNOWN, issue.code)
    }

    @Test
    fun ambiguous403_isNotConfirmedAsAuthenticationFailure() {
        val issues = DownloadIssueClassifier.classify(
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.DOWNLOAD,
                message = "HTTP Error 403: Forbidden"
            )
        )

        assertEquals(listOf(DownloadIssueCode.UNKNOWN), issues.map { it.code })
    }

    @Test
    fun unknownFailureOffersReconfigurationWithoutSameSettingsRetry() {
        val issue = DownloadIssueClassifier.classify(
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.DOWNLOAD,
                message = "unrecognized extractor failure"
            )
        ).single()

        assertFalse(issue.retryable)
        assertTrue(DownloadSuggestedAction.RECONFIGURE in issue.suggestedActions)
        assertFalse(DownloadSuggestedAction.RETRY in issue.suggestedActions)
    }

    @Test
    fun storageFullCanRetryAfterSpaceIsFreed() {
        val issue = DownloadIssueClassifier.classify(
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.MOVE,
                message = "write failed: no space left on device"
            )
        ).single()

        assertTrue(issue.retryable)
        assertTrue(DownloadSuggestedAction.RETRY in issue.suggestedActions)
        assertTrue(DownloadSuggestedAction.OPEN_STORAGE_SETTINGS in issue.suggestedActions)
    }

    @Test
    fun returnsMultipleIndependentHighConfidenceCauses() {
        val issues = DownloadIssueClassifier.classify(
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.MOVE,
                message = "Permission denied; no space left on device"
            )
        )

        assertEquals(
            setOf(DownloadIssueCode.STORAGE_FULL, DownloadIssueCode.DESTINATION_NOT_WRITABLE),
            issues.map { it.code }.toSet()
        )
    }

    @Test
    fun negativePhrasesDoNotTriggerSpecificClassifiers() {
        val cases = listOf(
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.HISTORY,
                message = "connection timed out while writing an old audit entry"
            ) to DownloadIssueCode.NETWORK_TIMEOUT,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.DOWNLOAD,
                message = "HTTP Error 403: Forbidden"
            ) to DownloadIssueCode.AUTH_REQUIRED,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.EXTRACT,
                message = "requested format 137 is available"
            ) to DownloadIssueCode.FORMAT_UNAVAILABLE,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.MOVE,
                message = "storage space available"
            ) to DownloadIssueCode.STORAGE_FULL,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.MOVE,
                message = "destination is writable",
                destinationWritable = true
            ) to DownloadIssueCode.DESTINATION_NOT_WRITABLE,
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.MERGE,
                message = "ffmpeg completed",
                exitCode = 0
            ) to DownloadIssueCode.FFMPEG_FAILED
        )

        cases.forEach { (input, excludedCode) ->
            val issues = DownloadIssueClassifier.classify(input)
            assertFalse(input.toString(), issues.any { it.code == excludedCode })
        }
    }

    @Test
    fun classifierRedactsDetails() {
        val issue = DownloadIssueClassifier.classify(
            DownloadIssueClassifier.Input(
                stage = DownloadIssueStage.DOWNLOAD,
                message = "read timed out token=private-value"
            )
        ).single()

        assertFalse(issue.redactedDetails.contains("private-value"))
        assertTrue(issue.retryable)
    }
}
