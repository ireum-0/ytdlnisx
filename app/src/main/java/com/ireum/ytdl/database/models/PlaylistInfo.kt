package com.ireum.ytdl.database.models

data class PlaylistInfo(
    val id: Long,
    val name: String,
    val description: String?,
    val itemCount: Int,
    val thumbnail: String?
)

