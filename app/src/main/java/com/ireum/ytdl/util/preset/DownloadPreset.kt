package com.ireum.ytdl.util.preset

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class PresetDownloadType {
    AUDIO,
    VIDEO;

    fun toDownloadType(): DownloadType = when (this) {
        AUDIO -> DownloadType.audio
        VIDEO -> DownloadType.video
    }

    companion object {
        fun from(type: DownloadType): PresetDownloadType? = when (type) {
            DownloadType.audio -> AUDIO
            DownloadType.video -> VIDEO
            else -> null
        }
    }
}

@Serializable
data class PresetAudioConfiguration(
    val embedThumbnail: Boolean = true,
    val cropThumbnail: Boolean? = null,
    val splitByChapters: Boolean = false,
    val sponsorBlockFilters: List<String> = emptyList(),
    val bitrate: String = ""
)

@Serializable
data class PresetVideoConfiguration(
    val embedSubtitles: Boolean = true,
    val addChapters: Boolean = true,
    val splitByChapters: Boolean = false,
    val sponsorBlockFilters: List<String> = emptyList(),
    val writeSubtitles: Boolean = false,
    val writeAutomaticSubtitles: Boolean = false,
    val subtitleLanguages: String = "en.*,.*-orig",
    val removeAudio: Boolean = false,
    val alsoDownloadAsAudio: Boolean = false,
    val recodeVideo: Boolean = false,
    val liveFromStart: Boolean = false,
    val waitForVideoMinutes: Int = 0,
    val compatibilityMode: Boolean = false,
    val embedThumbnail: Boolean = false
)

@Serializable
data class DownloadPresetConfiguration(
    val type: PresetDownloadType,
    val container: String = "",
    val saveThumbnail: Boolean = false,
    val audio: PresetAudioConfiguration = PresetAudioConfiguration(),
    val video: PresetVideoConfiguration = PresetVideoConfiguration()
)

@Serializable
data class DownloadPreset(
    val id: String,
    val name: String,
    val configuration: DownloadPresetConfiguration
)

@Serializable
data class DownloadPresetEnvelope(
    val version: Int = CURRENT_PRESET_VERSION,
    val presets: List<DownloadPreset> = emptyList()
)

object DownloadPresetCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(presets: List<DownloadPreset>): String {
        return json.encodeToString(DownloadPresetEnvelope(presets = presets.map(::sanitizePreset)))
    }

    fun decode(value: String): List<DownloadPreset> {
        if (value.isBlank()) return emptyList()
        val envelope = runCatching { json.decodeFromString<DownloadPresetEnvelope>(value) }
            .getOrDefault(DownloadPresetEnvelope())
        if (envelope.version > CURRENT_PRESET_VERSION) return emptyList()
        return envelope.presets
            .map(::sanitizePreset)
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy(DownloadPreset::id)
            .take(MAX_PRESET_COUNT)
    }

    fun sanitizeName(name: String): String {
        return name.replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
            .replace(Regex(" {2,}"), " ")
            .take(MAX_PRESET_NAME_LENGTH)
    }

    private fun sanitizePreset(preset: DownloadPreset): DownloadPreset {
        val type = preset.configuration.type
        val allowedContainers = when (type) {
            PresetDownloadType.AUDIO -> AUDIO_CONTAINERS
            PresetDownloadType.VIDEO -> VIDEO_CONTAINERS
        }
        val audio = preset.configuration.audio
        val video = preset.configuration.video
        return preset.copy(
            id = preset.id.trim().take(MAX_PRESET_ID_LENGTH),
            name = sanitizeName(preset.name),
            configuration = preset.configuration.copy(
                container = preset.configuration.container.lowercase().takeIf(allowedContainers::contains).orEmpty(),
                audio = audio.copy(
                    sponsorBlockFilters = sanitizeSponsorBlockFilters(audio.sponsorBlockFilters),
                    bitrate = audio.bitrate.lowercase().takeIf(AUDIO_BITRATES::contains).orEmpty()
                ),
                video = video.copy(
                    sponsorBlockFilters = sanitizeSponsorBlockFilters(video.sponsorBlockFilters),
                    subtitleLanguages = video.subtitleLanguages
                        .replace(Regex("[\\r\\n\\u0000]+"), "")
                        .trim()
                        .take(MAX_SUBTITLE_LANGUAGE_LENGTH),
                    waitForVideoMinutes = video.waitForVideoMinutes.coerceIn(0, MAX_WAIT_FOR_VIDEO_MINUTES)
                )
            )
        )
    }

    private fun sanitizeSponsorBlockFilters(filters: List<String>): List<String> {
        return filters.map(String::lowercase)
            .filter(SPONSOR_BLOCK_FILTERS::contains)
            .distinct()
    }
}

