package com.ireum.ytdl.database.cookies

/** Opaque result identity shared by WebView acquisition and its retry callers. */
internal object CookieAcquisitionHandoff {
    const val EXTRA_REQUEST_ID = "com.ireum.ytdl.cookie.REQUEST_ID"
    const val EXTRA_PROJECTION_GENERATION = "com.ireum.ytdl.cookie.PROJECTION_GENERATION"

    fun matches(
        expectedRequestId: String?,
        resultRequestId: String?,
        projectionGeneration: String?,
    ): Boolean = !expectedRequestId.isNullOrBlank() &&
        expectedRequestId == resultRequestId &&
        !projectionGeneration.isNullOrBlank()
}
