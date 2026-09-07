package com.ireum.ytdl.work

import com.google.gson.JsonElement
import com.google.gson.JsonParser
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
        val accepted = (if (output.contains(STRUCTURED_FILES_MARKER) ||
            output.contains(STRUCTURED_INFO_MARKER)
        ) {
            parseStructuredYtdlpOutputPaths(output)
        } else {
            parseYtdlpOutputPaths(output)
        })
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
        const val STRUCTURED_FILES_MARKER = "__YTDLNISX_FILES__"
        const val STRUCTURED_INFO_MARKER = "__YTDLNISX_INFO__"

        /**
         * Parse only app-generated machine markers.  The post_process marker
         * carries yt-dlp's exact __files_to_move map (including descriptions,
         * subtitles, thumbnails, chapter and playlist artifacts); the
         * after_move marker carries final filepath/sidecar fields.  Human
         * progress wording is intentionally not consulted when a marker is
         * present.
         */
        fun parseStructuredYtdlpOutputPaths(output: String): List<String> {
            val paths = linkedSetOf<String>()
            output.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) return@forEach
                extractCarrierPayload(trimmed, PRINT_MARKER)?.let { payload ->
                    parseReportedValue(payload)?.let(paths::add)
                }
                extractCarrierPayload(trimmed, STRUCTURED_FILES_MARKER)?.let { payload ->
                    parseStructuredFilesMap(payload)
                        .forEach(paths::add)
                }
                extractCarrierPayload(trimmed, STRUCTURED_INFO_MARKER)?.let { payload ->
                    // ``infojson_filename`` is a scalar path while subtitle,
                    // thumbnail, and chapter carriers are JSON objects/arrays.
                    // Accept the scalar form without weakening the structured
                    // field allow-list used for JSON payloads.
                    parseReportedValue(payload)?.let(paths::add)
                    parseStructuredJsonPaths(payload).forEach(paths::add)
                }
            }
            return paths.toList()
        }

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

                extractCarrierPayload(trimmed, PRINT_MARKER)?.let { payload ->
                    parseReportedValue(payload)?.let(paths::add)
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
            val value = rawValue.trim().trim('"', '\'')
            return value.takeIf { isAbsolutePath(it) && it.length > 1 }
        }

        /**
         * Extract an app-generated carrier only at its outer syntax boundary.
         * Marker text embedded in a real filename or parent directory is path
         * data, not a second carrier prefix and must remain untouched.
         */
        private fun extractCarrierPayload(line: String, marker: String): String? {
            val index = line.indexOf(marker)
            if (index < 0) return null
            val prefix = line.substring(0, index)
            if (prefix.isNotEmpty() && prefix.last() !in setOf(' ', '\t', '\'', '"', ':', '=')) {
                return null
            }
            return line.substring(index + marker.length)
        }

        private fun parseStructuredJsonPaths(rawValue: String): List<String> {
            val jsonText = rawValue.trim().trim('"', '\'')
            if (jsonText.isBlank() || jsonText == "NA" || jsonText == "None") return emptyList()
            return runCatching {
                val element = JsonParser.parseString(jsonText)
                val paths = linkedSetOf<String>()

                fun visit(
                    value: JsonElement,
                    allowFileFields: Boolean = false,
                    recurseForFileFields: Boolean = false,
                ) {
                    when {
                        value.isJsonObject -> value.asJsonObject.entrySet().forEach { (name, child) ->
                            // Only fields that yt-dlp documents as exact file
                            // carriers are authority-bearing.  Arbitrary
                            // metadata strings/URLs are never promoted.
                            if (allowFileFields && (name == "filepath" || name == "infojson_filename")) {
                                child.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                                    ?.asString
                                    ?.let { parseReportedValue(it)?.let(paths::add) }
                            } else if (name == "__files_to_move") {
                                if (child.isJsonObject) {
                                    child.asJsonObject.entrySet().forEach { (source, destination) ->
                                        parseReportedValue(source)?.let(paths::add)
                                        if (destination.isJsonPrimitive && destination.asJsonPrimitive.isString) {
                                            parseReportedValue(destination.asString)?.let(paths::add)
                                        }
                                    }
                                }
                            } else if (
                                name == "requested_subtitles" ||
                                name == "thumbnails" ||
                                name == "chapters"
                            ) {
                                // These are the exact yt-dlp structures used
                                // by the trusted marker arguments below. Do
                                // not recurse through arbitrary metadata
                                // objects, where a user-controlled field
                                // named `filepath` could regain authority.
                                visit(
                                    child,
                                    allowFileFields = true,
                                    recurseForFileFields = true,
                                )
                            } else if (recurseForFileFields) {
                                // requested_subtitles is keyed by language and
                                // thumbnails may contain nested exact records.
                                // Recurse only inside those known file-bearing
                                // containers, never through arbitrary metadata.
                                visit(
                                    child,
                                    allowFileFields = true,
                                    recurseForFileFields = true,
                                )
                            }
                        }
                        value.isJsonArray -> value.asJsonArray.forEach { child ->
                            visit(child, allowFileFields, recurseForFileFields)
                        }
                    }
                }
                // A direct requested-subtitles payload is keyed by language
                // (for example ``{"en": {"filepath": "/..."}}``), so it
                // needs one controlled recursive pass.  A record that already
                // exposes a top-level filepath/infojson_filename is kept
                // non-recursive so arbitrary metadata fields cannot smuggle a
                // nested filepath into the authority carrier.
                val rootIsFileRecord = element.isJsonObject &&
                    element.asJsonObject.entrySet().any { (name, _) ->
                        name == "filepath" || name == "infojson_filename"
                    }
                visit(
                    element,
                    allowFileFields = true,
                    recurseForFileFields = !rootIsFileRecord,
                )
                paths.toList()
            }.getOrDefault(emptyList())
        }

        /**
         * ``%(__files_to_move)#j`` serializes the move manifest itself, so
         * the marker payload is normally a JSON object whose keys are exact
         * source paths and values are exact destination paths.  Keep support
         * for the wrapped shape used by older test/injection seams, but never
         * promote arbitrary nested metadata fields from this carrier.
         */
        private fun parseStructuredFilesMap(rawValue: String): List<String> {
            val jsonText = rawValue.trim().trim('"', '\'')
            if (jsonText.isBlank() || jsonText == "NA" || jsonText == "None") return emptyList()
            return runCatching {
                val element = JsonParser.parseString(jsonText)
                val objectValue = element.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@runCatching emptyList()
                val mapValue = objectValue.get("__files_to_move")?.takeIf { it.isJsonObject }
                    ?: element
                if (!mapValue.isJsonObject) return@runCatching emptyList()
                mapValue.asJsonObject.entrySet().flatMap { (source, destination) ->
                    buildList {
                        parseReportedValue(source)?.let(::add)
                        if (destination.isJsonPrimitive && destination.asJsonPrimitive.isString) {
                            parseReportedValue(destination.asString)?.let(::add)
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

        /** Build the trusted direct-argv carrier shared by Download and Terminal. */
        fun structuredOutputMarkerArguments(marker: File): List<String> = listOf(
            "--print-to-file",
            "after_move:${PRINT_MARKER}%(filepath)s",
            marker.absolutePath,
            "--print-to-file",
            "post_process:${STRUCTURED_FILES_MARKER}%(__files_to_move)#j",
            marker.absolutePath,
            "--print-to-file",
            "after_move:${STRUCTURED_INFO_MARKER}%(requested_subtitles)#j",
            marker.absolutePath,
            "--print-to-file",
            "after_move:${STRUCTURED_INFO_MARKER}%(thumbnails)#j",
            marker.absolutePath,
            "--print-to-file",
            "after_move:${STRUCTURED_INFO_MARKER}%(chapters)#j",
            marker.absolutePath,
            "--print-to-file",
            "after_move:${STRUCTURED_INFO_MARKER}%(infojson_filename|)s",
            marker.absolutePath,
        )

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
    private const val ARTIFACT_MANIFEST_NAME = ".ytdlnisx-output-artifacts.txt"
    private const val ARTIFACT_MANIFEST_HEADER = "ytdlnisx-output-artifacts"

    /**
     * Persist the exact files reported by this direct attempt.  The marker
     * proves only the root identity; this manifest is the separate proof for
     * individual descendants used by retry/failure cleanup.
     */
    fun recordExactArtifacts(
        outputPlan: YtdlpOutputPlan,
        expectedMarkerText: String,
        paths: Iterable<String>,
    ): Boolean {
        val staging = validatedStaging(outputPlan, expectedMarkerText) ?: return false
        val entries = paths.mapNotNull { raw ->
            runCatching {
                val file = File(raw).canonicalFile
                if (!file.isFile || !isInside(file, staging) ||
                    file == outputPlan.ownershipMarker?.canonicalFile ||
                    file.name == ARTIFACT_MANIFEST_NAME
                ) null
                else file.relativeTo(staging).invariantSeparatorsPath
            }.getOrNull()
        }.filter(String::isNotBlank).toSortedSet()
        if (entries.isEmpty()) return false
        return runCatching {
            manifestFile(staging).writeText(
                buildString {
                    append(ARTIFACT_MANIFEST_HEADER)
                    append('\n')
                    append("files:\n")
                    entries.forEach { entry ->
                        append(entry)
                        append('\n')
                    }
                },
            )
            manifestFile(staging).isFile
        }.getOrDefault(false)
    }

    /**
     * Remove only manifest-listed direct artifacts and prune directories that
     * become empty.  An unexpected child leaves the root intact and causes a
     * fail-closed result; no recursive deletion is performed.
     */
    fun removeExactArtifactsAndEmptyParents(
        outputPlan: YtdlpOutputPlan,
        expectedMarkerText: String,
    ): Boolean {
        val staging = validatedStaging(outputPlan, expectedMarkerText) ?: return false
        val manifest = manifestFile(staging)
        val entries = readManifest(manifest) ?: return false
        entries.forEach { relative ->
            val candidate = runCatching { File(staging, relative).canonicalFile }.getOrNull() ?: return@forEach
            if (isInside(candidate, staging) && candidate != outputPlan.ownershipMarker?.canonicalFile &&
                candidate != manifest.canonicalFile && candidate.isFile
            ) {
                candidate.delete()
            }
        }
        if (manifest.exists() && !manifest.delete()) return false
        pruneEmptyDirectories(staging)
        val marker = outputPlan.ownershipMarker!!.canonicalFile
        val remaining = staging.listFiles()?.filter { it != marker } ?: return false
        if (remaining.isNotEmpty()) {
            // The marker remains live so a later exact cleanup can still
            // authenticate the root; unknown descendants are preserved.
            return false
        }
        if (!marker.delete() && marker.exists()) return false
        val removedRoot = staging.delete() || !staging.exists()
        if (!removedRoot) return false
        val namespace = staging.parentFile?.canonicalFile ?: return false
        if (namespace.isDirectory && namespace.listFiles()?.isEmpty() == true) {
            namespace.delete()
        }
        return true
    }

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
        val manifest = manifestFile(staging)
        val entries = readManifest(manifest) ?: return null
        if (entries.any { relative ->
                val candidate = runCatching { File(staging, relative).canonicalFile }.getOrNull()
                candidate != null && candidate.isFile
            }) {
            // The exact source carrier is still present (for example when a
            // copy-style publication intentionally retained the source).  Do
            // not revoke the marker and strand the manifest; leave the root
            // available for deterministic recovery.
            return null
        }
        if (manifest.exists() && !manifest.delete()) return null
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

    private fun validatedStaging(
        outputPlan: YtdlpOutputPlan,
        expectedMarkerText: String,
    ): File? {
        if (!outputPlan.directNoCache) return null
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
        ) return null
        return runCatching { marker.readText() }
            .getOrNull()
            ?.takeIf { it == expectedMarkerText }
            ?.let { staging }
    }

    private fun manifestFile(staging: File): File = File(staging, ARTIFACT_MANIFEST_NAME)

    private fun readManifest(manifest: File): List<String>? {
        if (!manifest.isFile) return emptyList()
        val lines = runCatching { manifest.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull()?.trim() != ARTIFACT_MANIFEST_HEADER) return null
        val filesIndex = lines.indexOfFirst { it.trim() == "files:" }
        if (filesIndex < 0) return null
        return lines.drop(filesIndex + 1).map(String::trim).filter(String::isNotBlank).distinct()
    }

    private fun pruneEmptyDirectories(root: File) {
        root.walkBottomUp()
            .filter { it != root && it.isDirectory }
            .forEach { directory ->
                if (directory.listFiles()?.isEmpty() == true) directory.delete()
            }
    }

    private fun isInside(candidate: File, root: File): Boolean = runCatching {
        candidate.canonicalFile.toPath().normalize()
            .startsWith(root.canonicalFile.toPath().normalize())
    }.getOrDefault(false)
}
