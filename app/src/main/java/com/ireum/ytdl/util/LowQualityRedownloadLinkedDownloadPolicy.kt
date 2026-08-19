package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.repository.DownloadRepository

internal object LowQualityRedownloadLinkedDownloadPolicy {
    fun reconciledState(
        currentState: LowQualityRedownloadItemState,
        downloadStatus: String?,
    ): LowQualityRedownloadItemState? {
        if (currentState.isTerminal) return currentState
        return when (downloadStatus) {
            DownloadRepository.Status.Error.name -> LowQualityRedownloadItemState.FAILED
            DownloadRepository.Status.Cancelled.name -> LowQualityRedownloadItemState.CANCELLED
            DownloadRepository.Status.Saved.name -> LowQualityRedownloadItemState.SKIPPED
            null -> LowQualityRedownloadItemState.FAILED
            else -> null
        }
    }
}
