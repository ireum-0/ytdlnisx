package com.ireum.ytdl.work

import com.ireum.ytdl.database.dao.DownloadDao
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.repository.ResultRepository

/**
 * Production DownloadWorker metadata publication.  The repository resolves
 * an immutable metadata patch; this function publishes only those fields at
 * the exact execution/source boundary and never writes a full row snapshot.
 */
internal suspend fun persistDownloadMetadataNarrowly(
    resultRepo: ResultRepository,
    dao: DownloadDao,
    downloadItem: DownloadItem,
): Boolean {
    val patch = resultRepo.getDownloadMetadataPatch(
        downloadItem,
        lookupOrder = ResultRepository.DownloadMetadataLookupOrder.CACHE_FIRST,
    ) ?: return false
    return dao.updateMetadataIfSourceAndExecutionOwned(
        id = patch.downloadId,
        expectedSourceUrl = patch.expectedSourceUrl,
        expectedExecutionId = downloadItem.executionId,
        title = patch.title,
        author = patch.author,
        playlistTitle = patch.playlistTitle,
        duration = patch.duration,
        website = patch.website,
        thumb = patch.thumb,
        mediaPublishedAt = patch.mediaPublishedAt,
    ) > 0
}
