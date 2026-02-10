package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "keyword_group_members",
    primaryKeys = ["groupId", "keyword"],
    indices = [Index(value = ["keyword"])]
)
data class KeywordGroupMember(
    val groupId: Long,
    val keyword: String
)

