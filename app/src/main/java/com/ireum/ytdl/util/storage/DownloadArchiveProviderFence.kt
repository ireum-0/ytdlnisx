package com.ireum.ytdl.util.storage

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/**
 * Durable evidence that a provider-backed archive promotion is unresolved.
 *
 * A SAF provider may partially truncate or rewrite the authoritative document
 * and then fail.  The document can remain readable while holding only part of
 * the intended contents, so a readable provider is not proof of a complete
 * archive.  This fence is written durably before any destructive provider
 * replacement and survives process death, which lets ordinary duplicate
 * admission fail closed until recovery verifies the provider again.
 *
 * Only provider authority is fenced.  Raw app-owned archives are replaced
 * atomically, so their existing durability boundary is unchanged.
 */
internal data class DownloadArchiveProviderFenceRecord(
    val version: Int = SCHEMA_VERSION,
    val authorityKey: String = "",
    val downloadId: Long = 0L,
    val executionId: String = "",
    val generationKey: String = "",
    val createdAt: Long = 0L,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

internal object DownloadArchiveProviderFence {
    private const val DIRECTORY = "download-archive-generations"
    private const val FILE_PREFIX = "provider-promotion-fence-"
    private const val FILE_SUFFIX = ".json"
    private val gson = Gson()
    private val lock = Any()

    /** Stable identity for one provider promotion responsibility. */
    fun authorityKey(treeUri: Uri): String = "saf:${treeUri}"

    /**
     * True while any promotion against this configured authority is
     * unresolved.  An unreadable or corrupt fence still counts: unknown fence
     * state must never be read as a resolved provider.
     */
    fun isUnresolved(context: Context, authority: ConfiguredDownloadArchive): Boolean {
        val treeUri = authority.treeUriOrNull ?: return false
        return fenceFile(context, treeUri).exists()
    }

    /** Best-effort fence evidence for recovery. Unreadable is not cleared. */
    fun read(
        context: Context,
        authority: ConfiguredDownloadArchive,
    ): DownloadArchiveProviderFenceRecord? {
        val treeUri = authority.treeUriOrNull ?: return null
        return synchronized(lock) {
            runCatching {
                gson.fromJson(
                    fenceFile(context, treeUri).readText(Charsets.UTF_8),
                    DownloadArchiveProviderFenceRecord::class.java,
                )
            }.getOrNull()
        }
    }

    /**
     * Makes the unresolved promotion durable.  Callers must invoke this before
     * the first destructive provider write.
     */
    fun install(
        context: Context,
        authority: ConfiguredDownloadArchive,
        downloadId: Long,
        executionId: String,
        generationKey: String,
    ) {
        val treeUri = authority.treeUriOrNull ?: return
        val record = DownloadArchiveProviderFenceRecord(
            authorityKey = authorityKey(treeUri),
            downloadId = downloadId,
            executionId = executionId,
            generationKey = generationKey,
            createdAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            DownloadArchiveAuthority.writeDurably(
                fenceFile(context, treeUri),
                gson.toJson(record),
            )
        }
    }

    /**
     * Retires the fence.  Callers must invoke this only after the provider has
     * been verified to contain the complete intended contents.
     */
    fun clear(context: Context, authority: ConfiguredDownloadArchive) {
        val treeUri = authority.treeUriOrNull ?: return
        synchronized(lock) {
            val file = fenceFile(context, treeUri)
            if (file.exists()) {
                check(file.delete() || !file.exists()) {
                    "Could not retire resolved provider archive promotion fence"
                }
            }
        }
    }

    internal fun clearAllForTesting(context: Context) {
        synchronized(lock) {
            directory(context).listFiles()
                .orEmpty()
                .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
                .forEach { runCatching { it.delete() } }
        }
    }

    private fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    private fun fenceFile(context: Context, treeUri: Uri): File = File(
        directory(context),
        "$FILE_PREFIX${digest(authorityKey(treeUri))}$FILE_SUFFIX",
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
