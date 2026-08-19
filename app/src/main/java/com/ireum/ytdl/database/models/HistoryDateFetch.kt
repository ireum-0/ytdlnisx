package com.ireum.ytdl.database.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class HistoryDateFetchOperationState {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this != RUNNING
}

enum class HistoryDateFetchItemState {
    PENDING,
    UPDATED,
    NO_DATE,
    FAILED,
    SKIPPED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this != PENDING
}

@Entity(
    tableName = "history_date_fetch_operations",
    indices = [
        Index(value = ["state"]),
        Index(value = ["createdAt"]),
    ],
    primaryKeys = ["operationId"],
)
data class HistoryDateFetchOperation(
    val operationId: String,
    @ColumnInfo(defaultValue = "'RUNNING'")
    val state: String = HistoryDateFetchOperationState.RUNNING.name,
    @ColumnInfo(defaultValue = "0")
    val cancelRequested: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val candidateCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val uniqueSourceCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val processedSourceCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val localHits: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val cacheHits: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val duplicateCoalesced: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val extractorLaunches: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val compatibilityFallbacks: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val elapsedMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val completedAt: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val terminalReason: String = "",
) {
    val stateValue: HistoryDateFetchOperationState
        get() = runCatching { HistoryDateFetchOperationState.valueOf(state) }
            .getOrDefault(HistoryDateFetchOperationState.FAILED)
}

@Entity(
    tableName = "history_date_fetch_items",
    primaryKeys = ["operationId", "historyId"],
    foreignKeys = [
        ForeignKey(
            entity = HistoryDateFetchOperation::class,
            parentColumns = ["operationId"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["operationId"]),
        Index(value = ["historyId"]),
        Index(value = ["operationId", "itemState"]),
    ],
)
data class HistoryDateFetchItem(
    val operationId: String,
    val historyId: Long,
    val sourceUrlSnapshot: String,
    @ColumnInfo(defaultValue = "''")
    val sourceGroupKey: String = "",
    @ColumnInfo(defaultValue = "'PENDING'")
    val itemState: String = HistoryDateFetchItemState.PENDING.name,
    @ColumnInfo(defaultValue = "''")
    val reasonCode: String = "",
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
) {
    val stateValue: HistoryDateFetchItemState
        get() = runCatching { HistoryDateFetchItemState.valueOf(itemState) }
            .getOrDefault(HistoryDateFetchItemState.FAILED)
}

data class HistoryDateFetchCounts(
    val total: Int,
    val pending: Int,
    val updated: Int,
    val noDate: Int,
    val failed: Int,
    val skipped: Int,
    val cancelled: Int,
) {
    val processed: Int
        get() = (total - pending).coerceAtLeast(0)
}

data class KnownMediaPublishedDate(
    val url: String,
    val mediaPublishedAt: Long,
)
