package com.ireum.ytdl.util.extractors

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.ireum.ytdl.database.models.ResultItem
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayList
import java.util.Locale
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class YoutubeApiUtil(context: Context) {
    private var sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val countryCode = sharedPreferences.getString("locale", "")!!.ifEmpty { "US" }
    private var lastQuotaExceeded = false
    private val prefDisabledUntil = "youtube_api_disabled_until"
    private val pref403Streak = "youtube_api_403_streak"
    private val prefBackoffLevel = "youtube_api_backoff_level"

    data class ChannelInfo(
        val channelId: String,
        val title: String,
        val iconUrl: String,
        val channelUrl: String
    )

    @Throws(JSONException::class)
    fun getTrending(): ArrayList<ResultItem> {
        if (isTemporarilyDisabled()) return arrayListOf()
        val items = arrayListOf<ResultItem>()
        val key = sharedPreferences.getString("api_key", "")!!
        val url = "https://www.googleapis.com/youtube/v3/videos?part=snippet&chart=mostPopular&videoCategoryId=10&regionCode=${countryCode}&maxResults=25&key=$key"
        //short data
        val res = NetworkUtil.genericRequest(url)
        //extra data from the same videos
        val contentDetails =
            NetworkUtil.genericRequest("https://www.googleapis.com/youtube/v3/videos?part=contentDetails&chart=mostPopular&videoCategoryId=10&regionCode=${countryCode}&maxResults=25&key=$key")
        if (isApiBlocked(res) || isApiBlocked(contentDetails)) {
            registerApiFailure(res)
            registerApiFailure(contentDetails)
            return ArrayList()
        }
        if (!contentDetails.has("items")) return ArrayList()
        val dataArray = res.getJSONArray("items")
        val extraDataArray = contentDetails.getJSONArray("items")
        for (i in 0 until dataArray.length()) {
            val element = dataArray.getJSONObject(i)
            val snippet = element.getJSONObject("snippet")
            var duration = extraDataArray.getJSONObject(i).getJSONObject("contentDetails")
                .getString("duration")
            duration = formatDuration(duration)
            snippet.put("videoID", element.getString("id"))
            snippet.put("duration", duration)
            fixThumbnail(snippet)
            val v = createVideofromJSON(snippet)
            if (v == null || v.thumb.isEmpty()) {
                continue
            }
            items.add(v)
        }
        return items
    }

    private fun createVideofromJSON(obj: JSONObject): ResultItem? {
        var video: ResultItem? = null
        try {
            val id = obj.getString("videoID")
            val localizedTitle = obj.optJSONObject("localized")?.optString("title").orEmpty()
            val title = if (localizedTitle.isNotBlank()) localizedTitle else obj.getString("title").toString()
            val author = obj.getString("channelTitle").toString()
            val duration = obj.getString("duration")
            val thumb = obj.getString("thumb")
            val url = "https://www.youtube.com/watch?v=$id"
            video = ResultItem(0,
                url,
                title,
                author,
                duration,
                thumb,
                "youtube",
                "",
                ArrayList(),
                "",
                ArrayList()
            )
        } catch (e: Exception) {
            Log.e("YoutubeApiUtil", e.toString())
        }
        return video
    }

    @Throws(JSONException::class)
    fun searchVideos(query: String, language: String, region: String): ArrayList<ResultItem> {
        val items = arrayListOf<ResultItem>()
        val key = sharedPreferences.getString("api_key", "")!!
        if (key.isBlank()) return items
        if (isTemporarilyDisabled()) return items
        lastQuotaExceeded = false
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val searchUrl = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=20" +
            "&q=$encodedQuery&relevanceLanguage=$language&regionCode=$region&hl=$language&key=$key"
        val searchRes = NetworkUtil.genericRequest(searchUrl)
        if (isApiBlocked(searchRes)) {
            registerApiFailure(searchRes)
            return items
        }
        searchRes.optJSONObject("error")?.let { error ->
            if (isQuotaExceededError(error)) {
                lastQuotaExceeded = true
            }
            Log.e("YoutubeApiUtil", "search error code=${error.optInt("code")} message=${error.optString("message")}")
        }
        if (!searchRes.has("items")) return items
        val dataArray = searchRes.getJSONArray("items")
        Log.d("YoutubeApiUtil", "search items=${dataArray.length()} query=$query lang=$language region=$region")
        if (dataArray.length() == 0) return items
        val ids = ArrayList<String>()
        for (i in 0 until dataArray.length()) {
            val idObj = dataArray.getJSONObject(i).optJSONObject("id")
            val videoId = idObj?.optString("videoId").orEmpty()
            if (videoId.isNotBlank()) ids.add(videoId)
        }
        Log.d("YoutubeApiUtil", "search ids=${ids.size}")
        if (ids.isEmpty()) return items
        val idParam = ids.joinToString(",")
        val videosUrl = "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=$idParam" +
            "&hl=$language&regionCode=$region&key=$key"
        val videosRes = NetworkUtil.genericRequest(videosUrl)
        if (isApiBlocked(videosRes)) {
            registerApiFailure(videosRes)
            return items
        }
        videosRes.optJSONObject("error")?.let { error ->
            if (isQuotaExceededError(error)) {
                lastQuotaExceeded = true
            }
            Log.e("YoutubeApiUtil", "videos error code=${error.optInt("code")} message=${error.optString("message")}")
        }
        if (!videosRes.has("items")) return items
        val videosArray = videosRes.getJSONArray("items")
        Log.d("YoutubeApiUtil", "videos items=${videosArray.length()}")
        for (i in 0 until videosArray.length()) {
            val element = videosArray.getJSONObject(i)
            val snippet = element.getJSONObject("snippet")
            var duration = element.getJSONObject("contentDetails").getString("duration")
            duration = formatDuration(duration)
            snippet.put("videoID", element.getString("id"))
            snippet.put("duration", duration)
            fixThumbnail(snippet)
            val v = createVideofromJSON(snippet)
            if (v == null || v.thumb.isEmpty()) {
                Log.d("YoutubeApiUtil", "skip video id=${element.optString("id")} title=${snippet.optString("title")} thumbEmpty=${v?.thumb.isNullOrEmpty()}")
                continue
            }
            if (i < 3) {
                val localizedTitle = snippet.optJSONObject("localized")?.optString("title").orEmpty()
                Log.d("YoutubeApiUtil", "video[$i] title=${snippet.optString("title")} localized=$localizedTitle channel=${snippet.optString("channelTitle")}")
            }
            items.add(v)
        }
        registerApiSuccess()
        return items
    }

    fun wasQuotaExceeded(): Boolean = lastQuotaExceeded

    fun searchChannelByName(query: String, language: String, region: String): ChannelInfo? {
        val key = sharedPreferences.getString("api_key", "") ?: ""
        if (key.isBlank() || query.isBlank()) return null
        if (isTemporarilyDisabled()) return null
        lastQuotaExceeded = false
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val searchUrl = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=channel&maxResults=1" +
            "&q=$encodedQuery&relevanceLanguage=$language&regionCode=$region&hl=$language&key=$key"
        val searchRes = NetworkUtil.genericRequest(searchUrl)
        if (isApiBlocked(searchRes)) {
            registerApiFailure(searchRes)
            return null
        }
        searchRes.optJSONObject("error")?.let { error ->
            if (isQuotaExceededError(error)) {
                lastQuotaExceeded = true
            }
            Log.e("YoutubeApiUtil", "channel search error code=${error.optInt("code")} message=${error.optString("message")}")
        }
        if (!searchRes.has("items")) return null
        val dataArray = searchRes.getJSONArray("items")
        if (dataArray.length() == 0) return null
        val element = dataArray.getJSONObject(0)
        val idObj = element.optJSONObject("id")
        val channelId = idObj?.optString("channelId").orEmpty()
        if (channelId.isBlank()) return null
        val snippet = element.getJSONObject("snippet")
        fixThumbnail(snippet)
        val iconUrl = snippet.optString("thumb").orEmpty()
        val title = snippet.optString("channelTitle").orEmpty()
        val channelUrl = "https://www.youtube.com/channel/$channelId"
        if (iconUrl.isBlank()) return null
        registerApiSuccess()
        return ChannelInfo(channelId, title, iconUrl, channelUrl)
    }

    fun searchChannelsByName(query: String, language: String, region: String, maxResults: Int = 5): List<ChannelInfo> {
        val key = sharedPreferences.getString("api_key", "") ?: ""
        if (key.isBlank() || query.isBlank()) return emptyList()
        if (isTemporarilyDisabled()) return emptyList()
        lastQuotaExceeded = false
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val searchUrl = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=channel&maxResults=$maxResults" +
            "&q=$encodedQuery&relevanceLanguage=$language&regionCode=$region&hl=$language&key=$key"
        val searchRes = NetworkUtil.genericRequest(searchUrl)
        if (isApiBlocked(searchRes)) {
            registerApiFailure(searchRes)
            return emptyList()
        }
        searchRes.optJSONObject("error")?.let { error ->
            if (isQuotaExceededError(error)) {
                lastQuotaExceeded = true
            }
            Log.e("YoutubeApiUtil", "channel search error code=${error.optInt("code")} message=${error.optString("message")}")
        }
        if (!searchRes.has("items")) return emptyList()
        val dataArray = searchRes.getJSONArray("items")
        if (dataArray.length() == 0) return emptyList()
        val results = ArrayList<ChannelInfo>()
        for (i in 0 until dataArray.length()) {
            val element = dataArray.getJSONObject(i)
            val idObj = element.optJSONObject("id")
            val channelId = idObj?.optString("channelId").orEmpty()
            if (channelId.isBlank()) continue
            val snippet = element.getJSONObject("snippet")
            fixThumbnail(snippet)
            val iconUrl = snippet.optString("thumb").orEmpty()
            if (iconUrl.isBlank()) continue
            val title = snippet.optString("channelTitle").orEmpty()
            val channelUrl = "https://www.youtube.com/channel/$channelId"
            results.add(ChannelInfo(channelId, title, iconUrl, channelUrl))
        }
        registerApiSuccess()
        return results
    }

    private fun isQuotaExceededError(error: JSONObject): Boolean {
        val errors = error.optJSONArray("errors")
        if (errors != null) {
            for (i in 0 until errors.length()) {
                val reason = errors.optJSONObject(i)?.optString("reason")?.lowercase(Locale.US).orEmpty()
                if (reason.contains("quota") || reason.contains("rate")) {
                    return true
                }
            }
        }
        val message = error.optString("message").lowercase(Locale.US)
        return message.contains("quota")
    }

    private fun formatDuration(dur: String): String {
        var badDur = dur
        if (dur == "P0D") {
            return "LIVE"
        }
        var hours = false
        var duration = ""
        badDur = badDur.substring(2)
        if (badDur.contains("H")) {
            hours = true
            duration += String.format(
                Locale.getDefault(),
                "%02d",
                badDur.substring(0, badDur.indexOf("H")).toInt()
            ) + ":"
            badDur = badDur.substring(badDur.indexOf("H") + 1)
        }
        if (badDur.contains("M")) {
            duration += String.format(
                Locale.getDefault(),
                "%02d",
                badDur.substring(0, badDur.indexOf("M")).toInt()
            ) + ":"
            badDur = badDur.substring(badDur.indexOf("M") + 1)
        } else if (hours) duration += "00:"
        if (badDur.contains("S")) {
            if (duration.isEmpty()) duration = "00:"
            duration += String.format(
                Locale.getDefault(),
                "%02d",
                badDur.substring(0, badDur.indexOf("S")).toInt()
            )
        } else {
            duration += "00"
        }
        if (duration == "00:00") {
            duration = ""
        }
        return duration
    }

    private fun fixThumbnail(o: JSONObject): JSONObject {
        var imageURL = ""
        try {
            val thumbs = o.getJSONObject("thumbnails")
            imageURL = thumbs.getJSONObject("maxres").getString("url")
        } catch (e: Exception) {
            try {
                val thumbs = o.getJSONObject("thumbnails")
                imageURL = thumbs.getJSONObject("high").getString("url")
            } catch (u: Exception) {
                try {
                    val thumbs = o.getJSONObject("thumbnails")
                    imageURL = thumbs.getJSONObject("default").getString("url")
                } catch (ignored: Exception) {
                }
            }
        }
        try {
            o.put("thumb", imageURL)
        } catch (ignored: Exception) {
        }
        return o
    }

    private fun isTemporarilyDisabled(): Boolean {
        val until = sharedPreferences.getLong(prefDisabledUntil, 0L)
        return System.currentTimeMillis() < until
    }

    private fun isApiBlocked(response: JSONObject): Boolean {
        val httpCode = response.optInt("_httpCode", 200)
        if (httpCode == 403) return true
        response.optJSONObject("error")?.let { error ->
            if (error.optInt("code") == 403) return true
            if (isQuotaExceededError(error)) return true
        }
        return false
    }

    private fun registerApiFailure(response: JSONObject) {
        val httpCode = response.optInt("_httpCode", 0)
        val is403 = httpCode == 403 || response.optJSONObject("error")?.optInt("code") == 403
        val quota = response.optJSONObject("error")?.let { isQuotaExceededError(it) } == true
        if (!is403 && !quota) return
        lastQuotaExceeded = quota
        val streak = sharedPreferences.getInt(pref403Streak, 0) + 1
        sharedPreferences.edit().putInt(pref403Streak, streak).apply()
        if (streak < 3) return
        val nextLevel = 6
        val disabledUntil = System.currentTimeMillis() + (6 * 60 * 60 * 1000L)
        sharedPreferences.edit()
            .putLong(prefDisabledUntil, disabledUntil)
            .putInt(prefBackoffLevel, nextLevel)
            .apply()
        Log.w("YoutubeApiUtil", "YouTube API temporarily disabled until=$disabledUntil streak=$streak level=$nextLevel")
    }

    private fun registerApiSuccess() {
        sharedPreferences.edit()
            .putInt(pref403Streak, 0)
            .putInt(prefBackoffLevel, 0)
            .putLong(prefDisabledUntil, 0L)
            .apply()
    }
}
