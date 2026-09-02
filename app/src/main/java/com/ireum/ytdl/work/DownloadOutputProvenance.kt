package com.ireum.ytdl.work

import java.io.File

/**
 * Carries output authority for one yt-dlp execution attempt.
 *
 * A path reported by yt-dlp is only an artifact candidate until it is
 * validated against the attempt-owned staging root (or, for the legacy raw
 * command path, an exact new path in the configured direct destination).
 * Directory membership, recency, and filename similarity are deliberately
 * absent from this contract.
 */
internal class DownloadOutputProvenance(
    tempDirectory: File,
    directDirectory: File? = null,
) {
    private val tempRoot = tempDirectory.canonicalFile
    private val directRoot = directDirectory?.canonicalFile

    private var tempRootWasCleanAtAttemptStart = false
    private var directPathsPresentAtAttemptStart: Set<String> = emptySet()
    private var attemptStarted = false
    private val currentAttemptPaths = linkedSetOf<String>()

    /** Begin a new route/retry attempt without carrying the previous attempt's paths. */
    fun beginAttempt() {
        attemptStarted = true
        currentAttemptPaths.clear()
        tempRootWasCleanAtAttemptStart = isEmptyOrMissingDirectory(tempRoot)
        directPathsPresentAtAttemptStart = snapshotFiles(directRoot)
    }

    /**
     * Record paths emitted by the current yt-dlp response. Only paths that
     * are visible as files in an attempt-owned root become authoritative.
     */
    fun acceptYtdlpOutput(output: String): List<String> {
        if (!attemptStarted) return emptyList()
        val accepted = parseYtdlpOutputPaths(output)
            .mapNotNull(::acceptReportedPath)
            .distinct()
        currentAttemptPaths.addAll(accepted)
        return currentAttemptPaths.toList()
    }

    /**
     * Record exact destinations returned by a move/copy operation. The
     * operation result is the provenance carrier; no destination rescan is
     * performed here.
     */
    fun recordMoveResults(paths: Iterable<String>, sourcePaths: Iterable<String>): List<String> {
        if (!attemptStarted || !tempRootWasCleanAtAttemptStart) return emptyList()
        val normalizedSources = sourcePaths
            .mapNotNull(::normalizeStoredPath)
            .distinct()
        if (
            normalizedSources.isEmpty() ||
            normalizedSources.any { source ->
                !isAuthoritative(source) ||
                    source.startsWith("content://") ||
                    !isInside(File(source), tempRoot)
            }
        ) {
            return emptyList()
        }
        val accepted = paths
            .mapNotNull(::normalizeStoredPath)
            .distinct()
        currentAttemptPaths.addAll(accepted)
        return accepted
    }

    /** Record an exact transform output whose input is already authoritative. */
    fun recordDerivedOutput(outputPath: String, inputPaths: Iterable<String>): String? {
        if (!attemptStarted) return null
        val normalizedInputs = inputPaths
            .mapNotNull(::normalizeStoredPath)
            .distinct()
        if (normalizedInputs.isEmpty() || normalizedInputs.any { !isAuthoritative(it) }) return null
        val normalized = normalizeFilesystemFile(outputPath) ?: return null
        currentAttemptPaths.add(normalized)
        return normalized
    }

    fun currentAttemptPaths(): List<String> = currentAttemptPaths.toList()

    /**
     * Failed output processing must not erase a file whose ownership was
     * never established. This signal is for cleanup policy only; it never
     * promotes the file into the authoritative output set.
     */
    fun hasUnprovenTemporaryArtifacts(): Boolean {
        if (!attemptStarted) return false
        val authoritative = currentAttemptPaths.toList()
        return snapshotFiles(tempRoot).any { candidate ->
            authoritative.none { equivalentStoredPath(it, candidate) }
        }
    }

    fun isAuthoritative(path: String): Boolean {
        val normalized = normalizeStoredPath(path) ?: return false
        return currentAttemptPaths.any { equivalentStoredPath(it, normalized) }
    }

    private fun acceptReportedPath(rawPath: String): String? {
        val normalized = normalizeFilesystemFile(rawPath) ?: return null
        val file = File(normalized)
        return when {
            tempRootWasCleanAtAttemptStart && isInside(file, tempRoot) -> normalized
            directRoot != null &&
                isInside(file, directRoot) &&
                normalized !in directPathsPresentAtAttemptStart -> normalized
            else -> null
        }
    }

    private fun normalizeFilesystemFile(rawPath: String): String? {
        val trimmed = rawPath.trim().trim('"', '\'')
        if (trimmed.isBlank() || trimmed.startsWith("content://")) return null
        return runCatching {
            val file = File(trimmed).canonicalFile
            if (file.exists() && file.isFile) file.absolutePath else null
        }.getOrNull()
    }

    private fun normalizeStoredPath(rawPath: String): String? {
        val trimmed = rawPath.trim().trim('"', '\'')
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("content://")) return trimmed
        return runCatching { File(trimmed).canonicalFile.absolutePath }.getOrNull()
    }

    private fun equivalentStoredPath(first: String, second: String): Boolean {
        if (first.startsWith("content://") || second.startsWith("content://")) {
            return first == second
        }
        return runCatching {
            File(first).canonicalFile == File(second).canonicalFile
        }.getOrDefault(false)
    }

    private fun isEmptyOrMissingDirectory(directory: File): Boolean {
        if (!directory.exists()) return true
        if (!directory.isDirectory) return false
        return runCatching { directory.walkTopDown().none { it.isFile } }.getOrDefault(false)
    }

    private fun snapshotFiles(directory: File?): Set<String> {
        if (directory == null || !directory.exists() || !directory.isDirectory) return emptySet()
        return runCatching {
            directory.walkTopDown()
                .filter { it.isFile }
                .map { it.canonicalFile.absolutePath }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun isInside(file: File, root: File): Boolean {
        return runCatching {
            file.canonicalFile.toPath().normalize()
                .startsWith(root.canonicalFile.toPath().normalize())
        }.getOrDefault(false)
    }

    companion object {
        const val PRINT_MARKER = "__YTDLNISX_OUTPUT__"

        /** Parse only output-bearing yt-dlp lines; this is not a directory discovery operation. */
        fun parseYtdlpOutputPaths(output: String): List<String> {
            val markers = listOf(
                "Destination:",
                "Merging formats into",
                "Writing video subtitles to:",
                "Writing automatic subtitles to:",
                "Writing subtitles to:",
                "Writing video thumbnail to:",
                "Writing thumbnail to:",
                "Writing description to:",
                "Writing metadata to:",
                "Writing internet shortcut to:",
            )
            val paths = linkedSetOf<String>()
            output.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) return@forEach

                val markerIndex = trimmed.indexOf(PRINT_MARKER)
                if (markerIndex >= 0) {
                    parseReportedValue(trimmed.substring(markerIndex + PRINT_MARKER.length))?.let(paths::add)
                }

                markers.firstOrNull { trimmed.contains(it) }?.let { marker ->
                    val value = trimmed.substringAfter(marker).trim()
                    parseReportedValue(value)?.let(paths::add)
                }

                if (
                    (trimmed.startsWith("'/") && trimmed.endsWith("'")) ||
                    (trimmed.startsWith("\"/") && trimmed.endsWith("\""))
                ) {
                    parseReportedValue(trimmed)?.let(paths::add)
                }
            }
            return paths.toList()
        }

        private fun parseReportedValue(rawValue: String): String? {
            var value = rawValue.trim()
            val markerIndex = value.indexOf(PRINT_MARKER)
            if (markerIndex >= 0) {
                value = value.substring(markerIndex + PRINT_MARKER.length)
            }
            value = value.trim().trim('"', '\'')
            return value.takeIf { isAbsolutePath(it) && it.length > 1 }
        }

        private fun isAbsolutePath(value: String): Boolean {
            return value.startsWith("/") ||
                value.matches(Regex("^[A-Za-z]:[\\\\/].+"))
        }
    }
}
