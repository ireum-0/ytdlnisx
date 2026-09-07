package com.ireum.ytdl.work

import java.io.File
import java.io.IOException

/**
 * Carries publication authority for one cached Terminal execution.
 *
 * The Terminal cache directory is operation-scoped and is deliberately not
 * treated as an output manifest.  Only paths explicitly reported by the
 * current yt-dlp response, while they exist under the clean attempt root,
 * can become move sources.
 */
internal class TerminalOutputAuthority(
    val stagingRoot: File,
) {
    private val provenance = DownloadOutputProvenance(stagingRoot)

    fun beginAttempt() {
        provenance.beginAttempt()
    }

    fun currentSourceFiles(output: String): List<File> {
        return provenance
            .acceptYtdlpOutput(output)
            .mapNotNull { path ->
                runCatching { File(path).canonicalFile }
                    .getOrNull()
                    ?.takeIf { it.isFile && isInside(it, stagingRoot) }
            }
            .distinctBy { it.absolutePath }
    }

    fun recordMoveResults(
        movedPaths: Iterable<String>,
        sourceFiles: Iterable<File>,
    ): List<String> {
        return provenance.recordMoveResults(
            paths = movedPaths,
            sourcePaths = sourceFiles.map { it.absolutePath },
        )
    }

    fun hasUnprovenTemporaryArtifacts(): Boolean = provenance.hasUnprovenTemporaryArtifacts()

    private fun isInside(file: File, root: File): Boolean {
        return runCatching {
            file.canonicalFile.toPath().normalize()
                .startsWith(root.canonicalFile.toPath().normalize())
        }.getOrDefault(false)
    }
}

internal fun requireTerminalSourceFiles(
    authority: TerminalOutputAuthority,
    output: String,
): List<File> {
    val sources = authority.currentSourceFiles(output)
    if (sources.isEmpty()) {
        throw IOException("Terminal completed without an authoritative current-attempt output path")
    }
    return sources
}
