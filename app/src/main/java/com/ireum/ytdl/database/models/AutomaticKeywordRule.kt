package com.ireum.ytdl.database.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object AutomaticKeywordRuleTypes {
    const val PLAYLIST = "PLAYLIST"
}

object AutomaticKeywordSyncStatus {
    const val NEVER = "NEVER"
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val SUCCESS = "SUCCESS"
    const val PARTIAL = "PARTIAL"
    const val FAILED = "FAILED"
}

object AutomaticKeywordSyncError {
    const val NONE = ""
    const val NETWORK = "NETWORK"
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val PRIVATE_PLAYLIST = "PRIVATE_PLAYLIST"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val EXTRACTION = "EXTRACTION"
    const val DATABASE_PARTIAL = "DATABASE_PARTIAL"
    const val UNKNOWN = "UNKNOWN"
}

object HistoryKeywordAssignmentSources {
    const val MANUAL = "MANUAL"
    const val RULE = "RULE"
    const val LEGACY_OBSERVE_SOURCE = "LEGACY_OBSERVE_SOURCE"
    const val MANUAL_SOURCE_ID = 0L
}

@Entity(
    tableName = "automatic_keyword_rules",
    indices = [
        Index(value = ["conditionType", "conditionKey"]),
        Index(value = ["enabled"])
    ]
)
data class AutomaticKeywordRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conditionType: String = AutomaticKeywordRuleTypes.PLAYLIST,
    val conditionValue: String,
    val conditionKey: String,
    val playlistName: String,
    val enabled: Boolean = true,
    val revision: Long = 1,
    val baselineComplete: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val pendingApplyToExisting: Boolean = false,
    val manualSyncStatus: String = AutomaticKeywordSyncStatus.NEVER,
    val manualSyncAt: Long = 0,
    val manualSyncError: String = AutomaticKeywordSyncError.NONE,
    val discoveryStatus: String = AutomaticKeywordSyncStatus.NEVER,
    val discoveryAt: Long = 0,
    val discoveryError: String = AutomaticKeywordSyncError.NONE
)

@Entity(
    tableName = "automatic_keyword_rule_keywords",
    primaryKeys = ["ruleId", "normalizedKeyword"],
    foreignKeys = [
        ForeignKey(
            entity = AutomaticKeywordRule::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["ruleId"])]
)
data class AutomaticKeywordRuleKeyword(
    val ruleId: Long,
    val normalizedKeyword: String,
    val keyword: String,
    val position: Int
)

@Entity(
    tableName = "automatic_keyword_rule_video_matches",
    primaryKeys = ["ruleId", "videoKey"],
    foreignKeys = [
        ForeignKey(
            entity = AutomaticKeywordRule::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["ruleId"]),
        Index(value = ["videoKey"])
    ]
)
data class AutomaticKeywordRuleVideoMatch(
    val ruleId: Long,
    val videoKey: String,
    val videoUrl: String,
    val eligibleForAssignment: Boolean,
    val firstSeenAt: Long
)

@Entity(
    tableName = "history_keyword_assignments",
    primaryKeys = ["historyItemId", "normalizedKeyword", "sourceType", "sourceId"],
    foreignKeys = [
        ForeignKey(
            entity = HistoryItem::class,
            parentColumns = ["id"],
            childColumns = ["historyItemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["historyItemId"]),
        Index(value = ["sourceType", "sourceId"])
    ]
)
data class HistoryKeywordAssignment(
    val historyItemId: Long,
    val normalizedKeyword: String,
    val keyword: String,
    val sourceType: String,
    val sourceId: Long,
    val position: Int,
    val createdAt: Long
)

data class AutomaticKeywordRuleSummary(
    val id: Long,
    val conditionType: String,
    val conditionValue: String,
    val conditionKey: String,
    val playlistName: String,
    val enabled: Boolean,
    val revision: Long,
    val baselineComplete: Boolean,
    val pendingApplyToExisting: Boolean,
    val manualSyncStatus: String,
    val manualSyncAt: Long,
    val manualSyncError: String,
    val discoveryStatus: String,
    val discoveryAt: Long,
    val discoveryError: String,
    val keywordsCsv: String?,
    val matchedHistoryCount: Int
)
