package com.ireum.ytdl.util.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.ireum.ytdl.R
import com.ireum.ytdl.util.AppPrivatePathRedactor
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.SensitiveTextRedactor
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.coroutineContext

enum class RuntimeProbeId {
    YTDLP,
    PYTHON,
    FFMPEG,
    FFPROBE,
    ARIA2C,
    QUICKJS,
    COOKIES,
    DESTINATION,
    AVAILABLE_STORAGE,
    NOTIFICATIONS,
    BATTERY_OPTIMIZATION
}

enum class RuntimeProbeStatus {
    OK,
    WARNING,
    ERROR,
    UNKNOWN
}

enum class RuntimeExecutableState {
    READY,
    MISSING,
    NOT_A_FILE,
    NOT_EXECUTABLE
}

data class RuntimeProbeResult(
    val id: RuntimeProbeId,
    val status: RuntimeProbeStatus,
    val detail: String
)

data class RuntimeDiagnosticsReport(
    val reportedAbis: List<String>,
    val results: List<RuntimeProbeResult>
)

sealed interface TimedProbeResult<out T> {
    data class Value<T>(val value: T) : TimedProbeResult<T>
    data object TimedOut : TimedProbeResult<Nothing>
}

object RuntimeProbePolicy {
    fun executableState(exists: Boolean, isFile: Boolean, canExecute: Boolean): RuntimeExecutableState {
        return when {
            !exists -> RuntimeExecutableState.MISSING
            !isFile -> RuntimeExecutableState.NOT_A_FILE
            !canExecute -> RuntimeExecutableState.NOT_EXECUTABLE
            else -> RuntimeExecutableState.READY
        }
    }

    suspend fun <T> runWithTimeout(
        timeoutMillis: Long,
        block: suspend () -> T
    ): TimedProbeResult<T> {
        return try {
            TimedProbeResult.Value(withTimeout(timeoutMillis.coerceAtLeast(1L)) { block() })
        } catch (_: TimeoutCancellationException) {
            TimedProbeResult.TimedOut
        }
    }
}

