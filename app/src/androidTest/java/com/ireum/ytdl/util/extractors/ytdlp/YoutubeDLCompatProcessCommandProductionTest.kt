package com.ireum.ytdl.util.extractors.ytdlp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class YoutubeDLCompatProcessCommandProductionTest {
    private val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetProductionSeams() {
        YoutubeDLCompat.processStarterOverrideForTesting = null
        YoutubeDLCompat.runtimeLayoutOverrideForTesting = null
    }

    @Test
    fun realProcessIdExecutionBuildsSupervisorChildAsBundledPythonThenAppDataYtdlp() {
        val root = File(appContext.noBackupFilesDir, "a2-command-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val python = File(root, "bundled-python").apply { writeText("test") }
        val ytdlp = File(
            appContext.noBackupFilesDir,
            "youtubedl-android/yt-dlp/yt-dlp",
        ).apply {
            parentFile!!.mkdirs()
            writeText("test")
        }
        val quickJs = File(root, "quickjs").apply { writeText("test") }
        val ssl = File(root, "cert.pem").apply { writeText("test") }
        val runtime = YoutubeDLCompat.RuntimeLayout(
            baseDir = root,
            packagesDir = File(root, "packages"),
            nativeBinDir = File(root, "native"),
            pythonBinary = python,
            quickJsBinary = quickJs,
            ytdlpBinary = ytdlp,
            ffmpegBinary = File(root, "ffmpeg"),
            ffprobeBinary = File(root, "ffprobe"),
            aria2cBinary = File(root, "aria2c"),
            pythonHome = File(root, "python-home"),
            pythonLibraryDir = File(root, "python-lib"),
            ffmpegLibraryDir = File(root, "ffmpeg-lib"),
            aria2cLibraryDir = File(root, "aria2c-lib"),
            sslCertificate = ssl,
        )
        var processCommand: List<String>? = null
        var processEnvironment: Map<String, String>? = null
        YoutubeDLCompat.runtimeLayoutOverrideForTesting = runtime
        YoutubeDLCompat.processStarterOverrideForTesting = { command, environment, _ ->
            processCommand = command
            processEnvironment = environment
            ExitedProcess()
        }

        val processId = "terminal-command-test-${System.nanoTime()}"
        val request = YoutubeDLRequest("https://example.com/video").apply {
            addOption("--skip-download")
        }
        try {
            val result = YoutubeDLCompat.executeWithQuiescence(
                context = appContext,
                request = request,
                processId = processId,
                redirectErrorStream = true,
            )

            val command = requireNotNull(processCommand)
            val environment = requireNotNull(processEnvironment)
            assertFalse(result.nativeQuiescent)
            assertEquals(python.absolutePath, command[0])
            assertEquals("-c", command[1])
            // [supervisor Python, -c, script, marker, child Python, yt-dlp, ...]
            assertEquals(python.absolutePath, command[4])
            assertEquals(ytdlp.absolutePath, command[5])
            assertTrue(command[4] != ytdlp.absolutePath)
            assertTrue(command[0] != ytdlp.absolutePath)
            assertNotNull(environment[YtdlpNativeProcessBarrier.NATIVE_GENERATION_ENVIRONMENT])
            assertEquals(processId, environment["YTDLNISX_PROCESS_ID"])
        } finally {
            // The fake starter leaves the durable STARTING marker in place;
            // the real destroy/recovery entrypoint must converge it by exact
            // generation absence before the test can reuse the workspace.
            YoutubeDLCompat.destroyProcessById(processId)
            root.deleteRecursively()
            ytdlp.delete()
        }
    }

    @Test
    fun rootExitZeroIsSuccessfulOnlyWhenTheProductionMarkerIsQuiescent() {
        val root = File(appContext.noBackupFilesDir, "a2-quiescence-result-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val python = File(root, "bundled-python").apply { writeText("test") }
        val ytdlp = File(root, "yt-dlp").apply { writeText("test") }
        val quickJs = File(root, "quickjs").apply { writeText("test") }
        val ssl = File(root, "cert.pem").apply { writeText("test") }
        YoutubeDLCompat.runtimeLayoutOverrideForTesting = runtimeLayout(
            root = root,
            python = python,
            ytdlp = ytdlp,
            quickJs = quickJs,
            ssl = ssl,
        )
        val processId = "quiescence-result-${System.nanoTime()}"
        YoutubeDLCompat.processStarterOverrideForTesting = { command, environment, _ ->
            YtdlpNativeProcessBarrier.writeMarkerForTesting(
                processId = processId,
                state = "QUIESCENT",
                generationToken = requireNotNull(
                    environment[YtdlpNativeProcessBarrier.NATIVE_GENERATION_ENVIRONMENT],
                ),
            )
            ExitedProcess()
        }

        try {
            val result = YoutubeDLCompat.executeWithQuiescence(
                context = appContext,
                request = YoutubeDLRequest("https://example.com/video"),
                processId = processId,
                redirectErrorStream = true,
            )

            assertEquals(0, result.response.exitCode)
            assertTrue(result.nativeQuiescent)
            assertFalse(YtdlpNativeProcessBarrier.markerFor(processId).exists())
        } finally {
            YoutubeDLCompat.destroyProcessById(processId)
            root.deleteRecursively()
        }
    }

    private fun runtimeLayout(
        root: File,
        python: File,
        ytdlp: File,
        quickJs: File,
        ssl: File,
    ) = YoutubeDLCompat.RuntimeLayout(
        baseDir = root,
        packagesDir = File(root, "packages"),
        nativeBinDir = File(root, "native"),
        pythonBinary = python,
        quickJsBinary = quickJs,
        ytdlpBinary = ytdlp,
        ffmpegBinary = File(root, "ffmpeg"),
        ffprobeBinary = File(root, "ffprobe"),
        aria2cBinary = File(root, "aria2c"),
        pythonHome = File(root, "python-home"),
        pythonLibraryDir = File(root, "python-lib"),
        ffmpegLibraryDir = File(root, "ffmpeg-lib"),
        aria2cLibraryDir = File(root, "aria2c-lib"),
        sslCertificate = ssl,
    )

    private class ExitedProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
        override fun isAlive(): Boolean = false
    }
}
