package com.ireum.ytdl.work

import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.DownloadItem
import kotlinx.coroutines.CancellationException

/** An unreadable authority is neither a missing row nor a revoked execution. */
internal class DownloadAuthorityReadException(
    val downloadId: Long,
    val executionId: String,
    val boundary: String,
    cause: Exception,
) : IllegalStateException("Download authority unreadable at $boundary for $downloadId/$executionId", cause)

/**
 * A row (including a replaced token), proven absence, and a failed read are
 * deliberately distinct. Callers may stop normally only on a readable result.
 */
internal fun readDownloadExecutionAuthority(
    dbManager: DBManager,
    item: DownloadItem,
    boundary: String,
): DownloadItem? = try {
    DownloadWorkerEffectTestHooks.beforeAuthorityReadForTesting?.invoke(item.id, boundary)
    dbManager.downloadDao.getNullableDownloadById(item.id)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw DownloadAuthorityReadException(item.id, item.executionId, boundary, failure)
}
