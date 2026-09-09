package com.ireum.ytdl.work

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Durable lifecycle ownership for one Terminal execution.
 *
 * PublicationRecoveryJournal is deliberately limited to output lineage.  A
 * Terminal command also needs an owner before it has an output directory (or
 * any output at all), so this app-private journal carries the execution and
 * native-generation obligation independently of cache use.
 */
internal object TerminalExecutionRecovery {
    private const val DIRECTORY_NAME = "terminal-execution-recovery"
    private const val FILE_PREFIX = "ytdlnisx-terminal-execution-"
    private const val SCHEMA_VERSION = 1
    private val gson = Gson()
    private val lock = Any()

    internal enum class Phase {
        ADMITTED,
        NATIVE_STARTED,
        NATIVE_FINISHED,
        NATIVE_QUIESCENCE_PENDING,
        TERMINAL_FAILURE,
        TERMINAL_STOPPED,
        COMMITTING,
        COMMITTED,
    }

    internal enum class Outcome {
        FAILURE,
        STOPPED,
    }

    internal enum class Admission {
        NO_WITNESS,
        ACQUIRED,
        ALREADY_COMMITTED,
        TERMINAL_FAILURE,
        BLOCKED,
        PERSISTENCE_FAILURE,
    }

    internal data class Record(
        val version: Int = SCHEMA_VERSION,
        val subjectId: Long,
        val executionToken: String,
        val processId: String,
        val phase: Phase,
        val outcome: Outcome? = null,
        val nativeGenerationToken: String? = null,
    ) {
        val nativeMayHaveStarted: Boolean
            get() = phase in setOf(
                Phase.NATIVE_STARTED,
                Phase.NATIVE_FINISHED,
                Phase.NATIVE_QUIESCENCE_PENDING,
            ) || nativeGenerationToken != null

        val terminal: Boolean
            get() = phase in setOf(
                Phase.TERMINAL_FAILURE,
                Phase.TERMINAL_STOPPED,
                Phase.COMMITTED,
            )

        val quiescent: Boolean
            get() = phase in setOf(
                Phase.ADMITTED,
                Phase.NATIVE_FINISHED,
                Phase.TERMINAL_FAILURE,
                Phase.TERMINAL_STOPPED,
                Phase.COMMITTING,
                Phase.COMMITTED,
            )
    }

    internal data class ReconcileResult(
        val discovered: Int,
        val converged: Int,
        val deferred: Int,
    )

    /** Explicit discovery status for the execution-witness namespace. */
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

    /** Deterministic test seam; production uses checked atomic writes. */
    @Volatile
    internal var persistenceFailureForTesting: Boolean = false

    /** Deterministic native-quiescence seam for worker/recovery tests. */
    @Volatile
    internal var quiescenceOverrideForTesting: ((String, String?) -> Boolean)? = null

    fun begin(
        context: Context,
        subjectId: Long,
        executionToken: String,
        processId: String,
    ): Boolean = begin(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        subjectId = subjectId,
        executionToken = executionToken,
        processId = processId,
    )

    internal fun begin(
        storageDirectory: File,
        subjectId: Long,
        executionToken: String,
        processId: String,
    ): Boolean = synchronized(lock) {
        if (subjectId <= 0L || executionToken.isBlank() || processId.isBlank()) return false
        val file = recordFile(storageDirectory, subjectId)
        val existing = read(file)
        if (file.exists() && existing == null) return false
        if (existing != null) {
            return existing.subjectId == subjectId && existing.executionToken == executionToken &&
                existing.processId == processId
        }
        persist(
            file,
            Record(
                subjectId = subjectId,
                executionToken = executionToken,
                processId = processId,
                phase = Phase.ADMITTED,
            ),
        )
    }

    internal fun read(context: Context, subjectId: Long): Record? =
        read(recordFile(File(context.filesDir, DIRECTORY_NAME), subjectId))

    internal fun read(storageDirectory: File, subjectId: Long): Record? =
        read(recordFile(storageDirectory, subjectId))

    internal fun hasRecordFile(context: Context, subjectId: Long): Boolean =
        recordFile(File(context.filesDir, DIRECTORY_NAME), subjectId).exists()