class RuntimeDiagnostics(
    context: Context,
    private val perProbeTimeoutMillis: Long = DEFAULT_PROBE_TIMEOUT_MILLIS
) {
    private val appContext = context.applicationContext

    suspend fun run(): RuntimeDiagnosticsReport = withContext(Dispatchers.IO) {
        val runtime = YoutubeDLCompat.runtimeLayout(appContext)
        val environment = runtimeEnvironment(runtime)
        val results = buildList {
            add(runTimed(RuntimeProbeId.YTDLP) {
                executableProbe(
                    RuntimeProbeId.YTDLP,
                    runtime.pythonBinary,
                    listOf(runtime.ytdlpBinary.absolutePath, "--version"),
                    environment
                )
            })
            add(runTimed(RuntimeProbeId.PYTHON) {
                executableProbe(
                    RuntimeProbeId.PYTHON,
                    runtime.pythonBinary,
                    listOf("--version"),
                    environment
                )
            })
            add(runTimed(RuntimeProbeId.FFMPEG) {
                executableProbe(
                    RuntimeProbeId.FFMPEG,
                    runtime.ffmpegBinary,
                    listOf("-version"),
                    environment
                )
            })
            add(runTimed(RuntimeProbeId.FFPROBE) {
                executableProbe(
                    RuntimeProbeId.FFPROBE,
                    runtime.ffprobeBinary,
                    listOf("-version"),
                    environment
                )
            })
            add(runTimed(RuntimeProbeId.ARIA2C) {
                executableProbe(
                    RuntimeProbeId.ARIA2C,
                    runtime.aria2cBinary,
                    listOf("--version"),
                    environment
                )
            })
            add(runTimed(RuntimeProbeId.QUICKJS) {
                executableProbe(
                    RuntimeProbeId.QUICKJS,
                    runtime.quickJsBinary,
                    listOf("--help"),
                    environment,
                    successfulExitCodes = setOf(0, 1),
                    requiredOutputToken = "quickjs"
                )
            })
            add(runTimed(RuntimeProbeId.COOKIES) { cookieProbe() })
            add(runTimed(RuntimeProbeId.DESTINATION) { destinationProbe() })
            add(runTimed(RuntimeProbeId.AVAILABLE_STORAGE) { availableStorageProbe() })
            add(runTimed(RuntimeProbeId.NOTIFICATIONS) { notificationProbe() })
            add(runTimed(RuntimeProbeId.BATTERY_OPTIMIZATION) { batteryOptimizationProbe() })
        }
        RuntimeDiagnosticsReport(
            reportedAbis = Build.SUPPORTED_ABIS.toList(),
            results = results
        )
    }

    private suspend fun runTimed(
        id: RuntimeProbeId,
        block: suspend () -> RuntimeProbeResult
    ): RuntimeProbeResult {
        return try {
            when (val result = RuntimeProbePolicy.runWithTimeout(perProbeTimeoutMillis, block)) {
                is TimedProbeResult.Value -> result.value
                TimedProbeResult.TimedOut -> RuntimeProbeResult(
                    id,
                    RuntimeProbeStatus.ERROR,
                    appContext.getString(R.string.runtime_probe_timed_out)
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val safeMessage = sanitize(error.message.orEmpty())
            RuntimeProbeResult(
                id,
                RuntimeProbeStatus.ERROR,
                safeMessage.ifBlank { appContext.getString(R.string.runtime_probe_failed) }
            )
        }
    }

    private suspend fun executableProbe(
        id: RuntimeProbeId,
        executable: File,
        arguments: List<String>,
        environment: Map<String, String>,
        successfulExitCodes: Set<Int> = setOf(0),
        requiredOutputToken: String? = null
    ): RuntimeProbeResult {
        val executableState = RuntimeProbePolicy.executableState(
            executable.exists(),
            executable.isFile,
            executable.canExecute()
        )
        if (executableState != RuntimeExecutableState.READY) {
            return RuntimeProbeResult(
                id,
                RuntimeProbeStatus.ERROR,
                appContext.getString(
                    when (executableState) {
                        RuntimeExecutableState.MISSING -> R.string.runtime_executable_missing
                        RuntimeExecutableState.NOT_A_FILE -> R.string.runtime_executable_not_file
                        RuntimeExecutableState.NOT_EXECUTABLE -> R.string.runtime_executable_not_executable
                        RuntimeExecutableState.READY -> R.string.runtime_probe_failed
                    }
                )
            )
        }

        val execution = executeProcess(executable, arguments, environment)
        val summary = firstUsefulLine(execution.output)
        val hasRequiredOutput = requiredOutputToken == null ||
            execution.output.contains(requiredOutputToken, ignoreCase = true)
        return if (execution.exitCode in successfulExitCodes && hasRequiredOutput) {
            RuntimeProbeResult(
                id,
                RuntimeProbeStatus.OK,
                summary.ifBlank { appContext.getString(R.string.runtime_executable_completed) }
            )
        } else {
            RuntimeProbeResult(
                id,
                RuntimeProbeStatus.ERROR,
                appContext.getString(R.string.runtime_exit_code, execution.exitCode)
            )
        }
    }

    private fun cookieProbe(): RuntimeProbeResult {
        val present = File(appContext.cacheDir, "cookies.txt").isFile
        return RuntimeProbeResult(
            RuntimeProbeId.COOKIES,
            if (present) RuntimeProbeStatus.OK else RuntimeProbeStatus.WARNING,
            appContext.getString(
                if (present) R.string.runtime_cookie_present else R.string.runtime_cookie_absent
            )
        )
    }

    private fun destinationProbe(): RuntimeProbeResult {
        val destinations = configuredDestinations()
        val unavailable = destinations.filterNot { (_, path) ->
            FileUtil.canWriteToDestination(path, appContext)
        }.keys
        return RuntimeProbeResult(
            RuntimeProbeId.DESTINATION,
            if (unavailable.isEmpty()) RuntimeProbeStatus.OK else RuntimeProbeStatus.ERROR,
            if (unavailable.isEmpty()) {
                appContext.getString(R.string.runtime_destinations_writable)
            } else {
                appContext.getString(
                    R.string.runtime_destinations_not_writable,
                    unavailable.joinToString()
                )
            }
        )
    }

    private fun availableStorageProbe(): RuntimeProbeResult {
        val storage = configuredDestinations().mapNotNull { (label, path) ->
            FileUtil.getAvailableFreeSpaceBytes(path, appContext)?.let { label to it }
        }
        if (storage.isEmpty()) {
            return RuntimeProbeResult(
                RuntimeProbeId.AVAILABLE_STORAGE,
                RuntimeProbeStatus.UNKNOWN,
                appContext.getString(R.string.runtime_storage_unresolved)
            )
        }
        val detail = storage.joinToString { (label, bytes) ->
            "$label: ${readableBytes(bytes)}"
        }
        return RuntimeProbeResult(
            RuntimeProbeId.AVAILABLE_STORAGE,
            if (storage.any { it.second <= 0L }) RuntimeProbeStatus.ERROR else RuntimeProbeStatus.OK,
            detail
        )
    }

    private fun notificationProbe(): RuntimeProbeResult {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        return when {
            !permissionGranted -> RuntimeProbeResult(
                RuntimeProbeId.NOTIFICATIONS,
                RuntimeProbeStatus.ERROR,
                appContext.getString(R.string.runtime_notification_permission_denied)
            )
            !notificationsEnabled -> RuntimeProbeResult(
                RuntimeProbeId.NOTIFICATIONS,
                RuntimeProbeStatus.WARNING,
                appContext.getString(R.string.runtime_notifications_disabled)
            )
            else -> RuntimeProbeResult(
                RuntimeProbeId.NOTIFICATIONS,
                RuntimeProbeStatus.OK,
                appContext.getString(R.string.runtime_notifications_ready)
            )
        }
    }

    private fun batteryOptimizationProbe(): RuntimeProbeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return RuntimeProbeResult(
                RuntimeProbeId.BATTERY_OPTIMIZATION,
                RuntimeProbeStatus.UNKNOWN,
                appContext.getString(R.string.runtime_battery_not_applicable)
            )
        }
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return RuntimeProbeResult(
                RuntimeProbeId.BATTERY_OPTIMIZATION,
                RuntimeProbeStatus.UNKNOWN,
                appContext.getString(R.string.runtime_battery_unresolved)
            )
        val exempt = powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
        return RuntimeProbeResult(
            RuntimeProbeId.BATTERY_OPTIMIZATION,
            if (exempt) RuntimeProbeStatus.OK else RuntimeProbeStatus.WARNING,
            appContext.getString(
                if (exempt) R.string.runtime_battery_exempt else R.string.runtime_battery_optimized
            )
        )
    }

    private fun configuredDestinations(): Map<String, String> {
        val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
        val audio = preferences.getString("music_path", "").orEmpty()
            .ifBlank(FileUtil::getDefaultAudioPath)
        val video = preferences.getString("video_path", "").orEmpty()
            .ifBlank(FileUtil::getDefaultVideoPath)
        return linkedMapOf(
            appContext.getString(R.string.audio) to audio,
            appContext.getString(R.string.video) to video
        )
    }

    private fun runtimeEnvironment(runtime: YoutubeDLCompat.RuntimeLayout): Map<String, String> {
        return mapOf(
            "LD_LIBRARY_PATH" to listOf(
                runtime.pythonLibraryDir,
                runtime.ffmpegLibraryDir,
                runtime.aria2cLibraryDir
            ).joinToString(":") { it.absolutePath },
            "SSL_CERT_FILE" to runtime.sslCertificate.absolutePath,
            "PATH" to System.getenv("PATH").orEmpty() + ":" + runtime.nativeBinDir.absolutePath,
            "PYTHONHOME" to runtime.pythonHome.absolutePath,
            "HOME" to runtime.pythonHome.absolutePath,
            "TMPDIR" to appContext.cacheDir.absolutePath
        )
    }

    private suspend fun executeProcess(
        executable: File,
        arguments: List<String>,
        environment: Map<String, String>
    ): ProcessExecution = coroutineScope {
        val process = ProcessBuilder(listOf(executable.absolutePath) + arguments)
            .redirectErrorStream(true)
            .apply { environment().putAll(environment) }
            .start()
        val output = StringBuffer()
        val collector = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(512)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (output.length < MAX_CAPTURED_OUTPUT_CHARS) {
                            val remaining = MAX_CAPTURED_OUTPUT_CHARS - output.length
                            output.append(buffer, 0, count.coerceAtMost(remaining))
                        }
                    }
                }
            }
        }, "runtime-diagnostic-output").apply {
            isDaemon = true
            start()
        }

        try {
            while (true) {
                coroutineContext.ensureActive()
                val exitCode = runCatching { process.exitValue() }.getOrNull()
                if (exitCode != null) {
                    collector.join(OUTPUT_JOIN_TIMEOUT_MILLIS)
                    return@coroutineScope ProcessExecution(exitCode, output.toString())
                }
                delay(PROCESS_POLL_MILLIS)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            runCatching { process.destroy() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            collector.interrupt()
        }
    }

    private fun firstUsefulLine(output: String): String {
        return output.lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            ?.let(::sanitize)
            .orEmpty()
    }

    private fun sanitize(text: String): String {
        return AppPrivatePathRedactor.redact(appContext, text)
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(MAX_DETAIL_CHARS)
    }

    private fun readableBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        return FileUtil.convertFileSize(bytes)
    }

    private data class ProcessExecution(val exitCode: Int, val output: String)

    companion object {
        private const val DEFAULT_PROBE_TIMEOUT_MILLIS = 5_000L
        private const val PROCESS_POLL_MILLIS = 50L
        private const val OUTPUT_JOIN_TIMEOUT_MILLIS = 1_000L
        private const val MAX_CAPTURED_OUTPUT_CHARS = 4_096
        private const val MAX_DETAIL_CHARS = 240
    }
}

