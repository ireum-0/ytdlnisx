package com.ireum.ytdl.database.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class LowQualityRedownloadPhase {
    SCANNING,
    AWAITING_SELECTION,
    PREPARING,
    QUEUEING,
    DOWNLOADING,
    FINALIZING
}

enum class LowQualityRedownloadOperationState {
    RUNNING,
    COMPLETED,
    PARTIAL_FAILURE,
    CANCELLED,
    FAILED,
    UNRECOVERABLE;

    val isTerminal: Boolean
        get() = this != RUNNING
}

enum class LowQualityRedownloadItemState {
    PROVISIONAL,
    PENDING,
    CHECKING,
    QUEUED,
    ACTIVE,
    WAITING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLATION_REQUESTED,
    CANCELLED,
    NOT_SELECTED;

    val isTerminal: Boolean
        get() = this in setOf(SUCCEEDED, FAILED, SKIPPED, CANCELLED, NOT_SELECTED)
}

@Entity(tableName = "low_quality_redownload_operations")
data class LowQualityRedownloadOperation(
    @androidx.room.PrimaryKey
    val operationId: String,
    @ColumnInfo(defaultValue = "SCANNING")
    val phase: String = LowQualityRedownloadPhase.SCANNING.name,
    @ColumnInfo(defaultValue = "RUNNING")
    val state: String = LowQualityRedownloadOperationState.RUNNING.name,
    @ColumnInfo(defaultValue = "0")
    val scanUpperBoundHistoryId: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val scanCursorHistoryId: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val scanTotal: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val scanProcessed: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val scanFailures: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val cancelRequested: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val completedAt: Long = 0,
    @ColumnInfo(defaultValue = "")
    val terminalReason: String = ""
) {
    val phaseValue: LowQualityRedownloadPhase
        get() = runCatching { LowQualityRedownloadPhase.valueOf(phase) }
            .getOrDefault(LowQualityRedownloadPhase.SCANNING)

    val stateValue: LowQualityRedownloadOperationState
        get() = runCatching { LowQualityRedownloadOperationState.valueOf(state) }
            .getOrDefault(LowQualityRedownloadOperationState.UNRECOVERABLE)
}

@Entity(
    tableName = "low_quality_redownload_items",
    primaryKeys = ["operationId", "historyId"],
    foreignKeys = [
        ForeignKey(
            entity = LowQualityRedownloadOperation::class,
            parentColumns = ["operationId"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["operationId"]),
        Index(value = ["downloadId"], unique = true)
    ]
)
data class LowQualityRedownloadItem(
    val operationId: String,
    val historyId: Long,
    /**
     * Immutable identity captured with the candidate.  PREPARING must never
     * reconstruct replacement authority from historyId alone.
     */
    @ColumnInfo(defaultValue = "")
    val intendedSourceUrl: String = "",
    @ColumnInfo(defaultValue = "")
    val intendedType: String = "",
    @ColumnInfo(defaultValue = "")
    val candidateReason: String = "",
    @ColumnInfo(defaultValue = "")
    val mediaState: String = "",
    @ColumnInfo(defaultValue = "0")
    val actualHeight: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val requestedHeight: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val expectedHeight: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val sourceMaxHeight: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val selected: Boolean = false,
    @ColumnInfo(defaultValue = "PROVISIONAL")
    val itemState: String = LowQualityRedownloadItemState.PROVISIONAL.name,
    @ColumnInfo(defaultValue = "")
    val reasonCode: String = "",
    val downloadId: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0
) {
    val stateValue: LowQualityRedownloadItemState
        get() = runCatching { LowQualityRedownloadItemState.valueOf(itemState) }
            .getOrDefault(LowQualityRedownloadItemState.FAILED)
}

data class LowQualityRedownloadLiveCounts(
    val queued: Int = 0,
    val active: Int = 0,
    val waiting: Int = 0,
    val paused: Int = 0
)
