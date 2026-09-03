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

        assertEquals(directory.canonicalFile, (result as YtdlpCommandPathResolution.Explicit).pathMap.home)
    }

    @Test
    fun parsesAttachedShortAndEqualsForms() {
        val directory = Files.createTempDirectory("ytdlp-output-plan-short-forms-").toFile()
        val path = directory.absolutePath.replace('\\', '/')

        val attached = YtdlpCommandPathParser.resolve("-P$path")
        val equals = YtdlpCommandPathParser.resolve("-P=\"$path\"")

        assertEquals(directory.canonicalFile, (attached as YtdlpCommandPathResolution.Explicit).pathMap.home)
        assertEquals(directory.canonicalFile, (equals as YtdlpCommandPathResolution.Explicit).pathMap.home)
    }

    @Test
    fun parsesUnqualifiedHomeAndSeparateHomeAndTempDeclarations() {
        val configured = Files.createTempDirectory("ytdlp-output-plan-configured-").toFile()
        val effective = Files.createTempDirectory("ytdlp-output-plan-effective-").toFile()
        val temporary = Files.createTempDirectory("ytdlp-output-plan-temp-").toFile()
        val configuredPath = configured.absolutePath.replace('\\', '/')
        val effectivePath = effective.absolutePath.replace('\\', '/')
        val temporaryPath = temporary.absolutePath.replace('\\', '/')

        val map = (YtdlpCommandPathParser.resolve(
            "-P \"$configuredPath\" --paths home:\"$effectivePath\" --paths temp:\"$temporaryPath\""
        ) as YtdlpCommandPathResolution.Explicit).pathMap

        assertEquals(effective.canonicalFile, map.home)
        assertEquals(temporary.canonicalFile, map.temp)
    }

    @Test
    fun repeatedDeclarationsUseTheLastValueForEachEffectiveKey() {
        val firstHome = Files.createTempDirectory("ytdlp-output-plan-first-home-").toFile()
        val lastHome = Files.createTempDirectory("ytdlp-output-plan-last-home-").toFile()
        val firstTemp = Files.createTempDirectory("ytdlp-output-plan-first-temp-").toFile()
        val lastTemp = Files.createTempDirectory("ytdlp-output-plan-last-temp-").toFile()

        val map = (YtdlpCommandPathParser.resolve(
            "--paths home:${firstHome.absolutePath.replace('\\', '/')} " +
                "--paths temp:${firstTemp.absolutePath.replace('\\', '/')} " +
                "-P ${lastHome.absolutePath.replace('\\', '/')} " +
                "--paths temp:${lastTemp.absolutePath.replace('\\', '/')}"
        ) as YtdlpCommandPathResolution.Explicit).pathMap

        assertEquals(lastHome.canonicalFile, map.home)
        assertEquals(lastTemp.canonicalFile, map.temp)
    }

    @Test
    fun commaSeparatedHomeAndTempTypesApplyToBothKeys() {
        val directory = Files.createTempDirectory("ytdlp-output-plan-comma-").toFile()
        val path = directory.absolutePath.replace('\\', '/')
        val map = (YtdlpCommandPathParser.resolve("--paths=home,temp:$path")
            as YtdlpCommandPathResolution.Explicit).pathMap

        assertEquals(directory.canonicalFile, map.home)
        assertEquals(directory.canonicalFile, map.temp)
    }

    @Test
    fun parsesEquivalentLongPathsSyntax() {
        val directory = Files.createTempDirectory("ytdlp-output-plan-long-").toFile()
        val path = directory.absolutePath.replace('\\', '/')
        val result = YtdlpCommandPathParser.resolve("--paths=home:$path")

        assertEquals(directory.canonicalFile, (result as YtdlpCommandPathResolution.Explicit).pathMap.home)
    }

    @Test
    fun retainsOutputSpecificPathsForExplicitUnsafeRejection() {
        val directory = Files.createTempDirectory("ytdlp-output-plan-output-type-").toFile()
        val path = directory.absolutePath.replace('\\', '/')
        val result = YtdlpCommandPathParser.resolve("--paths subtitle:$path")

        val map = (result as YtdlpCommandPathResolution.Explicit).pathMap
        assertEquals(directory.canonicalFile, map.outputTypePaths["subtitle"])
        assertTrue(map.home == null)
        assertTrue(map.temp == null)
    }

    @Test
    fun rejectsMalformedOrUnsafePathSpecifications() {
        val malformed = YtdlpCommandPathParser.resolve("-P \"/missing")
        val unsupportedType = YtdlpCommandPathParser.resolve("--paths unsupported:/shared/output")
        val relative = YtdlpCommandPathParser.resolve("-P relative/output")

        assertTrue(malformed is YtdlpCommandPathResolution.Invalid)
        assertTrue(unsupportedType is YtdlpCommandPathResolution.Invalid)
        assertTrue(relative is YtdlpCommandPathResolution.Invalid)
    }

    @Test
    fun optionsAfterDoubleDashDoNotBecomeOutputAuthority() {
        val result = YtdlpCommandPathParser.resolve("-- --paths /ambient")

        assertTrue(result is YtdlpCommandPathResolution.None)
    }
}
