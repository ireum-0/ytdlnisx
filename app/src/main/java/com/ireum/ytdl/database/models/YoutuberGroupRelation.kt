package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "youtuber_group_relations",
    primaryKeys = ["parentGroupId", "childGroupId"],
    indices = [Index(value = ["childGroupId"])]
)
data class YoutuberGroupRelation(
    val parentGroupId: Long,
    val childGroupId: Long
)
