package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable owner for an exact one-shot handoff into WorkManager.
 *
 * The row is created before the ephemeral producer (a click, alarm, or
 * notification action) is allowed to release its authority.  [requestId] is
 * the exact WorkRequest generation; [generationId] remains stable when a
 * failed enqueue is retried with a new WorkRequest id.
 */
@Entity(
    tableName = "work_manager_handoff_carriers",
    indices = [
        Index(value = ["kind", "boundary", "state"]),
        Index(value = ["sourceId", "confirmedUrl", "configFingerprint", "decision", "kind"]),
    ],
)
data class WorkManagerHandoffCarrier(
    @PrimaryKey
    val handoffId: String,
    val kind: String,
    val generationId: String,
    val requestId: String,
    val uniqueWorkName: String,
    val state: String = PENDING_ENQUEUE,
    val sourceId: Long = 0L,
    val confirmedUrl: String = "",
    val decision: String = "",
    val configFingerprint: String = "",
    val boundary: String = "",
    val notBeforeAt: Long = 0L,
    val attempt: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val HARD_SUB_SCAN = "HARD_SUB_SCAN"
        const val SCHEDULE_START = "SCHEDULE_START"
        const val SCHEDULE_END = "SCHEDULE_END"
        const val OBSERVE_RETRY_DOWNLOAD = "OBSERVE_RETRY_DOWNLOAD"

        const val START_BOUNDARY = "START"
        const val END_BOUNDARY = "END"

        const val PENDING_ENQUEUE = "PENDING_ENQUEUE"
        const val ACCEPTED = "ACCEPTED"
        const val RESOLVED = "RESOLVED"
    }
}
