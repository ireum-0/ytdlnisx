package com.ireum.ytdl.util.storage

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Keeps yt-dlp's per-generation archive separate from the app-global
 * duplicate authority.  The global side keeps its real storage authority: a
 * raw file is written atomically, while a persisted SAF tree is read, merged
 * and written through the provider grant.  The global archive is promoted only
 * after the caller has durably committed the app-level primary result, and the
 * private generation is retired only after the configured authority is
 * verified to contain the promoted delta.
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

    /** Observes the production sync boundary without replacing the sync. */
    internal var beforeSyncForTesting: ((FileOutputStream) -> Unit)? = null

    data class Generation(
        val downloadId: Long,
        val executionId: String,
        val privateArchive: File,
        val configuredArchive: ConfiguredDownloadArchive,
    )

    fun prepare(context: Context, downloadId: Long, executionId: String): Generation {
        require(executionId.isNotBlank())
        val configured = ConfiguredDownloadArchiveStore.resolve(context)
        val root = File(context.filesDir, GENERATION_DIRECTORY).canonicalFile
        check(root.exists() || root.mkdirs()) { "Could not create Download archive generation directory" }
        val privateArchive = File(root, "${stableKey(downloadId, executionId)}.txt").canonicalFile
        if (!privateArchive.exists()) {
            // An unreadable configured archive must never seed a private
            // generation that looks empty: yt-dlp would then run with a
            // duplicate authority that silently lost its existing entries.
            val seeded = when (val read = ConfiguredDownloadArchiveStore.read(context, configured)) {
                is ConfiguredDownloadArchiveRead.Available -> read.lines
                is ConfiguredDownloadArchiveRead.Unavailable ->
                    throw DownloadArchiveUnavailableException(read.reason)
            }
            atomicWrite(privateArchive, seeded)
        } else {
            check(privateArchive.isFile) { "Download archive generation is not a file" }
        }
        return Generation(downloadId, executionId, privateArchive, configured)
    }

    fun delta(context: Context?, generation: Generation): List<String> {
        val privateLines = readLines(generation.privateArchive)
        val known = when (
            val read = ConfiguredDownloadArchiveStore.read(context, generation.configuredArchive)
        ) {
            is ConfiguredDownloadArchiveRead.Available -> read.lines.toSet()
            // An unreadable configured archive cannot narrow the delta.
            is ConfiguredDownloadArchiveRead.Unavailable -> emptySet()
        }
        return privateLines.filterNot { it in known }.distinct()
    }

    fun promote(
        context: Context?,
        generation: Generation,
        fallbackDelta: List<String> = emptyList(),
    ): Boolean = synchronized(promotionLock) {
        val privateLines = if (generation.privateArchive.exists()) {
            (readLinesStrict(generation.privateArchive) + fallbackDelta).distinct()
        } else {
            fallbackDelta.distinct()
        }
        val configured = generation.configuredArchive
        val existing = when (val read = ConfiguredDownloadArchiveStore.read(context, configured)) {
            is ConfiguredDownloadArchiveRead.Available -> read.lines
            is ConfiguredDownloadArchiveRead.Unavailable -> return@synchronized false
        }
        val merged = LinkedHashSet<String>(existing.size + privateLines.size).apply {
            addAll(existing)
            addAll(privateLines)
        }.toList()
        if (merged != existing) {
            ConfiguredDownloadArchiveStore.replace(context, configured, merged)
        }
        // A provider write that returned without error is not proof. Re-read
        // through the same authority and keep the private generation unless the
        // promoted delta is actually present.
        val verified = when (
            val read = ConfiguredDownloadArchiveStore.read(context, configured)
        ) {
            is ConfiguredDownloadArchiveRead.Available -> read.lines
            is ConfiguredDownloadArchiveRead.Unavailable -> return@synchronized false
        }.toSet()
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
        context,
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

    /** Checked durable replacement used for every raw archive write. */
    internal fun writeAtomically(file: File, lines: List<String>) = atomicWrite(file, lines)

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
                // Keep the descriptor open through the complete durability
                // boundary. Closing a Writer backed by this stream would
                // close the stream before sync() on production runtimes.
                if (lines.isNotEmpty()) {
                    output.write((lines.joinToString("\n") + "\n")
                        .toByteArray(StandardCharsets.UTF_8))
                }
                output.flush()
                beforeSyncForTesting?.invoke(output)
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
