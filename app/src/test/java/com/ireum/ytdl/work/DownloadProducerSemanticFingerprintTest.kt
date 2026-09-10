package com.ireum.ytdl.work

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadProducerSemanticFingerprintTest {
    @Test
    fun runtimePathsDoNotChangeFingerprintButOutputSemanticsDo() {
        val root = Files.createTempDirectory("producer-fingerprint").toFile()
        try {
            val first = DownloadProducerSemanticFingerprint.fingerprint(
                "yt-dlp --config-locations ${File(root, "config-a")} " +
                    "--download-archive ${File(root, "archive-a")} " +
                    "https://youtu.be/dQw4w9WgXcQ -o '%(title)s.%(ext)s'",
            )
            val second = DownloadProducerSemanticFingerprint.fingerprint(
                "yt-dlp --config-locations ${File(root, "config-b")} " +
                    "--download-archive ${File(root, "archive-b")} " +
                    "https://youtu.be/dQw4w9WgXcQ -o '%(title)s.%(ext)s'",
            )
            val changed = DownloadProducerSemanticFingerprint.fingerprint(
                "yt-dlp --config-locations ${File(root, "config-b")} " +
                    "--download-archive ${File(root, "archive-b")} " +
                    "https://youtu.be/dQw4w9WgXcQ -f bestaudio -o '%(title)s.%(ext)s'",
            )
            assertEquals(first, second)
            assertNotEquals(first, changed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sourceSpellingIsCanonicalButOptionOwnedUrlRemainsSemantic() {
        val watch = DownloadProducerSemanticFingerprint.fingerprint(
            "yt-dlp --referer https://youtube.com/watch?v=AAA111BBB22 " +
                "https://youtube.com/watch?v=dQw4w9WgXcQ -o '%(title)s.%(ext)s'",
        )
        val equivalent = DownloadProducerSemanticFingerprint.fingerprint(
            "yt-dlp --referer https://youtube.com/watch?v=AAA111BBB22 " +
                "https://youtu.be/dQw4w9WgXcQ -o '%(title)s.%(ext)s'",
        )
        val changedReferer = DownloadProducerSemanticFingerprint.fingerprint(
            "yt-dlp --referer https://youtu.be/AAA111BBB22 " +
                "https://youtu.be/dQw4w9WgXcQ -o '%(title)s.%(ext)s'",
        )
        assertEquals(watch, equivalent)
        assertNotEquals(watch, changedReferer)
    }

    @Test
    fun publicationModeIsPartOfProducerCompatibility() {
        val command = "yt-dlp https://youtu.be/dQw4w9WgXcQ -o '%(title)s.%(ext)s'"
        val ordinary = DownloadProducerSemanticFingerprint.fingerprint(
            command = command,
            publicationSemantics = mapOf("incognito" to "false", "redownload" to "none"),
        )
        val incognito = DownloadProducerSemanticFingerprint.fingerprint(
            command = command,
            publicationSemantics = mapOf("incognito" to "true", "redownload" to "none"),
        )
        assertNotEquals(ordinary, incognito)
    }
}
