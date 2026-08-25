package com.ireum.ytdl.util.extractors.ytdlp

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Durable process-group evidence for the bundled Python -> yt-dlp runtime.
 * yt-dlp can launch the bundled ffmpeg/ffprobe or aria2c binaries, so the
 * Java root Process is not by itself a descendant-quiescence proof.
 *
 * A marker is a launch state machine, not a PID cache. Every new marker has
 * an immutable random generation token. The supervisor and every descendant
 * inherit that token in their environment, which lets restart recovery address
 * the exact old generation even when its numeric PID/PGID has been reused.
 * Linux /proc start-time evidence is retained in the marker as a second
 * incarnation check. Recovery never sends a signal from a numeric PGID
 * alone; it re-reads the token and process start time immediately before
 * signalling the exact process.
 */
internal object YtdlpNativeProcessBarrier {
    private const val DIRECTORY_NAME = "process-barriers"
    private const val STATE_STARTING = "STARTING"
    private const val STATE_SUPERVISOR_STARTED = "SUPERVISOR_STARTED"
    private const val STATE_LAUNCHING_CHILD = "LAUNCHING_CHILD"
    private const val STATE_RUNNING = "RUNNING"
    private const val STATE_QUIESCENT = "QUIESCENT"
    private const val TERMINATION_WAIT_MILLIS = 4_000L
    private const val POLL_MILLIS = 50L
    private const val GENERATION_TOKEN_KEY = "generationToken"
    private const val PROCESS_ID_KEY = "processId"
    private const val PROCESS_ID_ENVIRONMENT = "YTDLNISX_PROCESS_ID"

    /** Environment key passed to the supervisor and inherited by descendants. */
    internal const val NATIVE_GENERATION_ENVIRONMENT = "YTDLNISX_NATIVE_GENERATION"

    @Volatile
    private var directory: File? = null

    internal data class PreparedProcess(
        val marker: File,
        val generationToken: String,
    )

    internal data class DurableDownloadProcess(
        val processId: String,
        val downloadId: Long,
        val executionId: String,
        val generationToken: String?,
    )

    private data class MarkerSnapshot(
        val state: String,
        val processId: String,
        val generationToken: String?,
        val supervisorPid: Long?,
        val supervisorStartTime: Long?,
        val childPid: Long?,
        val childStartTime: Long?,
        val pgid: Long?,
        val pgidStartTime: Long?,
    )

    private data class GenerationSelector(
        val environmentKey: String,
        val environmentValue: String,
    )

    private data class ProcSnapshot(
        val pid: Long,
        val startTime: Long,
        val processGroupId: Long,
        val environment: Map<String, String>,
    )

    private sealed interface ProcReadResult {
        data class Present(val process: ProcSnapshot) : ProcReadResult
        data object Gone : ProcReadResult
        data object Unavailable : ProcReadResult
    }

    private sealed interface ProcScan {
        data class Complete(val processes: List<ProcSnapshot>) : ProcScan
        data object Unavailable : ProcScan
    }

    fun configure(context: Context) {
        val next = File(
            context.applicationContext.noBackupFilesDir,
            "youtubedl-android/$DIRECTORY_NAME",
        )
        if (next.isDirectory || next.mkdirs()) {
            directory = next
        }
    }

    internal fun isConfigured(): Boolean = directory != null

    /**
     * Publishes an immutable launch intent before ProcessBuilder.start().
     * If start never happens, restart recovery proves that no process carrying
     * this token exists and clears the intent without using age or a timeout.
     */
    fun prepare(context: Context, processId: String): PreparedProcess {
        configure(context)
        val marker = markerFor(processId)
        if (marker.exists() && !recover(marker)) {
            error("An unresolved yt-dlp process barrier already exists for $processId")
        }
        val generationToken = UUID.randomUUID().toString()
        writeMarker(
            marker,
            MarkerSnapshot(
                state = STATE_STARTING,
                processId = processId,
                generationToken = generationToken,
                supervisorPid = null,
                supervisorStartTime = null,
                childPid = null,
                childStartTime = null,
                pgid = null,
                pgidStartTime = null,
            ),
        )
        return PreparedProcess(marker, generationToken)
    }

    fun markerFor(processId: String): File {
        val root = directory ?: error("Yt-dlp process barrier directory is not configured")
        val safeName = processId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(root, "$safeName.marker")
    }

    /** Returns the immutable launch token currently recorded for this process ID. */
    internal fun generationTokenFor(processId: String): String? {
        val marker = runCatching { markerFor(processId) }.getOrNull() ?: return null
        return readMarker(marker)
            ?.takeIf { it.processId == processId }
            ?.generationToken
    }

