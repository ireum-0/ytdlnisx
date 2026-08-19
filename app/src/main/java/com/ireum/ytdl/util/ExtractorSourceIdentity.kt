package com.ireum.ytdl.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class ExtractorSourceIdentity(
    val requestedSource: String = "",
    val originalUrl: String = "",
    val canonicalUrl: String = "",
    val fallbackUrl: String = "",
    val stableMediaId: String = "",
    val extractor: String = "",
    val resultType: String = "",
    val playlistUrl: String = "",
) {
    fun trustedUrls(): List<String> {
        return listOf(originalUrl, canonicalUrl, fallbackUrl, playlistUrl)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }
}

/** Validates extractor provenance without treating the caller's request as evidence. */
object ExtractorSourceIdentityPolicy {
    fun matchesRequestedSource(
        requestedSource: String,
        identity: ExtractorSourceIdentity,
    ): Boolean {
        if (WebUrlInput.resolveExtractorInput(requestedSource) == null) return false
        if (
            identity.requestedSource.isNotBlank() &&
            !MediaPublishedDateSource.matches(requestedSource, identity.requestedSource)
        ) return false
        val trustedUrls = identity.trustedUrls()
        val requestedYoutubeId = MediaPublishedDateSource.youtubeVideoId(requestedSource)
        if (requestedYoutubeId != null) {
            return trustedUrls.any {
                MediaPublishedDateSource.youtubeVideoId(it) == requestedYoutubeId
            } ||
                isYoutubeExtractor(identity.extractor) && identity.stableMediaId == requestedYoutubeId
        }

        val requestedPlaylistId = youtubePlaylistId(requestedSource)
        if (requestedPlaylistId != null) {
            if (!identity.resultType.equals("playlist", ignoreCase = true)) return false
            return trustedUrls.any { youtubePlaylistId(it) == requestedPlaylistId }
        }

        if (isYoutubeChannelUrl(requestedSource)) {
            return trustedUrls.any { candidate ->
                isYoutubeChannelUrl(candidate) &&
                    MediaPublishedDateSource.matches(requestedSource, candidate)
            }
        }

        if (
            identity.playlistUrl.isNotBlank() &&
            MediaPublishedDateSource.matches(requestedSource, identity.playlistUrl) &&
            !identity.resultType.equals("playlist", ignoreCase = true)
        ) return false

        if (trustedUrls.any { MediaPublishedDateSource.matches(requestedSource, it) }) {
            return true
        }
        return matchesKnownStableProviderRedirect(requestedSource, identity, trustedUrls)
    }

    private fun matchesKnownStableProviderRedirect(
        requestedSource: String,
        identity: ExtractorSourceIdentity,
        trustedUrls: List<String>,
    ): Boolean {
        val stableId = identity.stableMediaId.takeIf(String::isNotBlank) ?: return false
        if (!identity.extractor.contains("vimeo", ignoreCase = true)) return false
        val requested = parsedWebUrl(requestedSource) ?: return false
        if (!requested.host.isVimeoHost() || stableId !in requested.pathSegments) return false
        return trustedUrls.any { candidate ->
            parsedWebUrl(candidate)?.let { parsed ->
                parsed.host.isVimeoHost() && stableId in parsed.pathSegments
            } == true
        }
    }

    private fun youtubePlaylistId(value: String): String? {
        val parsed = parsedWebUrl(value) ?: return null
        if (
            !MediaPublishedDateSource.isYoutubeHost(parsed.host) ||
            MediaPublishedDateSource.youtubeVideoId(value) != null
        ) return null
        return parsed.queryParameters["list"]?.takeIf(String::isNotBlank)
    }

    private fun isYoutubeChannelUrl(value: String): Boolean {
        val parsed = parsedWebUrl(value) ?: return false
        if (!MediaPublishedDateSource.isYoutubeHost(parsed.host)) return false
        val firstSegment = parsed.pathSegments.firstOrNull() ?: return false
        return firstSegment.startsWith("@") || firstSegment in setOf("channel", "c", "user")
    }

    private fun parsedWebUrl(value: String): ParsedWebUrl? {
        val dispatch = WebUrlInput.resolveExtractorInput(value)?.dispatchValue ?: return null
        val uri = runCatching { URI(dispatch) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val queryParameters = uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
            if (parameter.isBlank()) return@mapNotNull null
            val separator = parameter.indexOf('=')
            val rawName = if (separator >= 0) parameter.substring(0, separator) else parameter
            val rawValue = if (separator >= 0) parameter.substring(separator + 1) else ""
            val name = decode(rawName) ?: return@mapNotNull null
            val decodedValue = decode(rawValue) ?: return@mapNotNull null
            name to decodedValue
        }.toMap()
        return ParsedWebUrl(
            host = host,
            pathSegments = uri.rawPath.orEmpty().split('/').filter(String::isNotBlank),
            queryParameters = queryParameters,
        )
    }

    private fun decode(value: String): String? {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrNull()
    }

    private fun String.isVimeoHost(): Boolean {
        return this == "vimeo.com" || endsWith(".vimeo.com")
    }

    private fun isYoutubeExtractor(value: String): Boolean {
        return value.contains("youtube", ignoreCase = true)
    }

    private data class ParsedWebUrl(
        val host: String,
        val pathSegments: List<String>,
        val queryParameters: Map<String, String>,
    )
}
