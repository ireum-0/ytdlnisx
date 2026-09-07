package com.ireum.ytdl.util.storage

import java.io.File

/** Ownership marker for a single cached Terminal execution directory. */
internal object TerminalCacheOwnership {
    private const val MARKER_NAME = ".ytdlnisx-terminal-owner"
    private const val VERSION = "1"

    data class OwnedRoot(
        val directory: File,
        val marker: File,
    )

    fun markerFile(directory: File): File = File(directory, MARKER_NAME)

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
}
