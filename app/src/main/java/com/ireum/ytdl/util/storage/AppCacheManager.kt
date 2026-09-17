package com.ireum.ytdl.util.storage

import android.content.Context
import com.ireum.ytdl.util.FileUtil
import java.io.File

enum class AppCacheCategory {
    APP_CACHE,
    EXTERNAL_APP_CACHE,
    DOWNLOAD_TEMP,
    SHARE_CACHE,
    TERMINAL_CACHE,
    LOG_EXPORT_CACHE
}

data class AppCacheCategorySnapshot(
    val category: AppCacheCategory,
    val bytes: Long,
    val fileCount: Int,
    val available: Boolean
)

data class AppCacheScan(
    val scannedAtMillis: Long,
    val categories: List<AppCacheCategorySnapshot>
) {
    val totalBytes: Long = categories.sumOf { it.bytes }
}

/**
 * Exact file authority captured for one cache-maintenance operation.
 *
 * [files] is intentionally a frozen set.  A later cleanup pass may delete
 * only these path/metadata identities; it must not rediscover newly-created
 * files in the same cache root.
 */
data class AppCacheExactFile(
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
)

data class AppCacheExactSnapshot(
    val category: AppCacheCategory,
    val rootPath: String,
    val files: List<AppCacheExactFile>,
)

data class AppCacheDeletionResult(
    val requestedCategories: Set<AppCacheCategory>,
    val deletedBytes: Long,
    val deletedFiles: Int,
    val failedEntries: Int,
    val skippedCategories: Set<AppCacheCategory>
) {
    val isComplete: Boolean = failedEntries == 0 && skippedCategories.isEmpty()
    val isPartial: Boolean = !isComplete && (deletedFiles > 0 || deletedBytes > 0)
}

object AppOwnedPathPolicy {
    fun isWithin(candidate: File, allowedRoots: List<File>): Boolean {
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        return allowedRoots.any { root ->
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
            canonicalCandidate == canonicalRoot || canonicalCandidate.toPath().startsWith(canonicalRoot.toPath())
        }
    }
}

internal fun protectedAppCacheEntries(appCacheRoot: File): List<File> {
    return listOf(File(appCacheRoot, "cookies.txt"))
}

class AppCacheManager(private val context: Context) {
    private data class Target(
        val category: AppCacheCategory,
        val root: File,
        val exclusions: List<File>,
        val available: Boolean
    )

    fun scan(): AppCacheScan {
        val targets = targets()
        return AppCacheScan(
            scannedAtMillis = System.currentTimeMillis(),
            categories = AppCacheCategory.entries.map { category ->
                val target = targets[category]
                if (target == null || !target.available) {
                    AppCacheCategorySnapshot(category, 0L, 0, false)
                } else {
                    val files = collectFiles(target)
                    AppCacheCategorySnapshot(
                        category = category,
                        bytes = files.sumOf { it.length().coerceAtLeast(0L) },
                        fileCount = files.size,
                        available = true
                    )
                }
            }
        )
    }

    /**
     * Captures exact file paths for a later destructive operation.  A null
     * result means that the cache root or one of its directory listings could
     * not be proven; callers must fail closed rather than treating it as an
     * empty cache.
     */
    fun snapshotExact(category: AppCacheCategory): AppCacheExactSnapshot? {
        val target = targets()[category] ?: return null
        if (!target.available) return null
        val root = runCatching { target.root.canonicalFile }.getOrNull() ?: return null
        if (!root.exists()) {
            return AppCacheExactSnapshot(category, root.path, emptyList())
        }
        if (!root.isDirectory) return null

        val files = mutableListOf<AppCacheExactFile>()
        fun visit(directory: File): Boolean {
            val children = directory.listFiles() ?: return false
            children.forEach { child ->
                if (!AppOwnedPathPolicy.isWithin(child, listOf(root))) return@forEach
                if (target.exclusions.any { excluded ->
                        AppOwnedPathPolicy.isWithin(child, listOf(excluded))
                    }
                ) {
                    return@forEach
                }
                if (child.isDirectory) {
                    if (!visit(child)) return false
                } else if (child.isFile) {
                    val relative = runCatching {
                        child.canonicalFile.relativeTo(root).invariantSeparatorsPath
                    }.getOrNull() ?: return false
                    if (relative.isNotBlank()) {
                        files += AppCacheExactFile(
                            relativePath = relative,
                            size = child.length().coerceAtLeast(0L),
                            lastModified = child.lastModified(),
                        )
                    }
                }
            }
            return true
        }
        if (!visit(root)) return null
        return AppCacheExactSnapshot(
            category = category,
            rootPath = root.path,
            files = files.distinctBy(AppCacheExactFile::relativePath)
                .sortedBy(AppCacheExactFile::relativePath),
        )
    }

