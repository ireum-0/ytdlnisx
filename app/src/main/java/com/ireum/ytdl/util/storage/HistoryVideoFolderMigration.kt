package com.ireum.ytdl.util.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.HistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * Moves default-folder media only after its exact History references have
 * durably committed the copied destination paths.
 */
class HistoryVideoFolderMigration(
    private val context: Context,
    private val database: DBManager,
) {
    data class Result(
        val movedFiles: Int,
        val updatedCards: Int,
        val failedFiles: Int,
    )

    private data class DestinationCopy(
        val path: String,
        val sourceLength: Long,
        val sourceLastModified: Long,
    )

    internal var afterDestinationCopyForTesting: ((sourcePath: String, destinationPath: String) -> Unit)? = null
    internal var beforePathReconciliationForTesting: ((historyItems: List<HistoryItem>) -> Unit)? = null

    suspend fun migrate(
        sourceRoot: File,
        destinationRoot: String,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        HistoryReferenceMutationCoordinator.withLock {
            if (!sourceRoot.exists() || !sourceRoot.isDirectory) {
                return@withLock Result(0, 0, 0)
            }

            val sourceNormalized = sourceRoot.absolutePath.replace('\\', '/')
            val destinationNormalized = destinationRoot.replace('\\', '/')
            if (destinationNormalized.equals(sourceNormalized, ignoreCase = true)) {
                return@withLock Result(0, 0, 0)
            }

            val historyItems = database.historyDao.getAll().filter { it.type == DownloadType.video }
            val candidateOccurrences = historyItems.flatMap { item ->
                item.downloadPath.filter { oldPath -> isMigrationCandidate(oldPath, sourceNormalized) }
            }
            val candidatePaths = candidateOccurrences.toSet()
            onProgress(0, candidateOccurrences.size)

            val destinationBySource = linkedMapOf<String, DestinationCopy?>()
            val failedSourcePaths = linkedSetOf<String>()
            var processed = 0
            historyItems.forEach { item ->
                item.downloadPath.forEach pathLoop@ { oldPath ->
                    if (oldPath !in candidatePaths) return@pathLoop
                    if (!destinationBySource.containsKey(oldPath)) {
                        val sourceFile = File(oldPath)
                        val destinationCopy = copyToDestination(sourceFile, destinationRoot)
                        destinationBySource[oldPath] = destinationCopy
                        if (destinationCopy == null) {
                            failedSourcePaths += oldPath
                        } else {
                            afterDestinationCopyForTesting?.invoke(oldPath, destinationCopy.path)
                        }
                    }
                    processed += 1
                    onProgress(processed, candidateOccurrences.size)
                }
            }

            val preparedCopies = destinationBySource.mapNotNull { (sourcePath, copy) ->
                copy?.let { sourcePath to it }
            }.toMap()
            if (preparedCopies.isEmpty()) {
                return@withLock Result(0, 0, failedSourcePaths.size)
            }

            beforePathReconciliationForTesting?.invoke(historyItems)
            val updatedCards = runCatching {
                database.withTransaction {
                    var updatedCount = 0
                    historyItems.forEach { item ->
                        val updatedPaths = item.downloadPath.map { oldPath ->
                            preparedCopies[oldPath]?.path ?: oldPath
                        }
                        if (updatedPaths != item.downloadPath) {
                            if (
                                database.historyDao.updateDownloadPathIfUnchanged(
                                    id = item.id,
                                    expectedDownloadPath = item.downloadPath,
                                    downloadPath = updatedPaths,
                                ) == 1
                            ) {
                                updatedCount += 1
                            }
                        }
                    }
                    updatedCount
                }
            }.onFailure { error ->
                Log.e(TAG, "Video-folder History path reconciliation failed; preserving source files", error)
            }.getOrDefault(0)

            val currentHistory = runCatching { database.historyDao.getAll() }
                .onFailure { error ->
                    Log.e(TAG, "Video-folder reference check failed; preserving source files", error)
                }
                .getOrNull()
            if (currentHistory == null) {
                return@withLock Result(0, updatedCards, failedSourcePaths.size + preparedCopies.size)
            }

            var movedFiles = 0
            preparedCopies.forEach { (sourcePath, destinationCopy) ->
                val sourceFile = File(sourcePath)
                val stillReferenced = currentHistory.any { item ->
                    item.downloadPath.any { storedPath -> referencesSameLocalFile(storedPath, sourceFile) }
                }
                if (stillReferenced) {
                    failedSourcePaths += sourcePath
                    return@forEach
                }

                if (
                    sourceFile.exists() &&
                    (
                        sourceFile.length() != destinationCopy.sourceLength ||
                            sourceFile.lastModified() != destinationCopy.sourceLastModified
                        )
                ) {
                    failedSourcePaths += sourcePath
                    return@forEach
                }
                if (sourceFile.exists() && !sourceFile.delete()) {
                    failedSourcePaths += sourcePath
                    return@forEach
                }
                movedFiles += 1
            }

            Result(movedFiles, updatedCards, failedSourcePaths.size)
        }
    }

    private fun isMigrationCandidate(oldPath: String, sourceNormalized: String): Boolean {
        if (oldPath.startsWith("content://")) return false
        val oldFile = File(oldPath)
        if (!oldFile.exists() || !oldFile.isFile) return false
        val oldParent = oldFile.parentFile?.absolutePath?.replace('\\', '/') ?: return false
        return oldParent.equals(sourceNormalized, ignoreCase = true)
    }

    private fun copyToDestination(sourceFile: File, destinationRoot: String): DestinationCopy? =
        if (destinationRoot.startsWith("content://")) {
            copyToContentTree(sourceFile, destinationRoot)
        } else {
            copyToFileDirectory(sourceFile, destinationRoot)
        }

    private fun copyToFileDirectory(sourceFile: File, destinationRoot: String): DestinationCopy? {
        val destinationDirectory = File(destinationRoot)
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) return null
        if (!destinationDirectory.isDirectory) return null
        val destinationFile = createUniqueFile(destinationDirectory, sourceFile.name) ?: return null
        val initialLength = sourceFile.length()
        val initialLastModified = sourceFile.lastModified()
        var copyCompleted = false
        return try {
            val copiedBytes = FileInputStream(sourceFile).use { input ->
                FileOutputStream(destinationFile).use { output -> input.copyTo(output) }
            }
            val validCopy =
                copiedBytes == initialLength &&
                    sourceFile.length() == initialLength &&
                    sourceFile.lastModified() == initialLastModified &&
                    destinationFile.isFile &&
                    destinationFile.length() == initialLength
            if (!validCopy) {
                null
            } else {
                copyCompleted = true
                DestinationCopy(
                    path = destinationFile.absolutePath,
                    sourceLength = initialLength,
                    sourceLastModified = initialLastModified,
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            if (!copyCompleted) destinationFile.delete()
        }
    }

    private fun copyToContentTree(sourceFile: File, destinationRoot: String): DestinationCopy? {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(destinationRoot)) ?: return null
        val destinationName = resolveUniqueNameForTree(tree, sourceFile.name)
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(destinationName.substringAfterLast('.', "").lowercase(Locale.getDefault()))
            ?: "application/octet-stream"
        val created = tree.createFile(mimeType, destinationName) ?: return null
        val initialLength = sourceFile.length()
        val initialLastModified = sourceFile.lastModified()
        var copyCompleted = false
        return try {
            val copiedBytes = context.contentResolver.openOutputStream(created.uri)?.use { output ->
                FileInputStream(sourceFile).use { input -> input.copyTo(output) }
            } ?: return null
            val validCopy =
                copiedBytes == initialLength &&
                    sourceFile.length() == initialLength &&
                    sourceFile.lastModified() == initialLastModified
            if (!validCopy) {
                null
            } else {
                copyCompleted = true
                DestinationCopy(
                    path = created.uri.toString(),
                    sourceLength = initialLength,
                    sourceLastModified = initialLastModified,
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            if (!copyCompleted) created.delete()
        }
    }

    private fun createUniqueFile(directory: File, filename: String): File? {
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val extension = if (dot > 0) filename.substring(dot) else ""
        var index = 0
        while (index < Int.MAX_VALUE) {
            val candidateName = if (index == 0) filename else "$base ($index)$extension"
            val candidate = File(directory, candidateName)
            if (candidate.createNewFile()) return candidate
            index += 1
        }
        return null
    }

    private fun resolveUniqueNameForTree(tree: DocumentFile, filename: String): String {
        val dot = filename.lastIndexOf('.')
        val base = if (dot > 0) filename.substring(0, dot) else filename
        val extension = if (dot > 0) filename.substring(dot) else ""
        var candidate = filename
        var index = 1
        while (tree.findFile(candidate) != null) {
            candidate = "$base ($index)$extension"
            index += 1
        }
        return candidate
    }

    private fun referencesSameLocalFile(storedPath: String, sourceFile: File): Boolean {
        if (storedPath.startsWith("content://")) return false
        if (storedPath == sourceFile.path) return true
        return runCatching { File(storedPath).canonicalFile == sourceFile.canonicalFile }
            .getOrDefault(false)
    }

    private companion object {
        const val TAG = "HistoryVideoFolderMigration"
    }
}
