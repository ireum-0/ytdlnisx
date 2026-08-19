package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeVideoQualityIntent
import kotlin.math.min

enum class VideoFileQualityState {
    READY,
    MISSING,
    INACCESSIBLE,
    CORRUPT,
    NO_VIDEO
}

data class VideoMediaQuality(
    val state: VideoFileQualityState,
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = false,
    val path: String = ""
) {
    val resolutionHeight: Int
        get() = when {
            width > 0 && height > 0 -> min(width, height)
            height > 0 -> height
            else -> 0
        }
}

enum class LowQualityCandidateReason {
    BELOW_EXPECTED,
    FILE_MISSING,
    FILE_CORRUPT,
    NO_VIDEO_STREAM
}

enum class LowQualitySkipReason {
    NOT_VIDEO,
    LOCAL_OR_UNSUPPORTED_SOURCE,
    ACTIVE_DUPLICATE,
    INCOGNITO_ENABLED,
    FILE_INACCESSIBLE,
    HARD_SUB_REPLACEMENT_UNSUPPORTED,
    QUALITY_EXPECTATION_UNKNOWN,
    SOURCE_QUALITY_UNKNOWN,
    MEETS_EXPECTED_QUALITY
}

sealed interface LowQualityAssessment {
    data class Candidate(
        val reason: LowQualityCandidateReason,
        val actualHeight: Int,
        val expectedHeight: Int,
        val sourceMaxHeight: Int
    ) : LowQualityAssessment

    data class Skipped(val reason: LowQualitySkipReason) : LowQualityAssessment
}

data class LowQualityAssessmentInput(
    val isVideo: Boolean,
    val extractorSourceEligible: Boolean,
    val activeDuplicate: Boolean,
    val incognitoEnabled: Boolean,
    val hardSubDone: Boolean,
    val requestedHeight: Int?,
    val sourceMaxHeight: Int?,
    val media: VideoMediaQuality
)

object VideoQualityPolicy {
    private val explicitHeightRegex = Regex("""(?<!\d)(\d{3,4})p(?:\d{1,3})?(?!\d)""", RegexOption.IGNORE_CASE)
    private val dimensionsRegex = Regex("""(?<!\d)(\d{2,5})\s*[x×]\s*(\d{2,5})(?!\d)""", RegexOption.IGNORE_CASE)

    fun requestedHeight(format: Format): Int? {
        return parseHeight(format.format_id)
            ?: parseHeight(format.format_note)
            ?: parseHeight(format.encoding)
    }

    fun qualityIntent(format: Format, sourceFormats: List<Format>): YoutubeVideoQualityIntent {
        val normalizedId = format.format_id.trim().lowercase()
        if (normalizedId == "worst" || normalizedId == "wa" || normalizedId == "wv") {
            return YoutubeVideoQualityIntent.IntentionallyLow(requestedHeight(format))
        }

        val knownSourceMaximum = maxSourceHeight(sourceFormats)
        if (normalizedId == "best" || normalizedId == "b" || normalizedId == "bv") {
            return YoutubeVideoQualityIntent.Best(knownSourceMaximum)
        }

        val selectedSourceFormat = sourceFormats.firstOrNull {
            it.format_id.trim().equals(format.format_id.trim(), ignoreCase = true)
        }
        val selectedHeight = selectedSourceFormat?.let(::requestedHeight)
        if (selectedHeight != null) {
            return YoutubeVideoQualityIntent.SpecificHeight(selectedHeight)
        }

        return requestedHeight(format)
            ?.let(YoutubeVideoQualityIntent::NumericHeight)
            ?: YoutubeVideoQualityIntent.Unknown
    }

    fun effectiveTargetHeight(
        intent: YoutubeVideoQualityIntent,
        knownSourceMaximum: Int? = null,
        compatibilityMaximum: Int? = null,
    ): Int? {
        val intendedHeight = when (intent) {
            is YoutubeVideoQualityIntent.NumericHeight -> knownSourceMaximum
                ?.takeIf { it > 0 }
                ?.let { intent.height.coerceAtMost(it) }
                ?: intent.height
            is YoutubeVideoQualityIntent.SpecificHeight -> intent.height
            is YoutubeVideoQualityIntent.Best -> intent.knownSourceMaximum
            is YoutubeVideoQualityIntent.IntentionallyLow,
            YoutubeVideoQualityIntent.Unknown -> null
        }?.takeIf { it > 0 } ?: return null

        return compatibilityMaximum
            ?.takeIf { it > 0 }
            ?.let { intendedHeight.coerceAtMost(it) }
            ?: intendedHeight
    }

