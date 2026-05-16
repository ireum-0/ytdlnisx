package com.ireum.ytdl.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Locale

object SubtitleFormatConverter {
    private data class Cue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    fun convertJson3ToAss(input: File): File? {
        val cues = parseJson3Cues(input)
        if (cues.isEmpty()) return null

        val output = File(input.parentFile ?: return null, "${input.nameWithoutExtension}.burnin_tmp.ass")
        return runCatching {
            output.writeText(buildAss(cues), Charsets.UTF_8)
            if (output.length() > 0L) output else null
        }.getOrNull()
    }

    fun convertJson3ToSrt(input: File): File? {
        val cues = parseJson3Cues(input)
        if (cues.isEmpty()) return null

        val output = File(input.parentFile ?: return null, "${input.nameWithoutExtension}.srt")
        return runCatching {
            output.writeText(buildSrt(cues), Charsets.UTF_8)
            if (output.length() > 0L) output else null
        }.getOrNull()
    }

    private fun parseJson3Cues(input: File): List<Cue> {
        val ext = input.extension.lowercase(Locale.US)
        if (ext !in setOf("json", "json3")) return emptyList()

        val root = runCatching {
            JsonParser.parseString(input.readText(Charsets.UTF_8)).asJsonObject
        }.getOrNull() ?: return emptyList()
        val events = root.getAsJsonArray("events") ?: return emptyList()

        val cues = mutableListOf<Cue>()
        for (element in events) {
            if (!element.isJsonObject) continue
            val event = element.asJsonObject
            val startMs = event.longValue("tStartMs") ?: continue
            val durationMs = event.longValue("dDurationMs")?.takeIf { it > 0L } ?: 2000L
            val text = event.json3Text()
                .replace("\u200b", "")
                .replace("\ufeff", "")
                .trim()
            if (text.isBlank()) continue

            cues.add(Cue(startMs, startMs + durationMs, text))
        }
        return cues
    }

    private fun JsonObject.json3Text(): String {
        val segs = getAsJsonArray("segs")
        if (segs != null) {
            return buildString {
                for (segElement in segs) {
                    if (!segElement.isJsonObject) continue
                    val value = segElement.asJsonObject.get("utf8")?.takeIf { it.isJsonPrimitive }?.asString
                    if (!value.isNullOrEmpty()) append(value)
                }
            }
        }
        return get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }

    private fun JsonObject.longValue(name: String): Long? {
        return get(name)?.takeIf { it.isJsonPrimitive }?.asLong
    }

    private fun buildAss(cues: List<Cue>): String {
        return buildString {
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine("WrapStyle: 2")
            appendLine("ScaledBorderAndShadow: yes")
            appendLine()
            appendLine("[V4+ Styles]")
            appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
            appendLine("Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,0,2,40,40,40,1")
            appendLine()
            appendLine("[Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
            cues.forEach { cue ->
                appendLine("Dialogue: 0,${formatAssTime(cue.startMs)},${formatAssTime(cue.endMs)},Default,,0,0,0,,${escapeAssText(cue.text)}")
            }
        }
    }

    private fun buildSrt(cues: List<Cue>): String {
        return buildString {
            cues.forEachIndexed { index, cue ->
                appendLine(index + 1)
                appendLine("${formatSrtTime(cue.startMs)} --> ${formatSrtTime(cue.endMs)}")
                appendLine(cue.text)
                appendLine()
            }
        }
    }

    private fun escapeAssText(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "\\N")
    }

    private fun formatAssTime(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val centiseconds = (ms.coerceAtLeast(0L) % 1000L) / 10L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return "%d:%02d:%02d.%02d".format(Locale.US, hours, minutes, seconds, centiseconds)
    }

    private fun formatSrtTime(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000L
        val milliseconds = safeMs % 1000L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return "%02d:%02d:%02d,%03d".format(Locale.US, hours, minutes, seconds, milliseconds)
    }
}
