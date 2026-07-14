package com.ireum.ytdl.util.preset

import android.content.Context
import com.ireum.ytdl.R

object DownloadPresetText {
    fun summary(context: Context, preset: DownloadPreset): String {
        val configuration = preset.configuration
        val parts = mutableListOf(
            context.getString(
                when (configuration.type) {
                    PresetDownloadType.AUDIO -> R.string.audio
                    PresetDownloadType.VIDEO -> R.string.video
                }
            ),
            configuration.container.ifBlank { context.getString(R.string.defaultValue) }
        )
        when (configuration.type) {
            PresetDownloadType.AUDIO -> {
                configuration.audio.bitrate.takeIf(String::isNotBlank)?.let(parts::add)
                if (configuration.audio.splitByChapters) {
                    parts += context.getString(R.string.split_by_chapters)
                }
                if (configuration.audio.embedThumbnail) {
                    parts += context.getString(R.string.embed_thumb)
                }
            }
            PresetDownloadType.VIDEO -> {
                if (configuration.video.embedSubtitles) {
                    parts += context.getString(R.string.embed_subtitles)
                }
                if (configuration.video.addChapters) {
                    parts += context.getString(R.string.add_chapters)
                }
                if (configuration.video.recodeVideo) {
                    parts += context.getString(R.string.recode_video)
                }
            }
        }
        return parts.take(MAX_SUMMARY_PARTS).joinToString()
    }

    private const val MAX_SUMMARY_PARTS = 5
}