    suspend fun delete(categories: Set<AppCacheCategory>): AppCacheDeletionResult =
        CacheMaintenanceAuthority.withMaintenanceWindow {
            val targets = targets()
            var deletedBytes = 0L
            var deletedFiles = 0
            var failedEntries = 0
            val skipped = linkedSetOf<AppCacheCategory>()

            categories.forEach { category ->
                val target = targets[category]
                if (target == null || !target.available) {
                    skipped += category
                    return@forEach
                }
                val entries = collectEntries(target)
                    .sortedByDescending { it.toPath().nameCount }
                entries.forEach { entry ->
                    if (isLiveOwnedEntry(target.root, entry)) {
                        // A live owner is an honest incomplete deletion: the
                        // exact entry remains protected until that owner
                        // exits and a later maintenance pass can retry it.
                        failedEntries++
                        return@forEach
                    }
                    val wasFile = entry.isFile
                    val size = if (wasFile) entry.length().coerceAtLeast(0L) else 0L
                    if (entry.delete() || !entry.exists()) {
                        if (wasFile) {
                            deletedBytes += size
                            deletedFiles++
                        }
                    } else {
                        failedEntries++
                    }
                }
            }

            AppCacheDeletionResult(
                requestedCategories = categories,
                deletedBytes = deletedBytes,
                deletedFiles = deletedFiles,
                failedEntries = failedEntries,
                skippedCategories = skipped
            )
        }

    /**
     * Deletes only the exact files captured by [snapshot].  DOWNLOAD_TEMP
     * snapshots resolve their own canonical root so a later cache_path change
     * cannot redirect the operation to a different root.  Live-owned entries
     * remain protected by the normal maintenance gate.
     */
    suspend fun deleteExact(snapshot: AppCacheExactSnapshot): AppCacheDeletionResult =
        CacheMaintenanceAuthority.withMaintenanceWindow {
            val target = targetForExactSnapshot(snapshot)
            val root = target?.let { runCatching { it.root.canonicalFile }.getOrNull() }
            if (target == null || !target.available || root == null) {
                return@withMaintenanceWindow AppCacheDeletionResult(
                    requestedCategories = setOf(snapshot.category),
                    deletedBytes = 0L,
                    deletedFiles = 0,
                    failedEntries = 0,
                    skippedCategories = setOf(snapshot.category),
                )
            }

            var deletedBytes = 0L
            var deletedFiles = 0
            var failedEntries = 0
            snapshot.files.distinctBy(AppCacheExactFile::relativePath).forEach { file ->
                val candidate = runCatching {
                    File(root, file.relativePath).canonicalFile
                }.getOrNull()
                if (
                    candidate == null ||
                    candidate == root ||
                    !AppOwnedPathPolicy.isWithin(candidate, listOf(root)) ||
                    target.exclusions.any { excluded ->
                        AppOwnedPathPolicy.isWithin(candidate, listOf(excluded))
                    }
                ) {
                    failedEntries++
                    return@forEach
                }
                if (!candidate.exists()) return@forEach
                if (!candidate.isFile || isLiveOwnedEntry(root, candidate)) {
                    failedEntries++
                    return@forEach
                }
                if (
                    candidate.length().coerceAtLeast(0L) != file.size ||
                    candidate.lastModified() != file.lastModified
                ) {
                    // The path was reused or changed after the journaled
                    // snapshot.  Keep it rather than treating path equality
                    // as proof that it is still the same artifact.
                    failedEntries++
                    return@forEach
                }
                val size = candidate.length().coerceAtLeast(0L)
                if (candidate.delete() || !candidate.exists()) {
                    deletedBytes += size
                    deletedFiles++
                } else {
                    failedEntries++
                }
            }
            AppCacheDeletionResult(
                requestedCategories = setOf(snapshot.category),
                deletedBytes = deletedBytes,
                deletedFiles = deletedFiles,
                failedEntries = failedEntries,
                skippedCategories = emptySet(),
            )
        }

    private fun isLiveOwnedEntry(targetRoot: File, entry: File): Boolean {
        val root = runCatching { targetRoot.canonicalFile }.getOrNull() ?: return true
        val canonical = runCatching { entry.canonicalFile }.getOrNull() ?: return true
        var current: File? = canonical
        while (current != null && current != root) {
            if (DownloadCacheOwnership.isLiveOwnedMarker(root, current)) return true
            if (DownloadCacheOwnership.isLiveOwnedRoot(root, current)) return true
            if (TerminalCacheOwnership.isLiveOwnedRoot(current)) return true
            current = current.parentFile
        }
        return false
    }

