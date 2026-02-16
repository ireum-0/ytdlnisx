package com.ireum.ytdl

import android.app.Application
import android.os.Build
import android.os.Looper
import android.system.Os
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.ThemeUtil
import com.ireum.ytdl.work.HardSubScanWorker
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
                scheduleHardSubScanWorker()
            }catch (e: Exception){
                Looper.prepare().runCatching {
                    Toast.makeText(this@App, e.message, Toast.LENGTH_SHORT).show()
                }
                e.printStackTrace()
            }
        }
        ThemeUtil.init(this)
    }
    @Throws(YoutubeDLException::class)
    private fun initLibraries() {
        ensureShellEnvironment()
        removeLegacyBundledHardSubArtifacts()
        installBundledFfmpegPayload()
        ensureFfmpegRuntimeDependencies()
        installBundledSrv3Converter()
        YoutubeDL.getInstance().init(this)
        // Do not initialize youtubedl-android FFmpeg wrapper here.
        // Its packaged libffmpeg.so can hard-crash on some builds before hard-sub fallback runs.
        Log.i(TAG, "Skipping FFmpeg wrapper init; hard-sub uses runtime executable fallback path")
        Aria2c.getInstance().init(this)
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

    private fun removeLegacyBundledHardSubArtifacts() {
        runCatching {
            val bundledFfmpeg = File(filesDir, "bin/ffmpeg")
            if (bundledFfmpeg.exists()) {
                bundledFfmpeg.delete()
                Log.i(TAG, "Removed legacy bundled hard-sub ffmpeg at ${bundledFfmpeg.absolutePath}")
            }
        }.onFailure {
            Log.w(TAG, "Failed to remove legacy bundled hard-sub ffmpeg", it)
        }
    }

    private fun installBundledFfmpegPayload() {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val assetPath = "bin/$abi/ffmpeg_payload.zip"
        val payloadRoot = File(noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        val revisionFile = File(payloadRoot, ".payload_revision")
        val expectedRevision = "arm64-termux-ffmpeg-8.0.1-openssl3-r1"
        val requiredLibs = listOf(
            "usr/lib/libavdevice.so.62",
            "usr/lib/libavformat.so.62",
            "usr/lib/libssl.so.3",
            "usr/lib/libcrypto.so.3"
        )

        val alreadyInstalled = requiredLibs.all { rel ->
            val file = File(payloadRoot, rel)
            file.exists() && file.length() > 0L
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
            revisionFile.writeText(expectedRevision, Charsets.UTF_8)
            Log.i(TAG, "Installed bundled ffmpeg payload for ABI=$abi at ${payloadRoot.absolutePath}")
        }.onFailure {
            Log.w(TAG, "No bundled ffmpeg payload found for ABI=$abi at assets/$assetPath", it)
        }
    }

    private fun ensureFfmpegRuntimeDependencies() {
        val ffmpegLibDir = File(noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        if (!ffmpegLibDir.exists() || !ffmpegLibDir.isDirectory) return

        val is64BitAbi = Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true
        val genericCandidates = buildList {
            add(File(applicationInfo.nativeLibraryDir, "libc++_shared.so"))
            add(File("/data/data/com.termux/files/usr/lib/libc++_shared.so"))
            add(File("/data/user/0/com.termux/files/usr/lib/libc++_shared.so"))
            if (is64BitAbi) {
                add(File("/apex/com.android.runtime/lib64/libc++_shared.so"))
                add(File("/apex/com.android.runtime/lib64/libc++.so"))
                add(File("/system/lib64/libc++_shared.so"))
                add(File("/system/lib64/libc++.so"))
                add(File("/vendor/lib64/libc++_shared.so"))
                add(File("/vendor/lib64/libc++.so"))
            } else {
                add(File("/apex/com.android.runtime/lib/libc++_shared.so"))
                add(File("/apex/com.android.runtime/lib/libc++.so"))
                add(File("/system/lib/libc++_shared.so"))
                add(File("/system/lib/libc++.so"))
                add(File("/vendor/lib/libc++_shared.so"))
                add(File("/vendor/lib/libc++.so"))
            }
        }

        fun patchIfMissing(
            libName: String,
            extraCandidates: List<File> = emptyList(),
            forceReplace: Boolean = false
        ) {
            val target = File(ffmpegLibDir, libName)
            if (target.exists() && !forceReplace) return
            val source = (extraCandidates + genericCandidates)
                .firstOrNull { it.exists() && it.isFile } ?: return
            runCatching {
                source.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.setReadable(true, true)
                Log.i(TAG, "Patched ffmpeg runtime dependency: ${source.absolutePath} -> ${target.absolutePath}")
            }.onFailure {
                Log.e(TAG, "Failed to patch ffmpeg runtime dependency: $libName", it)
            }
        }

        val appLibDir = File(applicationInfo.nativeLibraryDir)
        patchIfMissing("libc++_shared.so")
        patchIfMissing("libplacebo.so", listOf(File(appLibDir, "libplacebo.so")))
        patchIfMissing("libandroid-execinfo.so", listOf(File(appLibDir, "libandroid-execinfo.so")))
        patchIfMissing("libglslang.so", listOf(File(appLibDir, "libglslang.so")))
        patchIfMissing("libSPIRV.so", listOf(File(appLibDir, "libSPIRV.so")))
        patchIfMissing(
            "libglslang-default-resource-limits.so",
            listOf(File(appLibDir, "libglslang-default-resource-limits.so"))
        )
        patchIfMissing(
            "libbluray.so.3",
            listOf(
                File(ffmpegLibDir, "libbluray.so"),
                File(appLibDir, "libbluray.so.3"),
                File(appLibDir, "libbluray.so")
            )
        )
        patchIfMissing(
            "libvpx.so.12",
            listOf(
                File(ffmpegLibDir, "libvpx.so"),
                File(appLibDir, "libvpx.so.12"),
                File(appLibDir, "libvpx.so")
            )
        )
        patchIfMissing(
            "libOpenCL.so",
            listOf(
                File(appLibDir, "libOpenCL.so"),
                File(appLibDir, "libOpenCL.so.1")
            )
        )
        patchIfMissing(
            "libOpenCL.so.1",
            listOf(
                File(appLibDir, "libOpenCL.so.1"),
                File(appLibDir, "libOpenCL.so")
            )
        )
        patchIfMissing(
            "libcrypto.so.3",
            listOf(
                File(appLibDir, "libcrypto.so.3")
            )
        )
        patchIfMissing(
            "libssl.so.3",
            listOf(
                File(appLibDir, "libssl.so.3")
            )
        )
        patchIfMissing("libexpat.so.1", listOf(File("/system/lib64/libexpat.so")))
    }

    private fun scheduleHardSubScanWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<HardSubScanWorker>()
            .setConstraints(constraints)
            .addTag(HardSubScanWorker.TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            HardSubScanWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        private const val TAG = "App"
        private lateinit var applicationScope: CoroutineScope
        lateinit var instance: App
    }
}
