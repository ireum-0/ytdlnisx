package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlist_groups",
    indices = [
        Index(value = ["name"], unique = true)
    ]
)
data class PlaylistGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)
