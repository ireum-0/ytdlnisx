package com.ireum.ytdl.util.extractors.ytdlp

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.CanceledException
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import com.ireum.ytdl.util.process.ProcessQuiescence
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.WeakHashMap
import java.util.regex.Pattern

object YoutubeDLCompat {
    private const val TAG = "YoutubeDLCompat"
    private const val BASE_NAME = "youtubedl-android"
    private const val PACKAGES_ROOT = "packages"
    private const val PYTHON_DIR_NAME = "python"
    private const val FFMPEG_DIR_NAME = "ffmpeg"
    private const val ARIA2C_DIR_NAME = "aria2c"
    private const val PYTHON_BIN_NAME = "libpython.so"
    private const val QUICKJS_BIN_NAME = "libqjs.so"
    private const val YTDLP_BIN_NAME = "yt-dlp"

    private data class TrackedProcess(
        val process: Process,
        val descendantBarrier: YtdlpNativeProcessBarrier.PreparedProcess?,
    )

    internal data class ExecutionResult(
        val response: YoutubeDLResponse,
        val nativeFinalization: YtdlpNativeProcessBarrier.FinalizationResult,
    ) {
        val nativeQuiescent: Boolean
            get() = nativeFinalization.isProvenQuiescent
    }

    /**
     * Retains the root extraction failure while exact native recovery is
     * still pending.  Callers must not enter ordinary retry/cleanup paths
     * until the attached finalization result is positive.
     */
    internal class NativeExecutionFailure(
        val processId: String?,
        val finalization: YtdlpNativeProcessBarrier.FinalizationResult,
        val originalFailure: Throwable,
    ) : IllegalStateException(
        "Native generation was not quiescent for processId=$processId " +
            "state=${finalization.state}",
        originalFailure,
    )

