package com.ireum.ytdl.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ireum.ytdl.MainActivity
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.util.Extensions.toDurationSeconds
import com.ireum.ytdl.util.Extensions.toStringDuration
import com.ireum.ytdl.util.LocalAddCandidateDto
import com.ireum.ytdl.util.LocalAddEntryDto
import com.ireum.ytdl.util.LocalAddMatchDto
import com.ireum.ytdl.util.LocalAddStorage
import com.ireum.ytdl.util.LocalAddIdentity
import com.ireum.ytdl.util.LocalMatchUtil
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.setForegroundSafely
import android.media.MediaMetadataRetriever
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale
import java.util.UUID

class LocalAddWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    private var lastNotifyAt = 0L
    private var lastNotifyDone = -1
    private var lastNotifyPercent = -1

    override suspend fun doWork(): Result {
        val entriesJson = inputData.getString(KEY_ENTRIES_JSON).orEmpty()
        val sessionId = inputData.getString(KEY_SESSION_ID).orEmpty()
        val loadedFromSession = entriesJson.isBlank() && sessionId.isNotBlank()
        val type = object : TypeToken<List<LocalAddEntryDto>>() {}.type
        val entries: List<LocalAddEntryDto> = if (entriesJson.isNotBlank()) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                Gson().fromJson(entriesJson, type) as? List<LocalAddEntryDto>
            }.getOrNull() ?: emptyList()
        } else if (sessionId.isNotBlank()) {
            LocalAddStorage.loadEntries(context, sessionId)
        } else {
            emptyList()
        }
        if (entries.isEmpty()) {
            LocalAddStorage.clearProgressSnapshot(context)
            return Result.success()
        }
        val dedupedEntries = deduplicateEntriesForAdmission(entries)
        // Prevent background restrictions from stopping a long-running local add session.
        if (!setForegroundSafely()) return Result.retry()

        val db = DBManager.getInstance(context)
        val resultRepository = ResultRepository(db.resultDao, db.commandTemplateDao, context)
        val allItems = db.historyDao.getAll()
        val existingLocalIdentities = allItems
            .flatMap { LocalAddIdentity.keysForHistory(it) }
            .toMutableSet()

        val pending = mutableListOf<LocalAddCandidateDto>()
        var processed = 0
        setProgress(workDataOf(KEY_TOTAL to dedupedEntries.size, KEY_DONE to processed))
        updateProgressNotification(processed, dedupedEntries.size, force = true)

        dedupedEntries.forEach { entry ->
            currentCoroutineContext().ensureActive()
            if (isStopped) throw CancellationException("Local add worker stopped")
            try {
                val uri = Uri.parse(entry.uri)
                val treeUri = entry.treeUri?.let { Uri.parse(it) }
                val uriString = uri.toString()
                val treeMeta = buildTreeMeta(treeUri, uri)
                val identities = LocalAddIdentity.keysFor(
                    uri = uriString,
                    treeUri = treeMeta.first.ifBlank { null },
                    treePath = treeMeta.second.ifBlank { null }
                )
                if (identities.any { existingLocalIdentities.contains(it) }) return@forEach
                existingLocalIdentities.addAll(identities)
                val name = getDisplayNameFromUri(uri)
                    ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
                    ?: "local-media"
                val title = name.substringBeforeLast('.')
                val ext = name.substringAfterLast('.', "")
                val size = getFileSize(uri)
                val durationSeconds = getDurationSeconds(uri)

                val match = try {
                    LocalMatchUtil.findYoutubeMatch(resultRepository, title, durationSeconds)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }

                if (match != null && match.exactTitleMatch) {
                    val existingByUrl = db.historyDao.getItem(match.item.url)
                    if (existingByUrl != null) {
                        return@forEach
                    }
                    val format = Format(
                        format_id = "local",
                        container = ext,
                        filesize = size,
                        format_note = "local"
                    )
                    val item = HistoryItem(
                        id = 0,
                        url = match.item.url,
                        title = match.item.title.ifBlank { title },
                        author = match.item.author,
                        artist = "",
                        duration = if (match.item.duration.isNotBlank()) match.item.duration
                        else if (durationSeconds > 0) durationSeconds.toStringDuration(Locale.US) else "",
                        durationSeconds = if (match.item.duration.isNotBlank()) match.item.duration.toDurationSeconds() else durationSeconds.toLong(),
                        thumb = match.item.thumb,
                        type = DownloadType.video,
                        time = System.currentTimeMillis() / 1000L,
                        downloadPath = listOf(uriString),
                        website = match.item.website,
                        format = format,
                        filesize = size,
                        downloadId = 0,
                        localTreeUri = treeMeta.first,
                        localTreePath = treeMeta.second,
                        mediaPublishedAt = match.item.mediaPublishedAt
                    )
                    HistoryKeywordAssignmentRepository(db).insertHistory(item)
                    return@forEach
                }

                val matchDto = match?.item?.let {
                    LocalAddMatchDto(
                        url = it.url,
                        title = it.title,
                        author = it.author,
                        duration = it.duration,
                        thumb = it.thumb,
                        website = it.website,
                        mediaPublishedAt = it.mediaPublishedAt
                    )
                }
                pending.add(
                    LocalAddCandidateDto(
                        uri = uriString,
                        treeUri = treeUri?.toString(),
                        title = title,
                        ext = ext,
                        size = size,
                        durationSeconds = durationSeconds,
                        match = matchDto
                    )
                )
            } finally {
                processed += 1
                setProgress(workDataOf(KEY_TOTAL to dedupedEntries.size, KEY_DONE to processed))
                updateProgressNotification(processed, dedupedEntries.size)
            }
        }

        currentCoroutineContext().ensureActive()
        if (isStopped) throw CancellationException("Local add worker stopped")
        if (pending.isNotEmpty()) {
            val sessionId = UUID.randomUUID().toString()
            LocalAddStorage.savePending(context, sessionId, pending)
            LocalAddStorage.setOpenSession(context, sessionId)
            NotificationUtil(context).notify(
                NOTIFICATION_ID,
                createPendingNotification(pending.size, sessionId)
            )
        }

        if (loadedFromSession) {
            LocalAddStorage.clearEntries(context, sessionId)
        }
        LocalAddStorage.clearProgressSnapshot(context)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notif = NotificationUtil(context).createDefaultWorkerNotification()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notif,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun createPendingNotification(count: Int, sessionId: String) =
        NotificationUtil(context).createLocalAddPendingNotification(
            count,
            createOpenPendingIntent(sessionId)
        )

    private fun createOpenPendingIntent(sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        intent.putExtra("destination", "Downloads")
        intent.putExtra("localAddSessionId", sessionId)
        return PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createOpenDownloadsIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        intent.putExtra("destination", "Downloads")
        return PendingIntent.getActivity(
            context,
            "local_add_progress".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateProgressNotification(done: Int, total: Int, force: Boolean = false) {
        val safeTotal = if (total <= 0) 1 else total
        val safeDone = done.coerceIn(0, safeTotal)
        val percent = (safeDone * 100) / safeTotal
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (safeDone == lastNotifyDone && percent == lastNotifyPercent) return
            if (safeDone != safeTotal && now - lastNotifyAt < 1000) return
        }
        LocalAddStorage.setProgressSnapshot(context, safeDone, safeTotal)
        val notification = NotificationUtil(context).createLocalAddProgressNotification(
            safeDone,
            safeTotal,
            createOpenDownloadsIntent()
        )
        NotificationUtil(context).notify(NOTIFICATION_ID, notification)
        lastNotifyAt = now
        lastNotifyDone = safeDone
        lastNotifyPercent = percent
    }

    private fun getFileSize(uri: Uri): Long {
        return DocumentFile.fromSingleUri(context, uri)?.length() ?: 0L
    }

    private fun getDisplayNameFromUri(uri: Uri): String? {
        val docName = DocumentFile.fromSingleUri(context, uri)?.name
        val raw = docName ?: uri.lastPathSegment
        if (raw.isNullOrBlank()) return null
        val afterSlash = raw.substringAfterLast('/')
        val afterColon = afterSlash.substringAfterLast(':')
        return afterColon.ifBlank { afterSlash.ifBlank { raw } }
    }

    private fun getDurationSeconds(uri: Uri): Int {
        return runCatching {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration?.toIntOrNull()?.div(1000) ?: 0
            } finally {
                runCatching { retriever?.release() }
            }
        }.getOrElse { 0 }
    }

    private fun buildTreeMeta(treeUri: Uri?, fileUri: Uri): Pair<String, String> {
        if (treeUri == null) return "" to ""
        val treeId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val docId = runCatching { android.provider.DocumentsContract.getDocumentId(fileUri) }.getOrNull()
        if (treeId.isNullOrBlank() || docId.isNullOrBlank()) return "" to ""
        val relative = if (docId == treeId) "" else docId.removePrefix("$treeId/").removePrefix(treeId).trimStart('/')
        return treeUri.toString() to relative
    }

    companion object {
        const val KEY_ENTRIES_JSON = "entries_json"
        const val KEY_SESSION_ID = "entries_session_id"
        const val KEY_TOTAL = "progress_total"
        const val KEY_DONE = "progress_done"
        const val NOTIFICATION_ID = 93500
        const val TAG = "local_add_worker"

        /** The production batch-admission policy; unknown identities fail open. */
        internal fun deduplicateEntriesForAdmission(entries: List<LocalAddEntryDto>): List<LocalAddEntryDto> {
            val seenIdentities = HashSet<String>()
            return entries.filter { entry ->
                val identity = LocalAddIdentity.keyForEntry(entry.uri, entry.treeUri)
                identity == null || seenIdentities.add(identity)
            }
        }
    }
}
