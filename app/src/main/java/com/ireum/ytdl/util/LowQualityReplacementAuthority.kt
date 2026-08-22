package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
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
        expectedOperationId: String,
        expectedSourceUrl: String,
        expectedType: DownloadType,
    ): Boolean {
        if (!marker.isQualityReplacement) return true
        return item != null &&
            operation != null &&
            item.downloadId != null &&
            item.historyId == marker.historyId &&
            expectedOperationId.isNotBlank() &&
            item.operationId == expectedOperationId &&
            !item.stateValue.isTerminal &&
            !operation.stateValue.isTerminal &&
            item.intendedSourceUrl.isNotBlank() &&
            item.intendedType.isNotBlank() &&
            item.intendedType == expectedType.name &&
            HistoryReplacementSourceIdentity.matches(
                item.intendedSourceUrl,
                expectedSourceUrl,
            )
    }
}