    /**
     * The supervisor receives SIGTERM, terminates the exact child process
     * group, waits for the yt-dlp child, and only then records QUIESCENT.  The
     * Java root Process therefore has a durable descendant acknowledgement;
     * a force-killed root leaves the marker for startup group recovery.
     */
    private val PROCESS_SUPERVISOR_SCRIPT = """
import os
import signal
import subprocess
import sys
import time

marker = sys.argv[1]
command = sys.argv[2:]

def write_marker(state, pgid=None):
    generation = os.environ.get("YTDLNISX_NATIVE_GENERATION", "")
    supervisor_pid = os.getpid()
    supervisor_start_time = read_start_time(supervisor_pid)
    values = [
        state,
        "processId=" + os.environ.get("YTDLNISX_PROCESS_ID", ""),
        "generationToken=" + generation,
        "supervisorPid=" + str(supervisor_pid),
        "supervisorStartTime=" + str(supervisor_start_time),
    ]
    if pgid is not None:
        values.append("childPid=" + str(child.pid))
        if child_start_time is not None:
            values.append("childStartTime=" + str(child_start_time))
        values.append("pgid=" + str(pgid))
        if child_pgid_start_time is not None:
            values.append("pgidStartTime=" + str(child_pgid_start_time))
    temporary = marker + ".tmp"
    with open(temporary, "w") as stream:
        stream.write("\n".join(values) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, marker)

def read_start_time(pid):
    with open("/proc/" + str(pid) + "/stat", "r") as stream:
        value = stream.read()
    close_paren = value.rfind(") ")
    if close_paren < 0:
        raise RuntimeError("invalid /proc stat")
    fields = value[close_paren + 2:].split()
    return int(fields[19])

child = None
child_pgid = None
child_start_time = None
child_pgid_start_time = None
write_marker("SUPERVISOR_STARTED")

def read_environment(pid):
    with open("/proc/" + str(pid) + "/environ", "rb") as stream:
        raw = stream.read()
    values = {}
    for entry in raw.split(b"\0"):
        if b"=" not in entry:
            continue
        key, value = entry.split(b"=", 1)
        values[key.decode("latin1")] = value.decode("latin1")
    return values

def read_process_identity(pid):
    with open("/proc/" + str(pid) + "/stat", "r") as stream:
        value = stream.read()
    close_paren = value.rfind(") ")
    if close_paren < 0:
        raise RuntimeError("invalid /proc stat")
    fields = value[close_paren + 2:].split()
    return int(fields[19]), read_environment(pid)

def generation_processes():
    expected = os.environ.get("YTDLNISX_NATIVE_GENERATION", "")
    if not expected:
        raise RuntimeError("missing native generation")
    result = []
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        pid = int(entry)
        if pid == os.getpid():
            continue
        try:
            start_time, environment = read_process_identity(pid)
        except FileNotFoundError:
            continue
        if environment.get("YTDLNISX_NATIVE_GENERATION") == expected:
            result.append((pid, start_time))
    return result

def generation_exists():
    return len(generation_processes()) > 0

def signal_generation(signum):
    expected = os.environ.get("YTDLNISX_NATIVE_GENERATION", "")
    for pid, start_time in generation_processes():
        try:
            current_start_time, environment = read_process_identity(pid)
            if current_start_time != start_time:
                continue
            if environment.get("YTDLNISX_NATIVE_GENERATION") != expected:
                continue
            os.kill(pid, signum)
        except FileNotFoundError:
            continue
        except ProcessLookupError:
            continue

def stop_group():
    if child is None:
        return True
    try:
        signal_generation(signal.SIGTERM)
    except (FileNotFoundError, ProcessLookupError):
        return True
    except BaseException:
        return False
    deadline = time.monotonic() + 4
    try:
        while generation_exists() and time.monotonic() < deadline:
            try:
                child.wait(timeout=0.05)
            except subprocess.TimeoutExpired:
                pass
    except BaseException:
        return False
    try:
        if not generation_exists():
            return True
    except BaseException:
        return False
    try:
        signal_generation(signal.SIGKILL)
    except (FileNotFoundError, ProcessLookupError):
        return True
    except BaseException:
        return False
    try:
        child.wait(timeout=0.2)
    except subprocess.TimeoutExpired:
        pass
    try:
        return not generation_exists()
    except (FileNotFoundError, ProcessLookupError):
        return True
    except BaseException:
        return False

def stop(signum, frame):
    raise SystemExit(128 + signum)

signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
write_marker("LAUNCHING_CHILD")
try:
    child = subprocess.Popen(command, start_new_session=True)
except BaseException:
    # The launch marker remains the exact recovery carrier when exec fails.
    # It is safe to publish QUIESCENT only after the immutable token scan
    # proves that no external generation was created.
    try:
        if not generation_exists():
            write_marker("QUIESCENT")
    except BaseException:
        pass
    raise
child_pgid = child.pid
try:
    child_pgid = os.getpgid(child.pid)
except ProcessLookupError:
    pass
try:
    child_start_time = read_start_time(child.pid)
except (FileNotFoundError, ProcessLookupError):
    child_start_time = None
try:
    child_pgid_start_time = read_start_time(child_pgid)
except (FileNotFoundError, ProcessLookupError):
    child_pgid_start_time = None
write_marker("RUNNING", child_pgid)
try:
    exit_code = child.wait()
finally:
    if stop_group():
        write_marker("QUIESCENT", child_pgid)
    else:
        # Leave a positive native barrier for a later exact process-group
        # recovery pass; root-process exit is not a descendant proof.
        write_marker("RUNNING", child_pgid)
sys.exit(exit_code)
""".trimIndent()

    /** Shared production command builder; the Android execution path uses it directly. */
    internal fun buildSupervisorCommand(
        pythonPath: String,
        markerPath: String,
        command: List<String>,
    ): List<String> = buildList {
        add(pythonPath)
        add("-c")
        add(PROCESS_SUPERVISOR_SCRIPT)
        add(markerPath)
        addAll(command)
    }

    internal fun supervisorScriptForTesting(): String = PROCESS_SUPERVISOR_SCRIPT

    private val idProcessMap =
        Collections.synchronizedMap(HashMap<String, TrackedProcess>())
    /**
     * A ProcessBuilder launch is itself a short-lived native authority.  Keep
     * the process ID reserved from the duplicate check through exact registry
     * publication so a second caller cannot recover/replace STARTING between
     * prepare() and ProcessBuilder.start().
     */
    private val launchingProcessIds = mutableSetOf<String>()
    private val allowedConfigFilesByRequest =
        Collections.synchronizedMap(WeakHashMap<YoutubeDLRequest, MutableSet<File>>())
    private val initLock = Any()

