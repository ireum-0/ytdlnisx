package com.ireum.ytdl.util

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class MissingSourceDatePolicy {
    USE_DOWNLOAD_DATE,
    GROUP_FIRST,
    GROUP_LAST
}

object MediaPublishedDateSource {
    fun matches(firstUrl: String, secondUrl: String): Boolean {
        val secondKeys = matchingKeys(secondUrl).toHashSet()
        return matchingKeys(firstUrl).any(secondKeys::contains)
    }

    internal fun matchingKeys(value: String): List<MediaPublishedDateSourceKey> = buildList {
        add(MediaPublishedDateSourceKey(MediaPublishedDateSourceKeyKind.EXACT, value.trim()))
        WebUrlInput.sourceKey(value)?.let {
            add(MediaPublishedDateSourceKey(MediaPublishedDateSourceKeyKind.SOURCE, it))
        }
        WebUrlInput.sourceIdentityKey(value)?.let {
            add(MediaPublishedDateSourceKey(MediaPublishedDateSourceKeyKind.IDENTITY, it))
        }
        youtubeVideoId(value)?.let {
            add(MediaPublishedDateSourceKey(MediaPublishedDateSourceKeyKind.YOUTUBE_VIDEO, it))
        }
    }

    internal fun youtubeVideoId(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank() || trimmed.any(Char::isWhitespace)) return null
        val uri = runCatching {
            URI(if (trimmed.contains("://")) trimmed else "https://$trimmed")
        }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)

        if (host == "youtu.be" || host == "www.youtu.be") {
            return segments.firstOrNull()?.takeIf(::isYoutubeVideoId)
        }
        if (!isYoutubeHost(host)) return null

        uri.rawQuery.orEmpty().split('&').forEach { parameter ->
            val separator = parameter.indexOf('=')
            if (separator <= 0) return@forEach
            val name = decodeQueryComponent(parameter.substring(0, separator))
                ?: return@forEach
            if (name != "v") return@forEach
            val id = decodeQueryComponent(parameter.substring(separator + 1))
                ?: return@forEach
            if (isYoutubeVideoId(id)) return id
        }

        val markerIndex = segments.indexOfFirst { it in YOUTUBE_PATH_MARKERS }
        return segments.getOrNull(markerIndex + 1)
            ?.takeIf { markerIndex >= 0 && isYoutubeVideoId(it) }
    }

    private fun isYoutubeVideoId(value: String): Boolean {
        return value.matches(Regex("""^[A-Za-z0-9_-]{11}$"""))
    }

    private fun decodeQueryComponent(value: String): String? {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrNull()
    }

    internal fun isYoutubeHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT)
        return normalized in YOUTUBE_HOSTS ||
            normalized.endsWith(".youtube.com") ||
            normalized.endsWith(".youtube-nocookie.com")
    }

    private val YOUTUBE_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "gaming.youtube.com",
        "youtube-nocookie.com",
        "www.youtube-nocookie.com",
        "piped.video",
        "www.piped.video",
    )
    private val YOUTUBE_PATH_MARKERS = setOf("embed", "shorts", "live", "v")
}

internal enum class MediaPublishedDateSourceKeyKind {
    EXACT,
    SOURCE,
    IDENTITY,
    YOUTUBE_VIDEO,
}

internal data class MediaPublishedDateSourceKey(
    val kind: MediaPublishedDateSourceKeyKind,
    val value: String,
)

/** Source dates are persisted and compared as Unix epoch seconds. */
object MediaPublishedDate {
    const val MISSING = 0L

    fun isPresent(value: Long): Boolean = value != MISSING
}

/**
 * Normalizes the date fields exposed by extractors into Unix epoch seconds.
 *
 * Release information is preferred over upload/publication information when
 * both are present. Date-only values are anchored at midnight UTC so sorting
 * does not depend on the device time zone.
 */
object MediaPublishedDateParser {
    private const val MIN_REASONABLE_EPOCH_SECONDS = -62_135_596_800L // 0001-01-01T00:00:00Z
    private const val MAX_REASONABLE_EPOCH_SECONDS = 32_503_680_000L // 3000-01-01T00:00:00Z

    fun resolve(
        releaseTimestampSeconds: Number? = null,
        timestampSeconds: Number? = null,
        releaseDate: String? = null,
        uploadDate: String? = null,
        publishedAt: String? = null
    ): Long {
        return fromEpochSeconds(releaseTimestampSeconds)
            ?: parseDate(releaseDate)
            ?: fromEpochSeconds(timestampSeconds)
            ?: parseDate(publishedAt)
            ?: parseDate(uploadDate)
            ?: MediaPublishedDate.MISSING
    }

    /** Converts a source value that is explicitly defined as Unix seconds. */
    fun fromEpochSeconds(value: Number?): Long? {
        return value?.toLong()?.takeIf(::isReasonableEpoch)
    }

