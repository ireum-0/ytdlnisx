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
    private const val VERSION = "1"

    data class OwnedRoot(
        val directory: File,
        val marker: File,
    )

    fun markerFile(cacheRoot: File, downloadId: Long): File =
        File(cacheRoot, "$MARKER_PREFIX$downloadId$MARKER_SUFFIX")

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
        val existing = marker.takeIf(File::isFile)?.let { runCatching { it.readText() }.getOrNull() }
        if (existing != null) {
            val existingFields = parse(existing)
            val existingDownloadId = existingFields["downloadId"]?.toLongOrNull()
            val existingOperationId = existingFields["operationId"].orEmpty()
            if (existingDownloadId != item.id || existingOperationId != item.operationId) {
                throw IllegalStateException(
                    "Refusing to reuse Download cache owned by another operation: ${marker.absolutePath}"
                )
            }
        }
        marker.writeText(markerText(item))
        if (!marker.isFile) {
            throw IllegalStateException("Download cache ownership marker was not created")
        }
        return marker
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
            fields["operationId"] == item.operationId
    }

    /** Delete one exact numeric staging root only when its marker proves ownership. */
    fun deleteIfOwned(cacheRoot: File, item: DownloadItem): Boolean {
        val root = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return false
        val directory = File(root, item.id.toString()).canonicalFile
        if (directory.parentFile?.canonicalFile != root || directory.name != item.id.toString()) {
            return false
        }
        if (!isOwned(root, item)) return false
        val deleted = !directory.exists() || directory.deleteRecursively()
        if (!deleted) return false
        markerFile(root, item.id).delete()
        return true
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

    private fun parse(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }
        .toMap()
}
