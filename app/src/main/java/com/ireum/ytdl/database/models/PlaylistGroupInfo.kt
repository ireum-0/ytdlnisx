package com.ireum.ytdl.database.models

data class PlaylistGroupInfo(
    val id: Long,
    val name: String,
    val memberCount: Int,
    val itemCount: Int,
    val thumbnail: String?
)
