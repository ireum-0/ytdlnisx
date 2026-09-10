package com.ireum.ytdl.work

import com.ireum.ytdl.util.extractors.ytdlp.YtdlpProducerSemanticSnapshot
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadProducerSemanticFingerprintTest {
    @Test
    fun productionMaterializedConfigSemanticsParticipateBeyondOuterLauncher() {
        val root = Files.createTempDirectory("producer-effective-config").toFile()
        try {
            val source = "https://youtube.com/watch?v=dQw4w9WgXcQ"
            val configA = "--format bv\n--write-subs\n"
            val configB = "--format worst\n--write-subs\n"
            val requestA = YoutubeDLRequest(
                listOf("--config-locations", File(root, "generated-a.txt").absolutePath, source),
            )
            val requestB = YoutubeDLRequest(
                listOf("--config-locations", File(root, "generated-b.txt").absolutePath, source),
            )
            YtdlpProducerSemanticSnapshot.register(
                request = requestA,
                configContents = configA,
                runtimePaths = listOf(File(root, "generated-a.txt").absolutePath),
            )
            YtdlpProducerSemanticSnapshot.register(
                request = requestB,
                configContents = configB,
                runtimePaths = listOf(File(root, "generated-b.txt").absolutePath),
            )

            fun fingerprint(request: YoutubeDLRequest): String {
                val snapshot = requireNotNull(YtdlpProducerSemanticSnapshot.forRequest(request))
                val command = request.buildCommand().joinToString(" ")
                return DownloadProducerSemanticFingerprint.fingerprint(
                    command = command,
                    effectiveProducerSemantics = snapshot.configContents,
                    runtimePaths = snapshot.runtimePaths,
                )
            }

            assertNotEquals(fingerprint(requestA), fingerprint(requestB))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun productionMaterializedConfigPathDoesNotChangeFingerprint() {
        val root = Files.createTempDirectory("producer-effective-config-path").toFile()
        try {
            val source = "https://youtube.com/watch?v=dQw4w9WgXcQ"
            val config = "--format bv\n--postprocessor-args \"VideoConvertor:-c:v copy\"\n"
            val requestA = YoutubeDLRequest(
                listOf("--config-locations", File(root, "generated-a.txt").absolutePath, source),
            )
            val requestB = YoutubeDLRequest(
                listOf("--config-locations", File(root, "generated-b.txt").absolutePath, source),
            )
            listOf(
                requestA to "generated-a.txt",
                requestB to "generated-b.txt",
            ).forEach { (request, name) ->
                val path = File(root, name).absolutePath
                YtdlpProducerSemanticSnapshot.register(
                    request = request,
                    configContents = config,
                    runtimePaths = listOf(path),
                )
            }

            fun fingerprint(request: YoutubeDLRequest): String {
                val snapshot = requireNotNull(YtdlpProducerSemanticSnapshot.forRequest(request))
                return DownloadProducerSemanticFingerprint.fingerprint(
                    command = request.buildCommand().joinToString(" "),
                    effectiveProducerSemantics = snapshot.configContents,
                    runtimePaths = snapshot.runtimePaths,
                )
            }

            assertEquals(fingerprint(requestA), fingerprint(requestB))
        } finally {
            root.deleteRecursively()
        }
    }

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
