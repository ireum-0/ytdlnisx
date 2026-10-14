package com.ireum.ytdl.work

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.util.FormatUtil

/** Only format-selection preferences, never credentials or the full preferences environment. */
internal data class FormatRequestPolicy(
    val source: String,
    val allowFallback: Boolean,
    val strings: Map<String, String>,
    val booleans: Map<String, Boolean>,
    val command: Format?,
) {
    fun choose(context: Context, formats: List<Format>, type: DownloadType): Format {
        if (type !in setOf(DownloadType.audio, DownloadType.video)) return checkNotNull(command)
        val util = FormatUtil(context, FrozenPreferences(strings, booleans))
        return if (type == DownloadType.audio) {
            val values = formats.filter { it.vcodec.isBlank() || it.vcodec == "none" }
                .ifEmpty { util.getGenericAudioFormats(context.resources).sortedByDescending { it.filesize } }
            try { util.sortAudioFormats(values).first().copy() }
            catch (_: Exception) { util.getGenericAudioFormats(context.resources).first().copy() }
        } else {
            val values = formats.filter { it.vcodec.isNotBlank() && it.vcodec != "none" }
                .ifEmpty { util.getGenericVideoFormats(context.resources).sortedByDescending { it.filesize } }
            try { util.sortVideoFormats(values).first().copy() }
            catch (_: Exception) { util.getGenericVideoFormats(context.resources).first().copy() }
        }
    }

    companion object {
        private fun command(context: Context, database: DBManager, url: String): Format {
            val dao = database.commandTemplateDao
            val template = dao.getPreferredCommandTemplates().firstOrNull { candidate ->
                candidate.urlRegex.isEmpty() || candidate.urlRegex.any { Regex(it).containsMatchIn(url) }
            } ?: dao.getFirst()
            val content = template?.content ?: PreferenceManager.getDefaultSharedPreferences(context)
                .getString("lastCommandTemplateUsed", "").orEmpty()
            return Format(template?.title.orEmpty(), (template?.id ?: 0).toString(), "", "", "", 0, content.replace("\n", " "))
        }

        fun capture(context: Context, database: DBManager, type: DownloadType, url: String, source: String? = null): FormatRequestPolicy {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val strings = listOf("format_id", "format_id_audio", "video_quality", "audio_language",
                "audio_codec", "video_codec", "audio_format", "video_format", "preferred_format_size",
                "format_importance_audio", "format_importance_video").associateWith { key ->
                // Absence retains FormatUtil's resource-specific default, rather than an invented value.
                preferences.getString(key, null)
            }.filterValues { it != null }.mapValues { checkNotNull(it.value) }
            val booleans = listOf("use_format_sorting", "prefer_container_over_codec_audio", "prefer_drc_audio")
                .associateWith { preferences.getBoolean(it, false) }
            return FormatRequestPolicy(source ?: preferences.getString("formats_source", "yt-dlp").orEmpty(), source == null,
                strings, booleans, if (type !in setOf(DownloadType.audio, DownloadType.video)) command(context, database, url) else null)
        }
    }
}

private class FrozenPreferences(
    private val strings: Map<String, String>, private val booleans: Map<String, Boolean>,
) : SharedPreferences {
    override fun getString(key: String?, defValue: String?): String? = strings[key] ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = booleans[key] ?: defValue
    override fun contains(key: String?): Boolean = strings.containsKey(key) || booleans.containsKey(key)
    override fun getAll(): MutableMap<String, *> = (strings + booleans).toMutableMap()
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun edit(): SharedPreferences.Editor = error("Immutable format request preferences")
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
