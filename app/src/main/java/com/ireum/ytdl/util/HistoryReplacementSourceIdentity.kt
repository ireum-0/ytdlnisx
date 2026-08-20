package com.ireum.ytdl.util

/**
 * Source identity used only at the destructive History replacement boundary.
 * Generic HTTP and HTTPS endpoints remain distinct; stable provider identities
 * such as a YouTube video ID may still establish equivalence.
 */
object HistoryReplacementSourceIdentity {
    fun matches(firstUrl: String, secondUrl: String): Boolean {
        val first = firstUrl.trim()
        val second = secondUrl.trim()
        if (first.isBlank() || second.isBlank()) return false
        if (first == second) return true

        val firstYoutubeId = MediaPublishedDateSource.youtubeVideoId(first)
        val secondYoutubeId = MediaPublishedDateSource.youtubeVideoId(second)
        if (firstYoutubeId != null || secondYoutubeId != null) {
            return firstYoutubeId != null && firstYoutubeId == secondYoutubeId
        }

        val firstKey = WebUrlInput.strictSourceIdentityKey(first) ?: return false
        return firstKey == WebUrlInput.strictSourceIdentityKey(second)
    }
}
