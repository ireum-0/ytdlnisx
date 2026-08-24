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
        val descendantBarrier: File?,
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
    values = [state, "processId=" + os.environ.get("YTDLNISX_PROCESS_ID", "")]
    if pgid is not None:
        values.append("pgid=" + str(pgid))
    temporary = marker + ".tmp"
    with open(temporary, "w") as stream:
        stream.write("\n".join(values) + "\n")
    os.replace(temporary, marker)

child = None
write_marker("STARTING")

def stop_group():
    if child is None:
        return
    try:
        os.killpg(child.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        child.wait(timeout=4)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(child.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        child.wait()

def stop(signum, frame):
    stop_group()
    write_marker("QUIESCENT")
    raise SystemExit(128 + signum)

signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
child = subprocess.Popen(command, start_new_session=True)
write_marker("RUNNING", os.getpgid(child.pid))
try:
    exit_code = child.wait()
finally:
    stop_group()
    write_marker("QUIESCENT")
sys.exit(exit_code)
""".trimIndent()

    private val idProcessMap =
        Collections.synchronizedMap(HashMap<String, TrackedProcess>())
    private val allowedConfigFilesByRequest =
        Collections.synchronizedMap(WeakHashMap<YoutubeDLRequest, MutableSet<File>>())
    private val initLock = Any()

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

        if (
            processId != null &&
                synchronized(idProcessMap) { idProcessMap.containsKey(processId) }
        ) {
            throw YoutubeDLException("Process ID already exists")
        }
        val descendantBarrier = processId?.let {
            YtdlpNativeProcessBarrier.prepare(context, it)
        }
        val processCommand = if (processId == null) {
            command
        } else {
            mutableListOf<String>().apply {
                add(pythonPath.absolutePath)
                add("-c")
                add(PROCESS_SUPERVISOR_SCRIPT)
                add(descendantBarrier!!.absolutePath)
                addAll(command.drop(1))
            }
        }
        val processBuilder = ProcessBuilder(processCommand).redirectErrorStream(redirectErrorStream)
        processBuilder.environment().apply {
            this["LD_LIBRARY_PATH"] = envLibraryPath
            this["SSL_CERT_FILE"] = envSslCertFile
            this["PATH"] = System.getenv("PATH").orEmpty() + ":" + nativeBinDir.absolutePath
            this["PYTHONHOME"] = envPythonHome
            this["HOME"] = envPythonHome
            this["TMPDIR"] = context.cacheDir.absolutePath
            if (processId != null) this["YTDLNISX_PROCESS_ID"] = processId
        }

        val startTime = System.currentTimeMillis()
        val outBuffer = StringBuffer()
        val errBuffer = StringBuffer()
        val process = try {
            processBuilder.start()
        } catch (e: IOException) {
            descendantBarrier?.let { runCatching { YtdlpNativeProcessBarrier.clear(it) } }
            throw YoutubeDLException(e)
        }

        if (processId != null) {
            val duplicate = synchronized(idProcessMap) {
                if (idProcessMap.containsKey(processId)) {
                    true
                } else {
                    idProcessMap[processId] = TrackedProcess(process, descendantBarrier)
                    false
                }
            }
            if (duplicate) {
                // Do not overwrite a live process registered by another
                // exact execution.  The newly-started process is not
                // published as an owner, so terminate it before reporting
                // the identity collision.
                ProcessQuiescence.requestTermination(
                    process,
                    terminationProof = {
                        descendantBarrier == null ||
                            YtdlpNativeProcessBarrier.isQuiescent(descendantBarrier)
                    },
                )
                throw YoutubeDLException("Process ID already exists")
            }
        }
        onProcessRegistered?.invoke()

        val stdOutProcessor = ProgressStreamReader(outBuffer, process.inputStream, callback)
        val stdErrProcessor = StreamCollector(errBuffer, process.errorStream)
        val exitCode = try {
            stdOutProcessor.join()
            stdErrProcessor.join()
            process.waitFor()
        } catch (e: InterruptedException) {
            val quiesced = processId?.let { destroyTrackedProcess(it) }
                ?: ProcessQuiescence.requestTermination(process)
            if (processId != null && quiesced) {
                synchronized(idProcessMap) {
                    if (idProcessMap[processId]?.process === process) {
                        idProcessMap.remove(processId)
                    }
                }
            }
            throw e
        }

        val out = outBuffer.toString()
        val err = errBuffer.toString()
        if (exitCode > 0) {
            if (processId != null && !isExactProcessRegistered(processId, process)) {
                throw CanceledException()
            }
            if (!ignoreErrors(request, out)) {
                processId?.let { removeExactProcess(it, process) }
                val errorOutput = err.ifBlank { out }.ifBlank { "yt-dlp exited with code $exitCode" }
                throw YoutubeDLException(errorOutput)
            }
        }
        processId?.let { removeExactProcess(it, process) }

        return YoutubeDLResponse(command, exitCode, System.currentTimeMillis() - startTime, out, err)
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
        return inMemory || YtdlpNativeProcessBarrier.configuredDownloadProcesses()
            .any { it.downloadId == downloadId && YtdlpNativeProcessBarrier.hasUnresolved(it.processId) }
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
        return inMemory || YtdlpNativeProcessBarrier.configuredDownloadProcesses()
            .any {
                it.downloadId == downloadId &&
                    it.processId != expectedProcessId &&
                    YtdlpNativeProcessBarrier.hasUnresolved(it.processId)
            }
    }

    /** Test seam that registers a fake in the same production process map. */
    internal fun registerProcessForTesting(processId: String, process: Process) {
        synchronized(idProcessMap) {
            idProcessMap[processId] = TrackedProcess(process, null)
        }
    }

    internal fun clearProcessForTesting(processId: String) {
        synchronized(idProcessMap) {
            idProcessMap.remove(processId)
        }
    }

    private fun isExactProcessRegistered(processId: String, process: Process): Boolean =
        synchronized(idProcessMap) { idProcessMap[processId]?.process === process }

    private fun removeExactProcess(processId: String, process: Process) {
        synchronized(idProcessMap) {
            val tracked = idProcessMap[processId]
            if (
                tracked?.process === process &&
                    (tracked.descendantBarrier == null ||
                        YtdlpNativeProcessBarrier.isQuiescent(tracked.descendantBarrier))
            ) {
                idProcessMap.remove(processId)
                tracked.descendantBarrier?.let {
                    runCatching { YtdlpNativeProcessBarrier.clear(it) }
                }
            }
        }
    }

    private fun destroyTrackedProcess(processId: String): Boolean {
        val tracked = synchronized(idProcessMap) { idProcessMap[processId] }
        if (tracked == null) {
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
        val acknowledged = ProcessQuiescence.requestTermination(
            process = tracked.process,
            terminationProof = {
                tracked.descendantBarrier == null ||
                    YtdlpNativeProcessBarrier.isQuiescent(tracked.descendantBarrier)
            },
        )
        val recovered = if (acknowledged) {
            true
        } else {
            tracked.descendantBarrier?.let(YtdlpNativeProcessBarrier::recover) ?: false
        }
        if (recovered) {
            synchronized(idProcessMap) {
                if (idProcessMap[processId]?.process === tracked.process) {
                    idProcessMap.remove(processId)
                }
            }
            tracked.descendantBarrier?.let { runCatching { YtdlpNativeProcessBarrier.clear(it) } }
        }
        return recovered
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
            } catch (e: IOException) {
                Log.e(TAG, "failed to read stream", e)
            }
        }
    }

    private class ProgressStreamReader(
        private val buffer: StringBuffer,
        private val stream: InputStream,
        private val callback: ((Float, Long, String) -> Unit)?
    ) : Thread() {
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
            } catch (e: IOException) {
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


