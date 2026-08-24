package com.ireum.ytdl.util.extractors.ytdlp

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Durable process-group evidence for the bundled Python -> yt-dlp runtime.
 * yt-dlp can launch the bundled ffmpeg/ffprobe or aria2c binaries, so the
 * Java root Process is not by itself a descendant-quiescence proof.
 */
internal object YtdlpNativeProcessBarrier {
    private const val DIRECTORY_NAME = "process-barriers"
    private const val STATE_STARTING = "STARTING"
    private const val STATE_RUNNING = "RUNNING"
    private const val STATE_QUIESCENT = "QUIESCENT"
    private const val TERMINATION_WAIT_MILLIS = 4_000L
    private const val POLL_MILLIS = 50L

    @Volatile
    private var directory: File? = null

    internal data class DurableDownloadProcess(
        val processId: String,
        val downloadId: Long,
        val executionId: String,
    )

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

    fun prepare(context: Context, processId: String): File {
        configure(context)
        val marker = markerFor(processId)
        if (marker.exists() && !recover(marker)) {
            error("An unresolved yt-dlp process barrier already exists for $processId")
        }
        marker.parentFile?.mkdirs()
        marker.writeText(
            "$STATE_STARTING\nprocessId=$processId\n",
            Charsets.UTF_8,
        )
        return marker
    }

    fun markerFor(processId: String): File {
        val root = directory ?: error("Yt-dlp process barrier directory is not configured")
        val safeName = processId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(root, "$safeName.marker")
    }

    fun isQuiescent(marker: File): Boolean = readState(marker) == STATE_QUIESCENT

    fun clear(marker: File) {
        if (marker.exists() && !marker.delete()) {
            error("Could not clear yt-dlp process barrier ${marker.name}")
        }
    }

    /**
     * Quiesces a marker left by a force-killed or process-dead supervisor.
     * Only the exact recorded process group is signalled; unrelated Android
     * processes are never addressed.
     */
    fun recover(marker: File): Boolean {
        if (!marker.exists()) return true
        if (isQuiescent(marker)) {
            marker.delete()
            return true
        }
        val pgid = readLong(marker, "pgid") ?: return false
        if (pgid <= 0L || pgid > Int.MAX_VALUE) return false
        val groupId = pgid.toInt()
        if (!signalGroup(groupId, OsConstants.SIGTERM)) return false
        if (awaitGroupGone(marker, groupId)) {
            marker.delete()
            return true
        }
        if (!signalGroup(groupId, OsConstants.SIGKILL)) return false
        if (!awaitGroupGone(marker, groupId)) return false
        marker.delete()
        return true
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
                val processId = readString(marker, "processId") ?: return@mapNotNull null
                val parts = processId.split(':', limit = 3)
                if (parts.size != 3 || parts[0] != "download") return@mapNotNull null
                val downloadId = parts[1].toLongOrNull() ?: return@mapNotNull null
                DurableDownloadProcess(
                    processId = processId,
                    downloadId = downloadId,
                    executionId = parts[2],
                )
            }
    }

    private fun awaitGroupGone(marker: File, groupId: Int): Boolean {
        val deadline = System.nanoTime() + TERMINATION_WAIT_MILLIS * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (isQuiescent(marker) || !groupExists(groupId)) return true
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return isQuiescent(marker) || !groupExists(groupId)
    }

    private fun signalGroup(groupId: Int, signal: Int): Boolean = try {
        // A negative pid addresses exactly the process group created by the
        // supervisor, never every process owned by the application UID.
        Os.kill(-groupId, signal)
        true
    } catch (error: ErrnoException) {
        error.errno == OsConstants.ESRCH
    } catch (_: Exception) {
        false
    }

    private fun groupExists(groupId: Int): Boolean = try {
        Os.kill(-groupId, 0)
        true
    } catch (error: ErrnoException) {
        when (error.errno) {
            OsConstants.ESRCH -> false
            else -> true
        }
    } catch (_: Exception) {
        true
    }

    private fun readState(marker: File): String? = marker
        .takeIf { it.isFile }
        ?.useLines { lines -> lines.firstOrNull() }

    private fun readString(marker: File, key: String): String? = marker
        .takeIf { it.isFile }
        ?.useLines { lines ->
            lines.firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')
        }

    private fun readLong(marker: File, key: String): Long? =
        readString(marker, key)?.toLongOrNull()
}
