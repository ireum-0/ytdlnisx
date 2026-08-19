package com.ireum.ytdl.util.extractors.ytdlp

enum class YoutubeMediaAccessProfile {
    PUBLIC_CONFIGURED,
    PUBLIC_DEFAULT,
    AUTHENTICATED,
    USER_PINNED;

    val isPublic: Boolean
        get() = this == PUBLIC_CONFIGURED || this == PUBLIC_DEFAULT
}

sealed interface YoutubeVideoQualityIntent {
    data class NumericHeight(val height: Int) : YoutubeVideoQualityIntent
    data class SpecificHeight(val height: Int) : YoutubeVideoQualityIntent
    data class Best(val knownSourceMaximum: Int?) : YoutubeVideoQualityIntent
    data class IntentionallyLow(val height: Int? = null) : YoutubeVideoQualityIntent
    object Unknown : YoutubeVideoQualityIntent
}

data class YoutubeMediaPolicyInput(
    val hasConfiguredPublicClients: Boolean,
    val hasEnabledPoTokenConfiguration: Boolean,
    val hasRawAccessConfiguration: Boolean,
    val hasRawFormatOverride: Boolean,
)

enum class YoutubeMediaFailureKind {
    ACCOUNT_RESTRICTED,
    QUALITY_UNAVAILABLE,
    GENERIC_FORBIDDEN,
    OTHER,
}

enum class YoutubeMediaAccessFamily {
    PUBLIC,
    AUTHENTICATED
}

val YoutubeMediaAccessProfile.family: YoutubeMediaAccessFamily
    get() = if (isPublic) YoutubeMediaAccessFamily.PUBLIC else YoutubeMediaAccessFamily.AUTHENTICATED

data class YoutubeMediaAttemptSnapshot(
    val attemptedFamilies: Set<YoutubeMediaAccessFamily>,
    val probedFamilies: Set<YoutubeMediaAccessFamily>,
    val completedMediaTransfers: Int
)

sealed interface YoutubeQualityRouteOutcome {
    object Accept : YoutubeQualityRouteOutcome
    object AcceptDegraded : YoutubeQualityRouteOutcome
    object RejectReplacement : YoutubeQualityRouteOutcome
    object RejectInvalid : YoutubeQualityRouteOutcome
    data class Probe(
        val profile: YoutubeMediaAccessProfile,
        val targetHeight: Int
    ) : YoutubeQualityRouteOutcome
    data class Retry(val profile: YoutubeMediaAccessProfile) : YoutubeQualityRouteOutcome
}

data class YoutubeQualityRouteInput(
    val completedProfile: YoutubeMediaAccessProfile,
    val attempts: YoutubeMediaAttemptSnapshot,
    val expectedHeight: Int?,
    val actualHeight: Int,
    val isYoutubeVideo: Boolean,
    val isVerifiedReplacement: Boolean,
    val canBuildCleanPublicRequest: Boolean,
    val hasAuthenticationConfiguration: Boolean,
    val accountRestrictionEvidence: Boolean = false,
    val authenticatedProbeHeight: Int? = null
)

data class YoutubeMediaRequestPolicy(
    val includeCookies: Boolean,
    val includeDataSyncId: Boolean,
    val includePoTokens: Boolean,
    val useConfiguredPlayerClients: Boolean,
    val forceDefaultPublicClients: Boolean,
    val includeWebClientForSubtitles: Boolean,
)

object YoutubeMediaAccessPolicy {
    private val rawFormatOption = Regex("""(?i)(?:^|\s)(?:-f|--format)(?:\s|=|$)""")
    private val rawAccessOption = Regex(
        """(?i)(?:^|[\s;])(?:--cookies(?:-from-browser)?|--username|--password|--video-password|--extractor-args)(?:\s|=|$)|(?:^|[;])\s*(?:po_token|data_sync_id|visitor_data|player_client)\s*="""
    )

    fun initialProfile(input: YoutubeMediaPolicyInput): YoutubeMediaAccessProfile {
        if (
            input.hasEnabledPoTokenConfiguration ||
            input.hasRawAccessConfiguration
        ) {
            return YoutubeMediaAccessProfile.USER_PINNED
        }
        return if (input.hasConfiguredPublicClients) {
            YoutubeMediaAccessProfile.PUBLIC_CONFIGURED
        } else {
            YoutubeMediaAccessProfile.PUBLIC_DEFAULT
        }
    }

