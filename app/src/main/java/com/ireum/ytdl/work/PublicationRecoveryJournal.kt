package com.ireum.ytdl.work

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ireum.ytdl.util.storage.TerminalCacheOwnership
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Durable, exact publication lineage for one Download/Terminal attempt.
 *
 * The journal is deliberately independent of the staging directory.  A
 * staging marker proves only the root; this carrier records each source to
 * the exact destination returned by FileUtil at the irreversible boundary.
 * It is retained until the semantic terminal result has been committed.
 */
internal object PublicationRecoveryJournal {
    private const val DIRECTORY_NAME = "publication-recovery"
    private const val FILE_PREFIX = "ytdlnisx-publication-"
    private const val SCHEMA_VERSION = 1
    private val gson = Gson()

    internal enum class Kind {
        DOWNLOAD,
        TERMINAL,
    }

    internal enum class Phase {
        PREPARED,
        PUBLISHING,
        PARTIAL,
        COMPLETE,
        /** Publication finished; Terminal semantic row finalization is pending. */
        COMMITTING,
        /** Terminal semantic result was durably committed. */
        COMMITTED,
        /** Exact remainder was durably quarantined after a failed attempt. */
        QUARANTINED,
        /**
         * Provider creation crossed an opaque external boundary but did not
         * return an exact destination.  This is a terminal quarantine state:
         * it fences provider replay without pretending that an object was
         * created or that it can be recovered by name/directory scans.
         */
        QUARANTINED_UNKNOWN,
    }

    internal data class Artifact(
        val sourcePath: String,
        val destinationPath: String? = null,
        /** Exact destination reserved before an irreversible move starts. */
        val reservedDestinationPath: String? = null,
    )

    internal data class Record(
        val version: Int = SCHEMA_VERSION,
        val kind: Kind,
        val subjectId: String,
        val operationId: String,
        val executionId: String,
        val attemptId: String,
        val sourceRoot: String,
        val artifacts: List<Artifact>,
        val phase: Phase = Phase.PREPARED,
    ) {
        fun publishedDestinations(): List<String> = artifacts
            .mapNotNull { it.destinationPath?.takeIf(String::isNotBlank) }
            .distinct()

        fun remainingSources(): List<String> = artifacts
            .filter { it.destinationPath.isNullOrBlank() }
            .map(Artifact::sourcePath)
            .distinct()

        fun reservedDestinations(): List<String> = artifacts
            .filter { it.destinationPath.isNullOrBlank() }
            .mapNotNull { it.reservedDestinationPath?.takeIf(String::isNotBlank) }
            .distinct()
    }

    /**
     * Discovery is deliberately richer than a List.  A missing namespace is
     * a healthy empty state, while an unreadable directory or malformed
     * journal is durable evidence that must fence admission.
     */
    internal sealed interface DiscoveryResult {
        val records: List<Record>

        data class Healthy(override val records: List<Record>) : DiscoveryResult

        data class Unavailable(
            val reason: String,
        ) : DiscoveryResult {
            override val records: List<Record> = emptyList()
        }

        data class Opaque(
            override val records: List<Record>,
            val opaqueFiles: List<String>,
        ) : DiscoveryResult
    }

