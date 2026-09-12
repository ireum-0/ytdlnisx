package com.ireum.ytdl.util

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.ireum.ytdl.App
import com.ireum.ytdl.R
import com.ireum.ytdl.util.storage.OpenStoredLocationResult
import com.ireum.ytdl.util.storage.StoredLocation
import com.ireum.ytdl.util.storage.StoredLocationKind
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.internal.closeQuietly
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.math.log10
import kotlin.math.pow


object FileUtil {

    @Volatile
    private var lastMoveFailureDetails: String? = null
    private const val SHARED_FILE_PROVIDER_DIR = "shared"
    private const val MAX_SHARE_CACHE_COPY_BYTES = 50L * 1024L * 1024L

    private val blockedShareFileNames = setOf(
        "cookies.txt",
        "keystore.properties",
        "local.properties",
        ".env"
    )

    private enum class StoredFileOperationFailure {
        NOT_FOUND,
        PERMISSION_REQUIRED,
        INVALID_LOCATION,
        UNSUPPORTED,
        UNKNOWN
    }

    private data class PreparedStoredFile(
        val uri: Uri? = null,
        val failure: StoredFileOperationFailure? = null
    )

    fun deleteFile(path: String){
        runCatching {
            val normalizedPath = path.trim()
            if (normalizedPath.isBlank()) return@runCatching

            val deleted = when {
                normalizedPath.startsWith("content://") -> {
                    deleteDocumentUri(Uri.parse(normalizedPath))
                }
                normalizedPath.startsWith("file://") -> {
                    val filePath = Uri.parse(normalizedPath).path.orEmpty()
                    deleteRawFilePath(filePath)
                }
                else -> {
                    deleteRawFilePath(normalizedPath)
                }
            }

            if (!deleted) {
                Log.w("FileUtil", "deleteFile fallback failed target=${safeDiagnosticLocation(normalizedPath)}")
            }
        }
    }

    fun deleteFileWithZeroByteSiblings(path: String) {
        // The historical name is retained for source compatibility, but a
        // deletion authorization is scoped to the exact target only.  A
        // sibling's stem, extension, or zero-byte state is not provenance.
        deleteFile(path)
    }

    fun deleteFilesWithZeroByteSiblings(paths: List<String>) {
        paths.forEach { path ->
            deleteFileWithZeroByteSiblings(path)
        }
    }

    fun cleanupDeletedRawFileArtifacts(path: String) {
        cleanupDeletedRawFileArtifacts(listOf(path))
    }

    fun cleanupDeletedRawFileArtifacts(paths: Collection<String>) {
        val normalizedPaths = paths.map(String::trim).filter(String::isNotBlank).distinct()
        if (normalizedPaths.isEmpty()) return
        normalizedPaths.forEach(::deleteFileFromMediaStore)
    }

    private fun deleteRawFilePath(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        if (file.exists() && file.delete()) {
            deleteFileFromMediaStore(path)
            return true
        }

        val documentDeleted = buildDocumentUriForPath(path)?.let { deleteDocumentUri(it) } == true
        if (documentDeleted) {
            deleteFileFromMediaStore(path)
            return true
        }

        val mediaStoreDeleted = deleteFileFromMediaStore(path)
        if (mediaStoreDeleted) {
            return true
        }

        return !file.exists()
    }

