package com.ireum.ytdl.util.storage

import java.io.File

/** One exact source file selected for cache migration. */
internal data class CacheImportArtifact(
    val source: File,
    val relativePath: String,
    val ownershipMarker: File,
)

/**
 * A marker-revoked Terminal remainder.  Recovery code may inspect this
 * carrier explicitly, but normal cache import must never treat it as live
 * publication authority.
 */
internal data class CacheRecoveryArtifact(
    val directory: File,
    val carrier: File,
    val taskToken: String,
    val remainingSourcePaths: List<String>,
    val publishedDestinationPaths: List<String>,
    val phase: String,
)

/**
 * Builds a migration manifest from explicit operation ownership markers.
 * Unknown files and legacy directories are deliberately not part of the
 * manifest, even when they happen to live below the configured cache root.
 */
internal object CacheImportPlanner {
    fun collect(cacheRoot: File): List<CacheImportArtifact> {
        val root = runCatching { cacheRoot.canonicalFile }.getOrNull() ?: return emptyList()
        if (!root.isDirectory) return emptyList()

        val ownedRoots = buildList {
            addAll(DownloadCacheOwnership.listOwnedRoots(root).map {
                Triple(it.directory, it.marker, root)
            })
            addAll(TerminalCacheOwnership.listOwnedRoots(root).map {
                Triple(it.directory, it.marker, root)
            })
        }

        return ownedRoots
            .flatMap { (directory, marker, cache) ->
                val explicitFiles = if (marker.name.startsWith(".ytdlnisx-download-owner-")) {
                    DownloadCacheOwnership.listArtifactFiles(
                        DownloadCacheOwnership.OwnedRoot(directory, marker)
                    )
                } else {
                    TerminalCacheOwnership.listArtifactFiles(
                        TerminalCacheOwnership.OwnedRoot(directory, marker)
                    )
                }
                explicitFiles.mapNotNull { source ->
                    val relative = runCatching {
                        source.relativeTo(cache.canonicalFile).invariantSeparatorsPath
                    }.getOrNull() ?: return@mapNotNull null
                    CacheImportArtifact(source, relative, marker)
                }
            }
            .distinctBy { it.source.absolutePath }
            .sortedBy { it.relativePath }
    }

    /**
     * Enumerate only explicit Terminal quarantine carriers.  This is a
     * separate API from collect(): callers must deliberately enter recovery
     * mode, and these artifacts are never included in ordinary import.
     */
    fun collectRecovery(cacheRoot: File): List<CacheRecoveryArtifact> =
        TerminalCacheOwnership.listRecoveryRoots(cacheRoot).map { root ->
            CacheRecoveryArtifact(
                directory = root.directory,
                carrier = root.carrier,
                taskToken = root.taskToken,
                remainingSourcePaths = root.remainingSourcePaths,
                publishedDestinationPaths = root.publishedDestinationPaths,
                phase = root.phase,
            )
        }

    /** Pick a destination without replacing an existing unrelated file. */
    fun collisionSafeDestination(destinationRoot: File, relativePath: String): File {
        val initial = File(destinationRoot, relativePath).canonicalFile
        if (!initial.exists()) return initial

        val parent = initial.parentFile ?: return initial
        val name = initial.name
        val extension = initial.extension
        val stem = if (extension.isBlank()) name else name.removeSuffix(".$extension")
        var index = 1
        while (true) {
            val candidateName = if (extension.isBlank()) {
                "$stem ($index)"
            } else {
                "$stem ($index).$extension"
            }
            val candidate = File(parent, candidateName).canonicalFile
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun isInside(candidate: File, root: File): Boolean = runCatching {
        candidate.canonicalFile.toPath().normalize()
            .startsWith(root.canonicalFile.toPath().normalize())
    }.getOrDefault(false)
}
