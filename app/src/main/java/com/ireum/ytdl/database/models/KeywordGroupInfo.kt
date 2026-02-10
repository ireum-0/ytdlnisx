package com.ireum.ytdl.database.models

data class KeywordGroupInfo(
    val id: Long,
    val name: String,
    val memberCount: Int,
    val videoCount: Int,
    val thumbnail: String?
)

