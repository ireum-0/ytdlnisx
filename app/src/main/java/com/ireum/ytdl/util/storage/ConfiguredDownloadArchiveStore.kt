package com.ireum.ytdl.util.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.io.File

/**
 * Typed storage authority for the configured duplicate-prevention archive.
 *
 * A persisted `content://` tree URI keeps provider authority. It is never
 * reconstructed into a filesystem pathname: neither raw [File] I/O nor native
 * yt-dlp can reach whatever the provider actually stores, so treating a
 * provider URI as a [File] silently downgrades a valid archive to "absent".
 */
internal sealed interface ConfiguredDownloadArchive {
    /** App/raw filesystem authority backed by a real [File]. */
    data class RawFile(val file: File) : ConfiguredDownloadArchive

    /** SAF provider/tree authority backed by the persisted tree URI. */
    data class SafTree(val treeUri: Uri) : ConfiguredDownloadArchive

    /** Persisted value that is neither a usable raw path nor a usable tree URI. */
    data class Unresolved(val persistedValue: String) : ConfiguredDownloadArchive

    /** Direct filesystem authority, or `null` when only a provider owns it. */
    val rawFileOrNull: File?
        get() = (this as? RawFile)?.file

    /** Provider authority, or `null` when the archive is app/raw owned. */
    val treeUriOrNull: Uri?
        get() = (this as? SafTree)?.treeUri
}

/**
 * Result of reading the configured archive.  An unavailable authority is never
 * reported as an empty archive, because "could not be read" and "contains no
 * entries" have opposite duplicate-protection meaning.
 */
internal sealed interface ConfiguredDownloadArchiveRead {
    data class Available(val lines: List<String>) : ConfiguredDownloadArchiveRead

    data class Unavailable(val reason: String) : ConfiguredDownloadArchiveRead

    val linesOrNull: List<String>?
        get() = (this as? Available)?.lines

    val isAvailable: Boolean
        get() = this is Available
}

/** Raised when a persisted provider tree cannot be read or written at all. */
internal class DownloadArchiveUnavailableException(message: String) : Exception(message)

/**
 * Narrow provider seam for the configured archive document.  Production uses
 * the persisted SAF tree grant; tests supply a fake instead of mocking
 * Android storage.
 */
internal interface ConfiguredDownloadArchiveProvider {
    /** `null` when the archive document does not exist yet in an accessible tree. */
    fun readText(context: Context, treeUri: Uri): String?

    /** Replaces the archive document contents, creating it when absent. */
    fun replaceText(context: Context, treeUri: Uri, text: String)
}

internal object ConfiguredDownloadArchiveStore {
    const val PREFERENCE_KEY = "download_archive_path"
    const val ARCHIVE_FILE_NAME = "download_archive.txt"

    private const val TAG = "DownloadArchiveStore"

    /** Deterministic provider seam; production resolves the persisted grant. */
    @Volatile
    internal var providerForTesting: ConfiguredDownloadArchiveProvider? = null

