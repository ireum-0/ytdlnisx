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

    /** Unique durable role identity for a direct native writer. */
    fun directDownload(
        downloadId: Long,
        executionId: String,
        role: String,
    ): String {
        require(executionId.isNotBlank()) { "Direct native identity needs an execution token" }
        require(role.isNotBlank() && !role.contains(':')) {
            "Direct native role must be a single identity component"
        }
        return "download:$downloadId:$executionId:direct:$role:${java.util.UUID.randomUUID()}"
    }

    fun terminal(terminalId: Long): String = "terminal:$terminalId"
}