    /** A synchronized handle used by the move callback at the exact boundary. */
    internal class Handle internal constructor(
        private val file: File,
        private var record: Record,
    ) {
        @Synchronized
        fun snapshot(): Record = record

        /**
         * Persist the exact destination before the move/copy boundary.  If
         * the process dies after the destination is created but before the
         * post-move callback runs, recovery can still reconcile this pair.
         */
        @Synchronized
        fun reserve(sourcePath: String, destinationPath: String): Boolean {
            val source = normalizeSource(sourcePath) ?: return false
            val destination = normalizeDestination(destinationPath) ?: return false
            val index = record.artifacts.indexOfFirst {
                normalizeSource(it.sourcePath) == source
            }
            if (index < 0) return false
            val existing = record.artifacts[index]
            if (!existing.destinationPath.isNullOrBlank()) {
                return normalizeDestination(existing.destinationPath) == destination
            }
            if (!existing.reservedDestinationPath.isNullOrBlank() &&
                normalizeDestination(existing.reservedDestinationPath) != destination &&
                !isReservationIntent(existing.reservedDestinationPath)
            ) return false
            val updated = record.artifacts.toMutableList()
            updated[index] = existing.copy(
                sourcePath = source,
                reservedDestinationPath = destination,
            )
            val next = record.copy(
                artifacts = updated,
                phase = Phase.PUBLISHING,
            )
            if (!persist(file, next)) return false
            record = next
            return true
        }

        /**
         * Persist an operation-bound provider creation intent before calling
         * createDocument()/insert().  The intent is deliberately not a
         * destination path and can never grant output authority; it only
         * prevents a retry from racing an exact provider URI that may have
         * been created before the process died.  The subsequent exact
         * reserve() call replaces it with the URI returned by the provider.
         */
        @Synchronized
        fun reserveIntent(sourcePath: String): Boolean {
            val source = normalizeSource(sourcePath) ?: return false
            val index = record.artifacts.indexOfFirst {
                normalizeSource(it.sourcePath) == source
            }
            if (index < 0) return false
            val existing = record.artifacts[index]
            if (!existing.destinationPath.isNullOrBlank()) return true
            // An existing reservation means that a previous invocation has
            // already crossed (or may have crossed) the external provider
            // creation boundary. Replaying the provider call could create a
            // collision-suffixed duplicate, so only an exact destination
            // reservation or explicit recovery reconciliation may advance it.
            if (!existing.reservedDestinationPath.isNullOrBlank()) return false
            val updated = record.artifacts.toMutableList()
            updated[index] = existing.copy(
                sourcePath = source,
                reservedDestinationPath = buildReservationIntent(source),
            )
            val next = record.copy(
                artifacts = updated,
                phase = Phase.PUBLISHING,
            )
            if (!persist(file, next)) return false
            record = next
            return true
        }

        /**
         * Drop a reservation only after the caller has proved that its exact
         * destination does not exist.  This is needed when a provider/raw
         * move fails after reservation but before creating the destination;
         * a later retry may legitimately receive a different URI/path.
         */
        @Synchronized
        fun clearReservation(sourcePath: String): Boolean {
            val source = normalizeSource(sourcePath) ?: return false
            val index = record.artifacts.indexOfFirst {
                normalizeSource(it.sourcePath) == source
            }
            if (index < 0) return false
            val existing = record.artifacts[index]
            if (!existing.destinationPath.isNullOrBlank()) return false
            if (existing.reservedDestinationPath.isNullOrBlank()) return true
            if (isUnknownReservation(existing.reservedDestinationPath)) return false
            val updated = record.artifacts.toMutableList()
            updated[index] = existing.copy(reservedDestinationPath = null)
            val next = record.copy(
                artifacts = updated,
                phase = Phase.PUBLISHING,
            )
            if (!persist(file, next)) return false
            record = next
            return true
        }

        @Synchronized
        fun markPublished(sourcePath: String, destinationPath: String): Boolean {
            val source = normalizeSource(sourcePath) ?: return false
            val destination = normalizeDestination(destinationPath) ?: return false
            val index = record.artifacts.indexOfFirst {
                normalizeSource(it.sourcePath) == source
            }
            if (index < 0) return false
            val existing = record.artifacts[index]
            if (!existing.destinationPath.isNullOrBlank() &&
                normalizeDestination(existing.destinationPath) != destination
            ) {
                return false
            }
            if (isUnknownReservation(existing.reservedDestinationPath)) return false
            val updated = record.artifacts.toMutableList()
            if (!existing.reservedDestinationPath.isNullOrBlank() &&
                !isReservationIntent(existing.reservedDestinationPath) &&
                normalizeDestination(existing.reservedDestinationPath) != destination
            ) return false
            updated[index] = Artifact(source, destination, reservedDestinationPath = null)
            val next = record.copy(
                artifacts = updated,
                phase = if (updated.all { !it.destinationPath.isNullOrBlank() }) {
                    Phase.COMPLETE
                } else {
                    Phase.PARTIAL
                },
            )
            if (!persist(file, next)) return false
            record = next
            return true
        }

        /**
         * Persist the third provider-creation outcome: the external call
         * completed ambiguously (for example a RemoteException or null
         * result after the provider may already have mutated state). This is
         * deliberately not a destination and must never be cleared or
         * replayed as a fresh provider operation without exact reconciliation.
         */
        @Synchronized
        fun markReservationUnknown(sourcePath: String): Boolean {
            val source = normalizeSource(sourcePath) ?: return false
            val index = record.artifacts.indexOfFirst {
                normalizeSource(it.sourcePath) == source
            }
            if (index < 0) return false
            val existing = record.artifacts[index]
            if (!existing.destinationPath.isNullOrBlank()) return true
            if (isUnknownReservation(existing.reservedDestinationPath)) return true
            if (!isReservationIntent(existing.reservedDestinationPath)) return false
            val updated = record.artifacts.toMutableList()
            updated[index] = existing.copy(
                sourcePath = source,
                reservedDestinationPath = buildUnknownReservation(source),
            )
            val next = record.copy(
                artifacts = updated,
                phase = Phase.PARTIAL,
            )
            if (!persist(file, next)) return false
            record = next
            return true
        }

        /**
         * Convert an ambiguous provider reservation into a durable terminal
         * outcome. A pending intent is included: after a process dies between
         * intent persistence and the provider call, this architecture cannot
         * prove that the call was never crossed. Treating it conservatively as
         * UNKNOWN prevents replay while the sentinel remains evidence and
         * continues to reject clear/reserve/publish operations.
         */
        @Synchronized
        fun terminalizeUnknownReservation(): Boolean {
            if (record.phase == Phase.QUARANTINED_UNKNOWN) return true
            if (!record.artifacts.any {
                    isUnknownReservation(it.reservedDestinationPath) ||
                        isReservationIntent(it.reservedDestinationPath)
                }) {
                return false
            }
            val next = record.copy(
                artifacts = record.artifacts.map { artifact ->
                    if (isReservationIntent(artifact.reservedDestinationPath)) {
                        artifact.copy(
                            reservedDestinationPath = buildUnknownReservation(artifact.sourcePath),
                        )
                    } else {
                        artifact
                    }
                },
                phase = Phase.QUARANTINED_UNKNOWN,
            )
            if (!persist(file, next)) return false
            record = next
            return true
        }

        /** Persist a phase transition without retiring exact lineage. */
        @Synchronized
        fun markPhase(phase: Phase): Boolean {
            if (record.phase == Phase.QUARANTINED_UNKNOWN && phase != Phase.QUARANTINED_UNKNOWN) {
                // Once opaque provider completion has been terminalized, no
                // generic phase transition may reopen or retire the fence.
                return false
            }
            if (
                phase == Phase.QUARANTINED_UNKNOWN &&
                    !record.artifacts.any { isUnknownReservation(it.reservedDestinationPath) }
            ) {
                return false
            }
            if (
                phase in setOf(Phase.COMPLETE, Phase.COMMITTING, Phase.COMMITTED) &&
                record.artifacts.any { it.destinationPath.isNullOrBlank() }
            ) {
                return false
            }
            val next = record.copy(phase = phase)
            if (!persist(file, next)) return false
            record = next
            return true
        }

        /** Retire only after the caller has committed the semantic result. */
        @Synchronized
        fun clear(): Boolean {
            val canRetire = when (record.kind) {
                Kind.DOWNLOAD -> record.phase == Phase.COMPLETE
                Kind.TERMINAL -> when (record.phase) {
                    Phase.COMMITTED,
                    Phase.QUARANTINED -> true
                    Phase.QUARANTINED_UNKNOWN -> {
                        // UNKNOWN may be retired only after Terminal
                        // recovery has durably installed and validated its
                        // marker-revoked quarantine carrier.  The journal is
                        // otherwise the sole evidence and must remain.
                        val root = runCatching { File(record.sourceRoot).canonicalFile }
                            .getOrNull()
                        root != null &&
                            !TerminalCacheOwnership.markerFile(root).isFile &&
                            TerminalCacheOwnership.isValidRecoveryCarrier(
                                root,
                                record.executionId,
                                requiredPhase = Phase.QUARANTINED_UNKNOWN.name,
                                requiredSubjectId = record.subjectId,
                            )
                    }
                    else -> false
                }
            }
            if (!canRetire) return false
            if (!file.exists()) return true
            return file.delete() || !file.exists()
        }
    }

