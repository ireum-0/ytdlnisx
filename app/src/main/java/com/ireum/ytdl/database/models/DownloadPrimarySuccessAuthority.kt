package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index

/**
 * Durable primary-success authority for one exact Download execution.
 *
 * The execution token is part of the primary key so a later retry cannot
 * inherit an earlier History result merely by sharing a Download id.  A
 * nullable History id is represented as zero for the incognito/no-History
 * publication case; the exact generation identity remains authoritative.
 */
@Entity(
    tableName = "download_primary_success_authorities",
    indices = [
        Index(value = ["downloadId"]),
        Index(value = ["historyId"]),
    ],
)
data class DownloadPrimarySuccessAuthority(
    @androidx.room.PrimaryKey
    val authorityKey: String,
    val downloadId: Long,
    val operationId: String,
    val executionId: String,
    val historyId: Long,
    val noHistory: Boolean,
    val semanticFingerprint: String,
    val archiveDelta: String,
    val phase: String,
    val createdAt: Long,
)
