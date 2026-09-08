package com.ireum.ytdl.util.storage

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Ownership marker for a single cached Terminal execution directory. */
internal object TerminalCacheOwnership {
    private const val MARKER_NAME = ".ytdlnisx-terminal-owner"
    private const val ARTIFACT_MANIFEST_NAME = ".ytdlnisx-terminal-artifacts.txt"
    private const val RECOVERY_CARRIER_NAME = ".ytdlnisx-terminal-recovery.json"
    private const val VERSION = "1"
    private val gson = Gson()

    data class OwnedRoot(
        val directory: File,
        val marker: File,
    )

    /** Marker-revoked state that remains discoverable only by recovery code. */
    data class RecoveryRoot(
        val directory: File,
        val carrier: File,
        val taskToken: String,
        val remainingSourcePaths: List<String>,
        val publishedDestinationPaths: List<String>,
        val phase: String,
    )

    fun markerFile(directory: File): File = File(directory, MARKER_NAME)

    fun artifactManifestFile(directory: File): File = File(directory, ARTIFACT_MANIFEST_NAME)

    fun recoveryCarrierFile(directory: File): File = File(directory, RECOVERY_CARRIER_NAME)

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
        val recoveryCarrier = recoveryCarrierFile(root).canonicalFile
        val existing = marker.takeIf(File::isFile)?.let { runCatching { parse(it.readText()) }.getOrNull() }
        if (existing == null && recoveryCarrier.isFile) {
            // A marker-revoked remainder is quarantine state for the prior
            // task. Reusing that directory for a new token would let E2
            // inherit E1's exact files while hiding the recovery carrier.
            throw IllegalStateException(
                "Refusing to reuse Terminal cache with a pending recovery carrier: ${root.absolutePath}"
            )
        }
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

    /**
     * Persist the exact partial-publication state before revoking the live
     * marker.  The carrier is intentionally separate from OwnedRoot: it is a
     * recovery/quarantine proof and is never accepted by listOwnedRoots().
     */
    fun recordRecoveryCarrier(
        directory: File,
        taskToken: String,
        publishedDestinationPaths: Iterable<String> = emptyList(),
        phase: String = "PARTIAL_PUBLICATION",
    ): Boolean {
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        if (!isOwned(root, taskToken)) return false
        val manifest = artifactManifestFile(root)
        val remaining = runCatching {
            if (!manifest.isFile) emptyList() else manifest.readLines()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .mapNotNull { relative ->
                    val file = File(root, relative).canonicalFile
                    file.takeIf { isInside(it, root) && it.isFile }?.absolutePath
                }
        }.getOrNull() ?: return false
        val published = publishedDestinationPaths.map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val carrier = recoveryCarrierFile(root)
        if (carrier.exists() && !isValidRecoveryCarrier(root, taskToken)) {
            // Do not overwrite an unreadable or mismatched recovery carrier;
            // retaining the live marker is safer than revoking ownership and
            // making the only exact recovery evidence undiscoverable.
            return false
        }
        val payload = RecoveryPayload(
            version = VERSION,
            taskToken = taskToken,
            sourceRoot = root.absolutePath,
            remainingSourcePaths = remaining,
            publishedDestinationPaths = published,
            phase = phase,
        )
        return writeAtomically(carrier, payload)
    }

    /** Validate an existing recovery carrier without granting live authority. */
    fun isValidRecoveryCarrier(directory: File, taskToken: String): Boolean {
        val root = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        val payload = runCatching {
            val carrier = recoveryCarrierFile(root)
            if (!carrier.isFile) null else gson.fromJson(carrier.readText(), RecoveryPayload::class.java)
        }.getOrNull() ?: return false
        return payload.version == VERSION &&
            payload.taskToken == taskToken &&
            payload.sourceRoot == root.absolutePath &&
            !payload.phase.isNullOrBlank() &&
            payload.remainingSourcePaths != null &&
            payload.publishedDestinationPaths != null
    }

    /** Explicit recovery discovery; these roots are not live import roots. */
    fun listRecoveryRoots(cacheRoot: File): List<RecoveryRoot> {
        val terminalRoot = runCatching { File(cacheRoot.canonicalFile, "TERMINAL").canonicalFile }
            .getOrNull() ?: return emptyList()
        if (!terminalRoot.isDirectory) return emptyList()
        return terminalRoot.listFiles()
            ?.asSequence()
            ?.filter(File::isDirectory)
            ?.mapNotNull { directory ->
                val carrier = recoveryCarrierFile(directory)
                val payload = runCatching {
                    if (!carrier.isFile) null else gson.fromJson(
                        carrier.readText(),
                        RecoveryPayload::class.java,
                    )
                }.getOrNull() ?: return@mapNotNull null
                val root = runCatching { directory.canonicalFile }.getOrNull() ?: return@mapNotNull null
                val taskToken = payload.taskToken ?: return@mapNotNull null
                val sourceRoot = payload.sourceRoot ?: return@mapNotNull null
                val phase = payload.phase ?: return@mapNotNull null
                val remainingRaw = payload.remainingSourcePaths ?: return@mapNotNull null
                if (
                    payload.version != VERSION || taskToken.isBlank() ||
                    sourceRoot != root.absolutePath || phase.isBlank() ||
                    markerFile(root).isFile
                ) return@mapNotNull null
                val remaining = remainingRaw.mapNotNull { raw ->
                    runCatching {
                        File(raw).canonicalFile.takeIf { it.isFile && isInside(it, root) }?.absolutePath
                    }.getOrNull()
                }.distinct()
                if (remaining.size != remainingRaw.map(String::trim).filter(String::isNotBlank).distinct().size) {
                    return@mapNotNull null
                }
                val published = runCatching {
                    payload.publishedDestinationPaths.orEmpty()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinct()
                }.getOrElse { return@mapNotNull null }
                RecoveryRoot(
                    directory = root,
                    carrier = carrier.canonicalFile,
                    taskToken = taskToken,
                    remainingSourcePaths = remaining,
                    publishedDestinationPaths = published,
                    phase = phase,
                )
            }
            ?.distinctBy { it.carrier.absolutePath }
            ?.toList()
            .orEmpty()
    }

    fun clearRecoveryCarrier(recovery: RecoveryRoot): Boolean =
        !recovery.carrier.exists() || recovery.carrier.delete() || !recovery.carrier.exists()

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

    private data class RecoveryPayload(
        val version: String?,
        val taskToken: String?,
        val sourceRoot: String?,
        val remainingSourcePaths: List<String>?,
        val publishedDestinationPaths: List<String>?,
        val phase: String?,
    )

    private fun writeAtomically(file: File, payload: RecoveryPayload): Boolean = runCatching {
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(gson.toJson(payload).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            // Preserve the previous carrier until the replacement move has
            // succeeded; deleting it first would lose exact recovery state
            // across a crash during a partial publication.
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        file.isFile
    }.getOrDefault(false)
}