    /** Production execute-path seams used only by command/protocol tests. */
    @Volatile
    internal var processStarterOverrideForTesting:
        ((List<String>, Map<String, String>, Boolean) -> Process)? = null

    @Volatile
    internal var runtimeLayoutOverrideForTesting: RuntimeLayout? = null

    data class RuntimeLayout(
        val baseDir: File,
        val packagesDir: File,
        val nativeBinDir: File,
        val pythonBinary: File,
        val quickJsBinary: File,
        val ytdlpBinary: File,
        val ffmpegBinary: File,
        val ffprobeBinary: File,
        val aria2cBinary: File,
        val pythonHome: File,
        val pythonLibraryDir: File,
        val ffmpegLibraryDir: File,
        val aria2cLibraryDir: File,
        val sslCertificate: File
    )

    fun runtimeLayout(context: Context): RuntimeLayout {
        runtimeLayoutOverrideForTesting?.let { return it }
        val baseDir = File(context.noBackupFilesDir, BASE_NAME)
        val packagesDir = File(baseDir, PACKAGES_ROOT)
        val nativeBinDir = File(context.applicationInfo.nativeLibraryDir)
        val pythonDir = File(packagesDir, PYTHON_DIR_NAME)
        val ffmpegDir = File(packagesDir, FFMPEG_DIR_NAME)
        val aria2cDir = File(packagesDir, ARIA2C_DIR_NAME)
        return RuntimeLayout(
            baseDir = baseDir,
            packagesDir = packagesDir,
            nativeBinDir = nativeBinDir,
            pythonBinary = File(nativeBinDir, PYTHON_BIN_NAME),
            quickJsBinary = File(nativeBinDir, QUICKJS_BIN_NAME),
            ytdlpBinary = File(File(baseDir, YTDLP_BIN_NAME), YTDLP_BIN_NAME),
            ffmpegBinary = File(nativeBinDir, "libffmpeg.so"),
            ffprobeBinary = File(nativeBinDir, "libffprobe.so"),
            aria2cBinary = File(nativeBinDir, "libaria2c.so"),
            pythonHome = File(pythonDir, "usr"),
            pythonLibraryDir = File(pythonDir, "usr/lib"),
            ffmpegLibraryDir = File(ffmpegDir, "usr/lib"),
            aria2cLibraryDir = File(aria2cDir, "usr/lib"),
            sslCertificate = File(pythonDir, "usr/etc/tls/cert.pem")
        )
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun execute(
        context: Context,
        request: YoutubeDLRequest,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        callback: ((Float, Long, String) -> Unit)? = null,
        onProcessRegistered: (() -> Unit)? = null,
    ): YoutubeDLResponse {
        val result = executeWithQuiescence(
            context = context,
            request = request,
            processId = processId,
            redirectErrorStream = redirectErrorStream,
            callback = callback,
            onProcessRegistered = onProcessRegistered,
        )
        if (processId != null && !result.nativeQuiescent) {
            throw NativeExecutionFailure(
                processId = processId,
                finalization = result.nativeFinalization,
                originalFailure = IllegalStateException(
                    "Native quiescence was unresolved after root execution",
                ),
            )
        }
        return result.response
    }

    /**
     * Compatibility execution result for callers that own a durable native
     * sidecar. Root-process success is intentionally separate from exact
     * descendant quiescence so Download cannot publish semantic success while
     * its native generation remains addressable.
     */
    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    internal fun executeWithQuiescence(
        context: Context,
        request: YoutubeDLRequest,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        callback: ((Float, Long, String) -> Unit)? = null,
        onProcessRegistered: (() -> Unit)? = null,
    ): ExecutionResult {
        val runtime = runtimeLayout(context)
        val nativeBinDir = runtime.nativeBinDir
        val pythonPath = runtime.pythonBinary
        val quickJsPath = runtime.quickJsBinary
        val ytdlpPath = runtime.ytdlpBinary
        val envLibraryPath = listOf(
            runtime.pythonLibraryDir.absolutePath,
            runtime.ffmpegLibraryDir.absolutePath,
            runtime.aria2cLibraryDir.absolutePath
        ).joinToString(":")
        val envSslCertFile = runtime.sslCertificate.absolutePath
        val envPythonHome = runtime.pythonHome.absolutePath

        ensureRuntimeInitialized(context, pythonPath, quickJsPath, ytdlpPath)
        checkRequiredBinary(pythonPath, "python")
        checkRequiredBinary(quickJsPath, "quickjs")
        checkRequiredBinary(ytdlpPath, "yt-dlp")

        val args = sanitizeArguments(
            context,
            request.buildCommand(),
            takeAllowedAppGeneratedConfigFiles(request)
        )
        val command = mutableListOf<String>()
        command.add(pythonPath.absolutePath)
        command.add(ytdlpPath.absolutePath)
        command.addAll(args)

        var launchReservationOwned = false
        if (processId != null) {
            synchronized(idProcessMap) {
                if (
                    idProcessMap.containsKey(processId) ||
                        !launchingProcessIds.add(processId)
                ) {
                    throw YoutubeDLException("Process ID already exists")
                }
                launchReservationOwned = true
            }
        }
        val descendantBarrier = try {
            processId?.let {
                YtdlpNativeProcessBarrier.prepare(context, it)
            }
        } catch (failure: Throwable) {
            if (processId != null && launchReservationOwned) {
                synchronized(idProcessMap) { launchingProcessIds.remove(processId) }
            }
            if (processId != null) {
                throw NativeExecutionFailure(
                    processId = processId,
                    finalization = YtdlpNativeProcessBarrier.FinalizationResult(
                        YtdlpNativeProcessBarrier.QuiescenceState.UNRESOLVED,
                    ),
                    originalFailure = failure,
                )
            }
            throw failure
        }
        val processCommand: List<String>
        val processBuilder: ProcessBuilder
        try {
            processCommand = if (processId == null) {
                command
            } else {
                // The supervisor must launch the same command as the normal
                // path: bundled Python first, writable app-data yt-dlp second.
                // Dropping command[0] would directly exec yt-dlp from writable
                // app-private storage and violate Android W^X.
                buildSupervisorCommand(
                    pythonPath = pythonPath.absolutePath,
                    markerPath = descendantBarrier!!.marker.absolutePath,
                    command = command,
                )
            }
            processBuilder = ProcessBuilder(processCommand).redirectErrorStream(redirectErrorStream)
            processBuilder.environment().apply {
                this["LD_LIBRARY_PATH"] = envLibraryPath
                this["SSL_CERT_FILE"] = envSslCertFile
                this["PATH"] = System.getenv("PATH").orEmpty() + ":" + nativeBinDir.absolutePath
                this["PYTHONHOME"] = envPythonHome
                this["HOME"] = envPythonHome
                this["TMPDIR"] = context.cacheDir.absolutePath
                if (processId != null) {
                    this["YTDLNISX_PROCESS_ID"] = processId
                    this["YTDLNISX_NATIVE_GENERATION"] = descendantBarrier!!.generationToken
                }
            }
        } catch (failure: Throwable) {
            if (processId != null && launchReservationOwned) {
                val finalization = descendantBarrier?.let {
                    YtdlpNativeProcessBarrier.recoverDetailed(it.marker, it.generationToken)
                } ?: YtdlpNativeProcessBarrier.FinalizationResult(
                    YtdlpNativeProcessBarrier.QuiescenceState.UNRESOLVED,
                )
                synchronized(idProcessMap) { launchingProcessIds.remove(processId) }
                if (!finalization.isProvenQuiescent) {
                    throw NativeExecutionFailure(processId, finalization, failure)
                }
            }
            throw failure
        }

        val startTime = System.currentTimeMillis()
        val outBuffer = StringBuffer()
        val errBuffer = StringBuffer()
        var process: Process? = null
        var rootFailure: Throwable? = null
        var response: YoutubeDLResponse? = null
        try {
            process = try {
                processStarterOverrideForTesting?.invoke(
                    processCommand.toList(),
                    processBuilder.environment().toMap(),
                    redirectErrorStream,
                ) ?: processBuilder.start()
            } catch (e: IOException) {
                throw YoutubeDLException(e)
            }

            if (processId != null) {
                val duplicate = synchronized(idProcessMap) {
                    if (idProcessMap.containsKey(processId)) {
                        true
                    } else {
                        idProcessMap[processId] = TrackedProcess(process, descendantBarrier)
                        launchingProcessIds.remove(processId)
                        launchReservationOwned = false
                        false
                    }
                }
                if (duplicate) {
                    // The newly-started process has the new immutable token;
                    // finalize only that token and never overwrite the older
                    // registry entry.
                    throw YoutubeDLException("Process ID already exists")
                }
            }
            onProcessRegistered?.invoke()

            val stdOutProcessor = ProgressStreamReader(outBuffer, process.inputStream, callback)
            val stdErrProcessor = StreamCollector(errBuffer, process.errorStream)
            stdOutProcessor.join()
            stdErrProcessor.join()
            stdOutProcessor.failure?.let { throw it }
            stdErrProcessor.failure?.let { throw it }
            val exitCode = process.waitFor()

            val out = outBuffer.toString()
            val err = errBuffer.toString()
            if (exitCode > 0) {
                if (processId != null && !isExactProcessRegistered(processId, process)) {
                    throw CanceledException()
                }
                if (!ignoreErrors(request, out)) {
                    val errorOutput = err.ifBlank { out }
                        .ifBlank { "yt-dlp exited with code $exitCode" }
                    throw YoutubeDLException(errorOutput)
                }
            }
            response = YoutubeDLResponse(
                command,
                exitCode,
                System.currentTimeMillis() - startTime,
                out,
                err,
            )
        } catch (failure: Throwable) {
            rootFailure = failure
        }

        val finalization = try {
            if (processId != null) {
                finalizeTrackedProcess(
                    processId = processId,
                    process = process,
                    prepared = descendantBarrier,
                )
            } else if (process != null && ProcessQuiescence.requestTermination(process)) {
                YtdlpNativeProcessBarrier.FinalizationResult(
                    YtdlpNativeProcessBarrier.QuiescenceState.PROVEN_QUIESCENT_AND_CLEARED,
                )
            } else {
                YtdlpNativeProcessBarrier.FinalizationResult(
                    YtdlpNativeProcessBarrier.QuiescenceState.UNRESOLVED,
                )
            }
        } finally {
            if (processId != null && launchReservationOwned) {
                synchronized(idProcessMap) { launchingProcessIds.remove(processId) }
            }
        }

        rootFailure?.let { failure ->
            if (finalization.isProvenQuiescent) throw failure
            throw NativeExecutionFailure(processId, finalization, failure)
        }
        val completedResponse = requireNotNull(response)
        return ExecutionResult(
            response = completedResponse,
            nativeFinalization = finalization,
        )
    }

    fun allowAppGeneratedConfigFile(request: YoutubeDLRequest, configFile: File) {
        val canonicalFile = runCatching { configFile.canonicalFile }.getOrElse { configFile.absoluteFile }
        synchronized(allowedConfigFilesByRequest) {
            val allowedConfigFiles = allowedConfigFilesByRequest.getOrPut(request) { mutableSetOf() }
            allowedConfigFiles.add(canonicalFile)
        }
    }

    fun previewSanitizedArguments(context: Context, request: YoutubeDLRequest): List<String> {
        return sanitizeArguments(
            context,
            request.buildCommand(),
            takeAllowedAppGeneratedConfigFiles(request)
        )
    }

    /**
     * Requests cancellation and retains the exact process entry until its OS
     * termination has been acknowledged.  A false result is fail-closed: the
     * caller must not release the Download execution or make its resources
     * reusable.
     */
    fun destroyProcessById(processId: String): Boolean =
        destroyProcessByIdAndAwait(processId)

    internal fun destroyProcessByIdAndAwait(processId: String): Boolean {
        return destroyTrackedProcess(processId)
    }

    /** Returns whether this exact execution still has a registered yt-dlp process. */
    internal fun hasProcessById(processId: String): Boolean =
        synchronized(idProcessMap) { idProcessMap.containsKey(processId) } ||
            YtdlpNativeProcessBarrier.hasUnresolved(processId)

    /** Returns whether any download-scoped yt-dlp process remains for this row. */
    internal fun hasAnyDownloadProcess(downloadId: Long): Boolean {
        val prefix = "download:$downloadId:"
        val inMemory = synchronized(idProcessMap) {
            idProcessMap.keys.any { it.startsWith(prefix) }
        }
        return inMemory || YtdlpNativeProcessBarrier.hasUnresolvedDownloadExecution(downloadId)
    }

    /** Returns whether another execution, not the expected one, remains registered. */
    internal fun hasOtherDownloadProcess(
        downloadId: Long,
        expectedProcessId: String,
    ): Boolean {
        val prefix = "download:$downloadId:"
        val inMemory = synchronized(idProcessMap) {
            idProcessMap.keys.any { it.startsWith(prefix) && it != expectedProcessId }
        }
        return inMemory || YtdlpNativeProcessBarrier.hasOtherUnresolvedDownloadExecution(
            downloadId,
            expectedProcessId,
        )
    }

    /** Test seam that registers a fake in the same production process map. */
    internal fun registerProcessForTesting(processId: String, process: Process) {
        synchronized(idProcessMap) {
            idProcessMap[processId] = TrackedProcess(process, null)
            launchingProcessIds.remove(processId)
        }
    }

    internal fun clearProcessForTesting(processId: String) {
        synchronized(idProcessMap) {
            idProcessMap.remove(processId)
        }
    }

    private fun isExactProcessRegistered(processId: String, process: Process): Boolean =
        synchronized(idProcessMap) { idProcessMap[processId]?.process === process }

    private fun finalizeTrackedProcess(
        processId: String,
        process: Process?,
        prepared: YtdlpNativeProcessBarrier.PreparedProcess?,
    ): YtdlpNativeProcessBarrier.FinalizationResult {
        val tracked = synchronized(idProcessMap) { idProcessMap[processId] }
        if (process != null && tracked?.process !== process) {
            return prepared?.let {
                YtdlpNativeProcessBarrier.recoverDetailed(
                    it.marker,
                    it.generationToken,
                )
            } ?: YtdlpNativeProcessBarrier.FinalizationResult(
                YtdlpNativeProcessBarrier.QuiescenceState.OWNER_OR_GENERATION_CHANGED,
            )
        }
        val result = prepared?.let {
            // The marker/token scan is the descendant proof for every root
            // exit mode.  It also publishes QUIESCENT before optional delete.
            YtdlpNativeProcessBarrier.recoverDetailed(it.marker, it.generationToken)
        } ?: if (process == null || ProcessQuiescence.requestTermination(process)) {
            YtdlpNativeProcessBarrier.FinalizationResult(
                YtdlpNativeProcessBarrier.QuiescenceState.PROVEN_QUIESCENT_AND_CLEARED,
            )
        } else {
            YtdlpNativeProcessBarrier.FinalizationResult(
                YtdlpNativeProcessBarrier.QuiescenceState.UNRESOLVED,
            )
        }
        if (result.isProvenQuiescent) {
            synchronized(idProcessMap) {
                if (idProcessMap[processId]?.process === process) {
                    idProcessMap.remove(processId)
                }
            }
        }
        return result
    }

    private fun destroyTrackedProcess(processId: String): Boolean {
        val tracked = synchronized(idProcessMap) { idProcessMap[processId] }
        if (tracked == null) {
            if (synchronized(idProcessMap) { launchingProcessIds.contains(processId) }) {
                // A live Java launcher still owns the right to create the
                // exact generation.  Do not clear STARTING underneath it.
                return false
            }
            // Unit/test callers and non-download compatibility paths may
            // cancel an already-absent process before application startup has
            // configured the durable marker directory. There is no marker
            // namespace to inspect in that state; production DownloadWorker
            // and App startup configure it before any recoverable process can
            // be published.
            if (!YtdlpNativeProcessBarrier.isConfigured()) return true
            val recovered = runCatching {
                YtdlpNativeProcessBarrier.markerFor(processId)
            }.mapCatching { YtdlpNativeProcessBarrier.recover(it) }
                // An unaddressable marker is not proof that the native
                // process is gone.  Keep the exact execution fail-closed.
                .getOrElse { false }
            return recovered
        }
        val trackedBarrier = tracked.descendantBarrier
        if (trackedBarrier != null) {
            val currentGenerationToken = runCatching {
                YtdlpNativeProcessBarrier.generationTokenFor(processId)
            }.getOrNull()
            if (
                currentGenerationToken != null &&
                    currentGenerationToken != trackedBarrier.generationToken
            ) {
                // The marker path is shared by the product process ID, so a
                // stale in-memory E1 entry must not pass its old File object
                // to recovery after a newer same-ID generation published a
                // different token. Recover E1 by token only, then remove its
                // exact Java registry entry without touching E2's marker.
                val recovered = YtdlpNativeProcessBarrier.recoverGeneration(
                    processId = processId,
                    generationToken = trackedBarrier.generationToken,
                )
                if (recovered) {
                    synchronized(idProcessMap) {
                        if (idProcessMap[processId]?.process === tracked.process) {
                            idProcessMap.remove(processId)
                        }
                    }
                }
                return recovered
            }
        }
        return finalizeTrackedProcess(
            processId = processId,
            process = tracked.process,
            prepared = tracked.descendantBarrier,
        ).isProvenQuiescent
    }

    private fun sanitizeArguments(
        context: Context,
        originalArgs: List<String>,
        allowedConfigFiles: Set<File>
    ): List<String> {
        val args = YtdlpArgumentPolicy.sanitize(originalArgs, allowedConfigFiles).toMutableList()

        resolveValidFfmpegLocation(context)?.let { ffmpegLocation ->
            args.add(0, ffmpegLocation)
            args.add(0, "--ffmpeg-location")
        }

        if (!YtdlpArgumentPolicy.containsOptionWithValue(args, "--cache-dir")) {
            args.add("--no-cache-dir")
        }

        if (args.contains("libaria2c.so")) {
            args.add("--external-downloader-args")
            args.add("aria2c:--summary-interval=1")
            args.add("--external-downloader-args")
            args.add("aria2c:--ca-certificate=${File(context.noBackupFilesDir, "$BASE_NAME/$PACKAGES_ROOT/$PYTHON_DIR_NAME/usr/etc/tls/cert.pem").absolutePath}")
        }

        args.add("--js-runtimes")
        args.add("quickjs:${File(context.applicationInfo.nativeLibraryDir, QUICKJS_BIN_NAME).absolutePath}")

        return args
    }

    fun stripExternalFfmpegLocationOptions(commandString: String): String {
        return YtdlpArgumentPolicy.stripExternalFfmpegLocationOptions(commandString)
    }

    fun stripExternalFfmpegLocationOptionsWithReport(
        commandString: String
    ): YtdlpArgumentPolicy.CommandStringSanitizeResult {
        return YtdlpArgumentPolicy.stripExternalFfmpegLocationOptionsWithReport(commandString)
    }

    private fun takeAllowedAppGeneratedConfigFiles(request: YoutubeDLRequest): Set<File> {
        return synchronized(allowedConfigFilesByRequest) {
            allowedConfigFilesByRequest.remove(request)?.toSet().orEmpty()
        }
    }

    private fun resolveValidFfmpegLocation(context: Context): String? {
        return runCatching {
            val nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir).canonicalFile
            val ffmpeg = File(nativeLibraryDir, "libffmpeg.so").canonicalFile
            val ffprobe = File(nativeLibraryDir, "libffprobe.so").canonicalFile
            val payloadLibDir = File(
                context.noBackupFilesDir,
                "$BASE_NAME/$PACKAGES_ROOT/$FFMPEG_DIR_NAME/usr/lib"
            ).canonicalFile

            if (
                ffmpeg.parentFile == nativeLibraryDir &&
                ffmpeg.name == "libffmpeg.so" &&
                ffmpeg.isFile &&
                ffmpeg.canExecute() &&
                ffprobe.parentFile == nativeLibraryDir &&
                ffprobe.name == "libffprobe.so" &&
                ffprobe.isFile &&
                ffprobe.canExecute() &&
                payloadLibDir.isDirectory
            ) {
                ffmpeg.absolutePath
            } else {
                null
            }
        }.getOrNull()
    }