    fun isQuiescent(marker: File): Boolean = readMarker(marker)?.state == STATE_QUIESCENT

    /**
     * Clears a marker only after its exact generation is quiescent. The
     * start-failure path may clear STARTING after proving the token absent;
     * it can never delete a live generation merely because the marker is old.
     */
    fun clear(marker: File, expectedGenerationToken: String? = null) {
        val snapshot = readMarker(marker)
        if (snapshot != null) {
            if (
                expectedGenerationToken != null &&
                    snapshot.generationToken != expectedGenerationToken
            ) {
                error("yt-dlp process barrier generation changed before clear")
            }
            if (snapshot.state != STATE_QUIESCENT) {
                check(proveSelectorAbsent(selectorFor(snapshot))) {
                    "Cannot clear a live yt-dlp process barrier"
                }
            }
        } else if (marker.exists()) {
            error("Malformed yt-dlp process barrier ${marker.name}")
        }
        deleteMarker(marker)
    }

    /**
     * Quiesces a marker left by a force-killed or process-dead supervisor.
     * Only processes carrying the immutable generation identity are signalled.
     * Numeric PID/PGID values are never sufficient authority.
     */
    fun recover(marker: File): Boolean {
        if (!marker.exists()) return true
        val snapshot = readMarker(marker) ?: return false
        if (snapshot.state == STATE_QUIESCENT) {
            return deleteMarker(marker)
        }
        return recoverSelector(
            marker = marker,
            selector = selectorFor(snapshot),
            expectedGenerationToken = snapshot.generationToken,
        )
    }

    /**
     * Recovers an exact old generation without touching a newer marker for the
     * same product process ID. This is used by the recovery journal when an
     * old E1 carrier outlives its marker or when E2 has already published a
     * different generation identity.
     */
    internal fun recoverGeneration(
        processId: String,
        generationToken: String,
    ): Boolean {
        val marker = runCatching { markerFor(processId) }.getOrNull()
        val current = marker?.let(::readMarker)
        if (current?.generationToken == generationToken) {
            return recover(marker)
        }
        // A newer generation owns the marker, or the old marker was already
        // cleared. Only the old token may be inspected or terminated.
        return recoverSelector(
            marker = null,
            selector = GenerationSelector(
                NATIVE_GENERATION_ENVIRONMENT,
                generationToken,
            ),
            expectedGenerationToken = generationToken,
        )
    }

    /** Proves absence of one exact native generation without mutating a marker. */
    internal fun proveGenerationAbsent(generationToken: String): Boolean =
        when (val scan = scanGeneration(
            GenerationSelector(NATIVE_GENERATION_ENVIRONMENT, generationToken),
        )) {
            ProcScan.Unavailable -> false
            is ProcScan.Complete -> scan.processes.isEmpty()
        }

    fun hasUnresolved(processId: String): Boolean {
        val marker = runCatching { markerFor(processId) }.getOrNull() ?: return false
        return marker.exists() && !isQuiescent(marker)
    }

    fun downloadProcesses(context: Context): List<DurableDownloadProcess> {
        configure(context)
        return configuredDownloadProcesses()
    }

