package com.ireum.ytdl.util

import android.net.Uri
import android.provider.DocumentsContract
import com.ireum.ytdl.database.models.HistoryItem
import java.io.File
import java.util.Locale

/**
 * Strong storage identities used by LocalAdd admission.
 *
 * A filename is presentation metadata and is deliberately absent from this
 * policy.  Provider document IDs are scoped by their authority, while tree
 * identities are scoped by the normalized tree URI and relative path.
 */
object LocalAddStorageIdentityPolicy {
    fun hasSameProviderAuthority(treeUri: Uri, fileUri: Uri): Boolean =
        sameAuthority(treeUri, fileUri)

    fun identityForEntry(uriString: String, treeUriString: String? = null): String? {
        val uri = parseUri(uriString) ?: return null
        val treeUri = treeUriString?.let(::parseUri)
        val relativePath = if (treeUri != null) relativePath(treeUri, uri) else null
        return identityForUri(uri, treeUri, relativePath)
    }

    fun identitiesForHistory(item: HistoryItem): Set<String> {
        val identities = linkedSetOf<String>()
        val treeUri = item.localTreeUri.trim().takeIf(String::isNotBlank)?.let(::parseUri)
        val relativePath = item.localTreePath.trim().takeIf(String::isNotBlank)
        item.downloadPath.forEach { path ->
            identityForUri(path, treeUri, relativePath)?.let(identities::add)
        }
        return identities
    }

    fun hasSameStorageIdentity(candidate: HistoryItem, existing: HistoryItem): Boolean {
        val candidateIdentities = identitiesForHistory(candidate)
        if (candidateIdentities.isEmpty()) return false
        return candidateIdentities.any { it in identitiesForHistory(existing) }
    }

    private fun identityForUri(
        uri: Uri,
        treeUri: Uri?,
        relativePath: String?,
    ): String? {
        if (treeUri != null && !relativePath.isNullOrBlank() && sameAuthority(treeUri, uri)) {
            treeIdentity(treeUri, relativePath)?.let { return it }
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return when (scheme) {
            "content" -> {
                val authority = uri.authority?.trim()?.lowercase(Locale.ROOT)
                val documentId = runCatching { DocumentsContract.getDocumentId(uri) }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                if (!authority.isNullOrBlank() && !documentId.isNullOrBlank()) {
                    "provider:$authority:$documentId"
                } else {
                    normalizedUri(uri)?.takeIf { authority?.isNotBlank() == true }
                        ?.let { "uri:$it" }
                }
            }
            "file" -> uri.path?.let(::canonicalFileIdentity)
            null, "" -> canonicalFileIdentity(uri.toString())
            else -> null
        }
    }

    private fun identityForUri(
        path: String,
        treeUri: Uri?,
        relativePath: String?,
    ): String? {
        val uri = parseUri(path) ?: return null
        return identityForUri(uri, treeUri, relativePath)
    }

    private fun treeIdentity(treeUri: Uri, relativePath: String): String? {
        val normalizedTree = normalizedUri(treeUri) ?: return null
        val relative = relativePath.trim().trimStart('/').takeIf(String::isNotBlank)
            ?: return null
        return "tree:$normalizedTree|$relative"
    }

    private fun relativePath(treeUri: Uri, fileUri: Uri): String? {
        if (!sameAuthority(treeUri, fileUri)) return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
        val documentId = runCatching { DocumentsContract.getDocumentId(fileUri) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
        if (treeId == null || documentId == null) return null
        return if (documentId == treeId) {
            null
        } else {
            documentId.removePrefix("$treeId/")
                .removePrefix(treeId)
                .trimStart('/')
                .takeIf(String::isNotBlank)
        }
    }

    private fun sameAuthority(first: Uri, second: Uri): Boolean {
        val firstAuthority = first.authority?.trim()?.lowercase(Locale.ROOT)
        val secondAuthority = second.authority?.trim()?.lowercase(Locale.ROOT)
        return !firstAuthority.isNullOrBlank() && firstAuthority == secondAuthority
    }

    private fun parseUri(value: String): Uri? = value.trim()
        .takeIf(String::isNotBlank)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun normalizedUri(uri: Uri): String? = runCatching {
        uri.normalizeScheme().toString().takeIf(String::isNotBlank)
    }.getOrNull()

    private fun canonicalFileIdentity(path: String): String? = runCatching {
        val canonical = File(path).canonicalFile.path.takeIf(String::isNotBlank) ?: return null
        "file:$canonical"
    }.getOrNull()
}