    /**
     * Abandon only the provisional admission witness when publication
     * admission did not acquire the subject and native execution therefore
     * could not have started.  A failure to remove it is deliberately treated
     * as retained durable responsibility by the caller.
     */
    internal fun abandonAdmission(
        context: Context,
        subjectId: Long,
        executionToken: String,
    ): Boolean = abandonAdmission(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        subjectId = subjectId,
        executionToken = executionToken,
    )

    internal fun abandonAdmission(
        storageDirectory: File,
        subjectId: Long,
        executionToken: String,
    ): Boolean = synchronized(lock) {
        val file = recordFile(storageDirectory, subjectId)
        val current = read(file) ?: return false
        if (current.executionToken != executionToken || current.phase != Phase.ADMITTED) {
            return false
        }
        if (!file.delete()) return false
        !file.exists()
    }

    internal fun readAll(context: Context): List<Record> =
        readAll(File(context.filesDir, DIRECTORY_NAME))

    internal fun readAll(storageDirectory: File): List<Record> {
        return discover(storageDirectory).records
    }

    /**
     * Discover execution witnesses without treating an unreadable namespace
     * or malformed witness as healthy absence of debt.
     */
    internal fun discover(storageDirectory: File): DiscoveryResult {
        if (!storageDirectory.exists()) return DiscoveryResult.Healthy(emptyList())
        if (!storageDirectory.isDirectory) {
            return DiscoveryResult.Unavailable("terminal execution recovery is not a directory")
        }
        val files = try {
            storageDirectory.listFiles()
                ?: return DiscoveryResult.Unavailable("terminal execution recovery listing failed")
        } catch (error: SecurityException) {
            return DiscoveryResult.Unavailable(
                "terminal execution recovery listing denied: ${error::class.java.simpleName}",
            )
        }
        val candidates = files.filter {
            it.isFile && it.name.startsWith(FILE_PREFIX) && it.extension == "json"
        }
        val records = candidates.mapNotNull(::read).filter(::isValid)
        val validPaths = records.map { recordFile(storageDirectory, it.subjectId).canonicalPath }.toSet()
        val opaqueFiles = candidates.filter { candidate ->
            runCatching { candidate.canonicalPath !in validPaths }.getOrDefault(true)
        }.map(File::getAbsolutePath)
        return if (opaqueFiles.isEmpty()) {
            DiscoveryResult.Healthy(records)
        } else {
            DiscoveryResult.Opaque(records, opaqueFiles)
        }
    }

    /**
     * Admission first reconciles an older exact witness.  A terminal record
     * is a tombstone and can never be replaced by a new task token for the
     * same subject; Terminal IDs are monotonic and a stale WorkManager retry
     * must therefore become a no-op/failure, never a replay.
     */
    internal fun inspectAdmission(
        context: Context,
        subjectId: Long,
        activeExecution: (String) -> Boolean,
    ): Admission {
        // Admission can be the first recovery entrypoint after process death.
        // Configure the native marker namespace before attempting to rebind a
        // generation prepared immediately before launch; otherwise the marker
        // is observed as UNKNOWN merely because the process-local barrier had
        // not yet been initialized.
        YtdlpNativeProcessBarrier.configure(context)
        val storageDirectory = File(context.filesDir, DIRECTORY_NAME)
        when (discover(storageDirectory)) {
            is DiscoveryResult.Unavailable,
            is DiscoveryResult.Opaque -> return Admission.BLOCKED
            is DiscoveryResult.Healthy -> Unit
        }
        val file = recordFile(storageDirectory, subjectId)
        if (!file.exists()) return Admission.NO_WITNESS
        val record = read(file) ?: return Admission.TERMINAL_FAILURE
        if (activeExecution(record.executionToken)) return Admission.BLOCKED
        val reconciled = reconcileRecord(context, record)
        val after = read(file)
        if (after == null) {
            return if (
                reconciled &&
                    record.phase in setOf(Phase.COMMITTING, Phase.COMMITTED)
            ) {
                Admission.ALREADY_COMMITTED
            } else {
                Admission.TERMINAL_FAILURE
            }
        }
        return when (after.phase) {
            Phase.COMMITTED -> Admission.ALREADY_COMMITTED
            Phase.TERMINAL_FAILURE,
            Phase.TERMINAL_STOPPED -> Admission.TERMINAL_FAILURE
            else -> Admission.BLOCKED
        }
    }

    internal fun markNativeStarted(
        context: Context,
        subjectId: Long,
        executionToken: String,
    ): Boolean = markNativeStarted(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        subjectId = subjectId,
        executionToken = executionToken,
    )

