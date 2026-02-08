package com.ireum.ytdl.database.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ireum.ytdl.database.enums.DownloadType

@Entity(
    tableName = "history",
    indices = [
        Index(value = ["time"]),
        Index(value = ["author"]),
        Index(value = ["title"]),
        Index(value = ["type"]),
        Index(value = ["website"]),
        Index(value = ["filesize"]),
        Index(value = ["url"])
    ]
)
data class HistoryItem(
    @PrimaryKey(autoGenerate = true)
    var id: Long,
    val url: String,
    val title: String,
    val author: String,
    @ColumnInfo(defaultValue = "")
    val artist: String = "",
    val duration: String,
    @ColumnInfo(defaultValue = "0")
    val durationSeconds: Long = 0,
    val thumb: String,
    val type: DownloadType,
    val time: Long,
    @ColumnInfo(defaultValue = "0")
    val lastWatched: Long = 0,
    val downloadPath: List<String>,
    val website: String,
    val format: Format,
    @ColumnInfo(defaultValue = "0")
    val filesize: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val downloadId: Long,
    @ColumnInfo(defaultValue = "")
    val command: String = "",
    @ColumnInfo(defaultValue = "0")
    val playbackPositionMs: Long = 0,
    @ColumnInfo(defaultValue = "")
    val localTreeUri: String = "",
    @ColumnInfo(defaultValue = "")
    val localTreePath: String = "",
    @ColumnInfo(defaultValue = "")
    val keywords: String = "",
    @ColumnInfo(defaultValue = "")
    val customThumb: String = ""
)
