package com.ireum.ytdl.util.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException

enum class FileAccessState {
    EXISTS,
    MISSING,
    PERMISSION_REQUIRED,
    UNKNOWN,
    CHECKING
}

object FileAccessStateResolver {
    fun combine(states: List<FileAccessState>): FileAccessState {
        if (states.isEmpty()) return FileAccessState.UNKNOWN
        if (states.all { it == FileAccessState.EXISTS }) return FileAccessState.EXISTS
        if (states.any { it == FileAccessState.PERMISSION_REQUIRED }) {
            return FileAccessState.PERMISSION_REQUIRED
        }
        if (states.any { it == FileAccessState.MISSING }) return FileAccessState.MISSING
        if (states.any { it == FileAccessState.CHECKING }) return FileAccessState.CHECKING
        return FileAccessState.UNKNOWN
    }
}

object FileAccessChecker {
    fun checkAll(context: Context, paths: List<String>): FileAccessState {
        return FileAccessStateResolver.combine(
            paths.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .map { check(context, it) }
                .toList()
        )
    }

    fun check(context: Context, storedPath: String): FileAccessState {
        val path = storedPath.trim()
        if (path.isBlank()) return FileAccessState.UNKNOWN
        return when {
            path.startsWith("content://", ignoreCase = true) -> checkContentUri(context, path)
            path.startsWith("file://", ignoreCase = true) -> {
                val rawPath = runCatching { Uri.parse(path).path }.getOrNull().orEmpty()
                checkRawPath(context, rawPath)
            }
            else -> checkRawPath(context, path)
        }
    }

    private fun checkContentUri(context: Context, value: String): FileAccessState {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return FileAccessState.UNKNOWN
        return try {
            val document = if (DocumentsContract.isTreeUri(uri)) {
                DocumentFile.fromTreeUri(context, uri)
            } else {
                DocumentFile.fromSingleUri(context, uri)
            }
            if (document?.exists() == true) {
                if (document.isDirectory || document.canRead()) {
                    FileAccessState.EXISTS
                } else {
                    FileAccessState.PERMISSION_REQUIRED
                }
            } else {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { }
                FileAccessState.EXISTS
            }
        } catch (_: SecurityException) {
            FileAccessState.PERMISSION_REQUIRED
        } catch (_: FileNotFoundException) {
            if (hasReadGrant(context, uri)) FileAccessState.MISSING else FileAccessState.UNKNOWN
        } catch (_: IllegalArgumentException) {
            FileAccessState.UNKNOWN
        } catch (_: Exception) {
            FileAccessState.UNKNOWN
        }
    }

    private fun checkRawPath(context: Context, value: String): FileAccessState {
        if (value.isBlank()) return FileAccessState.UNKNOWN
        return try {
            val file = File(value)
            if (file.exists()) {
                if (file.canRead()) FileAccessState.EXISTS else FileAccessState.PERMISSION_REQUIRED
            } else {
                val parent = file.parentFile
                when {
                    parent?.exists() == true && parent.canRead() -> FileAccessState.MISSING
                    parent?.exists() == true -> FileAccessState.PERMISSION_REQUIRED
                    AppOwnedPathPolicy.isWithin(file, appOwnedRoots(context)) -> FileAccessState.MISSING
                    else -> FileAccessState.UNKNOWN
                }
            }
        } catch (_: SecurityException) {
            FileAccessState.PERMISSION_REQUIRED
        } catch (_: Exception) {
            FileAccessState.UNKNOWN
        }
    }

    private fun hasReadGrant(context: Context, uri: Uri): Boolean {
        val directGrant = context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
        if (directGrant) return true
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && uri.toString().startsWith(permission.uri.toString())
        }
    }

    private fun appOwnedRoots(context: Context): List<File> {
        return listOfNotNull(
            context.cacheDir,
            context.filesDir,
            context.externalCacheDir,
            context.getExternalFilesDir(null)
        )
    }
}
