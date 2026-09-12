package com.ireum.ytdl.work

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ireum.ytdl.util.storage.DownloadCacheOwnership
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Durable producer-finality authority that exists before yt-dlp is allowed
 * to run.  It covers the interval in which output may exist but publication
 * has not yet created its own journal, including successful no-output runs.
 */
internal object DownloadProducerRecovery {
    private const val DIRECTORY_NAME = "download-producer-recovery"
    private const val FILE_PREFIX = "ytdlnisx-producer-"
    private const val SCHEMA_VERSION = 1
    private val lock = Any()
    private val gson = Gson()

    internal enum class Phase {
        PREPARED,
        RUNNING,
        OUTPUT_UNPROVEN,
        COMPLETE,
        NO_OUTPUT_COMPLETE,
        SUPERSEDED,
        FINALIZED,
    }

    internal data class Record(
        val version: Int = SCHEMA_VERSION,
        val downloadId: Long,
        val operationId: String,
        val executionId: String,
        val generationId: String,
        val sequence: Long,
        val semanticFingerprint: String,
        val outputRoot: String,
        val outputPaths: List<String> = emptyList(),
        val archiveDelta: String = "",
        val phase: Phase = Phase.PREPARED,
        val predecessorGenerationId: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        /**
         * A compatible successor may adopt a predecessor's exact staging
         * root. Keep the marker owner separate from the successor execution
         * identity so later cleanup cannot authenticate the root as E2.
         * Legacy records leave these nullable and resolve to their own ids.
         */
        val outputOwnerOperationId: String? = null,
        val outputOwnerExecutionId: String? = null,
    ) {
        val effectiveOutputOwnerOperationId: String
            get() = outputOwnerOperationId?.takeIf(String::isNotBlank) ?: operationId
        val effectiveOutputOwnerExecutionId: String
            get() = outputOwnerExecutionId?.takeIf(String::isNotBlank) ?: executionId
    }

    internal sealed interface DiscoveryResult {
        val records: List<Record>

        data class Healthy(override val records: List<Record>) : DiscoveryResult
        data class Unavailable(val reason: String) : DiscoveryResult {
            override val records: List<Record> = emptyList()
        }
        data class Opaque(
            override val records: List<Record>,
            val opaqueFiles: List<String>,
        ) : DiscoveryResult
    }

    internal sealed interface Resolution {
        data object NoPrior : Resolution
        data class Compatible(
            val record: Record,
            /** Older records are explicit supersession debt, not output authority. */
            val obsoleteRecords: List<Record> = emptyList(),
        ) : Resolution
        data class Incompatible(
            val record: Record,
            /** Older records are explicit supersession debt, not output authority. */
            val obsoleteRecords: List<Record> = emptyList(),
        ) : Resolution
        data class Ambiguous(val records: List<Record>) : Resolution
        data class Unavailable(val reason: String) : Resolution
        data class Opaque(val files: List<String>) : Resolution
    }

    fun prepare(
        context: Context,
        downloadId: Long,
        operationId: String,
        executionId: String,
        semanticFingerprint: String,
        outputRoot: File,
        predecessorGenerationId: String? = null,
        outputOwnerOperationId: String = operationId,
        outputOwnerExecutionId: String = executionId,
    ): Record? = prepare(
        storageDirectory = storageDirectory(context),
        downloadId = downloadId,
        operationId = operationId,
        executionId = executionId,
        semanticFingerprint = semanticFingerprint,
        outputRoot = outputRoot,
        predecessorGenerationId = predecessorGenerationId,
        outputOwnerOperationId = outputOwnerOperationId,
        outputOwnerExecutionId = outputOwnerExecutionId,
    )

