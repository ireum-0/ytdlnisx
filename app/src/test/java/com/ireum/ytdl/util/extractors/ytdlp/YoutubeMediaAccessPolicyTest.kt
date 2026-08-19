package com.ireum.ytdl.util.extractors.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeMediaAccessPolicyTest {
    @Test
    fun ordinaryMediaStartsWithTheAvailablePublicRoute() {
        assertEquals(
            YoutubeMediaAccessProfile.PUBLIC_CONFIGURED,
            YoutubeMediaAccessPolicy.initialProfile(input(configuredClients = true))
        )
        assertEquals(
            YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
            YoutubeMediaAccessPolicy.initialProfile(input())
        )
    }

    @Test
    fun publicMediaExcludesCredentialsAndSubtitleWebIsRequestDriven() {
        val noSubtitles = YoutubeMediaAccessPolicy.requestPolicy(
            YoutubeMediaAccessProfile.PUBLIC_CONFIGURED,
            subtitlesRequested = false,
        )
        assertFalse(noSubtitles.includeCookies)
        assertFalse(noSubtitles.includeDataSyncId)
        assertFalse(noSubtitles.includePoTokens)
        assertFalse(noSubtitles.includeWebClientForSubtitles)
        assertTrue(noSubtitles.useConfiguredPlayerClients)

        val manualOrHardSubtitles = YoutubeMediaAccessPolicy.requestPolicy(
            YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
            subtitlesRequested = true,
        )
        assertFalse(manualOrHardSubtitles.includeCookies)
        assertFalse(manualOrHardSubtitles.includePoTokens)
        assertTrue(manualOrHardSubtitles.includeWebClientForSubtitles)
        assertTrue(manualOrHardSubtitles.forceDefaultPublicClients)

        val authenticated = YoutubeMediaAccessPolicy.requestPolicy(
            YoutubeMediaAccessProfile.AUTHENTICATED,
            subtitlesRequested = false,
        )
        assertTrue(authenticated.includeCookies)
        assertTrue(authenticated.includeDataSyncId)
        assertTrue(authenticated.includePoTokens)
        assertFalse(authenticated.includeWebClientForSubtitles)
    }

    @Test
    fun genericRequestsRetainConfiguredCookiesUnderPublicProfiles() {
        listOf(
            YoutubeMediaAccessProfile.PUBLIC_CONFIGURED,
            YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
        ).forEach { profile ->
            assertTrue(
                YoutubeMediaAccessPolicy.shouldAttachConfiguredCookies(
                    isYoutubeRequest = false,
                    profile = profile,
                    cookiesEnabled = true,
                    cookieFileAvailable = true,
                )
            )
        }
    }

    @Test
    fun genericFilenamePreviewUsesTheSharedConfiguredCookieDecision() {
        assertTrue(
            YoutubeMediaAccessPolicy.shouldAttachConfiguredCookies(
                isYoutubeRequest = false,
                profile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                cookiesEnabled = true,
                cookieFileAvailable = true,
            )
        )
    }

    @Test
    fun configuredCookieDecisionRequiresEnabledCookiesAndAnAvailableFile() {
        assertFalse(
            YoutubeMediaAccessPolicy.shouldAttachConfiguredCookies(
                isYoutubeRequest = false,
                profile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                cookiesEnabled = false,
                cookieFileAvailable = true,
            )
        )
        assertFalse(
            YoutubeMediaAccessPolicy.shouldAttachConfiguredCookies(
                isYoutubeRequest = false,
                profile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                cookiesEnabled = true,
                cookieFileAvailable = false,
            )
        )
    }

    @Test
    fun youtubeConfiguredCookiesFollowTheMediaAccessProfile() {
        listOf(
            YoutubeMediaAccessProfile.PUBLIC_CONFIGURED,
            YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
        ).forEach { profile ->
            assertFalse(
                YoutubeMediaAccessPolicy.shouldAttachConfiguredCookies(
                    isYoutubeRequest = true,
                    profile = profile,
                    cookiesEnabled = true,
                    cookieFileAvailable = true,
                )
            )
        }
        listOf(
            YoutubeMediaAccessProfile.AUTHENTICATED,
            YoutubeMediaAccessProfile.USER_PINNED,
        ).forEach { profile ->
            assertTrue(
                YoutubeMediaAccessPolicy.shouldAttachConfiguredCookies(
                    isYoutubeRequest = true,
                    profile = profile,
                    cookiesEnabled = true,
                    cookieFileAvailable = true,
                )
            )
        }
    }

    @Test
    fun poTokensAndRawAccessOrFormatConfigurationAreUserPinned() {
        assertEquals(
            YoutubeMediaAccessProfile.USER_PINNED,
            YoutubeMediaAccessPolicy.initialProfile(input(poTokens = true))
        )
        assertEquals(
            YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
            YoutubeMediaAccessPolicy.initialProfile(input(rawFormat = true))
        )
        assertTrue(YoutubeMediaAccessPolicy.containsRawAccessConfiguration("po_token=web.gvs+secret"))
        assertTrue(YoutubeMediaAccessPolicy.containsRawAccessConfiguration("--cookies session.txt"))
        assertTrue(YoutubeMediaAccessPolicy.containsRawFormatOverride("--format=bv+ba/b"))
        assertTrue(YoutubeMediaAccessPolicy.containsRawFormatOverride("-f 299+251"))
        assertFalse(YoutubeMediaAccessPolicy.containsRawAccessConfiguration("player_skip=webpage"))
        assertFalse(YoutubeMediaAccessPolicy.containsRawFormatOverride("--merge-output-format mp4"))
    }

    @Test
    fun accountRestrictionClassifierUsesOnlyHighConfidenceCases() {
        listOf(
            "ERROR: This video is private",
            "This video is available to this channel's members",
            "Sign in to confirm your age",
            "Login required to view this video",
            "HTTP Error 401: Unauthorized",
        ).forEach { assertTrue(it, YoutubeAccountRestrictionClassifier.isAccountRestricted(it)) }

        listOf(
            "HTTP Error 403: Forbidden",
            "Connection timed out",
            "HTTP Error 429: Too Many Requests",
            "Requested format is not available",
        ).forEach { assertFalse(it, YoutubeAccountRestrictionClassifier.isAccountRestricted(it)) }
    }

    @Test
    fun routeTrackingNeverRepeatsOrCycles() {
        val attempts = YoutubeMediaAttemptSet()
        assertTrue(attempts.markAttempted(YoutubeMediaAccessProfile.PUBLIC_CONFIGURED))
        assertFalse(attempts.markAttempted(YoutubeMediaAccessProfile.PUBLIC_CONFIGURED))
        assertEquals(
            YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
            attempts.nextCleanPublicAfter(YoutubeMediaAccessProfile.PUBLIC_CONFIGURED)
        )
        assertTrue(attempts.markAttempted(YoutubeMediaAccessProfile.PUBLIC_DEFAULT))
        assertNull(attempts.nextCleanPublicAfter(YoutubeMediaAccessProfile.PUBLIC_CONFIGURED))
        assertEquals(YoutubeMediaAccessProfile.AUTHENTICATED, attempts.authenticatedIfUntried())
        assertTrue(attempts.markAttempted(YoutubeMediaAccessProfile.AUTHENTICATED))
        assertNull(attempts.authenticatedIfUntried())
        assertTrue(attempts.markProbed(YoutubeMediaAccessProfile.AUTHENTICATED))
        assertFalse(attempts.markProbed(YoutubeMediaAccessProfile.AUTHENTICATED))
        assertTrue(attempts.markCachedInfoRetried(YoutubeMediaAccessProfile.AUTHENTICATED))
        assertFalse(attempts.markCachedInfoRetried(YoutubeMediaAccessProfile.AUTHENTICATED))
        assertTrue(attempts.recordCompletedMediaTransfer())
        assertTrue(attempts.recordCompletedMediaTransfer())
        assertFalse(attempts.recordCompletedMediaTransfer())
        assertEquals(2, attempts.completedMediaTransferCount())
        assertEquals(
            setOf(
                YoutubeMediaAccessProfile.PUBLIC_CONFIGURED,
                YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                YoutubeMediaAccessProfile.AUTHENTICATED,
            ),
            attempts.attempted()
        )
    }

    @Test
    fun selectionProbeIsDeferredUntilGuardedPreTransferUnavailability() {
        assertTrue(
            YoutubeMediaAccessPolicy.shouldRunSelectionProbe(
                profile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                failureKind = YoutubeMediaFailureKind.QUALITY_UNAVAILABLE,
                transferStarted = false,
                qualityGuardApplied = true,
                targetHeight = 1080,
            )
        )
        assertFalse(
            YoutubeMediaAccessPolicy.shouldRunSelectionProbe(
                profile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                failureKind = YoutubeMediaFailureKind.GENERIC_FORBIDDEN,
                transferStarted = false,
                qualityGuardApplied = true,
                targetHeight = 1080,
            )
        )
        assertFalse(
            YoutubeMediaAccessPolicy.shouldRunSelectionProbe(
                profile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                failureKind = YoutubeMediaFailureKind.QUALITY_UNAVAILABLE,
                transferStarted = true,
                qualityGuardApplied = true,
                targetHeight = 1080,
            )
        )
        assertFalse(
            YoutubeMediaAccessPolicy.shouldRunSelectionProbe(
                profile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                failureKind = YoutubeMediaFailureKind.QUALITY_UNAVAILABLE,
                transferStarted = false,
                qualityGuardApplied = true,
                targetHeight = null,
            )
        )
    }

    @Test
    fun authenticatedDegradedCompletionUsesOneCleanPublicRetry() {
        assertEquals(
            YoutubeQualityRouteOutcome.Retry(YoutubeMediaAccessProfile.PUBLIC_DEFAULT),
            qualityRoute(
                completedProfile = YoutubeMediaAccessProfile.AUTHENTICATED,
                attemptedFamilies = setOf(YoutubeMediaAccessFamily.AUTHENTICATED),
                cleanPublic = true
            )
        )
        assertEquals(
            YoutubeQualityRouteOutcome.AcceptDegraded,
            qualityRoute(
                completedProfile = YoutubeMediaAccessProfile.AUTHENTICATED,
                attemptedFamilies = setOf(
                    YoutubeMediaAccessFamily.AUTHENTICATED,
                    YoutubeMediaAccessFamily.PUBLIC
                ),
                cleanPublic = true
            )
        )
    }

    @Test
    fun userPinnedFallbackRequiresSafeReconstruction() {
        assertEquals(
            YoutubeQualityRouteOutcome.Retry(YoutubeMediaAccessProfile.PUBLIC_DEFAULT),
            qualityRoute(
                completedProfile = YoutubeMediaAccessProfile.USER_PINNED,
                attemptedFamilies = setOf(YoutubeMediaAccessFamily.AUTHENTICATED),
                cleanPublic = true
            )
        )
        assertEquals(
            YoutubeQualityRouteOutcome.AcceptDegraded,
            qualityRoute(
                completedProfile = YoutubeMediaAccessProfile.USER_PINNED,
                attemptedFamilies = setOf(YoutubeMediaAccessFamily.AUTHENTICATED),
                cleanPublic = false
            )
        )
    }

    @Test
    fun publicDegradedCompletionNeedsTargetProvingAuthenticatedProbe() {
        val initial = qualityRoute(
            completedProfile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
            attemptedFamilies = setOf(YoutubeMediaAccessFamily.PUBLIC),
            cleanPublic = true
        )
        assertEquals(
            YoutubeQualityRouteOutcome.Probe(YoutubeMediaAccessProfile.AUTHENTICATED, 1080),
            initial
        )
        assertEquals(
            YoutubeQualityRouteOutcome.AcceptDegraded,
            qualityRoute(
                completedProfile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                attemptedFamilies = setOf(YoutubeMediaAccessFamily.PUBLIC),
                probedFamilies = setOf(YoutubeMediaAccessFamily.AUTHENTICATED),
                authenticatedProbeHeight = 720,
                cleanPublic = true
            )
        )
        assertEquals(
            YoutubeQualityRouteOutcome.Retry(YoutubeMediaAccessProfile.AUTHENTICATED),
            qualityRoute(
                completedProfile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                attemptedFamilies = setOf(YoutubeMediaAccessFamily.PUBLIC),
                probedFamilies = setOf(YoutubeMediaAccessFamily.AUTHENTICATED),
                authenticatedProbeHeight = 1080,
                cleanPublic = true
            )
        )
    }

    @Test
    fun verifiedReplacementNeverAcceptsExhaustedDegradedOutput() {
        assertEquals(
            YoutubeQualityRouteOutcome.RejectReplacement,
            qualityRoute(
                completedProfile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                attemptedFamilies = setOf(
                    YoutubeMediaAccessFamily.PUBLIC,
                    YoutubeMediaAccessFamily.AUTHENTICATED
                ),
                cleanPublic = true,
                replacement = true
            )
        )
    }

    private fun qualityRoute(
        completedProfile: YoutubeMediaAccessProfile,
        attemptedFamilies: Set<YoutubeMediaAccessFamily>,
        cleanPublic: Boolean,
        probedFamilies: Set<YoutubeMediaAccessFamily> = emptySet(),
        authenticatedProbeHeight: Int? = null,
        replacement: Boolean = false,
    ): YoutubeQualityRouteOutcome = YoutubeMediaAccessPolicy.qualityRoute(
        YoutubeQualityRouteInput(
            completedProfile = completedProfile,
            attempts = YoutubeMediaAttemptSnapshot(
                attemptedFamilies = attemptedFamilies,
                probedFamilies = probedFamilies,
                completedMediaTransfers = 1
            ),
            expectedHeight = 1080,
            actualHeight = 360,
            isYoutubeVideo = true,
            isVerifiedReplacement = replacement,
            canBuildCleanPublicRequest = cleanPublic,
            hasAuthenticationConfiguration = true,
            authenticatedProbeHeight = authenticatedProbeHeight
        )
    )

    private fun input(
        configuredClients: Boolean = false,
        poTokens: Boolean = false,
        rawAccess: Boolean = false,
        rawFormat: Boolean = false,
    ) = YoutubeMediaPolicyInput(
        hasConfiguredPublicClients = configuredClients,
        hasEnabledPoTokenConfiguration = poTokens,
        hasRawAccessConfiguration = rawAccess,
        hasRawFormatOverride = rawFormat,
    )
}