    fun requestPolicy(
        profile: YoutubeMediaAccessProfile,
        subtitlesRequested: Boolean,
    ): YoutubeMediaRequestPolicy {
        val authenticated = profile == YoutubeMediaAccessProfile.AUTHENTICATED ||
            profile == YoutubeMediaAccessProfile.USER_PINNED
        return YoutubeMediaRequestPolicy(
            includeCookies = authenticated,
            includeDataSyncId = authenticated,
            includePoTokens = authenticated,
            useConfiguredPlayerClients = profile != YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
            forceDefaultPublicClients = profile == YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
            includeWebClientForSubtitles = subtitlesRequested,
        )
    }

    fun shouldAttachConfiguredCookies(
        isYoutubeRequest: Boolean,
        profile: YoutubeMediaAccessProfile,
        cookiesEnabled: Boolean,
        cookieFileAvailable: Boolean,
    ): Boolean {
        if (!cookiesEnabled || !cookieFileAvailable) return false
        return !isYoutubeRequest || requestPolicy(profile, subtitlesRequested = false).includeCookies
    }

    fun containsRawFormatOverride(vararg rawArguments: String?): Boolean {
        return rawArguments.any { rawFormatOption.containsMatchIn(it.orEmpty()) }
    }

    fun containsRawAccessConfiguration(vararg rawArguments: String?): Boolean {
        return rawArguments.any { rawAccessOption.containsMatchIn(it.orEmpty()) }
    }

    fun classifyFailure(text: String): YoutubeMediaFailureKind {
        return when {
            YoutubeAccountRestrictionClassifier.isAccountRestricted(text) -> {
                YoutubeMediaFailureKind.ACCOUNT_RESTRICTED
            }
            text.contains("requested format is not available", ignoreCase = true) ||
                text.contains("no video formats found", ignoreCase = true) ||
                text.contains("no suitable formats", ignoreCase = true) -> {
                YoutubeMediaFailureKind.QUALITY_UNAVAILABLE
            }
            text.contains("HTTP Error 403", ignoreCase = true) ||
                text.contains("403: Forbidden", ignoreCase = true) -> {
                YoutubeMediaFailureKind.GENERIC_FORBIDDEN
            }
            else -> YoutubeMediaFailureKind.OTHER
        }
    }

    fun shouldRunSelectionProbe(
        profile: YoutubeMediaAccessProfile,
        failureKind: YoutubeMediaFailureKind,
        transferStarted: Boolean,
        qualityGuardApplied: Boolean,
        targetHeight: Int?,
    ): Boolean {
        return profile.isPublic &&
            failureKind == YoutubeMediaFailureKind.QUALITY_UNAVAILABLE &&
            !transferStarted &&
            qualityGuardApplied &&
            targetHeight != null && targetHeight > 0
    }

    fun qualityRoute(input: YoutubeQualityRouteInput): YoutubeQualityRouteOutcome {
        val target = input.expectedHeight?.takeIf { it > 0 }
            ?: return YoutubeQualityRouteOutcome.Accept
        if (!input.isYoutubeVideo) return YoutubeQualityRouteOutcome.Accept
        if (input.actualHeight >= target) return YoutubeQualityRouteOutcome.Accept
        if (input.actualHeight <= 0) return YoutubeQualityRouteOutcome.RejectInvalid

        fun exhausted(): YoutubeQualityRouteOutcome = if (input.isVerifiedReplacement) {
            YoutubeQualityRouteOutcome.RejectReplacement
        } else {
            YoutubeQualityRouteOutcome.AcceptDegraded
        }

        if (input.attempts.completedMediaTransfers >= YoutubeMediaAttemptSet.MAX_COMPLETED_MEDIA_TRANSFERS) {
            return exhausted()
        }

        return when (input.completedProfile.family) {
            YoutubeMediaAccessFamily.AUTHENTICATED -> {
                if (
                    YoutubeMediaAccessFamily.PUBLIC !in input.attempts.attemptedFamilies &&
                    input.canBuildCleanPublicRequest
                ) {
                    YoutubeQualityRouteOutcome.Retry(YoutubeMediaAccessProfile.PUBLIC_DEFAULT)
                } else {
                    exhausted()
                }
            }
            YoutubeMediaAccessFamily.PUBLIC -> {
                if (
                    !input.hasAuthenticationConfiguration ||
                    YoutubeMediaAccessFamily.AUTHENTICATED in input.attempts.attemptedFamilies
                ) {
                    return exhausted()
                }
                when {
                    input.accountRestrictionEvidence ->
                        YoutubeQualityRouteOutcome.Retry(YoutubeMediaAccessProfile.AUTHENTICATED)
                    input.authenticatedProbeHeight != null -> {
                        if (input.authenticatedProbeHeight >= target) {
                            YoutubeQualityRouteOutcome.Retry(YoutubeMediaAccessProfile.AUTHENTICATED)
                        } else {
                            exhausted()
                        }
                    }
                    YoutubeMediaAccessFamily.AUTHENTICATED !in input.attempts.probedFamilies ->
                        YoutubeQualityRouteOutcome.Probe(
                            YoutubeMediaAccessProfile.AUTHENTICATED,
                            target
                        )
                    else -> exhausted()
                }
            }
        }
    }
}

