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

    fun delete(categories: Set<AppCacheCategory>): AppCacheDeletionResult {
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

        return AppCacheDeletionResult(
            requestedCategories = categories,
            deletedBytes = deletedBytes,
            deletedFiles = deletedFiles,
            failedEntries = failedEntries,
            skippedCategories = skipped
        )
    }

    private fun targets(): Map<AppCacheCategory, Target> {
        val ownershipRoots = listOfNotNull(
            context.cacheDir,
            context.externalCacheDir,
            context.getExternalFilesDir(null)
        )
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
