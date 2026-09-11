package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.models.DownloadItem

/**
 * Immutable metadata-only publication produced from one requested source
 * snapshot.  The expected URL is part of the patch so the DAO can reject a
 * stale result atomically when the Download has been reconfigured or deleted.
 */
internal data class DownloadMetadataPatch(
    val downloadId: Long,
    val expectedSourceUrl: String,
    val title: String? = null,
    val author: String? = null,
    val playlistTitle: String? = null,
    val duration: String? = null,
    val website: String? = null,
    val thumb: String? = null,
    val mediaPublishedAt: Long? = null,
) {
    fun applyTo(item: DownloadItem): Boolean {
        var changed = false
        title?.let {
            if (item.title != it) {
                item.title = it
                changed = true
            }
        }
        author?.let {
            if (item.author != it) {
                item.author = it
                changed = true
            }
        }
        playlistTitle?.let {
            if (item.playlistTitle != it) {
                item.playlistTitle = it
                changed = true
            }
        }
        duration?.let {
            if (item.duration != it) {
                item.duration = it
                changed = true
            }
        }
        website?.let {
            if (item.website != it) {
                item.website = it
                changed = true
            }
        }
        thumb?.let {
            if (item.thumb != it) {
                item.thumb = it
                changed = true
            }
        }
        mediaPublishedAt?.let {
            if (item.mediaPublishedAt != it) {
                item.mediaPublishedAt = it
                changed = true
            }
        }
        return changed
    }
}
