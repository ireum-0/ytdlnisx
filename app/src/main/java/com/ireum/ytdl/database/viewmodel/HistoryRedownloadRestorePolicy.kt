package com.ireum.ytdl.database.viewmodel

import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage

internal object HistoryRedownloadRestorePolicy {
    /**
     * Download backups do not carry the low-quality authority graph.  A
     * quality marker without a projected refusal therefore cannot be restored
     * as an executable History replacement.
     */
    fun revokeOrphanQualityMarker(
        item: DownloadItem,
        hasPersistedRefusal: Boolean,
    ): DownloadItem? {
        val marker = HistoryRedownloadMarker.parse(item.playlistURL)
        if (marker?.isQualityReplacement != true || hasPersistedRefusal) return null
        return item.copy(
            playlistURL = "",
            status = DownloadRepository.Status.Error.name,
            lastIssueCode = DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED.name,
            lastIssueStage = DownloadIssueStage.HISTORY.name,
        )
    }
}