    internal fun begin(
        context: Context,
        kind: Kind,
        subjectId: String,
        operationId: String,
        executionId: String,
        attemptId: String,
        sourceRoot: File,
        sourceFiles: Iterable<File>,
    ): Handle? = begin(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        kind = kind,
        subjectId = subjectId,
        operationId = operationId,
        executionId = executionId,
        attemptId = attemptId,
        sourceRoot = sourceRoot,
        sourceFiles = sourceFiles,
    )

    /** JVM-testable entry point; the directory is app-private in production. */
    internal fun begin(
        storageDirectory: File,
        kind: Kind,
        subjectId: String,
        operationId: String,
        executionId: String,
        attemptId: String,
        sourceRoot: File,
        sourceFiles: Iterable<File>,
    ): Handle? {
        if (
            subjectId.isBlank() || operationId.isBlank() || executionId.isBlank() ||
            attemptId.isBlank()
        ) return null
        val root = runCatching { sourceRoot.canonicalFile }.getOrNull() ?: return null
        val sources = sourceFiles.mapNotNull { source ->
            runCatching {
                val canonical = source.canonicalFile
                canonical.takeIf {
                    it.isFile && isInside(it, root)
                }?.absolutePath
            }.getOrNull()
        }.distinct()
        if (sources.isEmpty()) return null
        if (!storageDirectory.exists() && !storageDirectory.mkdirs()) return null
        if (!storageDirectory.isDirectory) return null
        val file = journalFile(
            storageDirectory,
            kind,
            subjectId,
            operationId,
            executionId,
            attemptId,
        )
        val existing = read(file)
        if (file.exists() && existing == null) {
            // Never overwrite an unreadable/incomplete recovery carrier.  A
            // malformed durable record is not authority, but it is still
            // evidence that must remain available for diagnostics/recovery.
            return null
        }
        if (existing != null) {
            if (
                existing.version != SCHEMA_VERSION || existing.kind != kind ||
                existing.subjectId != subjectId || existing.operationId != operationId ||
                existing.executionId != executionId || existing.attemptId != attemptId ||
                existing.sourceRoot != root.absolutePath
            ) return null
            return Handle(file, existing)
        }
        val record = Record(
            kind = kind,
            subjectId = subjectId,
            operationId = operationId,
            executionId = executionId,
            attemptId = attemptId,
            sourceRoot = root.absolutePath,
            artifacts = sources.map(::Artifact),
            phase = Phase.PREPARED,
        )
        return if (persist(file, record)) Handle(file, record) else null
    }

