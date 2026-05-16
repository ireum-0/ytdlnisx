package com.ireum.ytdl.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object SubtitleFileValidator {
    data class Result(
        val valid: Boolean,
        val cueCount: Int,
        val reason: String
    )

    fun validate(file: File, liveChat: Boolean): Result {
        if (!file.exists() || !file.isFile) {
            return Result(false, 0, "missing")
        }
        if (file.length() <= 0L) {
            return Result(false, 0, "empty-file")
        }

        val text = runCatching { file.readText() }.getOrElse {
            return Result(false, 0, "read-failed")
        }.trim()
        if (text.isBlank()) {
            return Result(false, 0, "empty-body")
        }

        return when (file.extension.lowercase()) {
            "json", "json3" -> validateJson(text, liveChat)
            "vtt" -> validateVtt(text)
            "srt" -> validateSrt(text)
            "srv3", "ttml" -> validateXmlSubtitle(text)
            "ass" -> validateAss(text)
            else -> Result(true, 1, "unknown-format-nonempty")
        }
    }

    private fun validateJson(text: String, liveChat: Boolean): Result {
        val parsed = runCatching {
            val element = JsonParser.parseString(text)
            when {
                element.isJsonObject -> countJsonObjectCues(element.asJsonObject, liveChat)
                element.isJsonArray -> countJsonArrayCues(element.asJsonArray, liveChat)
                else -> countJsonLines(text, liveChat)
            }
        }.getOrElse {
            return Result(false, 0, "json-parse-failed")
        }

        return if (parsed > 0) {
            Result(true, parsed, "ok")
        } else {
            Result(false, 0, "json-no-cues")
        }
    }

    private fun countJsonObjectCues(root: JsonObject, liveChat: Boolean): Int {
        val events = root.getAsJsonArray("events")
        if (events != null) {
            var cues = 0
            for (eventElement in events) {
                if (!eventElement.isJsonObject) continue
                val event = eventElement.asJsonObject
                if ((event.getAsJsonArray("segs")?.size() ?: 0) > 0) cues++
            }
            if (cues > 0) return cues
        }

        if (liveChat) {
            val actions = root.getAsJsonArray("actions")
            if (actions != null && actions.size() > 0) return actions.size()
            if (root.has("replayChatItemAction") || root.has("liveChatTextMessageRenderer")) return 1
        }
        return 0
    }

    private fun countJsonArrayCues(array: JsonArray, liveChat: Boolean): Int {
        if (array.size() == 0) return 0
        if (liveChat) return array.size()
        var cues = 0
        for (itemElement in array) {
            if (!itemElement.isJsonObject) continue
            val item = itemElement.asJsonObject
            if ((item.getAsJsonArray("segs")?.size() ?: 0) > 0 || item.has("text")) cues++
        }
        return cues
    }

    private fun countJsonLines(text: String, liveChat: Boolean): Int {
        if (!liveChat) return 0
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .count { line ->
                runCatching { JsonParser.parseString(line).isJsonObject }.getOrDefault(false)
            }
    }

    private fun validateVtt(text: String): Result {
        val cueCount = Regex("""(?m)^\s*\d{2}:\d{2}:\d{2}\.\d{3}\s+-->\s+""")
            .findAll(text)
            .count()
        return if (text.contains("WEBVTT") && cueCount > 0) {
            Result(true, cueCount, "ok")
        } else {
            Result(false, cueCount, "vtt-no-cues")
        }
    }

    private fun validateSrt(text: String): Result {
        val cueCount = Regex("""(?m)^\s*\d{2}:\d{2}:\d{2},\d{3}\s+-->\s+""")
            .findAll(text)
            .count()
        return if (cueCount > 0) Result(true, cueCount, "ok") else Result(false, 0, "srt-no-cues")
    }

    private fun validateXmlSubtitle(text: String): Result {
        val cueCount = Regex("""<p(\s|>)|<text(\s|>)""").findAll(text).count()
        return if (cueCount > 0) Result(true, cueCount, "ok") else Result(false, 0, "xml-no-cues")
    }

    private fun validateAss(text: String): Result {
        val cueCount = Regex("""(?m)^Dialogue:""").findAll(text).count()
        return if (text.contains("[Events]") && cueCount > 0) {
            Result(true, cueCount, "ok")
        } else {
            Result(false, cueCount, "ass-no-cues")
        }
    }
}
