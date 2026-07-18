package com.ireum.ytdl.database.models

import androidx.room.Ignore

data class YoutuberInfo(
    val author: String,
    val videoCount: Int,
    val thumbnail: String?,
    val lastTime: Long,
    val totalSize: Long,
    val firstTime: Long
) {
    @Ignore
    var fallbackThumbnail: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is YoutuberInfo) return false
        return author == other.author &&
            videoCount == other.videoCount &&
            thumbnail == other.thumbnail &&
            lastTime == other.lastTime &&
            totalSize == other.totalSize &&
            firstTime == other.firstTime &&
            fallbackThumbnail == other.fallbackThumbnail
    }

    override fun hashCode(): Int {
        var result = author.hashCode()
        result = 31 * result + videoCount
        result = 31 * result + (thumbnail?.hashCode() ?: 0)
        result = 31 * result + lastTime.hashCode()
        result = 31 * result + totalSize.hashCode()
        result = 31 * result + firstTime.hashCode()
        result = 31 * result + (fallbackThumbnail?.hashCode() ?: 0)
        return result
    }
}

