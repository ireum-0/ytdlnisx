package com.ireum.ytdl.util.storage

import java.io.File

/** Ownership marker for a single cached Terminal execution directory. */
internal object TerminalCacheOwnership {
    private const val MARKER_NAME = ".ytdlnisx-terminal-owner"
    private const val ARTIFACT_MANIFEST_NAME = ".ytdlnisx-terminal-artifacts.txt"
    private const val VERSION = "1"

    data class OwnedRoot(
        val directory: File,
        val marker: File,
    )

    fun markerFile(directory: File): File = File(directory, MARKER_NAME)

    fun artifactManifestFile(directory: File): File = File(directory, ARTIFACT_MANIFEST_NAME)

    /** Remove the exact-output manifest after its entries have been consumed. */
    fun removeArtifactManifest(directory: File): Boolean {
        val root = directory.canonicalFile
        if (!isOwned(root)) return false
        val manifest = artifactManifestFile(root)
        return !manifest.exists() || manifest.delete() || !manifest.exists()
    }

    fun ensureMarker(directory: File, taskToken: String): File {
        require(taskToken.isNotBlank()) { "Terminal cache ownership requires a task token" }
        val root = directory.canonicalFile
        if (!root.exists() && !root.mkdirs()) {
            throw IllegalStateException("Could not create Terminal cache directory: ${root.absolutePath}")
        }
        if (!root.isDirectory) {
            throw IllegalStateException("Terminal cache path is not a directory: ${root.absolutePath}")
        }
        val marker = markerFile(root).canonicalFile
        val existing = marker.takeIf(File::isFile)?.let { runCatching { parse(it.readText()) }.getOrNull() }
        if (existing != null &&
            (existing["version"] != VERSION || existing["taskToken"] != taskToken)
        ) {
            throw IllegalStateException("Refusing to reuse Terminal cache owned by another task")
        }
        marker.writeText(
            "ytdlnisx-terminal-owner\n" +
                "version=$VERSION\n" +
                "taskToken=$taskToken\n"
        )
        if (!marker.isFile) {
            throw IllegalStateException("Terminal cache ownership marker was not created")
        }
        return marker
    }

    /** Persist only exact current-attempt files as migration authority. */
    fun recordArtifacts(directory: File, files: Iterable<String>): Boolean {
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        if (!markerFile(root).isFile) return false
        val entries = files.mapNotNull { raw ->
            runCatching {
                val file = File(raw).canonicalFile
                if (!file.isFile || !isInside(file, root)) null
                else file.relativeTo(root).invariantSeparatorsPath
            }.getOrNull()
        }.filter { it.isNotBlank() && it != MARKER_NAME && it != ARTIFACT_MANIFEST_NAME }
            .toSortedSet()
        if (entries.isEmpty()) return false
        return runCatching {
            artifactManifestFile(root).writeText(entries.joinToString("\n", postfix = "\n"))
            artifactManifestFile(root).isFile
        }.getOrDefault(false)
    }

    /** True only when the exact staging directory carries a valid owner marker. */
    fun isOwned(directory: File, taskToken: String? = null): Boolean {
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        val marker = markerFile(root)
        val fields = runCatching {
            if (marker.isFile) parse(marker.readText()) else emptyMap()
        }.getOrDefault(emptyMap())
        return fields["version"] == VERSION &&
            fields["taskToken"].orEmpty().isNotBlank() &&
            (taskToken == null || fields["taskToken"] == taskToken)
    }

    /**
     * Delete only exact files recorded by this execution. Unknown children
     * revoke the marker and remain available for recovery/diagnostics.
     */
    fun deleteIfOwned(directory: File, taskToken: String? = null): Boolean {
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        if (!isOwned(root, taskToken)) return false
        if (!root.exists()) return true
        if (!root.isDirectory) return false

        val marker = markerFile(root)
        val manifest = artifactManifestFile(root)
        val entries = runCatching {
            if (!manifest.isFile) emptyList() else manifest.readLines()
        }.getOrDefault(emptyList())
            .map(String::trim)
            .filter { it.isNotBlank() }
            .distinct()
        val initialChildren = root.listFiles()?.toList() ?: return false
        if (entries.isEmpty() && initialChildren.any {
                it.name != MARKER_NAME && it.name != ARTIFACT_MANIFEST_NAME
            }) {
            marker.delete()
            return false
        }

        entries.forEach { relative ->
            val candidate = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return@forEach
            if (!isInside(candidate, root) || candidate == marker || candidate == manifest) return@forEach
            if (candidate.isFile) candidate.delete()
        }
        manifest.delete()
        pruneEmptyDirectories(root)
        val remaining = root.listFiles()?.toList()?.filter {
            it.name != MARKER_NAME && it.name != ARTIFACT_MANIFEST_NAME
        } ?: return false
        if (remaining.isNotEmpty()) {
            marker.delete()
            return false
        }
        val deleted = root.delete()
        if (!deleted && root.exists()) marker.delete()
        return deleted || !root.exists()
    }

    /**
     * Revoke a failed/cancelled attempt without deleting its exact remainder.
     * A partial publication can leave authoritative files behind after
     * FileUtil.moveFile has moved only a prefix of the manifest.  Removing the
     * marker prevents migration/publication from treating that directory as a
     * live Terminal result, while retaining the manifest and files for
     * deterministic recovery/diagnostics.
     */
    fun revokeOwnershipPreservingArtifacts(directory: File, taskToken: String? = null): Boolean {
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        if (!isOwned(root, taskToken)) return false
        val marker = markerFile(root)
        return !marker.exists() || marker.delete() || !marker.exists()
    }

    fun listArtifactFiles(root: OwnedRoot): List<File> = runCatching {
        val manifest = artifactManifestFile(root.directory)
        if (!manifest.isFile) return@runCatching emptyList()
        manifest.readLines()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { relative ->
                val file = File(root.directory, relative).canonicalFile
                file.takeIf {
                    it.isFile && it != root.marker.canonicalFile &&
                        it != manifest.canonicalFile && isInside(it, root.directory)
                }
            }
    }.getOrDefault(emptyList())

    fun listOwnedRoots(cacheRoot: File): List<OwnedRoot> {
        val terminalRoot = runCatching { File(cacheRoot.canonicalFile, "TERMINAL").canonicalFile }
            .getOrNull() ?: return emptyList()
        if (!terminalRoot.isDirectory) return emptyList()
        return terminalRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.mapNotNull { directory ->
                val marker = markerFile(directory)
                val fields = runCatching {
                    if (marker.isFile) parse(marker.readText()) else emptyMap()
                }.getOrDefault(emptyMap())
                if (fields["version"] != VERSION || fields["taskToken"].orEmpty().isBlank()) {
                    null
                } else {
                    OwnedRoot(directory.canonicalFile, marker.canonicalFile)
                }
            }
            ?.distinctBy { it.directory.absolutePath }
            ?.toList()
            .orEmpty()
    }

    private fun parse(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }
        .toMap()

    private fun pruneEmptyDirectories(root: File) {
        root.walkBottomUp()
            .filter { it != root && it.isDirectory }
            .forEach { directory ->
                if (directory.listFiles()?.toList()?.isEmpty() == true) directory.delete()
            }
    }

    private fun isInside(candidate: File, root: File): Boolean = runCatching {
        candidate.canonicalFile.toPath().normalize()
            .startsWith(root.canonicalFile.toPath().normalize())
    }.getOrDefault(false)
}
