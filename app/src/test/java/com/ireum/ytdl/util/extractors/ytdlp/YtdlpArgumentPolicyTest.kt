package com.ireum.ytdl.util.extractors.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class YtdlpArgumentPolicyTest {
    @Test
    fun sanitizeRemovesExecOptions() {
        assertEquals(
            listOf("https://example.com/video"),
            YtdlpArgumentPolicy.sanitize(
                listOf("--exec", "echo unsafe", "https://example.com/video"),
                emptySet()
            )
        )

        assertEquals(
            listOf("https://example.com/video"),
            YtdlpArgumentPolicy.sanitize(
                listOf("--exec=echo unsafe", "https://example.com/video"),
                emptySet()
            )
        )
    }

    @Test
    fun sanitizeRemovesDangerousOptionValuesEvenWhenTheyStartWithDash() {
        assertEquals(
            listOf("https://example.com/video"),
            YtdlpArgumentPolicy.sanitize(
                listOf(
                    "--exec",
                    "-rm",
                    "--external-downloader",
                    "-custom-downloader",
                    "--plugin-dirs",
                    "-plugins",
                    "https://example.com/video"
                ),
                emptySet()
            )
        )
    }

    @Test
    fun sanitizeKeepsOnlyBundledDownloader() {
        assertEquals(
            listOf("--downloader", "libaria2c.so", "https://example.com/video"),
            YtdlpArgumentPolicy.sanitize(
                listOf(
                    "--downloader",
                    "libaria2c.so",
                    "--downloader",
                    "/sdcard/custom-downloader",
                    "https://example.com/video"
                ),
                emptySet()
            )
        )
    }

    @Test
    fun sanitizeKeepsOnlyKnownSafePostprocessorValues() {
        assertEquals(
            listOf(
                "--use-postprocessor",
                "FFmpegCopyStream",
                "--ppa",
                "CopyStream:-c copy -an",
                "https://example.com/video"
            ),
            YtdlpArgumentPolicy.sanitize(
                listOf(
                    "--use-postprocessor",
                    "FFmpegCopyStream",
                    "--ppa",
                    "CopyStream:-c copy -an",
                    "--ppa",
                    "UnsafeProcessor:--exec bad",
                    "https://example.com/video"
                ),
                emptySet()
            )
        )
    }

    @Test
    fun sanitizeKeepsOnlyAllowedConfigFiles() {
        val allowed = File.createTempFile("allowed-ytdlp", ".conf")
        val blocked = File.createTempFile("blocked-ytdlp", ".conf")
        try {
            assertEquals(
                listOf(
                    "--config",
                    allowed.absolutePath,
                    "--config=${allowed.absolutePath}",
                    "https://example.com/video"
                ),
                YtdlpArgumentPolicy.sanitize(
                    listOf(
                        "--config",
                        allowed.absolutePath,
                        "--config",
                        blocked.absolutePath,
                        "--config=/sdcard/external.conf",
                        "--config=${allowed.absolutePath}",
                        "https://example.com/video"
                    ),
                    setOf(allowed)
                )
            )
        } finally {
            allowed.delete()
            blocked.delete()
        }
    }

    @Test
    fun stripExternalOptionsRemovesDangerousLinesAndKeepsSafeValues() {
        val allowed = YtdlpArgumentPolicy.stripExternalFfmpegLocationOptions(
            """
            --downloader libaria2c.so
            --use-postprocessor FFmpegCopyStream
            --ppa "CopyStream:-c copy -an"
            https://example.com/video
            """.trimIndent()
        )

        assertTrue(allowed.contains("--downloader libaria2c.so"))
        assertTrue(allowed.contains("--use-postprocessor FFmpegCopyStream"))
        assertTrue(allowed.contains("""--ppa "CopyStream:-c copy -an""""))

        val stripped = YtdlpArgumentPolicy.stripExternalFfmpegLocationOptions(
            """
            --exec
            -rm
            --plugin-dirs /sdcard/plugins
            --netrc-cmd "cat /sdcard/netrc"
            --ppa "UnsafeProcessor:--exec bad"
            https://example.com/video
            """.trimIndent()
        )

        assertFalse(stripped.contains("--exec"))
        assertFalse(stripped.contains("-rm"))
        assertFalse(stripped.contains("--plugin-dirs"))
        assertFalse(stripped.contains("--netrc-cmd"))
        assertFalse(stripped.contains("UnsafeProcessor"))
        assertTrue(stripped.contains("https://example.com/video"))
    }
}
