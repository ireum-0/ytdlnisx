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
 * policy. Provider document IDs are scoped by their authority and are
 * opaque. A generic DocumentsProvider contract does not give this client a
 * portable textual relative path, so tree metadata is never used to
 * manufacture a storage identity. The exact provider document identity
 * remains usable through the document URI itself.
 */
object LocalAddStorageIdentityPolicy {
    fun hasSameProviderAuthority(treeUri: Uri, fileUri: Uri): Boolean =
        sameAuthority(treeUri, fileUri)

    /**
     * Returns tree metadata only when a provider-backed proof of both
     * membership and path semantics exists. Generic document IDs are opaque
     * and this shared policy has no such proof, so it intentionally returns
     * null. Callers must retain the exact document URI instead of inventing a
     * relative path from a document-ID prefix.
     */
    fun validatedTreeMetadata(treeUri: Uri?, fileUri: Uri): Pair<String, String>? = null

    fun identityForEntry(uriString: String, treeUriString: String? = null): String? {
        val uri = parseUri(uriString) ?: return null
        return identityForUri(uri)
    }

    fun identitiesForHistory(item: HistoryItem): Set<String> {
        val identities = linkedSetOf<String>()
        item.downloadPath.forEach { path ->
            identityForUri(path)?.let(identities::add)
        }
        return identities
    }

    fun hasSameStorageIdentity(candidate: HistoryItem, existing: HistoryItem): Boolean {
        val candidateIdentities = identitiesForHistory(candidate)
        if (candidateIdentities.isEmpty()) return false
        return candidateIdentities.any { it in identitiesForHistory(existing) }
    }

    private fun identityForUri(uri: Uri): String? {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return when (scheme) {
            "content" -> {
                val authority = uri.authority
                val documentId = runCatching { DocumentsContract.getDocumentId(uri) }
                    .getOrNull()
                if (!authority.isNullOrBlank() && documentId != null) {
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
    ): String? {
        val uri = parseUri(path) ?: return null
        return identityForUri(uri)
    }

    private fun sameAuthority(first: Uri, second: Uri): Boolean {
        val firstAuthority = first.authority
        val secondAuthority = second.authority
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
