package com.ireum.ytdl.util.preset

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPresetTest {
    @Test
    fun applyingPresetCopiesTypedConfigurationAndPreservesSourceState() {
        val item = downloadItem().copy(
            url = "https://example.test/watch?v=1",
            downloadPath = "/chosen/output",
            extraCommands = "--site-derived-option value",
            downloadSections = "10-20",
            videoPreferences = VideoPreferences(audioFormatIDs = arrayListOf("251"))
        )
        val preset = DownloadPreset(
            id = "preset-1",
            name = "Audio chapters",
            configuration = DownloadPresetConfiguration(
                type = PresetDownloadType.AUDIO,
                container = "mp3",
                saveThumbnail = true,
                audio = PresetAudioConfiguration(
                    embedThumbnail = false,
                    splitByChapters = true,
                    sponsorBlockFilters = listOf("sponsor"),
                    bitrate = "192k"
                )
            )
        )
        val selectedFormat = Format(format_id = "audio-best", vcodec = "none")

        val applied = DownloadPresetMapper.applyTo(item, preset, selectedFormat)

        assertEquals(DownloadType.audio, applied.type)
        assertEquals("mp3", applied.container)
        assertEquals(selectedFormat, applied.format)
        assertTrue(applied.audioPreferences.splitByChapters)
        assertFalse(applied.audioPreferences.embedThumb)
        assertEquals("192k", applied.audioPreferences.bitrate)
        assertEquals("https://example.test/watch?v=1", applied.url)
        assertEquals("/chosen/output", applied.downloadPath)
        assertEquals("--site-derived-option value", applied.extraCommands)
        assertEquals("10-20", applied.downloadSections)
        assertEquals(listOf("251"), applied.videoPreferences.audioFormatIDs)
        assertNotSame(item.audioPreferences, applied.audioPreferences)
    }

    @Test
    fun manualChangesAfterApplyDoNotMutatePreset() {
        val preset = DownloadPresetMapper.fromDownloadItem("id", "Original", downloadItem())!!
        val applied = DownloadPresetMapper.applyTo(downloadItem(), preset)

        applied.audioPreferences.bitrate = "64k"
        applied.audioPreferences.sponsorBlockFilters += "intro"

        assertEquals("192k", preset.configuration.audio.bitrate)
        assertEquals(listOf("sponsor"), preset.configuration.audio.sponsorBlockFilters)
    }

    @Test
    fun codecRejectsUnsafeOrUnsupportedOptionValues() {
        val unsafe = DownloadPreset(
            id = "id",
            name = "  Unsafe\nname  ",
            configuration = DownloadPresetConfiguration(
                type = PresetDownloadType.VIDEO,
                container = "--exec",
                audio = PresetAudioConfiguration(
                    sponsorBlockFilters = listOf("sponsor", "unknown;command"),
                    bitrate = "999k"
                ),
                video = PresetVideoConfiguration(
                    subtitleLanguages = "en\n--exec",
                    waitForVideoMinutes = 999_999
                )
            )
        )

        val decoded = DownloadPresetCodec.decode(DownloadPresetCodec.encode(listOf(unsafe))).single()

        assertEquals("Unsafe name", decoded.name)
        assertEquals("", decoded.configuration.container)
        assertEquals("", decoded.configuration.audio.bitrate)
        assertEquals(listOf("sponsor"), decoded.configuration.audio.sponsorBlockFilters)
        assertEquals("en--exec", decoded.configuration.video.subtitleLanguages)
        assertEquals(1_440, decoded.configuration.video.waitForVideoMinutes)
    }

    @Test
    fun commandItemsCannotBecomePresets() {
        assertEquals(
            null,
            DownloadPresetMapper.fromDownloadItem(
                "id",
                "Command",
                downloadItem().copy(type = DownloadType.command)
            )
        )
    }

    @Test
    fun serializedPresetExcludesSourcePathsCommandsAndMetadata() {
        val source = downloadItem().copy(
            url = "https://private.example.test/token",
            downloadPath = "/private/output/path",
            extraCommands = "--cookies /private/cookies.txt",
            customFileNameTemplate = "private-template"
        )
        val preset = DownloadPresetMapper.fromDownloadItem("id", "Safe", source)!!

        val encoded = DownloadPresetCodec.encode(listOf(preset))

        assertFalse(encoded.contains(source.url))
        assertFalse(encoded.contains(source.downloadPath))
        assertFalse(encoded.contains(source.extraCommands))
        assertFalse(encoded.contains(source.customFileNameTemplate))
    }

    private fun downloadItem(): DownloadItem {
        return DownloadItem(
            id = 0,
            url = "https://example.test",
            title = "Title",
            author = "Author",
            thumb = "",
            duration = "1:00",
            type = DownloadType.audio,
            format = Format(format_id = "140", vcodec = "none"),
            container = "m4a",
            downloadSections = "",
            allFormats = mutableListOf(),
            downloadPath = "/output",
            website = "example",
            downloadSize = "",
            playlistTitle = "",
            audioPreferences = AudioPreferences(
                embedThumb = true,
                cropThumb = false,
                splitByChapters = false,
                sponsorBlockFilters = arrayListOf("sponsor"),
                bitrate = "192k"
            ),
            videoPreferences = VideoPreferences(),
            extraCommands = "",
            customFileNameTemplate = "%(title)s",
            SaveThumb = false,
            status = "Queued",
            downloadStartTime = 0,
            logID = null
        )
    }
}
