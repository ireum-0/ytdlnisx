package com.ireum.ytdl.database.models

data class YoutuberInfo(
    val author: String,
    val videoCount: Int,
    val thumbnail: String?,
    val lastTime: Long,
    val totalSize: Long,
    val firstTime: Long
)

