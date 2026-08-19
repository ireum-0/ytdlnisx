package com.ireum.ytdl.database

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {
    @Test
    fun legacyAutoDownloadTemplateGetsCurrentRetryDefaults() {
        val current = DownloadItem(
            id = 1L,
            url = "https://example.com/video",
            title = "Video",
            author = "Creator",
            thumb = "",
            duration = "",
            type = DownloadType.video,
            format = Format(),
            container = "Default",
            downloadSections = "",
            allFormats = mutableListOf(),
            downloadPath = "",
            website = "example.com",
            downloadSize = "",
            playlistTitle = "",
            audioPreferences = AudioPreferences(),
            videoPreferences = VideoPreferences(),
            extraCommands = "",
            customFileNameTemplate = "",
            SaveThumb = false,
            status = "Queued",
            downloadStartTime = 0L,
            logID = null
        )
        val legacyJson = JsonParser.parseString(Gson().toJson(current)).asJsonObject.apply {
            remove("operationId")
            remove("retryAttempt")
            remove("retryStrategy")
            remove("lastIssueCode")
            remove("lastIssueStage")
        }.toString()

        val restored = Converters().stringToDownloadItem(legacyJson)

        assertEquals("", restored.operationId)
        assertEquals(0, restored.retryAttempt)
        assertEquals("ORIGINAL", restored.retryStrategy)
        assertEquals("", restored.lastIssueCode)
        assertEquals("", restored.lastIssueStage)
        restored.hashCode()
    }
}
