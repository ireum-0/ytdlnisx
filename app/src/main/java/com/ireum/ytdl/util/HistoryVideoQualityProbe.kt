package com.ireum.ytdl.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.util.Locale

object HistoryVideoQualityProbe {
    fun probe(context: Context, paths: List<String>): VideoMediaQuality {
        if (paths.isEmpty()) return VideoMediaQuality(VideoFileQualityState.MISSING)
        var foundAccessiblePath = false
        var foundInaccessibleContentUri = false
        var noVideoResult: VideoMediaQuality? = null
        for (path in paths) {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) continue
            val isContentUri = trimmed.startsWith("content://", ignoreCase = true)
            if (!isContentUri) {
                val file = File(trimmed)
                if (!file.exists() || !file.isFile || file.length() <= 0L) continue
            }
            if (!isContentUri) foundAccessiblePath = true
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                if (isContentUri) {
                    retriever.setDataSource(context, Uri.parse(trimmed))
                } else {
                    retriever.setDataSource(trimmed)
                }
                foundAccessiblePath = true
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    ?.lowercase(Locale.US)
                    ?.let { it == "yes" || it == "1" || it == "true" }
                    ?: (width > 0 && height > 0)
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                    ?.lowercase(Locale.US)
                    ?.let { it == "yes" || it == "1" || it == "true" }
                    ?: false
                if (hasVideo && width > 0 && height > 0) {
                    return VideoMediaQuality(VideoFileQualityState.READY, width, height, hasAudio, trimmed)
                } else {
                    noVideoResult = VideoMediaQuality(
                        VideoFileQualityState.NO_VIDEO,
                        width,
                        height,
                        hasAudio,
                        trimmed
                    )
                    continue
                }
            } catch (_: Exception) {
                if (isContentUri) foundInaccessibleContentUri = true
                // Try another recorded path before classifying the item as corrupt.
            } finally {
                runCatching { retriever?.release() }
            }
        }
        return noVideoResult ?: VideoMediaQuality(
            state = when {
                foundAccessiblePath -> VideoFileQualityState.CORRUPT
                foundInaccessibleContentUri -> VideoFileQualityState.INACCESSIBLE
                else -> VideoFileQualityState.MISSING
            }
        )
    }
}
