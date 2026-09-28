package com.ireum.ytdl.util.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.FileOutputStream
import java.util.UUID

sealed interface BundledFfmpegRuntimeResolution {
    data class Available(
        val ffmpegExecutable: File,
        val ffprobeExecutable: File,
        val payloadLibraryDirectory: File,
        val generation: String?,
    ) : BundledFfmpegRuntimeResolution

    data class Unavailable(val reason: String) : BundledFfmpegRuntimeResolution
}

object BundledFfmpegRuntime {
    const val PAYLOAD_REVISION = "arm64-wrapper-libffmpeg-0.18.1-r12"
    const val PAYLOAD_REVISION_FILE = ".payload_revision"
    const val PAYLOAD_GENERATION_FILE = ".payload_generation"
    internal const val MINIMUM_LIBRARY_BYTES = 255L

    val REQUIRED_PAYLOAD_RELATIVE_PATHS = listOf(
        "usr/lib/libavdevice.so.61",
        "usr/lib/libavfilter.so.10",
        "usr/lib/libavformat.so.61",
        "usr/lib/libavcodec.so.61",
        "usr/lib/libavutil.so.59",
        "usr/lib/libc++_shared.so",
        "usr/lib/libexpat.so.1",
        "usr/lib/libcrypto.so.3",
        "usr/lib/libssl.so.3",
    )

    val REQUIRED_COPIED_DEPENDENCIES = listOf(
        "libc++_shared.so",
        "libexpat.so.1",
        "libcrypto.so.3",
        "libssl.so.3",
    )

    @Volatile
    internal var resolutionForTesting: ((Context) -> BundledFfmpegRuntimeResolution)? = null

