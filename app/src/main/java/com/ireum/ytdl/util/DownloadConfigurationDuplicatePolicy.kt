package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandTokenizer
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpOptionOwnership

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
        val normalized = ArrayList<String>(tokens.size)
        var index = 0
        var afterOptionTerminator = false
        while (index < tokens.size) {
            val token = tokens[index]
            if (!afterOptionTerminator && token == "--") {
                normalized += token
                afterOptionTerminator = true
                index += 1
                continue
            }

            // An option owns its declared value tokens.  Those values remain
            // configuration identity, even when they happen to be YouTube
            // URLs (for example --referer or --proxy).  Only a positively
            // identified positional source token is canonicalized.
            if (!afterOptionTerminator && YtdlpOptionOwnership.isOptionToken(token)) {
                val ownership = YtdlpOptionOwnership.inspect(tokens, index)
                normalized += token
                val ownedCount = ownership.consumedFollowingTokenCount
                if (ownership.recognizedOption || ownership.ambiguousOption) {
                    repeat(ownedCount) { offset ->
                        normalized += tokens[index + 1 + offset]
                    }
                    index += ownership.nextIndexDelta
                } else {
                    // An unknown option has ambiguous arity. Preserve one
                    // following non-option token rather than accidentally
                    // treating an option value as the source media identity.
                    if (index + 1 < tokens.size &&
                        !YtdlpOptionOwnership.isOptionToken(tokens[index + 1])
                    ) {
                        normalized += tokens[index + 1]
                        index += 2
                    } else {
                        index += 1
                    }
                }
                continue
            }

            normalized += if (MediaPublishedDateSource.youtubeVideoId(token) != null) {
                canonicalMediaIdentity(token)
            } else {
                token
            }
            index += 1
        }
        return YtdlpCommandTokenizer.render(normalized)
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
