package com.ireum.ytdl.util

import java.io.IOException

/**
 * Tracks provider side effects during one move operation.  A provider URI is
 * opaque: once a create call has returned, a failed journal callback cannot
 * be treated as an ordinary route failure unless the exact object was
 * positively rolled back.
 */
internal enum class ProviderPublicationOutcome {
    NO_EXTERNAL_SIDE_EFFECT,
    EXACT_SIDE_EFFECT_DURABLE,
    UNRESOLVED_EXTERNAL_SIDE_EFFECT,
}

/**
 * Result of trying to unwind an exact provider object after its destination
 * reservation could not be persisted.  Both the provider delete and the
 * corresponding reservation clear must be acknowledged before the caller may
 * safely try another publication route.
 */
internal enum class ProviderReservationRecovery {
    ROLLED_BACK,
    UNRESOLVED,
}

internal fun resolveProviderReservationFailure(
    rollbackProviderObject: () -> Boolean,
    clearReservation: () -> Boolean,
): ProviderReservationRecovery {
    if (!runCatching { rollbackProviderObject() }.getOrDefault(false)) {
        return ProviderReservationRecovery.UNRESOLVED
    }
    return if (runCatching { clearReservation() }.getOrDefault(false)) {
        ProviderReservationRecovery.ROLLED_BACK
    } else {
        ProviderReservationRecovery.UNRESOLVED
    }
}

internal class ProviderPublicationTracker {
    private var durableReservationCount = 0
    private var unresolved = false

    val outcome: ProviderPublicationOutcome
        get() = when {
            unresolved -> ProviderPublicationOutcome.UNRESOLVED_EXTERNAL_SIDE_EFFECT
            durableReservationCount > 0 -> ProviderPublicationOutcome.EXACT_SIDE_EFFECT_DURABLE
            else -> ProviderPublicationOutcome.NO_EXTERNAL_SIDE_EFFECT
        }

    fun exactReservationEstablished() {
        durableReservationCount += 1
    }

    fun rollbackProven() {
        if (durableReservationCount > 0) durableReservationCount -= 1
    }

    fun unresolvedSideEffect() {
        unresolved = true
    }
}

/** Provider mutation crossed an opaque boundary without a durable exact URI. */
internal class UnresolvedProviderPublicationException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
