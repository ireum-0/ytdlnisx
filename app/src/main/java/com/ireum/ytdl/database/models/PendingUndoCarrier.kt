package com.ireum.ytdl.database.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable carrier for one exact user Undo capability. */
@Entity(tableName = "pending_undo_carriers")
data class PendingUndoCarrier(
    @PrimaryKey
    val token: String,
    val kind: String,
    @ColumnInfo(defaultValue = "")
    val ownerId: String = "",
    @ColumnInfo(defaultValue = "0")
    val authorityGeneration: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val resolutionIntent: String = "",
    @ColumnInfo(defaultValue = "0")
    val resolverGeneration: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val snapshotJson: String = "",
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0L,
) {
    companion object {
        const val CANCELLATION_KIND = "CANCELLATION"
        const val REMOVAL_KIND = "REMOVAL"
        const val RESTORE_INTENT = "RESTORE"
        const val COMMIT_INTENT = "COMMIT"
    }
}

enum class PendingUndoResolutionIntent {
    RESTORE,
    COMMIT,
}
