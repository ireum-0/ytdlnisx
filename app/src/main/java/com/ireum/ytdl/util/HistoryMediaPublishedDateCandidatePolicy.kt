package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.HistoryItem

/** Authoritative eligibility policy applied after the DAO's broad missing-date query. */
internal object HistoryMediaPublishedDateCandidatePolicy {
    fun isEligible(item: HistoryItem): Boolean {
        return item.type in setOf(DownloadType.video, DownloadType.audio) &&
            !MediaPublishedDate.isPresent(item.mediaPublishedAt) &&
            WebUrlInput.resolveExtractorInput(item.url) != null
    }

    fun select(items: Iterable<HistoryItem>): List<HistoryItem> {
        return items.filter(::isEligible)
    }
}