    internal fun open(context: Context, record: Record): Handle? =
        open(File(context.filesDir, DIRECTORY_NAME), record)

    internal fun open(storageDirectory: File, record: Record): Handle? {
        val file = journalFile(
            storageDirectory,
            record.kind,
            record.subjectId,
            record.operationId,
            record.executionId,
            record.attemptId,
        )
        return read(file)?.takeIf { it == record }?.let { Handle(file, it) }
    }

    internal fun readAll(storageDirectory: File): List<Record> {
        // Legacy callers retain the old projection; correctness-sensitive
        // consumers use discover() so unavailable/opaque state is not
        // reinterpreted as an empty namespace.
        return discover(storageDirectory).records
    }

    internal fun discover(storageDirectory: File): DiscoveryResult {
        if (!storageDirectory.exists()) return DiscoveryResult.Healthy(emptyList())
        if (!storageDirectory.isDirectory) {
            return DiscoveryResult.Unavailable("publication namespace is not a directory")
        }
        val files = try {
            storageDirectory.listFiles()
                ?: return DiscoveryResult.Unavailable("publication namespace listing failed")
        } catch (error: SecurityException) {
            return DiscoveryResult.Unavailable(
                "publication namespace listing denied: ${error::class.java.simpleName}",
            )
        }
        val records = mutableListOf<Record>()
        val opaque = mutableListOf<String>()
        files.asSequence()
            .filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.extension == "json" }
            .forEach { file ->
                val record = read(file)
                if (record == null) opaque += file.absolutePath else records += record
            }
        return if (opaque.isEmpty()) {
            DiscoveryResult.Healthy(records)
        } else {
            DiscoveryResult.Opaque(records, opaque.distinct())
        }
    }

    internal fun readAll(context: Context): List<Record> =
        readAll(File(context.filesDir, DIRECTORY_NAME))

    internal fun discover(context: Context): DiscoveryResult =
        discover(File(context.filesDir, DIRECTORY_NAME))

    internal fun reservationIntentForSource(sourcePath: String): String? {
        val source = normalizeSource(sourcePath) ?: return null
        return buildReservationIntent(source)
    }

    internal fun findDownload(
        context: Context,
        downloadId: Long,
        operationId: String,
    ): List<Record> = readAll(context).filter {
        it.kind == Kind.DOWNLOAD && it.subjectId == downloadId.toString() &&
            it.operationId == operationId
    }

    internal fun findDownloadDiscovery(
        context: Context,
        downloadId: Long,
        operationId: String,
    ): DiscoveryResult = when (val discovery = discover(context)) {
        is DiscoveryResult.Healthy -> DiscoveryResult.Healthy(
            discovery.records.filter {
                it.kind == Kind.DOWNLOAD && it.subjectId == downloadId.toString() &&
                    it.operationId == operationId
            },
        )
        is DiscoveryResult.Unavailable -> discovery
        is DiscoveryResult.Opaque -> DiscoveryResult.Opaque(
            records = discovery.records.filter {
                it.kind == Kind.DOWNLOAD && it.subjectId == downloadId.toString() &&
                    it.operationId == operationId
            },
            opaqueFiles = discovery.opaqueFiles,
        )
    }

    private fun read(file: File): Record? = runCatching {
        if (!file.isFile) return null
        val json = JsonParser.parseString(file.readText()).asJsonObject
        // Reject incomplete records before Gson materializes nullable missing
        // fields. A malformed carrier is not an authority proof.
        if (
            !json.has("version") || !json.has("kind") || !json.has("subjectId") ||
            !json.has("operationId") || !json.has("executionId") ||
            !json.has("attemptId") || !json.has("sourceRoot") ||
            !json.has("artifacts") || !json.has("phase")
        ) return null
        if (!json.get("artifacts").isJsonArray || json.getAsJsonArray("artifacts").size() == 0) {
            return null
        }
        if (json.getAsJsonArray("artifacts").any { value ->
                !value.isJsonObject ||
                    !value.asJsonObject.has("sourcePath") ||
                    !value.asJsonObject.get("sourcePath").isJsonPrimitive ||
                    !value.asJsonObject.getAsJsonPrimitive("sourcePath").isString ||
                    (value.asJsonObject.has("destinationPath") &&
                        !value.asJsonObject.get("destinationPath").isJsonNull &&
                        (!value.asJsonObject.get("destinationPath").isJsonPrimitive ||
                            !value.asJsonObject.getAsJsonPrimitive("destinationPath").isString)) ||
                    (value.asJsonObject.has("reservedDestinationPath") &&
                        !value.asJsonObject.get("reservedDestinationPath").isJsonNull &&
                        (!value.asJsonObject.get("reservedDestinationPath").isJsonPrimitive ||
                            !value.asJsonObject.getAsJsonPrimitive("reservedDestinationPath").isString))
            }
        ) return null
        gson.fromJson(json, Record::class.java)
    }.getOrNull()?.takeIf { record ->
        runCatching {
            record.version == SCHEMA_VERSION &&
                record.subjectId.isNotBlank() &&
                record.operationId.isNotBlank() && record.executionId.isNotBlank() &&
                record.attemptId.isNotBlank() && record.sourceRoot.isNotBlank() &&
            record.artifacts.isNotEmpty() && record.artifacts.all { artifact ->
                    artifact.sourcePath.isNotBlank() &&
                        (artifact.destinationPath == null || artifact.destinationPath.isNotBlank()) &&
                        (artifact.reservedDestinationPath == null || artifact.reservedDestinationPath.isNotBlank()) &&
                        (artifact.destinationPath.isNullOrBlank() || artifact.reservedDestinationPath.isNullOrBlank())
                }
        }.getOrDefault(false)
    }

    private fun persist(file: File, record: Record): Boolean = runCatching {
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(gson.toJson(record).toByteArray(Charsets.UTF_8))
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
            // Keep the previous durable record in place until the replacement
            // move succeeds.  Deleting it first would create a crash window
            // in which an irreversible publication has no recovery carrier.
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        file.isFile
    }.getOrDefault(false)

    private fun normalizeSource(path: String): String? = runCatching {
        File(path).canonicalFile.absolutePath
    }.getOrNull()

    private fun normalizeDestination(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith("content://", ignoreCase = true)) {
            trimmed
        } else {
            runCatching { File(trimmed).canonicalFile.absolutePath }.getOrNull()
        }
    }

    private fun buildReservationIntent(sourcePath: String): String =
        "$RESERVATION_INTENT_PREFIX${digest(sourcePath)}"

    private fun buildUnknownReservation(sourcePath: String): String =
        "$UNKNOWN_RESERVATION_PREFIX${digest(sourcePath)}"

    internal fun isReservationIntent(path: String?): Boolean =
        path?.startsWith(RESERVATION_INTENT_PREFIX) == true

    internal fun isUnknownReservation(path: String?): Boolean =
        path?.startsWith(UNKNOWN_RESERVATION_PREFIX) == true

    internal fun isUnknownTerminal(record: Record): Boolean =
        record.phase == Phase.QUARANTINED_UNKNOWN

    private fun isInside(candidate: File, root: File): Boolean = runCatching {
        candidate.canonicalFile.toPath().normalize()
            .startsWith(root.canonicalFile.toPath().normalize())
    }.getOrDefault(false)

    private fun digest(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun journalFile(
        storageDirectory: File,
        kind: Kind,
        subjectId: String,
        operationId: String,
        executionId: String,
        attemptId: String,
    ): File = File(
        storageDirectory,
        FILE_PREFIX + digest(
            listOf(kind.name, subjectId, operationId, executionId, attemptId)
                .joinToString("\u0000")
        ) + ".json",
    )

    private const val RESERVATION_INTENT_PREFIX = "pending://ytdlnisx/"
    private const val UNKNOWN_RESERVATION_PREFIX = "unknown://ytdlnisx/"
}

/**
 * Raised only when an exact provider destination was never obtained and the
 * durable journal has fenced the operation from replay.  It is deliberately
 * distinct from retryable I/O so Download can persist a terminal diagnostic.
 */
internal class UnknownProviderPublicationException(
    message: String = "Provider publication outcome is unknown and has been quarantined",
) : java.io.IOException(message)
