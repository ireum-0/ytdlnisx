package com.ireum.ytdl.work

/**
 * Namespaces the global yt-dlp process registry by the owning product domain.
 * Download attempts additionally carry their exact execution token so a stale
 * attempt cannot address a newer process for the same row.
 */
internal object YtdlpProcessIdentity {
    fun download(downloadId: Long, executionId: String): String {
        require(executionId.isNotBlank()) { "Download process identity needs an execution token" }
        return "download:$downloadId:$executionId"
    }

    fun terminal(terminalId: Long): String = "terminal:$terminalId"
}