    /** JVM-testable producer authority entry point. */
    internal fun prepare(
        storageDirectory: File,
        downloadId: Long,
        operationId: String,
        executionId: String,
        semanticFingerprint: String,
        outputRoot: File,
        predecessorGenerationId: String? = null,
        outputOwnerOperationId: String = operationId,
        outputOwnerExecutionId: String = executionId,
    ): Record? = synchronized(lock) {
        if (
            downloadId <= 0L || operationId.isBlank() || executionId.isBlank() ||
            semanticFingerprint.isBlank() || outputOwnerOperationId.isBlank() ||
            outputOwnerExecutionId.isBlank()
        ) return@synchronized null
        val root = runCatching { outputRoot.canonicalFile }.getOrNull() ?: return@synchronized null
        // Use one fail-closed namespace observation for both idempotence and
        // generation sequencing.  A second listing can fail or change after
        // the first read; consulting its default ``records`` projection would
        // turn an unavailable/opaque namespace into affirmative clean state.
        val discovery = discover(storageDirectory)
        val records = when (discovery) {
            is DiscoveryResult.Healthy -> discovery.records
            is DiscoveryResult.Unavailable,
            is DiscoveryResult.Opaque -> return@synchronized null
        }
        val existing = records.firstOrNull {
            it.downloadId == downloadId && it.executionId == executionId &&
                it.phase != Phase.FINALIZED && it.phase != Phase.SUPERSEDED
        }
        if (existing != null) {
            return@synchronized existing.takeIf {
                it.operationId == operationId &&
                    it.semanticFingerprint == semanticFingerprint &&
                    it.outputRoot == root.absolutePath
            }
        }
        val sequence = records
            .filter { it.downloadId == downloadId }
            .maxOfOrNull(Record::sequence)
            ?.plus(1L)
            ?: 1L
        val record = Record(
            downloadId = downloadId,
            operationId = operationId,
            executionId = executionId,
            generationId = UUID.randomUUID().toString(),
            sequence = sequence,
            semanticFingerprint = semanticFingerprint,
            outputRoot = root.absolutePath,
            predecessorGenerationId = predecessorGenerationId,
            outputOwnerOperationId = outputOwnerOperationId,
            outputOwnerExecutionId = outputOwnerExecutionId,
        )
        val file = fileFor(storageDirectory, record)
        persist(file, record).takeIf { it }?.let { record }
    }

    internal fun markRunning(
        context: Context,
        record: Record,
    ): Boolean = markRunning(storageDirectory(context), record)

    internal fun markRunning(storageDirectory: File, record: Record): Boolean =
        transition(storageDirectory, record) { current ->
            if (current.phase == Phase.RUNNING) current
            else if (current.phase == Phase.PREPARED) current.copy(phase = Phase.RUNNING)
            else null
        }

    internal fun markComplete(
        context: Context,
        record: Record,
        outputPaths: Iterable<String>,
        archiveDelta: String = "",
    ): Record? = markComplete(storageDirectory(context), record, outputPaths, archiveDelta)

    internal fun markComplete(
        storageDirectory: File,
        record: Record,
        outputPaths: Iterable<String>,
        archiveDelta: String = "",
    ): Record? {
        val rawPaths = outputPaths.toList()
        val normalized = rawPaths.mapNotNull { raw ->
            runCatching { File(raw).canonicalFile }.getOrNull()?.takeIf { file ->
                file.absolutePath == record.outputRoot ||
                    file.toPath().startsWith(File(record.outputRoot).toPath())
            }?.absolutePath
        }.distinct()
        if (normalized.size != rawPaths.map { it.trim() }.filter(String::isNotBlank).distinct().size) {
            return null
        }
        return transition(storageDirectory, record) { current ->
            if (current.phase != Phase.RUNNING && current.phase != Phase.COMPLETE &&
                current.phase != Phase.NO_OUTPUT_COMPLETE
            ) return@transition null
            current.copy(
                outputPaths = normalized,
                archiveDelta = archiveDelta,
                phase = if (normalized.isEmpty()) Phase.NO_OUTPUT_COMPLETE else Phase.COMPLETE,
            )
        }.let { if (it) readExact(storageDirectory, record)?.takeIf { next -> next.phase == Phase.COMPLETE || next.phase == Phase.NO_OUTPUT_COMPLETE } else null }
    }