class YoutubeMediaAttemptSet {
    private val attemptedProfiles = linkedSetOf<YoutubeMediaAccessProfile>()
    private val probedProfiles = linkedSetOf<YoutubeMediaAccessProfile>()
    private val cachedInfoRetriedProfiles = linkedSetOf<YoutubeMediaAccessProfile>()
    private var completedMediaTransfers = 0

    fun markAttempted(profile: YoutubeMediaAccessProfile): Boolean = attemptedProfiles.add(profile)

    fun markProbed(profile: YoutubeMediaAccessProfile): Boolean = probedProfiles.add(profile)

    fun markCachedInfoRetried(profile: YoutubeMediaAccessProfile): Boolean =
        cachedInfoRetriedProfiles.add(profile)

    fun recordCompletedMediaTransfer(): Boolean {
        if (completedMediaTransfers >= MAX_COMPLETED_MEDIA_TRANSFERS) return false
        completedMediaTransfers += 1
        return true
    }

    fun completedMediaTransferCount(): Int = completedMediaTransfers

    fun wasAttempted(profile: YoutubeMediaAccessProfile): Boolean = profile in attemptedProfiles

    fun wasProbed(profile: YoutubeMediaAccessProfile): Boolean = profile in probedProfiles

    fun nextCleanPublicAfter(profile: YoutubeMediaAccessProfile): YoutubeMediaAccessProfile? {
        return YoutubeMediaAccessProfile.PUBLIC_DEFAULT.takeIf {
            profile == YoutubeMediaAccessProfile.PUBLIC_CONFIGURED && it !in attemptedProfiles
        }
    }

    fun authenticatedIfUntried(): YoutubeMediaAccessProfile? {
        return YoutubeMediaAccessProfile.AUTHENTICATED.takeIf { it !in attemptedProfiles }
    }

    fun attempted(): Set<YoutubeMediaAccessProfile> = attemptedProfiles.toSet()

    fun snapshot(): YoutubeMediaAttemptSnapshot = YoutubeMediaAttemptSnapshot(
        attemptedFamilies = attemptedProfiles.mapTo(linkedSetOf()) { it.family },
        probedFamilies = probedProfiles.mapTo(linkedSetOf()) { it.family },
        completedMediaTransfers = completedMediaTransfers
    )

    companion object {
        const val MAX_COMPLETED_MEDIA_TRANSFERS = 2
    }
}

object YoutubeAccountRestrictionClassifier {
    private val highConfidencePhrases = listOf(
        "this video is private",
        "private video",
        "private video. sign in if you've been granted access",
        "available to this channel's members",
        "available to members of this channel",
        "only available to channel members",
        "join this channel to get access",
        "sign in to confirm your age",
        "confirm your age",
        "age-restricted video",
        "sign in to confirm you're not a bot",
        "sign in to confirm you\u2019re not a bot",
        "sign in to view this video",
        "you must be signed in to view",
        "login required",
        "account is required",
        "requires an account",
        "HTTP Error 401",
        "401: Unauthorized",
    )
    private val subscriberOnlyAvailability = Regex(
        """(?i)[\"']?availability[\"']?\s*[:=]\s*[\"']?subscriber_only"""
    )

    fun isAccountRestricted(text: String): Boolean {
        return subscriberOnlyAvailability.containsMatchIn(text) ||
            highConfidencePhrases.any { phrase -> text.contains(phrase, ignoreCase = true) }
    }
}
