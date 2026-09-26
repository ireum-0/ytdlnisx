package com.ireum.ytdl.util.storage

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/**
 * Durable evidence for one download-archive generation and the exact configured
 * authority it is allowed to promote into.
 *
 * A SAF provider may partially truncate or rewrite the authoritative document
 * and then fail.  The document can remain readable while holding only part of
 * the intended contents, so a readable provider is not proof of a complete
 * archive.  A record with [promotionUnresolved] is written durably before any
 * destructive provider replacement and survives process death, which lets
 * ordinary duplicate admission fail closed until recovery verifies the provider
 * again.
 *
 * The same record binds the exact authority identity the generation was created
 * under, and it is written when the generation takes responsibility, before its
 * private archive can carry any promotion debt.  Recovery of a surviving
 * generation therefore resolves its target from that durable identity rather
 * than from the current preference, so a later `download_archive_path` change
 * cannot redirect old debt onto a newly selected archive.
 *
 * Only provider authority is fenced.  A raw app-owned archive is replaced
 * atomically, so its existing durability boundary is unchanged; a raw
 * generation is still bound, because a raw path can be reconfigured too.
 */
internal data class DownloadArchiveProviderFenceRecord(
    val version: Int = SCHEMA_VERSION,
    val generationKey: String = "",
    val authorityIdentity: String = "",
    val promotionUnresolved: Boolean = false,
    val downloadId: Long = 0L,
    val executionId: String = "",
    val createdAt: Long = 0L,
) {
    companion object {
        const val SCHEMA_VERSION = 2
    }
}

internal object DownloadArchiveProviderFence {
    private const val DIRECTORY = "download-archive-generations"
    private const val FILE_PREFIX = "provider-promotion-fence-"
    private const val FILE_SUFFIX = ".json"
    private val gson = Gson()
    private val lock = Any()

    /**
     * True while a recorded promotion against this exact configured authority
     * is unresolved.
     *
     * A binding-only record carries no unresolved promotion, so it withholds
     * nothing.  A record that cannot be read, or that does not describe its own
     * generation, cannot be attributed to any authority at all, and unknown
     * fence state must never be read as a resolved provider.
     */
    fun isUnresolved(context: Context, authority: ConfiguredDownloadArchive): Boolean {
        val identity = ConfiguredDownloadArchiveStore.identityKey(authority)
        return synchronized(lock) {
            recordFiles(context).any { file ->
                val record = decode(file)
                record == null || (record.promotionUnresolved && record.authorityIdentity == identity)
            }
        }
    }

    /**
     * Durable evidence for one exact generation.  A present but unreadable or
     * incomplete record is reported as corrupt so recovery fails closed rather
     * than adopting the current preference.
     */
    fun readForGeneration(
        context: Context,
        generationKey: String,
    ): Result = synchronized(lock) {
        val file = fenceFile(context, generationKey)
        if (!file.exists()) return@synchronized Result.MISSING
        val record = decode(file, generationKey)
        if (record == null) Result.CORRUPT else Result.Recorded(record)
    }

    /**
     * Binds a generation to the exact configured authority it was created
     * under.  Callers must invoke this before the generation's private archive
     * can carry promotion debt, so process death can never leave debt whose
     * recovery authority is known only from a later preference.
     *
     * Binding alone withholds nothing; it only fixes the one authority this
     * generation may ever promote into.
     */
    fun bind(
        context: Context,
        generationKey: String,
        authority: ConfiguredDownloadArchive,
        downloadId: Long,
        executionId: String,
    ) {
        // A persisted value that is not a storage authority could never be
        // honoured on recovery, so it is never bound in the first place.
        if (authority is ConfiguredDownloadArchive.Unresolved) return
        write(
            context = context,
            generationKey = generationKey,
            authorityIdentity = ConfiguredDownloadArchiveStore.identityKey(authority),
            downloadId = downloadId,
            executionId = executionId,
            unresolved = false,
        )
    }

