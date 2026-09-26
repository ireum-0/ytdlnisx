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
 *
 * A generation is bound to the exact configured authority it was created under,
 * and that binding is durable before the private archive can carry any debt.
 * Promotion therefore always targets the authority the generation was created
 * against, never whatever `download_archive_path` happens to name later.
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
        val generationKey = stableKey(downloadId, executionId)
        val root = File(context.filesDir, GENERATION_DIRECTORY).canonicalFile
        check(root.exists() || root.mkdirs()) { "Could not create Download archive generation directory" }
        val privateArchive = File(root, "$generationKey.txt").canonicalFile
        val survivingGeneration = privateArchive.exists()
        check(!survivingGeneration || privateArchive.isFile) {
            "Download archive generation is not a file"
        }

        // A generation that already carries debt is bound to the exact authority
        // recorded when it took responsibility.  Recovery must never re-resolve
        // the mutable preference here, or a later download_archive_path change
        // would promote A-derived contents into a newly selected B.
        val binding = DownloadArchiveProviderFence.readForGeneration(context, generationKey)
        if (binding is DownloadArchiveProviderFence.Result.CORRUPT) {
            // Unreadable durable state is unknown, not the current preference.
            throw DownloadArchiveUnavailableException(
                "Download archive generation authority identity is unreadable",
            )
        }
        val record = (binding as? DownloadArchiveProviderFence.Result.Recorded)?.record
        val carriesDebt = survivingGeneration || record?.promotionUnresolved == true
        val configured = if (carriesDebt) {
            val identity = record?.authorityIdentity
                ?: throw DownloadArchiveUnavailableException(
                    // A surviving generation with no durable identity cannot be
                    // redirected to whatever the preference happens to name now.
                    "Download archive generation authority identity is missing",
                )
            ConfiguredDownloadArchiveStore.authorityFromIdentity(identity)
                ?: throw DownloadArchiveUnavailableException(
                    "Download archive generation authority identity is not a usable location",
                )
        } else {
            ConfiguredDownloadArchiveStore.resolve(context)
        }

        if (!survivingGeneration) {
            // An unreadable configured archive must never seed a private
            // generation that looks empty: yt-dlp would then run with a
            // duplicate authority that silently lost its existing entries.
            val seeded = when (val read = ConfiguredDownloadArchiveStore.read(context, configured)) {
                is ConfiguredDownloadArchiveRead.Available -> read.lines
                is ConfiguredDownloadArchiveRead.Unavailable ->
                    throw DownloadArchiveUnavailableException(read.reason)
            }
            // Bound durably only once the source archive is known readable, and
            // always before the private archive can carry promotion debt.  A
            // crash here leaves a binding with no debt, which the next attempt
            // may still re-point at the current preference.
            DownloadArchiveProviderFence.bind(
                context = context,
                generationKey = generationKey,
                authority = configured,
                downloadId = downloadId,
                executionId = executionId,
            )
            atomicWrite(privateArchive, seeded)
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
        // Promotion owns this authority, so it inspects the provider through
        // the repair read rather than the admission read.
        val existing = when (
            val read = ConfiguredDownloadArchiveStore.readForPromotion(context, configured)
        ) {
            is ConfiguredDownloadArchiveRead.Available -> read.lines
            is ConfiguredDownloadArchiveRead.Unavailable -> return@synchronized false
        }
        val merged = LinkedHashSet<String>(existing.size + privateLines.size).apply {
            addAll(existing)
            addAll(privateLines)
        }.toList()
        // Durable before the first destructive provider write, and retained
        // across process death, provider failure and verification failure.
        if (context != null) {
            DownloadArchiveProviderFence.install(
                context = context,
                generationKey = stableKey(generation.downloadId, generation.executionId),
                authority = configured,
                downloadId = generation.downloadId,
                executionId = generation.executionId,
            )
        }
        if (merged != existing) {
            ConfiguredDownloadArchiveStore.replace(context, configured, merged)
        }
        // A provider write that returned without error is not proof. Re-read
        // through the same authority and keep the private generation and the
        // fence unless the promoted delta is actually present.
        val verified = when (
            val read = ConfiguredDownloadArchiveStore.readForPromotion(context, configured)
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
        if (complete && context != null) {
            // The provider is verified complete, so admission may trust it again.
            DownloadArchiveProviderFence.clearForGeneration(
                context,
                stableKey(generation.downloadId, generation.executionId),
            )
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
    internal fun writeAtomically(file: File, lines: List<String>) =
        writeDurably(file, ConfiguredDownloadArchiveStore.serialize(lines))

    /**
     * Checked durable replacement of exact text.  The descriptor stays open
     * through the sync boundary and the rename is atomic, so a reader never
     * observes a partially written durable record.
     */
    internal fun writeDurably(file: File, text: String) {
        val parent = file.parentFile ?: error("Durable record has no parent")
        check(parent.exists() || parent.mkdirs()) { "Could not create record parent" }
        val temporary = File(parent, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(temporary).use { output ->
                if (text.isNotEmpty()) {
                    output.write(text.toByteArray(StandardCharsets.UTF_8))
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
                    "Could not replace durable record atomically"
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun readLinesStrict(file: File): List<String> {
        check(file.isFile) { "Download archive is not a regular file: ${file.absolutePath}" }
        return file.readLines(StandardCharsets.UTF_8)
            .map(String::trimEnd)
            .filter(String::isNotBlank)
    }

    private fun atomicWrite(file: File, lines: List<String>) = writeAtomically(file, lines)
}
