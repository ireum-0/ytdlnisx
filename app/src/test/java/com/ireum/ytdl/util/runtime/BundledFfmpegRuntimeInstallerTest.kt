package com.ireum.ytdl.util.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

class BundledFfmpegRuntimeInstallerTest {
    @Test
    fun alreadyVerifiedGenerationSkipsMaterialization() = withRuntimeTree { nativeDirectory, payloadRoot ->
        createCompletePayload(payloadRoot)
        val existingGeneration = UUID.randomUUID().toString()
        File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_GENERATION_FILE).writeText(existingGeneration)
        val result = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) {
            error("verified generation should use the fast path")
        }.install()

        assertEquals(BundledFfmpegInstallResult.VerifiedCurrent(existingGeneration), result)
    }

    @Test
    fun publishesOnlyACompleteVerifiedStagingGeneration() = withRuntimeTree { nativeDirectory, payloadRoot ->
        val result = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { stage ->
            createStagedPayload(stage)
        }.install()

        assertTrue(result is BundledFfmpegInstallResult.VerifiedNew)
        val generation = (result as BundledFfmpegInstallResult.VerifiedNew).generation
        val live = BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot)
        assertTrue(live is BundledFfmpegRuntimeResolution.Available)
        assertEquals(generation, (live as BundledFfmpegRuntimeResolution.Available).generation)
        assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.backup").exists())
        assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.install-journal").exists())
    }

    @Test
    fun upgradesACompleteOlderRevisionOnlyAfterTheNewGenerationValidates() = withRuntimeTree { nativeDirectory, payloadRoot ->
        createCompletePayload(payloadRoot, revision = PREVIOUS_REVISION)

        val result = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { stage ->
            createStagedPayload(stage)
        }.install()

        assertTrue(result is BundledFfmpegInstallResult.VerifiedNew)
        assertTrue(BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot) is BundledFfmpegRuntimeResolution.Available)
        assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.backup").exists())
    }

    @Test
    fun extractionFailurePreservesPreviouslyValidOlderGeneration() = withRuntimeTree { nativeDirectory, payloadRoot ->
        createCompletePayload(payloadRoot, revision = PREVIOUS_REVISION)
        val oldLibrary = File(payloadRoot, "usr/lib/libavformat.so.61").readBytes()

        val result = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) {
            error("injected archive extraction failure")
        }.install()

        assertTrue(result is BundledFfmpegInstallResult.Failure)
        assertEquals(PREVIOUS_REVISION, File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_REVISION_FILE).readText())
        assertEquals(oldLibrary.toList(), File(payloadRoot, "usr/lib/libavformat.so.61").readBytes().toList())
        assertTrue(
            BundledFfmpegRuntime.validate(
                nativeDirectory,
                payloadRoot,
                expectedRevision = PREVIOUS_REVISION,
                requireGeneration = false,
            ) is BundledFfmpegRuntimeResolution.Available,
        )
        assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.backup").exists())
    }

    @Test
    fun failureBeforeProvenancePublicationPreservesThePriorLiveGeneration() = withRuntimeTree { nativeDirectory, payloadRoot ->
        createCompletePayload(payloadRoot, revision = PREVIOUS_REVISION)
        val oldLibrary = File(payloadRoot, "usr/lib/libavformat.so.61").readBytes()
        val installer = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { stage ->
            createStagedPayload(stage)
        }.apply {
            failureAtBoundaryForTesting = BundledFfmpegRuntimeInstaller.Boundary.BEFORE_PROVENANCE_MARKERS
        }

        val result = installer.install()

        assertTrue(result is BundledFfmpegInstallResult.Failure)
        assertEquals(PREVIOUS_REVISION, File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_REVISION_FILE).readText())
        assertEquals(oldLibrary.toList(), File(payloadRoot, "usr/lib/libavformat.so.61").readBytes().toList())
        assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.install-journal").exists())
    }

    @Test
    fun requiredDependencyCopyFailurePreservesThePreviousPayload() {
        BundledFfmpegRuntime.REQUIRED_COPIED_DEPENDENCIES.forEach { failedName ->
            withRuntimeTree { nativeDirectory, payloadRoot ->
                createCompletePayload(payloadRoot, revision = PREVIOUS_REVISION)
                val oldLibrary = File(payloadRoot, "usr/lib/libavformat.so.61").readBytes()
                val result = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { stage ->
                    createCorePayloadWithoutCopiedDependencies(stage)
                    BundledFfmpegRequiredDependencyCopier.copy(File(stage, "usr/lib")) { name ->
                        if (name == failedName) throw IOException("injected copy failure: $name")
                        ByteArrayInputStream(ByteArray(512) { 4 })
                    }
                }.install()

                assertTrue("$failedName must fail installation", result is BundledFfmpegInstallResult.Failure)
                assertEquals(PREVIOUS_REVISION, File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_REVISION_FILE).readText())
                assertEquals(oldLibrary.toList(), File(payloadRoot, "usr/lib/libavformat.so.61").readBytes().toList())
                assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.backup").exists())
            }
        }
    }

    @Test
    fun finalPublishFailureRollsBackToThePreviousPayload() = withRuntimeTree { nativeDirectory, payloadRoot ->
        createCompletePayload(payloadRoot, revision = PREVIOUS_REVISION)
        val oldLibrary = File(payloadRoot, "usr/lib/libavformat.so.61").readBytes()
        val installer = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { stage ->
            createStagedPayload(stage)
        }.apply {
            failureAtBoundaryForTesting = BundledFfmpegRuntimeInstaller.Boundary.BEFORE_NEW_LIVE_PUBLISH
        }

        val result = installer.install()

        assertTrue(result is BundledFfmpegInstallResult.Failure)
        assertEquals(PREVIOUS_REVISION, File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_REVISION_FILE).readText())
        assertEquals(oldLibrary.toList(), File(payloadRoot, "usr/lib/libavformat.so.61").readBytes().toList())
        assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.backup").exists())
        assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.install-journal").exists())
    }

    @Test
    fun processDeathDuringStagingLeavesThePriorLivePayloadAndRecoveryRemovesOwnedStage() = withRuntimeTree { nativeDirectory, payloadRoot ->
        createCompletePayload(payloadRoot, revision = PREVIOUS_REVISION)
        val installer = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { stage ->
            createStagedPayload(stage)
        }.apply {
            crashAtBoundaryForTesting = BundledFfmpegRuntimeInstaller.Boundary.MATERIALIZED
        }
        assertNotNull(runCatching { installer.install() }.exceptionOrNull())
        assertEquals(PREVIOUS_REVISION, File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_REVISION_FILE).readText())

        val recovery = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { error("recovery must not install") }
        assertEquals(null, recovery.recoverInterruptedInstall())

        assertTrue(payloadRoot.isDirectory)
        assertEquals(PREVIOUS_REVISION, File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_REVISION_FILE).readText())
        assertTrue(ownedStageDirectories(payloadRoot).isEmpty())
    }

    @Test
    fun processDeathAfterBackupRestoresThePreviousPayload() = withRuntimeTree { nativeDirectory, payloadRoot ->
        createCompletePayload(payloadRoot, revision = PREVIOUS_REVISION)
        val oldLibrary = File(payloadRoot, "usr/lib/libavformat.so.61").readBytes()
        val installer = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { stage ->
            createStagedPayload(stage)
        }.apply {
            crashAtBoundaryForTesting = BundledFfmpegRuntimeInstaller.Boundary.OLD_LIVE_BACKED_UP
        }
        assertNotNull(runCatching { installer.install() }.exceptionOrNull())
        assertFalse(payloadRoot.exists())
        assertTrue(File(payloadRoot.parentFile, ".${payloadRoot.name}.backup").exists())

        val recovery = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { error("recovery must not install") }
        assertEquals(null, recovery.recoverInterruptedInstall())

        assertEquals(PREVIOUS_REVISION, File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_REVISION_FILE).readText())
        assertEquals(oldLibrary.toList(), File(payloadRoot, "usr/lib/libavformat.so.61").readBytes().toList())
        assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.backup").exists())
        assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.install-journal").exists())
    }

    @Test
    fun processDeathAfterPublishFinishesTheVerifiedGenerationAndRetiresItsBackup() = withRuntimeTree { nativeDirectory, payloadRoot ->
        createCompletePayload(payloadRoot, revision = PREVIOUS_REVISION)
        val installer = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { stage ->
            createStagedPayload(stage)
        }.apply {
            crashAtBoundaryForTesting = BundledFfmpegRuntimeInstaller.Boundary.NEW_LIVE_PUBLISHED
        }
        assertNotNull(runCatching { installer.install() }.exceptionOrNull())
        val publishedGeneration = File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_GENERATION_FILE).readText()

        val recovery = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { error("recovery must not install") }
        assertEquals(null, recovery.recoverInterruptedInstall())

        val live = BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot)
        assertTrue(live is BundledFfmpegRuntimeResolution.Available)
        assertEquals(publishedGeneration, (live as BundledFfmpegRuntimeResolution.Available).generation)
        assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.backup").exists())
        assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.install-journal").exists())
    }

    @Test
    fun processDeathAtEveryInstallBoundaryRecoversToThePriorOrFullyPublishedGeneration() {
        BundledFfmpegRuntimeInstaller.Boundary.values().forEach { boundary ->
            withRuntimeTree { nativeDirectory, payloadRoot ->
                createCompletePayload(payloadRoot, revision = PREVIOUS_REVISION)
                val installer = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { stage ->
                    createStagedPayload(stage)
                }.apply { crashAtBoundaryForTesting = boundary }

                assertNotNull("expected process-death simulation at $boundary", runCatching { installer.install() }.exceptionOrNull())
                val recovery = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) {
                    error("recovery must not start a new installation")
                }
                assertEquals("recovery at $boundary", null, recovery.recoverInterruptedInstall())

                val wasPublished = boundary == BundledFfmpegRuntimeInstaller.Boundary.NEW_LIVE_PUBLISHED ||
                    boundary == BundledFfmpegRuntimeInstaller.Boundary.BEFORE_BACKUP_RETIREMENT
                if (wasPublished) {
                    assertTrue("new generation should remain live after $boundary", BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot) is BundledFfmpegRuntimeResolution.Available)
                } else {
                    assertEquals(
                        "older generation must be restored after $boundary",
                        PREVIOUS_REVISION,
                        File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_REVISION_FILE).readText(),
                    )
                }
                assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.backup").exists())
                assertFalse(File(payloadRoot.parentFile, ".${payloadRoot.name}.install-journal").exists())
                assertTrue(ownedStageDirectories(payloadRoot).isEmpty())
            }
        }
    }

    @Test
    fun recoveryLeavesUnownedStagingAndRollbackPathsUntouched() = withRuntimeTree { nativeDirectory, payloadRoot ->
        val parent = requireNotNull(payloadRoot.parentFile).apply { mkdirs() }
        val attemptId = UUID.randomUUID().toString()
        val unownedStage = File(parent, ".${payloadRoot.name}.stage-$attemptId").apply { mkdirs() }
        val sentinel = File(unownedStage, "keep-me.txt").apply { writeText("unowned") }
        val unownedBackup = File(parent, ".${payloadRoot.name}.backup").apply {
            mkdirs()
            File(this, "keep-me.txt").writeText("unowned-backup")
        }
        val installer = BundledFfmpegRuntimeInstaller(nativeDirectory, payloadRoot) { error("must not run") }

        assertEquals(null, installer.recoverInterruptedInstall())
        val result = installer.install()

        assertTrue(result is BundledFfmpegInstallResult.Failure)
        assertTrue(sentinel.isFile)
        assertTrue(File(unownedBackup, "keep-me.txt").isFile)
    }

    private fun withRuntimeTree(block: (nativeDirectory: File, payloadRoot: File) -> Unit) {
        val root = Files.createTempDirectory("bundled-ffmpeg-installer-").toFile()
        try {
            val nativeDirectory = File(root, "native").apply { mkdirs() }
            listOf("libffmpeg.so", "libffprobe.so").forEach { name ->
                File(nativeDirectory, name).writeBytes(ELF_HEADER + ByteArray(508))
            }
            block(nativeDirectory, File(root, "packages/ffmpeg"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createCompletePayload(
        root: File,
        revision: String = BundledFfmpegRuntime.PAYLOAD_REVISION,
    ) {
        createStagedPayload(root)
        File(root, BundledFfmpegRuntime.PAYLOAD_REVISION_FILE).writeText(revision)
        if (revision == BundledFfmpegRuntime.PAYLOAD_REVISION) {
            File(root, BundledFfmpegRuntime.PAYLOAD_GENERATION_FILE).writeText(UUID.randomUUID().toString())
        }
    }

    private fun createStagedPayload(root: File) {
        createCorePayloadWithoutCopiedDependencies(root)
        val libDirectory = File(root, "usr/lib")
        BundledFfmpegRuntime.REQUIRED_COPIED_DEPENDENCIES.forEach { name ->
            File(libDirectory, name).writeBytes(ELF_HEADER + ByteArray(508) { 5 })
        }
    }

    private fun createCorePayloadWithoutCopiedDependencies(root: File) {
        BundledFfmpegRuntime.REQUIRED_PAYLOAD_RELATIVE_PATHS
            .filterNot { it.substringAfterLast('/') in BundledFfmpegRuntime.REQUIRED_COPIED_DEPENDENCIES }
            .forEach { relativePath ->
                File(root, relativePath).apply {
                    parentFile?.mkdirs()
                    writeBytes(ELF_HEADER + ByteArray(508) { 3 })
                }
            }
    }

    private fun ownedStageDirectories(payloadRoot: File): List<File> {
        val prefix = ".${payloadRoot.name}.stage-"
        return payloadRoot.parentFile!!.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(prefix) }
    }

    private companion object {
        const val PREVIOUS_REVISION = "arm64-wrapper-libffmpeg-0.18.1-r11"
        val ELF_HEADER = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    }
}