    internal fun markOutputUnproven(
        context: Context,
        record: Record,
    ): Boolean = transition(storageDirectory(context), record) { current ->
        if (current.phase == Phase.RUNNING || current.phase == Phase.OUTPUT_UNPROVEN) {
            current.copy(phase = Phase.OUTPUT_UNPROVEN)
        } else current
    }

    internal fun markSuperseded(context: Context, record: Record): Boolean =
        markSuperseded(storageDirectory(context), record)

    internal fun markSuperseded(storageDirectory: File, record: Record): Boolean =
        transition(storageDirectory, record) { current ->
            when (current.phase) {
                Phase.FINALIZED,
                Phase.SUPERSEDED -> current
                else -> current.copy(phase = Phase.SUPERSEDED)
            }
        }

    internal fun resolve(
        context: Context,
        downloadId: Long,
        currentFingerprint: String,
        currentExecutionId: String,
    ): Resolution = resolve(storageDirectory(context), downloadId, currentFingerprint, currentExecutionId)

    internal fun resolve(
        storageDirectory: File,
        downloadId: Long,
        currentFingerprint: String,
        currentExecutionId: String,
    ): Resolution {
        val discovery = discover(storageDirectory)
        val candidates = when (discovery) {
            is DiscoveryResult.Healthy -> discovery.records
            is DiscoveryResult.Unavailable -> return Resolution.Unavailable(discovery.reason)
            is DiscoveryResult.Opaque -> return Resolution.Opaque(discovery.opaqueFiles)
        }.filter {
            it.downloadId == downloadId &&
                it.executionId != currentExecutionId &&
                it.phase != Phase.FINALIZED &&
                it.phase != Phase.SUPERSEDED
        }
        if (candidates.isEmpty()) return Resolution.NoPrior
        val maxSequence = candidates.maxOf(Record::sequence)
        val latest = candidates.filter { it.sequence == maxSequence }
        // Sequence is the only persisted generation ordering. A duplicate
        // sequence is therefore not resolvable by filesystem enumeration or
        // UUID lexical order; retain all evidence and fail closed.
        if (latest.size != 1) return Resolution.Ambiguous(latest)
        val selected = latest.single()
        // A unique persisted sequence is the only generation ordering
        // authority. Older records are not merged into the selected result;
        // they are returned as explicit supersession debt so the caller can
        // retire them only after the selected successor is durable. Legacy
        // records that do not establish a unique sequence remain ambiguous
        // above rather than being ordered by filesystem enumeration or UUID.
        val obsoleteRecords = candidates
            .asSequence()
            .filter { it.generationId != selected.generationId }
            .sortedWith(
                compareByDescending<Record> { it.sequence }
                    .thenBy { it.generationId },
            )
            .toList()
        return if (
            selected.phase in setOf(Phase.COMPLETE, Phase.NO_OUTPUT_COMPLETE) &&
                selected.semanticFingerprint == currentFingerprint
        ) {
            Resolution.Compatible(selected, obsoleteRecords)
        } else {
            Resolution.Incompatible(selected, obsoleteRecords)
        }
    }

    internal fun discover(context: Context): DiscoveryResult = discover(storageDirectory(context))

