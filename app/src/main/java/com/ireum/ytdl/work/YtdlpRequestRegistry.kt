package com.ireum.ytdl.work

internal class YtdlpPreparationRequestOwner<Request>(
    private val cleanupRequest: (Request) -> Unit,
) {
    private val registeredRequests = mutableListOf<Request>()

    fun register(request: Request) {
        registeredRequests += request
    }

    fun snapshot(): List<Request> = registeredRequests.toList()

    fun cleanup() {
        val requestsToCleanup = registeredRequests.toList()
        registeredRequests.clear()
        requestsToCleanup.forEach { request ->
            runCatching { cleanupRequest(request) }
        }
    }
}

internal fun <Request> cleanupYtdlpPreparationAndRethrow(
    requestOwner: YtdlpPreparationRequestOwner<Request>,
    error: Throwable,
): Nothing {
    requestOwner.cleanup()
    throw error
}
