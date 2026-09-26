package com.ireum.ytdl.util.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
            environment = environment(cacheDownloads = true, nativeDestinationWritable = false)
        )

        assertFalse(plan.usesAppCache)
        assertTrue(plan.downloadLocation.replace('\\', '/').endsWith("/storage/emulated/0/Custom"))
        assertTrue(plan.requestOptions.none { it.name == "-P" })
    }

    @Test
    fun metadataReplacementPathLookingValueDoesNotSelectTerminalDestination() {
        val plan = TerminalCommandPlanner.create(
            command = "--replace-in-metadata title -P /tmp/metadata-replacement",
            environment = environment(cacheDownloads = true, nativeDestinationWritable = true)
        )

        assertTrue(plan.usesAppCache)
        assertEquals("/app/cache/TERMINAL/42", plan.requestOptions.single { it.name == "-P" }.value)
    }

    @Test
    fun shlexProtectedPathLookingValueDoesNotSelectTerminalDestination() {
        val plan = TerminalCommandPlanner.create(
            command = "\"\\-P\" /writable-looking/path",
            environment = environment(cacheDownloads = true, nativeDestinationWritable = true)
        )

        assertTrue(plan.usesAppCache)
        assertEquals("/app/cache/TERMINAL/42", plan.requestOptions.single { it.name == "-P" }.value)
        assertEquals(
            listOf("\\-P", "/writable-looking/path"),
            requireNotNull(
                com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandTokenizer
                    .tokenize(plan.sanitizedConfig)
            ),
        )
    }

    @Test
    fun shellLikeOutputDataCannotBecomeTerminalPathThroughSanitization() {
        val plan = TerminalCommandPlanner.create(
            command = "-o sh -c --no-playlist",
            environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
        )

        assertEquals(
            listOf("-o", "sh", "-c", "--no-playlist"),
            requireNotNull(
                com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandTokenizer
                    .tokenize(plan.sanitizedConfig)
            ),
        )
        assertTrue(plan.usesAppCache)
        assertEquals("/app/cache/TERMINAL/42", plan.requestOptions.single { it.name == "-P" }.value)
    }

    @Test
    fun unsafeAbsoluteOutputTemplateIsRejectedBeforeTerminalExecution() {
        try {
            TerminalCommandPlanner.create(
                command = "-o /storage/emulated/0/escape/%(title)s.%(ext)s https://example.com/video",
                environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
            )
            fail("absolute Terminal output template must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("output template"))
        }
    }

    @Test
    fun tempOnlyPathCannotBypassTerminalOutputStaging() {
        try {
            TerminalCommandPlanner.create(
                command = "--paths temp:/storage/emulated/0/unconfined https://example.com/video",
                environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
            )
            fail("Terminal temp-only path must not establish direct output authority")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("explicit home"))
        }
    }

    @Test
    fun explicitNoOutputSemanticsDoNotRequireAFileCarrier() {
        val plan = TerminalCommandPlanner.create(
            command = "--simulate --quiet https://example.com/video",
            environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
        )

        assertEquals(TerminalOutputExpectation.NO_FILES_EXPECTED, plan.outputExpectation)
        assertTrue(plan.outputAuthorityMarkerPath!!.endsWith(".ytdlnisx-terminal-output.txt"))
    }

    @Test
    fun ordinaryTerminalDownloadRequiresStructuredFileCarrier() {
        val plan = TerminalCommandPlanner.create(
            command = "https://example.com/video",
            environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
        )

        assertEquals(TerminalOutputExpectation.FILES_REQUIRED, plan.outputExpectation)
        assertTrue(plan.outputAuthorityMarkerPath!!.contains("TERMINAL/42"))
    }

    @Test
    fun shortAndLongNoOutputAliasesAreModeledWithLastSimulationOverride() {
        listOf("-j", "-J", "-e", "-g", "-O %(title)s", "--print %(title)s", "--list-extractors", "--skip-download")
            .forEach { option ->
                val plan = TerminalCommandPlanner.create(
                    command = "$option https://example.com/video",
                    environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
                )
                assertEquals(
                    "$option should not require a file carrier",
                    TerminalOutputExpectation.NO_FILES_EXPECTED,
                    plan.outputExpectation,
                )
            }

        val reenabled = TerminalCommandPlanner.create(
            command = "--simulate --no-simulate https://example.com/video",
            environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
        )
        assertEquals(TerminalOutputExpectation.FILES_REQUIRED, reenabled.outputExpectation)

        val explicitDownload = TerminalCommandPlanner.create(
            command = "--no-simulate --print %(title)s https://example.com/video",
            environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
        )
        assertEquals(TerminalOutputExpectation.FILES_REQUIRED, explicitDownload.outputExpectation)

        val laterStagePrint = TerminalCommandPlanner.create(
            command = "--print after_move:%(filepath)s https://example.com/video",
            environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
        )
        assertEquals(TerminalOutputExpectation.FILES_REQUIRED, laterStagePrint.outputExpectation)

        val mixedPrintStages = TerminalCommandPlanner.create(
            command = "--print %(title)s --print after_move:%(filepath)s https://example.com/video",
            environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
        )
        assertEquals(TerminalOutputExpectation.FILES_REQUIRED, mixedPrintStages.outputExpectation)
    }

    @Test
    fun fileProducingOptionsOverrideObservationOnlyFlags() {
        listOf(
            "--no-download --write-description",
        ).forEach { command ->
            val plan = TerminalCommandPlanner.create(
                command = command,
                environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
            )
            assertEquals(
                "$command should retain the exact file carrier requirement",
                TerminalOutputExpectation.FILES_REQUIRED,
                plan.outputExpectation,
            )
        }

        listOf(
            "--simulate --write-info-json",
            "--simulate --write-thumbnail",
            "--simulate --write-subs",
            "--dump-json --write-info-json",
            "--print %(title)s --write-description",
        ).forEach { command ->
            val plan = TerminalCommandPlanner.create(
                command = command,
                environment = environment(cacheDownloads = true, nativeDestinationWritable = true),
            )
            assertEquals(
                "$command remains simulated and must not require a file carrier",
                TerminalOutputExpectation.NO_FILES_EXPECTED,
                plan.outputExpectation,
            )
        }
    }

    @Test
    fun writableDirectDestinationAndCacheFallbackStayDistinct() {
        val direct = TerminalCommandPlanner.create(
            command = "https://example.com/video",
            environment = environment(cacheDownloads = false, nativeDestinationWritable = true)
        )
        val fallback = TerminalCommandPlanner.create(
            command = "https://example.com/video",
            environment = environment(cacheDownloads = false, nativeDestinationWritable = false)
        )

        assertFalse(direct.usesAppCache)
        // The native destination is the typed raw authority itself.  Display
        // formatting is never promoted to output authority.
        assertEquals("/raw/output", direct.requestOptions.last().value)
        assertTrue(fallback.usesAppCache)
        assertEquals("/app/cache/TERMINAL/42", fallback.requestOptions.last().value)
    }

    @Test
    fun providerAuthorityNeverBecomesANativeOutputPath() {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ACommand"
        val staged = TerminalCommandPlanner.create(
            command = "https://example.com/video",
            environment = environment(
                destinationAuthority = TerminalDestinationAuthority.ProviderTree(treeUri),
                cacheDownloads = true,
            )
        )
        val direct = TerminalCommandPlanner.create(
            command = "https://example.com/video",
            environment = environment(
                destinationAuthority = TerminalDestinationAuthority.ProviderTree(treeUri),
                cacheDownloads = false,
                // Provider writability must not be read as native writability.
                nativeDestinationWritable = true,
            )
        )

        // Caching on and off both stage; the grant never enables direct output.
        for (plan in listOf(staged, direct)) {
            assertTrue(plan.usesAppCache)
            assertEquals("/app/cache/TERMINAL/42", plan.requestOptions.single { it.name == "-P" }.value)
            // The exact provider URI stays the publication destination.
            assertEquals(treeUri, plan.downloadLocation)
            // Output provenance stays active whenever output is staged.
            assertEquals(
                "/app/cache/TERMINAL/42/.ytdlnisx-terminal-output.txt",
                plan.outputAuthorityMarkerPath,
            )
            // No reconstructed /storage path may appear anywhere in the plan.
            assertFalse(plan.requestOptions.any { it.value.startsWith("/storage/") })
            assertFalse(plan.sanitizedConfig.contains("/storage/"))
        }
    }

    @Test
    fun folderPickedProviderDestinationIsRemovedFromTheNativeCommand() {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ACommand"
        val plan = TerminalCommandPlanner.create(
            command = "${TerminalProviderDestinationOption.render(treeUri)} " +
                "https://example.com/video",
            environment = environment(cacheDownloads = false, nativeDestinationWritable = true),
        )

        assertTrue(plan.usesAppCache)
        assertEquals("/app/cache/TERMINAL/42", plan.requestOptions.single { it.name == "-P" }.value)
        assertEquals(treeUri, plan.downloadLocation)
        // Terminal-owned metadata must never reach yt-dlp.
        assertFalse(plan.sanitizedConfig.contains(TerminalProviderDestinationOption.OPTION))
        assertFalse(plan.requestOptions.any { it.name == TerminalProviderDestinationOption.OPTION })
    }

    @Test
    fun folderPickedProviderSelectionOutranksAChangedConfiguredRawPath() {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ACommand"
        val plan = TerminalCommandPlanner.create(
            command = TerminalProviderDestinationOption.render(treeUri),
            environment = environment(
                downloadLocation = "/storage/emulated/0/YTDLnisx/Command",
                destinationAuthority = TerminalDestinationAuthority.NativeRaw(
                    "/storage/emulated/0/YTDLnisx/Command",
                ),
                cacheDownloads = false,
                nativeDestinationWritable = true,
            ),
        )

        assertEquals(treeUri, plan.downloadLocation)
        assertTrue(plan.usesAppCache)
        assertEquals("/app/cache/TERMINAL/42", plan.requestOptions.single { it.name == "-P" }.value)
    }

    @Test
    fun authoredNativePathOutranksAProviderSelection() {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ACommand"
        val plan = TerminalCommandPlanner.create(
            command = TerminalProviderDestinationOption.render(treeUri) +
                " -P /storage/emulated/0/Custom https://example.com/video",
            environment = environment(
                destinationAuthority = TerminalDestinationAuthority.ProviderTree(treeUri),
                cacheDownloads = false,
            ),
        )

        // The authored native destination is honored in full; the provider
        // selection is simply not used, and never redirects the raw path.
        assertFalse(plan.usesAppCache)
        assertTrue(plan.requestOptions.none { it.name == "-P" })
        assertTrue(
            plan.downloadLocation.replace('\\', '/').endsWith("/storage/emulated/0/Custom"),
        )
        assertFalse(plan.sanitizedConfig.contains(TerminalProviderDestinationOption.OPTION))
    }

    @Test
    fun malformedOrRepeatedProviderDestinationIsRefused() {
        val malformed = runCatching {
            TerminalCommandPlanner.create(
                command = "${TerminalProviderDestinationOption.OPTION}=content:// https://example.com/v",
                environment = environment(),
            )
        }
        assertTrue(malformed.isFailure)

        val repeated = runCatching {
            TerminalCommandPlanner.create(
                command = TerminalProviderDestinationOption.render("content://a/b") + " " +
                    TerminalProviderDestinationOption.render("content://a/c"),
                environment = environment(),
            )
        }
        assertTrue(repeated.isFailure)
    }

    @Test
    fun destinationAuthorityClassificationKeepsProviderIdentityExact() {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ACommand"
        assertEquals(
            TerminalDestinationAuthority.ProviderTree(treeUri),
            TerminalDestinationAuthority.classify(treeUri),
        )
        // A leading/trailing space must not change the identity that is stored.
        assertEquals(
            TerminalDestinationAuthority.ProviderTree(treeUri),
            TerminalDestinationAuthority.classify("  $treeUri "),
        )
        assertEquals(
            TerminalDestinationAuthority.NativeRaw("/storage/emulated/0/Command"),
            TerminalDestinationAuthority.classify("/storage/emulated/0/Command"),
        )
        assertTrue(
            TerminalDestinationAuthority.classify("content://") is
                TerminalDestinationAuthority.Unusable,
        )
        assertTrue(TerminalDestinationAuthority.classify("   ") is
            TerminalDestinationAuthority.Unusable)
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
        downloadLocation: String = "/raw/output",
        destinationAuthority: TerminalDestinationAuthority =
            TerminalDestinationAuthority.NativeRaw("/raw/output"),
        cacheDownloads: Boolean = true,
        nativeDestinationWritable: Boolean = true
    ) = TerminalCommandEnvironment(
        cookiePath = cookiePath,
        userAgentHeader = userAgentHeader,
        downloadLocation = downloadLocation,
        destinationAuthority = destinationAuthority,
        appCacheOutputPath = "/app/cache/TERMINAL/42",
        appCacheOutputMarkerPath = "/app/cache/TERMINAL/42/.ytdlnisx-terminal-output.txt",
        cacheDownloads = cacheDownloads,
        nativeDestinationWritable = nativeDestinationWritable
    )
}