    fun configuredDownloadProcesses(): List<DurableDownloadProcess> {
        val root = directory ?: return emptyList()
        return root.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "marker" }
            .mapNotNull { marker ->
                val snapshot = readMarker(marker) ?: return@mapNotNull null
                val parts = snapshot.processId.split(':', limit = 3)
                if (parts.size != 3 || parts[0] != "download") return@mapNotNull null
                val downloadId = parts[1].toLongOrNull() ?: return@mapNotNull null
                DurableDownloadProcess(
                    processId = snapshot.processId,
                    downloadId = downloadId,
                    executionId = parts[2],
                    generationToken = snapshot.generationToken,
                )
            }
    }

    /** Test-only marker writer used with a real /proc-backed process harness. */
    internal fun writeMarkerForTesting(
        processId: String,
        state: String,
        generationToken: String,
        pgid: Long? = null,
        pgidStartTime: Long? = null,
    ): File {
        val marker = markerFor(processId)
        writeMarker(
            marker,
            MarkerSnapshot(
                state = state,
                processId = processId,
                generationToken = generationToken,
                supervisorPid = null,
                supervisorStartTime = null,
                childPid = null,
                childStartTime = null,
                pgid = pgid,
                pgidStartTime = pgidStartTime,
            ),
        )
        return marker
    }

    private fun recoverSelector(
        marker: File?,
        selector: GenerationSelector,
        expectedGenerationToken: String?,
    ): Boolean {
        var scan = scanGeneration(selector)
        if (scan is ProcScan.Unavailable) return false
        if ((scan as ProcScan.Complete).processes.isEmpty()) {
            return marker?.let { clearMarkerIfExact(it, expectedGenerationToken) } ?: true
        }

        signalProcesses(scan.processes, selector, OsConstants.SIGTERM)
        if (awaitGenerationGone(selector)) {
            return marker?.let { clearMarkerIfExact(it, expectedGenerationToken) } ?: true
        }

        scan = scanGeneration(selector)
        if (scan is ProcScan.Unavailable) return false
        signalProcesses((scan as ProcScan.Complete).processes, selector, OsConstants.SIGKILL)
        if (!awaitGenerationGone(selector)) return false
        return marker?.let { clearMarkerIfExact(it, expectedGenerationToken) } ?: true
    }

    private fun clearMarkerIfExact(marker: File, expectedGenerationToken: String?): Boolean {
        if (!marker.exists()) return true
        val snapshot = readMarker(marker) ?: return false
        if (
            expectedGenerationToken != null &&
                snapshot.generationToken != expectedGenerationToken
        ) {
            return true
        }
        return if (snapshot.state == STATE_QUIESCENT) {
            deleteMarker(marker)
        } else {
            runCatching {
                clear(marker, expectedGenerationToken)
                true
            }.getOrDefault(false)
        }
    }

    private fun selectorFor(snapshot: MarkerSnapshot): GenerationSelector =
        snapshot.generationToken?.let {
            GenerationSelector(NATIVE_GENERATION_ENVIRONMENT, it)
        } ?: GenerationSelector(PROCESS_ID_ENVIRONMENT, snapshot.processId)

    private fun awaitGenerationGone(selector: GenerationSelector): Boolean {
        val deadline = System.nanoTime() + TERMINATION_WAIT_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline) {
            when (val scan = scanGeneration(selector)) {
                ProcScan.Unavailable -> return false
                is ProcScan.Complete -> if (scan.processes.isEmpty()) return true
            }
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return when (val scan = scanGeneration(selector)) {
            ProcScan.Unavailable -> false
            is ProcScan.Complete -> scan.processes.isEmpty()
        }
    }

    private fun signalProcesses(
        processes: List<ProcSnapshot>,
        selector: GenerationSelector,
        signal: Int,
    ) {
        processes.forEach { process ->
            signalExactProcess(process, selector, signal)
        }
    }

    /**
     * Revalidates both the process incarnation and the immutable generation
     * before a signal. A recycled PID can therefore never receive an old
     * generation's signal merely because it occupies the same numeric slot.
     */
    private fun signalExactProcess(
        expected: ProcSnapshot,
        selector: GenerationSelector,
        signal: Int,
    ): Boolean {
        val current = when (val result = readProcSnapshot(expected.pid)) {
            ProcReadResult.Gone -> return true
            ProcReadResult.Unavailable -> return false
            is ProcReadResult.Present -> result.process
        }
        if (
            current.startTime != expected.startTime ||
                !matchesSelector(current.environment, selector)
        ) {
            return true
        }
        return try {
            Os.kill(expected.pid.toInt(), signal)
            true
        } catch (error: ErrnoException) {
            error.errno == OsConstants.ESRCH
        } catch (_: Exception) {
            false
        }
    }

    private fun proveSelectorAbsent(selector: GenerationSelector): Boolean =
        when (val scan = scanGeneration(selector)) {
            ProcScan.Unavailable -> false
            is ProcScan.Complete -> scan.processes.isEmpty()
        }

    private fun scanGeneration(selector: GenerationSelector): ProcScan {
        val procRoot = File("/proc")
        val entries = procRoot.listFiles()
            ?: return ProcScan.Unavailable
        val currentUid = runCatching { android.os.Process.myUid().toLong() }.getOrNull()
        val processes = mutableListOf<ProcSnapshot>()
        for (entry in entries) {
            val pid = entry.name.toLongOrNull() ?: continue
            val statusUid = readUid(entry)
            if (currentUid != null && statusUid != null && statusUid != currentUid) {
                continue
            }
            when (val result = readProcSnapshot(pid)) {
                ProcReadResult.Gone -> {
                    // A process can disappear between /proc directory
                    // enumeration and stat read. That is positive absence
                    // evidence for this candidate, not a reason to address a
                    // numeric PID.
                    continue
                }
                ProcReadResult.Unavailable -> return ProcScan.Unavailable
                is ProcReadResult.Present -> {
                    if (matchesSelector(result.process.environment, selector)) {
                        processes += result.process
                    }
                }
            }
        }
        return ProcScan.Complete(processes)
    }

    private fun readProcSnapshot(pid: Long): ProcReadResult {
        if (pid <= 0L || pid > Int.MAX_VALUE) return ProcReadResult.Gone
        val processDirectory = File("/proc/$pid")
        if (!processDirectory.isDirectory) return ProcReadResult.Gone
        val stat = try {
            processDirectory.resolve("stat").readText(StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return if (processDirectory.exists()) {
                ProcReadResult.Unavailable
            } else {
                ProcReadResult.Gone
            }
        }
        val closeParen = stat.lastIndexOf(") ")
        if (closeParen < 0) return ProcReadResult.Unavailable
        val fields = stat.substring(closeParen + 2).trim().split(Regex("\\s+"))
        val processGroupId = fields.getOrNull(2)?.toLongOrNull()
            ?: return ProcReadResult.Unavailable
        val startTime = fields.getOrNull(19)?.toLongOrNull()
            ?: return ProcReadResult.Unavailable
        val environment = try {
            val bytes = processDirectory.resolve("environ").readBytes()
            bytes.toString(StandardCharsets.ISO_8859_1)
                .split('\u0000')
                .asSequence()
                .mapNotNull { value ->
                    val separator = value.indexOf('=')
                    if (separator <= 0) null
                    else value.substring(0, separator) to value.substring(separator + 1)
                }
                .toMap()
        } catch (_: Exception) {
            return if (processDirectory.exists()) {
                ProcReadResult.Unavailable
            } else {
                ProcReadResult.Gone
            }
        }
        return ProcReadResult.Present(
            ProcSnapshot(pid, startTime, processGroupId, environment),
        )
    }

    private fun readUid(entry: File): Long? = runCatching {
        entry.resolve("status")
            .useLines { lines ->
                lines.firstOrNull { it.startsWith("Uid:") }
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toLongOrNull()
            }
    }.getOrNull()

    private fun matchesSelector(
        environment: Map<String, String>,
        selector: GenerationSelector,
    ): Boolean = environment[selector.environmentKey] == selector.environmentValue

    private fun readMarker(marker: File): MarkerSnapshot? {
        if (!marker.isFile) return null
        return runCatching {
            val lines = marker.readLines(StandardCharsets.UTF_8)
            val values = lines
                .drop(1)
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
            val processId = values[PROCESS_ID_KEY]
                ?.takeIf { it.isNotBlank() }
                ?: return@runCatching null
            MarkerSnapshot(
                state = lines.firstOrNull().orEmpty(),
                processId = processId,
                generationToken = values[GENERATION_TOKEN_KEY]?.takeIf { it.isNotBlank() },
                supervisorPid = values["supervisorPid"]?.toLongOrNull(),
                supervisorStartTime = values["supervisorStartTime"]?.toLongOrNull(),
                childPid = values["childPid"]?.toLongOrNull(),
                childStartTime = values["childStartTime"]?.toLongOrNull(),
                pgid = values["pgid"]?.toLongOrNull(),
                pgidStartTime = values["pgidStartTime"]?.toLongOrNull(),
            )
        }.getOrNull()
    }

    private fun writeMarker(marker: File, snapshot: MarkerSnapshot) {
        marker.parentFile?.mkdirs()
        val temporary = File(
            marker.parentFile,
            marker.name + ".tmp." + UUID.randomUUID(),
        )
        val lines = buildString {
            appendLine(snapshot.state)
            appendLine("$PROCESS_ID_KEY=${snapshot.processId}")
            snapshot.generationToken?.let { appendLine("$GENERATION_TOKEN_KEY=$it") }
            snapshot.supervisorPid?.let { appendLine("supervisorPid=$it") }
            snapshot.supervisorStartTime?.let { appendLine("supervisorStartTime=$it") }
            snapshot.childPid?.let { appendLine("childPid=$it") }
            snapshot.childStartTime?.let { appendLine("childStartTime=$it") }
            snapshot.pgid?.let { appendLine("pgid=$it") }
            snapshot.pgidStartTime?.let { appendLine("pgidStartTime=$it") }
        }
        FileOutputStream(temporary).use { output ->
            output.write(lines.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(marker)) {
            temporary.delete()
            "Could not atomically publish yt-dlp process barrier ${marker.name}"
        }
    }

    private fun deleteMarker(marker: File): Boolean {
        if (!marker.exists()) return true
        return marker.delete() || !marker.exists()
    }
}
