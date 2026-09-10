package com.ireum.ytdl.util.storage

import android.content.Context
import com.ireum.ytdl.util.FileUtil
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Keeps yt-dlp's per-generation archive separate from the app-global
 * duplicate authority.  The global file is promoted only after the caller
 * has durably committed the app-level primary result.
 */
internal object DownloadArchiveAuthority {
    private const val GENERATION_DIRECTORY = "download-archive-generations"
    private val promotionLock = Any()

    /**
     * Production always forces the temporary archive to stable storage.  JVM
     * tests can replace this operation because some host filesystems do not
     * implement descriptor sync; the default remains the checked durability
     * boundary used by the app.
     */
    internal var syncForTesting: ((FileOutputStream) -> Unit)? = null

    data class Generation(
        val downloadId: Long,
        val executionId: String,
        val privateArchive: File,
        val globalArchive: File,
    )

    fun prepare(context: Context, downloadId: Long, executionId: String): Generation {
        require(executionId.isNotBlank())
        val global = File(FileUtil.getDownloadArchivePath(context)).canonicalFile
        val root = File(context.filesDir, GENERATION_DIRECTORY).canonicalFile
        check(root.exists() || root.mkdirs()) { "Could not create Download archive generation directory" }
        val privateArchive = File(root, "${stableKey(downloadId, executionId)}.txt").canonicalFile
        if (!privateArchive.exists()) {
            atomicWrite(privateArchive, readLines(global))
        } else {
            check(privateArchive.isFile) { "Download archive generation is not a file" }
        }
        return Generation(downloadId, executionId, privateArchive, global)
    }

    fun delta(generation: Generation): List<String> {
        val privateLines = readLines(generation.privateArchive)
        val globalLines = readLines(generation.globalArchive).toSet()
        return privateLines.filterNot { it in globalLines }.distinct()
    }

    fun promote(
        generation: Generation,
        fallbackDelta: List<String> = emptyList(),
    ): Boolean = synchronized(promotionLock) {
        val privateLines = if (generation.privateArchive.exists()) {
            (readLinesStrict(generation.privateArchive) + fallbackDelta).distinct()
        } else {
            fallbackDelta.distinct()
        }
        val globalLines = readLines(generation.globalArchive)
        val merged = LinkedHashSet<String>(globalLines.size + privateLines.size).apply {
            addAll(globalLines)
            addAll(privateLines)
        }.toList()
        if (merged != globalLines) atomicWrite(generation.globalArchive, merged)
        val verified = readLinesStrict(generation.globalArchive)
        val complete = privateLines.all { it in verified }
        if (complete && generation.privateArchive.exists()) {
            check(generation.privateArchive.delete() || !generation.privateArchive.exists()) {
                "Could not retire promoted Download archive generation"
            }
        }
        complete
    }

    fun promote(
        context: Context,
        downloadId: Long,
        executionId: String,
        fallbackDelta: List<String> = emptyList(),
    ): Boolean = promote(
        prepare(context, downloadId, executionId),
        fallbackDelta = fallbackDelta,
    )

    internal fun stableKey(downloadId: Long, executionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$downloadId\n$executionId".toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    internal fun readLines(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return readLinesStrict(file)
    }

    private fun readLinesStrict(file: File): List<String> {
        check(file.isFile) { "Download archive is not a regular file: ${file.absolutePath}" }
        return file.readLines(StandardCharsets.UTF_8)
            .map(String::trimEnd)
            .filter(String::isNotBlank)
    }

    private fun atomicWrite(file: File, lines: List<String>) {
        val parent = file.parentFile ?: error("Download archive has no parent")
        check(parent.exists() || parent.mkdirs()) { "Could not create archive parent" }
        val temporary = File(parent, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(temporary).use { output ->
                output.writer(StandardCharsets.UTF_8).use { writer ->
                    if (lines.isNotEmpty()) writer.write(lines.joinToString("\n") + "\n")
                }
                (syncForTesting ?: { stream -> stream.fd.sync() })(output)
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                check(temporary.renameTo(file) || !temporary.exists()) {
                    "Could not replace Download archive atomically"
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
