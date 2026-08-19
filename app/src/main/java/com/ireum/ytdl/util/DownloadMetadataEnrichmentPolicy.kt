package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.DownloadItem

/** Defines when source-backed download metadata can and should be enriched. */
internal object DownloadMetadataEnrichmentPolicy {
    fun shouldEnrich(item: DownloadItem): Boolean {
        return shouldEnrich(
            source = item.url,
            title = item.title,
            author = item.author,
            thumbnail = item.thumb,
            mediaPublishedAt = item.mediaPublishedAt,
        )
    }

    fun shouldEnrich(
        source: String,
        title: String,
        author: String,
        thumbnail: String,
        mediaPublishedAt: Long,
    ): Boolean {
        if (WebUrlInput.resolveExtractorInput(source) == null) return false
        return title.isBlank() ||
            author.isBlank() ||
            thumbnail.isBlank() ||
            !MediaPublishedDate.isPresent(mediaPublishedAt)
    }
}
