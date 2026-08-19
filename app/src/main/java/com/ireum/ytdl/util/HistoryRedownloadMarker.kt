package com.ireum.ytdl.util

data class HistoryRedownloadMarker(
    val historyId: Long,
    val expectedMinimumHeight: Int? = null
) {
    init {
        require(historyId > 0L)
        require(expectedMinimumHeight == null || expectedMinimumHeight > 0)
    }

    val isQualityReplacement: Boolean
        get() = expectedMinimumHeight != null

    fun encode(): String = if (expectedMinimumHeight == null) {
        "$PREFIX$historyId"
    } else {
        "$PREFIX$historyId:$QUALITY_SEGMENT:$expectedMinimumHeight"
    }

    companion object {
        const val PREFIX = "history-redownload:"
        private const val QUALITY_SEGMENT = "quality"

        fun regular(historyId: Long): String = HistoryRedownloadMarker(historyId).encode()

        fun quality(historyId: Long, expectedMinimumHeight: Int): String =
            HistoryRedownloadMarker(historyId, expectedMinimumHeight).encode()

        fun parse(value: String?): HistoryRedownloadMarker? {
            val suffix = value?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
            val parts = suffix.split(':')
            val historyId = parts.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0L } ?: return null
            return when {
                parts.size == 1 -> HistoryRedownloadMarker(historyId)
                parts.size == 3 && parts[1] == QUALITY_SEGMENT -> {
                    val expectedHeight = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
                    HistoryRedownloadMarker(historyId, expectedHeight)
                }
                else -> null
            }
        }
    }
}

object HistoryReplacementFilePolicy {
    fun originalPathsToDelete(previousPaths: List<String>, replacementPaths: List<String>): List<String> {
        val replacements = normalized(replacementPaths).toSet()
        if (replacements.isEmpty()) return emptyList()
        return normalized(previousPaths).filterNot(replacements::contains)
    }

    fun rejectedPathsToDelete(previousPaths: List<String>, candidatePaths: List<String>): List<String> {
        val originals = normalized(previousPaths).toSet()
        return normalized(candidatePaths).filterNot(originals::contains)
    }

    private fun normalized(paths: List<String>): List<String> {
        return paths.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
    }
}

object HistoryRedownloadQueuePolicy {
    fun shouldUseDownloadArchive(playlistOrMarker: String?): Boolean {
        return HistoryRedownloadMarker.parse(playlistOrMarker) == null
    }

    fun isDuplicate(
        markerValue: String?,
        pendingMarkerValues: Iterable<String?>,
        seenHistoryIds: MutableSet<Long>
    ): Boolean {
        val historyId = HistoryRedownloadMarker.parse(markerValue)?.historyId ?: return false
        val pending = pendingMarkerValues.any { value ->
            HistoryRedownloadMarker.parse(value)?.historyId == historyId
        }
        val repeatedInBatch = !seenHistoryIds.add(historyId)
        return pending || repeatedInBatch
    }
}
