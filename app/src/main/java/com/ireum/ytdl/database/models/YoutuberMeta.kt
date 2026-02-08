package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "youtuber_meta")
data class YoutuberMeta(
    @PrimaryKey
    val author: String,
    val channelUrl: String,
    val iconUrl: String
)
