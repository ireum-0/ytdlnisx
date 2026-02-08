package com.ireum.ytdl.database.models

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "playlist_group_members",
    primaryKeys = ["groupId", "playlistId"],
    indices = [
        Index(value = ["playlistId"])
    ]
)
data class PlaylistGroupMember(
    val groupId: Long,
    val playlistId: Long
)
