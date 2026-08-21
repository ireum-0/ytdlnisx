package com.ireum.ytdl.util

import java.net.IDN
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object WebUrlInput {
    private val explicitScheme = Regex(
        "^([A-Za-z][A-Za-z0-9+.-]*)://",
        RegexOption.IGNORE_CASE,
    )
    private val extractorSchemes = setOf("http", "https")
    private val comparisonSchemes = extractorSchemes + "ftp"
    private val domainLabel = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    data class ExtractorInput(
        val originalValue: String,
        val dispatchValue: String,
        val sourceKey: String,
    )

    internal sealed class InputRoute {
        data class Extractor(val input: ExtractorInput) : InputRoute()
        data class UnsupportedExplicitScheme(val scheme: String) : InputRoute()
        object SearchQuery : InputRoute()
    }

    internal fun routeInput(value: String): InputRoute {
        resolveExtractorInput(value)?.let { return InputRoute.Extractor(it) }
        val scheme = explicitScheme.find(value.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
        return if (scheme == null) {
            InputRoute.SearchQuery
        } else {
            InputRoute.UnsupportedExplicitScheme(scheme)
        }
    }

    fun resolveExtractorInput(value: String): ExtractorInput? {
        val trimmed = value.trim()
        if (!isSupportedWebAddress(trimmed)) return null
        val dispatchValue = if (isSchemeLessWebAddress(trimmed)) {
            "https://$trimmed"
        } else {
            trimmed
        }
        return ExtractorInput(
            originalValue = value,
            dispatchValue = dispatchValue,
            sourceKey = sourceKey(dispatchValue) ?: return null,
        )
    }

    fun isSupportedWebAddress(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed.any(Char::isWhitespace)) return false
        if (explicitScheme.containsMatchIn(trimmed)) {
            val parsed = parse(trimmed, schemeLess = false) ?: return false
            return parsed.scheme in extractorSchemes
        }
        if (trimmed.contains("://")) return false
        return isSchemeLessWebAddress(trimmed)
    }

    fun isSchemeLessWebAddress(value: String): Boolean {
        val trimmed = value.trim()
        if (
            trimmed.isBlank() ||
            trimmed.any(Char::isWhitespace) ||
            explicitScheme.containsMatchIn(trimmed) ||
            trimmed.contains("://") ||
            trimmed.startsWith("//")
        ) {
            return false
        }
        return parse(trimmed, schemeLess = true) != null
    }

    /** Produces a comparison-only key without rewriting the caller's value. */
    fun sourceKey(value: String): String? {
        return sourceKey(value, dropNonIdentifyingParameters = false)
    }

    fun sourceIdentityKey(value: String): String? {
        return sourceKey(value, dropNonIdentifyingParameters = true)
    }

    /**
     * Produces a destructive-comparison key that keeps HTTP and HTTPS as
     * distinct endpoint schemes while retaining the normal canonicalization.
     */
    fun strictSourceIdentityKey(value: String): String? {
        return sourceKey(
            value,
            dropNonIdentifyingParameters = false,
            strictSchemeIdentity = true,
        )
    }

    private fun sourceKey(
        value: String,
        dropNonIdentifyingParameters: Boolean,
        strictSchemeIdentity: Boolean = false,
    ): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed.any(Char::isWhitespace)) return null
        val schemeLess = !explicitScheme.containsMatchIn(trimmed)
        val parsed = parse(trimmed, schemeLess) ?: return null
        val family = when {
            parsed.scheme == "ftp" -> "ftp"
            strictSchemeIdentity -> parsed.scheme
            else -> "web"
        }
        val defaultPort = when {
            family == "ftp" -> 21
            strictSchemeIdentity && parsed.scheme == "http" -> 80
            strictSchemeIdentity && parsed.scheme == "https" -> 443
            else -> null
        }
        val port = parsed.port.takeIf { port ->
            port >= 0 &&
                port != defaultPort &&
                !(family == "web" && port in setOf(80, 443))
        }
        val path = parsed.rawPath.orEmpty().ifBlank { "/" }
        val rawQuery = if (dropNonIdentifyingParameters) {
            parsed.rawQuery?.withoutNonIdentifyingParameters()
        } else {
            parsed.rawQuery
        }
        val query = rawQuery?.takeIf(String::isNotBlank)?.let { "?$it" }.orEmpty()
        return buildString {
            append(family)
            append("://")
            append(parsed.host)
            port?.let { append(":$it") }
            append(path)
            append(query)
        }
    }

    private fun String.withoutNonIdentifyingParameters(): String {
        return split('&').filterNot { parameter ->
            val rawName = parameter.substringBefore('=')
            val name = runCatching {
                URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
                    .lowercase(Locale.ROOT)
            }.getOrNull() ?: return@filterNot false
            name.startsWith("utm_") || name in NON_IDENTIFYING_QUERY_PARAMETERS
        }.joinToString("&")
    }

    private fun parse(value: String, schemeLess: Boolean): ParsedWebAddress? {
        val withScheme = if (schemeLess) "https://$value" else value
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in comparisonSchemes || uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (!isValidHost(host, allowLocalHost = !schemeLess)) return null
        return ParsedWebAddress(
            scheme = scheme,
            host = normalizeHost(host) ?: return null,
            port = uri.port,
            rawPath = uri.rawPath,
            rawQuery = uri.rawQuery,
        )
    }

    private fun isValidHost(host: String, allowLocalHost: Boolean): Boolean {
        if (host == "localhost") return allowLocalHost
        if (host.contains(':')) return allowLocalHost
        if (host.isIpv4Address()) return true

        val asciiHost = normalizeHost(host) ?: return false
        val labels = asciiHost.split('.')
        if (labels.size < 2 || labels.any { !domainLabel.matches(it) }) return false
        val topLevelDomain = labels.last()
        return topLevelDomain.startsWith("xn--") || topLevelDomain.all(Char::isLetter)
    }

    private fun normalizeHost(host: String): String? {
        return runCatching { IDN.toASCII(host).lowercase(Locale.ROOT) }
            .getOrNull()
            ?.takeIf { it.length <= 253 }
    }

    private fun String.isIpv4Address(): Boolean {
        val parts = split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() &&
                part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private data class ParsedWebAddress(
        val scheme: String,
        val host: String,
        val port: Int,
        val rawPath: String?,
        val rawQuery: String?,
    )

    private val NON_IDENTIFYING_QUERY_PARAMETERS = setOf(
        "fbclid",
        "gclid",
    )
}
