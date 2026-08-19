package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeVideoQualityIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoQualityPolicyTest {
    @Test
    fun parsesGenericNotesAndDimensionsWithoutTreatingFormatIdsAsHeights() {
        assertEquals(1080, VideoQualityPolicy.parseHeight("1080p_ytdlnisxgeneric"))
        assertEquals(1080, VideoQualityPolicy.parseHeight("1080p60"))
        assertEquals(1080, VideoQualityPolicy.parseHeight("1920x1080"))
        assertEquals(1080, VideoQualityPolicy.parseHeight("1080x1920"))
        assertEquals(null, VideoQualityPolicy.parseHeight("299"))
    }

    @Test
    fun sourceMaximumIgnoresAudioOnlyFormats() {
        val formats = listOf(
            Format(format_id = "251", vcodec = "none", format_note = "audio only"),
            Format(format_id = "18", vcodec = "avc1", format_note = "360p"),
            Format(format_id = "299", vcodec = "avc1", format_note = "1080p60")
        )

        assertEquals(1080, VideoQualityPolicy.maxSourceHeight(formats))
    }

    @Test
    fun lowerActualResolutionIsCandidate() {
        val result = assess(actualHeight = 360, requestedHeight = 1080, sourceMaxHeight = 2160)

        assertEquals(
            LowQualityAssessment.Candidate(
                LowQualityCandidateReason.BELOW_EXPECTED,
                actualHeight = 360,
                expectedHeight = 1080,
                sourceMaxHeight = 2160
            ),
            result
        )
    }

    @Test
    fun equalRequestedResolutionAndIntentionalLowResolutionAreNotCandidates() {
        assertEquals(
            LowQualityAssessment.Skipped(LowQualitySkipReason.MEETS_EXPECTED_QUALITY),
            assess(actualHeight = 1080, requestedHeight = 1080, sourceMaxHeight = 2160)
        )
        assertEquals(
            LowQualityAssessment.Skipped(LowQualitySkipReason.MEETS_EXPECTED_QUALITY),
            assess(actualHeight = 360, requestedHeight = 360, sourceMaxHeight = 2160)
        )
    }

    @Test
    fun sourceMaximumBelowRequestDefinesTheExpectedResult() {
        assertEquals(
            LowQualityAssessment.Skipped(LowQualitySkipReason.MEETS_EXPECTED_QUALITY),
            assess(actualHeight = 720, requestedHeight = 1080, sourceMaxHeight = 720)
        )
        assertEquals(
            LowQualityAssessment.Candidate(
                LowQualityCandidateReason.BELOW_EXPECTED,
                actualHeight = 360,
                expectedHeight = 720,
                sourceMaxHeight = 720
            ),
            assess(actualHeight = 360, requestedHeight = 1080, sourceMaxHeight = 720)
        )
    }

    @Test
    fun missingCorruptAndNoVideoFilesAreCandidatesWhenSourceQualityIsKnown() {
        assertCandidateReason(VideoFileQualityState.MISSING, LowQualityCandidateReason.FILE_MISSING)
        assertCandidateReason(VideoFileQualityState.CORRUPT, LowQualityCandidateReason.FILE_CORRUPT)
        assertCandidateReason(VideoFileQualityState.NO_VIDEO, LowQualityCandidateReason.NO_VIDEO_STREAM)
    }

    @Test
    fun audioLocalInvalidActiveIncognitoAndHardSubItemsAreSkipped() {
        val base = input()
        assertSkipped(LowQualitySkipReason.NOT_VIDEO, base.copy(isVideo = false))
        assertSkipped(
            LowQualitySkipReason.LOCAL_OR_UNSUPPORTED_SOURCE,
            base.copy(extractorSourceEligible = false)
        )
        assertSkipped(LowQualitySkipReason.ACTIVE_DUPLICATE, base.copy(activeDuplicate = true))
        assertSkipped(LowQualitySkipReason.INCOGNITO_ENABLED, base.copy(incognitoEnabled = true))
        assertSkipped(
            LowQualitySkipReason.HARD_SUB_REPLACEMENT_UNSUPPORTED,
            base.copy(hardSubDone = true)
        )
    }

    @Test
    fun unknownSourceQualityDoesNotProduceFalsePositive() {
        assertSkipped(LowQualitySkipReason.SOURCE_QUALITY_UNKNOWN, input().copy(sourceMaxHeight = null))
    }

    @Test
    fun preliminaryScanRequiresANumericRequestAndDefersSourceLimitedDecisions() {
        val low = VideoMediaQuality(VideoFileQualityState.READY, width = 640, height = 360)
        val equal = VideoMediaQuality(VideoFileQualityState.READY, width = 1920, height = 1080)

        assertTrue(VideoQualityPolicy.isPreliminaryCandidate(1080, low))
        assertTrue(
            VideoQualityPolicy.isPreliminaryCandidate(
                1080,
                VideoMediaQuality(VideoFileQualityState.MISSING)
            )
        )
        assertFalse(VideoQualityPolicy.isPreliminaryCandidate(1080, equal))
        assertFalse(VideoQualityPolicy.isPreliminaryCandidate(null, low))
    }

    @Test
    fun currentIncognitoSnapshotControlsReplacementPersistenceBoundary() {
        assertNull(VideoQualityPolicy.replacementPersistenceSkipReason(false))
        assertEquals(
            LowQualitySkipReason.INCOGNITO_ENABLED,
            VideoQualityPolicy.replacementPersistenceSkipReason(true)
        )
    }

    @Test
    fun compatibilityLimitNeverExceedsUserRequest() {
        assertEquals(1080, VideoQualityPolicy.compatibleMaximumHeight(1080, 2160))
        assertEquals(720, VideoQualityPolicy.compatibleMaximumHeight(1080, 720))
        assertEquals(2160, VideoQualityPolicy.compatibleMaximumHeight(null, 2160))
    }

    @Test
    fun effectiveTargetUsesPerItemSourceMaximumWithoutUpgradingIntentionalLowQuality() {
        assertEquals(720, VideoQualityPolicy.expectedHeight(1080, 720))
        assertEquals(360, VideoQualityPolicy.expectedHeight(360, 2160))
        assertEquals(1080, VideoQualityPolicy.expectedHeight(1080, null))
        assertEquals(2160, VideoQualityPolicy.expectedHeight(null, 2160))
    }

    @Test
    fun qualityIntentAndEffectiveTargetRespectSourceAndCompatibilityCaps() {
        val numeric = VideoQualityPolicy.qualityIntent(
            Format(format_id = "1080p_ytdlnisxgeneric"),
            listOf(Format(format_id = "22", vcodec = "avc1", format_note = "720p"))
        )
        assertEquals(YoutubeVideoQualityIntent.NumericHeight(1080), numeric)
        assertEquals(
            720,
            VideoQualityPolicy.effectiveTargetHeight(
                intent = numeric,
                knownSourceMaximum = 720,
                compatibilityMaximum = 1080,
            )
        )
        assertEquals(
            720,
            VideoQualityPolicy.effectiveTargetHeight(
                YoutubeVideoQualityIntent.NumericHeight(1080),
                compatibilityMaximum = 720,
            )
        )
        assertEquals(
            1080,
            VideoQualityPolicy.effectiveTargetHeight(YoutubeVideoQualityIntent.Best(1080))
        )
        assertEquals(
            YoutubeVideoQualityIntent.SpecificHeight(1080),
            VideoQualityPolicy.qualityIntent(
                Format(format_id = "299"),
                listOf(Format(format_id = "299", vcodec = "avc1", format_note = "1080p60")),
            )
        )
        assertEquals(
            null,
            VideoQualityPolicy.effectiveTargetHeight(YoutubeVideoQualityIntent.Best(null))
        )
        assertEquals(
            null,
            VideoQualityPolicy.effectiveTargetHeight(YoutubeVideoQualityIntent.IntentionallyLow(360))
        )
    }

    @Test
    fun guardedGenericSelectorCannotFallBackToFormat18At360p() {
        val guarded = VideoQualityPolicy.guardedFormatExpression(
            expression = "bv+ba/bv/b",
            minimumHeight = 1080,
            preserveLeadingSpecificSelectors = false,
        )

        assertEquals(
            "bv[height>=1080][height<=1080]+ba/bv[width>=1080][width<=1080]+ba/" +
                "bv[height>=1080][height<=1080]/bv[width>=1080][width<=1080]/" +
                "b[height>=1080][height<=1080]/b[width>=1080][width<=1080]",
            guarded
        )
        assertFalse(guarded.split('/').any { it == "b" || it == "bv" })
        assertTrue(
            (VideoQualityPolicy.requestedHeight(
                Format(format_id = "299", vcodec = "avc1", format_note = "1080p60")
            ) ?: 0) >= 1080
        )
    }

    @Test
    fun specificIdRemainsFirstAndOnlyFallbackBranchesReceiveTheGuard() {
        assertEquals(
            "299+251/299/bv[height>=1080][height<=1080]+ba/" +
                "bv[width>=1080][width<=1080]+ba/" +
                "b[height>=1080][height<=1080]/b[width>=1080][width<=1080]",
            VideoQualityPolicy.guardedFormatExpression(
                expression = "299+251/299/bv+ba/b",
                minimumHeight = 1080,
                preserveLeadingSpecificSelectors = true,
            )
        )
    }

    @Test
    fun inaccessibleMediaIsSkippedInsteadOfTreatedAsCorrupt() {
        assertSkipped(
            LowQualitySkipReason.FILE_INACCESSIBLE,
            input().copy(media = VideoMediaQuality(VideoFileQualityState.INACCESSIBLE))
        )
    }

    @Test
    fun unknownWorstAndCustomSelectionsAreNotSilentlyGuarded() {
        val expression = "bv+ba/b"
        assertEquals(expression, VideoQualityPolicy.guardedFormatExpression(expression, null, false))
        assertEquals(
            null,
            VideoQualityPolicy.effectiveTargetHeight(YoutubeVideoQualityIntent.Unknown)
        )
        assertEquals(
            null,
            VideoQualityPolicy.effectiveTargetHeight(YoutubeVideoQualityIntent.IntentionallyLow())
        )
    }

    @Test
    fun videosWithAQualityTargetAreStagedEvenWhenDirectDownloadsAreEnabled() {
        val selected = Format(format_id = "1080p_ytdlnisxgeneric")
        val sourceFormats = listOf(Format(format_id = "299", vcodec = "avc1", format_note = "1080p60"))

        assertTrue(VideoQualityPolicy.requiresVerifiedStaging(true, selected, sourceFormats, false))
        assertTrue(
            VideoQualityPolicy.requiresVerifiedStaging(
                true,
                Format(format_id = "best"),
                sourceFormats,
                false,
            )
        )
        assertTrue(VideoQualityPolicy.requiresVerifiedStaging(true, Format(), emptyList(), true))
        assertFalse(VideoQualityPolicy.requiresVerifiedStaging(false, selected, sourceFormats, true))
        assertFalse(VideoQualityPolicy.requiresVerifiedStaging(true, Format(), sourceFormats, false))
        assertFalse(VideoQualityPolicy.requiresVerifiedStaging(true, Format(), emptyList(), false))
    }

    @Test
    fun authenticatedQualityMismatchRetriesPublicThenWarnsIfStillDegraded() {
        assertEquals(
            DownloadQualityDecision.RETRY_PUBLIC,
            DownloadQualityFallbackPolicy.decide(
                expectedHeight = 1080,
                actualHeight = 360,
                isYoutubeVideo = true,
                initialAttemptHadAuthentication = true,
                publicRetryAlreadyUsed = false,
                isVerifiedReplacement = false
            )
        )
        assertEquals(
            DownloadQualityDecision.ACCEPT_WITH_DEGRADED_WARNING,
            DownloadQualityFallbackPolicy.decide(
                expectedHeight = 1080,
                actualHeight = 360,
                isYoutubeVideo = true,
                initialAttemptHadAuthentication = true,
                publicRetryAlreadyUsed = true,
                isVerifiedReplacement = false
            )
        )
    }

    @Test
    fun failedReplacementValidationRejectsBeforeOriginalCanBeReplaced() {
        assertEquals(
            DownloadQualityDecision.REJECT_REPLACEMENT,
            DownloadQualityFallbackPolicy.decide(
                expectedHeight = 1080,
                actualHeight = 360,
                isYoutubeVideo = true,
                initialAttemptHadAuthentication = false,
                publicRetryAlreadyUsed = true,
                isVerifiedReplacement = true
            )
        )
        assertEquals(
            DownloadQualityDecision.REJECT_INVALID_OUTPUT,
            DownloadQualityFallbackPolicy.decide(
                expectedHeight = 1080,
                actualHeight = 0,
                isYoutubeVideo = false,
                initialAttemptHadAuthentication = false,
                publicRetryAlreadyUsed = true,
                isVerifiedReplacement = false
            )
        )
    }

    @Test
    fun nonYoutubeNumericTargetsUseFallbackValidationWhileNullTargetsRemainRaw() {
        fun decide(actualHeight: Int, replacement: Boolean = false) =
            DownloadQualityFallbackPolicy.decide(
                expectedHeight = 1080,
                actualHeight = actualHeight,
                isYoutubeVideo = false,
                initialAttemptHadAuthentication = false,
                publicRetryAlreadyUsed = true,
                isVerifiedReplacement = replacement,
            )

        assertEquals(DownloadQualityDecision.ACCEPT_WITH_DEGRADED_WARNING, decide(720))
        assertEquals(DownloadQualityDecision.REJECT_REPLACEMENT, decide(720, replacement = true))
        assertEquals(DownloadQualityDecision.REJECT_INVALID_OUTPUT, decide(0))
        assertEquals(
            DownloadQualityDecision.ACCEPT,
            DownloadQualityFallbackPolicy.decide(
                expectedHeight = null,
                actualHeight = 0,
                isYoutubeVideo = false,
                initialAttemptHadAuthentication = false,
                publicRetryAlreadyUsed = true,
                isVerifiedReplacement = true,
            )
        )
    }

    @Test
    fun rawLowerResolutionOverrideHasNoValidationTargetAndAcceptsSuccessfulResult() {
        val target = StagedVideoQualityValidationPolicy.targetHeight(
            attemptTargetHeight = null,
            configuredFallbackHeight = 1080,
            hasRawFormatOverride = true,
        )

        assertNull(target)
        assertEquals(
            DownloadQualityDecision.ACCEPT,
            DownloadQualityFallbackPolicy.decide(
                expectedHeight = target,
                actualHeight = 360,
                isYoutubeVideo = false,
                initialAttemptHadAuthentication = false,
                publicRetryAlreadyUsed = true,
                isVerifiedReplacement = true,
            ),
        )
    }

    @Test
    fun rawAudioOnlyOverrideHasNoValidationTargetAndAcceptsZeroHeight() {
        val target = StagedVideoQualityValidationPolicy.targetHeight(
            attemptTargetHeight = null,
            configuredFallbackHeight = 1080,
            hasRawFormatOverride = true,
        )

        assertNull(target)
        assertEquals(
            DownloadQualityDecision.ACCEPT,
            DownloadQualityFallbackPolicy.decide(
                expectedHeight = target,
                actualHeight = 0,
                isYoutubeVideo = false,
                initialAttemptHadAuthentication = false,
                publicRetryAlreadyUsed = true,
                isVerifiedReplacement = true,
            ),
        )
    }

    @Test
    fun configuredNumericTargetRemainsActiveWithoutRawOverride() {
        val configuredTarget = StagedVideoQualityValidationPolicy.targetHeight(
            attemptTargetHeight = null,
            configuredFallbackHeight = 1080,
            hasRawFormatOverride = false,
        )
        val attemptTarget = StagedVideoQualityValidationPolicy.targetHeight(
            attemptTargetHeight = 720,
            configuredFallbackHeight = 1080,
            hasRawFormatOverride = false,
        )

        assertEquals(1080, configuredTarget)
        assertEquals(720, attemptTarget)
        assertEquals(
            DownloadQualityDecision.ACCEPT_WITH_DEGRADED_WARNING,
            DownloadQualityFallbackPolicy.decide(
                expectedHeight = configuredTarget,
                actualHeight = 360,
                isYoutubeVideo = false,
                initialAttemptHadAuthentication = false,
                publicRetryAlreadyUsed = true,
                isVerifiedReplacement = false,
            ),
        )
    }

    private fun assertCandidateReason(state: VideoFileQualityState, reason: LowQualityCandidateReason) {
        val result = VideoQualityPolicy.assess(
            input().copy(media = VideoMediaQuality(state = state))
        )
        assertTrue(result is LowQualityAssessment.Candidate)
        assertEquals(reason, (result as LowQualityAssessment.Candidate).reason)
    }

    private fun assertSkipped(reason: LowQualitySkipReason, input: LowQualityAssessmentInput) {
        assertEquals(LowQualityAssessment.Skipped(reason), VideoQualityPolicy.assess(input))
    }

    private fun assess(
        actualHeight: Int,
        requestedHeight: Int,
        sourceMaxHeight: Int
    ): LowQualityAssessment = VideoQualityPolicy.assess(
        input().copy(
            requestedHeight = requestedHeight,
            sourceMaxHeight = sourceMaxHeight,
            media = VideoMediaQuality(
                state = VideoFileQualityState.READY,
                width = actualHeight * 16 / 9,
                height = actualHeight
            )
        )
    )

    private fun input() = LowQualityAssessmentInput(
        isVideo = true,
        extractorSourceEligible = true,
        activeDuplicate = false,
        incognitoEnabled = false,
        hardSubDone = false,
        requestedHeight = 1080,
        sourceMaxHeight = 2160,
        media = VideoMediaQuality(
            state = VideoFileQualityState.READY,
            width = 640,
            height = 360,
            hasAudio = true
        )
    )
}
