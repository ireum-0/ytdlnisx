package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandTokenizer

internal object DownloadConfigurationDuplicatePolicy {
    fun matches(first: DownloadItem, second: DownloadItem): Boolean =
        requestConfiguration(first) == requestConfiguration(second)

    fun findMatch(
        candidates: Iterable<DownloadItem>,
        requested: DownloadItem,
    ): DownloadItem? = candidates.firstOrNull { matches(it, requested) }

    /**
     * Normalize only source-media URL tokens in a persisted yt-dlp command.
     * Tokenization is important here: a URL carried by an option value must
     * not be rewritten by a raw substring replacement.  All other command
     * tokens remain part of configuration identity.
     */
    fun normalizeCommandForComparison(command: String): String {
        val tokens = YtdlpCommandTokenizer.tokenize(command) ?: return command
        return YtdlpCommandTokenizer.render(
            tokens.map { token ->
                if (MediaPublishedDateSource.youtubeVideoId(token) != null) {
                    canonicalMediaIdentity(token)
                } else {
                    token
                }
            },
        )
    }

    fun commandsMatch(first: String, second: String): Boolean {
        val volatile = Regex("(-P \\\"(.*?)\\\")|(--trim-filenames \\\"(.*?)\\\")")
        return normalizeCommandForComparison(first).replace(volatile, "") ==
            normalizeCommandForComparison(second).replace(volatile, "")
    }

    private fun requestConfiguration(item: DownloadItem) = RequestConfiguration(
        // The source URL is media identity, not user spelling.  Keep every
        // other request field below in the configuration identity so two
        // differently configured downloads of the same video remain distinct.
        url = canonicalMediaIdentity(item.url),
        playlistUrl = item.playlistURL,
        playlistIndex = item.playlistIndex,
        title = item.title,
        author = item.author,
        playlistTitle = item.playlistTitle,
        type = item.type,
        selectedFormat = item.format,
        sourceFormats = item.allFormats,
        container = item.container,
        downloadSections = item.downloadSections,
        downloadPath = item.downloadPath,
        audioPreferences = item.audioPreferences,
        videoPreferences = item.videoPreferences,
        extraCommands = item.extraCommands,
        customFileNameTemplate = item.customFileNameTemplate,
        saveThumbnail = item.SaveThumb,
        incognito = item.incognito,
        rowNumber = item.rowNumber,
        observeSourceId = item.observeSourceId,
    )

    private fun canonicalMediaIdentity(value: String): String {
        val trimmed = value.trim()
        return MediaPublishedDateSource.youtubeVideoId(trimmed)?.let { "youtube:$it" } ?: trimmed
    }

    private data class RequestConfiguration(
        val url: String,
        val playlistUrl: String?,
        val playlistIndex: Int?,
        val title: String,
        val author: String,
        val playlistTitle: String,
        val type: DownloadType,
        val selectedFormat: Format,
        val sourceFormats: List<Format>,
        val container: String,
        val downloadSections: String,
        val downloadPath: String,
        val audioPreferences: AudioPreferences,
        val videoPreferences: VideoPreferences,
        val extraCommands: String,
        val customFileNameTemplate: String,
        val saveThumbnail: Boolean,
        val incognito: Boolean,
        val rowNumber: Int,
        val observeSourceId: Long,
    )
}
