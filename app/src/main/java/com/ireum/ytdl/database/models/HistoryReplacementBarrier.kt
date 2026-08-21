package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Immutable refusal of a privileged History replacement.
 *
 * This is deliberately separate from DownloadItem.status and the diagnostic
 * projection on DownloadItem.  Pausing, cancelling, re-queuing, or a stale
 * full-row writer must not be able to erase the refusal or make the numeric
 * History id authoritative again.
 */
@Entity(tableName = "history_replacement_barriers")
data class HistoryReplacementBarrier(
    @PrimaryKey
    val downloadId: Long,
    val operationId: String,
    val historyId: Long,
    val expectedSourceUrl: String,
    val expectedType: String,
    val issueCode: String,
    val issueStage: String,
    val createdAt: Long,
)
