package com.ireum.ytdl.util.extractors.ytdlp

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtdlpOutputPlanTest {

    @Test
    fun parsesExplicitShortPathOption() {
        val directory = Files.createTempDirectory("ytdlp-output-plan-short-").toFile()
        val path = directory.absolutePath.replace('\\', '/')
        val result = YtdlpCommandPathParser.resolve("-P \"$path\" --no-playlist")

        assertEquals(directory.canonicalFile, (result as YtdlpCommandPathResolution.Explicit).directory)
    }

    @Test
    fun explicitPathMayEqualOrDifferFromConfiguredDestination() {
        val configured = Files.createTempDirectory("ytdlp-output-plan-configured-").toFile()
        val effective = Files.createTempDirectory("ytdlp-output-plan-effective-").toFile()
        val configuredPath = configured.absolutePath.replace('\\', '/')
        val effectivePath = effective.absolutePath.replace('\\', '/')

        val same = YtdlpCommandPathParser.resolve("-P \"$configuredPath\"")
        val different = YtdlpCommandPathParser.resolve("--paths \"$effectivePath\"")

        assertEquals(configured.canonicalFile, (same as YtdlpCommandPathResolution.Explicit).directory)
        assertEquals(effective.canonicalFile, (different as YtdlpCommandPathResolution.Explicit).directory)
    }

    @Test
    fun parsesEquivalentLongPathsSyntax() {
        val directory = Files.createTempDirectory("ytdlp-output-plan-long-").toFile()
        val path = directory.absolutePath.replace('\\', '/')
        val result = YtdlpCommandPathParser.resolve("--paths=home:$path")

        assertEquals(directory.canonicalFile, (result as YtdlpCommandPathResolution.Explicit).directory)
    }

    @Test
    fun rejectsMalformedOrAmbiguousPathSpecifications() {
        val malformed = YtdlpCommandPathParser.resolve("-P \"/missing")
        val typed = YtdlpCommandPathParser.resolve("--paths temp:/shared/output")
        val relative = YtdlpCommandPathParser.resolve("-P relative/output")
        val multiple = YtdlpCommandPathParser.resolve("-P /one --paths /two")

        assertTrue(malformed is YtdlpCommandPathResolution.Invalid)
        assertTrue(typed is YtdlpCommandPathResolution.Invalid)
        assertTrue(relative is YtdlpCommandPathResolution.Invalid)
        assertTrue(multiple is YtdlpCommandPathResolution.Invalid)
    }

    @Test
    fun optionsAfterDoubleDashDoNotBecomeOutputAuthority() {
        val result = YtdlpCommandPathParser.resolve("-- --paths /ambient")

        assertTrue(result is YtdlpCommandPathResolution.None)
    }
}
