package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import java.net.URI
import java.util.Locale

/**
 * Destructive-grade identity for automatic History duplicate selection.
 *
 * A title is deliberately absent from this contract.  The only accepted
 * identities are a stable official YouTube video identity or a strictly
 * canonical HTTP(S) source URL.  Values which cannot be proven to be one of
 * those source classes are not eligible for automatic grouping.
 */
internal object HistoryDuplicateIdentity {
    enum class SourceKind {
        YOUTUBE_VIDEO,
        WEB_URL,
    }

    data class Key(
        val type: DownloadType,
        val sourceKind: SourceKind,
        val sourceValue: String,
    )

    fun key(type: DownloadType, source: String): Key? {
        val trimmed = source.trim()
        if (trimmed.isBlank() || !WebUrlInput.isSupportedWebAddress(trimmed)) return null

        val youtubeId = MediaPublishedDateSource.youtubeVideoId(trimmed)
        if (isOfficialYoutubeSource(trimmed)) {
            // A recognized YouTube host without a valid stable video identity
            // is ambiguous (playlist/channel/search/etc.) at this destructive
            // boundary.  Do not fall back to URL equality for it.
            return youtubeId?.let { id ->
                Key(type, SourceKind.YOUTUBE_VIDEO, id)
            }
        }

        // Piped/other providers and generic web sources remain host-, scheme-,
        // path-, query-, and fragment-sensitive.  In particular HTTP and
        // HTTPS are not collapsed, and meaningful query parameters remain
        // identifying.
        return WebUrlInput.strictSourceIdentityKeyPreservingFragment(trimmed)?.let { strictKey ->
            Key(type, SourceKind.WEB_URL, strictKey)
        }
    }

    fun matches(
        firstType: DownloadType,
        firstSource: String,
        secondType: DownloadType,
        secondSource: String,
    ): Boolean = key(firstType, firstSource) == key(secondType, secondSource)

    private fun isOfficialYoutubeSource(value: String): Boolean {
        val dispatchValue = if (value.contains("://")) value else "https://$value"
        val host = runCatching { URI(dispatchValue).host?.lowercase(Locale.ROOT) }.getOrNull()
            ?: return false
        return host == "youtu.be" ||
            host == "www.youtu.be" ||
            host.endsWith(".youtube.com") ||
            host.endsWith(".youtube-nocookie.com") ||
            host == "youtube.com" ||
            host == "youtube-nocookie.com"
    }
}
