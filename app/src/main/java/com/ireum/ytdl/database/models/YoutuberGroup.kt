package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "youtuber_groups",
    indices = [Index(value = ["name"], unique = true)]
)
data class YoutuberGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String
)