    private fun checkRequiredBinary(file: File, label: String) {
        if (!file.exists() || !file.isFile) {
            throw YoutubeDLException("Missing $label runtime at ${file.absolutePath}")
        }
    }

    @Throws(YoutubeDLException::class)
    private fun ensureRuntimeInitialized(
        context: Context,
        pythonPath: File,
        quickJsPath: File,
        ytdlpPath: File
    ) {
        if (pythonPath.isFile && quickJsPath.isFile && ytdlpPath.isFile) {
            return
        }

        synchronized(initLock) {
            if (pythonPath.isFile && quickJsPath.isFile && ytdlpPath.isFile) {
                return
            }

            Log.i(TAG, "yt-dlp runtime missing; initializing library on demand")
            YoutubeDL.getInstance().init(context)
        }
    }

    private fun ignoreErrors(request: YoutubeDLRequest, out: String): Boolean {
        return request.hasOption("--dump-json") && out.isNotEmpty() && request.hasOption("--ignore-errors")
    }

    private class StreamCollector(
        private val buffer: StringBuffer,
        private val stream: InputStream
    ) : Thread() {
        @Volatile
        var failure: Throwable? = null

        init {
            start()
        }

        override fun run() {
            try {
                val input: Reader = InputStreamReader(stream, StandardCharsets.UTF_8)
                var nextChar: Int
                while (input.read().also { nextChar = it } != -1) {
                    buffer.append(nextChar.toChar())
                }
            } catch (e: Throwable) {
                failure = e
                Log.e(TAG, "failed to read stream", e)
            }
        }
    }