    fun guardedFormatExpression(
        expression: String,
        minimumHeight: Int?,
        preserveLeadingSpecificSelectors: Boolean,
    ): String {
        val target = minimumHeight?.takeIf { it > 0 } ?: return expression
        var preservingSpecific = preserveLeadingSpecificSelectors
        return expression.split('/').joinToString("/") { branch ->
            val trimmed = branch.trim()
            if (trimmed.isEmpty()) return@joinToString trimmed

            val videoSelector = trimmed.substringBefore('+').trim()
            val genericVideo = isGenericVideoSelector(videoSelector)
            if (preservingSpecific && !genericVideo) {
                return@joinToString trimmed
            }
            preservingSpecific = false
            if (
                !genericVideo ||
                videoSelector.contains("height>=") ||
                videoSelector.contains("width>=")
            ) {
                return@joinToString trimmed
            }

            val suffix = trimmed.removePrefix(videoSelector)
            val landscape = "$videoSelector[height>=$target][height<=$target]$suffix"
            val portrait = "$videoSelector[width>=$target][width<=$target]$suffix"
            "$landscape/$portrait"
        }
    }

    private fun isGenericVideoSelector(selector: String): Boolean {
        val base = selector.substringBefore('[').trim().lowercase()
        return base in setOf("b", "b*", "best", "bv", "bv*", "bestvideo", "w", "wv")
    }

    fun maxSourceHeight(formats: List<Format>): Int? {
        return formats.asSequence()
            .filterNot { format ->
                format.vcodec.equals("none", ignoreCase = true) ||
                    format.format_note.contains("audio only", ignoreCase = true)
            }
            .mapNotNull { format ->
                parseHeight(format.format_note)
                    ?: parseHeight(format.encoding)
                    ?: parseHeight(format.format_id)
            }
            .filter { it > 0 }
            .maxOrNull()
    }

    fun compatibleMaximumHeight(requestedHeight: Int?, decoderMaximumHeight: Int): Int {
        require(decoderMaximumHeight > 0)
        return requestedHeight?.takeIf { it > 0 }?.coerceAtMost(decoderMaximumHeight)
            ?: decoderMaximumHeight
    }

    fun expectedDownloadHeight(format: Format, sourceFormats: List<Format>): Int? {
        return effectiveTargetHeight(
            intent = qualityIntent(format, sourceFormats),
            knownSourceMaximum = maxSourceHeight(sourceFormats),
        )
    }

    fun expectedHeight(requestedHeight: Int?, sourceMaxHeight: Int?): Int? {
        val requested = requestedHeight?.takeIf { it > 0 }
        val sourceMax = sourceMaxHeight?.takeIf { it > 0 }
        return when {
            requested != null && sourceMax != null -> min(requested, sourceMax)
            requested != null -> requested
            else -> sourceMax
        }
    }

    fun requiresVerifiedStaging(
        isVideo: Boolean,
        format: Format,
        sourceFormats: List<Format>,
        isQualityReplacement: Boolean
    ): Boolean {
        return isVideo && (
            isQualityReplacement || expectedDownloadHeight(format, sourceFormats) != null
            )
    }

    fun isPreliminaryCandidate(
        requestedHeight: Int?,
        media: VideoMediaQuality
    ): Boolean {
        val requested = requestedHeight?.takeIf { it > 0 } ?: return false
        return media.state != VideoFileQualityState.READY || media.resolutionHeight < requested
    }

    fun replacementPersistenceSkipReason(
        incognitoEnabled: Boolean
    ): LowQualitySkipReason? =
        LowQualitySkipReason.INCOGNITO_ENABLED.takeIf { incognitoEnabled }

