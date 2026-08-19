package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.util.MediaPublishedDate

internal object ResultMetadataMergePolicy {
    fun isUsable(item: ResultItem): Boolean {
        return item.title.isNotBlank() ||
            item.author.isNotBlank() ||
            item.thumb.isNotBlank() ||
            item.duration.isNotBlank() ||
            item.website.isNotBlank() ||
            MediaPublishedDate.isPresent(item.mediaPublishedAt)
    }

    fun needsFallback(item: ResultItem): Boolean {
        return item.url.isBlank() ||
            item.title.isBlank() ||
            item.author.isBlank() ||
            item.thumb.isBlank() ||
            !MediaPublishedDate.isPresent(item.mediaPublishedAt)
    }

    fun merge(primary: ResultItem, fallback: ResultItem): ResultItem {
        return primary.copy(
            url = primary.url.ifBlank { fallback.url },
            title = primary.title.ifBlank { fallback.title },
            author = primary.author.ifBlank { fallback.author },
            duration = primary.duration.ifBlank { fallback.duration },
            thumb = primary.thumb.ifBlank { fallback.thumb },
            website = primary.website.ifBlank { fallback.website },
            playlistTitle = primary.playlistTitle.ifBlank { fallback.playlistTitle },
            formats = primary.formats.ifEmpty { fallback.formats },
            urls = primary.urls.ifBlank { fallback.urls },
            chapters = primary.chapters?.takeIf { it.isNotEmpty() } ?: fallback.chapters,
            playlistURL = primary.playlistURL?.takeIf { it.isNotBlank() } ?: fallback.playlistURL,
            playlistIndex = primary.playlistIndex ?: fallback.playlistIndex,
            availableSubtitles = primary.availableSubtitles.ifEmpty { fallback.availableSubtitles },
            mediaPublishedAt = primary.mediaPublishedAt.takeIf(MediaPublishedDate::isPresent)
                ?: fallback.mediaPublishedAt,
        ).also { merged ->
            merged.sourceIdentity = primary.sourceIdentity ?: fallback.sourceIdentity
        }
    }
}