    private class ProgressStreamReader(
        private val buffer: StringBuffer,
        private val stream: InputStream,
        private val callback: ((Float, Long, String) -> Unit)?
    ) : Thread() {
        @Volatile
        var failure: Throwable? = null

        private val downloadPattern = Pattern.compile("\\[download\\]\\s+(\\d+\\.\\d)% .* ETA (\\d+):(\\d+)")
        private val aria2Pattern =
            Pattern.compile("\\[#\\w{6}.*\\((\\d*\\.*\\d+)%\\).*?((\\d+)m)*((\\d+)s)*]")
        private val ffmpegPattern = Pattern.compile("size=.*")
        private var progress = -1.0f
        private var eta = -1L

        init {
            start()
        }

        override fun run() {
            try {
                val input: Reader = InputStreamReader(stream, StandardCharsets.UTF_8)
                val currentLine = StringBuilder()
                var nextChar: Int
                while (input.read().also { nextChar = it } != -1) {
                    buffer.append(nextChar.toChar())
                    if (nextChar == '\r'.code || nextChar == '\n'.code) {
                        emit(currentLine.toString())
                        currentLine.setLength(0)
                        continue
                    }
                    currentLine.append(nextChar.toChar())
                }
            } catch (e: Throwable) {
                failure = e
                Log.e(TAG, "failed to read stream", e)
            }
        }

        private fun emit(line: String) {
            callback?.invoke(progressFor(line), etaFor(line), line)
        }

        private fun progressFor(line: String): Float {
            val download = downloadPattern.matcher(line)
            if (download.find()) {
                return download.group(1)!!.toFloat().also { progress = it }
            }
            val aria2 = aria2Pattern.matcher(line)
            if (aria2.find()) {
                return aria2.group(1)!!.toFloat().also { progress = it }
            }
            if (ffmpegPattern.matcher(line).find()) {
                return 99f.also { progress = it }
            }
            return progress
        }

        private fun etaFor(line: String): Long {
            val download = downloadPattern.matcher(line)
            if (download.find()) {
                return toSeconds(download.group(2), download.group(3)).also { eta = it }
            }
            val aria2 = aria2Pattern.matcher(line)
            if (aria2.find()) {
                return toSeconds(aria2.group(3), aria2.group(5)).also { eta = it }
            }
            return eta
        }

        private fun toSeconds(minutes: String?, seconds: String?): Long {
            if (seconds == null) return 0L
            if (minutes == null) return seconds.toLong()
            return minutes.toLong() * 60L + seconds.toLong()
        }
    }
}


