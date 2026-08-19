package com.ireum.ytdl.util

import android.net.Uri
import java.util.Locale
import java.util.regex.Pattern

object LinkUtil {
    private val urlPattern = Pattern.compile("""(?i)\b(?:https?|ftp)://[^\s<>"']+""")
    private val youtubeHosts = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "gaming.youtube.com",
        "youtube-nocookie.com",
        "www.youtube-nocookie.com",
        "youtu.be",
        "www.youtu.be",
        "piped.video",
        "www.piped.video"
    )

    fun extractFirstUrl(text: String): String {
        val matcher = urlPattern.matcher(text)
        return if (matcher.find()) {
            trimSharedUrl(matcher.group())
        } else {
            text.trim()
        }
    }

    fun isUrl(value: String): Boolean {
        return urlPattern.matcher(value.trim()).matches()
    }

    fun isExtractorInput(value: String): Boolean {
        return WebUrlInput.resolveExtractorInput(value) != null
    }

    fun isYoutubeUrl(value: String): Boolean {
        val uri = parseLenientUri(value) ?: return false
        val host = uri.host.normalizedHost() ?: return false
        return host in youtubeHosts || host.endsWith(".youtube.com")
    }

    fun isYoutubeChannelUrl(value: String): Boolean {
        val uri = parseLenientUri(value) ?: return false
        val host = uri.host.normalizedHost() ?: return false
        if (host !in youtubeHosts && !host.endsWith(".youtube.com")) return false
        val segments = uri.pathSegments
        if (segments.isEmpty()) return false
        return segments.first().startsWith("@") ||
            segments.first() in setOf("channel", "c", "user")
    }

    fun isYoutubeWatchVideosUrl(value: String): Boolean {
        val uri = parseLenientUri(value) ?: return false
        val host = uri.host.normalizedHost() ?: return false
        if (host !in youtubeHosts && !host.endsWith(".youtube.com")) return false
        return uri.path == "/watch_videos" && !uri.getQueryParameter("video_ids").isNullOrBlank()
    }

    fun getYoutubeVideoId(value: String): String? {
        val uri = parseLenientUri(value) ?: return null
        val host = uri.host.normalizedHost() ?: return null
        val segments = uri.pathSegments

        if (host == "youtu.be" || host == "www.youtu.be") {
            return segments.firstOrNull()?.takeIf { it.isYoutubeVideoId() }
        }

        if (host !in youtubeHosts && !host.endsWith(".youtube.com")) return null

        uri.getQueryParameter("v")
            ?.takeIf { it.isYoutubeVideoId() }
            ?.let { return it }

        val markerIndex = segments.indexOfFirst { it in setOf("embed", "shorts", "live", "v") }
        if (markerIndex >= 0) {
            return segments.getOrNull(markerIndex + 1)?.takeIf { it.isYoutubeVideoId() }
        }

        return null
    }

    fun canonicalYoutubeVideoUrlOrSelf(value: String): String {
        val trimmed = value.trim()
        val id = getYoutubeVideoId(trimmed) ?: return trimmed
        return "https://youtu.be/$id"
    }

    fun equivalentYoutubeVideoUrls(value: String): List<String> {
        val canonical = canonicalYoutubeVideoUrlOrSelf(value)
        val id = getYoutubeVideoId(canonical) ?: return listOf(value.trim())
        return listOf(
            "https://youtu.be/$id",
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id"
        ).distinct()
    }

    private fun parseLenientUri(value: String): Uri? {
        val trimmed = trimSharedUrl(value.trim())
        if (trimmed.isBlank() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return runCatching { Uri.parse(withScheme) }.getOrNull()
            ?.takeIf { !it.host.isNullOrBlank() }
    }

    private fun trimSharedUrl(value: String): String {
        return value.trim().trimEnd('.', ',', ';', '!', '?', '"', '\'', ')', ']', '}')
    }

    private fun String?.normalizedHost(): String? {
        return this?.lowercase(Locale.ROOT)
    }

    private fun String.isYoutubeVideoId(): Boolean {
        return matches(Regex("""^[A-Za-z0-9_-]{11}$"""))
    }
}