    fun resolve(context: Context): ConfiguredDownloadArchive {
        val persisted = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREFERENCE_KEY, "")
            ?.trim()
            .orEmpty()
        if (persisted.isEmpty()) {
            return ConfiguredDownloadArchive.RawFile(defaultArchiveFile(context))
        }
        val uri = Uri.parse(persisted)
        if (ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true) &&
            !uri.authority.isNullOrBlank()
        ) {
            return ConfiguredDownloadArchive.SafTree(uri)
        }
        // Historical semantics treated every non-empty stored value as the
        // archive FOLDER and appended the archive file name.  A legacy raw
        // folder must never be reinterpreted as the archive file itself, so
        // the folder interpretation is preserved for absolute raw values.
        return if (File(persisted).isAbsolute) {
            ConfiguredDownloadArchive.RawFile(File(persisted, ARCHIVE_FILE_NAME))
        } else {
            Log.w(TAG, "Configured download archive is not a usable location")
            ConfiguredDownloadArchive.Unresolved(persisted)
        }
    }

    /**
     * Ordinary admission read.  While a provider promotion fence is
     * unresolved this authority is UNAVAILABLE even when the provider document
     * itself is readable, because a partially written document can be
     * readable and still incomplete.
     */
    fun read(
        context: Context?,
        authority: ConfiguredDownloadArchive,
    ): ConfiguredDownloadArchiveRead {
        if (context != null && DownloadArchiveProviderFence.isUnresolved(context, authority)) {
            return ConfiguredDownloadArchiveRead.Unavailable(
                "provider archive promotion is unresolved",
            )
        }
        return readProviderState(context, authority)
    }

    /**
     * Internal repair/verification read used by exact promotion and recovery.
     * It deliberately ignores this promotion's own fence so recovery can
     * inspect and converge the provider it is responsible for.
     */
    internal fun readForPromotion(
        context: Context?,
        authority: ConfiguredDownloadArchive,
    ): ConfiguredDownloadArchiveRead = readProviderState(context, authority)

    private fun readProviderState(
        context: Context?,
        authority: ConfiguredDownloadArchive,
    ): ConfiguredDownloadArchiveRead = when (authority) {
        is ConfiguredDownloadArchive.RawFile -> runCatching {
            DownloadArchiveAuthority.readLines(authority.file)
        }.fold(
            onSuccess = { ConfiguredDownloadArchiveRead.Available(it) },
            onFailure = { failure ->
                ConfiguredDownloadArchiveRead.Unavailable(
                    "raw archive could not be read: ${failure.javaClass.simpleName}",
                )
            },
        )

        is ConfiguredDownloadArchive.SafTree -> {
            // A provider archive without a Context cannot be resolved. That is
            // unknown membership, never an empty archive.
            val resolver = context
                ?: return ConfiguredDownloadArchiveRead.Unavailable(
                    "provider archive requires an Android context",
                )
            runCatching {
                provider().readText(resolver, authority.treeUri)
            }.fold(
                onSuccess = { text ->
                    ConfiguredDownloadArchiveRead.Available(parseLines(text.orEmpty()))
                },
                onFailure = { failure ->
                    ConfiguredDownloadArchiveRead.Unavailable(
                        "provider archive could not be read: ${failure.javaClass.simpleName}",
                    )
                },
            )
        }

        is ConfiguredDownloadArchive.Unresolved ->
            ConfiguredDownloadArchiveRead.Unavailable("configured archive authority is not a usable location")
    }

    /**
     * Replaces the configured archive contents.  Throws on any storage
     * failure so a caller can never read a returned success as proof that the
     * provider accepted the write.
     */
    fun replace(
        context: Context?,
        authority: ConfiguredDownloadArchive,
        lines: List<String>,
    ) {
        when (authority) {
            is ConfiguredDownloadArchive.RawFile ->
                DownloadArchiveAuthority.writeAtomically(authority.file, lines)

            is ConfiguredDownloadArchive.SafTree -> {
                val resolver = context
                    ?: throw DownloadArchiveUnavailableException(
                        "provider archive requires an Android context",
                    )
                provider().replaceText(resolver, authority.treeUri, serialize(lines))
            }

            is ConfiguredDownloadArchive.Unresolved ->
                throw DownloadArchiveUnavailableException(
                    "configured archive authority is not a usable location",
                )
        }
    }

    /**
     * The exact path native yt-dlp may use for `--download-archive`.  A
     * provider-backed archive has no such path and must never be synthesized.
     */
    fun nativeArchivePathOrNull(context: Context): String? =
        resolve(context).rawFileOrNull?.absolutePath

    internal fun parseLines(text: String): List<String> = text
        .split('\n')
        .map(String::trimEnd)
        .filter(String::isNotBlank)

    internal fun serialize(lines: List<String>): String =
        if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"

    /** Human-readable summary. Display formatting never becomes authority. */
    fun describe(context: Context): String = when (val authority = resolve(context)) {
        is ConfiguredDownloadArchive.RawFile -> authority.file.absolutePath
        is ConfiguredDownloadArchive.SafTree ->
            "Provider folder (${authority.treeUri.authority.orEmpty()})"
        is ConfiguredDownloadArchive.Unresolved -> "Not a usable folder"
    }

    private fun provider(): ConfiguredDownloadArchiveProvider =
        providerForTesting ?: SafDownloadArchiveProvider

    private fun defaultArchiveFile(context: Context): File {
        val external = context.getExternalFilesDir(null)
        val folder = if (external == null) context.cacheDir else external
        return File(folder, ARCHIVE_FILE_NAME)
    }
}

/**
 * Production provider access for a persisted `ACTION_OPEN_DOCUMENT_TREE`
 * grant.  Tree accessibility is proven before a missing document is reported,
 * because `DocumentFile.findFile` answers `null` for both an absent child and
 * a revoked grant.
 */
private object SafDownloadArchiveProvider : ConfiguredDownloadArchiveProvider {
    override fun readText(context: Context, treeUri: Uri): String? {
        val tree = accessibleTree(context, treeUri)
        val child = tree.findFile(ConfiguredDownloadArchiveStore.ARCHIVE_FILE_NAME) ?: return null
        if (!child.isFile) {
            throw DownloadArchiveUnavailableException("archive entry is not a file")
        }
        return context.contentResolver.openInputStream(child.uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw DownloadArchiveUnavailableException("archive entry could not be opened")
    }

    override fun replaceText(context: Context, treeUri: Uri, text: String) {
        val tree = accessibleTree(context, treeUri)
        val existing = tree.findFile(ConfiguredDownloadArchiveStore.ARCHIVE_FILE_NAME)
        val target = existing?.takeIf { it.isFile }
            ?: tree.createFile("text/plain", ConfiguredDownloadArchiveStore.ARCHIVE_FILE_NAME)
            ?: throw DownloadArchiveUnavailableException("archive entry could not be created")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        } ?: throw DownloadArchiveUnavailableException("archive entry could not be written")
    }

    private fun accessibleTree(context: Context, treeUri: Uri): DocumentFile {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw DownloadArchiveUnavailableException("archive tree could not be opened")
        if (!tree.exists() || !tree.isDirectory) {
            throw DownloadArchiveUnavailableException("archive tree is not accessible")
        }
        return tree
    }
}
