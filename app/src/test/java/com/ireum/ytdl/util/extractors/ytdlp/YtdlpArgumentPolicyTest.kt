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

    @Test
    fun fixedArityMetadataDataIsNotReclassifiedByArgumentSanitizer() {
        val original = listOf(
            "--replace-in-metadata",
            "title",
            "--exec",
            "replacement",
            "https://example.com/video",
        )
        assertEquals(original, YtdlpArgumentPolicy.sanitize(original, emptySet()))

        val equalsForm = listOf(
            "--replace-in-metadata=title",
            "--ffmpeg-location",
            "replacement",
            "https://example.com/video",
        )
        assertEquals(equalsForm, YtdlpArgumentPolicy.sanitize(equalsForm, emptySet()))
    }

    @Test
    fun generatedConfigSanitizerSharesFixedArityOwnershipAcrossLines() {
        val sanitized = YtdlpArgumentPolicy.stripExternalFfmpegLocationOptionsWithReport(
            """
            # A metadata replacement value may look like a blocked option
            --replace-in-metadata
            title
            --ffmpeg-location
            replacement
            --replace-in-metadata=artist --config-locations replacement
            --exec
            echo unsafe
            https://example.com/video
            """.trimIndent()
        )

        assertEquals(listOf("--exec"), sanitized.removedOptions)
        assertTrue(sanitized.commandString.contains("--replace-in-metadata"))
        assertTrue(sanitized.commandString.contains("--ffmpeg-location"))
        assertTrue(sanitized.commandString.contains("--config-locations"))
        assertFalse(sanitized.commandString.contains("echo unsafe"))
        assertTrue(sanitized.commandString.contains("https://example.com/video"))
    }

    @Test
    fun realSensitiveOptionsRemainBlockedWhenFollowingFixedArityData() {
        val sanitized = YtdlpArgumentPolicy.stripExternalFfmpegLocationOptionsWithReport(
            "--replace-in-metadata title --exec replacement --exec echo unsafe"
        )

        assertEquals(listOf("--exec"), sanitized.removedOptions)
        assertTrue(
            YtdlpCommandTokenizer.tokenize(sanitized.commandString)
                ?.containsAll(listOf("--replace-in-metadata", "title", "--exec", "replacement")) == true
        )
        assertFalse(sanitized.commandString.contains("echo unsafe"))
    }

    @Test
    fun shlexProtectedSensitiveLookalikesRemainDataThroughSanitization() {
        val original = """
            "\--exec" literal
            "\--ffmpeg-location" /data/literal-ffmpeg
            "\--config-locations" /data/literal-config
            "\-P" /data/literal-path
            "\--paths" /data/literal-paths
            "\-o" literal-output
            "\--output" literal-long-output
        """.trimIndent()
        val expected = requireNotNull(YtdlpCommandTokenizer.tokenize(original))

        val sanitized = YtdlpArgumentPolicy.stripExternalFfmpegLocationOptionsWithReport(original)

        assertTrue(sanitized.removedOptions.isEmpty())
        assertEquals(expected, YtdlpCommandTokenizer.tokenize(sanitized.commandString))
        assertTrue(
            YtdlpCommandPathParser.resolve(sanitized.commandString) is
                YtdlpCommandPathResolution.None,
        )
        assertTrue(
            YtdlpCommandOutputTemplateParser.resolve(sanitized.commandString) is
                YtdlpCommandOutputTemplateResolution.None,
        )
    }

    @Test
    fun actualSensitiveOptionsRemainBlockedBesideProtectedLookalikeData() {
        val original = """
            "\--exec" literal
            --exec echo unsafe
            "\--ffmpeg-location" /data/literal-ffmpeg
            --ffmpeg-location /external/ffmpeg
            "\--config-locations" /data/literal-config
            --config-locations /external/config
        """.trimIndent()

        val sanitized = YtdlpArgumentPolicy.stripExternalFfmpegLocationOptionsWithReport(original)
        val tokens = requireNotNull(YtdlpCommandTokenizer.tokenize(sanitized.commandString))

        assertEquals(
            listOf("--exec", "--ffmpeg-location", "--config-locations"),
            sanitized.removedOptions,
        )
        assertTrue(tokens.containsAll(listOf("\\--exec", "\\--ffmpeg-location", "\\--config-locations")))
        assertFalse(tokens.contains("--exec"))
        assertFalse(tokens.contains("--ffmpeg-location"))
        assertFalse(tokens.contains("--config-locations"))
        assertEquals(
            listOf(
                "\\--exec",
                "literal",
                "unsafe",
                "\\--ffmpeg-location",
                "/data/literal-ffmpeg",
                "\\--config-locations",
                "/data/literal-config",
            ),
            tokens,
        )
    }

    @Test
    fun sanitizerPreservesShlexEscapedQuotesBackslashesAndComments() {
        val original = """
            --replace-in-metadata title "a\"b" "a\\b"
            "quoted # hash" \# literal # comment
            ""
        """.trimIndent()
        val expected = requireNotNull(YtdlpCommandTokenizer.tokenize(original))
        val sanitized = YtdlpArgumentPolicy.stripExternalFfmpegLocationOptions(original)

        assertEquals(expected, YtdlpCommandTokenizer.tokenize(sanitized))
    }
}
