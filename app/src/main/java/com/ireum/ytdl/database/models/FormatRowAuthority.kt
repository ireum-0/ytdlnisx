package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Database-owned, non-reused identities. Never restored from an application backup. */
@Entity(tableName = "format_authority_clock")
data class FormatAuthorityClock(@PrimaryKey(autoGenerate = true) val id: Long = 0)

/** A row incarnation is distinct from its current write authority and semantic retry lineage. */
@Entity(tableName = "format_row_authorities", primaryKeys = ["kind", "target"])
data class FormatRowAuthority(
    val kind: String,
    val target: String,
    val revision: Long,
    val incarnation: Long,
    val owner: String = "",
    val lineage: String = "",
    val completedLineage: String = "",
    val state: String = "CURRENT",
)

/** Finite foreground authority; process-session replacement revokes abandoned claims. */
@Entity(tableName = "format_foreground_claims")
data class FormatForegroundClaim(
    @PrimaryKey val id: String,
    val session: Long,
    val payload: String,
)

/** Numeric-ID legacy work is retained as debt, never interpreted as current write permission. */
@Entity(tableName = "format_legacy_debts")
data class FormatLegacyDebt(
    @PrimaryKey val requestId: String,
    val payload: String,
    val reason: String,
)

/** Exact outgoing request tombstone; semantic member retry debt remains with its carrier. */
@Entity(tableName = "format_request_cancellations")
data class FormatRequestCancellation(
    @PrimaryKey val requestId: String,
    val handoffId: String,
    val generationId: String,
    val state: String = "PENDING",
)
