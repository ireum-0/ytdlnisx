package com.ireum.ytdl.util

import java.io.File
import java.util.Locale

object SubtitleSelection {
    private val ignoredFallbackTokens = setOf("all", "*", ".*", ".*-orig")

    data class Request(
        val subLanguages: String,
        val liveChatOnly: Boolean,
        val ignoredLiveChat: Boolean,
        val requestedLanguages: List<String>
    )

    fun normalize(subsLanguages: String): Request {
        val tokens = subsLanguages
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("-") }

        val liveChatSelected = tokens.any { it.equals("live_chat", ignoreCase = true) }
        val languageTokens = tokens
            .filterNot { it.equals("live_chat", ignoreCase = true) }
            .filterNot { ignoredFallbackTokens.contains(it.lowercase(Locale.US)) }
            .filterNot { it.lowercase(Locale.US).endsWith("-orig") }
            .distinct()

        if (languageTokens.isEmpty() && liveChatSelected) {
            return Request("live_chat", liveChatOnly = true, ignoredLiveChat = false, requestedLanguages = listOf("live_chat"))
        }

        val expanded = languageTokens
            .ifEmpty { listOf("en") }
            .flatMap { expandLanguageToken(it) }
            .distinct()

        return Request(
            subLanguages = expanded.joinToString(","),
            liveChatOnly = false,
            ignoredLiveChat = liveChatSelected,
            requestedLanguages = expanded
        )
    }

    fun isAutomaticCaption(languageCode: String?, vssId: String?, kind: String?): Boolean {
        val normalizedKind = kind.orEmpty().trim().lowercase(Locale.US)
        val normalizedVssId = vssId.orEmpty().trim().lowercase(Locale.US)
        val normalizedLanguage = languageCode.orEmpty().trim().lowercase(Locale.US)
        return normalizedKind == "asr" ||
            normalizedVssId.startsWith("a.") ||
            normalizedLanguage.startsWith("a.")
    }

    fun isSelectedSubtitleFile(file: File, request: Request): Boolean {
        val name = file.name.lowercase(Locale.US)
        if (request.liveChatOnly) {
            return name.contains(".live_chat.") || name.contains("live_chat")
        }
        if (name.contains(".live_chat.") || name.contains("live_chat")) return false

        val language = inferLanguageFromSubtitleFile(file) ?: return true
        return SubtitleLanguageMatcher.hasRequestedSubtitle(listOf(language), request.subLanguages)
    }

    private fun expandLanguageToken(token: String): List<String> {
        val normalized = token.trim()
        if (normalized.contains("*")) return listOf(normalized)

        val plainLanguage = normalized.matches(Regex("^[A-Za-z]{2,3}$"))
        return if (plainLanguage) {
            listOf("${normalized}.*", normalized)
        } else {
            listOf(normalized)
        }
    }

    private fun inferLanguageFromSubtitleFile(file: File): String? {
        val stem = file.nameWithoutExtension
        val candidate = stem.substringAfterLast('.', "")
        if (candidate.isBlank()) return null
        if (candidate.equals("live_chat", ignoreCase = true)) return "live_chat"
        return candidate.takeIf {
            it.matches(Regex("^[A-Za-z]{2,3}([_-][A-Za-z0-9]+)?(\\.[A-Za-z0-9_-]+)?$"))
        }?.replace('_', '-')
    }
}