    internal fun discover(storageDirectory: File): DiscoveryResult {
        if (!storageDirectory.exists()) return DiscoveryResult.Healthy(emptyList())
        if (!storageDirectory.isDirectory) return DiscoveryResult.Unavailable("producer namespace is not a directory")
        val files = try {
            storageDirectory.listFiles() ?: return DiscoveryResult.Unavailable("producer namespace listing failed")
        } catch (error: SecurityException) {
            return DiscoveryResult.Unavailable("producer namespace listing denied: ${error::class.java.simpleName}")
        }
        val records = mutableListOf<Record>()
        val opaque = mutableListOf<String>()
        files.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.extension == "json" }
            .forEach { file -> read(file)?.let(records::add) ?: opaque.add(file.absolutePath) }
        return if (opaque.isEmpty()) DiscoveryResult.Healthy(records) else DiscoveryResult.Opaque(records, opaque.distinct())
    }

    internal fun hasPending(context: Context): Boolean = when (val result = discover(context)) {
        is DiscoveryResult.Healthy -> result.records.any { it.phase != Phase.FINALIZED }
        is DiscoveryResult.Unavailable,
        is DiscoveryResult.Opaque -> true
    }

    internal fun hasPendingForDownload(context: Context, downloadId: Long): Boolean = when (
        val result = discover(context)
    ) {
        is DiscoveryResult.Healthy -> result.records.any {
            it.downloadId == downloadId && it.phase != Phase.FINALIZED
        }
        is DiscoveryResult.Unavailable,
        is DiscoveryResult.Opaque -> true
    }

    /**
     * A COMPLETE generation is an adoptable predecessor, not a reason to
     * refuse a queued successor forever.  All weaker or terminalization-
     * pending phases remain an admission fence until their exact recovery
     * owner converges them.
     */
    internal fun hasBlockingForAdmission(context: Context, downloadId: Long): Boolean =
        hasBlockingForAdmission(discover(context), downloadId)

    /** Package-private overload used by deterministic JVM admission tests. */
    internal fun hasBlockingForAdmissionForTests(
        storageDirectory: File,
        downloadId: Long,
    ): Boolean = hasBlockingForAdmission(discover(storageDirectory), downloadId)

    private fun hasBlockingForAdmission(
        discovery: DiscoveryResult,
        downloadId: Long,
    ): Boolean = when (discovery) {
        is DiscoveryResult.Healthy -> discovery.records.any {
            it.downloadId == downloadId &&
                it.phase != Phase.FINALIZED &&
                it.phase != Phase.COMPLETE
        }
        is DiscoveryResult.Unavailable,
        is DiscoveryResult.Opaque -> true
    }

    /** Package-private storage overload used by deterministic JVM recovery tests. */
    internal fun hasPendingForTests(storageDirectory: File): Boolean = when (val result = discover(storageDirectory)) {
        is DiscoveryResult.Healthy -> result.records.any { it.phase != Phase.FINALIZED }
        is DiscoveryResult.Unavailable,
        is DiscoveryResult.Opaque -> true
    }

    /**
     * Retire an unpublished producer generation only after its caller has
     * positively proven native quiescence.  This is deliberately limited to
     * exact paths recorded by the generation; unknown descendants keep the
     * durable record in place.  A successor worker can therefore make
     * progress without treating a stale producer record as a permanent
     * provider/publication authority.
     */
    internal fun retireUnpublishedAfterQuiescence(
        context: Context,
        record: Record,
        /**
         * Completed producer output is semantic finality, not disposable
         * staging.  Only an explicit successor/stronger-owner path may
         * authorize its retirement after that successor is durable.
         */
        allowCompletedFinality: Boolean = false,
    ): Boolean = synchronized(lock) {
        val current = readExact(storageDirectory(context), record) ?: return@synchronized false
        if (current.phase == Phase.FINALIZED) return@synchronized true
        if (
            !allowCompletedFinality &&
                current.phase in setOf(Phase.COMPLETE, Phase.NO_OUTPUT_COMPLETE)
        ) {
            return@synchronized false
        }
        if (current.phase == Phase.SUPERSEDED) {
            return@synchronized retireSupersededRecord(storageDirectory(context), current)
        }
        val root = runCatching { File(current.outputRoot).canonicalFile }.getOrNull()
            ?: return@synchronized false
        var exactCleanupFailed = false
        current.outputPaths.forEach { raw ->
            if (raw.startsWith("content://", ignoreCase = true)) return@synchronized false
            val path = runCatching { File(raw).canonicalFile }.getOrNull()
                ?: return@synchronized false
            if (!path.toPath().startsWith(root.toPath())) return@synchronized false
            if (path.exists() && (!path.isFile || !path.delete()) && path.exists()) {
                // Keep the exact root eligible for whole-root quarantine below;
                // recursive deletion is never used for an unproven child.
                exactCleanupFailed = true
            }
        }

        // Recovery must use the generation's persisted output root, not a
        // fresh mutable cache_path resolution.  A preference/writability
        // change after process death must never redirect cleanup to another
        // cache namespace.
        val cacheRoot = root.parentFile?.canonicalFile
        if (cacheRoot != null &&
            root.name == current.downloadId.toString()
        ) {
            val retired = !exactCleanupFailed && DownloadCacheOwnership.retireRecoveredExecution(
                    cacheRoot = cacheRoot,
                    downloadId = current.downloadId,
                    operationId = current.effectiveOutputOwnerOperationId,
                    executionId = current.effectiveOutputOwnerExecutionId,
                )
            if (!retired && !DownloadCacheOwnership.quarantineRecoveredExecution(
                    cacheRoot = cacheRoot,
                    downloadId = current.downloadId,
                    operationId = current.effectiveOutputOwnerOperationId,
                    executionId = current.effectiveOutputOwnerExecutionId,
                )
            ) return@synchronized false
        } else if (
            !retireDirectCarrier(
                root = root,
                expectedDownloadId = current.downloadId,
                expectedExecutionId = current.effectiveOutputOwnerExecutionId,
            )
        ) {
            if (!quarantineDirectCarrier(root, current.effectiveOutputOwnerExecutionId, current.downloadId)) {
                return@synchronized false
            }
        }

        val file = fileFor(storageDirectory(context), current)
        val superseded = current.copy(phase = Phase.SUPERSEDED)
        if (!persist(file, superseded)) return@synchronized false
        // Leave SUPERSEDED durable if deletion fails; hasPendingForDownload()
        // keeps the recovery owner alive for the next pass.
        file.delete() || !file.exists()
    }

    internal fun retire(context: Context, record: Record): Boolean =
        retire(storageDirectory(context), record)

    internal fun retire(storageDirectory: File, record: Record): Boolean = synchronized(lock) {
        val current = readExact(storageDirectory, record) ?: return@synchronized false
        if (current.phase != Phase.COMPLETE && current.phase != Phase.NO_OUTPUT_COMPLETE &&
            current.phase != Phase.SUPERSEDED && current.phase != Phase.FINALIZED
        ) return@synchronized false
        val finalized = current.copy(phase = Phase.FINALIZED)
        val file = fileFor(storageDirectory, current)
        if (!persist(file, finalized)) return@synchronized false
        !file.exists() || file.delete() || !file.exists()
    }

    internal fun retireForExecution(context: Context, downloadId: Long, executionId: String): Boolean {
        val records = when (val discovery = discover(context)) {
            is DiscoveryResult.Healthy -> discovery.records
            else -> return false
        }.filter { it.downloadId == downloadId && it.executionId == executionId }
        return records.all { retire(context, it) }
    }

    private fun storageDirectory(context: Context): File = File(context.filesDir, DIRECTORY_NAME)

    private fun fileFor(storageDirectory: File, record: Record): File =
        File(storageDirectory, "$FILE_PREFIX${record.downloadId}-${record.generationId}.json")

    private fun transition(
        storageDirectory: File,
        record: Record,
        update: (Record) -> Record?,
    ): Boolean = synchronized(lock) {
        val current = readExact(storageDirectory, record) ?: return@synchronized false
        val next = update(current) ?: return@synchronized false
        persist(fileFor(storageDirectory, current), next)
    }

    private fun readExact(storageDirectory: File, record: Record): Record? =
        read(fileFor(storageDirectory, record))?.takeIf {
            it.version == record.version &&
                it.downloadId == record.downloadId &&
                it.executionId == record.executionId &&
                it.generationId == record.generationId
        }

    private fun read(file: File): Record? = try {
        if (!file.isFile) return null
        val json = JsonParser.parseString(file.readText()).asJsonObject
        val required = listOf(
            "version", "downloadId", "operationId", "executionId", "generationId",
            "sequence", "semanticFingerprint", "outputRoot", "outputPaths", "archiveDelta", "phase",
        )
        if (required.any { !json.has(it) }) return null
        val record = gson.fromJson(json, Record::class.java)
        if (
            record.version != SCHEMA_VERSION || record.downloadId <= 0L ||
            record.operationId.isBlank() || record.executionId.isBlank() ||
            record.generationId.isBlank() || record.sequence <= 0L ||
            record.semanticFingerprint.isBlank() || record.outputRoot.isBlank() ||
            record.outputPaths.any { it.isBlank() || !isInside(it, record.outputRoot) } ||
            record.outputOwnerOperationId?.let(String::isBlank) == true ||
            record.outputOwnerExecutionId?.let(String::isBlank) == true
        ) null else record
    } catch (_: Exception) {
        null
    }

    private fun isInside(path: String, root: String): Boolean = runCatching {
        File(path).canonicalFile.toPath().startsWith(File(root).canonicalFile.toPath())
    }.getOrDefault(false)

    private fun persist(file: File, record: Record): Boolean {
        return try {
            val parent = file.parentFile ?: return false
            if (!parent.exists() && !parent.mkdirs()) return false
            val temporary = File(parent, ".${file.name}.tmp-${UUID.randomUUID()}")
            FileOutputStream(temporary).use { output ->
                output.write(gson.toJson(record).toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            file.isFile
        } catch (_: Exception) {
            false
        }
    }

    private fun retireSupersededRecord(storageDirectory: File, record: Record): Boolean {
        val file = fileFor(storageDirectory, record)
        if (!file.exists()) return true
        return file.delete() || !file.exists()
    }

    /** Remove only the exact direct-output carrier after its children are gone. */
    private fun retireDirectCarrier(
        root: File,
        expectedDownloadId: Long,
        expectedExecutionId: String,
    ): Boolean {
        if (!root.exists()) return true
        if (!root.isDirectory) return false
        val marker = File(root, ".ytdlnisx-owner").canonicalFile
        val manifest = File(root, ".ytdlnisx-output-artifacts.txt").canonicalFile
        if (marker.exists()) {
            val text = runCatching { marker.readText() }.getOrNull() ?: return false
            val fields = text.lineSequence().mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()
            if (
                fields["downloadId"]?.toLongOrNull() != expectedDownloadId ||
                fields["executionId"] != expectedExecutionId
            ) return false
        }
        val children = root.listFiles()?.toList() ?: return false
        if (children.any { child ->
                val canonical = runCatching { child.canonicalFile }.getOrNull()
                canonical != marker && canonical != manifest
            }
        ) return false
        if (manifest.exists() && !manifest.delete() && manifest.exists()) return false
        if (marker.exists() && !marker.delete() && marker.exists()) return false
        return !root.exists() || root.delete() || !root.exists()
    }

    /**
     * Preserve an exact direct staging root as an identity-bound quarantined
     * directory when unknown descendants prevent safe recursive cleanup.
     * Moving the whole root keeps every unproven file confined while allowing
     * a superseding generation to obtain a fresh staging token.
     */
    private fun quarantineDirectCarrier(
        root: File,
        expectedExecutionId: String,
        downloadId: Long,
    ): Boolean {
        if (!root.exists()) return true
        if (!root.isDirectory) return false
        val marker = File(root, ".ytdlnisx-owner").canonicalFile
        val text = runCatching { if (marker.isFile) marker.readText() else null }.getOrNull()
            ?: return false
        val fields = text.lineSequence().mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }.toMap()
        if (
            fields["downloadId"]?.toLongOrNull() != downloadId ||
            fields["executionId"] != expectedExecutionId
        ) return false
        val namespace = root.parentFile?.canonicalFile ?: return false
        if (namespace.name != ".ytdlnisx-output") return false
        val quarantine = File(namespace, ".ytdlnisx-quarantine").canonicalFile
        if (!quarantine.exists() && !quarantine.mkdirs()) return false
        if (!quarantine.isDirectory) return false
        val suffix = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$downloadId\n$expectedExecutionId".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val destination = File(quarantine, "$downloadId-$suffix").canonicalFile
        if (destination.exists()) {
            if (!destination.isDirectory || root.exists()) return false
        } else {
            val moved = runCatching {
                Files.move(root.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                true
            }.getOrElse {
                root.renameTo(destination)
            }
            if (!moved || !destination.isDirectory || root.exists()) return false
        }
        return true
    }
}
