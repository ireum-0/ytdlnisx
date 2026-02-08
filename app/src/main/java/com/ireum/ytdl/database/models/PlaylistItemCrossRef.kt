package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index

@Entity(
    primaryKeys = ["playlistId", "historyItemId"],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["historyItemId"])
    ]
)
data class PlaylistItemCrossRef(
    val playlistId: Long,
    val historyItemId: Long
)

