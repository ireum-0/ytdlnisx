package com.ireum.ytdl.database.repository

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.HistoryRedownloadMarker
import java.io.File

/** Application-level construction for maintenance re-downloads. */
class HistoryRedownloadItemFactory(
    context: Context,
    private val database: DBManager = DBManager.getInstance(context)
) {
    private val appContext = context.applicationContext
    private val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)

    fun createQualityReplacement(
        historyItem: HistoryItem,
        expectedMinimumHeight: Int,
        sourceFormats: List<Format>
    ): DownloadItem {
        val sponsorBlock = preferences.getStringSet("sponsorblock_filters", emptySet()).orEmpty()
        val defaultPath = preferences.getString("video_path", FileUtil.getDefaultVideoPath())
            ?: FileUtil.getDefaultVideoPath()
        val localSource = historyItem.localTreeUri.isNotBlank() ||
            historyItem.localTreePath.isNotBlank() ||
            historyItem.downloadPath.any { it.startsWith("content://") } ||
            historyItem.format.format_id.isLocalFormatLike()
        val destination = if (localSource) {
            defaultPath
        } else {
            historyItem.downloadPath.firstOrNull { FileUtil.exists(it) }
                ?.let { File(it).parent?.takeIf { parent -> File(parent).exists() } }
                ?: historyItem.downloadPath.firstOrNull()
                    ?.let { File(it).parent?.takeIf { parent -> File(parent).exists() } }
                ?: defaultPath
        }
        val format = Gson().fromJson(
            Gson().toJson(historyItem.format, Format::class.java),
            Format::class.java
        ).apply {
            if (localSource || format_id.isLocalFormatLike()) format_id = "best"
        }
        val extraCommands = if (preferences.getBoolean("use_extra_commands", false)) {
            database.commandTemplateDao.getAllTemplatesAsExtraCommandsForVideo()
                .filter { template ->
                    template.urlRegex.isEmpty() || template.urlRegex.any { pattern ->
                        runCatching { Regex(pattern).containsMatchIn(historyItem.url) }.getOrDefault(false)
                    }
                }
                .joinToString(" ") { it.content }
        } else {
            ""
        }

        return DownloadItem(
            id = 0,
            url = historyItem.url,
            title = historyItem.title,
            author = historyItem.author,
            thumb = historyItem.thumb,
            duration = historyItem.duration,
            type = DownloadType.video,
            format = format,
            container = preferences.getString("video_format", "Default").orEmpty(),
            downloadSections = "",
            allFormats = ArrayList(sourceFormats),
            downloadPath = destination,
            website = historyItem.website,
            downloadSize = "",
            playlistTitle = "",
            audioPreferences = AudioPreferences(
                preferences.getBoolean("embed_thumbnail", false),
                preferences.getBoolean("crop_thumbnail", false),
                false,
                ArrayList(sponsorBlock),
                preferences.getString("audio_bitrate", "").orEmpty()
            ),
            videoPreferences = VideoPreferences(
                embedSubs = false,
                addChapters = preferences.getBoolean("add_chapters", false),
                splitByChapters = false,
                sponsorBlockFilters = ArrayList(sponsorBlock),
                writeSubs = false,
                writeAutoSubs = false,
                subsLanguages = preferences.getString("subs_lang", "en.*,.*-orig").orEmpty(),
                recodeVideo = preferences.getBoolean("recode_video", false)
            ),
            extraCommands = extraCommands,
            customFileNameTemplate = preferences
                .getString("file_name_template", "%(uploader).30B - %(title).170B")
                .orEmpty(),
            SaveThumb = preferences.getBoolean("write_thumbnail", false),
            status = DownloadRepository.Status.Queued.name,
            downloadStartTime = 0,
            logID = null,
            playlistURL = HistoryRedownloadMarker.quality(historyItem.id, expectedMinimumHeight),
            playlistIndex = null,
            incognito = false,
            availableSubtitles = emptyList(),
            mediaPublishedAt = historyItem.mediaPublishedAt
        )
    }

    private fun String.isLocalFormatLike(): Boolean {
        val normalized = trim().lowercase()
        return normalized == "local" || normalized.startsWith("local+")
    }
}