    private fun deleteDocumentUri(uri: Uri): Boolean {
        if (DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(App.instance, uri)) {
            Log.w("FileUtil", "Refusing to delete a SAF tree root")
            return false
        }
        val single = DocumentFile.fromSingleUri(App.instance, uri)
        if (single?.exists() == true && single.delete()) {
            return true
        }
        return runCatching {
            App.instance.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }

    private fun deleteFileFromMediaStore(path: String): Boolean {
        val contentResolver = App.instance.contentResolver
        val file = File(path)
        val uri = MediaStore.Files.getContentUri("external")

        // DATA is the only path field that identifies this exact raw target.
        // A RELATIVE_PATH + DISPLAY_NAME predicate can match multiple MediaStore
        // rows with the same name, so it must never be used as deletion
        // authority for one History path. If the provider no longer exposes
        // DATA, leave its row untouched rather than deleting a sibling.
        val selection = MediaStore.MediaColumns.DATA + " =?"
        val selectionArgs = arrayOf(file.absolutePath)
        return runCatching {
            contentResolver.delete(uri, selection, selectionArgs) > 0
        }.getOrDefault(false)
    }

    fun exists(path: String): Boolean = exists(path, App.instance)

    fun exists(path: String, context: Context) : Boolean {
        if (path.isEmpty()) return false
        if (path.startsWith("content://")) {
            return DocumentFile.fromSingleUri(context, Uri.parse(path))?.exists() == true
        }
        if (path.startsWith("file://")) {
            return runCatching { File(Uri.parse(path).path ?: "").exists() }.getOrDefault(false)
        }
        return File(path).exists()
    }

    /**
     * Provider-aware file identity helpers for stored output paths.  A
     * published SAF/MediaStore path is an exact content URI, not a pathname
     * that can safely be handed to java.io.File.  Keep these checks beside the
     * existing provider-aware existence check so output/failure bookkeeping
     * cannot silently discard an exact provider result.
     */
    fun isFile(path: String, context: Context = App.instance): Boolean {
        val normalized = path.trim()
        if (normalized.isBlank()) return false
        if (normalized.startsWith("content://", ignoreCase = true)) {
            return runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(normalized))?.isFile == true
            }.getOrDefault(false)
        }
        val file = if (normalized.startsWith("file://", ignoreCase = true)) {
            runCatching { File(Uri.parse(normalized).path.orEmpty()) }.getOrNull()
        } else {
            File(normalized)
        } ?: return false
        return file.isFile
    }

    fun length(path: String, context: Context = App.instance): Long {
        val normalized = path.trim()
        if (normalized.isBlank()) return 0L
        if (normalized.startsWith("content://", ignoreCase = true)) {
            return runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(normalized))
                    ?.takeIf { it.isFile }
                    ?.length()
                    ?: 0L
            }.getOrDefault(0L)
        }
        val file = if (normalized.startsWith("file://", ignoreCase = true)) {
            runCatching { File(Uri.parse(normalized).path.orEmpty()) }.getOrNull()
        } else {
            File(normalized)
        } ?: return 0L
        return file.length()
    }

    fun lastModified(path: String, context: Context = App.instance): Long {
        val normalized = path.trim()
        if (normalized.isBlank()) return 0L
        if (normalized.startsWith("content://", ignoreCase = true)) {
            return runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(normalized))
                    ?.takeIf { it.isFile }
                    ?.lastModified()
                    ?: 0L
            }.getOrDefault(0L)
        }
        val file = if (normalized.startsWith("file://", ignoreCase = true)) {
            runCatching { File(Uri.parse(normalized).path.orEmpty()) }.getOrNull()
        } else {
            File(normalized)
        } ?: return 0L
        return file.lastModified()
    }

    fun fileName(path: String, context: Context = App.instance): String {
        val normalized = path.trim()
        if (normalized.isBlank()) return ""
        if (normalized.startsWith("content://", ignoreCase = true)) {
            return runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(normalized))?.name
                    ?: Uri.parse(normalized).lastPathSegment.orEmpty()
            }.getOrDefault("")
        }
        val file = if (normalized.startsWith("file://", ignoreCase = true)) {
            runCatching { File(Uri.parse(normalized).path.orEmpty()) }.getOrNull()
        } else {
            File(normalized)
        } ?: return ""
        return file.name
    }

    fun fileExtension(path: String, context: Context = App.instance): String =
        fileName(path, context).substringAfterLast('.', "")

    fun resolveTreeDocumentUri(treeUriString: String, relativePath: String): Uri? {
        if (treeUriString.isBlank() || relativePath.isBlank()) return null
        val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val rel = relativePath.trimStart('/')
        val docId = if (rel.isBlank()) treeId else "$treeId/$rel"
        return runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, docId) }.getOrNull()
    }

    fun buildDocumentUriForPath(path: String): Uri? {
        val storagePath = parseStorageDocumentPath(path) ?: return null
        val docId = "${storagePath.volumeId}:${storagePath.relativePath}"

        val permissions = App.instance.contentResolver.persistedUriPermissions
        for (perm in permissions) {
            if (!perm.isReadPermission && !perm.isWritePermission) continue
            val treeUri = perm.uri ?: continue
            if (!DocumentsContract.isTreeUri(treeUri)) continue
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
            if (!treeDocId.startsWith("${storagePath.volumeId}:")) continue
            val treePath = treeDocId.substringAfter(':')
            if (
                treePath.isNotEmpty() &&
                storagePath.relativePath != treePath &&
                !storagePath.relativePath.startsWith("$treePath/")
            ) continue
            return runCatching {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            }.getOrNull()
        }
        return null
    }

    fun formatPath(path: String) : String {
        var dataValue = path
        if (dataValue.startsWith("/storage/")) return dataValue
        dataValue = dataValue.replace("content://com.android.externalstorage.documents/tree/", "")
        dataValue = dataValue.replace("content://com.android.externalstorage.documents/document/", "")
        dataValue = dataValue.replace("raw:/storage/", "")
        dataValue = dataValue.replace("^/document/".toRegex(), "")
        dataValue = dataValue.replace("^primary:".toRegex(), "primary/")
        dataValue = dataValue.replace("%3A".toRegex(), "/")
        try {
            dataValue = URLDecoder.decode(dataValue, StandardCharsets.UTF_8.name())
        } catch (ignored: Exception) {
        }
        val pieces = dataValue.split("/").toTypedArray()
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
            .trimEnd('/', '\\')
            .replace('\\', '/')
        val formattedPath = StringBuilder()
        if (pieces[0] == "primary"){
            formattedPath.append(primaryRoot).append("/")
        }else{
            formattedPath.append("/storage/").append(pieces[0]).append("/")
        }
        pieces.forEachIndexed { i, it ->
            if (i > 0 && it.isNotEmpty()){
                formattedPath.append(it).append("/")
            }
        }
        return formattedPath.toString()
    }

    fun canWriteToDestination(path: String, context: Context = App.instance): Boolean {
        val normalizedPath = path.trim()
        if (normalizedPath.isBlank()) return false

        if (normalizedPath.startsWith("content://")) {
            val uri = runCatching { Uri.parse(normalizedPath) }.getOrNull() ?: return false
            val tree = DocumentFile.fromTreeUri(context, uri)
            if (tree?.exists() == true && tree.canWrite()) return true
            val single = DocumentFile.fromSingleUri(context, uri)
            if (single?.exists() == true && single.canWrite()) return true
            return context.contentResolver.persistedUriPermissions.any { perm ->
                perm.isWritePermission && perm.uri == uri
            }
        }

        val rawPath = if (normalizedPath.startsWith("file://")) {
            Uri.parse(normalizedPath).path.orEmpty()
        } else {
            formatPath(normalizedPath)
        }.trim()
        if (rawPath.isBlank()) return false

        val target = File(rawPath)
        if (target.exists()) return target.canWrite()
        val parent = target.parentFile ?: return false
        return parent.exists() && parent.canWrite()
    }

    fun getAvailableFreeSpaceBytes(path: String, context: Context = App.instance): Long? {
        val normalizedPath = path.trim()
        if (normalizedPath.isBlank()) return null

        val rawPath = when {
            normalizedPath.startsWith("content://") -> formatPath(normalizedPath)
            normalizedPath.startsWith("file://") -> Uri.parse(normalizedPath).path.orEmpty()
            else -> formatPath(normalizedPath)
        }.trim().trimEnd('/', '\\')

        if (rawPath.isBlank()) return null

        val target = File(rawPath)
        val probe = when {
            target.exists() -> target
            target.parentFile?.exists() == true -> target.parentFile
            else -> null
        } ?: return null

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                probe.usableSpace
            } else {
                StatFs(probe.absolutePath).availableBytes
            }
        }.getOrNull()?.takeIf { it >= 0L }
    }


    fun consumeLastMoveFailureDetails(): String? {
        val details = lastMoveFailureDetails
        lastMoveFailureDetails = null
        return details
    }

    @Throws(Exception::class)
    suspend fun moveFile(
        originDir: File,
        context: Context,
        destDir: String,
        keepCache: Boolean,
        progress: (p: Int) -> Unit
    ): List<String> = moveFile(
        originDir = originDir,
        context = context,
        destDir = destDir,
        keepCache = keepCache,
        progress = progress,
        onOutput = {},
        sourceFiles = null,
    )

    @Throws(Exception::class)
    suspend fun moveFile(
        originDir: File,
        context: Context,
        destDir: String,
        keepCache: Boolean,
        progress: (p: Int) -> Unit,
        onOutput: (String) -> Unit,
        onOutputWithSource: ((File, String) -> Unit)? = null,
        onOutputReserved: ((File, String) -> Unit)? = null,
        onOutputReservationIntent: ((File) -> Boolean)? = null,
        onOutputReservationUnknown: ((File) -> Boolean)? = null,
        onOutputCommitted: ((File, String) -> Boolean)? = null,
        sourceFiles: List<File>? = null,
        onOutputReservationRolledBack: ((File) -> Boolean)? = null,
    ) : List<String> {
        fun notifyOutput(path: String) {
            runCatching { onOutput(path) }
                .onFailure { error -> Log.w("FileUtil", "Move output observer failed", error) }
        }
        fun notifyOutput(source: File, path: String) {
            if (onOutputCommitted != null && !onOutputCommitted.invoke(source, path)) {
                throw IOException(
                    "Move output could not durably record the exact publication for ${source.absolutePath}",
                )
            }
            notifyOutput(path)
            runCatching { onOutputWithSource?.invoke(source, path) }
                .onFailure { error -> Log.w("FileUtil", "Move source observer failed", error) }
        }
        fun reserveOutput(source: File, path: String) {
            try {
                onOutputReserved?.invoke(source, path)
            } catch (error: Exception) {
                throw IOException(
                    "Move output destination could not be durably reserved for ${source.absolutePath}",
                    error,
                )
            }
        }
        fun reserveOutputIntent(source: File) {
            try {
                if (onOutputReservationIntent != null && !onOutputReservationIntent.invoke(source)) {
                    throw IOException(
                        "Move output reservation intent could not be persisted for ${source.absolutePath}",
                    )
                }
            } catch (error: Exception) {
                throw IOException(
                    "Move output reservation intent could not be persisted for ${source.absolutePath}",
                    error,
                )
            }
        }
        val providerPublicationTracker = ProviderPublicationTracker()
        var providerReservationUnknown = false
        fun markOutputReservationUnknown(source: File) {
            providerReservationUnknown = true
            providerPublicationTracker.unresolvedSideEffect()
            try {
                if (onOutputReservationUnknown != null && !onOutputReservationUnknown.invoke(source)) {
                    throw UnresolvedProviderPublicationException(
                        "Move output reservation status could not be persisted for ${source.absolutePath}",
                    )
                }
            } catch (error: Exception) {
                throw UnresolvedProviderPublicationException(
                    "Move output reservation status could not be persisted for ${source.absolutePath}",
                    error,
                )
            }
        }
        fun markProviderReservationEstablished() {
            providerPublicationTracker.exactReservationEstablished()
        }
        fun markProviderReservationRolledBack(source: File): Boolean {
            val recorded = runCatching {
                onOutputReservationRolledBack?.invoke(source) ?: true
            }.getOrDefault(false)
            if (recorded) {
                providerPublicationTracker.rollbackProven()
            }
            return recorded
        }
        fun throwIfProviderPublicationFenced() {
            when (providerPublicationTracker.outcome) {
                ProviderPublicationOutcome.UNRESOLVED_EXTERNAL_SIDE_EFFECT ->
                    throw UnresolvedProviderPublicationException(
                        "Provider publication completion is unknown; retry and fallback are fenced",
                    )
                ProviderPublicationOutcome.EXACT_SIDE_EFFECT_DURABLE ->
                    throw IOException(
                        "Exact provider publication remains recoverable; fallback is fenced",
                    )
                ProviderPublicationOutcome.NO_EXTERNAL_SIDE_EFFECT -> Unit
            }
        }
        return withContext(Dispatchers.Main){
            lastMoveFailureDetails = null
            val fileList = mutableListOf<String>()
            val moveErrors = mutableListOf<String>()
            var hasMoveFailure = false
            val normalizedDestDir = formatPath(destDir)
            val directFileWrite = !destDir.startsWith("content://") && File(normalizedDestDir).canWrite()
            val dir = File(normalizedDestDir)
            if (directFileWrite && !dir.exists()) dir.mkdirs()
            val shouldPreferPrimaryMediaStore = !destDir.startsWith("content://") && isPrimarySharedStoragePath(normalizedDestDir)
            if (!directFileWrite && shouldPreferPrimaryMediaStore) {
                val mediaStoreMoved = runCatching {
                    moveFilesToPrimaryMediaStore(
                        originDir = originDir,
                        context = context,
                        normalizedDestDir = normalizedDestDir,
                        progress = progress,
                        onOutput = ::notifyOutput,
                        onOutputWithSource = onOutputWithSource,
                        onOutputReserved = ::reserveOutput,
                        onOutputReservationIntent = ::reserveOutputIntent,
                        onOutputReservationUnknown = ::markOutputReservationUnknown,
                        onOutputCommitted = onOutputCommitted,
                        onReservationEstablished = ::markProviderReservationEstablished,
                        onReservationRolledBack = ::markProviderReservationRolledBack,
                        sourceFiles = sourceFiles,
                    )
                }.onFailure { e ->
                    moveErrors.add("MediaStore raw-path move failed raw=$destDir normalized=$normalizedDestDir error=${e.message}")
                }.getOrNull()
                if (mediaStoreMoved != null) {
                    if (!keepCache) {
                        cleanupMovedSourceTree(originDir, sourceFiles)
                    }
                    val scanned = scanMedia(mediaStoreMoved, context)
                    return@withContext scanned.ifEmpty { mediaStoreMoved }
                }
                if (providerReservationUnknown) throwIfProviderPublicationFenced()
                if (providerPublicationTracker.outcome != ProviderPublicationOutcome.NO_EXTERNAL_SIDE_EFFECT) {
                    throwIfProviderPublicationFenced()
                }
            }
            val safDestinationDir = if (!directFileWrite) {
                resolveDestinationDocumentDir(context, destDir, normalizedDestDir)
            } else null
            if (!directFileWrite && safDestinationDir == null) {
                if (!shouldPreferPrimaryMediaStore) {
                    val mediaStoreMoved = runCatching {
                        moveFilesToPrimaryMediaStore(
                            originDir = originDir,
                            context = context,
                            normalizedDestDir = normalizedDestDir,
                            progress = progress,
                            onOutput = ::notifyOutput,
                            onOutputWithSource = onOutputWithSource,
                            onOutputReserved = ::reserveOutput,
                            onOutputReservationIntent = ::reserveOutputIntent,
                            onOutputReservationUnknown = ::markOutputReservationUnknown,
                            onOutputCommitted = onOutputCommitted,
                            onReservationEstablished = ::markProviderReservationEstablished,
                            onReservationRolledBack = ::markProviderReservationRolledBack,
                            sourceFiles = sourceFiles,
                        )
                    }.onFailure { e ->
                        moveErrors.add("MediaStore fallback failed raw=$destDir normalized=$normalizedDestDir error=${e.message}")
                    }.getOrNull()
                    if (mediaStoreMoved != null) {
                        if (!keepCache) {
                            cleanupMovedSourceTree(originDir, sourceFiles)
                        }
                        val scanned = scanMedia(mediaStoreMoved, context)
                        return@withContext scanned.ifEmpty { mediaStoreMoved }
                    }
                    if (providerReservationUnknown) throwIfProviderPublicationFenced()
                    if (providerPublicationTracker.outcome != ProviderPublicationOutcome.NO_EXTERNAL_SIDE_EFFECT) {
                        throwIfProviderPublicationFenced()
                    }
                }

                moveErrors.add("resolveDestinationDocumentDir failed raw=$destDir normalized=$normalizedDestDir perms=${describePersistedUriPermissions(context)}")
            }
            if (!directFileWrite && safDestinationDir != null) {
                val safResult = moveFilesToSafWithExactOutputs(
                    originDir = originDir,
                    context = context,
                    destinationDir = safDestinationDir,
                    progress = progress,
                    onOutput = ::notifyOutput,
                    onOutputWithSource = onOutputWithSource,
                    onOutputReserved = ::reserveOutput,
                    onOutputReservationIntent = ::reserveOutputIntent,
                    onOutputReservationUnknown = ::markOutputReservationUnknown,
                    onOutputCommitted = onOutputCommitted,
                    onReservationEstablished = ::markProviderReservationEstablished,
                    onReservationRolledBack = ::markProviderReservationRolledBack,
                    sourceFiles = sourceFiles,
                )
                if (providerReservationUnknown) throwIfProviderPublicationFenced()
                fileList.addAll(safResult.paths)
                if (safResult.errors.isNotEmpty()) {
                    hasMoveFailure = true
                    moveErrors.addAll(safResult.errors)
                }
                if (!keepCache && !hasMoveFailure) {
                    cleanupMovedSourceTree(originDir, sourceFiles)
                }
                if (fileList.isEmpty()) {
                    val detail = moveErrors.joinToString(limit = 3, separator = " | ")
                    throw IOException("moveFile produced no outputs${if (detail.isNotBlank()) ": $detail" else ""}")
                }
                val normalized = fileList
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .toList()
                if (hasMoveFailure) {
                    lastMoveFailureDetails = moveErrors.joinToString(limit = 8, separator = " | ")
                }
                val scanned = scanMedia(normalized, context)
                return@withContext scanned.ifEmpty { normalized }
            }
            if (!directFileWrite) {
                val detail = moveErrors.joinToString(limit = 3, separator = " | ")
                throw IOException("No writable destination for $destDir${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            val skipPattern = "(^config.*.\\.txt\$)|(rList)|(.*.part-Frag.*)|(.*.live_chat)|(.*.ytdl)".toRegex()
            (sourceFiles ?: originDir.walkTopDown().filter { it.isFile }.toList())
                .filter { isFileInside(it, originDir) && it.isFile }
                .forEach { source ->
                    try {
                        if (source.name.matches(skipPattern) || source.length() == 0L) return@forEach
                        val relativePath = source.relativeTo(originDir).path
                        val target = uniqueDestinationFile(File(dir, relativePath))
                        target.parentFile?.mkdirs()
                        reserveOutput(source, target.absolutePath)
                        if (Build.VERSION.SDK_INT >= 26) {
                            val movedPath = Files.move(
                                source.toPath(),
                                target.toPath(),
                            ).absolutePathString()
                            fileList.add(movedPath)
                            notifyOutput(source, movedPath)
                        } else {
                            source.copyTo(target, false)
                            fileList.add(target.absolutePath)
                            // The copy is the irreversible publication
                            // boundary. Record the exact destination before
                            // retiring the source so a crash between copy and
                            // delete can be recovered without a destination
                            // scan or collision-suffixed duplicate.
                            notifyOutput(source, target.absolutePath)
                            if (!source.delete() && source.exists()) {
                                throw IOException(
                                    "Could not retire copied source ${source.absolutePath}",
                                )
                            }
                        }
                    } catch (e: Exception) {
                        hasMoveFailure = true
                        moveErrors.add("${source.absolutePath}: ${e.message}")
                        Log.e("error", e.message.toString())
                    }
                }
            if (!keepCache && !hasMoveFailure){
                cleanupMovedSourceTree(originDir, sourceFiles)
            } else if (hasMoveFailure) {
                val detail = moveErrors.joinToString(limit = 8, separator = " | ")
                lastMoveFailureDetails = detail
                Log.w("FileUtil", "moveFile encountered failures; preserving originDir=${originDir.absolutePath} detail=$detail")
            }
            val normalized = fileList
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            if (normalized.isEmpty()) {
                val detail = moveErrors.joinToString(limit = 3, separator = " | ")
                throw IOException("moveFile produced no outputs${if (detail.isNotBlank()) ": $detail" else ""}")
            }
            val scanned = scanMedia(normalized, context)
            if (scanned.isNotEmpty()) {
                return@withContext scanned
            }
            // Media scan can fail on some storage providers even after successful move.
            // In that case, return the moved paths directly so downstream hard-sub logic can continue.
            return@withContext normalized
        }
    }

    private data class ExactSafMoveResult(
        val paths: List<String>,
        val errors: List<String>,
    )

    /**
     * Copy each source file through the provider and retain the exact
     * document URI returned by createDocument. A merged destination folder
     * is never rescanned, because its pre-existing children are not outputs
     * of this move operation.
     */
    private suspend fun moveFilesToSafWithExactOutputs(
        originDir: File,
        context: Context,
        destinationDir: DocumentFile,
        progress: (p: Int) -> Unit,
        onOutput: (String) -> Unit,
        onOutputWithSource: ((File, String) -> Unit)? = null,
        onOutputReserved: ((File, String) -> Unit)? = null,
        onOutputReservationIntent: ((File) -> Unit)? = null,
        onOutputReservationUnknown: ((File) -> Unit)? = null,
        onOutputCommitted: ((File, String) -> Boolean)? = null,
        onReservationEstablished: (() -> Unit)? = null,
        onReservationRolledBack: ((File) -> Boolean)? = null,
        sourceFiles: List<File>? = null,
    ): ExactSafMoveResult = withContext(Dispatchers.IO) {
        val skipPattern = "(^config.*.\\.txt\$)|(rList)|(.*.part-Frag.*)|(.*.live_chat)|(.*.ytdl)".toRegex()
        val files = (sourceFiles ?: originDir.walkTopDown().filter { it.isFile }.toList())
            .filter { isFileInside(it, originDir) && it.isFile && it.length() > 0L && !it.name.matches(skipPattern) }
            .toList()
        val outputs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val directoryCache = mutableMapOf("" to destinationDir)

        files.forEachIndexed { index, source ->
            try {
                val relativeParent = runCatching {
                    source.relativeTo(originDir).parent.orEmpty()
                        .replace('\\', '/')
                        .trim('/')
                }.getOrElse {
                    throw IOException("Could not resolve SAF relative source path: ${source.absolutePath}", it)
                }
                val targetDir = resolveSafOutputDirectory(
                    root = destinationDir,
                    relativeParent = relativeParent,
                    cache = directoryCache,
                ) ?: throw IOException("Could not create SAF destination directory for ${source.absolutePath}")
                val destinationUri = moveFileInputStream(
                    source,
                    context,
                    targetDir,
                    onReservationIntent = { onOutputReservationIntent?.invoke(source) },
                    onReservationUnknown = { onOutputReservationUnknown?.invoke(source) },
                    onDestinationReserved = { path -> onOutputReserved?.invoke(source, path) },
                    onReservationEstablished = onReservationEstablished,
                    onReservationRolledBack = {
                        onReservationRolledBack?.invoke(source) ?: true
                    },
                    onOutputCommitted = { path -> onOutputCommitted?.invoke(source, path) ?: true },
                )
                    ?: throw IOException("Could not create SAF output for ${source.absolutePath}")
                val storedPath = destinationUri.toString()
                outputs.add(storedPath)
                onOutput(storedPath)
                runCatching { onOutputWithSource?.invoke(source, storedPath) }
                    .onFailure { error -> Log.w("FileUtil", "Move source observer failed", error) }
                if (!source.delete()) {
                    errors.add("Failed to delete moved SAF source ${source.absolutePath}")
                }
            } catch (error: UnresolvedProviderPublicationException) {
                throw error
            } catch (error: Exception) {
                errors.add("${source.absolutePath}: ${error.message ?: error.javaClass.simpleName}")
            }
            progress((((index + 1).toDouble() / files.size.toDouble()) * 100).toInt())
        }

        ExactSafMoveResult(outputs, errors)
    }

    private fun resolveSafOutputDirectory(
        root: DocumentFile,
        relativeParent: String,
        cache: MutableMap<String, DocumentFile>,
    ): DocumentFile? {
        if (relativeParent.isBlank()) return root
        var current = root
        var cacheKey = ""
        relativeParent.split('/').filter { it.isNotBlank() }.forEach { segment ->
            cacheKey = if (cacheKey.isBlank()) segment else "$cacheKey/$segment"
            current = cache[cacheKey] ?: run {
                val existing = current.findFile(segment)
                when {
                    existing?.isDirectory == true -> existing
                    existing != null -> return null
                    else -> current.createDirectory(segment)
                }
            } ?: return null
            cache[cacheKey] = current
        }
        return current
    }

    private fun uniqueDestinationFile(target: File): File {
        if (!target.exists()) return target
        val base = target.nameWithoutExtension
        val extension = target.extension.takeIf { it.isNotBlank() }?.let { ".${it}" }.orEmpty()
        var counter = 1
        var candidate = File(target.parentFile, "$base ($counter)$extension")
        while (candidate.exists()) {
            counter += 1
            candidate = File(target.parentFile, "$base ($counter)$extension")
        }
        return candidate
    }

    /**
     * Legacy callers own the complete origin directory. Provenance-aware
     * callers pass an exact source set, so only empty directories may be
     * removed; an unproven sibling must survive for recovery/diagnostics.
     */
    private fun cleanupMovedSourceTree(originDir: File, sourceFiles: List<File>?) {
        if (sourceFiles == null) {
            originDir.deleteRecursively()
            return
        }
        if (!originDir.exists() || !originDir.isDirectory) return
        originDir.walkBottomUp()
            .filter { it.isDirectory }
            .forEach { directory ->
                if (directory.listFiles()?.isEmpty() == true) {
                    directory.delete()
                }
            }
    }

    private fun describePersistedUriPermissions(context: Context): String {
        return context.contentResolver.persistedUriPermissions
            .joinToString(separator = "; ") { perm ->
                val docId = runCatching { DocumentsContract.getTreeDocumentId(perm.uri) }.getOrNull().orEmpty()
                "authority=${perm.uri.authority.orEmpty()} read=${perm.isReadPermission} write=${perm.isWritePermission} treeConfigured=${docId.isNotBlank()}"
            }
            .ifBlank { "<none>" }
    }

    private fun resolveDestinationDocumentDir(context: Context, rawDestDir: String, normalizedDestDir: String): DocumentFile? {
        if (rawDestDir.startsWith("content://")) {
            val asTree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(rawDestDir)) }.getOrNull()
            if (asTree?.exists() == true) return asTree
            val asDoc = runCatching { DocumentFile.fromSingleUri(context, Uri.parse(rawDestDir)) }.getOrNull()
            if (asDoc?.exists() == true) return asDoc
            return null
        }

        val storagePath = parseStorageDocumentPath(normalizedDestDir) ?: return null

        val permissions = context.contentResolver.persistedUriPermissions
        for (perm in permissions) {
            if (!perm.isWritePermission) continue
            val treeUri = perm.uri ?: continue
            if (!DocumentsContract.isTreeUri(treeUri)) continue
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: continue
            if (!treeDocId.startsWith("${storagePath.volumeId}:")) continue

            val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
            val treePath = treeDocId.substringAfter(':')
            val relFromTree = when {
                treePath.isBlank() -> storagePath.relativePath
                storagePath.relativePath == treePath -> ""
                storagePath.relativePath.startsWith("$treePath/") -> storagePath.relativePath.removePrefix("$treePath/")
                else -> continue
            }

            var current = root
            var failed = false
            relFromTree.split('/').filter { it.isNotBlank() }.forEach { segment ->
                val next = current.findFile(segment) ?: current.createDirectory(segment)
                if (next == null) {
                    failed = true
                    return@forEach
                }
                current = next
            }
            if (!failed && current.exists()) {
                return current
            }
        }
        return null
    }

    private fun parseStorageDocumentPath(path: String): StorageDocumentPath? {
        if (!path.startsWith("/storage/")) return null
        val normalized = path.trim().trimEnd('/', '\\').replace('\\', '/')
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
            .trimEnd('/', '\\')
            .replace('\\', '/')

        if (normalized == primaryRoot || normalized.startsWith("$primaryRoot/")) {
            val relativePath = normalized.removePrefix(primaryRoot).trim('/').replace('\\', '/')
            if (relativePath.isBlank()) return null
            return StorageDocumentPath("primary", relativePath)
        }

        val relative = normalized.removePrefix("/storage/").trim('/').replace('\\', '/')
        val splitIndex = relative.indexOf('/')
        if (splitIndex <= 0 || splitIndex >= relative.length - 1) return null
        val volumeId = relative.substring(0, splitIndex)
        val relativePath = relative.substring(splitIndex + 1)
        if (relativePath.isBlank()) return null
        return StorageDocumentPath(volumeId, relativePath)
    }

    private data class StorageDocumentPath(
        val volumeId: String,
        val relativePath: String
    )

    private fun isPrimarySharedStoragePath(path: String): Boolean {
        val normalizedPath = path.trimEnd('/', '\\').replace('\\', '/')
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/', '\\').replace('\\', '/')
        return normalizedPath.startsWith("$primaryRoot/")
    }

    private suspend fun moveFilesToPrimaryMediaStore(
        originDir: File,
        context: Context,
        normalizedDestDir: String,
        progress: (p: Int) -> Unit,
        onOutput: (String) -> Unit,
        onOutputWithSource: ((File, String) -> Unit)? = null,
        onOutputReserved: ((File, String) -> Unit)? = null,
        onOutputReservationIntent: ((File) -> Unit)? = null,
        onOutputReservationUnknown: ((File) -> Unit)? = null,
        onOutputCommitted: ((File, String) -> Boolean)? = null,
        onReservationEstablished: (() -> Unit)? = null,
        onReservationRolledBack: ((File) -> Boolean)? = null,
        sourceFiles: List<File>? = null,
    ): List<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/', '\\')
        val destination = normalizedDestDir.trimEnd('/', '\\').replace('\\', '/')
        if (!destination.startsWith(primaryRoot.replace('\\', '/'))) return null

        val destRelativeRoot = destination
            .removePrefix(primaryRoot.replace('\\', '/'))
            .trim('/', '\\')
            .replace('\\', '/')
        if (destRelativeRoot.isBlank()) return null

        return withContext(Dispatchers.IO) {
            val files = (sourceFiles ?: originDir.walkTopDown().filter { it.isFile }.toList())
                .filter { file ->
                    isFileInside(file, originDir) &&
                        file.isFile &&
                        file.length() > 0L &&
                        !file.name.matches("(^config.*.\\.txt\$)|(rList)|(.*.part-Frag.*)|(.*.live_chat)|(.*.ytdl)".toRegex())
                }
                .toList()
            if (files.isEmpty()) return@withContext null

            val outputs = mutableListOf<String>()
            files.forEachIndexed { index, source ->
                val relFromOrigin = source.absolutePath
                    .removePrefix(originDir.absolutePath)
                    .trimStart(File.separatorChar, '/', '\\')
                    .replace('\\', '/')
                val relParent = relFromOrigin.substringBeforeLast('/', "")
                val targetRelativeDir = listOf(destRelativeRoot, relParent)
                    .filter { it.isNotBlank() }
                    .joinToString("/")
                    .trim('/')
                val moved = moveSingleFileToPrimaryMediaStore(
                    context = context,
                    source = source,
                    relativeDir = targetRelativeDir,
                    onReservationIntent = { onOutputReservationIntent?.invoke(source) },
                    onReservationUnknown = { onOutputReservationUnknown?.invoke(source) },
                    onDestinationReserved = { path -> onOutputReserved?.invoke(source, path) },
                    onReservationEstablished = onReservationEstablished,
                    onReservationRolledBack = {
                        onReservationRolledBack?.invoke(source) ?: true
                    },
                    onOutputCommitted = { path -> onOutputCommitted?.invoke(source, path) ?: true },
                )
                // ContentResolver.insert() is the sole authority for the
                // published MediaStore row.  Keep that exact URI in the
                // output carrier; a synthesized RELATIVE_PATH/DISPLAY_NAME
                // pathname can identify a different row after a collision.
                outputs.add(moved.providerUri)
                onOutput(moved.providerUri)
                runCatching { onOutputWithSource?.invoke(source, moved.providerUri) }
                    .onFailure { error -> Log.w("FileUtil", "Move source observer failed", error) }
                if (!source.delete() && source.exists()) {
                    // The provider row is already an exact, finalized
                    // publication and has been durably reported above. Keep
                    // the source in the recovery carrier when it cannot be
                    // retired instead of silently pretending the move was
                    // complete and clearing the only duplicate-prevention
                    // evidence.
                    throw IOException("Could not retire MediaStore source ${source.absolutePath}")
                }
                progress((((index + 1).toDouble() / files.size.toDouble()) * 100).toInt())
            }
            outputs
        }
    }

    private data class MediaStoreMoveResult(
        val providerUri: String,
    )

    private fun moveSingleFileToPrimaryMediaStore(
        context: Context,
        source: File,
        relativeDir: String,
        onReservationIntent: (() -> Unit)? = null,
        onReservationUnknown: (() -> Unit)? = null,
        onDestinationReserved: ((String) -> Unit)? = null,
        onReservationEstablished: (() -> Unit)? = null,
        onReservationRolledBack: (() -> Boolean)? = null,
        onOutputCommitted: ((String) -> Boolean)? = null,
    ): MediaStoreMoveResult {
        val resolver = context.contentResolver
        val normalizedRelativeDir = relativeDir.trim('/', '\\').replace('\\', '/') + "/"
        val displayName = resolveUniqueMediaStoreName(context, normalizedRelativeDir, source.name)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase(Locale.US))
            ?: "application/octet-stream"
        val collection = resolvePrimaryMediaStoreCollection(normalizedRelativeDir, mimeType)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, normalizedRelativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        onReservationIntent?.invoke()
        val uri = try {
            resolver.insert(collection, values)
        } catch (error: Exception) {
            runCatching { onReservationUnknown?.invoke() }
            throw UnresolvedProviderPublicationException(
                "MediaStore insert completion is unknown for ${source.name}",
                error,
            )
        } ?: run {
            onReservationUnknown?.invoke()
            throw UnresolvedProviderPublicationException(
                "MediaStore insert returned no exact URI for ${source.name}",
            )
        }

        var providerObjectRolledBack = false
        try {
            try {
                onDestinationReserved?.invoke(uri.toString())
            } catch (reservationError: Exception) {
                when (resolveProviderReservationFailure(
                    rollbackProviderObject = {
                        resolver.delete(uri, null, null) == 1
                    },
                    clearReservation = {
                        onReservationRolledBack?.invoke() ?: true
                    },
                )) {
                    ProviderReservationRecovery.ROLLED_BACK -> {
                        providerObjectRolledBack = true
                        throw reservationError
                    }
                    ProviderReservationRecovery.UNRESOLVED -> Unit
                }
                runCatching { onReservationUnknown?.invoke() }
                throw UnresolvedProviderPublicationException(
                    "MediaStore create completed but exact reservation could not be established for ${source.name}",
                    reservationError,
                )
            }
            onReservationEstablished?.invoke()

            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Could not open MediaStore output stream for ${source.name}")

            val finalizedRows = resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
            if (finalizedRows != 1) {
                throw IOException(
                    "MediaStore publication finalization was not acknowledged for ${source.name}: " +
                        "affectedRows=$finalizedRows",
                )
            }
            if (onOutputCommitted != null && !onOutputCommitted.invoke(uri.toString())) {
                throw IOException(
                    "MediaStore publication could not durably record ${uri}",
                )
            }
        } catch (e: UnresolvedProviderPublicationException) {
            throw e
        } catch (e: Exception) {
            if (providerObjectRolledBack) throw e
            val deletedRows = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
            if (deletedRows == 1) {
                val rollbackRecorded = runCatching {
                    onReservationRolledBack?.invoke() ?: true
                }.getOrDefault(false)
                if (!rollbackRecorded) {
                    runCatching { onReservationUnknown?.invoke() }
                    throw UnresolvedProviderPublicationException(
                        "MediaStore cleanup was acknowledged but exact reservation retirement was not durable for ${source.name}",
                        e,
                    )
                }
            } else {
                e.addSuppressed(
                    IOException(
                        "MediaStore cleanup was not acknowledged for ${uri}: affectedRows=$deletedRows",
                    ),
                )
            }
            throw e
        }

        return MediaStoreMoveResult(
            providerUri = uri.toString(),
        )
    }

    private fun resolvePrimaryMediaStoreCollection(relativeDir: String, mimeType: String): Uri {
        val topLevelDir = relativeDir.substringBefore('/').lowercase(Locale.US)
        return when {
            topLevelDir == Environment.DIRECTORY_DOWNLOADS.lowercase(Locale.US) ->
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mimeType.startsWith("video/") ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mimeType.startsWith("audio/") ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mimeType.startsWith("image/") ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else ->
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
    }

    private fun resolveUniqueMediaStoreName(context: Context, relativeDir: String, filename: String): String {
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var candidate = filename
        var index = 1
        while (mediaStoreFileExists(context, relativeDir, candidate)) {
            candidate = "$base ($index)$ext"
            index += 1
        }
        return candidate
    }

    private fun mediaStoreFileExists(context: Context, relativeDir: String, filename: String): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        return context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            projection,
            selection,
            arrayOf(relativeDir, filename),
            null
        )?.use { cursor ->
            cursor.moveToFirst()
        } ?: false
    }

    private fun moveFileInputStream(
        it: File,
        context: Context,
        dst: DocumentFile,
        onReservationIntent: (() -> Unit)? = null,
        onReservationUnknown: (() -> Unit)? = null,
        onDestinationReserved: ((String) -> Unit)? = null,
        onReservationEstablished: (() -> Unit)? = null,
        onReservationRolledBack: (() -> Boolean)? = null,
        onOutputCommitted: ((String) -> Boolean)? = null,
    ) : Uri? {
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension) ?: "*/*"
        val displayName = resolveUniqueDocumentName(dst, it.name)

        onReservationIntent?.invoke()
        val destUri = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                dst.uri,
                mimeType,
                displayName
            ) ?: run {
                try {
                    onReservationUnknown?.invoke()
                } catch (error: Exception) {
                    throw UnresolvedProviderPublicationException(
                        "SAF create returned no exact URI and the unknown reservation could not be recorded for ${it.name}",
                        error,
                    )
                }
                throw UnresolvedProviderPublicationException(
                    "SAF create returned no exact URI for ${it.name}",
                )
            }
        } catch (error: UnresolvedProviderPublicationException) {
            throw error
        } catch (error: Exception) {
            runCatching { onReservationUnknown?.invoke() }
            throw UnresolvedProviderPublicationException(
                "SAF create completion is unknown for ${it.name}",
                error,
            )
        }

        var providerObjectRolledBack = false
        try {
            try {
                onDestinationReserved?.invoke(destUri.toString())
            } catch (reservationError: Exception) {
                when (resolveProviderReservationFailure(
                    rollbackProviderObject = {
                        context.contentResolver.delete(destUri, null, null) == 1
                    },
                    clearReservation = {
                        onReservationRolledBack?.invoke() ?: true
                    },
                )) {
                    ProviderReservationRecovery.ROLLED_BACK -> {
                        providerObjectRolledBack = true
                        throw reservationError
                    }
                    ProviderReservationRecovery.UNRESOLVED -> Unit
                }
                runCatching { onReservationUnknown?.invoke() }
                throw UnresolvedProviderPublicationException(
                    "SAF create completed but exact reservation could not be established for ${it.name}",
                    reservationError,
                )
            }
            onReservationEstablished?.invoke()
            val inputStream = it.inputStream()
            val outputStream = context.contentResolver.openOutputStream(destUri)
                ?: throw IOException("Could not open SAF output stream for ${it.name}")
            inputStream.copyTo(outputStream)
            inputStream.closeQuietly()
            outputStream.closeQuietly()
            if (onOutputCommitted != null && !onOutputCommitted.invoke(destUri.toString())) {
                throw IOException(
                    "SAF publication could not durably record ${destUri}",
                )
            }
        } catch (error: UnresolvedProviderPublicationException) {
            throw error
        } catch (error: Exception) {
            if (providerObjectRolledBack) throw error
            val deletedRows = runCatching {
                context.contentResolver.delete(destUri, null, null)
            }.getOrDefault(0)
            if (deletedRows == 1) {
                val rollbackRecorded = runCatching {
                    onReservationRolledBack?.invoke() ?: true
                }.getOrDefault(false)
                if (!rollbackRecorded) {
                    runCatching { onReservationUnknown?.invoke() }
                    throw UnresolvedProviderPublicationException(
                        "SAF cleanup was acknowledged but exact reservation retirement was not durable for ${it.name}",
                        error,
                    )
                }
            } else {
                error.addSuppressed(
                    IOException(
                        "SAF cleanup was not acknowledged for ${destUri}: affectedRows=$deletedRows",
                    ),
                )
            }
            throw error
        }

        return destUri
    }

    private fun resolveUniqueDocumentName(destinationDir: DocumentFile, filename: String): String {
        if (destinationDir.findFile(filename) == null) return filename
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val extension = if (dot > 0) filename.substring(dot) else ""
        var counter = 1
        var candidate = "$base ($counter)$extension"
        while (destinationDir.findFile(candidate) != null) {
            counter += 1
            candidate = "$base ($counter)$extension"
        }
        return candidate
    }

    fun scanMedia(files: List<String>, context: Context) : List<String> {
        try {
            // Keep the exact stored-path representation returned by the
            // publication boundary.  Content URIs are provider identities;
            // converting them to File("content://...") would lose existence,
            // size, and ordering information and can erase authority on a
            // later failure path.
            // Media scanning is observation only.  In particular, do not let
            // provider size/mtime (or a scanner callback) reorder the exact
            // publication manifest: finalPaths.first() is the producer's
            // primary-media decision and remains semantic authority.
            val paths = mediaScanPublicationOrder(files)
            runCatching {
                paths.mapNotNull { rawFilesystemPath(it) }.forEach { filesystemPath ->
                    MediaScannerConnection.scanFile(context, arrayOf(filesystemPath), null, null)
                }
            }
            return paths
        }catch (e: Exception){
            e.printStackTrace()
        }

        return listOf()
    }

    /**
     * A reserved publication may be promoted after restart only when the
     * physical boundary itself proves completion.  Raw moves have that proof
     * when the exact source is gone after the destination exists.  Provider
     * rows require a positive MediaStore finalization acknowledgement;
     * generic SAF existence is deliberately insufficient because it cannot
     * distinguish a partial copy from a complete document.
     */
    internal fun isRecoverablePublicationComplete(
        sourcePath: String,
        destinationPath: String,
        context: Context? = null,
    ): Boolean {
        val destination = destinationPath.trim()
        if (destination.isBlank()) return false
        if (destination.startsWith("content://", ignoreCase = true)) {
            return context?.let { isFinalizedMediaStoreUri(destination, it) } == true
        }
        val source = runCatching {
            val raw = if (sourcePath.startsWith("file://", ignoreCase = true)) {
                Uri.parse(sourcePath).path.orEmpty()
            } else {
                sourcePath
            }
            File(raw).canonicalFile
        }.getOrNull() ?: return false
        val destinationExists = if (context == null) {
            runCatching { File(destination).exists() }.getOrDefault(false)
        } else {
            exists(destination, context)
        }
        return destinationExists && !source.exists()
    }

    private fun isFinalizedMediaStoreUri(path: String, context: Context): Boolean {
        val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return false
        if (!uri.scheme.equals("content", ignoreCase = true)) return false
        if (DocumentsContract.isDocumentUri(context, uri)) return false
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.IS_PENDING),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                index >= 0 && cursor.getInt(index) == 0
            } == true
        }.getOrDefault(false)
    }

    /**
     * Preserve the exact order established by the publication carrier.  A
     * media scanner may observe paths, but it cannot choose the primary
     * output by size, mtime, or provider enumeration order.
     */
    internal fun mediaScanPublicationOrder(files: List<String>): List<String> =
        files.distinct()

    private fun rawFilesystemPath(path: String): String? {
        val normalized = path.trim()
        if (normalized.isBlank() || normalized.startsWith("content://", ignoreCase = true)) {
            return null
        }
        return if (normalized.startsWith("file://", ignoreCase = true)) {
            runCatching { Uri.parse(normalized).path.orEmpty() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        } else {
            normalized
        }
    }

    fun getBackupPath(context: Context) : String {
        val preference = PreferenceManager.getDefaultSharedPreferences(context).getString("backup_path", "")
        return if (preference.isNullOrBlank() || !canWriteToDestination(preference, context)) {
            getDefaultApplicationPath() + "/Backups"
        }else {
            formatPath(preference)
        }
    }

    /**
     * Cache staging is a native/raw-filesystem contract. A persisted SAF
     * grant authorizes provider I/O, but it does not prove that yt-dlp,
     * marker files, and ordinary File operations can use the corresponding
     * raw path. Resolve provider-only or no-longer usable preferences to the
     * app-owned default before any native/cache consumer receives a root.
     */
    fun getCachePath(context: Context): String {
        val preference = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("cache_path", "")
        val defaultPath = defaultCachePath(context)
        if (preference.isNullOrBlank()) return defaultPath
        return resolveRawCachePath(context, preference) ?: run {
            Log.w(
                "FileUtil",
                "Configured cache path is not a directly writable filesystem root; using app default",
            )
            defaultPath
        }
    }

    /** True only when a selected value is safe for native/raw cache staging. */
    fun isSupportedCachePathSelection(context: Context, selectedPath: String): Boolean =
        resolveRawCachePath(context, selectedPath) != null

    private fun defaultCachePath(context: Context): String {
        val externalPath = context.getExternalFilesDir(null)
        return if (externalPath == null) {
            context.cacheDir.absolutePath + "/downloads/"
        } else {
            externalPath.absolutePath + "/downloads/"
        }
    }

    private fun resolveRawCachePath(context: Context, selectedPath: String): String? {
        val normalized = selectedPath.trim()
        if (normalized.isBlank() || normalized.startsWith("content://", ignoreCase = true)) {
            return null
        }
        val rawPath = when {
            normalized.startsWith("file://", ignoreCase = true) ->
                runCatching { Uri.parse(normalized).path.orEmpty() }.getOrDefault("")
            normalized.startsWith("/") -> normalized
            else -> formatPath(normalized)
        }.trim().trimEnd('/', '\\')
        if (rawPath.isBlank()) return null

        val candidate = File(rawPath)
        if (isAppOwnedCachePath(context, candidate)) return rawPath + File.separator

        // Non-app-owned paths require direct raw-directory write evidence.
        // Persisted URI permissions are intentionally not consulted here.
        val probe = if (candidate.exists()) candidate else candidate.parentFile
        return rawPath.takeIf { probe?.isDirectory == true && probe.canWrite() }
            ?.plus(File.separator)
    }

    fun deleteCachePathIfAppOwned(context: Context): Boolean {
        val cacheDir = File(getCachePath(context))
        if (!isAppOwnedCachePath(context, cacheDir)) {
            Log.w("FileUtil", "Refusing to recursively delete a non app-owned cache location")
            return false
        }
        return cacheDir.deleteRecursively()
    }

    private fun isAppOwnedCachePath(context: Context, cacheDir: File): Boolean {
        val canonicalCacheDir = runCatching { cacheDir.canonicalFile }.getOrElse { return false }
        val allowedParents = listOfNotNull(
            context.cacheDir,
            context.externalCacheDir,
            context.getExternalFilesDir(null)
        ).mapNotNull { runCatching { it.canonicalFile }.getOrNull() }

        return allowedParents.any { parent ->
            canonicalCacheDir == parent || canonicalCacheDir.startsWithFile(parent)
        }
    }

    private fun File.startsWithFile(parent: File): Boolean {
        var current: File? = this
        while (current != null) {
            if (current == parent) return true
            current = current.parentFile
        }
        return false
    }

    fun deleteConfigFiles(request: YoutubeDLRequest) {
        runCatching {
            request.getArguments("--config")?.forEach {
                if (it != null) File(it).delete()
            }
            request.getArguments("--config-locations")?.forEach {
                if (it != null) File(it).delete()
            }
            request.getArguments("-o")?.firstOrNull { it?.startsWith("infojson:") == true }?.apply {
                File(this.removePrefix("infojson:")).delete()
            }
        }
    }

    fun getDefaultAudioPath() : String{
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath + File.separator + "YTDLnisx/Audio"
    }

    fun getDefaultVideoPath() : String{
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath + File.separator + "YTDLnisx/Video"
    }

    fun getDefaultCommandPath() : String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath + File.separator + "YTDLnisx/Command"
    }

    fun getDefaultApplicationPath() : String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath + File.separator + "YTDLnisx"
    }

    fun getDownloadArchivePath(context: Context) : String {
        var folder = PreferenceManager.getDefaultSharedPreferences(context).getString("download_archive_path", "")!!
        if (folder == "") {
            val externalPath = context.getExternalFilesDir(null)
            folder =  if (externalPath == null){
                context.cacheDir.absolutePath + File.separator
            }else{
                externalPath.absolutePath + File.separator
            }
        }
        return "${formatPath(folder)}download_archive.txt"
    }

    fun getDefaultTerminalPath() : String {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath + File.separator + "YTDLnisx/TERMINAL_CACHE"
    }

    fun getCookieFile(context : Context, ignoreIfExists: Boolean = false,  path: (path: String) -> Unit){
        val cookiesFile = File(context.cacheDir, "cookies.txt")
        if (ignoreIfExists || cookiesFile.exists()){
            path(cookiesFile.absolutePath)
        }
    }

    fun convertFileSize(s: Long): String{
        if (s <= 1) return "?"
        val units = arrayOf("B", "kB", "MB", "GB", "TB")
        val digitGroups = (log10(s.toDouble()) / log10(1024.0)).toInt()
        val symbols = DecimalFormatSymbols(Locale.US)
        return "${DecimalFormat("#,##0.#", symbols).format(s / 1024.0.pow(digitGroups.toDouble()))} ${units[digitGroups]}"
    }

    fun prepareShareUri(context: Context, path: String): Uri? {
        return prepareStoredFile(context, path).uri
    }

    private fun prepareStoredFile(context: Context, storedPath: String): PreparedStoredFile {
        val path = storedPath.trim()
        if (path.isBlank()) return PreparedStoredFile(failure = StoredFileOperationFailure.INVALID_LOCATION)
        return try {
            val parsed = Uri.parse(path)
            if (parsed.scheme.equals("content", ignoreCase = true)) {
                if (parsed.authority.isNullOrBlank()) {
                    return PreparedStoredFile(failure = StoredFileOperationFailure.INVALID_LOCATION)
                }
                prepareContentStoredFile(context, parsed)
            } else {
                if (!parsed.scheme.isNullOrBlank() &&
                    !parsed.scheme.equals("file", ignoreCase = true) &&
                    path.contains("://")
                ) {
                    return PreparedStoredFile(failure = StoredFileOperationFailure.UNSUPPORTED)
                }
                val rawPath = if (parsed.scheme.equals("file", ignoreCase = true)) parsed.path.orEmpty() else path
                if (rawPath.isBlank()) {
                    return PreparedStoredFile(failure = StoredFileOperationFailure.INVALID_LOCATION)
                }
                val file = File(rawPath)
                when {
                    isBlockedShareFile(file) -> PreparedStoredFile(failure = StoredFileOperationFailure.UNSUPPORTED)
                    else -> prepareRawStoredFile(context, file)
                }
            }
        } catch (_: SecurityException) {
            PreparedStoredFile(failure = StoredFileOperationFailure.PERMISSION_REQUIRED)
        } catch (_: Exception) {
            PreparedStoredFile(failure = StoredFileOperationFailure.UNKNOWN)
        }
    }

    private fun prepareContentStoredFile(context: Context, uri: Uri): PreparedStoredFile {
        return try {
            if (context.contentResolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR) {
                return PreparedStoredFile(failure = StoredFileOperationFailure.INVALID_LOCATION)
            }
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return PreparedStoredFile(failure = StoredFileOperationFailure.UNKNOWN)
            descriptor.use { }
            PreparedStoredFile(uri = uri)
        } catch (_: FileNotFoundException) {
            PreparedStoredFile(failure = StoredFileOperationFailure.NOT_FOUND)
        } catch (_: SecurityException) {
            PreparedStoredFile(failure = StoredFileOperationFailure.PERMISSION_REQUIRED)
        } catch (_: IllegalArgumentException) {
            PreparedStoredFile(failure = StoredFileOperationFailure.INVALID_LOCATION)
        } catch (_: UnsupportedOperationException) {
            PreparedStoredFile(failure = StoredFileOperationFailure.UNSUPPORTED)
        } catch (_: Exception) {
            PreparedStoredFile(failure = StoredFileOperationFailure.UNKNOWN)
        }
    }

    private fun prepareRawStoredFile(context: Context, file: File): PreparedStoredFile {
        if (file.isDirectory) {
            return PreparedStoredFile(failure = StoredFileOperationFailure.INVALID_LOCATION)
        }
        val uri = prepareShareUriOptimistically(context, file)
            ?: return PreparedStoredFile(failure = StoredFileOperationFailure.UNKNOWN)
        return try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return PreparedStoredFile(failure = StoredFileOperationFailure.UNKNOWN)
            descriptor.use { }
            PreparedStoredFile(uri = uri)
        } catch (_: FileNotFoundException) {
            PreparedStoredFile(failure = StoredFileOperationFailure.NOT_FOUND)
        } catch (_: SecurityException) {
            PreparedStoredFile(failure = StoredFileOperationFailure.PERMISSION_REQUIRED)
        } catch (_: IllegalArgumentException) {
            PreparedStoredFile(failure = StoredFileOperationFailure.INVALID_LOCATION)
        } catch (_: Exception) {
            PreparedStoredFile(failure = StoredFileOperationFailure.UNKNOWN)
        }
    }

    fun describeStoredLocation(context: Context, storedPath: String): StoredLocation? {
        val value = storedPath.trim()
        if (value.isBlank()) return null
        return when {
            value.startsWith("content://", ignoreCase = true) -> {
                val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
                StoredLocation(
                    kind = StoredLocationKind.CONTENT_URI,
                    value = uri.toString(),
                    parentValue = parentContentUri(context, uri)?.toString(),
                    isAppPrivate = false
                )
            }
            value.startsWith("file://", ignoreCase = true) -> {
                val rawPath = runCatching { Uri.parse(value).path }.getOrNull().orEmpty()
                val file = File(rawPath)
                StoredLocation(
                    kind = StoredLocationKind.FILE_URI,
                    value = value,
                    parentValue = file.parentFile?.absolutePath,
                    isAppPrivate = isAppPrivatePath(context, file)
                )
            }
            else -> {
                val file = File(value)
                StoredLocation(
                    kind = StoredLocationKind.RAW_PATH,
                    value = value,
                    parentValue = file.parentFile?.absolutePath,
                    isAppPrivate = isAppPrivatePath(context, file)
                )
            }
        }
    }

    fun commonParentLocation(context: Context, storedPaths: List<String>): StoredLocation? {
        val locations = storedPaths.mapNotNull { describeStoredLocation(context, it) }
        if (locations.isEmpty() || locations.size != storedPaths.count { it.isNotBlank() }) return null
        val parentValues = locations.mapNotNull(StoredLocation::parentValue)
        if (parentValues.size != locations.size) return null
        val normalizedParents = parentValues.map { parent ->
            if (parent.startsWith("content://", ignoreCase = true)) {
                runCatching { Uri.parse(parent).normalizeScheme().toString() }.getOrDefault(parent)
            } else {
                normalizedAbsolutePath(File(parent))
            }
        }.distinct()
        if (normalizedParents.size != 1) return null
        val first = locations.first()
        return StoredLocation(
            kind = if (parentValues.first().startsWith("content://", ignoreCase = true)) {
                StoredLocationKind.CONTENT_URI
            } else {
                StoredLocationKind.RAW_PATH
            },
            value = parentValues.first(),
            parentValue = null,
            isAppPrivate = first.isAppPrivate
        )
    }

    fun openStoredLocation(context: Context, storedPaths: List<String>): OpenStoredLocationResult {
        val parent = commonParentLocation(context, storedPaths)
            ?: return OpenStoredLocationResult.UNAVAILABLE
        val parentUri = when (parent.kind) {
            StoredLocationKind.CONTENT_URI -> runCatching { Uri.parse(parent.value) }.getOrNull()
            StoredLocationKind.RAW_PATH,
            StoredLocationKind.FILE_URI -> buildDocumentUriForPath(parent.value)
        } ?: return OpenStoredLocationResult.COPY_PARENT_FALLBACK

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            data = parentUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(viewIntent) }.isSuccess) {
            return OpenStoredLocationResult.OPENED
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val treeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, parentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(treeIntent) }.isSuccess) {
                return OpenStoredLocationResult.OPENED
            }
        }
        return OpenStoredLocationResult.COPY_PARENT_FALLBACK
    }

    fun safeFallbackLocationText(location: StoredLocation): String {
        return if (location.isAppPrivate) {
            location.value.substringAfterLast(File.separatorChar).ifBlank { "App storage" }
        } else {
            location.value
        }
    }

    private fun parentContentUri(context: Context, uri: Uri): Uri? {
        if (DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri)) {
            return uri
        }
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?: return null
            val treeId = if (DocumentsContract.isTreeUri(uri)) {
                runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            } else {
                null
            }
            val parentId = documentId.substringBeforeLast('/', missingDelimiterValue = treeId.orEmpty())
                .ifBlank { treeId.orEmpty() }
                .ifBlank { return null }
            return if (DocumentsContract.isTreeUri(uri)) {
                runCatching { DocumentsContract.buildDocumentUriUsingTree(uri, parentId) }.getOrNull()
            } else {
                uri.authority?.let { authority ->
                    runCatching { DocumentsContract.buildDocumentUri(authority, parentId) }.getOrNull()
                }
            }
        }
        val segments = uri.pathSegments
        if (segments.size <= 1) return null
        return uri.buildUpon().path(segments.dropLast(1).joinToString("/", prefix = "/")).build()
    }

    private fun isAppPrivatePath(context: Context, file: File): Boolean {
        val candidate = normalizedAbsolutePath(file)
        return listOfNotNull(
            context.cacheDir,
            context.filesDir,
            context.externalCacheDir,
            context.getExternalFilesDir(null)
        ).any { root ->
            val normalizedRoot = normalizedAbsolutePath(root)
            candidate == normalizedRoot || candidate.startsWith(normalizedRoot + File.separator)
        }
    }

    private fun normalizedAbsolutePath(file: File): String {
        return runCatching { file.toPath().toAbsolutePath().normalize().toString() }
            .getOrDefault(file.absolutePath)
    }

    fun prepareShareUri(context: Context, file: File): Uri? {
        if (!file.exists() || !file.isFile || isBlockedShareFile(file)) return null
        return prepareShareUriOptimistically(context, file)
    }

    private fun prepareShareUriOptimistically(context: Context, file: File): Uri? {
        if (isBlockedShareFile(file)) return null
        findMediaStoreUri(context, file)?.let { return it }
        val providerFile = when {
            isInsideAllowedProviderRoot(context, file) -> file
            file.length() > MAX_SHARE_CACHE_COPY_BYTES -> {
                Log.w(
                    "FileUtil",
                    "Refusing to copy large file into share cache size=${file.length()} target=${file.name}"
                )
                return null
            }
            else -> copyToSharedProviderCache(context, file) ?: return null
        }
        return runCatching {
            FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                providerFile
            )
        }.onFailure { error ->
            Log.w("FileUtil", "Failed to build FileProvider share uri target=${providerFile.name} reason=${error.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun findMediaStoreUri(context: Context, file: File): Uri? {
        val canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        return runCatching {
            context.contentResolver.query(
                uri,
                projection,
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(canonicalPath),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    ContentUris.withAppendedId(uri, id)
                } else {
                    null
                }
            }
        }.onFailure { error ->
            Log.w("FileUtil", "Failed to query MediaStore share uri target=${file.name} reason=${error.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun isInsideAllowedProviderRoot(context: Context, file: File): Boolean {
        return isFileInside(file, File(getDefaultApplicationPath())) ||
                isFileInside(file, File(context.cacheDir, SHARED_FILE_PROVIDER_DIR)) ||
                isFileInside(file, File(context.getExternalFilesDir(null), SHARED_FILE_PROVIDER_DIR))
    }

    private fun copyToSharedProviderCache(context: Context, file: File): File? {
        return runCatching {
            val sharedDir = File(context.cacheDir, SHARED_FILE_PROVIDER_DIR).apply {
                mkdirs()
            }
            val safeName = sanitizeShareFileName(file.name)
            val target = File(sharedDir, uniqueShareFileName(file, safeName))
            file.copyTo(target, overwrite = true)
            target
        }.onFailure { error ->
            Log.w("FileUtil", "Failed to copy file into share cache target=${file.name} reason=${error.javaClass.simpleName}")
        }.getOrNull()
    }

    private fun uniqueShareFileName(file: File, safeName: String): String {
        val dot = safeName.lastIndexOf('.')
        val suffix = Integer.toHexString(file.absolutePath.hashCode())
        return if (dot > 0) {
            "${safeName.substring(0, dot)}-$suffix${safeName.substring(dot)}"
        } else {
            "$safeName-$suffix"
        }
    }

    private fun sanitizeShareFileName(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "shared-file" }
    }

    private fun isBlockedShareFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        return name in blockedShareFileNames ||
                name.startsWith("config-") ||
                name.endsWith(".info.json") ||
                name.endsWith(".db") ||
                name.endsWith(".sqlite") ||
                name.endsWith(".sqlite3")
    }

    private fun isFileInside(file: File, directory: File?): Boolean {
        if (directory == null) return false
        return runCatching {
            val filePath = file.canonicalFile.toPath()
            val directoryPath = directory.canonicalFile.toPath()
            filePath.startsWith(directoryPath)
        }.getOrDefault(false)
    }


    fun openFileIntent(context: Context, downloadPath: String) {
        val prepared = prepareStoredFile(context, downloadPath)
        val uri = prepared.uri

        if (uri == null){
            showStoredFileOperationFailure(context, prepared.failure)
        }else{
            try {
                val ext = downloadPath.substringBefore('?').substringAfterLast('.', "").lowercase()
                val mime = context.contentResolver.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, context.getString(R.string.error_opening_file), Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(context, context.getString(R.string.file_permission_required), Toast.LENGTH_SHORT).show()
            } catch (_: IllegalArgumentException) {
                Toast.makeText(context, context.getString(R.string.invalid_file_location), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.file_access_failed), Toast.LENGTH_SHORT).show()
            }
        }

    }

    fun shareFileIntent(context: Context, paths: List<String>){
        val uris : ArrayList<Uri> = arrayListOf()
        val failures = paths.map { path -> prepareStoredFile(context, path) }
            .onEach { prepared -> prepared.uri?.let(uris::add) }
            .mapNotNull(PreparedStoredFile::failure)

        if (uris.isEmpty()){
            showStoredFileOperationFailure(context, failures.firstOrNull())
        }else{
            try {
                val intent = Intent().apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    action = Intent.ACTION_SEND_MULTIPLE
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    type = if (uris.size == 1) {
                        context.contentResolver.getType(uris[0]) ?: "media/*"
                    } else {
                        "*/*"
                    }
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
                if (failures.isNotEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.share_files_partial, uris.size, failures.size),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, context.getString(R.string.no_compatible_share_app), Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(context, context.getString(R.string.file_permission_required), Toast.LENGTH_SHORT).show()
            } catch (_: IllegalArgumentException) {
                Toast.makeText(context, context.getString(R.string.invalid_file_location), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.file_access_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showStoredFileOperationFailure(context: Context, failure: StoredFileOperationFailure?) {
        val message = when (failure) {
            StoredFileOperationFailure.NOT_FOUND -> R.string.requested_file_not_found
            StoredFileOperationFailure.PERMISSION_REQUIRED -> R.string.file_permission_required
            StoredFileOperationFailure.INVALID_LOCATION -> R.string.invalid_file_location
            StoredFileOperationFailure.UNSUPPORTED -> R.string.file_operation_unsupported
            StoredFileOperationFailure.UNKNOWN,
            null -> R.string.file_access_failed
        }
        Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
    }

    private fun safeDiagnosticLocation(storedPath: String): String {
        return if (storedPath.startsWith("content://", ignoreCase = true)) {
            "content URI"
        } else {
            File(storedPath).name.ifBlank { "file" }
        }
    }

    fun hasAllFilesAccess() : Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return Environment.isExternalStorageManager()
    }

}

