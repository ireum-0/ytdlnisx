package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.repository.ResultRepository
import java.util.Locale
import kotlin.math.abs

data class LocalMatchResult(
    val item: ResultItem,
    val titleSimilarity: Float,
    val durationDiffSeconds: Int,
    val exactTitleMatch: Boolean = false
)

object LocalMatchUtil {
    fun extractTitleAndAuthorHint(value: String): Pair<String, String> {
        val trimmed = value.trim()
        val parts = trimmed.split(" - ", limit = 2)
        if (parts.size == 2) {
            val left = parts[0].trim()
            val right = parts[1].trim()
            if (left.isNotBlank() && right.isNotBlank() && !left.equals("y2mate.com", ignoreCase = true)) {
                return Pair(right, left)
            }
        }
        return Pair(trimmed, "")
    }

    fun normalizeTitle(value: String): String {
        return value
            .lowercase(Locale.getDefault())
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    fun titleSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 0f
        val dist = levenshteinDistance(a, b)
        return 1f - (dist.toFloat() / maxLen.toFloat())
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val rows = a.length + 1
        val cols = b.length + 1
        val dp = IntArray(cols) { it }
        for (i in 1 until rows) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1 until cols) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + cost
                )
                prev = temp
            }
        }
        return dp[cols - 1]
    }

    fun parseDurationSeconds(duration: String): Int {
        if (duration.isBlank()) return 0
        val parts = duration.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return 0
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0
        }
    }

    suspend fun findYoutubeMatch(
        resultRepository: ResultRepository,
        title: String,
        durationSeconds: Int
    ): LocalMatchResult? {
        if (title.isBlank()) return null
        val (searchQuery, expectedAuthor) = extractTitleAndAuthorHint(title)
        val results = resultRepository.search(searchQuery, resetResults = false, addToResults = false)
        if (results.isEmpty()) return null
        val normalizedQuery = normalizeTitle(searchQuery)
        val first = results.first()
        val firstTitle = normalizeTitle(first.title)
        if (normalizedQuery.isNotBlank() && normalizedQuery == firstTitle) {
            val firstSeconds = parseDurationSeconds(first.duration)
            val firstDiff = if (durationSeconds > 0 && firstSeconds > 0) {
                abs(firstSeconds - durationSeconds)
            } else {
                Int.MAX_VALUE
            }
            return LocalMatchResult(first, 1f, firstDiff, exactTitleMatch = true)
        }
        val normalizedTitle = normalizedQuery
        if (normalizedTitle.length < 4) return null
        val candidates = results.mapNotNull { item ->
            val itemTitle = normalizeTitle(item.title)
            if (itemTitle.isBlank()) return@mapNotNull null
            val titleSim = titleSimilarity(normalizedTitle, itemTitle)
            if (expectedAuthor.isNotBlank()) {
                val authorSim = titleSimilarity(normalizeTitle(expectedAuthor), normalizeTitle(item.author))
                if (authorSim < 0.8f) return@mapNotNull null
            }
            val itemSeconds = parseDurationSeconds(item.duration)
            val durationDiff = if (durationSeconds > 0 && itemSeconds > 0) {
                abs(itemSeconds - durationSeconds)
            } else {
                Int.MAX_VALUE
            }
            val durationOk = durationSeconds > 0 && itemSeconds > 0 && durationDiff <= 5
            val titleOk = titleSim >= 0.85f
            if (durationSeconds > 0) {
                if (!durationOk || !titleOk) return@mapNotNull null
            } else {
                if (titleSim < 0.92f) return@mapNotNull null
            }
            LocalMatchResult(item, titleSim, durationDiff)
        }
        return candidates.maxByOrNull { match ->
            val durationPenalty = if (match.durationDiffSeconds == Int.MAX_VALUE) 0f else (match.durationDiffSeconds / 60f)
            match.titleSimilarity - durationPenalty
        }
    }
}