    /**
     * Makes the unresolved promotion against the generation's bound authority
     * durable.  Callers must invoke this before the first destructive provider
     * write.
     *
     * Only provider authority is fenced; a raw archive is replaced atomically
     * and keeps its existing durability boundary.
     */
    fun install(
        context: Context,
        generationKey: String,
        authority: ConfiguredDownloadArchive,
        downloadId: Long,
        executionId: String,
    ) {
        if (authority !is ConfiguredDownloadArchive.SafTree) return
        write(
            context = context,
            generationKey = generationKey,
            authorityIdentity = ConfiguredDownloadArchiveStore.identityKey(authority),
            downloadId = downloadId,
            executionId = executionId,
            unresolved = true,
        )
    }

    /**
     * Retires the record for one exact generation, releasing both the binding
     * and the fence.  Callers must invoke this only after the provider has been
     * verified to contain the complete intended contents.
     */
    fun clearForGeneration(context: Context, generationKey: String) {
        synchronized(lock) {
            val file = fenceFile(context, generationKey)
            if (file.exists()) {
                check(file.delete() || !file.exists()) {
                    "Could not retire resolved provider archive promotion fence"
                }
            }
        }
    }

    internal fun clearAllForTesting(context: Context) {
        synchronized(lock) {
            recordFiles(context).forEach { runCatching { it.delete() } }
        }
    }

    /**
     * Persists one record durably.  Promotion debt may never be redirected onto
     * another authority, so an existing unresolved record naming a different
     * authority is a fail-closed conflict.  A binding-only record carries no
     * debt, so a generation that never took responsibility may still follow the
     * current preference.
     */
    private fun write(
        context: Context,
        generationKey: String,
        authorityIdentity: String,
        downloadId: Long,
        executionId: String,
        unresolved: Boolean,
    ) {
        val file = fenceFile(context, generationKey)
        synchronized(lock) {
            val existing = if (file.exists()) {
                decode(file, generationKey) ?: throw IllegalStateException(
                    "Refusing to overwrite an unreadable download archive generation record",
                )
            } else {
                null
            }
            if (existing != null && existing.promotionUnresolved) {
                check(existing.authorityIdentity == authorityIdentity) {
                    "Refusing to rebind a download archive generation that still owes a promotion"
                }
            }
            val record = DownloadArchiveProviderFenceRecord(
                generationKey = generationKey,
                authorityIdentity = authorityIdentity,
                // A later bind never silently downgrades an active fence.
                promotionUnresolved = unresolved || (existing?.promotionUnresolved ?: false),
                downloadId = downloadId,
                executionId = executionId,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
            DownloadArchiveAuthority.writeDurably(file, gson.toJson(record))
        }
    }

    /**
     * Reads one record, or `null` when it cannot be trusted.  A record must
     * carry the current schema, a complete identity, and the very generation key
     * its own file name is derived from, so a record can never be adopted on
     * behalf of another generation.
     */
    private fun decode(
        file: File,
        expectedGenerationKey: String? = null,
    ): DownloadArchiveProviderFenceRecord? {
        val record = runCatching {
            gson.fromJson(
                file.readText(Charsets.UTF_8),
                DownloadArchiveProviderFenceRecord::class.java,
            )
        }.getOrNull() ?: return null
        if (record.version != DownloadArchiveProviderFenceRecord.SCHEMA_VERSION) return null
        if (record.generationKey.isBlank() || record.authorityIdentity.isBlank()) return null
        if (file.name != fileName(record.generationKey)) return null
        if (expectedGenerationKey != null && record.generationKey != expectedGenerationKey) return null
        return record
    }

    private fun recordFiles(context: Context): List<File> = directory(context).listFiles()
        .orEmpty()
        .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }

    private fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    private fun fenceFile(context: Context, generationKey: String): File =
        File(directory(context), fileName(generationKey))

    private fun fileName(generationKey: String): String =
        "$FILE_PREFIX${digest(generationKey)}$FILE_SUFFIX"

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    /** Durable fence lookup outcome for one exact generation. */
    internal sealed interface Result {
        data object MISSING : Result
        data object CORRUPT : Result
        data class Recorded(val record: DownloadArchiveProviderFenceRecord) : Result
    }
}
