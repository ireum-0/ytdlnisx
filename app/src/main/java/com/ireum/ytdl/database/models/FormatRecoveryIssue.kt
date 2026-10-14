package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An observed format failure, never authority to select or mutate a Download or Result. */
@Entity(
    tableName = "format_recovery_issues",
    indices = [Index(value = ["requestId", "handoffId", "generationId", "origin"], unique = true)],
)
data class FormatRecoveryIssue(
    @PrimaryKey val issueId: String,
    val requestId: String,
    val origin: String,
    val reason: String,
    val payload: String,
    val handoffId: String = "",
    val generationId: String = "",
    val state: String = OPEN,
    val acknowledgedAt: Long = 0,
) {
    companion object {
        const val OPEN = "OPEN"
        const val ACKNOWLEDGED = "ACKNOWLEDGED"
    }
}
