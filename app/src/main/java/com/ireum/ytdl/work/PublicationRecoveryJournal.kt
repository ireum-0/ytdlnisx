package com.ireum.ytdl.work

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
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
                normalizeDestination(existing.reservedDestinationPath) != destination
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
            val updated = record.artifacts.toMutableList()
            if (!existing.reservedDestinationPath.isNullOrBlank() &&
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

        /** Persist a phase transition without retiring exact lineage. */
        @Synchronized
        fun markPhase(phase: Phase): Boolean {
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
                Kind.TERMINAL -> record.phase == Phase.COMMITTED || record.phase == Phase.QUARANTINED
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
        if (!storageDirectory.isDirectory) return emptyList()
        return storageDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.extension == "json" }
            .mapNotNull(::read)
            .filter { record -> runCatching {
                record.version == SCHEMA_VERSION &&
                    record.subjectId.isNotBlank() && record.operationId.isNotBlank() &&
                    record.executionId.isNotBlank() && record.attemptId.isNotBlank() &&
                    record.sourceRoot.isNotBlank() && record.artifacts.isNotEmpty() &&
                    record.artifacts.all { artifact ->
                        artifact.sourcePath.isNotBlank() &&
                            (artifact.destinationPath == null || artifact.destinationPath.isNotBlank()) &&
                            (artifact.reservedDestinationPath == null || artifact.reservedDestinationPath.isNotBlank()) &&
                            (artifact.destinationPath.isNullOrBlank() || artifact.reservedDestinationPath.isNullOrBlank())
                    }
            }.getOrDefault(false)
            }
            .toList()
    }

    internal fun readAll(context: Context): List<Record> =
        readAll(File(context.filesDir, DIRECTORY_NAME))

    internal fun findDownload(
        context: Context,
        downloadId: Long,
        operationId: String,
    ): List<Record> = readAll(context).filter {
        it.kind == Kind.DOWNLOAD && it.subjectId == downloadId.toString() &&
            it.operationId == operationId
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
}
