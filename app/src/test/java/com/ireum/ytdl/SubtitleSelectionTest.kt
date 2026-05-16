package com.ireum.ytdl

import com.ireum.ytdl.util.SubtitleFileValidator
import com.ireum.ytdl.util.SubtitleFormatConverter
import com.ireum.ytdl.util.SubtitleSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SubtitleSelectionTest {
    @Test
    fun koreanSelectionUsesManualKoreanOnlyPattern() {
        val request = SubtitleSelection.normalize("ko")

        assertEquals("ko.*,ko", request.subLanguages)
        assertFalse(request.liveChatOnly)
        assertTrue(SubtitleSelection.isSelectedSubtitleFile(File("video.ko.json3"), request))
        assertFalse(SubtitleSelection.isSelectedSubtitleFile(File("video.live_chat.json"), request))
    }

    @Test
    fun liveChatSelectionDoesNotIncludeLanguageTracks() {
        val request = SubtitleSelection.normalize("live_chat")

        assertEquals("live_chat", request.subLanguages)
        assertTrue(request.liveChatOnly)
        assertTrue(SubtitleSelection.isSelectedSubtitleFile(File("video.live_chat.json"), request))
        assertFalse(SubtitleSelection.isSelectedSubtitleFile(File("video.ko.json3"), request))
    }

    @Test
    fun automaticCaptionTracksAreExcluded() {
        assertTrue(SubtitleSelection.isAutomaticCaption("ja", "a.ja", "asr"))
        assertFalse(SubtitleSelection.isAutomaticCaption("ko", ".ko", ""))
    }

    @Test
    fun emptyJson3SubtitleIsNotSuccessful() {
        val file = File.createTempFile("subtitle-empty", ".json3")
        try {
            file.writeText("""{"events":[]}""")
            val result = SubtitleFileValidator.validate(file, liveChat = false)

            assertFalse(result.valid)
            assertEquals("json-no-cues", result.reason)
        } finally {
            file.delete()
        }
    }

    @Test
    fun fLLX2rRwvEManualKoreanCaptionTrackIsAccepted() {
        assertFalse(SubtitleSelection.isAutomaticCaption("ko", ".ko", ""))
        assertTrue(SubtitleSelection.isAutomaticCaption("ja", "a.ja", "asr"))
    }

    @Test
    fun json3SubtitleCanBeConvertedForHardSubBurnIn() {
        val file = File.createTempFile("subtitle-json3", ".json3")
        try {
            file.writeText(
                """
                {
                  "events": [
                    {
                      "tStartMs": 1000,
                      "dDurationMs": 2500,
                      "segs": [
                        { "utf8": "hello" },
                        { "utf8": "\nworld" }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )

            val ass = SubtitleFormatConverter.convertJson3ToAss(file)
            val srt = SubtitleFormatConverter.convertJson3ToSrt(file)

            assertTrue(ass?.exists() == true)
            assertTrue(ass!!.readText().contains("Dialogue:"))
            assertTrue(srt?.exists() == true)
            assertTrue(srt!!.readText().contains("00:00:01,000 --> 00:00:03,500"))
        } finally {
            File(file.parentFile, "${file.nameWithoutExtension}.burnin_tmp.ass").delete()
            File(file.parentFile, "${file.nameWithoutExtension}.srt").delete()
            file.delete()
        }
    }
}