    fun assess(input: LowQualityAssessmentInput): LowQualityAssessment {
        if (!input.isVideo) return LowQualityAssessment.Skipped(LowQualitySkipReason.NOT_VIDEO)
        if (!input.extractorSourceEligible) {
            return LowQualityAssessment.Skipped(LowQualitySkipReason.LOCAL_OR_UNSUPPORTED_SOURCE)
        }
        if (input.activeDuplicate) {
            return LowQualityAssessment.Skipped(LowQualitySkipReason.ACTIVE_DUPLICATE)
        }
        if (input.incognitoEnabled) {
            return LowQualityAssessment.Skipped(LowQualitySkipReason.INCOGNITO_ENABLED)
        }
        if (input.media.state == VideoFileQualityState.INACCESSIBLE) {
            return LowQualityAssessment.Skipped(LowQualitySkipReason.FILE_INACCESSIBLE)
        }
        if (input.hardSubDone) {
            return LowQualityAssessment.Skipped(LowQualitySkipReason.HARD_SUB_REPLACEMENT_UNSUPPORTED)
        }

        val sourceMax = input.sourceMaxHeight?.takeIf { it > 0 }
            ?: return LowQualityAssessment.Skipped(LowQualitySkipReason.SOURCE_QUALITY_UNKNOWN)
        val expected = expectedHeight(input.requestedHeight, sourceMax)
            ?: return LowQualityAssessment.Skipped(LowQualitySkipReason.QUALITY_EXPECTATION_UNKNOWN)
        if (expected <= 0) {
            return LowQualityAssessment.Skipped(LowQualitySkipReason.QUALITY_EXPECTATION_UNKNOWN)
        }

        val reason = when (input.media.state) {
            VideoFileQualityState.MISSING -> LowQualityCandidateReason.FILE_MISSING
            VideoFileQualityState.CORRUPT -> LowQualityCandidateReason.FILE_CORRUPT
            VideoFileQualityState.NO_VIDEO -> LowQualityCandidateReason.NO_VIDEO_STREAM
            VideoFileQualityState.INACCESSIBLE -> error("Inaccessible media must be skipped")
            VideoFileQualityState.READY -> {
                if (input.media.resolutionHeight >= expected) {
                    return LowQualityAssessment.Skipped(LowQualitySkipReason.MEETS_EXPECTED_QUALITY)
                }
                LowQualityCandidateReason.BELOW_EXPECTED
            }
        }
        return LowQualityAssessment.Candidate(
            reason = reason,
            actualHeight = input.media.resolutionHeight,
            expectedHeight = expected,
            sourceMaxHeight = sourceMax
        )
    }

    fun parseHeight(value: String?): Int? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        explicitHeightRegex.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val dimensions = dimensionsRegex.find(normalized) ?: return null
        val width = dimensions.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val height = dimensions.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return min(width, height).takeIf { it > 0 }
    }
}

enum class DownloadQualityDecision {
    ACCEPT,
    RETRY_PUBLIC,
    ACCEPT_WITH_DEGRADED_WARNING,
    REJECT_REPLACEMENT,
    REJECT_INVALID_OUTPUT
}

internal object StagedVideoQualityValidationPolicy {
    fun targetHeight(
        attemptTargetHeight: Int?,
        configuredFallbackHeight: Int?,
        hasRawFormatOverride: Boolean,
    ): Int? {
        if (hasRawFormatOverride) return null
        return attemptTargetHeight ?: configuredFallbackHeight
    }
}

object DownloadQualityFallbackPolicy {
    fun decide(
        expectedHeight: Int?,
        actualHeight: Int,
        isYoutubeVideo: Boolean,
        initialAttemptHadAuthentication: Boolean,
        publicRetryAlreadyUsed: Boolean,
        isVerifiedReplacement: Boolean
    ): DownloadQualityDecision {
        val expected = expectedHeight?.takeIf { it > 0 } ?: return DownloadQualityDecision.ACCEPT
        if (actualHeight >= expected) return DownloadQualityDecision.ACCEPT
        if (
            isYoutubeVideo &&
            initialAttemptHadAuthentication &&
            !publicRetryAlreadyUsed
        ) {
            return DownloadQualityDecision.RETRY_PUBLIC
        }
        if (actualHeight <= 0) return DownloadQualityDecision.REJECT_INVALID_OUTPUT
        return if (isVerifiedReplacement) {
            DownloadQualityDecision.REJECT_REPLACEMENT
        } else {
            DownloadQualityDecision.ACCEPT_WITH_DEGRADED_WARNING
        }
    }
}
