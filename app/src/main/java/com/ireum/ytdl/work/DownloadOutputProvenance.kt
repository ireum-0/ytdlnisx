package com.ireum.ytdl.work

import com.ireum.ytdl.util.extractors.ytdlp.YtdlpOutputPlan
import java.io.File
import java.io.IOException

/**
 * Carries output authority for one yt-dlp execution attempt.
 *
 * A path reported by yt-dlp is only an artifact candidate until it is
 * validated against the attempt-owned staging root.  The staging root may
 * live beside the final destination for a no-cache operation, but it is
 * created and marked by the current worker before yt-dlp starts.  Directory
 * membership, recency, and filename similarity are deliberately absent from
 * this contract.
 */
internal class DownloadOutputProvenance(
    tempDirectory: File,
    directDirectory: File? = null,
    private val directOwnershipMarker: File? = null,
    private val baselineSnapshotReader: ((File) -> BaselineSnapshot)? = null,
) {
    private val tempRoot = tempDirectory.canonicalFile
    private val directRoot = directDirectory?.canonicalFile

    private var tempRootWasCleanAtAttemptStart = false
    private var directRootReadyAtAttemptStart = false
    private var tempBaseline: BaselineSnapshot = BaselineSnapshot.Failed("attempt not started")
    private var directBaseline: BaselineSnapshot = BaselineSnapshot.Complete(emptySet())
    private var attemptStarted = false
    private val currentAttemptPaths = linkedSetOf<String>()

    internal sealed interface BaselineSnapshot {
        data class Complete(val files: Set<String>) : BaselineSnapshot
        data class Failed(val reason: String) : BaselineSnapshot
    }

    /** Begin a new route/retry attempt without carrying the previous attempt's paths. */
    fun beginAttempt() {
        attemptStarted = true
        currentAttemptPaths.clear()
        tempBaseline = readBaseline(tempRoot)
        directBaseline = directRoot?.let(::readBaseline) ?: BaselineSnapshot.Complete(emptySet())
        tempRootWasCleanAtAttemptStart = tempBaseline.isCompleteAndEmpty()
        directRootReadyAtAttemptStart = directRoot != null &&
            directOwnershipMarker != null &&
            directBaseline.isOwnedRoot(directOwnershipMarker)
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
        if (!attemptStarted) return emptyList()
        val normalizedSources = sourcePaths
            .mapNotNull(::normalizeStoredPath)
            .distinct()
        if (
            normalizedSources.isEmpty() ||
            normalizedSources.any { source ->
                !isAuthoritative(source) || !isOwnedSource(source)
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
        return hasUnprovenArtifacts(tempRoot, tempBaseline) ||
            (directRoot != null && hasUnprovenArtifacts(directRoot, directBaseline))
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
            directRootReadyAtAttemptStart &&
                isInside(file, directRoot!!) &&
                !isOwnershipMarker(file) -> normalized
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

    private fun isOwnedSource(path: String): Boolean {
        if (path.startsWith("content://")) return false
        val file = File(path)
        return (tempRootWasCleanAtAttemptStart && isInside(file, tempRoot)) ||
            (directRootReadyAtAttemptStart && directRoot != null && isInside(file, directRoot))
    }

    private fun isOwnershipMarker(file: File): Boolean =
        directOwnershipMarker?.let { marker -> equivalentStoredPath(file.absolutePath, marker.absolutePath) } == true

    private fun readBaseline(directory: File): BaselineSnapshot =
        runCatching {
            baselineSnapshotReader?.invoke(directory) ?: snapshotFiles(directory)
        }.getOrElse { error ->
            BaselineSnapshot.Failed(
                "baseline acquisition failed for ${directory.absolutePath}: " +
                    (error.message ?: error.javaClass.simpleName)
            )
        }

    private fun hasUnprovenArtifacts(
        directory: File,
        baseline: BaselineSnapshot,
    ): Boolean {
        if (baseline is BaselineSnapshot.Failed) return true
        val authoritative = currentAttemptPaths.toList()
        val current = snapshotFiles(directory)
        val currentFiles = (current as? BaselineSnapshot.Complete)?.files ?: return true
        return currentFiles.any { candidate ->
            !isOwnershipMarker(File(candidate)) &&
                authoritative.none { equivalentStoredPath(it, candidate) }
        }
    }

    private fun BaselineSnapshot.isCompleteAndEmpty(): Boolean =
        this is BaselineSnapshot.Complete && files.isEmpty()

    private fun BaselineSnapshot.isOwnedRoot(marker: File): Boolean {
        if (this !is BaselineSnapshot.Complete) return false
        val normalizedMarker = runCatching { marker.canonicalFile.absolutePath }.getOrNull() ?: return false
        return directRoot != null &&
            isInside(marker, directRoot) &&
            marker.exists() && marker.isFile &&
            files.contains(normalizedMarker) &&
            files.all { it == normalizedMarker }
    }

    private fun snapshotFiles(directory: File): BaselineSnapshot {
        return try {
            if (!directory.exists()) return BaselineSnapshot.Complete(emptySet())
            if (!directory.isDirectory) {
                return BaselineSnapshot.Failed("path is not a directory: ${directory.absolutePath}")
            }
            val root = directory.canonicalFile
            val files = linkedSetOf<String>()

            fun visit(current: File) {
                val canonicalCurrent = current.canonicalFile
                if (!isInside(canonicalCurrent, root)) {
                    throw IOException("directory escaped baseline root: ${current.absolutePath}")
                }
                val children = current.listFiles()
                    ?: throw IOException("could not enumerate directory: ${current.absolutePath}")
                children.forEach { child ->
                    val canonicalChild = child.canonicalFile
                    if (!isInside(canonicalChild, root)) {
                        throw IOException("child escaped baseline root: ${child.absolutePath}")
                    }
                    when {
                        child.isDirectory -> visit(child)
                        child.isFile -> files += canonicalChild.absolutePath
                        else -> throw IOException("unsupported directory entry: ${child.absolutePath}")
                    }
                }
            }

            visit(directory)
            BaselineSnapshot.Complete(files)
        } catch (error: Exception) {
            BaselineSnapshot.Failed(
                "baseline enumeration failed for ${directory.absolutePath}: " +
                    (error.message ?: error.javaClass.simpleName)
            )
        }
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

internal data class DirectOutputStagingCleanupResult(
    val markerDeleted: Boolean,
    val tokenDirectoryDeleted: Boolean,
    val namespaceDeleted: Boolean,
)

internal enum class DirectOutputPublicationState {
    SUCCESS,
    PARTIAL,
    FAILED,
    AMBIGUOUS,
}

/**
 * Removes only the current operation's successful direct-output marker. This
 * is intentionally a non-recursive operation: any unexpected or stranded
 * child prevents directory pruning and remains in place for recovery or
 * diagnostics.
 */
internal object DirectOutputStagingCleanup {
    fun removeOwnedMarkerAndEmptyParents(
        outputPlan: YtdlpOutputPlan,
        expectedMarkerText: String,
        publicationState: DirectOutputPublicationState,
    ): DirectOutputStagingCleanupResult? {
        if (
            !outputPlan.directNoCache ||
                publicationState != DirectOutputPublicationState.SUCCESS
        ) return null
        val staging = outputPlan.directStagingDirectory?.canonicalFile ?: return null
        val namespace = staging.parentFile?.canonicalFile ?: return null
        val stagingParent = outputPlan.directStagingParent?.canonicalFile ?: return null
        val marker = outputPlan.ownershipMarker?.canonicalFile ?: return null
        if (
            namespace.name != ".ytdlnisx-output" ||
                namespace.parentFile?.canonicalFile != stagingParent ||
                marker.parentFile?.canonicalFile != staging ||
                !staging.isDirectory ||
                !marker.isFile
        ) {
            return null
        }
        val markerText = runCatching { marker.readText() }.getOrNull() ?: return null
        if (markerText != expectedMarkerText) return null
        if (!marker.delete()) return null

        val tokenDirectoryDeleted = runCatching {
            staging.listFiles()?.let { entries ->
                entries.isEmpty() && staging.delete()
            } ?: false
        }.getOrDefault(false)
        val namespaceDeleted = if (tokenDirectoryDeleted) {
            runCatching {
                namespace.listFiles()?.let { entries ->
                    entries.isEmpty() && namespace.delete()
                } ?: false
            }.getOrDefault(false)
        } else {
            false
        }
        return DirectOutputStagingCleanupResult(
            markerDeleted = true,
            tokenDirectoryDeleted = tokenDirectoryDeleted,
            namespaceDeleted = namespaceDeleted,
        )
    }
}
