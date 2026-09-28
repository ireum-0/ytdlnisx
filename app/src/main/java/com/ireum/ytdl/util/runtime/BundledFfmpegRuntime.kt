package com.ireum.ytdl.util.runtime

import android.content.Context
import java.io.File
import java.io.IOException

sealed interface BundledFfmpegRuntimeResolution {
    data class Available(
        val ffmpegExecutable: File,
        val ffprobeExecutable: File,
        val payloadLibraryDirectory: File,
    ) : BundledFfmpegRuntimeResolution

    data class Unavailable(val reason: String) : BundledFfmpegRuntimeResolution
}

object BundledFfmpegRuntime {
    const val PAYLOAD_REVISION = "arm64-wrapper-libffmpeg-0.18.1-r12"

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
        ensureRuntimeInstalled: () -> Unit,
    ): BundledFfmpegRuntimeResolution {
        resolutionForTesting?.let { return it(context) }
        val installationFailure = runCatching(ensureRuntimeInstalled).exceptionOrNull()
        val resolution = validate(
            nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir),
            payloadRoot = File(
                context.noBackupFilesDir,
                "youtubedl-android/packages/ffmpeg",
            ),
        )
        if (resolution !is BundledFfmpegRuntimeResolution.Unavailable || installationFailure == null) {
            return resolution
        }
        return resolution.copy(
            reason = "${resolution.reason}; runtime installation failed: " +
                (installationFailure.message ?: installationFailure.javaClass.simpleName),
        )
    }

    internal fun validate(
        nativeLibraryDirectory: File,
        payloadRoot: File,
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
        val revisionFile = File(canonicalPayloadRoot, ".payload_revision")
        val revision = runCatching { revisionFile.readText(Charsets.UTF_8).trim() }.getOrNull()
        if (revision != PAYLOAD_REVISION) {
            return BundledFfmpegRuntimeResolution.Unavailable("FFmpeg payload revision is missing or unsupported")
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
                library.length() <= MINIMUM_LIBRARY_BYTES
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

    private const val MINIMUM_LIBRARY_BYTES = 255L
}

class FfmpegRuntimeUnavailableException(reason: String) : IOException(
    "FFmpeg runtime unavailable: $reason",
)
