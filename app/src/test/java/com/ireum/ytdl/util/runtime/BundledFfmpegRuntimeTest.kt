package com.ireum.ytdl.util.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class BundledFfmpegRuntimeTest {
    @Test
    fun completeArm64RuntimeIsAvailable() = withRuntimeLayout { nativeDirectory, payloadRoot ->
        val result = BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot) { true }

        assertTrue(result is BundledFfmpegRuntimeResolution.Available)
        val available = result as BundledFfmpegRuntimeResolution.Available
        assertEquals(File(nativeDirectory, "libffmpeg.so").canonicalFile, available.ffmpegExecutable)
        assertEquals(File(nativeDirectory, "libffprobe.so").canonicalFile, available.ffprobeExecutable)
        assertEquals(File(payloadRoot, "usr/lib").canonicalFile, available.payloadLibraryDirectory)
    }

    @Test
    fun missingExecutableIsUnavailable() = withRuntimeLayout { nativeDirectory, payloadRoot ->
        File(nativeDirectory, "libffprobe.so").delete()

        val result = BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot) { true }

        assertEquals(
            BundledFfmpegRuntimeResolution.Unavailable("libffprobe.so is missing or unusable"),
            result,
        )
    }

    @Test
    fun nonElfExecutableIsUnavailable() = withRuntimeLayout { nativeDirectory, payloadRoot ->
        File(nativeDirectory, "libffmpeg.so").writeBytes(ByteArray(512) { 1 })

        val result = BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot) { true }

        assertEquals(
            BundledFfmpegRuntimeResolution.Unavailable("libffmpeg.so is missing or unusable"),
            result,
        )
    }

    @Test
    fun missingRequiredPayloadLibraryIsUnavailable() = withRuntimeLayout { nativeDirectory, payloadRoot ->
        File(payloadRoot, BundledFfmpegRuntime.REQUIRED_PAYLOAD_RELATIVE_PATHS.first()).delete()

        val result = BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot) { true }

        assertTrue(result is BundledFfmpegRuntimeResolution.Unavailable)
        assertTrue((result as BundledFfmpegRuntimeResolution.Unavailable).reason.contains("libavdevice.so.61"))
    }

    @Test
    fun unsupportedPayloadRevisionIsUnavailable() = withRuntimeLayout { nativeDirectory, payloadRoot ->
        File(payloadRoot, ".payload_revision").writeText("unknown-revision")

        val result = BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot) { true }

        assertEquals(
            BundledFfmpegRuntimeResolution.Unavailable("FFmpeg payload revision is missing or unsupported"),
            result,
        )
    }

    @Test
    fun missingGenerationProvenanceIsUnavailable() = withRuntimeLayout { nativeDirectory, payloadRoot ->
        File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_GENERATION_FILE).delete()

        val result = BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot) { true }

        assertTrue(result is BundledFfmpegRuntimeResolution.Unavailable)
        assertTrue((result as BundledFfmpegRuntimeResolution.Unavailable).reason.contains("generation provenance"))
    }

    @Test
    fun installerFailureCannotReturnAnOtherwiseAvailableRuntime() = withRuntimeLayout { nativeDirectory, payloadRoot ->
        val available = BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot) { true }

        val result = BundledFfmpegRuntime.verifyInstalledGeneration(
            BundledFfmpegInstallResult.Failure("injected install failure"),
            available,
        )

        assertTrue(result is BundledFfmpegRuntimeResolution.Unavailable)
        assertTrue((result as BundledFfmpegRuntimeResolution.Unavailable).reason.contains("injected install failure"))
    }

    @Test
    fun consumerResolutionMustMatchTheExactInstallerGeneration() = withRuntimeLayout { nativeDirectory, payloadRoot ->
        val available = BundledFfmpegRuntime.validate(nativeDirectory, payloadRoot) { true }
            as BundledFfmpegRuntimeResolution.Available

        val matching = BundledFfmpegRuntime.verifyInstalledGeneration(
            BundledFfmpegInstallResult.VerifiedCurrent(requireNotNull(available.generation)),
            available,
        )
        val stale = BundledFfmpegRuntime.verifyInstalledGeneration(
            BundledFfmpegInstallResult.VerifiedNew(UUID.randomUUID().toString()),
            available,
        )

        assertEquals(available, matching)
        assertTrue(stale is BundledFfmpegRuntimeResolution.Unavailable)
    }

    private fun withRuntimeLayout(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("bundled-ffmpeg-runtime-").toFile()
        try {
            val nativeDirectory = File(root, "native").apply { mkdirs() }
            listOf("libffmpeg.so", "libffprobe.so").forEach { name ->
                File(nativeDirectory, name).writeBytes(ELF_HEADER + ByteArray(508))
            }
            val payloadRoot = File(root, "packages/ffmpeg")
            File(payloadRoot, "usr/lib").mkdirs()
            BundledFfmpegRuntime.REQUIRED_PAYLOAD_RELATIVE_PATHS.forEach { relativePath ->
                File(payloadRoot, relativePath).apply {
                    parentFile?.mkdirs()
                    writeBytes(ELF_HEADER + ByteArray(508))
                }
            }
            File(payloadRoot, ".payload_revision").writeText(BundledFfmpegRuntime.PAYLOAD_REVISION)
            File(payloadRoot, BundledFfmpegRuntime.PAYLOAD_GENERATION_FILE).writeText(UUID.randomUUID().toString())

            block(nativeDirectory, payloadRoot)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        val ELF_HEADER = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    }
}
