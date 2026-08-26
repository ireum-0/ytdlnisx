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

    /**
     * The result of consuming a native-generation proof.  The two positive
     * states deliberately remain positive even when physical marker cleanup
     * is still pending: the process is gone, while the marker is merely
     * finalization debt.
     */
    internal enum class QuiescenceState {
        PROVEN_QUIESCENT_AND_CLEARED,
        PROVEN_QUIESCENT_CLEANUP_PENDING,
        UNRESOLVED,
        OWNER_OR_GENERATION_CHANGED,
    }

    internal data class FinalizationResult(
        val state: QuiescenceState,
        val generationToken: String? = null,
    ) {
        val isProvenQuiescent: Boolean
            get() = state == QuiescenceState.PROVEN_QUIESCENT_AND_CLEARED ||
                state == QuiescenceState.PROVEN_QUIESCENT_CLEANUP_PENDING
    }

    /**
     * A nullable token is not an observation.  In particular, UNKNOWN must
     * never be treated as ABSENT by the recovery journal.
     */
    internal sealed interface GenerationObservation {
        data object ABSENT : GenerationObservation
        data class EXACT_GENERATION(val token: String) : GenerationObservation
        data class LEGACY_IDENTITY(val processId: String) : GenerationObservation
        data object UNKNOWN : GenerationObservation
    }

    private sealed interface MarkerObservation {
        data object ABSENT : MarkerObservation
        data class PRESENT(val snapshot: MarkerSnapshot) : MarkerObservation
        data object MALFORMED : MarkerObservation
        data object UNREADABLE : MarkerObservation
    }

    @Volatile
    private var directory: File? = null

    /** Deterministic marker fault seams used by state-machine tests. */
    @Volatile
    internal var markerWriteFailureForTesting: Boolean = false

    @Volatile
    internal var markerDeleteFailureForTesting: Boolean = false

    @Volatile
    internal var markerReadFailureForTesting: Boolean = false

    @Volatile
    internal var markerReadFailurePathForTesting: String? = null

    @Volatile
    internal var markerEnumerationFailureForTesting: Boolean = false

    internal class NativeMarkerNamespaceUnavailableException(
        message: String,
    ) : IllegalStateException(message)

    internal data class PreparedProcess(
        val marker: File,
        val generationToken: String,
    )

    internal data class DurableDownloadProcess(
        val processId: String,
        val downloadId: Long,
        val executionId: String,
        val generationToken: String?,
        val nativeRole: String?,
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

    /** Test-only namespace injection for JVM coverage of fail-closed checks. */
    internal fun configureForTesting(root: File) {
        directory = root
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
        return when (val observation = observeGeneration(processId)) {
            is GenerationObservation.EXACT_GENERATION -> observation.token
            else -> null
        }
    }

    /**
     * Reads the exact marker identity without collapsing read failures into
     * the absence of a marker.
     */
    internal fun observeGeneration(processId: String): GenerationObservation {
        val marker = runCatching { markerFor(processId) }.getOrElse {
            return GenerationObservation.UNKNOWN
        }
        return when (val observation = readMarkerObservation(marker)) {
            MarkerObservation.ABSENT -> GenerationObservation.ABSENT
            MarkerObservation.MALFORMED,
            MarkerObservation.UNREADABLE -> GenerationObservation.UNKNOWN
            is MarkerObservation.PRESENT -> {
                val snapshot = observation.snapshot
                if (snapshot.processId != processId) {
                    GenerationObservation.UNKNOWN
                } else {
                    snapshot.generationToken?.let {
                        GenerationObservation.EXACT_GENERATION(it)
                    } ?: GenerationObservation.LEGACY_IDENTITY(processId)
                }
            }
        }
    }

    /**
     * Returns the exact native observations for all roles of one Download
     * execution. A readable QUIESCENT marker still carries its exact token:
     * it is native-finalization debt, not proof that the marker namespace is
     * absent. More than one token is intentionally UNKNOWN for the single
     * journal carrier; recovery still enumerates every marker independently
     * before releasing the execution owner.
     */
    internal fun observeDownloadExecution(
        downloadId: Long,
        executionId: String,
    ): GenerationObservation {
        if (!isConfigured()) return GenerationObservation.UNKNOWN
        val candidates = if (executionId.isBlank()) {
            downloadMarkerCandidatesForId(downloadId)
        } else {
            markerCandidates(downloadId, executionId)
        }
            ?: return GenerationObservation.UNKNOWN
        if (candidates.isEmpty()) return GenerationObservation.ABSENT
        var legacyProcessId: String? = null
        val exactTokens = mutableListOf<String>()
        for (marker in candidates) {
            when (val observation = readMarkerObservation(marker)) {
                MarkerObservation.ABSENT -> Unit
                MarkerObservation.MALFORMED,
                MarkerObservation.UNREADABLE -> return GenerationObservation.UNKNOWN
                is MarkerObservation.PRESENT -> {
                    val snapshot = observation.snapshot
                    val parts = snapshot.processId.split(':')
                    if (
                        parts.size < 3 ||
                            parts[0] != "download" ||
                            parts[1] != downloadId.toString() ||
                            (executionId.isNotBlank() && parts[2] != executionId)
                    ) {
                        return GenerationObservation.UNKNOWN
                    }
                    snapshot.generationToken?.let(exactTokens::add)
                        ?: run { legacyProcessId = snapshot.processId }
                }
            }
        }
        if (legacyProcessId != null) return GenerationObservation.LEGACY_IDENTITY(legacyProcessId)
        return when (exactTokens.distinct().size) {
            0 -> GenerationObservation.ABSENT
            1 -> GenerationObservation.EXACT_GENERATION(exactTokens.single())
            else -> GenerationObservation.UNKNOWN
        }
    }

    fun isQuiescent(marker: File): Boolean = when (readMarkerObservation(marker)) {
        is MarkerObservation.PRESENT -> (readMarker(marker)?.state == STATE_QUIESCENT)
        else -> false
    }

    /**
     * Clears a marker only after its exact generation is quiescent. The
     * start-failure path may clear STARTING after proving the token absent;
     * it can never delete a live generation merely because the marker is old.
     */
    fun clear(
        marker: File,
        expectedGenerationToken: String? = null,
    ): FinalizationResult = when (val observation = readMarkerObservation(marker)) {
        MarkerObservation.ABSENT -> if (
            expectedGenerationToken == null || proveGenerationAbsent(expectedGenerationToken)
        ) {
            FinalizationResult(
                QuiescenceState.PROVEN_QUIESCENT_AND_CLEARED,
                expectedGenerationToken,
            )
        } else {
            FinalizationResult(
                QuiescenceState.UNRESOLVED,
                expectedGenerationToken,
            )
        }
        MarkerObservation.MALFORMED,
        MarkerObservation.UNREADABLE -> FinalizationResult(
            QuiescenceState.UNRESOLVED,
            expectedGenerationToken,
        )
        is MarkerObservation.PRESENT -> {
            val snapshot = observation.snapshot
            if (
                expectedGenerationToken != null &&
                    snapshot.generationToken != expectedGenerationToken
            ) {
                FinalizationResult(
                    QuiescenceState.OWNER_OR_GENERATION_CHANGED,
                    snapshot.generationToken,
                )
            } else if (snapshot.state == STATE_QUIESCENT) {
                finishQuiescentMarker(marker, snapshot.generationToken)
            } else if (snapshot.generationToken == null) {
                // A legacy processId-only marker has no anti-reuse
                // incarnation authority.  Do not signal or clear it as if
                // the processId were a generation token.
                FinalizationResult(QuiescenceState.UNRESOLVED)
            } else if (!proveSelectorAbsent(selectorFor(snapshot))) {
                FinalizationResult(
                    QuiescenceState.UNRESOLVED,
                    snapshot.generationToken,
                )
            } else {
                publishQuiescentAndFinish(
                    marker = marker,
                    snapshot = snapshot,
                    expectedGenerationToken = expectedGenerationToken,
                )
            }
        }
    }

    /**
     * Quiesces a marker left by a force-killed or process-dead supervisor.
     * Only processes carrying the immutable generation identity are signalled.
     * Numeric PID/PGID values are never sufficient authority.
     */
    fun recover(marker: File): Boolean = recoverDetailed(marker).isProvenQuiescent

    internal fun recoverDetailed(
        marker: File,
        expectedGenerationToken: String? = null,
    ): FinalizationResult {
        if (!marker.exists()) {
            return if (
                expectedGenerationToken == null || proveGenerationAbsent(expectedGenerationToken)
            ) {
                FinalizationResult(
                    QuiescenceState.PROVEN_QUIESCENT_AND_CLEARED,
                    expectedGenerationToken,
                )
            } else {
                FinalizationResult(
                    QuiescenceState.UNRESOLVED,
                    expectedGenerationToken,
                )
            }
        }
        val snapshot = when (val observation = readMarkerObservation(marker)) {
            MarkerObservation.ABSENT -> return if (
                expectedGenerationToken == null || proveGenerationAbsent(expectedGenerationToken)
            ) {
                FinalizationResult(
                    QuiescenceState.PROVEN_QUIESCENT_AND_CLEARED,
                    expectedGenerationToken,
                )
            } else {
                FinalizationResult(
                    QuiescenceState.UNRESOLVED,
                    expectedGenerationToken,
                )
            }
            MarkerObservation.MALFORMED,
            MarkerObservation.UNREADABLE -> return FinalizationResult(
                QuiescenceState.UNRESOLVED,
                expectedGenerationToken,
            )
            is MarkerObservation.PRESENT -> observation.snapshot
        }
        if (
            expectedGenerationToken != null &&
                snapshot.generationToken != expectedGenerationToken
        ) {
            return FinalizationResult(
                QuiescenceState.OWNER_OR_GENERATION_CHANGED,
                snapshot.generationToken,
            )
        }
        if (snapshot.state == STATE_QUIESCENT) {
            return finishQuiescentMarker(marker, snapshot.generationToken)
        }
        if (snapshot.generationToken == null) {
            return FinalizationResult(QuiescenceState.UNRESOLVED)
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
        if (marker?.exists() == true && readMarker(marker) == null) {
            // The known carrier is present but its identity is unreadable.
            // A token-only /proc scan cannot prove that this non-QUIESCENT
            // marker belongs to the generation being recovered.
            return false
        }
        markerFilesOrNull() ?: return false
        val candidateMarkers = buildList {
            marker?.let { if (it.exists()) add(it) }
            val parts = processId.split(':')
            if (parts.size >= 3 && parts[0] == "download") {
                parts[1].toLongOrNull()?.let { downloadId ->
                    if (parts[2].isBlank()) {
                        downloadMarkerCandidatesForId(downloadId)?.let(::addAll)
                    } else {
                        markerCandidates(
                            downloadId = downloadId,
                            executionId = parts[2],
                        )?.let(::addAll)
                    }
                }
            }
        }.distinctBy { it.absolutePath }
        if (candidateMarkers.any { it.exists() && readMarker(it) == null }) return false
        val exactMarker = candidateMarkers.firstOrNull { candidate ->
            readMarker(candidate)?.generationToken == generationToken
        }
        if (exactMarker != null) {
            return recoverDetailed(exactMarker, generationToken).isProvenQuiescent
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
        ).isProvenQuiescent
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
        val marker = runCatching { markerFor(processId) }.getOrNull() ?: return true
        return marker.exists() && !isQuiescent(marker)
    }

    /**
     * Fail-closed discovery for direct role markers, including malformed or
     * temporarily unreadable carriers.  The marker filename is only used as
     * a conservative candidate filter; destructive recovery still requires
     * the readable exact processId/token fields.
     */
    internal fun hasUnresolvedDownloadExecution(
        downloadId: Long,
        executionId: String? = null,
    ): Boolean {
        if (directory == null) return true
        val files = if (executionId == null || executionId.isBlank()) {
            markerFilesOrNull()?.filter { it.name.startsWith("download_${downloadId}_") }
        } else {
            markerCandidates(downloadId, executionId)
        } ?: return true
        return files.any { marker ->
            if (!marker.isFile || marker.extension != "marker") return@any false
            when (val observation = readMarkerObservation(marker)) {
                is MarkerObservation.PRESENT -> {
                    val parts = observation.snapshot.processId.split(':')
                        parts.size >= 3 &&
                        parts[0] == "download" &&
                        parts[1] == downloadId.toString() &&
                        (executionId == null || executionId.isBlank() || parts[2] == executionId) &&
                        observation.snapshot.state != STATE_QUIESCENT
                }
                MarkerObservation.ABSENT -> false
                MarkerObservation.MALFORMED,
                MarkerObservation.UNREADABLE -> true
            }
        }
    }

    internal fun hasOtherUnresolvedDownloadExecution(
        downloadId: Long,
        expectedProcessId: String,
    ): Boolean {
        if (directory == null) return true
        val prefix = "download_${downloadId}_"
        val files = markerFilesOrNull() ?: return true
        val expectedExecutionId = expectedProcessId.split(':').getOrNull(2)
        val expectedBase = markerFor(expectedProcessId)
            .name
            .removeSuffix(".marker")
        return files.any { marker ->
            if (!marker.isFile || marker.extension != "marker") return@any false
            if (!marker.name.startsWith(prefix)) return@any false
            when (val observation = readMarkerObservation(marker)) {
                is MarkerObservation.PRESENT -> {
                    val parts = observation.snapshot.processId.split(':')
                    parts.size >= 3 &&
                        parts[0] == "download" &&
                        parts[1] == downloadId.toString() &&
                        parts[2] != expectedExecutionId &&
                        observation.snapshot.state != STATE_QUIESCENT
                }
                MarkerObservation.ABSENT -> false
                MarkerObservation.MALFORMED,
                MarkerObservation.UNREADABLE ->
                    !(
                        marker.name == "$expectedBase.marker" ||
                            marker.name.startsWith("${expectedBase}_")
                        )
            }
        }
    }

    /**
     * Conservative filename discovery used when the marker body is malformed
     * or unreadable.  It never grants recovery authority; it only keeps the
     * numeric row/execution candidate visible to the restart reconciler so a
     * later readable pass can retry the exact carrier.
     */
    internal fun downloadMarkerCandidates(context: Context): List<Pair<Long, String>> {
        configure(context)
        return downloadMarkerCandidates()
    }

    internal fun downloadMarkerCandidates(): List<Pair<Long, String>> {
        val files = markerFilesOrNull()
            ?: throw NativeMarkerNamespaceUnavailableException(
                "Download native marker namespace could not be enumerated",
            )
        return files
            .asSequence()
            .filter { it.isFile && it.extension == "marker" }
            .mapNotNull { marker ->
                val stem = marker.name.removeSuffix(".marker")
                if (!stem.startsWith("download_")) return@mapNotNull null
                val remainder = stem.removePrefix("download_")
                val separator = remainder.indexOf('_')
                if (separator <= 0 || separator == remainder.lastIndex) return@mapNotNull null
                val downloadId = remainder.substring(0, separator).toLongOrNull()
                    ?: return@mapNotNull null
                val executionAndRole = remainder.substring(separator + 1)
                val executionId = executionAndRole.substringBefore("_direct_")
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                downloadId to executionId
            }
            .distinct()
            .toList()
    }

    private fun markerCandidates(downloadId: Long, executionId: String): List<File>? {
        if (directory == null) return null
        val base = markerFor("download:$downloadId:$executionId")
            .name
            .removeSuffix(".marker")
        return markerFilesOrNull()?.filter { marker ->
            marker.isFile &&
                marker.extension == "marker" &&
                (
                    marker.name == "$base.marker" ||
                        marker.name.startsWith("${base}_")
                    )
        }
    }

    private fun downloadMarkerCandidatesForId(downloadId: Long): List<File>? {
        if (directory == null) return null
        val prefix = "download_${downloadId}_"
        return markerFilesOrNull()?.filter { marker ->
            marker.isFile &&
                marker.extension == "marker" &&
                marker.name.startsWith(prefix)
        }
    }

    fun downloadProcesses(context: Context): List<DurableDownloadProcess> {
        configure(context)
        return configuredDownloadProcesses()
    }

    fun configuredDownloadProcesses(): List<DurableDownloadProcess> {
        return markerFilesOrNull()
            .orEmpty()
            .filter { it.isFile && it.extension == "marker" }
            .mapNotNull { marker ->
                val snapshot = readMarker(marker) ?: return@mapNotNull null
                val parts = snapshot.processId.split(':')
                if (parts.size < 3 || parts[0] != "download") return@mapNotNull null
                val downloadId = parts[1].toLongOrNull() ?: return@mapNotNull null
                DurableDownloadProcess(
                    processId = snapshot.processId,
                    downloadId = downloadId,
                    executionId = parts[2],
                    generationToken = snapshot.generationToken,
                    nativeRole = parts.drop(3).joinToString(":").ifBlank { null },
                )
            }
    }

    /**
     * Recovers every durable native marker for one exact Download execution.
     * Direct FFmpeg/converter roles use distinct marker names but share the
     * same owner tuple; no numeric Download ID is sufficient authority.
     */
    internal fun recoverDownloadExecution(
        downloadId: Long,
        executionId: String,
    ): Boolean {
        val markers = if (executionId.isBlank()) {
            downloadMarkerCandidatesForId(downloadId)
        } else {
            markerCandidates(downloadId, executionId)
        } ?: return false

        var allProven = true
        markers.forEach { marker ->
            when (val observation = readMarkerObservation(marker)) {
                MarkerObservation.ABSENT -> Unit
                MarkerObservation.MALFORMED,
                MarkerObservation.UNREADABLE -> allProven = false
                is MarkerObservation.PRESENT -> {
                    val snapshot = observation.snapshot
                    if (!belongsToExecution(snapshot.processId, downloadId, executionId)) {
                        // Filename discovery is only candidate visibility. A
                        // body mismatch is never destructive authority.
                        allProven = false
                    } else if (!recoverDetailed(marker, snapshot.generationToken).isProvenQuiescent) {
                        allProven = false
                    }
                }
            }
        }
        if (!allProven) return false

        // A readable-only first pass is not enough. Re-enumerate the exact
        // filename candidate set and fail closed for any opaque or newly
        // appeared non-quiescent carrier. QUIESCENT is finalization-only debt.
        val remaining = if (executionId.isBlank()) {
            downloadMarkerCandidatesForId(downloadId)
        } else {
            markerCandidates(downloadId, executionId)
        } ?: return false
        return remaining.all { marker ->
            when (val observation = readMarkerObservation(marker)) {
                MarkerObservation.ABSENT -> true
                MarkerObservation.MALFORMED,
                MarkerObservation.UNREADABLE -> false
                is MarkerObservation.PRESENT -> {
                    belongsToExecution(observation.snapshot.processId, downloadId, executionId) &&
                        observation.snapshot.state == STATE_QUIESCENT
                }
            }
        }
    }

    /**
     * Includes readable and opaque marker carriers. A QUIESCENT marker is
     * still debt because physical deletion may need a later retry, although
     * it never represents live native work.
     */
    internal fun hasDownloadMarkerDebt(
        downloadId: Long,
        executionId: String? = null,
    ): Boolean {
        if (directory == null) return true
        val files = markerFilesOrNull() ?: return true
        val candidates = if (executionId == null || executionId.isBlank()) {
            files.filter { it.name.startsWith("download_${downloadId}_") }
        } else {
            val base = markerFor("download:$downloadId:$executionId")
                .name
                .removeSuffix(".marker")
            files.filter { marker ->
                marker.name == "$base.marker" ||
                    marker.name.startsWith("${base}_")
            }
        }
        return candidates.any { it.isFile && it.extension == "marker" }
    }

    /**
     * Publishes RUNNING for a direct native Process after start.  The token
     * was already durable in STARTING before ProcessBuilder.start(), so a
     * scan/read failure leaves an exact recovery carrier rather than clearing
     * an unknown process.
     */
    internal fun publishDirectProcessRunning(
        prepared: PreparedProcess,
    ): Boolean {
        val snapshot = when (val observation = readMarkerObservation(prepared.marker)) {
            is MarkerObservation.PRESENT -> observation.snapshot
            else -> return false
        }
        if (snapshot.generationToken != prepared.generationToken) return false
        return when (val scan = scanGeneration(
            GenerationSelector(NATIVE_GENERATION_ENVIRONMENT, prepared.generationToken),
        )) {
            ProcScan.Unavailable -> false
            is ProcScan.Complete -> {
                val process = scan.processes.firstOrNull() ?: return false
                runCatching {
                    writeMarker(
                        prepared.marker,
                        snapshot.copy(
                            state = STATE_RUNNING,
                            supervisorPid = null,
                            supervisorStartTime = null,
                            childPid = process.pid,
                            childStartTime = process.startTime,
                            pgid = process.processGroupId,
                            pgidStartTime = process.startTime,
                        ),
                    )
                    readMarker(prepared.marker)?.let {
                        it.generationToken == prepared.generationToken &&
                            it.state == STATE_RUNNING
                    } == true
                }.getOrDefault(false)
            }
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

    private fun markerFilesOrNull(): List<File>? {
        if (markerEnumerationFailureForTesting) return null
        val root = directory ?: return null
        return root.listFiles()?.toList()
    }

    private fun belongsToExecution(
        processId: String,
        downloadId: Long,
        executionId: String,
    ): Boolean {
        val parts = processId.split(':')
        return parts.size >= 3 &&
            parts[0] == "download" &&
            parts[1] == downloadId.toString() &&
            (executionId.isBlank() || parts[2] == executionId)
    }

    private fun recoverSelector(
        marker: File?,
        selector: GenerationSelector,
        expectedGenerationToken: String?,
    ): FinalizationResult {
        var scan = scanGeneration(selector)
        if (scan is ProcScan.Unavailable) {
            return FinalizationResult(
                QuiescenceState.UNRESOLVED,
                expectedGenerationToken,
            )
        }
        if ((scan as ProcScan.Complete).processes.isEmpty()) {
            return marker?.let {
                clearMarkerIfExact(it, expectedGenerationToken)
            } ?: FinalizationResult(
                QuiescenceState.PROVEN_QUIESCENT_AND_CLEARED,
                expectedGenerationToken,
            )
        }

        signalProcesses(scan.processes, selector, OsConstants.SIGTERM)
        if (awaitGenerationGone(selector)) {
            return marker?.let {
                clearMarkerIfExact(it, expectedGenerationToken)
            } ?: FinalizationResult(
                QuiescenceState.PROVEN_QUIESCENT_AND_CLEARED,
                expectedGenerationToken,
            )
        }

        scan = scanGeneration(selector)
        if (scan is ProcScan.Unavailable) {
            return FinalizationResult(
                QuiescenceState.UNRESOLVED,
                expectedGenerationToken,
            )
        }
        signalProcesses((scan as ProcScan.Complete).processes, selector, OsConstants.SIGKILL)
        if (!awaitGenerationGone(selector)) {
            return FinalizationResult(
                QuiescenceState.UNRESOLVED,
                expectedGenerationToken,
            )
        }
        return marker?.let {
            clearMarkerIfExact(it, expectedGenerationToken)
        } ?: FinalizationResult(
            QuiescenceState.PROVEN_QUIESCENT_AND_CLEARED,
            expectedGenerationToken,
        )
    }

    private fun clearMarkerIfExact(
        marker: File,
        expectedGenerationToken: String?,
    ): FinalizationResult {
        if (!marker.exists()) {
            return if (
                expectedGenerationToken == null || proveGenerationAbsent(expectedGenerationToken)
            ) {
                FinalizationResult(
                    QuiescenceState.PROVEN_QUIESCENT_AND_CLEARED,
                    expectedGenerationToken,
                )
            } else {
                FinalizationResult(
                    QuiescenceState.UNRESOLVED,
                    expectedGenerationToken,
                )
            }
        }
        val snapshot = readMarker(marker) ?: return FinalizationResult(
            QuiescenceState.UNRESOLVED,
            expectedGenerationToken,
        )
        if (
            expectedGenerationToken != null &&
                snapshot.generationToken != expectedGenerationToken
        ) {
            return FinalizationResult(
                QuiescenceState.OWNER_OR_GENERATION_CHANGED,
                snapshot.generationToken,
            )
        }
        return if (snapshot.state == STATE_QUIESCENT) {
            finishQuiescentMarker(marker, snapshot.generationToken)
        } else {
            clear(marker, expectedGenerationToken)
        }
    }

    private fun publishQuiescentAndFinish(
        marker: File,
        snapshot: MarkerSnapshot,
        expectedGenerationToken: String?,
    ): FinalizationResult {
        if (
            expectedGenerationToken != null &&
                snapshot.generationToken != expectedGenerationToken
        ) {
            return FinalizationResult(
                QuiescenceState.OWNER_OR_GENERATION_CHANGED,
                snapshot.generationToken,
            )
        }
        val token = snapshot.generationToken ?: expectedGenerationToken
        return try {
            writeMarker(marker, snapshot.copy(state = STATE_QUIESCENT))
            val published = readMarker(marker)
            if (
                published?.state != STATE_QUIESCENT ||
                    (expectedGenerationToken != null &&
                        published.generationToken != expectedGenerationToken)
            ) {
                FinalizationResult(QuiescenceState.UNRESOLVED, token)
            } else {
                finishQuiescentMarker(marker, published.generationToken)
            }
        } catch (_: Exception) {
            FinalizationResult(QuiescenceState.UNRESOLVED, token)
        }
    }

    private fun finishQuiescentMarker(
        marker: File,
        generationToken: String?,
    ): FinalizationResult {
        return if (deleteMarker(marker)) {
            FinalizationResult(
                QuiescenceState.PROVEN_QUIESCENT_AND_CLEARED,
                generationToken,
            )
        } else {
            FinalizationResult(
                QuiescenceState.PROVEN_QUIESCENT_CLEANUP_PENDING,
                generationToken,
            )
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

    private fun readMarker(marker: File): MarkerSnapshot? = when (
        val observation = readMarkerObservation(marker)
    ) {
        is MarkerObservation.PRESENT -> observation.snapshot
        else -> null
    }

    private fun readMarkerObservation(marker: File): MarkerObservation {
        if (
            markerReadFailureForTesting ||
            markerReadFailurePathForTesting == marker.absolutePath
        ) {
            return MarkerObservation.UNREADABLE
        }
        if (!marker.exists()) return MarkerObservation.ABSENT
        if (!marker.isFile) return MarkerObservation.MALFORMED
        val lines = try {
            marker.readLines(StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return MarkerObservation.UNREADABLE
        }
        if (lines.isEmpty()) return MarkerObservation.MALFORMED
        val values = lines
            .drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null
                else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        val processId = values[PROCESS_ID_KEY]?.takeIf { it.isNotBlank() }
            ?: return MarkerObservation.MALFORMED
        val state = lines.firstOrNull().orEmpty()
        if (
            state !in setOf(
                STATE_STARTING,
                STATE_SUPERVISOR_STARTED,
                STATE_LAUNCHING_CHILD,
                STATE_RUNNING,
                STATE_QUIESCENT,
            )
        ) {
            return MarkerObservation.MALFORMED
        }
        return MarkerObservation.PRESENT(
            MarkerSnapshot(
                state = state,
                processId = processId,
                generationToken = values[GENERATION_TOKEN_KEY]?.takeIf { it.isNotBlank() },
                supervisorPid = values["supervisorPid"]?.toLongOrNull(),
                supervisorStartTime = values["supervisorStartTime"]?.toLongOrNull(),
                childPid = values["childPid"]?.toLongOrNull(),
                childStartTime = values["childStartTime"]?.toLongOrNull(),
                pgid = values["pgid"]?.toLongOrNull(),
                pgidStartTime = values["pgidStartTime"]?.toLongOrNull(),
            ),
        )
    }

    private fun writeMarker(marker: File, snapshot: MarkerSnapshot) {
        check(!markerWriteFailureForTesting) {
            "Injected native marker publication failure"
        }
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
        if (markerDeleteFailureForTesting) return false
        if (!marker.exists()) return true
        return marker.delete() || !marker.exists()
    }
}
