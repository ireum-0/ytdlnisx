package com.ireum.ytdl.util.storage

import com.ireum.ytdl.util.MediaPublishedDateSource

/** Exact identity emitted by yt-dlp's ``--download-archive`` format. */
internal data class DownloadArchiveEntry(
    val extractorKey: String,
    val mediaId: String,
)

/** Shared, exact archive duplicate policy for Manual and Observe consumers. */
internal object DownloadArchiveIdentity {
    fun parseLine(line: String): DownloadArchiveEntry? {
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.size != 2) return null
        // yt-dlp archive keys are exact extractor tokens. Do not fold case or
        // otherwise collapse two distinct extractor identities.
        val extractor = tokens[0].trim()
        val mediaId = tokens[1].trim()
        if (
            extractor.isBlank() || mediaId.isBlank() ||
            extractor.any(Char::isWhitespace) || mediaId.any(Char::isWhitespace) ||
            extractor.any(Char::isISOControl) || mediaId.any(Char::isISOControl)
        ) {
            return null
        }
        return DownloadArchiveEntry(extractor, mediaId)
    }

    fun parseLines(lines: Iterable<String>): Set<DownloadArchiveEntry> = lines
        .mapNotNull(::parseLine)
        .toSet()

    /** Resolve only source forms with a positively known archive extractor. */
    fun sourceEntry(source: String): DownloadArchiveEntry? {
        val id = MediaPublishedDateSource.youtubeVideoId(source) ?: return null
        return DownloadArchiveEntry("youtube", id)
    }

    fun matchesSource(source: String, archiveLines: Iterable<String>): Boolean {
        val sourceEntry = sourceEntry(source) ?: return false
        return parseLines(archiveLines).contains(sourceEntry)
    }

    fun matchesSource(source: String, archiveEntries: Set<DownloadArchiveEntry>): Boolean =
        sourceEntry(source)?.let(archiveEntries::contains) == true
}