    /**
     * Resolves a journaled snapshot without using mutable cache_path for the
     * DOWNLOAD_TEMP category.  The persisted root is still independently
     * constrained to the same application-owned boundary used by targets().
     */
    private fun targetForExactSnapshot(snapshot: AppCacheExactSnapshot): Target? {
        val root = runCatching { File(snapshot.rootPath).canonicalFile }.getOrNull()
            ?: return null
        if (!root.isAbsolute || root.path != snapshot.rootPath) return null

        if (snapshot.category == AppCacheCategory.DOWNLOAD_TEMP) {
            if (!AppOwnedPathPolicy.isWithin(root, applicationOwnedRoots())) return null
            return Target(
                category = AppCacheCategory.DOWNLOAD_TEMP,
                root = root,
                exclusions = listOf(
                    File(root, "TERMINAL"),
                    File(root, "Logs"),
                ),
                available = true,
            )
        }

        val current = targets()[snapshot.category] ?: return null
        if (!current.available) return null
        val currentRoot = runCatching { current.root.canonicalFile }.getOrNull() ?: return null
        return current.takeIf { currentRoot.path == root.path }
    }

    private fun applicationOwnedRoots(): List<File> = listOfNotNull(
        context.cacheDir,
        context.externalCacheDir,
        context.getExternalFilesDir(null)
    )

    private fun targets(): Map<AppCacheCategory, Target> {
        val ownershipRoots = applicationOwnedRoots()
        val appCache = context.cacheDir
        val externalCache = context.externalCacheDir
        val downloadTemp = File(FileUtil.getCachePath(context))
        val shareCache = File(appCache, "shared")
        val terminalCache = File(downloadTemp, "TERMINAL")
        val logCache = File(downloadTemp, "Logs")
        val persistentAppCacheEntries = protectedAppCacheEntries(appCache)

        val candidateRoots = linkedMapOf(
            AppCacheCategory.SHARE_CACHE to shareCache,
            AppCacheCategory.TERMINAL_CACHE to terminalCache,
            AppCacheCategory.LOG_EXPORT_CACHE to logCache,
            AppCacheCategory.DOWNLOAD_TEMP to downloadTemp,
            AppCacheCategory.APP_CACHE to appCache
        ).apply {
            externalCache?.let { put(AppCacheCategory.EXTERNAL_APP_CACHE, it) }
        }
        val accepted = linkedMapOf<AppCacheCategory, File>()
        val usedCanonicalRoots = linkedSetOf<String>()
        candidateRoots.forEach { (category, root) ->
            if (!AppOwnedPathPolicy.isWithin(root, ownershipRoots)) return@forEach
            val canonical = runCatching { root.canonicalPath }.getOrNull() ?: return@forEach
            if (usedCanonicalRoots.add(canonical)) accepted[category] = root
        }

        return AppCacheCategory.entries.associateWith { category ->
            val root = accepted[category]
            if (root == null) {
                Target(category, File("."), emptyList(), false)
            } else {
                val nestedCategoryExclusions = accepted
                    .filterKeys { it != category }
                    .values
                    .filter { other ->
                        other != root && AppOwnedPathPolicy.isWithin(other, listOf(root))
                    }
                val exclusions = buildList {
                    addAll(nestedCategoryExclusions)
                    if (category == AppCacheCategory.APP_CACHE) {
                        addAll(persistentAppCacheEntries)
                    }
                }
                Target(category, root, exclusions, true)
            }
        }
    }

    private fun collectFiles(target: Target): List<File> {
        return collectEntries(target).filter(File::isFile)
    }

    private fun collectEntries(target: Target): List<File> {
        if (!target.root.exists() || !target.root.isDirectory) return emptyList()
        val entries = mutableListOf<File>()

        fun visit(directory: File) {
            val children = directory.listFiles() ?: return
            children.forEach { child ->
                if (!AppOwnedPathPolicy.isWithin(child, listOf(target.root))) return@forEach
                if (target.exclusions.any { excluded ->
                        AppOwnedPathPolicy.isWithin(child, listOf(excluded))
                    }
                ) {
                    return@forEach
                }
                entries += child
                if (child.isDirectory) visit(child)
            }
        }

        visit(target.root)
        return entries
    }
}
