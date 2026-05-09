package com.ireum.ytdl.util.extractors.ytdlp

import android.content.Context
import android.os.Build
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.CanceledException
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
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
    private const val FFMPEG_LOCATION_OPTION = "--ffmpeg-location"
    private val CONFIG_OPTIONS = setOf("--config", "--config-location", "--config-locations")
    private val PROCESS_SPAWNING_OPTIONS = setOf(
        "--exec",
        "--exec-before-download",
        "--exec-after-download",
        "--external-downloader",
        "--downloader",
        "--external-downloader-args",
        "--downloader-args",
        "--postprocessor-args",
        "--ppa",
        "--use-postprocessor"
    )
    private val BLOCKED_EXTERNAL_OPTIONS = CONFIG_OPTIONS + FFMPEG_LOCATION_OPTION + PROCESS_SPAWNING_OPTIONS

    private val idProcessMap = Collections.synchronizedMap(HashMap<String, Process>())
    private val allowedConfigFilesByRequest =
        Collections.synchronizedMap(WeakHashMap<YoutubeDLRequest, MutableSet<File>>())
    private val initLock = Any()

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun execute(
        context: Context,
        request: YoutubeDLRequest,
        processId: String? = null,
        redirectErrorStream: Boolean = false,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse {
        val baseDir = File(context.noBackupFilesDir, BASE_NAME)
        val packagesDir = File(baseDir, PACKAGES_ROOT)
        val nativeBinDir = File(context.applicationInfo.nativeLibraryDir)
        val pythonPath = File(nativeBinDir, PYTHON_BIN_NAME)
        val quickJsPath = File(nativeBinDir, QUICKJS_BIN_NAME)
        val ytdlpPath = File(File(baseDir, YTDLP_BIN_NAME), YTDLP_BIN_NAME)
        val pythonDir = File(packagesDir, PYTHON_DIR_NAME)
        val ffmpegDir = File(packagesDir, FFMPEG_DIR_NAME)
        val aria2cDir = File(packagesDir, ARIA2C_DIR_NAME)
        val envLibraryPath = listOf(
            File(pythonDir, "usr/lib").absolutePath,
            File(ffmpegDir, "usr/lib").absolutePath,
            File(aria2cDir, "usr/lib").absolutePath
        ).joinToString(":")
        val envSslCertFile = File(pythonDir, "usr/etc/tls/cert.pem").absolutePath
        val envPythonHome = File(pythonDir, "usr").absolutePath

        ensureRuntimeInitialized(context, pythonPath, quickJsPath, ytdlpPath)
        checkRequiredBinary(pythonPath, "python")
        checkRequiredBinary(quickJsPath, "quickjs")
        checkRequiredBinary(ytdlpPath, "yt-dlp")

        if (processId != null && idProcessMap.containsKey(processId)) {
            throw YoutubeDLException("Process ID already exists")
        }

        val args = sanitizeArguments(
            context,
            request.buildCommand(),
            takeAllowedAppGeneratedConfigFiles(request)
        )
        val command = mutableListOf<String>()
        command.add(pythonPath.absolutePath)
        command.add(ytdlpPath.absolutePath)
        command.addAll(args)

        val processBuilder = ProcessBuilder(command).redirectErrorStream(redirectErrorStream)
        processBuilder.environment().apply {
            this["LD_LIBRARY_PATH"] = envLibraryPath
            this["SSL_CERT_FILE"] = envSslCertFile
            this["PATH"] = System.getenv("PATH") + ":" + nativeBinDir.absolutePath
            this["PYTHONHOME"] = envPythonHome
            this["HOME"] = envPythonHome
            this["TMPDIR"] = context.cacheDir.absolutePath
        }

        val startTime = System.currentTimeMillis()
        val outBuffer = StringBuffer()
        val errBuffer = StringBuffer()
        val process = try {
            processBuilder.start()
        } catch (e: IOException) {
            throw YoutubeDLException(e)
        }

        if (processId != null) {
            idProcessMap[processId] = process
        }

        val stdOutProcessor = ProgressStreamReader(outBuffer, process.inputStream, callback)
        val stdErrProcessor = StreamCollector(errBuffer, process.errorStream)
        val exitCode = try {
            stdOutProcessor.join()
            stdErrProcessor.join()
            process.waitFor()
        } catch (e: InterruptedException) {
            process.destroy()
            if (processId != null) idProcessMap.remove(processId)
            throw e
        }

        val out = outBuffer.toString()
        val err = errBuffer.toString()
        if (exitCode > 0) {
            if (processId != null && !idProcessMap.containsKey(processId)) {
                throw CanceledException()
            }
            if (!ignoreErrors(request, out)) {
                idProcessMap.remove(processId)
                throw YoutubeDLException(err)
            }
        }
        idProcessMap.remove(processId)

        return YoutubeDLResponse(command, exitCode, System.currentTimeMillis() - startTime, out, err)
    }

    fun allowAppGeneratedConfigFile(request: YoutubeDLRequest, configFile: File) {
        val canonicalFile = runCatching { configFile.canonicalFile }.getOrElse { configFile.absoluteFile }
        synchronized(allowedConfigFilesByRequest) {
            val allowedConfigFiles = allowedConfigFilesByRequest.getOrPut(request) { mutableSetOf() }
            allowedConfigFiles.add(canonicalFile)
        }
    }

    fun destroyProcessById(processId: String): Boolean {
        if (!idProcessMap.containsKey(processId)) return false
        val process = idProcessMap[processId] ?: return false
        var alive = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            alive = process.isAlive
        }
        if (!alive) {
            idProcessMap.remove(processId)
            return false
        }
        process.destroy()
        idProcessMap.remove(processId)
        return true
    }

    private fun sanitizeArguments(
        context: Context,
        originalArgs: List<String>,
        allowedConfigFiles: Set<File>
    ): List<String> {
        val args = mutableListOf<String>()
        var i = 0
        while (i < originalArgs.size) {
            val arg = originalArgs[i]
            val normalizedArg = arg.unwrapMatchingQuotes()
            if (isBlockedExternalOption(normalizedArg, BLOCKED_EXTERNAL_OPTIONS)) {
                val inlineValue = normalizedArg.substringAfter("=", "")
                    .takeIf { normalizedArg.contains("=") }
                if (inlineValue != null && isBlockedExternalOption(normalizedArg, CONFIG_OPTIONS)) {
                    if (isAllowedAppGeneratedConfigPath(inlineValue, allowedConfigFiles)) {
                        args.add(arg)
                    }
                    i += 1
                    continue
                }

                val nextArg = originalArgs.getOrNull(i + 1)
                if (
                    isBlockedExternalOption(normalizedArg, CONFIG_OPTIONS) &&
                    nextArg != null &&
                    isAllowedAppGeneratedConfigPath(nextArg, allowedConfigFiles)
                ) {
                    args.add(arg)
                    args.add(nextArg)
                    i += 2
                    continue
                }
                i += if (nextArg != null && !nextArg.unwrapMatchingQuotes().startsWith("-")) 2 else 1
                continue
            }
            args.add(arg)
            i += 1
        }

        resolveValidFfmpegLocation(context)?.let { ffmpegLocation ->
            args.add(0, ffmpegLocation)
            args.add(0, "--ffmpeg-location")
        }

        if (!containsOptionWithValue(args, "--cache-dir")) {
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
        val lineSeparator = if (commandString.contains("\r\n")) "\r\n" else "\n"
        var skipNextValueLine = false
        return commandString
            .split(Regex("\\r?\\n"), -1)
            .map { line ->
                if (line.isBlank() || line.trimStart().startsWith("#")) {
                    return@map line
                }

                val tokens = tokenizeCommandString(line)
                if (tokens.isEmpty()) {
                    return@map line
                }

                if (skipNextValueLine) {
                    val first = tokens.first().unwrapMatchingQuotes()
                    if (!first.startsWith("-")) {
                        skipNextValueLine = false
                        return@map null
                    }
                    skipNextValueLine = false
                }

                val sanitized = mutableListOf<String>()
                var changed = false
                var i = 0
                while (i < tokens.size) {
                    val token = tokens[i]
                    val normalizedToken = token.unwrapMatchingQuotes()
                    when {
                        isBlockedExternalOption(normalizedToken, BLOCKED_EXTERNAL_OPTIONS) -> {
                            changed = true
                            i += when {
                                normalizedToken.contains("=") -> 1
                                i + 1 < tokens.size && !tokens[i + 1].unwrapMatchingQuotes().startsWith("-") -> 2
                                else -> {
                                    skipNextValueLine = true
                                    1
                                }
                            }
                        }
                        else -> {
                            sanitized.add(token)
                            i += 1
                        }
                    }
                }

                if (!changed) line else sanitized.joinToString(" ").takeIf { it.isNotBlank() }
            }
            .filterNotNull()
            .joinToString(lineSeparator)
    }

    private fun tokenizeCommandString(commandString: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < commandString.length) {
            val c = commandString[i]
            if (c == '\\' && i + 1 < commandString.length) {
                current.append(c)
                current.append(commandString[i + 1])
                i += 2
                continue
            }
            if ((c == '"' || c == '\'') && (quote == null || quote == c)) {
                quote = if (quote == c) null else c
                current.append(c)
                i += 1
                continue
            }
            if (quote == null && c.isWhitespace()) {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.setLength(0)
                }
                i += 1
                continue
            }
            current.append(c)
            i += 1
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }

    private fun containsOptionWithValue(args: List<String>, option: String): Boolean {
        val index = args.indexOf(option)
        return index >= 0 && index + 1 < args.size && !args[index + 1].startsWith("-")
    }

    private fun isBlockedExternalOption(arg: String, option: String): Boolean {
        return arg == option || arg.startsWith("$option=")
    }

    private fun isBlockedExternalOption(arg: String, options: Set<String>): Boolean {
        return options.any { isBlockedExternalOption(arg, it) }
    }

    private fun String.unwrapMatchingQuotes(): String {
        val trimmed = trim()
        if (trimmed.length < 2) return trimmed
        val first = trimmed.first()
        val last = trimmed.last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun takeAllowedAppGeneratedConfigFiles(request: YoutubeDLRequest): Set<File> {
        return synchronized(allowedConfigFilesByRequest) {
            allowedConfigFilesByRequest.remove(request)?.toSet().orEmpty()
        }
    }

    private fun isAllowedAppGeneratedConfigPath(path: String, allowedConfigFiles: Set<File>): Boolean {
        return runCatching {
            val candidate = File(path.unwrapMatchingQuotes()).canonicalFile
            candidate.isFile && allowedConfigFiles.any { it.canonicalFile == candidate }
        }.getOrDefault(false)
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


