package com.ireum.ytdl.util.storage

import com.ireum.ytdl.database.models.DownloadItem
import java.io.File

/**
 * Durable ownership proof for the legacy numeric Download cache layout.
 *
 * The numeric directory name is only a location.  A sidecar marker binds the
 * directory to the Download's stable operation identity before any recursive
 * cleanup is permitted.  Unknown/legacy directories remain untouched.
 */
internal object DownloadCacheOwnership {
    private const val MARKER_PREFIX = ".ytdlnisx-download-owner-"
    private const val MARKER_SUFFIX = ".txt"
    private const val ARTIFACT_MANIFEST_NAME = ".ytdlnisx-download-artifacts.txt"
    private const val ARTIFACT_MANIFEST_HEADER = "ytdlnisx-download-artifacts"
    private const val VERSION = "1"

    data class OwnedRoot(
        val directory: File,
        val marker: File,
    )

    fun markerFile(cacheRoot: File, downloadId: Long): File =
        File(cacheRoot, "$MARKER_PREFIX$downloadId$MARKER_SUFFIX")

    fun artifactManifestFile(cacheRoot: File, downloadId: Long): File =
        File(File(cacheRoot, downloadId.toString()), ARTIFACT_MANIFEST_NAME)

    fun markerText(item: DownloadItem): String =
        "ytdlnisx-download-owner\n" +
            "version=$VERSION\n" +
            "downloadId=${item.id}\n" +
            "operationId=${item.operationId}\n" +
            "executionId=${item.executionId}\n"

