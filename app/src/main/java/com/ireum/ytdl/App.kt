package com.ireum.ytdl

import android.app.Application
import android.os.Build
import android.os.Looper
import android.system.Os
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.ThemeUtil
import com.yausername.aria2c.Aria2c
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream


class App : Application() {

    private val runtimeInstallLock = Any()

    override fun onCreate() {
        super.onCreate()
        instance = this

        val sharedPreferences =  PreferenceManager.getDefaultSharedPreferences(this@App)
        setDefaultValues()
        applicationScope = CoroutineScope(SupervisorJob())
        applicationScope.launch((Dispatchers.IO)) {
            try {
                createNotificationChannels()
                initLibraries()

                val appVer = sharedPreferences.getString("version", "")!!
                if(appVer.isEmpty() || appVer != BuildConfig.VERSION_NAME){
                    sharedPreferences.edit(commit = true){
                        putString("version", BuildConfig.VERSION_NAME)
                    }
                }
            }catch (e: Exception){
                Looper.prepare().runCatching {
                    Toast.makeText(
                        this@App,
                        R.string.runtime_initialization_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                e.printStackTrace()
            }
        }
        ThemeUtil.init(this)
    }
    @Throws(YoutubeDLException::class)
    private fun initLibraries() {
        ensureRuntimeToolsInstalled()
        YoutubeDL.getInstance().init(this)
        // Do not initialize youtubedl-android FFmpeg wrapper here.
        // Its packaged libffmpeg.so can hard-crash on some builds before hard-sub fallback runs.
        Log.i(TAG, "Skipping FFmpeg wrapper init; hard-sub uses runtime executable fallback path")
        Aria2c.getInstance().init(this)
    }

    fun ensureRuntimeToolsInstalled() {
        synchronized(runtimeInstallLock) {
            ensureShellEnvironment()
            installBundledFfmpegPayload()
            installBundledSrv3Converter()
        }
    }

    private fun ensureShellEnvironment() {
        runCatching {
            // yt-dlp --exec may resolve shell from process env; force Android shell path.
            Os.setenv("SHELL", "/system/bin/sh", true)
        }.onFailure {
            Log.w(TAG, "Failed to set SHELL environment variable", it)
        }
    }

    private fun setDefaultValues(){
        val SPL = 1
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        if (sp.getInt("spl", 0) != SPL) {
            PreferenceManager.setDefaultValues(this, R.xml.root_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.downloading_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.general_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.processing_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.folders_preference, true)
            PreferenceManager.setDefaultValues(this, R.xml.updating_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.advanced_preferences, true)
            sp.edit().putInt("spl", SPL).apply()
        }

    }

    private fun createNotificationChannels() {
        val notificationUtil = NotificationUtil(this)
        notificationUtil.createNotificationChannel()
    }

    private fun installBundledSrv3Converter() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val assetPath = "bin/$abi/yttml"
        val outFile = File(filesDir, "bin/yttml")
        runCatching {
            assets.open(assetPath).use { input ->
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            outFile.setReadable(true, true)
            outFile.setExecutable(true, true)
            outFile.setWritable(true, true)
            runCatching { Os.chmod(outFile.absolutePath, 493) } // 0755
            Log.i(TAG, "Installed bundled yttml for ABI=$abi at ${outFile.absolutePath}")
        }.onFailure {
            Log.i(TAG, "No bundled yttml found for ABI=$abi at assets/$assetPath")
        }
    }

    private fun installBundledFfmpegPayload() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val supportedAbis = Build.SUPPORTED_ABIS.joinToString()
        val assetPath = "bin/$abi/ffmpeg_payload.zip"
        val payloadRoot = File(noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        val revisionFile = File(payloadRoot, ".payload_revision")
        val expectedRevision = "arm64-wrapper-libffmpeg-0.18.1-r12"
        val requiredLibs = listOf(
            "usr/lib/libavdevice.so.61",
            "usr/lib/libavfilter.so.10",
            "usr/lib/libavformat.so.61",
            "usr/lib/libavcodec.so.61",
            "usr/lib/libavutil.so.59"
        )

        val requiredCopiedDeps = listOf(
            "usr/lib/libc++_shared.so",
            "usr/lib/libexpat.so.1",
            "usr/lib/libcrypto.so.3",
            "usr/lib/libssl.so.3"
        )
        val alreadyInstalled = requiredLibs.all { rel ->
            val file = File(payloadRoot, rel)
            file.exists() && file.length() > 255L
        } && requiredCopiedDeps.all { rel ->
            val file = File(payloadRoot, rel)
            file.exists() && file.length() > 255L
        } && revisionFile.exists() &&
            revisionFile.readText(Charsets.UTF_8).trim() == expectedRevision
        if (alreadyInstalled) {
            return
        }

        runCatching {
            if (payloadRoot.exists()) payloadRoot.deleteRecursively()
            payloadRoot.mkdirs()
            val rootCanonical = payloadRoot.canonicalPath + File.separator

            assets.open(assetPath).use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(payloadRoot, entry.name)
                        val outCanonical = outFile.canonicalPath
                        require(outCanonical.startsWith(rootCanonical)) {
                            "Invalid zip entry outside target dir: ${entry.name}"
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { output -> zis.copyTo(output) }
                            outFile.setReadable(true, true)
                            if (outFile.parentFile?.name == "bin") {
                                outFile.setExecutable(true, true)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            val payloadLibDir = File(payloadRoot, "usr/lib")
            materializeSharedLibraryLinks(payloadLibDir)
            copyRequiredBundledRuntimeDependencies(payloadLibDir, abi)
            copyBundledRuntimeDependencies(payloadLibDir, abi)
            revisionFile.writeText(expectedRevision, Charsets.UTF_8)
            Log.i(TAG, "Installed bundled ffmpeg payload for ABI=$abi at ${payloadRoot.absolutePath}")
        }.onFailure { error ->
            val assetExists = runCatching { assets.open(assetPath).close(); true }.getOrDefault(false)
            val payloadState = buildString {
                append("payloadRoot=")
                append(payloadRoot.absolutePath)
                append(" exists=")
                append(payloadRoot.exists())
                append(" isDir=")
                append(payloadRoot.isDirectory)
                append(" canWrite=")
                append(payloadRoot.canWrite())
                append(" assetExists=")
                append(assetExists)
                append(" supportedAbis=")
                append(supportedAbis)
            }
            Log.e(TAG, "Bundled ffmpeg payload install failed ABI=$abi asset=$assetPath $payloadState", error)
        }
    }



    private fun copyRequiredBundledRuntimeDependencies(libDir: File, abi: String) {
        if (!libDir.exists() || !libDir.isDirectory) return
        val requiredAssets = listOf(
            "libc++_shared.so",
            "libexpat.so.1",
            "libcrypto.so.3",
            "libssl.so.3"
        )
        val assetLibDir = "bin/$abi"
        requiredAssets.forEach { name ->
            val target = File(libDir, name)
            if (target.exists() && target.length() > 255L) return@forEach
            runCatching {
                assets.open("$assetLibDir/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.setReadable(true, true)
                Log.i(TAG, "Copied required bundled runtime dependency $name into ffmpeg payload")
            }.onFailure {
                Log.e(TAG, "Failed to copy required bundled runtime dependency $name", it)
            }
        }
    }

    private fun copyBundledRuntimeDependencies(libDir: File, abi: String) {
        if (!libDir.exists() || !libDir.isDirectory) return
        val excludedNames = setOf(
            "libffmpeg.so",
            "libffprobe.so",
            "libffmpeg_hardsub.so",
            "libffmpeg_hardsub_exec.so",
            "libyttml_exec.so"
        )
        val assetLibDir = "bin/$abi"
        val nativeSources = File(applicationInfo.nativeLibraryDir)
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && (it.extension == "so" || it.name.contains(".so.")) }
            .filterNot { it.name in excludedNames }
            .sortedBy { it.name }
            .associateBy { it.name }
            .toMutableMap()

        val assetNames = runCatching { assets.list(assetLibDir)?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { name -> (name.endsWith(".so") || name.contains(".so.")) && name !in excludedNames }
            .sorted()

        val sourceNames = linkedSetOf<String>()
        sourceNames.addAll(nativeSources.keys)
        sourceNames.addAll(assetNames)

        sourceNames.forEach { name ->
            val target = File(libDir, name)
            if (target.exists() && target.length() > 255L) return@forEach
            runCatching {
                val copied = nativeSources[name]?.takeIf { it.exists() && it.isFile }?.let { source ->
                    source.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                } ?: run {
                    assets.open("$assetLibDir/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                }
                if (copied) {
                    target.setReadable(true, true)
                    Log.i(TAG, "Copied bundled runtime dependency $name into ffmpeg payload")
                }
            }.onFailure {
                Log.e(TAG, "Failed to copy bundled runtime dependency $name", it)
            }
        }
    }

    private fun materializeSharedLibraryLinks(libDir: File) {
        if (!libDir.exists() || !libDir.isDirectory) return
        libDir.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || file.length() !in 1..255) return@forEach
            val targetName = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrDefault("")
            if (targetName.isBlank() || targetName.contains('/') || !targetName.endsWith(".so") && !targetName.contains(".so.")) {
                return@forEach
            }
            val target = File(libDir, targetName)
            if (!target.exists() || !target.isFile || target.length() <= 255L) return@forEach
            runCatching {
                target.inputStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.setReadable(true, true)
                Log.i(TAG, "Materialized ffmpeg symlink placeholder ${file.name} -> ${target.name}")
            }.onFailure {
                Log.e(TAG, "Failed to materialize ffmpeg symlink placeholder ${file.name}", it)
            }
        }
    }


    fun getFfmpegPayloadDiagnostics(): String {
        val payloadRoot = File(noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        val revisionFile = File(payloadRoot, ".payload_revision")
        val payloadLibDir = File(payloadRoot, "usr/lib")
        val probeNames = listOf(
            "libavdevice.so.61",
            "libavfilter.so.10",
            "libavformat.so.61",
            "libavcodec.so.61",
            "libavutil.so.59",
            "libc++_shared.so",
            "libexpat.so.1",
            "libcrypto.so.3",
            "libssl.so.3"
        )
        return buildString {
            appendLine("ffmpegPayload:")
            appendLine(" root=${payloadRoot.absolutePath} exists=${payloadRoot.exists()} isDir=${payloadRoot.isDirectory}")
            appendLine(" revision=${revisionFile.takeIf { it.exists() }?.readText(Charsets.UTF_8)?.trim() ?: "<missing>"}")
            probeNames.forEach { name ->
                val file = File(payloadLibDir, name)
                appendLine(" lib=$name exists=${file.exists()} size=${if (file.exists()) file.length() else -1L}")
            }
        }
    }

    companion object {
        private const val TAG = "App"
        private lateinit var applicationScope: CoroutineScope
        lateinit var instance: App
    }
}

