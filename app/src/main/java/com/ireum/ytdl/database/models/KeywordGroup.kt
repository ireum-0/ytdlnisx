package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "keyword_groups",
    indices = [Index(value = ["name"], unique = true)]
)
data class KeywordGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String
)

