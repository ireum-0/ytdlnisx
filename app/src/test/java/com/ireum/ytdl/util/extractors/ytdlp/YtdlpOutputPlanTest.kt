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
    fun parsesAttachedShortAndRejectsEqualsAsRelativeValue() {
        val directory = Files.createTempDirectory("ytdlp-output-plan-short-forms-").toFile()
        val path = directory.absolutePath.replace('\\', '/')

        val attached = YtdlpCommandPathParser.resolve("-P$path")
        val equals = YtdlpCommandPathParser.resolve("-P=\"$path\"")
        val typedEquals = YtdlpCommandPathParser.resolve("-P=home:$path")

        assertEquals(directory.canonicalFile, (attached as YtdlpCommandPathResolution.Explicit).pathMap.home)
        assertTrue(equals is YtdlpCommandPathResolution.Invalid)
        assertTrue(typedEquals is YtdlpCommandPathResolution.Invalid)
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
    fun rejectsYtdlpEnvironmentExpandablePathValuesBeforeGrantingAuthority() {
        val base = Files.createTempDirectory("ytdlp-output-plan-env-")
            .toFile()
            .canonicalPath
            .replace('\\', '/')
        val homeValue = "$base/\$HOME"
        val tempValue = "$base/\${TMPDIR}"

        listOf(
            "-P $homeValue",
            "--paths=$homeValue",
            "--paths=home:$homeValue",
            "--paths temp:$tempValue",
            "--paths=home,temp:$tempValue",
        ).forEach { command ->
            assertTrue(
                "environment-expandable path must fail closed: $command",
                YtdlpCommandPathParser.resolve(command) is YtdlpCommandPathResolution.Invalid,
            )
        }

        val ordinary = YtdlpCommandPathParser.resolve("-P $base")
        assertTrue(ordinary is YtdlpCommandPathResolution.Explicit)
        assertEquals(
            base,
            (ordinary as YtdlpCommandPathResolution.Explicit).pathMap.home
                ?.canonicalPath
                ?.replace('\\', '/'),
        )

        // A punctuation-only dollar is unchanged by Python expandvars and
        // therefore remains ordinary supported path data.
        assertTrue(
            YtdlpCommandPathParser.resolve("-P $base/\$-") is
                YtdlpCommandPathResolution.Explicit,
        )
    }

    @Test
    fun pathAuthorityIsNotGrantedWhenPathTokenIsAnotherOptionsValue() {
        val absolutePath = Files.createTempDirectory("ytdlp-output-plan-cluster-path-")
            .toFile()
            .absolutePath
            .replace('\\', '/')
        assertTrue(
            YtdlpCommandPathParser.resolve("-o --paths /shared/not-a-path-option") is
                YtdlpCommandPathResolution.None
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("--output --paths /shared/not-a-path-option") is
                YtdlpCommandPathResolution.None
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("--proxy --paths /shared/not-a-path-option") is
                YtdlpCommandPathResolution.None
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("-f -P /shared/not-a-path-option") is
                YtdlpCommandPathResolution.None
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("-qP $absolutePath") is
                YtdlpCommandPathResolution.Explicit
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("-qP$absolutePath") is
                YtdlpCommandPathResolution.Explicit
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("-vqP $absolutePath") is
                YtdlpCommandPathResolution.Explicit
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("--path $absolutePath") is
                YtdlpCommandPathResolution.Explicit
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("--no-playlist --paths $absolutePath") is
                YtdlpCommandPathResolution.Explicit
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("--progress --paths $absolutePath") is
                YtdlpCommandPathResolution.Explicit
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("--write-thumbnail --paths $absolutePath") is
                YtdlpCommandPathResolution.Explicit
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("--pro --paths $absolutePath") is
                YtdlpCommandPathResolution.None
        )
        assertTrue(
            YtdlpCommandPathParser.resolve("--paths $absolutePath --no-playlist") is
                YtdlpCommandPathResolution.Explicit
        )
    }

    @Test
    fun fixedArityMetadataOptionsOwnPathAndOutputLookingValues() {
        val separated = "--replace-in-metadata title -P /tmp/metadata-replacement"
        val equals = "--replace-in-metadata=title -P /tmp/metadata-replacement"
        val abbreviated = "--replace-in-met title -o replacement"

        listOf(separated, equals, abbreviated).forEach { command ->
            assertTrue(
                "metadata values must not become path authority for $command",
                YtdlpCommandPathParser.resolve(command) is YtdlpCommandPathResolution.None,
            )
            assertTrue(
                "metadata values must not become output authority for $command",
                YtdlpCommandOutputTemplateParser.resolve(command) is
                    YtdlpCommandOutputTemplateResolution.None,
            )
        }

        val separatedOutput = YtdlpCommandOutputTemplateParser.resolve(
            "--replace-in-metadata title -o replacement"
        )
        val equalsOutput = YtdlpCommandOutputTemplateParser.resolve(
            "--replace-in-metadata=title -o replacement"
        )
        assertTrue(separatedOutput is YtdlpCommandOutputTemplateResolution.None)
        assertTrue(equalsOutput is YtdlpCommandOutputTemplateResolution.None)
    }

    @Test
    fun realDestinationOptionsAfterAllFixedArityValuesRemainEffective() {
        val path = Files.createTempDirectory("ytdlp-output-plan-arity-path-")
            .toFile()
            .canonicalPath
            .replace('\\', '/')
        val pathResolution = YtdlpCommandPathParser.resolve(
            "--replace-in-metadata title regex replacement -P $path"
        )
        assertEquals(
            path,
            (pathResolution as YtdlpCommandPathResolution.Explicit)
                .pathMap.home
                ?.canonicalPath
                ?.replace('\\', '/'),
        )

        val outputResolution = YtdlpCommandOutputTemplateParser.resolve(
            "--replace-in-metadata title regex replacement -o safe/%(title)s.%(ext)s"
        )
        assertTrue(outputResolution is YtdlpCommandOutputTemplateResolution.Explicit)
        assertEquals(
            "safe/%(title)s.%(ext)s",
            (outputResolution as YtdlpCommandOutputTemplateResolution.Explicit)
                .templates["default"],
        )
    }

    @Test
    fun fixedArityMetadataValuesThatLookSensitiveRemainData() {
        val ownedAlias = "--replace-in-metadata title --alias replacement"
        val ownedPrint = "--replace-in-metadata title --print-to-file replacement"

        assertTrue(
            YtdlpCommandOutputTemplateParser.resolve(ownedAlias) is
                YtdlpCommandOutputTemplateResolution.None,
        )
        assertTrue(
            YtdlpCommandOutputTemplateParser.resolve(ownedPrint) is
                YtdlpCommandOutputTemplateResolution.None,
        )
        assertTrue(
            YtdlpCommandPathParser.resolve(ownedAlias) is YtdlpCommandPathResolution.None,
        )
        assertTrue(
            YtdlpCommandPathParser.resolve(ownedPrint) is YtdlpCommandPathResolution.None,
        )
    }

    @Test
    fun realAliasAndPrintToFileOwnTheirDeclaredArgumentCountsBeforeRejection() {
        val aliasOwnership = YtdlpOptionOwnership.inspect(
            YtdlpCommandTokenizer.tokenize("--alias safe '-o %(title)s.%(ext)s' -P /tmp/path")!!,
            0,
        )
        assertEquals("--alias", aliasOwnership.canonicalName)
        assertEquals(2, aliasOwnership.requiredValueCount)
        assertEquals(listOf("safe", "-o %(title)s.%(ext)s"), aliasOwnership.values)

        val printOwnership = YtdlpOptionOwnership.inspect(
            YtdlpCommandTokenizer.tokenize(
                "--print-to-file '%(title)s' /tmp/print.txt -P /tmp/path"
            )!!,
            0,
        )
        assertEquals("--print-to-file", printOwnership.canonicalName)
        assertEquals(2, printOwnership.requiredValueCount)
        assertEquals(listOf("%(title)s", "/tmp/print.txt"), printOwnership.values)

        assertTrue(
            YtdlpCommandOutputTemplateParser.resolve(
                "--alias safe '-o %(title)s.%(ext)s'"
            ) is YtdlpCommandOutputTemplateResolution.Invalid,
        )
        assertTrue(
            YtdlpCommandOutputTemplateParser.resolve(
                "--print-to-file '%(title)s' /tmp/print.txt"
            ) is YtdlpCommandOutputTemplateResolution.Invalid,
        )
    }

    @Test
    fun sharedOwnershipModelReportsEqualsArityAndOwnedTokens() {
        val tokens = YtdlpCommandTokenizer.tokenize(
            "--replace-in-metadata=title -P /tmp/metadata-replacement"
        )!!
        val ownership = YtdlpOptionOwnership.inspect(tokens, 0)

        assertEquals("--replace-in-metadata", ownership.canonicalName)
        assertEquals(3, ownership.requiredValueCount)
        assertEquals(
            listOf("title", "-P", "/tmp/metadata-replacement"),
            ownership.values,
        )
        assertEquals(2, ownership.consumedFollowingTokenCount)
        assertEquals(3, ownership.nextIndexDelta)
    }

    @Test
    fun optionsAfterDoubleDashDoNotBecomeOutputAuthority() {
        val result = YtdlpCommandPathParser.resolve("-- --paths /ambient")

        assertTrue(result is YtdlpCommandPathResolution.None)
    }
    @Test
    fun tokenizerMatchesPersistedConfigCommentSemantics() {
        val commentedUnsafe = YtdlpCommandOutputTemplateParser.resolve(
            "--output safe/%(title)s.%(ext)s # --output /shared/escape.%(ext)s"
        )
        assertTrue(commentedUnsafe is YtdlpCommandOutputTemplateResolution.Explicit)

        val nextLineSafe = YtdlpCommandOutputTemplateParser.resolve(
            "# --output /shared/escape.%(ext)s\n--output safe/%(title)s.%(ext)s"
        )
        assertTrue(nextLineSafe is YtdlpCommandOutputTemplateResolution.Explicit)

        val quotedHash = YtdlpCommandOutputTemplateParser.resolve(
            "--output \"safe/#tag/%(title)s.%(ext)s\""
        )
        assertTrue(quotedHash is YtdlpCommandOutputTemplateResolution.Explicit)

        val escapedHash = YtdlpCommandOutputTemplateParser.resolve(
            "--output safe/\\#tag/%(title)s.%(ext)s"
        )
        assertTrue(escapedHash is YtdlpCommandOutputTemplateResolution.Explicit)
    }

    @Test
    fun doubleQuotedBackslashesBeforeOrdinaryCharactersRemainLiteralData() {
        val path = Files.createTempDirectory("ytdlp-output-plan-shlex-path-")
            .toFile()
            .canonicalPath
            .replace('\\', '/')
        val commands = listOf(
            "\"\\\\-P\" $path",
            "\"\\\\--paths\" $path",
            "\"\\\\-o\" safe/%(title)s.%(ext)s",
            "\"\\\\--output\" safe/%(title)s.%(ext)s",
        )

        commands.forEach { command ->
            assertTrue(
                "double-quoted backslash data must not become path authority: $command",
                YtdlpCommandPathParser.resolve(command) is YtdlpCommandPathResolution.None,
            )
        }
        assertTrue(
            YtdlpCommandOutputTemplateParser.resolve(commands[2]) is
                YtdlpCommandOutputTemplateResolution.None,
        )
        assertTrue(
            YtdlpCommandOutputTemplateParser.resolve(commands[3]) is
                YtdlpCommandOutputTemplateResolution.None,
        )
        assertEquals(
            listOf("\\-P", path),
            YtdlpCommandTokenizer.tokenize(commands[0]),
        )
        assertEquals(
            listOf("\\--paths", path),
            YtdlpCommandTokenizer.tokenize(commands[1]),
        )
    }

    @Test
    fun outsideQuoteBackslashStillMatchesPythonShlexOptionSemantics() {
        val path = Files.createTempDirectory("ytdlp-output-plan-shlex-escaped-")
            .toFile()
            .canonicalPath
            .replace('\\', '/')
        val resolution = YtdlpCommandPathParser.resolve("\\-P $path")

        assertTrue(resolution is YtdlpCommandPathResolution.Explicit)
        assertEquals(
            path,
            (resolution as YtdlpCommandPathResolution.Explicit)
                .pathMap.home
                ?.canonicalPath
                ?.replace('\\', '/'),
        )
    }

    @Test
    fun tokenizerAndRendererPreservePythonShlexTokenVectors() {
        val tokens = listOf(
            "\\-P",
            "\\--paths",
            "\\-o",
            "\\--output",
            "\\--exec",
            "\\--ffmpeg-location",
            "\\--config-locations",
            "",
            "a\"b",
            "a\\b",
            "# quoted",
            "line\nnext",
        )

        val rendered = YtdlpCommandTokenizer.render(tokens)

        assertEquals(tokens, YtdlpCommandTokenizer.tokenize(rendered))
    }

    @Test
    fun tokenizerMatchesPythonShlexDoubleQuoteAndCommentRules() {
        assertEquals(
            listOf("a\"b"),
            YtdlpCommandTokenizer.tokenize("\"a\\\"b\""),
        )
        assertEquals(
            listOf("a\\b"),
            YtdlpCommandTokenizer.tokenize("\"a\\\\b\""),
        )
        assertEquals(
            listOf("\\q"),
            YtdlpCommandTokenizer.tokenize("\"\\q\""),
        )
        assertEquals(listOf(""), YtdlpCommandTokenizer.tokenize("\"\""))
        assertEquals(
            listOf("#"),
            YtdlpCommandTokenizer.tokenize("\\# # comment"),
        )
        assertEquals(
            listOf("# quoted"),
            YtdlpCommandTokenizer.tokenize("\"# quoted\" # comment"),
        )
        assertEquals(
            listOf("one", "two"),
            YtdlpCommandTokenizer.tokenize("one # comment\ntwo"),
        )
        assertTrue(YtdlpCommandTokenizer.tokenize("\"unbalanced") == null)
    }

    @Test
    fun parsesBundledShortOptionClustersForOutputAndPathPolicy() {
        val absolutePath = Files.createTempDirectory("ytdlp-output-plan-cluster-path-")
            .toFile()
            .absolutePath
            .replace('\\', '/')
        val safeAttached = YtdlpCommandOutputTemplateParser.resolve(
            "-qosafe/%(title)s.%(ext)s"
        )
        val safeSeparate = YtdlpCommandOutputTemplateParser.resolve(
            "-qo safe/nested/%(title)s.%(ext)s"
        )
        val safeVerbose = YtdlpCommandOutputTemplateParser.resolve(
            "-vqo%(title)s.%(ext)s"
        )
        val safeCombinedFlags = YtdlpCommandOutputTemplateParser.resolve(
            "-CXqosafe/combined/%(title)s.%(ext)s"
        )
        val outputLookingLikeOption = YtdlpCommandOutputTemplateParser.resolve("-qo -P")

        assertTrue(safeAttached is YtdlpCommandOutputTemplateResolution.Explicit)
        assertTrue(safeSeparate is YtdlpCommandOutputTemplateResolution.Explicit)
        assertTrue(safeVerbose is YtdlpCommandOutputTemplateResolution.Explicit)
        assertTrue(safeCombinedFlags is YtdlpCommandOutputTemplateResolution.Explicit)
        assertEquals(
            "-P",
            (outputLookingLikeOption as YtdlpCommandOutputTemplateResolution.Explicit)
                .templates["default"],
        )
        listOf(
            "-f -o /absolute/not-an-output-option",
            "--proxy --output /absolute/not-an-output-option",
            "-P -o /absolute/not-an-output-option",
        ).forEach { command ->
            assertTrue(
                "expected value-taking option to consume output-looking token for $command",
                YtdlpCommandOutputTemplateParser.resolve(command) is
                    YtdlpCommandOutputTemplateResolution.None,
            )
        }

        listOf(
            "-qo/absolute/escape.%(ext)s",
            "-qo /absolute/escape.%(ext)s",
            "-vqo/absolute/escape.%(ext)s",
            "-qo../traversal.%(ext)s",
        ).forEach { command ->
            assertTrue(
                "expected clustered output rejection for $command",
                YtdlpCommandOutputTemplateParser.resolve(command) is
                    YtdlpCommandOutputTemplateResolution.Invalid,
            )
        }

        listOf(
            "-qP $absolutePath",
            "-qP$absolutePath",
            "-vqP $absolutePath",
            "-CXqP $absolutePath",
        ).forEach { command ->
            assertTrue(
                "expected clustered path authority for $command",
                YtdlpCommandPathParser.resolve(command) is
                    YtdlpCommandPathResolution.Explicit,
            )
        }
    }

    @Test
    fun acceptsSupportedRelativeOutputTemplateForms() {
        val commands = listOf(
            "-o %(title)s.%(ext)s",
            "--output %(title)s.%(ext)s",
            "--output=%(title)s.%(ext)s",
            "-osafe/%(title)s.%(ext)s",
            "--output safe/nested/%(title)s.%(ext)s",
            "--output \"safe/%(uploader).30B - %(section_start>%H??M??S)s.%(ext)s\"",
            "--output %(title)s.%(ext)s",
            "--output %(id).80s.%(ext)s",
            "--output %(title).1s.%(ext).1s",
        )

        commands.forEach { command ->
            val result = YtdlpCommandOutputTemplateParser.resolve(command)
            assertTrue("expected confined output template for $command, got $result",
                result is YtdlpCommandOutputTemplateResolution.Explicit)
        }
    }

    @Test
    fun outputTemplateTypesAndRepeatedDeclarationsFollowEffectiveLastValueSemantics() {
        val result = YtdlpCommandOutputTemplateParser.resolve(
            "-o /unsafe/first.%(ext)s " +
                "--output safe/%(title)s.%(ext)s " +
                "-o thumbnail:/unsafe/thumb.jpg " +
                "--output=thumbnail,subtitle:side/%(id)s.jpg " +
                "-o description:"
        )

        val templates = (result as YtdlpCommandOutputTemplateResolution.Explicit).templates
        assertEquals("safe/%(title)s.%(ext)s", templates["default"])
        assertEquals("side/%(id)s.jpg", templates["thumbnail"])
        assertEquals("side/%(id)s.jpg", templates["subtitle"])
        assertTrue(templates.containsKey("description"))
        assertEquals(null, templates["description"])
    }

    @Test
    fun unsafeEffectiveOutputTemplatesFailClosed() {
        val commands = listOf(
            "-o /shared/escape.%(ext)s",
            "--output=../escape.%(ext)s",
            "-o safe/../../escape.%(ext)s",
            "-o ~/escape.%(ext)s",
            "-o \$HOME/escape.%(ext)s",
            "-o \${HOME}/escape.%(ext)s",
            "-o %(uploader)s/%(title)s.%(ext)s",
            "-o %(title)s",
            "-o -",
            "--output %(title",
            "--output %(title)Qzzz.%(ext)s",
            "--output %(title)ls",
            "--output '%(title&.)s.%(ext|)s'",
            "--output '%(title|.)s.%(ext|)s'",
            "--output '%(title&..)s%(ext|)s'",
            "--output '%(title).1s.%(ext).0s'",
            "--output '%(title).0s.%(ext).1s'",
            "--output safe/%(title)ls.%(ext)s",
            "--alias unsafe '-o /shared/escape.%(ext)s'",
            "-- --output safe.%(ext)s",
        )

        commands.forEach { command ->
            assertTrue(
                "expected rejection for $command",
                YtdlpCommandOutputTemplateParser.resolve(command) is
                    YtdlpCommandOutputTemplateResolution.Invalid,
            )
        }
    }

    @Test
    fun exactSafeLongOptionsThatPrefixSensitiveOptionsRemainAllowed() {
        assertTrue(
            YtdlpCommandOutputTemplateParser.resolve("--print %(title)s") is
                YtdlpCommandOutputTemplateResolution.None
        )
        assertTrue(
            YtdlpCommandOutputTemplateParser.resolve("--netrc") is
                YtdlpCommandOutputTemplateResolution.None
        )
    }

    @Test
    fun abbreviatedDestinationSensitiveOptionsFailClosed() {
        val commands = listOf(
            "--pat=/shared/escape",
            "--outp=/shared/escape.%(ext)s",
            "--print-to-f=video:%(title)s /shared/report.txt",
            "--download-arch=/shared/archive.txt",
            "--cache-d=/shared/cache",
            "--cook=/shared/cookies.txt",
            "--conf=/shared/evil.conf",
            "--ali unsafe '-o /shared/escape.%(ext)s'",
            "--ex 'touch /shared/escape'",
            "--js-r=node:/shared/node",
            "--output-na-p=.",
        )

        commands.forEach { command ->
            assertTrue(
                "expected abbreviated sensitive-option rejection for $command",
                YtdlpCommandOutputTemplateParser.resolve(command) is
                    YtdlpCommandOutputTemplateResolution.Invalid,
            )
        }
    }

    @Test
    fun authoredIndependentFileWriteOrExecutionOptionsFailClosed() {
        val commands = listOf(
            "--print-to-file '%(title)s' /shared/report.txt",
            "--print-to-file=video:%(title)s /shared/report.txt",
            "--download-archive /shared/archive.txt",
            "--download-archive=/shared/archive.txt",
            "--cookies /shared/cookies.txt",
            "--cache-dir /shared/cache",
            "--write-pages",
            "--js-runtimes node:/shared/node",
            "--js-runtimes=node:/shared/node",
            "--output-na-placeholder ''",
            "--output-na-placeholder=.",
        )

        commands.forEach { command ->
            assertTrue(
                "expected independent file writer rejection for $command",
                YtdlpCommandOutputTemplateParser.resolve(command) is
                    YtdlpCommandOutputTemplateResolution.Invalid,
            )
        }
    }

    @Test
    fun unsafeEarlierTemplateMayBeShadowedButUnsafeEffectiveTypeCannot() {
        val shadowed = YtdlpCommandOutputTemplateParser.resolve(
            "-o /unsafe/first.%(ext)s -o safe/%(title)s.%(ext)s"
        )
        assertTrue(shadowed is YtdlpCommandOutputTemplateResolution.Explicit)

        val stillEffective = YtdlpCommandOutputTemplateParser.resolve(
            "-o /unsafe/default.%(ext)s -o thumbnail:safe/thumb.jpg"
        )
        assertTrue(stillEffective is YtdlpCommandOutputTemplateResolution.Invalid)
    }

    @Test
    fun typedEmptySuppressionIsAllowedButEmptyDefaultIsNot() {
        val typedEmpty = YtdlpCommandOutputTemplateParser.resolve(
            "--output=thumbnail: --output=subtitle:"
        )
        val templates = (typedEmpty as YtdlpCommandOutputTemplateResolution.Explicit).templates
        assertTrue(templates.containsKey("thumbnail"))
        assertEquals(null, templates["thumbnail"])
        assertTrue(templates.containsKey("subtitle"))
        assertEquals(null, templates["subtitle"])

        assertTrue(
            YtdlpCommandOutputTemplateParser.resolve("--output=") is
                YtdlpCommandOutputTemplateResolution.Invalid
        )
    }

    @Test
    fun unrecognizedTypePrefixRemainsPartOfDefaultRelativeTemplate() {
        val result = YtdlpCommandOutputTemplateParser.resolve(
            "-o custom:safe/%(title)s.%(ext)s"
        )
        val templates = (result as YtdlpCommandOutputTemplateResolution.Explicit).templates
        assertEquals("custom:safe/%(title)s.%(ext)s", templates["default"])
    }

    @Test
    fun optionTerminatorOnlyRejectsWhenAppConfinementOptionsMustFollow() {
        val commandResolution = YtdlpCommandOutputTemplateParser.resolve(
            "-- --output /ignored/escape.%(ext)s",
            confinementOptionsFollow = true,
        )
        assertTrue(commandResolution is YtdlpCommandOutputTemplateResolution.Invalid)

        val extraResolution = YtdlpCommandOutputTemplateParser.resolve(
            "-- --output /ignored/escape.%(ext)s",
            confinementOptionsFollow = false,
        )
        assertTrue(extraResolution is YtdlpCommandOutputTemplateResolution.None)
    }

    @Test
    fun generatedFilenameTemplatesUseTheSameConfinementPolicy() {
        assertEquals(
            null,
            YtdlpCommandOutputTemplateParser.validateGeneratedTemplate(
                "safe/%(title)s.%(ext)s"
            )
        )
        assertTrue(
            YtdlpCommandOutputTemplateParser.validateGeneratedTemplate(
                "../escape.%(ext)s"
            ) != null
        )
        assertTrue(
            YtdlpCommandOutputTemplateParser.validateGeneratedTemplate(
                "/shared/escape.%(ext)s"
            ) != null
        )
    }

}
