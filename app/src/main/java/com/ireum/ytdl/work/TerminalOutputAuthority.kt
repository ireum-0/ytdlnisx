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
    ownershipMarker: File? = null,
) {
    // Terminal staging is created with an ownership marker before the
    // attempt begins. Treat that marker exactly like Download direct-output
    // staging: it is part of the clean baseline and is never an output, while
    // reported files under the same root remain eligible for authority.
    private val provenance = DownloadOutputProvenance(
        tempDirectory = stagingRoot,
        directDirectory = ownershipMarker?.let { stagingRoot },
        directOwnershipMarker = ownershipMarker,
    )

    fun beginAttempt() {
        provenance.beginAttempt()
    }

    fun currentSourceFiles(
        output: String,
        structuredMarker: File? = null,
        requireStructuredMarker: Boolean = false,
    ): List<File> {
        val structuredOutput = structuredMarker?.let { marker ->
            runCatching {
                if (!marker.isFile) null else marker.readText().takeIf { it.isNotBlank() }
            }.getOrNull()
        }
        if (requireStructuredMarker && structuredOutput == null) return emptyList()
        val authorityText = structuredOutput ?: output
        return provenance
            .acceptYtdlpOutput(authorityText)
            .mapNotNull { path ->
                runCatching { File(path).canonicalFile }
                    .getOrNull()
                    ?.takeIf { it.isFile && isInside(it, stagingRoot) }
            }
            .distinctBy { it.absolutePath }
    }

    fun removeStructuredMarker(marker: File?): Boolean {
        if (marker == null || !marker.exists()) return true
        return marker.delete() || !marker.exists()
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
    structuredMarker: File? = null,
): List<File> {
    val sources = authority.currentSourceFiles(
        output = output,
        structuredMarker = structuredMarker,
        requireStructuredMarker = structuredMarker != null,
    )
    if (sources.isEmpty()) {
        throw IOException("Terminal completed without an authoritative current-attempt output path")
    }
    return sources
}
