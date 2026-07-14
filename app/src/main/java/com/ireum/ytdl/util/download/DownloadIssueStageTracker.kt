package com.ireum.ytdl.util.download

object DownloadIssueStageTracker {
    fun update(current: DownloadIssueStage, outputLine: String): DownloadIssueStage {
        if (current == DownloadIssueStage.HARD_SUB) return current
        val line = outputLine.lowercase()
        return when {
            SUBTITLE_MARKERS.any(line::contains) -> DownloadIssueStage.SUBTITLE
            POST_PROCESSING_MARKERS.any(line::contains) -> DownloadIssueStage.MERGE
            DOWNLOAD_MARKERS.any(line::contains) -> DownloadIssueStage.DOWNLOAD
            else -> current
        }
    }

    private val SUBTITLE_MARKERS = listOf(
        "[embedsubtitle]",
        "[subtitlesconvertor]"
    )

    private val POST_PROCESSING_MARKERS = listOf(
        "[merger]",
        "[videoremuxer]",
        "[videoconvertor]",
        "[extractaudio]",
        "[embedthumbnail]",
        "[ffmpeg]",
        "postprocessing:",
        "post-processing:"
    )

    private val DOWNLOAD_MARKERS = listOf("[download]")
}