    internal fun markNativeStarted(
        storageDirectory: File,
        subjectId: Long,
        executionToken: String,
    ): Boolean = update(storageDirectory, subjectId, executionToken) { current ->
        when (current.phase) {
            Phase.ADMITTED,
            Phase.NATIVE_STARTED -> current.copy(phase = Phase.NATIVE_STARTED)
            else -> null
        }
    }

    internal fun bindNativeGeneration(
        context: Context,
        subjectId: Long,
        executionToken: String,
        generationToken: String,
    ): Boolean = bindNativeGeneration(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        subjectId = subjectId,
        executionToken = executionToken,
        generationToken = generationToken,
    )

    internal fun bindNativeGeneration(
        storageDirectory: File,
        subjectId: Long,
        executionToken: String,
        generationToken: String,
    ): Boolean {
        if (generationToken.isBlank()) return false
        return update(storageDirectory, subjectId, executionToken) { current ->
            // The generation marker is prepared immediately before launch.
            // Binding it is the durable handoff that changes ADMITTED into
            // NATIVE_STARTED; accepting both phases also lets restart
            // recovery consume a marker written between prepare() and the
            // original bind callback without inventing a second execution.
            if (current.phase != Phase.ADMITTED && current.phase != Phase.NATIVE_STARTED) {
                return@update null
            }
            if (
                current.nativeGenerationToken != null &&
                    current.nativeGenerationToken != generationToken
            ) return@update null
            current.copy(
                phase = Phase.NATIVE_STARTED,
                nativeGenerationToken = generationToken,
            )
        }
    }

    /** execute() only returns after its native barrier proves quiescence. */
    internal fun markNativeFinished(
        context: Context,
        subjectId: Long,
        executionToken: String,
    ): Boolean = markNativeFinished(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        subjectId = subjectId,
        executionToken = executionToken,
    )

    internal fun markNativeFinished(
        storageDirectory: File,
        subjectId: Long,
        executionToken: String,
    ): Boolean = update(storageDirectory, subjectId, executionToken) { current ->
        when (current.phase) {
            Phase.NATIVE_STARTED,
            Phase.NATIVE_FINISHED -> current.copy(phase = Phase.NATIVE_FINISHED)
            else -> null
        }
    }

    internal fun markCommitting(
        context: Context,
        subjectId: Long,
        executionToken: String,
    ): Boolean = markCommitting(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        subjectId = subjectId,
        executionToken = executionToken,
    )

    internal fun markCommitting(
        storageDirectory: File,
        subjectId: Long,
        executionToken: String,
    ): Boolean = update(storageDirectory, subjectId, executionToken) { current ->
        when (current.phase) {
            Phase.NATIVE_FINISHED,
            Phase.COMMITTING -> current.copy(phase = Phase.COMMITTING)
            else -> null
        }
    }

    internal fun markCommitted(
        context: Context,
        subjectId: Long,
        executionToken: String,
    ): Boolean = markCommitted(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        subjectId = subjectId,
        executionToken = executionToken,
    )

    internal fun markCommitted(
        storageDirectory: File,
        subjectId: Long,
        executionToken: String,
    ): Boolean = update(storageDirectory, subjectId, executionToken) { current ->
        when (current.phase) {
            Phase.COMMITTING,
            Phase.COMMITTED -> current.copy(phase = Phase.COMMITTED)
            else -> null
        }
    }

    /** Persist the stop/failure obligation before attempting native destroy. */
    internal fun markQuiescencePending(
        context: Context,
        subjectId: Long,
        executionToken: String,
        outcome: Outcome,
    ): Boolean = markQuiescencePending(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        subjectId = subjectId,
        executionToken = executionToken,
        outcome = outcome,
    )

    internal fun markQuiescencePending(
        storageDirectory: File,
        subjectId: Long,
        executionToken: String,
        outcome: Outcome,
    ): Boolean = update(storageDirectory, subjectId, executionToken) { current ->
        when (current.phase) {
            Phase.NATIVE_STARTED,
            Phase.NATIVE_QUIESCENCE_PENDING ->
                current.copy(phase = Phase.NATIVE_QUIESCENCE_PENDING, outcome = outcome)
            Phase.NATIVE_FINISHED -> current.copy(
                phase = if (outcome == Outcome.STOPPED) {
                    Phase.TERMINAL_STOPPED
                } else {
                    Phase.TERMINAL_FAILURE
                },
                outcome = outcome,
            )
            Phase.TERMINAL_FAILURE,
            Phase.TERMINAL_STOPPED -> current
            else -> null
        }
    }

