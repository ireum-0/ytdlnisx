package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "youtuber_group_members",
    primaryKeys = ["groupId", "author"],
    indices = [Index(value = ["author"])]
)
data class YoutuberGroupMember(
    val groupId: Long,
    val author: String
)
