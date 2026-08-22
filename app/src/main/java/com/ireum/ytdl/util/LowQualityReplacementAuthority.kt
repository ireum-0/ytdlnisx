package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation

/**
 * A quality marker is privileged only while its immutable low-quality ledger
 * identity is present and still owns a live operation.
 */
object LowQualityReplacementAuthority {
    fun isCoherent(
        marker: HistoryRedownloadMarker,
        item: LowQualityRedownloadItem?,
        operation: LowQualityRedownloadOperation?,
        expectedDownloadId: Long,
        expectedSourceUrl: String,
        expectedType: DownloadType,
    ): Boolean {
        if (!marker.isQualityReplacement) return true
        return item != null &&
            operation != null &&
            item.downloadId == expectedDownloadId &&
            item.historyId == marker.historyId &&
            item.stateValue in setOf(
                LowQualityRedownloadItemState.QUEUED,
                LowQualityRedownloadItemState.ACTIVE,
                LowQualityRedownloadItemState.WAITING,
            ) &&
            !operation.stateValue.isTerminal &&
            !operation.cancelRequested &&
            item.intendedSourceUrl.isNotBlank() &&
            item.intendedType.isNotBlank() &&
            item.intendedType == expectedType.name &&
            HistoryReplacementSourceIdentity.matches(
                item.intendedSourceUrl,
                expectedSourceUrl,
            )
    }
}
