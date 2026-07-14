package com.ireum.ytdl.util

import android.content.Context
import java.io.File

object AppPrivatePathRedactor {
    fun prefixes(context: Context): List<String> {
        val appContext = context.applicationContext
        return listOfNotNull(
            appContext.dataDir,
            appContext.filesDir,
            appContext.cacheDir,
            appContext.noBackupFilesDir,
            appContext.externalCacheDir,
            appContext.getExternalFilesDir(null),
            File(appContext.applicationInfo.nativeLibraryDir)
        ).map(File::getAbsolutePath)
            .distinct()
            .sortedByDescending(String::length)
    }

    fun redact(context: Context, text: String): String {
        return SensitiveTextRedactor.redactPrivatePaths(text, prefixes(context))
    }
}