object DownloadPresetMapper {
    fun fromDownloadItem(id: String, name: String, item: DownloadItem): DownloadPreset? {
        val type = PresetDownloadType.from(item.type) ?: return null
        return DownloadPreset(
            id = id,
            name = name,
            configuration = DownloadPresetConfiguration(
                type = type,
                container = item.container,
                saveThumbnail = item.SaveThumb,
                audio = PresetAudioConfiguration(
                    embedThumbnail = item.audioPreferences.embedThumb,
                    cropThumbnail = item.audioPreferences.cropThumb,
                    splitByChapters = item.audioPreferences.splitByChapters,
                    sponsorBlockFilters = item.audioPreferences.sponsorBlockFilters.toList(),
                    bitrate = item.audioPreferences.bitrate
                ),
                video = PresetVideoConfiguration(
                    embedSubtitles = item.videoPreferences.embedSubs,
                    addChapters = item.videoPreferences.addChapters,
                    splitByChapters = item.videoPreferences.splitByChapters,
                    sponsorBlockFilters = item.videoPreferences.sponsorBlockFilters.toList(),
                    writeSubtitles = item.videoPreferences.writeSubs,
                    writeAutomaticSubtitles = item.videoPreferences.writeAutoSubs,
                    subtitleLanguages = item.videoPreferences.subsLanguages,
                    removeAudio = item.videoPreferences.removeAudio,
                    alsoDownloadAsAudio = item.videoPreferences.alsoDownloadAsAudio,
                    recodeVideo = item.videoPreferences.recodeVideo,
                    liveFromStart = item.videoPreferences.liveFromStart,
                    waitForVideoMinutes = item.videoPreferences.waitForVideoMinutes,
                    compatibilityMode = item.videoPreferences.compatibilityMode,
                    embedThumbnail = item.videoPreferences.embedThumbnail
                )
            )
        ).let { DownloadPresetCodec.decode(DownloadPresetCodec.encode(listOf(it))).firstOrNull() }
    }

    fun applyTo(
        item: DownloadItem,
        preset: DownloadPreset,
        resolvedFormat: Format = item.format
    ): DownloadItem {
        val safePreset = DownloadPresetCodec.decode(DownloadPresetCodec.encode(listOf(preset)))
            .firstOrNull() ?: return item
        val configuration = safePreset.configuration
        val existingAudioFormatIds = ArrayList(item.videoPreferences.audioFormatIDs)
        return item.copy(
            type = configuration.type.toDownloadType(),
            format = resolvedFormat,
            container = configuration.container,
            audioPreferences = AudioPreferences(
                embedThumb = configuration.audio.embedThumbnail,
                cropThumb = configuration.audio.cropThumbnail,
                splitByChapters = configuration.audio.splitByChapters,
                sponsorBlockFilters = ArrayList(configuration.audio.sponsorBlockFilters),
                bitrate = configuration.audio.bitrate
            ),
            videoPreferences = VideoPreferences(
                embedSubs = configuration.video.embedSubtitles,
                addChapters = configuration.video.addChapters,
                splitByChapters = configuration.video.splitByChapters,
                sponsorBlockFilters = ArrayList(configuration.video.sponsorBlockFilters),
                writeSubs = configuration.video.writeSubtitles,
                writeAutoSubs = configuration.video.writeAutomaticSubtitles,
                subsLanguages = configuration.video.subtitleLanguages,
                audioFormatIDs = existingAudioFormatIds,
                removeAudio = configuration.video.removeAudio,
                alsoDownloadAsAudio = configuration.video.alsoDownloadAsAudio,
                recodeVideo = configuration.video.recodeVideo,
                liveFromStart = configuration.video.liveFromStart,
                waitForVideoMinutes = configuration.video.waitForVideoMinutes,
                compatibilityMode = configuration.video.compatibilityMode,
                embedThumbnail = configuration.video.embedThumbnail
            ),
            SaveThumb = configuration.saveThumbnail
        )
    }
}