    /**
     * Converts a source value that is explicitly defined as Unix milliseconds.
     * Values whose second-precision representation is epoch zero remain absent
     * because zero is the persisted missing-value sentinel.
     */
    fun fromEpochMilliseconds(value: Number?): Long? {
        val milliseconds = value?.toLong() ?: return null
        return Math.floorDiv(milliseconds, 1000L).takeIf(::isReasonableEpoch)
    }

    fun parseDate(value: String?): Long? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) return null

        if (normalized.length == 8 && normalized.all(Char::isDigit)) {
            return runCatching {
                LocalDate.parse(normalized, DateTimeFormatter.BASIC_ISO_DATE)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toEpochSecond()
            }.getOrNull()?.takeIf(::isReasonableEpoch)
        }

        val parsed = runCatching { Instant.parse(normalized).epochSecond }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(normalized).toEpochSecond() }.getOrNull()
            ?: runCatching {
                LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toEpochSecond()
            }.getOrNull()

        return parsed?.takeIf(::isReasonableEpoch)
    }

    private fun isReasonableEpoch(value: Long): Boolean {
        return MediaPublishedDate.isPresent(value) &&
            value >= MIN_REASONABLE_EPOCH_SECONDS &&
            value < MAX_REASONABLE_EPOCH_SECONDS
    }
}

object MediaPublishedDateOrder {
    fun <T> sorted(
        items: List<T>,
        policy: MissingSourceDatePolicy,
        descending: Boolean,
        mediaPublishedAt: (T) -> Long,
        downloadTime: (T) -> Long,
        stableId: (T) -> Long
    ): List<T> {
        return items.sortedWith { left, right ->
            val leftPublishedAt = mediaPublishedAt(left)
            val rightPublishedAt = mediaPublishedAt(right)
            val groupComparison = groupRank(leftPublishedAt, policy)
                .compareTo(groupRank(rightPublishedAt, policy))
            if (groupComparison != 0) {
                groupComparison
            } else {
                val leftEffectiveDate = effectiveDate(leftPublishedAt, downloadTime(left))
                val rightEffectiveDate = effectiveDate(rightPublishedAt, downloadTime(right))
                compareInOrder(leftEffectiveDate, rightEffectiveDate, descending)
                    .takeIf { it != 0 }
                    ?: compareInOrder(downloadTime(left), downloadTime(right), descending)
                        .takeIf { it != 0 }
                    ?: compareInOrder(stableId(left), stableId(right), descending)
            }
        }
    }

    fun sqlOrderBy(
        policy: MissingSourceDatePolicy,
        descending: Boolean,
        mediaPublishedAtColumn: String = "mediaPublishedAt",
        downloadTimeColumn: String = "time",
        stableIdColumn: String = "id"
    ): String {
        val direction = if (descending) "DESC" else "ASC"
        val groupClause = when (policy) {
            MissingSourceDatePolicy.USE_DOWNLOAD_DATE -> null
            MissingSourceDatePolicy.GROUP_FIRST ->
                "CASE WHEN $mediaPublishedAtColumn != 0 THEN 1 ELSE 0 END ASC"
            MissingSourceDatePolicy.GROUP_LAST ->
                "CASE WHEN $mediaPublishedAtColumn != 0 THEN 0 ELSE 1 END ASC"
        }
        val effectiveDate =
            "CASE WHEN $mediaPublishedAtColumn != 0 THEN $mediaPublishedAtColumn ELSE $downloadTimeColumn END"
        return listOfNotNull(
            groupClause,
            "$effectiveDate $direction",
            "$downloadTimeColumn $direction",
            "$stableIdColumn $direction"
        ).joinToString(", ")
    }

    private fun groupRank(mediaPublishedAt: Long, policy: MissingSourceDatePolicy): Int {
        val hasSourceDate = MediaPublishedDate.isPresent(mediaPublishedAt)
        return when (policy) {
            MissingSourceDatePolicy.USE_DOWNLOAD_DATE -> 0
            MissingSourceDatePolicy.GROUP_FIRST -> if (hasSourceDate) 1 else 0
            MissingSourceDatePolicy.GROUP_LAST -> if (hasSourceDate) 0 else 1
        }
    }

    private fun effectiveDate(mediaPublishedAt: Long, downloadTime: Long): Long {
        return mediaPublishedAt.takeIf(MediaPublishedDate::isPresent) ?: downloadTime
    }

    private fun compareInOrder(left: Long, right: Long, descending: Boolean): Int {
        return if (descending) right.compareTo(left) else left.compareTo(right)
    }
}

class MediaPublishedDateBackfillTracker {
    var updatedCount: Int = 0
        private set

    private var refreshDispatched = false

    fun recordUpdate(updated: Boolean) {
        if (updated) updatedCount += 1
    }

    fun dispatchRefreshIfNeeded(refresh: () -> Unit) {
        if (updatedCount == 0 || refreshDispatched) return
        refreshDispatched = true
        refresh()
    }
}
