package com.ireum.ytdl.util

import com.ireum.ytdl.database.models.HistoryItem
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * Strong, storage-scoped identity for LocalAdd admission.
 *
 * A display name or document id is provider-local metadata and is therefore
 * never enough to exclude a local item.  Only an exact stable URI or an exact
 * tree URI plus relative document path is comparable across records.
 */
object LocalAddIdentity {
    /**
     * Returns a comparable identity, or null when the input cannot prove a
     * stable local identity.  Null deliberately means "do not dedupe".
     */
    fun keyFor(uri: String, treeUri: String? = null, treePath: String? = null): String? {
        return keysFor(uri, treeUri, treePath).firstOrNull()
    }

    /** Returns every independently proven identity carried by the same entry. */
    fun keysFor(uri: String, treeUri: String? = null, treePath: String? = null): Set<String> {
        val keys = LinkedHashSet<String>()
        keyForTree(treeUri, treePath)?.let(keys::add)
        keyForUri(uri)?.let(keys::add)
        return keys
    }

    /** Returns a tree-scoped identity when both authority and relative path are valid. */
    fun keyForTree(treeUri: String?, relativePath: String?): String? {
        val normalizedTree = treeUri?.let(::normalizeStableUri) ?: return null
        val relative = relativePath?.trim().orEmpty()
        if (relative.isBlank() || containsUnsafeCharacters(relative)) return null
        return "tree:$normalizedTree|$relative"
    }

    /** Returns identities carried by a persisted History row. */
    fun keysForHistory(item: HistoryItem): Set<String> {
        val keys = LinkedHashSet<String>()
        keyForTree(item.localTreeUri, item.localTreePath)?.let(keys::add)
        item.downloadPath.forEach { path ->
            keyForUri(path)?.let(keys::add)
        }
        return keys
    }

    /** Returns identities carried by a persisted LocalAdd entry. */
    fun keyForEntry(uri: String, treeUri: String?, treePath: String? = null): String? =
        keyFor(uri, treeUri, treePath)

    private fun keyForUri(rawValue: String): String? {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank() || containsUnsafeCharacters(trimmed)) return null
        if (trimmed.startsWith("/")) {
            val normalizedPath = runCatching { File(trimmed).canonicalPath }
                .getOrElse { File(trimmed).absolutePath }
            return "path:$normalizedPath"
        }
        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
        return when (scheme) {
            "content" -> {
                if (parsed.rawAuthority.isNullOrBlank() || parsed.rawPath.isNullOrBlank()) return null
                normalizedWithScheme(trimmed, scheme)
            }
            "file" -> {
                if (parsed.rawPath.isNullOrBlank()) return null
                normalizedWithScheme(trimmed, scheme)
            }
            else -> null
        }?.let { "uri:$it" }
    }

    private fun normalizeStableUri(rawValue: String): String? {
        val trimmed = rawValue.trim()
        if (trimmed.isBlank() || containsUnsafeCharacters(trimmed)) return null
        val parsed = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "content" && scheme != "file") return null
        if (scheme == "content" && (parsed.rawAuthority.isNullOrBlank() || parsed.rawPath.isNullOrBlank())) return null
        if (scheme == "file" && parsed.rawPath.isNullOrBlank()) return null
        return normalizedWithScheme(trimmed, scheme)
    }

    private fun normalizedWithScheme(value: String, normalizedScheme: String): String {
        val separator = value.indexOf(':')
        return if (separator < 0) value else normalizedScheme + value.substring(separator)
    }

    private fun containsUnsafeCharacters(value: String): Boolean =
        value.any { character -> character.isWhitespace() || character.code < 0x20 || character.code == 0x7f }
}
