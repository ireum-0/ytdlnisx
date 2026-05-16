package com.ireum.ytdl.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.ireum.ytdl.database.models.YoutubeGeneratePoTokenItem
import com.ireum.ytdl.database.models.YoutubePlayerClientItem
import com.ireum.ytdl.util.Extensions.getIDFromYoutubeURL
import com.ireum.ytdl.util.Extensions.isYoutubeURL
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class YoutubeTimedTextFallback(context: Context) {
    private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Result(
        val file: File?,
        val status: Int,
        val contentLength: Long,
        val parseSuccess: Boolean,
        val cueCount: Int,
        val reason: String,
        val languageCode: String,
        val vssId: String,
        val kind: String,
        val poTokenUsed: Boolean
    )

    fun downloadSelectedSubtitle(
        videoUrl: String,
        outputDir: File,
        mediaFiles: List<File>,
        subsLanguages: String
    ): Result? {
        if (!videoUrl.isYoutubeURL()) return null

        val subtitleRequest = SubtitleSelection.normalize(subsLanguages)
        if (subtitleRequest.liveChatOnly) return null

        val videoId = videoUrl.getIDFromYoutubeURL() ?: return null
        val watchHtml = fetchText("https://www.youtube.com/watch?v=$videoId&hl=ko&persist_hl=1")
            ?: return failure("webpage-fetch-failed")
        val playerResponse = extractInitialPlayerResponse(watchHtml)
            ?: return failure("player-response-missing")
        val captionTracks = playerResponse
            .optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
            ?: return failure("caption-tracks-missing")

        var selectedTrack: JSONObject? = null
        for (i in 0 until captionTracks.length()) {
            val track = captionTracks.optJSONObject(i) ?: continue
            val languageCode = track.optString("languageCode")
            val vssId = track.optString("vssId")
            val kind = track.optString("kind")
            if (languageCode.isBlank()) continue
            if (SubtitleSelection.isAutomaticCaption(languageCode, vssId, kind)) continue
            if (SubtitleLanguageMatcher.hasRequestedSubtitle(listOf(languageCode), subtitleRequest.subLanguages)) {
                selectedTrack = track
                break
            }
        }

        val track = selectedTrack ?: return failure("selected-caption-track-missing")
        val languageCode = track.optString("languageCode")
        val vssId = track.optString("vssId")
        val kind = track.optString("kind")
        val baseUrl = track.optString("baseUrl").takeIf { it.isNotBlank() }
            ?: return failure("caption-base-url-missing", languageCode, vssId, kind)
        val clientVersion = extractClientVersion(watchHtml)
        val poToken = findWebSubsPoToken()
        val subtitleUrl = buildTimedTextUrl(baseUrl, languageCode, clientVersion, poToken)
            ?: return failure("timedtext-url-build-failed", languageCode, vssId, kind, poTokenUsed = poToken != null)

        val response = runCatching {
            client.newCall(
                Request.Builder()
                    .url(subtitleUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept", "application/json,text/plain,*/*")
                    .build()
            ).execute()
        }.getOrElse {
            return failure("timedtext-request-failed", languageCode, vssId, kind, poTokenUsed = poToken != null)
        }

        response.use {
            val status = it.code
            val body = it.body.string()
            val contentLength = body.toByteArray(Charsets.UTF_8).size.toLong()
            if (status != 200 || body.isBlank()) {
                return Result(
                    file = null,
                    status = status,
                    contentLength = contentLength,
                    parseSuccess = false,
                    cueCount = 0,
                    reason = if (body.isBlank()) "timedtext-empty-body" else "timedtext-http-$status",
                    languageCode = languageCode,
                    vssId = vssId,
                    kind = kind,
                    poTokenUsed = poToken != null
                )
            }

            outputDir.mkdirs()
            val output = File(outputDir, "${resolveSubtitleBaseName(mediaFiles, videoId)}.${languageCode}.json3")
            output.writeText(body)
            val validation = SubtitleFileValidator.validate(output, liveChat = false)
            if (!validation.valid) {
                runCatching { output.delete() }
            }

            return Result(
                file = output.takeIf { validation.valid },
                status = status,
                contentLength = contentLength,
                parseSuccess = validation.valid,
                cueCount = validation.cueCount,
                reason = validation.reason,
                languageCode = languageCode,
                vssId = vssId,
                kind = kind,
                poTokenUsed = poToken != null
            )
        }
    }

    private fun failure(
        reason: String,
        languageCode: String = "",
        vssId: String = "",
        kind: String = "",
        poTokenUsed: Boolean = false
    ) = Result(null, 0, 0, false, 0, reason, languageCode, vssId, kind, poTokenUsed)

    private fun fetchText(url: String): String? {
        return runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept-Language", "ko,en-US;q=0.9,en;q=0.8")
                    .build()
            ).execute().use { response ->
                if (response.code != 200) null else response.body.string()
            }
        }.getOrNull()
    }

    private fun extractInitialPlayerResponse(html: String): JSONObject? {
        val marker = "ytInitialPlayerResponse"
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null
        val start = html.indexOf('{', markerIndex)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val ch = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { JSONObject(html.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun extractClientVersion(html: String): String {
        return Regex(""""INNERTUBE_CONTEXT_CLIENT_VERSION"\s*:\s*"([^"]+)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: ""
    }

    private fun buildTimedTextUrl(
        baseUrl: String,
        languageCode: String,
        clientVersion: String,
        poToken: String?
    ): String? {
        val builder = baseUrl.toHttpUrlOrNull()?.newBuilder() ?: return null
        builder.setQueryParameter("fmt", "json3")
        builder.setQueryParameter("lang", languageCode)
        builder.setQueryParameter("c", "WEB")
        if (clientVersion.isNotBlank()) {
            builder.setQueryParameter("cver", clientVersion)
        }
        builder.setQueryParameter("cplayer", "UNIPLAYER")
        builder.setQueryParameter("cos", "Windows")
        builder.setQueryParameter("cplatform", "DESKTOP")
        if (!poToken.isNullOrBlank()) {
            builder.setQueryParameter("potc", "1")
            builder.setQueryParameter("pot", poToken)
        }
        return builder.build().toString()
    }

    private fun findWebSubsPoToken(): String? {
        val configured = sharedPreferences.getString("youtube_player_clients", "[]").orEmpty().ifBlank { "[]" }
        runCatching {
            Gson().fromJson(configured, Array<YoutubePlayerClientItem>::class.java)
                .firstOrNull { it.enabled && it.playerClient.equals("web", ignoreCase = true) }
                ?.poTokens
                ?.firstOrNull { it.context.equals("subs", ignoreCase = true) }
                ?.token
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }

        val generated = sharedPreferences.getString("youtube_generated_po_tokens", "[]").orEmpty().ifBlank { "[]" }
        return runCatching {
            Gson().fromJson(generated, Array<YoutubeGeneratePoTokenItem>::class.java)
                .firstOrNull { item ->
                    item.enabled && item.clients.any { it.equals("web", ignoreCase = true) }
                }
                ?.poTokens
                ?.firstOrNull { it.context.equals("subs", ignoreCase = true) }
                ?.token
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun resolveSubtitleBaseName(mediaFiles: List<File>, videoId: String): String {
        return mediaFiles
            .firstOrNull { it.exists() && it.isFile }
            ?.nameWithoutExtension
            ?.takeIf { it.isNotBlank() }
            ?: videoId.lowercase(Locale.US)
    }

    companion object {
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }
}
