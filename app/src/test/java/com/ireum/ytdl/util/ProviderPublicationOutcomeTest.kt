package com.ireum.ytdl.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderPublicationOutcomeTest {
    @Test
    fun providerDeleteAndReservationClearMustBothBeAcknowledged() {
        assertEquals(
            ProviderReservationRecovery.ROLLED_BACK,
            resolveProviderReservationFailure(
                rollbackProviderObject = { true },
                clearReservation = { true },
            ),
        )
        assertEquals(
            ProviderReservationRecovery.UNRESOLVED,
            resolveProviderReservationFailure(
                rollbackProviderObject = { false },
                clearReservation = { true },
            ),
        )
        assertEquals(
            ProviderReservationRecovery.UNRESOLVED,
            resolveProviderReservationFailure(
                rollbackProviderObject = { true },
                clearReservation = { false },
            ),
        )
        assertEquals(
            ProviderReservationRecovery.UNRESOLVED,
            resolveProviderReservationFailure(
                rollbackProviderObject = { throw IllegalStateException("provider unavailable") },
                clearReservation = { true },
            ),
        )
    }

    @Test
    fun failedProviderRollbackDoesNotAttemptToReleaseTheReservation() {
        var clearCalls = 0

        assertEquals(
            ProviderReservationRecovery.UNRESOLVED,
            resolveProviderReservationFailure(
                rollbackProviderObject = { false },
                clearReservation = {
                    clearCalls += 1
                    true
                },
            ),
        )

        assertEquals(0, clearCalls)
    }

    @Test
    fun exactReservationMakesProviderRouteRecoverable() {
        val tracker = ProviderPublicationTracker()

        assertEquals(
            ProviderPublicationOutcome.NO_EXTERNAL_SIDE_EFFECT,
            tracker.outcome,
        )

        tracker.exactReservationEstablished()

        assertEquals(
            ProviderPublicationOutcome.EXACT_SIDE_EFFECT_DURABLE,
            tracker.outcome,
        )
    }

    @Test
    fun positivelyRolledBackReservationReleasesProviderRoute() {
        val tracker = ProviderPublicationTracker()
        tracker.exactReservationEstablished()

        tracker.rollbackProven()

        assertEquals(
            ProviderPublicationOutcome.NO_EXTERNAL_SIDE_EFFECT,
            tracker.outcome,
        )
    }

    @Test
    fun unresolvedProviderSideEffectDominatesAnyDurableReservationState() {
        val tracker = ProviderPublicationTracker()
        tracker.exactReservationEstablished()
        tracker.unresolvedSideEffect()
        tracker.rollbackProven()

        assertEquals(
            ProviderPublicationOutcome.UNRESOLVED_EXTERNAL_SIDE_EFFECT,
            tracker.outcome,
        )
    }
}