    /** Establish/update the marker for a claimed operation before native I/O. */
    fun ensureMarker(cacheRoot: File, item: DownloadItem): File {
        require(item.id > 0L) { "Download cache ownership requires a persisted id" }
        require(item.operationId.isNotBlank()) { "Download cache ownership requires an operation id" }
        require(item.executionId.isNotBlank()) { "Download cache ownership requires an execution id" }
        val root = cacheRoot.canonicalFile
        if (!root.exists() && !root.mkdirs()) {
            throw IllegalStateException("Could not create Download cache root: ${root.absolutePath}")
        }
        if (!root.isDirectory) {
            throw IllegalStateException("Download cache root is not a directory: ${root.absolutePath}")
        }
        val marker = markerFile(root, item.id).canonicalFile
        val directory = File(root, item.id.toString()).canonicalFile
        val existing = marker.takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() }
        // Creating a sidecar marker is an authority-changing operation.  It
        // must not authenticate a numeric directory (or an old artifact
        // manifest) that was already present before this execution claimed
        // the root.  An empty directory is harmless and may be claimed; any
        // pre-existing child requires a prior marker-bound execution proof.
        if (existing == null && directory.exists()) {
            if (!directory.isDirectory) {
                throw IllegalStateException("Download cache path is not a directory: ${directory.absolutePath}")
            }
            val children = directory.listFiles()?.toList()
                ?: throw IllegalStateException(
                    "Could not inspect pre-existing Download cache contents: ${directory.absolutePath}"
                )
            if (children.isNotEmpty()) {
                throw IllegalStateException(
                    "Refusing to authenticate pre-existing Download cache contents: ${directory.absolutePath}"
                )
            }
        }
        if (existing != null) {
            val existingFields = parse(existing)
            val existingDownloadId = existingFields["downloadId"]?.toLongOrNull()
            val existingOperationId = existingFields["operationId"].orEmpty()
            val existingExecutionId = existingFields["executionId"].orEmpty()
            if (
                existingDownloadId != item.id ||
                existingOperationId != item.operationId ||
                existingExecutionId != item.executionId
            ) {
                val hasUnprovenContents = when {
                    !directory.exists() -> false
                    !directory.isDirectory -> true
                    else -> {
                        val children = directory.listFiles()?.toList() ?: throw IllegalStateException(
                            "Could not inspect stale Download cache contents: ${directory.absolutePath}"
                        )
                        artifactManifestFile(root, item.id).isFile ||
                            children.any { it.name != ARTIFACT_MANIFEST_NAME }
                    }
                }
                if (hasUnprovenContents) {
                    throw IllegalStateException(
                        "Refusing to reuse Download cache owned by another execution: ${marker.absolutePath}"
                    )
                }
                // An empty abandoned marker carries no artifact authority and
                // can be replaced by the newly claimed execution.  Never
                // replace a marker while any prior content remains.
                if (!marker.delete() && marker.exists()) {
                    throw IllegalStateException(
                        "Could not rotate stale Download cache ownership marker: ${marker.absolutePath}"
                    )
                }
            }
        }
        marker.writeText(markerText(item))
        if (!marker.isFile) {
            throw IllegalStateException("Download cache ownership marker was not created")
        }
        return marker
    }

    /**
     * Prepare a numeric staging root without recursively deleting unproven
     * children.  Existing files must be listed in the prior operation's exact
     * artifact manifest; otherwise the caller fails closed.
     */
    fun prepareAttempt(cacheRoot: File, item: DownloadItem): File {
        val root = cacheRoot.canonicalFile
        val marker = ensureMarker(root, item)
        val directory = File(root, item.id.toString()).canonicalFile
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw IllegalStateException("Download cache path is not a directory: ${directory.absolutePath}")
            }
            val initialChildren = directory.listFiles()?.toList()
                ?: throw IllegalStateException(
                    "Could not inspect Download cache contents: ${directory.absolutePath}"
                )
            val manifest = readArtifactManifest(root, item)
                ?: throw IllegalStateException(
                    "Refusing to consume an unbound Download artifact manifest: ${artifactManifestFile(root, item.id).absolutePath}"
                )
            if (manifest.isEmpty() && initialChildren.isNotEmpty()) {
                throw IllegalStateException(
                    "Refusing to delete unproven Download cache contents: ${directory.absolutePath}"
                )
            }
            manifest.forEach { relative ->
                val candidate = File(directory, relative).canonicalFile
                if (isInside(candidate, directory) && candidate.isFile) candidate.delete()
            }
            artifactManifestFile(root, item.id).delete()
            pruneEmptyDirectories(directory)
            val remainingChildren = directory.listFiles()?.toList()
                ?: throw IllegalStateException(
                    "Could not verify Download cache cleanup: ${directory.absolutePath}"
                )
            if (remainingChildren.isNotEmpty()) {
                throw IllegalStateException(
                    "Refusing to delete unproven Download cache contents: ${directory.absolutePath}"
                )
            }
            if (!directory.delete() && directory.exists()) {
                throw IllegalStateException("Could not clean Download cache directory: ${directory.absolutePath}")
            }
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IllegalStateException("Could not create Download cache directory: ${directory.absolutePath}")
        }
        if (!marker.isFile) throw IllegalStateException("Download cache ownership marker disappeared")
        return directory
    }

    /** True only for a marker bound to this Download operation. */
    fun isOwned(cacheRoot: File, item: DownloadItem): Boolean {
        if (item.id <= 0L || item.operationId.isBlank()) return false
        val root = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return false
        val marker = markerFile(root, item.id).canonicalFile
        val fields = runCatching { if (marker.isFile) parse(marker.readText()) else emptyMap() }
            .getOrDefault(emptyMap())
        return fields["version"] == VERSION &&
            fields["downloadId"]?.toLongOrNull() == item.id &&
            fields["operationId"] == item.operationId &&
            fields["executionId"] == item.executionId
    }

    /** Delete one exact numeric staging root only when its marker proves ownership. */
    fun deleteIfOwned(cacheRoot: File, item: DownloadItem): Boolean {
        val root = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return false
        val directory = File(root, item.id.toString()).canonicalFile
        if (directory.parentFile?.canonicalFile != root || directory.name != item.id.toString()) {
            return false
        }
        if (!isOwned(root, item)) return false
        if (!directory.exists()) {
            markerFile(root, item.id).delete()
            return true
        }
        if (!directory.isDirectory) return false

        // A marker proves the operation root, not every child that happens to
        // be below it.  Delete only paths recorded by the current operation's
        // exact artifact manifest; unknown/stale children remain untouched.
        val initialChildren = directory.listFiles()?.toList() ?: return false
        val manifest = readArtifactManifest(root, item) ?: return false
        if (manifest.isEmpty() && initialChildren.any { it.name != ARTIFACT_MANIFEST_NAME }) {
            return false
        }
        manifest.forEach { relative ->
            val candidate = File(directory, relative).canonicalFile
            if (!isInside(candidate, directory)) return@forEach
            if (candidate.isFile) candidate.delete()
        }
        artifactManifestFile(root, item.id).delete()
        pruneEmptyDirectories(directory)
        val remaining = directory.listFiles()?.toList() ?: return false
        if (remaining.isNotEmpty()) {
            // Revoke the import/cleanup marker when unproven content remains;
            // preserving the directory is safer than recursive deletion.
            markerFile(root, item.id).delete()
            return false
        }
        val deleted = directory.delete()
        if (deleted || !directory.exists()) markerFile(root, item.id).delete()
        return deleted || !directory.exists()
    }

    /** Record exact current-attempt artifacts for later cleanup/import. */
    fun recordArtifacts(cacheRoot: File, item: DownloadItem, files: Iterable<String>): Boolean {
        if (!isOwned(cacheRoot, item)) return false
        val root = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return false
        val directory = File(root, item.id.toString()).canonicalFile
        if (!directory.isDirectory) return false
        val entries = files.mapNotNull { raw ->
            runCatching {
                val file = File(raw).canonicalFile
                if (!file.isFile || !isInside(file, directory)) {
                    null
                } else {
                    file.relativeTo(directory).invariantSeparatorsPath
                }
            }.getOrNull()
        }.filter { it.isNotBlank() && it != ARTIFACT_MANIFEST_NAME }
            .toSortedSet()
        if (entries.isEmpty()) return false
        return runCatching {
            artifactManifestFile(root, item.id).writeText(
                buildString {
                    append(ARTIFACT_MANIFEST_HEADER)
                    append('\n')
                    append("version=")
                    append(VERSION)
                    append('\n')
                    append("downloadId=")
                    append(item.id)
                    append('\n')
                    append("operationId=")
                    append(item.operationId)
                    append('\n')
                    append("executionId=")
                    append(item.executionId)
                    append('\n')
                    append("files:\n")
                    entries.forEach { entry ->
                        append(entry)
                        append('\n')
                    }
                }
            )
            artifactManifestFile(root, item.id).isFile
        }.getOrDefault(false)
    }

    /**
     * Retire the current-attempt manifest after all of its exact sources have
     * been published and the caller is not retaining the cache.  The
     * ownership marker remains at the cache root; unknown files below the
     * numeric directory are therefore still preserved if they exist.
     */
    fun removeArtifactManifest(cacheRoot: File, item: DownloadItem): Boolean {
        val root = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return false
        if (!isOwned(root, item)) return false
        val manifest = artifactManifestFile(root, item.id)
        val removed = !manifest.exists() || manifest.delete() || !manifest.exists()
        if (removed) {
            val directory = File(root, item.id.toString()).canonicalFile
            if (directory.isDirectory && directory.listFiles()?.toList()?.isEmpty() == true) {
                directory.delete()
            }
        }
        return removed
    }

    fun listArtifactFiles(root: OwnedRoot): List<File> {
        val markerFields = runCatching { parse(root.marker.readText()) }.getOrDefault(emptyMap())
        val entries = readArtifactManifest(
            root.directory.parentFile ?: return emptyList(),
            markerFields["downloadId"]?.toLongOrNull() ?: return emptyList(),
            markerFields["operationId"].orEmpty(),
            markerFields["executionId"].orEmpty(),
        ) ?: return emptyList()
        return entries.mapNotNull { relative ->
            runCatching {
                val file = File(root.directory, relative).canonicalFile
                file.takeIf {
                        it.isFile && it != root.marker.canonicalFile &&
                        it != root.directory.resolve(ARTIFACT_MANIFEST_NAME).canonicalFile &&
                        isInside(it, root.directory)
                }
            }.getOrNull()
        }
    }

    /**
     * Enumerate only numeric roots carrying a valid app ownership marker.
     * Directory membership or a numeric name alone is deliberately ignored.
     */
    fun listOwnedRoots(cacheRoot: File): List<OwnedRoot> {
        val root = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return emptyList()
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith(MARKER_PREFIX) && it.name.endsWith(MARKER_SUFFIX) }
            ?.mapNotNull { marker ->
                val fields = runCatching { parse(marker.readText()) }.getOrNull() ?: return@mapNotNull null
                if (fields["version"] != VERSION) return@mapNotNull null
                val id = fields["downloadId"]?.toLongOrNull() ?: return@mapNotNull null
                if (fields["operationId"].orEmpty().isBlank()) return@mapNotNull null
                if (fields["executionId"].orEmpty().isBlank()) return@mapNotNull null
                if (marker.name != "$MARKER_PREFIX$id$MARKER_SUFFIX") return@mapNotNull null
                val directory = File(root, id.toString()).canonicalFile
                if (directory.parentFile?.canonicalFile != root || !directory.isDirectory) {
                    return@mapNotNull null
                }
                OwnedRoot(directory = directory, marker = marker.canonicalFile)
            }
            ?.distinctBy { it.directory.absolutePath }
            ?.toList()
            .orEmpty()
    }

    private fun readArtifactManifest(root: File, item: DownloadItem): List<String>? =
        readArtifactManifest(root, item.id, item.operationId, item.executionId)

    /**
     * Read an artifact manifest only when it carries the same identity as the
     * ownership marker/operation that is consuming it.  A missing manifest is
     * represented by an empty list; a present malformed or differently bound
     * manifest is represented by null and must fail closed.
     */
    private fun readArtifactManifest(
        root: File,
        downloadId: Long,
        operationId: String,
        executionId: String,
    ): List<String>? {
        val file = artifactManifestFile(root, downloadId)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull()?.trim() != ARTIFACT_MANIFEST_HEADER) return null
        val header = lines.drop(1).takeWhile { it.trim() != "files:" }.mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        if (
            header["version"] != VERSION ||
            header["downloadId"]?.toLongOrNull() != downloadId ||
            header["operationId"] != operationId ||
            header["executionId"] != executionId ||
            !lines.drop(1).any { it.trim() == "files:" }
        ) {
            return null
        }
        return lines.dropWhile { it.trim() != "files:" }
            .drop(1)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun pruneEmptyDirectories(root: File) {
        root.walkBottomUp()
            .filter { it != root && it.isDirectory }
            .forEach { directory ->
                if (directory.listFiles().orEmpty().isEmpty()) directory.delete()
            }
    }

    private fun isInside(candidate: File, root: File): Boolean = runCatching {
        candidate.canonicalFile.toPath().normalize()
            .startsWith(root.canonicalFile.toPath().normalize())
    }.getOrDefault(false)

    private fun parse(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }
        .toMap()
}