    /** Terminalize setup failures where native execution is proven not started. */
    internal fun markTerminalFailure(
        context: Context,
        subjectId: Long,
        executionToken: String,
        outcome: Outcome = Outcome.FAILURE,
        quiescenceProven: Boolean = false,
    ): Boolean = markTerminalFailure(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        subjectId = subjectId,
        executionToken = executionToken,
        outcome = outcome,
        quiescenceProven = quiescenceProven,
    )

    internal fun markTerminalFailure(
        storageDirectory: File,
        subjectId: Long,
        executionToken: String,
        outcome: Outcome = Outcome.FAILURE,
        quiescenceProven: Boolean = false,
    ): Boolean = update(storageDirectory, subjectId, executionToken) { current ->
        when (current.phase) {
            Phase.ADMITTED -> current.copy(
                phase = if (outcome == Outcome.STOPPED) Phase.TERMINAL_STOPPED else Phase.TERMINAL_FAILURE,
                outcome = outcome,
            )
            Phase.NATIVE_FINISHED -> current.copy(
                phase = if (outcome == Outcome.STOPPED) Phase.TERMINAL_STOPPED else Phase.TERMINAL_FAILURE,
                outcome = outcome,
            )
            Phase.NATIVE_QUIESCENCE_PENDING -> current.copy(
                phase = if (quiescenceProven) {
                    if (outcome == Outcome.STOPPED) Phase.TERMINAL_STOPPED else Phase.TERMINAL_FAILURE
                } else {
                    return@update null
                },
                outcome = outcome,
            )
            Phase.TERMINAL_FAILURE,
            Phase.TERMINAL_STOPPED -> current
            else -> null
        }
    }

    /**
     * Prove the exact native generation is quiescent.  A missing generation
     * token is accepted only for an ADMITTED record (native cannot have
     * started).  Once native may have started, the barrier/process contract
     * is authoritative and a false result retains the durable owner.
     */
    internal fun proveNativeQuiescent(
        context: Context,
        subjectId: Long,
        executionToken: String,
    ): Boolean {
        val record = read(context, subjectId)?.takeIf { it.executionToken == executionToken }
            ?: return false
        if (!record.nativeMayHaveStarted || record.phase == Phase.ADMITTED) return true
        // Once native may have started, a processId without the immutable
        // generation token is not enough authority to signal or clear a
        // process: the same Terminal subject can have a newer generation.
        // Preserve the pending witness until an exact generation is bound or
        // an external recovery mechanism proves the old generation quiescent.
        if (record.nativeGenerationToken.isNullOrBlank()) return false
        quiescenceOverrideForTesting?.let { return it(record.processId, record.nativeGenerationToken) }
        return YoutubeDLCompat.destroyProcessByIdForGeneration(
            record.processId,
            record.nativeGenerationToken,
        )
    }

    /**
     * Finish a failure/stop only after quiescence has been positively proven.
     * The record is intentionally retained as a terminal tombstone so a stale
     * WorkManager retry cannot acquire a new token for the same Terminal row.
     */
    internal fun convergeTerminal(
        context: Context,
        subjectId: Long,
        executionToken: String,
        outcome: Outcome,
    ): Boolean {
        val current = read(context, subjectId)?.takeIf { it.executionToken == executionToken }
            ?: return false
        if (current.phase == Phase.NATIVE_STARTED) {
            if (!markQuiescencePending(context, subjectId, executionToken, outcome)) return false
            if (!proveNativeQuiescent(context, subjectId, executionToken)) return false
        } else if (current.phase == Phase.NATIVE_QUIESCENCE_PENDING) {
            if (!proveNativeQuiescent(context, subjectId, executionToken)) return false
        } else if (current.phase !in setOf(Phase.ADMITTED, Phase.NATIVE_FINISHED, Phase.TERMINAL_FAILURE, Phase.TERMINAL_STOPPED)) {
            return false
        }
        return markTerminalFailure(
            context,
            subjectId,
            executionToken,
            outcome,
            quiescenceProven = true,
        )
    }

