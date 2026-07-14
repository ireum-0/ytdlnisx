package com.ireum.ytdl.util.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCommandPlanTest {
    @Test
    fun plannerSanitizesConfigAndBuildsOneExecutionRepresentation() {
        val plan = TerminalCommandPlanner.create(
            command = "--exec 'echo unsafe' --cookies /untrusted/cookies.txt https://example.com/video",
            environment = environment(
                cookiePath = "/app/cache/cookies.txt",
                userAgentHeader = "private-agent"
            )
        )

        assertFalse(plan.sanitizedConfig.contains("--exec"))
        assertFalse(plan.sanitizedConfig.contains("echo unsafe"))
        assertTrue(plan.removedOptions.contains("--exec"))
        assertEquals(
            listOf(
                TerminalRequestOption("--cookies", "/app/cache/cookies.txt"),
                TerminalRequestOption("--add-header", "User-Agent:private-agent"),
                TerminalRequestOption("-P", "/app/cache/TERMINAL/42")
            ),
            plan.requestOptions
        )
        assertTrue(plan.usesAppCache)
    }

    @Test
    fun explicitOutputPathTakesPrecedenceOverAppDestination() {
        val plan = TerminalCommandPlanner.create(
            command = "-P /storage/emulated/0/Custom https://example.com/video",
            environment = environment(cacheDownloads = true, destinationWritable = false)
        )

        assertFalse(plan.usesAppCache)
        assertFalse(plan.requestOptions.any { it.name == "-P" })
    }

    @Test
    fun writableDirectDestinationAndCacheFallbackStayDistinct() {
        val direct = TerminalCommandPlanner.create(
            command = "https://example.com/video",
            environment = environment(cacheDownloads = false, destinationWritable = true)
        )
        val fallback = TerminalCommandPlanner.create(
            command = "https://example.com/video",
            environment = environment(cacheDownloads = false, destinationWritable = false)
        )

        assertFalse(direct.usesAppCache)
        assertEquals("/formatted/output", direct.requestOptions.last().value)
        assertTrue(fallback.usesAppCache)
        assertEquals("/app/cache/TERMINAL/42", fallback.requestOptions.last().value)
    }

    @Test
    fun previewRedactsSecretsAndPrivatePathsFromEffectiveArguments() {
        val plan = TerminalCommandPlanner.create(
            command = """
                --password hunter2
                --cookies
                /storage/private/account-cookie.jar
                --exec bad
                https://example.com/video?token=secret
            """.trimIndent(),
            environment = environment(
                cookiePath = "/data/user/0/app/cache/cookies.txt",
                userAgentHeader = "private-agent"
            )
        )
        val preview = TerminalCommandPreviewFormatter.format(
            plan = plan,
            effectiveArguments = listOf(
                "--config-locations",
                "/data/user/0/app/cache/config-TERMINAL[1].txt",
                "--cookies",
                "/data/user/0/app/cache/cookies.txt",
                "--add-header",
                "User-Agent:private-agent",
                "--js-runtimes",
                "quickjs:/data/user/0/app/lib/libqjs.so",
                "https://example.com/video?token=secret"
            ),
            privatePathPrefixes = listOf("/data/user/0/app"),
            configHeading = "Config",
            argumentsHeading = "Arguments",
            removedHeading = "Removed"
        )

        assertTrue(preview.contains("Config"))
        assertTrue(preview.contains("Arguments"))
        assertTrue(preview.contains("Removed"))
        assertTrue(preview.contains("***"))
        assertTrue(preview.contains("<app-storage>"))
        assertFalse(preview.contains("hunter2"))
        assertFalse(preview.contains("account-cookie.jar"))
        assertFalse(preview.contains("private-agent"))
        assertFalse(preview.contains("token=secret"))
        assertFalse(preview.contains("/data/user/0/app"))
        assertFalse(preview.substringBefore("\nRemoved").contains("--exec"))
        assertTrue(preview.substringAfter("Removed").contains("--exec"))
    }

    @Test
    fun terminalExecutablePrefixIsNormalizedOnce() {
        assertEquals(
            " --no-playlist https://example.com/video",
            TerminalCommandPlanner.normalizeInput("yt-dlp --no-playlist https://example.com/video")
        )
    }

    private fun environment(
        cookiePath: String? = null,
        userAgentHeader: String? = null,
        cacheDownloads: Boolean = true,
        destinationWritable: Boolean = true
    ) = TerminalCommandEnvironment(
        cookiePath = cookiePath,
        userAgentHeader = userAgentHeader,
        downloadLocation = "/raw/output",
        formattedDownloadLocation = "/formatted/output",
        appCacheOutputPath = "/app/cache/TERMINAL/42",
        cacheDownloads = cacheDownloads,
        destinationWritable = destinationWritable
    )
}
