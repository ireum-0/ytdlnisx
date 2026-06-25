package com.ireum.ytdl.util

import java.util.Locale

object WebsiteUtil {
    private const val FILTER_SEPARATOR = "\u001F"

    fun canonicalName(value: String): String {
        val trimmed = value.trim()
        return when (trimmed.lowercase(Locale.ROOT)) {
            "youtube" -> "YouTube"
            else -> trimmed
        }
    }

    fun normalizeNames(values: Iterable<String>): List<String> {
        val namesByKey = linkedMapOf<String, String>()
        values.forEach { value ->
            val canonical = canonicalName(value)
            if (canonical.isNotBlank()) {
                namesByKey.putIfAbsent(canonical.lowercase(Locale.ROOT), canonical)
            }
        }
        return namesByKey.values.sortedBy { it.lowercase(Locale.ROOT) }
    }

    fun encodeFilter(values: Iterable<String>): String =
        normalizeNames(values).joinToString(FILTER_SEPARATOR)

    fun decodeFilter(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return normalizeNames(value.split(FILTER_SEPARATOR))
    }

    fun comparisonKey(value: String): String = canonicalName(value).lowercase(Locale.ROOT)
}