    internal fun canRelease(
        context: Context,
        subjectId: Long,
        executionToken: String?,
    ): Boolean {
        if (executionToken == null) return true
        if (!hasRecordFile(context, subjectId)) return true
        val record = read(context, subjectId) ?: return false
        if (record.executionToken != executionToken) return false
        return record.terminal
    }

    /**
     * Reconcile every exact record at app startup.  Process-local activity is
     * supplied by the registry so startup never destroys a live worker's
     * native generation.  No output directory scan is used as authority.
     */
    internal fun reconcile(
        context: Context,
        activeExecution: (String) -> Boolean = { false },
    ): ReconcileResult {
        YtdlpNativeProcessBarrier.configure(context)
        val storageDirectory = File(context.filesDir, DIRECTORY_NAME)
        val discovery = discover(storageDirectory)
        if (discovery is DiscoveryResult.Unavailable) {
            return ReconcileResult(discovered = 0, converged = 0, deferred = 1)
        }
        val records = discovery.records
        var converged = 0
        // Keep malformed/unreadable records visible as deferred debt.  The
        // subject-specific admission path also fails closed on these files;
        // startup must not report the namespace as clean merely because a
        // parser could not reconstruct a record.
        var deferred = (discovery as? DiscoveryResult.Opaque)?.opaqueFiles?.size ?: 0
        records.forEach { record ->
            if (activeExecution(record.executionToken)) return@forEach
            if (reconcileRecord(context, record)) converged++ else if (!record.terminal) deferred++
        }
        return ReconcileResult(records.size + deferred, converged, deferred)
    }

    /** Persist a terminal tombstone when legacy generic carrier recovery owns a stale row. */
    internal fun recordLegacyTerminalFailure(
        context: Context,
        subjectId: Long,
        executionToken: String,
        processId: String,
    ): Boolean = recordLegacyTerminalFailure(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        subjectId = subjectId,
        executionToken = executionToken,
        processId = processId,
    )

    internal fun recordLegacyTerminalFailure(
        storageDirectory: File,
        subjectId: Long,
        executionToken: String,
        processId: String,
    ): Boolean {
        synchronized(lock) {
            val file = recordFile(storageDirectory, subjectId)
            val existing = read(file)
            if (existing != null) {
                return existing.executionToken == executionToken && existing.terminal
            }
            if (file.exists()) return false
            return persist(
                file,
                Record(
                    subjectId = subjectId,
                    executionToken = executionToken,
                    processId = processId,
                    phase = Phase.TERMINAL_FAILURE,
                    outcome = Outcome.FAILURE,
                ),
            )
        }
    }

    internal fun clearForTesting(storageDirectory: File, subjectId: Long) {
        synchronized(lock) {
            recordFile(storageDirectory, subjectId).delete()
        }
    }

    private fun reconcileRecord(context: Context, record: Record): Boolean {
        // A process can die after the native barrier has durably created its
        // exact generation marker but before the pre-launch bind callback
        // reaches this journal. Rebind only a readable exact marker for the
        // same Terminal process identity. An unreadable marker remains
        // durable debt; it is never treated as proof that no native launch
        // occurred.
        if (record.phase == Phase.ADMITTED && record.nativeGenerationToken.isNullOrBlank()) {
            when (val observation = YtdlpNativeProcessBarrier.observeGeneration(record.processId)) {
                is YtdlpNativeProcessBarrier.GenerationObservation.EXACT_GENERATION -> {
                    if (!bindNativeGeneration(
                            context = context,
                            subjectId = record.subjectId,
                            executionToken = record.executionToken,
                            generationToken = observation.token,
                        )
                    ) return false
                    val rebound = read(context, record.subjectId) ?: return false
                    return reconcileRecord(context, rebound)
                }
                YtdlpNativeProcessBarrier.GenerationObservation.UNKNOWN,
                is YtdlpNativeProcessBarrier.GenerationObservation.LEGACY_IDENTITY ->
                    return false
                YtdlpNativeProcessBarrier.GenerationObservation.ABSENT -> Unit
            }
        }
        return when (record.phase) {
            Phase.ADMITTED -> {
                val marked = markTerminalFailure(
                    context,
                    record.subjectId,
                    record.executionToken,
                )
                if (marked) finishTerminalRecord(context, record) else false
            }
            Phase.NATIVE_STARTED -> {
                val outcome = record.outcome ?: Outcome.FAILURE
                if (markQuiescencePending(context, record.subjectId, record.executionToken, outcome) &&
                    proveNativeQuiescent(context, record.subjectId, record.executionToken) &&
                    markTerminalFailure(
                        context,
                        record.subjectId,
                        record.executionToken,
                        outcome,
                        quiescenceProven = true,
                    )
                ) {
                    finishTerminalRecord(context, record)
                } else false
            }
            Phase.NATIVE_QUIESCENCE_PENDING -> {
                val outcome = record.outcome ?: Outcome.FAILURE
                if (proveNativeQuiescent(context, record.subjectId, record.executionToken) &&
                    markTerminalFailure(
                        context,
                        record.subjectId,
                        record.executionToken,
                        outcome,
                        quiescenceProven = true,
                    )
                ) {
                    finishTerminalRecord(context, record)
                } else false
            }
            Phase.NATIVE_FINISHED -> {
                // Native returned successfully but semantic Terminal commit
                // was never entered. Do not replay; converge as a terminal
                // failure while preserving any publication journal separately.
                if (markTerminalFailure(context, record.subjectId, record.executionToken)) {
                    finishTerminalRecord(context, record)
                } else false
            }
            Phase.COMMITTING -> {
                val rowDeleted = deleteRow(context, record.subjectId)
                if (rowDeleted && markCommitted(context, record.subjectId, record.executionToken)) {
                    finishTerminalRecord(context, record)
                } else false
            }
            Phase.TERMINAL_FAILURE,
            Phase.TERMINAL_STOPPED,
            Phase.COMMITTED -> finishTerminalRecord(context, record)
        }
    }