object RuntimeDiagnosticsFormatter {
    fun format(context: Context, report: RuntimeDiagnosticsReport): String {
        val abiText = report.reportedAbis.ifEmpty {
            listOf(context.getString(R.string.runtime_status_unknown))
        }
            .joinToString()
        return buildString {
            appendLine(context.getString(R.string.runtime_abi_informational, abiText))
            report.results.forEach { result ->
                appendLine()
                append('[')
                append(context.getString(statusLabel(result.status)))
                append("] ")
                appendLine(context.getString(probeLabel(result.id)))
                append(result.detail)
            }
        }.let(SensitiveTextRedactor::redactOutput).trim()
    }

    private fun statusLabel(status: RuntimeProbeStatus): Int = when (status) {
        RuntimeProbeStatus.OK -> R.string.runtime_status_ok
        RuntimeProbeStatus.WARNING -> R.string.runtime_status_warning
        RuntimeProbeStatus.ERROR -> R.string.runtime_status_error
        RuntimeProbeStatus.UNKNOWN -> R.string.runtime_status_unknown
    }

    private fun probeLabel(id: RuntimeProbeId): Int = when (id) {
        RuntimeProbeId.YTDLP -> R.string.runtime_probe_ytdlp
        RuntimeProbeId.PYTHON -> R.string.runtime_probe_python
        RuntimeProbeId.FFMPEG -> R.string.runtime_probe_ffmpeg
        RuntimeProbeId.FFPROBE -> R.string.runtime_probe_ffprobe
        RuntimeProbeId.ARIA2C -> R.string.runtime_probe_aria2c
        RuntimeProbeId.QUICKJS -> R.string.runtime_probe_quickjs
        RuntimeProbeId.COOKIES -> R.string.runtime_probe_cookies
        RuntimeProbeId.DESTINATION -> R.string.runtime_probe_destination
        RuntimeProbeId.AVAILABLE_STORAGE -> R.string.runtime_probe_storage
        RuntimeProbeId.NOTIFICATIONS -> R.string.runtime_probe_notifications
        RuntimeProbeId.BATTERY_OPTIMIZATION -> R.string.runtime_probe_battery
    }
}
