package com.ireum.ytdl.util

import java.util.Locale

object SubtitleLanguageMatcher {

    fun hasRequestedSubtitle(availableSubtitles: List<String>, subsLanguages: String): Boolean {
        if (availableSubtitles.isEmpty()) return false

        val languages = availableSubtitles
            .map { it.trim().lowercase(Locale.getDefault()) }
            .filter { it.isNotBlank() }

        if (languages.isEmpty()) return false

        val tokens = subsLanguages
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val includeTokens = tokens.filterNot { it.startsWith("-") }
        val excludeTokens = tokens
            .filter { it.startsWith("-") }
            .map { it.removePrefix("-") }
            .filter { it.isNotBlank() }

        return languages.any { lang ->
            val included = if (includeTokens.isEmpty()) {
                true
            } else {
                includeTokens.any { tokenMatches(it, lang) }
            }

            val excluded = excludeTokens.any { tokenMatches(it, lang) }
            included && !excluded
        }
    }

    private fun tokenMatches(token: String, language: String): Boolean {
        val normalizedToken = token.trim()
        if (normalizedToken.equals("all", ignoreCase = true)) return true
        if (normalizedToken.isBlank()) return false

        val regexPattern = normalizedToken.replace("*", ".*")
        val compiled = runCatching {
            Regex("^$regexPattern$", RegexOption.IGNORE_CASE)
        }.getOrElse {
            Regex(
                "^${Regex.escape(normalizedToken).replace("\\*", ".*")}$",
                RegexOption.IGNORE_CASE
            )
        }
        return compiled.matches(language)
    }
}