    fun resolve(
        context: Context,
        ensureRuntimeInstalled: () -> BundledFfmpegInstallResult,
    ): BundledFfmpegRuntimeResolution {
        resolutionForTesting?.let { return it(context) }
        val installation = runCatching(ensureRuntimeInstalled).getOrElse { error ->
            BundledFfmpegInstallResult.Failure(
                error.message ?: "FFmpeg runtime installation failed",
                error,
            )
        }
        return verifyInstalledGeneration(
            installation,
            validate(
                nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir),
                payloadRoot = File(
                    context.noBackupFilesDir,
                    "youtubedl-android/packages/ffmpeg",
                ),
            ),
        )
    }

    internal fun verifyInstalledGeneration(
        installation: BundledFfmpegInstallResult,
        resolution: BundledFfmpegRuntimeResolution,
    ): BundledFfmpegRuntimeResolution {
        if (installation is BundledFfmpegInstallResult.Failure) {
            return BundledFfmpegRuntimeResolution.Unavailable(
                "FFmpeg runtime installation failed: ${installation.reason}",
            )
        }
        val available = resolution as? BundledFfmpegRuntimeResolution.Available ?: return resolution
        val expectedGeneration = when (installation) {
            is BundledFfmpegInstallResult.VerifiedCurrent -> installation.generation
            is BundledFfmpegInstallResult.VerifiedNew -> installation.generation
            is BundledFfmpegInstallResult.Failure -> error("handled above")
        }
        return if (available.generation == expectedGeneration) {
            available
        } else {
            BundledFfmpegRuntimeResolution.Unavailable(
                "FFmpeg runtime generation changed after installation verification",
            )
        }
    }

    internal fun validate(
        nativeLibraryDirectory: File,
        payloadRoot: File,
        expectedRevision: String = PAYLOAD_REVISION,
        requireGeneration: Boolean = true,
        canExecute: (File) -> Boolean = File::canExecute,
    ): BundledFfmpegRuntimeResolution {
        val nativeDir = runCatching { nativeLibraryDirectory.canonicalFile }.getOrElse {
            return BundledFfmpegRuntimeResolution.Unavailable("native library directory is unreadable")
        }
        if (!nativeDir.isDirectory) {
            return BundledFfmpegRuntimeResolution.Unavailable("native library directory is missing")
        }

        val ffmpeg = validateExecutable(nativeDir, "libffmpeg.so", canExecute)
            ?: return BundledFfmpegRuntimeResolution.Unavailable("libffmpeg.so is missing or unusable")
        val ffprobe = validateExecutable(nativeDir, "libffprobe.so", canExecute)
            ?: return BundledFfmpegRuntimeResolution.Unavailable("libffprobe.so is missing or unusable")

        val canonicalPayloadRoot = runCatching { payloadRoot.canonicalFile }.getOrElse {
            return BundledFfmpegRuntimeResolution.Unavailable("FFmpeg payload root is unreadable")
        }
        val expectedPayloadParent = runCatching { payloadRoot.absoluteFile.parentFile?.canonicalFile }.getOrNull()
        if (expectedPayloadParent == null || canonicalPayloadRoot.parentFile != expectedPayloadParent || canonicalPayloadRoot.name != payloadRoot.name) {
            return BundledFfmpegRuntimeResolution.Unavailable("FFmpeg payload root is outside its owned package directory")
        }
        val revisionFile = File(canonicalPayloadRoot, PAYLOAD_REVISION_FILE)
        val revision = runCatching { revisionFile.readText(Charsets.UTF_8).trim() }.getOrNull()
        if (revision != expectedRevision) {
            return BundledFfmpegRuntimeResolution.Unavailable("FFmpeg payload revision is missing or unsupported")
        }

        val generation = runCatching {
            File(canonicalPayloadRoot, PAYLOAD_GENERATION_FILE).readText(Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { isGenerationId(it) }
        if (requireGeneration && generation == null) {
            return BundledFfmpegRuntimeResolution.Unavailable("FFmpeg payload generation provenance is missing or unsupported")
        }

        val payloadLibraryDirectory = runCatching {
            File(canonicalPayloadRoot, "usr/lib").canonicalFile
        }.getOrElse {
            return BundledFfmpegRuntimeResolution.Unavailable("FFmpeg payload library directory is unreadable")
        }
        if (!payloadLibraryDirectory.isDirectory) {
            return BundledFfmpegRuntimeResolution.Unavailable("FFmpeg payload library directory is missing")
        }

        val missingLibrary = REQUIRED_PAYLOAD_RELATIVE_PATHS.firstOrNull { relativePath ->
            val library = runCatching { File(canonicalPayloadRoot, relativePath).canonicalFile }.getOrNull()
                ?: return@firstOrNull true
            library.parentFile != payloadLibraryDirectory ||
                !library.isFile ||
                library.length() <= MINIMUM_LIBRARY_BYTES ||
                !hasElfHeader(library)
        }
        if (missingLibrary != null) {
            return BundledFfmpegRuntimeResolution.Unavailable(
                "required FFmpeg payload library is missing or unusable: $missingLibrary",
            )
        }

        return BundledFfmpegRuntimeResolution.Available(
            ffmpegExecutable = ffmpeg,
            ffprobeExecutable = ffprobe,
            payloadLibraryDirectory = payloadLibraryDirectory,
            generation = generation,
        )
    }

    private fun validateExecutable(
        nativeLibraryDirectory: File,
        name: String,
        canExecute: (File) -> Boolean,
    ): File? {
        val executable = runCatching {
            File(nativeLibraryDirectory, name).canonicalFile
        }.getOrNull() ?: return null
        if (
            executable.parentFile != nativeLibraryDirectory ||
                executable.name != name ||
                !executable.isFile ||
                executable.length() <= MINIMUM_LIBRARY_BYTES ||
                !canExecute(executable) ||
            !hasElfHeader(executable)
        ) {
            return null
        }
        return executable
    }

    private fun hasElfHeader(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            input.read(header) == header.size &&
                header[0] == 0x7F.toByte() &&
                header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    private fun isGenerationId(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}

/** Copies the four dependencies that the FFmpeg payload cannot run without. */
internal object BundledFfmpegRequiredDependencyCopier {
    fun copy(
        libraryDirectory: File,
        openAsset: (name: String) -> InputStream,
    ) {
        if (!libraryDirectory.exists() && !libraryDirectory.mkdirs()) {
            throw IOException("Could not create required FFmpeg library directory")
        }
        if (!libraryDirectory.isDirectory) throw IOException("Required FFmpeg library path is not a directory")

        BundledFfmpegRuntime.REQUIRED_COPIED_DEPENDENCIES.forEach { name ->
            val target = File(libraryDirectory, name)
            if (target.isFile && target.length() > BundledFfmpegRuntime.MINIMUM_LIBRARY_BYTES) return@forEach

            val temporary = File(libraryDirectory, ".$name.${UUID.randomUUID()}.copying")
            try {
                FileOutputStream(temporary, false).use { output ->
                    openAsset(name).use { input -> input.copyTo(output) }
                    output.fd.sync()
                }
                temporary.setReadable(true, true)
                if (!temporary.isFile || temporary.length() <= BundledFfmpegRuntime.MINIMUM_LIBRARY_BYTES) {
                    throw IOException("Required FFmpeg dependency $name was copied incompletely")
                }
                if (target.exists() && !target.delete()) {
                    throw IOException("Could not replace incomplete FFmpeg dependency $name")
                }
                if (!temporary.renameTo(target)) {
                    throw IOException("Could not publish required FFmpeg dependency $name")
                }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }
}

class FfmpegRuntimeUnavailableException(reason: String) : IOException(
    "FFmpeg runtime unavailable: $reason",
)
