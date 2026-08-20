package com.ireum.ytdl.util.download

import kotlinx.coroutines.CancellationException

/**
 * Runs a notification side effect without allowing ordinary notification failures
 * to replace the authoritative download outcome. Cancellation remains cooperative.
 */
internal inline fun <T> runNonAuthoritativeNotificationSideEffect(
    action: () -> T,
    onFailure: (Exception) -> Unit,
): T? = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    onFailure(failure)
    null
}
