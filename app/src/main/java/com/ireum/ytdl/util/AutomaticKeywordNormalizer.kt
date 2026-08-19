package com.ireum.ytdl.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object AutomaticKeywordNormalizer {
    fun normalizeKeyword(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun parseKeywords(value: String): List<String> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<String>()
        value.split(',', '\n', '，').forEach { raw ->
            val display = raw.trim().replace(Regex("\\s+"), " ")
            val normalized = normalizeKeyword(display)
            if (normalized.isNotBlank() && seen.add(normalized)) result += display
        }
        return result
    }

    fun playlistConditionKey(value: String): String? {
        val uri = normalizedHttpUri(value) ?: return null
        val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        if (host == "youtube.com" || host == "m.youtube.com" || host == "music.youtube.com") {
            val playlistId = queryParameters(uri.rawQuery)["list"]?.firstOrNull()?.trim().orEmpty()
            if (playlistId.isBlank()) return null
            return "youtube:playlist:$playlistId"
        }
        return genericUrlKey(uri)
    }

    fun canonicalPlaylistUrl(value: String): String? {
        val uri = normalizedHttpUri(value) ?: return null
        val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        if (host == "youtube.com" || host == "m.youtube.com" || host == "music.youtube.com") {
            val playlistId = queryParameters(uri.rawQuery)["list"]?.firstOrNull()?.trim().orEmpty()
            if (playlistId.isBlank()) return null
            return "https://www.youtube.com/playlist?list=$playlistId"
        }
        return value.trim()
    }

    fun videoKey(value: String): String {
        val uri = normalizedHttpUri(value) ?: return value.trim()
        val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        if (host == "youtu.be") {
            val id = uri.path.orEmpty().trim('/').substringBefore('/').trim()
            if (id.isNotBlank()) return "youtube:video:$id"
        }
        if (host == "youtube.com" || host == "m.youtube.com" || host == "music.youtube.com") {
            val videoId = queryParameters(uri.rawQuery)["v"]?.firstOrNull()?.trim().orEmpty()
            if (videoId.isNotBlank()) return "youtube:video:$videoId"
            val parts = uri.path.orEmpty().trim('/').split('/')
            if (parts.size >= 2 && parts.first() in setOf("shorts", "live", "embed")) {
                return "youtube:video:${parts[1]}"
            }
        }
        return genericUrlKey(uri)
    }

    private fun normalizedHttpUri(value: String): URI? {
        val candidate = value.trim()
        if (candidate.isBlank()) return null
        return runCatching { URI(candidate) }.getOrNull()?.takeIf {
            (it.scheme.equals("http", true) || it.scheme.equals("https", true)) &&
                !it.host.isNullOrBlank()
        }
    }

    private fun genericUrlKey(uri: URI): String {
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = uri.path.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
        val query = uri.rawQuery?.takeIf(String::isNotBlank)?.let { "?$it" }.orEmpty()
        return "${uri.scheme.lowercase(Locale.ROOT)}://$host$port$path$query"
    }

    private fun queryParameters(rawQuery: String?): Map<String, List<String>> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            val key = decode(pair[0])
            if (key.isBlank()) null else key to decode(pair.getOrElse(1) { "" })
        }.groupBy({ it.first }, { it.second })
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
