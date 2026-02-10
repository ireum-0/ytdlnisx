package com.ireum.ytdl.database.models

data class KeywordInfo(
    val keyword: String,
    val videoCount: Int,
    val thumbnail: String?,
    val lastTime: Long,
    val firstTime: Long,
    val uniqueCreator: String?,
    val parentKeywords: List<String>,
    val childKeywords: List<String>
)
