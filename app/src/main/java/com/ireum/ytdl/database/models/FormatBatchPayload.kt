package com.ireum.ytdl.database.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Immutable membership of a format handoff; the carrier owns its request lifecycle. */
@Entity(
    tableName = "format_batch_payloads",
    foreignKeys = [ForeignKey(
        entity = WorkManagerHandoffCarrier::class,
        parentColumns = ["handoffId"], childColumns = ["handoffId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class FormatBatchPayload(
    @PrimaryKey val handoffId: String,
    val capturedIds: String,
    val selectedIds: String,
    val otherIds: String,
    val notificationId: Int,
    @ColumnInfo(defaultValue = "'[]'") val authority: String = "[]",
)
