package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences

internal object DownloadConfigurationDuplicatePolicy {
    fun matches(first: DownloadItem, second: DownloadItem): Boolean =
        requestConfiguration(first) == requestConfiguration(second)

    fun findMatch(
        candidates: Iterable<DownloadItem>,
        requested: DownloadItem,
    ): DownloadItem? = candidates.firstOrNull { matches(it, requested) }

    private fun requestConfiguration(item: DownloadItem) = RequestConfiguration(
        url = item.url,
        playlistUrl = item.playlistURL,
        playlistIndex = item.playlistIndex,
        title = item.title,
        author = item.author,
        playlistTitle = item.playlistTitle,
        type = item.type,
        selectedFormat = item.format,
        sourceFormats = item.allFormats,
        container = item.container,
        downloadSections = item.downloadSections,
        downloadPath = item.downloadPath,
        audioPreferences = item.audioPreferences,
        videoPreferences = item.videoPreferences,
        extraCommands = item.extraCommands,
        customFileNameTemplate = item.customFileNameTemplate,
        saveThumbnail = item.SaveThumb,
        incognito = item.incognito,
        rowNumber = item.rowNumber,
        observeSourceId = item.observeSourceId,
    )

    private data class RequestConfiguration(
        val url: String,
        val playlistUrl: String?,
        val playlistIndex: Int?,
        val title: String,
        val author: String,
        val playlistTitle: String,
        val type: DownloadType,
        val selectedFormat: Format,
        val sourceFormats: List<Format>,
        val container: String,
        val downloadSections: String,
        val downloadPath: String,
        val audioPreferences: AudioPreferences,
        val videoPreferences: VideoPreferences,
        val extraCommands: String,
        val customFileNameTemplate: String,
        val saveThumbnail: Boolean,
        val incognito: Boolean,
        val rowNumber: Int,
        val observeSourceId: Long,
    )
}
