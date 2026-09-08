package com.ireum.ytdl.work

import java.io.File
import java.util.UUID

/**
 * Exact ownership carrier for the temporary AV mux stage used by hard-sub.
 * The cache root may be user-configured/shared, so a generated directory name
 * is not enough authority for recursive cleanup.  Every removable file is
 * recorded explicitly and unknown descendants are preserved.
 */
internal object HardSubMuxStageOwnership {
    private const val MARKER_NAME = ".ytdlnisx-hardsub-owner"
    private const val MANIFEST_NAME = ".ytdlnisx-hardsub-artifacts.txt"
    private const val HEADER = "ytdlnisx-hardsub-artifacts"

    data class Stage(
        val root: File,
        val token: String,
    )

    fun create(cacheRoot: File, requestedToken: String? = null): Stage? {
        val parent = File(cacheRoot.canonicalFile, "hardsub_mux_stage").canonicalFile
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) return null
        if (!parent.isDirectory) return null
        repeat(if (requestedToken == null) 8 else 1) {
            val token = requestedToken ?: UUID.randomUUID().toString()
            val root = File(parent, token).canonicalFile
            // mkdir (rather than mkdirs) is intentionally used here: a
            // pre-existing collision is never reused or overwritten.
            if (!root.mkdir()) return@repeat
            val marker = markerFile(root)
            return runCatching {
                marker.writeText(markerText(token))
                if (!marker.isFile) null else Stage(root, token)
            }.getOrElse {
                marker.delete()
                root.delete()
                null
            }
        }
        return null
    }

    fun recordArtifacts(stage: Stage, files: Iterable<File>): Boolean {
        val root = stage.root.canonicalFile
        if (!isOwned(stage)) return false
        val entries = files.mapNotNull { file ->
            runCatching {
                val canonical = file.canonicalFile
                if (!canonical.isFile || !isInside(canonical, root) ||
                    canonical == markerFile(root) || canonical == manifestFile(root)
                ) null else canonical.relativeTo(root).invariantSeparatorsPath
            }.getOrNull()
        }.filter(String::isNotBlank).toSortedSet()
        if (entries.isEmpty()) return false
        return runCatching {
            val manifest = manifestFile(root)
            val existingManifest = readManifest(manifest)
            // A malformed existing carrier is evidence whose ownership cannot
            // be established. Never replace it with a new manifest: doing so
            // could erase exact recovery/cleanup evidence for unknown files.
            if (manifest.exists() && existingManifest == null) return@runCatching false
            val existing = existingManifest.orEmpty().toMutableSet()
            existing += entries
            manifest.writeText(
                buildString {
                    append(HEADER)
                    append('\n')
                    append("files:\n")
                    existing.toSortedSet().forEach {
                        append(it)
                        append('\n')
                    }
                },
            )
            manifest.isFile
        }.getOrDefault(false)
    }

    /** Delete only exact manifest entries; preserve unknown descendants. */
    fun cleanup(stage: Stage): Boolean {
        val root = stage.root.canonicalFile
        if (!isOwned(stage)) return false
        val manifest = manifestFile(root)
        val entries = readManifest(manifest) ?: return false
        entries.forEach { relative ->
            val candidate = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return@forEach
            if (isInside(candidate, root) && candidate != markerFile(root) && candidate != manifest && candidate.isFile) {
                candidate.delete()
            }
        }
        if (manifest.exists() && !manifest.delete()) return false
        pruneEmptyDirectories(root)
        val marker = markerFile(root)
        val remaining = root.listFiles()?.filter { it != marker } ?: return false
        if (remaining.isNotEmpty()) return false
        if (!marker.delete() && marker.exists()) return false
        if (!root.delete() && root.exists()) return false
        val parent = root.parentFile
        if (parent?.isDirectory == true && parent.listFiles()?.isEmpty() == true) parent.delete()
        return true
    }

    fun isOwned(stage: Stage): Boolean {
        val root = runCatching { stage.root.canonicalFile }.getOrNull() ?: return false
        val marker = markerFile(root)
        return marker.isFile && runCatching { marker.readText() == markerText(stage.token) }.getOrDefault(false)
    }

    private fun markerFile(root: File): File = File(root, MARKER_NAME)

    private fun manifestFile(root: File): File = File(root, MANIFEST_NAME)

    private fun markerText(token: String): String =
        "ytdlnisx-hardsub-owner\nversion=1\ntoken=$token\n"

    private fun readManifest(file: File): List<String>? {
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull()?.trim() != HEADER || lines.drop(1).none { it.trim() == "files:" }) return null
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
                if (directory.listFiles()?.isEmpty() == true) directory.delete()
            }
    }

    private fun isInside(candidate: File, root: File): Boolean = runCatching {
        candidate.canonicalFile.toPath().normalize().startsWith(root.canonicalFile.toPath().normalize())
    }.getOrDefault(false)
}