    /**
     * Retire the execution tombstone only after the Terminal row is absent.
     * The row check is the semantic stale-WorkManager fence; retaining the
     * tombstone while that check fails keeps the old execution authoritative.
     */
    private fun finishTerminalRecord(context: Context, record: Record): Boolean {
        if (!deleteRow(context, record.subjectId)) return false
        return synchronized(lock) {
            val file = recordFile(File(context.filesDir, DIRECTORY_NAME), record.subjectId)
            val current = read(file) ?: return@synchronized !file.exists()
            if (current.executionToken != record.executionToken || !current.terminal) return@synchronized false
            if (!file.delete()) return@synchronized false
            !file.exists()
        }
    }

    private fun deleteRow(context: Context, subjectId: Long): Boolean = runCatching {
        val dao = DBManager.getInstance(context).terminalDao
        runBlocking(Dispatchers.IO) { dao.delete(subjectId) }
        dao.getTerminalById(subjectId) == null
    }.getOrDefault(false)

    private fun update(
        context: Context,
        subjectId: Long,
        executionToken: String,
        transform: (Record) -> Record?,
    ): Boolean = update(
        storageDirectory = File(context.filesDir, DIRECTORY_NAME),
        subjectId = subjectId,
        executionToken = executionToken,
        transform = transform,
    )

    private fun update(
        storageDirectory: File,
        subjectId: Long,
        executionToken: String,
        transform: (Record) -> Record?,
    ): Boolean = synchronized(lock) {
        val file = recordFile(storageDirectory, subjectId)
        val current = read(file) ?: return false
        if (current.executionToken != executionToken) return false
        val next = transform(current) ?: return false
        next == current || persist(file, next)
    }

    private fun recordFile(storageDirectory: File, subjectId: Long): File =
        File(storageDirectory, "$FILE_PREFIX$subjectId.json")

    private fun read(file: File): Record? = runCatching {
        if (!file.isFile) return null
        val json = JsonParser.parseString(file.readText()).asJsonObject
        if (
            !json.has("version") || !json.has("subjectId") ||
            !json.has("executionToken") || !json.has("processId") || !json.has("phase")
        ) return null
        gson.fromJson(json, Record::class.java)
    }.getOrNull()?.takeIf(::isValid)

    private fun isValid(record: Record): Boolean =
        record.version == SCHEMA_VERSION && record.subjectId > 0L &&
            record.executionToken.isNotBlank() &&
            record.processId == YtdlpProcessIdentity.terminal(record.subjectId)

    private fun persist(file: File, record: Record): Boolean = runCatching {
        if (persistenceFailureForTesting) return false
        file.parentFile?.let { parent -> if (!parent.exists() && !parent.mkdirs()) return false }
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
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        read(file) == record
    }.getOrDefault(false)
}