class DownloadPresetStore(
    context: Context,
    private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
) {
    @Synchronized
    fun presets(): List<DownloadPreset> = readPresets()

    @Synchronized
    fun preset(id: String): DownloadPreset? = readPresets().firstOrNull { it.id == id }

    @Synchronized
    fun create(name: String, item: DownloadItem): DownloadPreset? {
        val safeName = DownloadPresetCodec.sanitizeName(name)
        val current = readPresets()
        if (
            safeName.isBlank() ||
            current.size >= MAX_PRESET_COUNT ||
            current.any { it.name.equals(safeName, ignoreCase = true) }
        ) {
            return null
        }
        val preset = DownloadPresetMapper.fromDownloadItem(UUID.randomUUID().toString(), safeName, item)
            ?: return null
        writePresets(current + preset)
        return preset
    }

    @Synchronized
    fun rename(id: String, name: String): Boolean {
        val safeName = DownloadPresetCodec.sanitizeName(name)
        val current = readPresets()
        if (
            safeName.isBlank() ||
            current.none { it.id == id } ||
            current.any { it.id != id && it.name.equals(safeName, ignoreCase = true) }
        ) {
            return false
        }
        writePresets(current.map { preset ->
            if (preset.id == id) preset.copy(name = safeName) else preset
        })
        return true
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val current = readPresets()
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return false
        writePresets(updated)
        if (preferences.getString(KEY_QUICK_PRESET_ID, "") == id) {
            preferences.edit { remove(KEY_QUICK_PRESET_ID) }
        }
        return true
    }

    @Synchronized
    fun setQuickDownloadPreset(id: String?): Boolean {
        if (id == null) {
            preferences.edit { remove(KEY_QUICK_PRESET_ID) }
            return true
        }
        if (readPresets().none { it.id == id }) return false
        preferences.edit { putString(KEY_QUICK_PRESET_ID, id) }
        return true
    }

    @Synchronized
    fun quickDownloadPreset(): DownloadPreset? {
        val id = preferences.getString(KEY_QUICK_PRESET_ID, "").orEmpty()
        return readPresets().firstOrNull { it.id == id }
    }

    private fun readPresets(): List<DownloadPreset> {
        return DownloadPresetCodec.decode(preferences.getString(KEY_PRESETS, "").orEmpty())
    }

    private fun writePresets(presets: List<DownloadPreset>) {
        preferences.edit { putString(KEY_PRESETS, DownloadPresetCodec.encode(presets)) }
    }

    companion object {
        const val KEY_PRESETS = "download_presets_v1"
        const val KEY_QUICK_PRESET_ID = "quick_download_preset_id"
    }
}

private const val CURRENT_PRESET_VERSION = 1
private const val MAX_PRESET_COUNT = 20
private const val MAX_PRESET_NAME_LENGTH = 60
private const val MAX_PRESET_ID_LENGTH = 80
private const val MAX_SUBTITLE_LANGUAGE_LENGTH = 200
private const val MAX_WAIT_FOR_VIDEO_MINUTES = 1_440
private val AUDIO_CONTAINERS = setOf("", "mp3", "m4a", "aac", "alac", "flac", "opus", "wav", "vorbis")
private val VIDEO_CONTAINERS = setOf("", "mp4", "webm", "mkv", "mov", "avi", "flv", "gif")
private val AUDIO_BITRATES = setOf("", "320k", "192k", "128k", "64k")
private val SPONSOR_BLOCK_FILTERS = setOf(
    "music_offtopic",
    "sponsor",
    "intro",
    "outro",
    "selfpromo",
    "preview",
    "filler",
    "interaction",
    "hook"
)
