package com.ireum.ytdl.database

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.Locale

/** Reads and removes the exact publication identity returned by backup(). */
internal object BackupPublicationTestSupport {
    fun readText(context: Context, publication: String): String =
        openStream(context, publication).bufferedReader().use { it.readText() }

    fun exists(context: Context, publication: String): Boolean =
        runCatching {
            openStream(context, publication).use { }
        }.isSuccess

    fun delete(context: Context, publication: String) {
        val uri = Uri.parse(publication)
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "content" -> runCatching {
                context.contentResolver.delete(uri, null, null)
            }
            "file" -> uri.path?.let { File(it).delete() }
            else -> File(publication).delete()
        }
    }

    private fun openStream(context: Context, publication: String) = when (
        Uri.parse(publication).scheme?.lowercase(Locale.ROOT)
    ) {
        "content" -> context.contentResolver.openInputStream(Uri.parse(publication))
            ?: throw IOException("Unable to open backup publication: $publication")
        "file" -> File(requireNotNull(Uri.parse(publication).path)).inputStream()
        else -> File(publication).inputStream()
    }
}
