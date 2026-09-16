package com.ireum.ytdl.work

import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.util.storage.AppCacheCategory
import com.ireum.ytdl.util.storage.AppCacheExactSnapshot
import com.ireum.ytdl.util.storage.DownloadCacheOwnership

/**
 * Restart-reconstructible authority for one cleanup occurrence.
 *
 * The target lists are snapshots of the rows that were eligible when the
 * occurrence was admitted.  Completion flags are advanced only after the
 * corresponding production side effect returns successfully.  A retry may
 * therefore resume an unfinished step, but it can never widen a step by
 * querying newly eligible rows.
 */
internal data class CleanupEffectJournal(
    val generation: String,
    val cadence: String,
    val monthlyAnchorDay: Int,
    val occurrenceAt: Long,
    val cancelledTargets: List<DownloadItem> = emptyList(),
    val erroredTargets: List<DownloadItem> = emptyList(),
    val cancelledOperationIds: List<String> = emptyList(),
    val erroredOperationIds: List<String> = emptyList(),
    val tempSnapshot: AppCacheExactSnapshot? = null,
    val tempCleanupRequired: Boolean = false,
    val cancelledDeletionComplete: Boolean = false,
    /** Download ids whose exact cache suffix existed at journal creation. */
    val cancelledCacheCleanupRequiredIds: List<Long> = emptyList(),
    /** Exact cache suffixes completed after the Room deletion step. */
    val cancelledCacheCleanupCompletedIds: List<Long> = emptyList(),
    /** Exact cache roots captured for each cancelled target. */
    val cancelledCacheCleanupBindings: List<DownloadCacheOwnership.CacheBinding> = emptyList(),
    /** Exact cache roots completed after the Room deletion step. */
    val cancelledCacheCleanupCompletedBindings: List<DownloadCacheOwnership.CacheBinding> = emptyList(),
    val cancelledRefreshComplete: Boolean = false,
    val erroredDeletionComplete: Boolean = false,
    val erroredCacheCleanupRequiredIds: List<Long> = emptyList(),
    val erroredCacheCleanupCompletedIds: List<Long> = emptyList(),
    /** Exact cache roots captured for each errored target. */
    val erroredCacheCleanupBindings: List<DownloadCacheOwnership.CacheBinding> = emptyList(),
    /** Exact cache roots completed after the Room deletion step. */
    val erroredCacheCleanupCompletedBindings: List<DownloadCacheOwnership.CacheBinding> = emptyList(),
    val erroredRefreshComplete: Boolean = false,
    val tempCleanupComplete: Boolean = true,
) {
    fun matches(
        expectedGeneration: String,
        expectedCadence: String,
        expectedAnchorDay: Int,
        expectedOccurrenceAt: Long,
    ): Boolean = generation == expectedGeneration &&
        cadence == expectedCadence &&
        monthlyAnchorDay == expectedAnchorDay &&
        occurrenceAt == expectedOccurrenceAt

    fun matches(debt: CleanupScheduleCoordinator.SchedulingDebt): Boolean = matches(
        expectedGeneration = debt.generation,
        expectedCadence = debt.cadence,
        expectedAnchorDay = debt.monthlyAnchorDay,
        expectedOccurrenceAt = debt.occurrenceAt,
    )

    fun isStructurallyValid(): Boolean = runCatching {
        generation.isNotBlank() &&
            CleanupSchedulePolicy.isEnabled(cadence) &&
            monthlyAnchorDay > 0 &&
            occurrenceAt > 0L &&
            cancelledTargets.all { it.id >= 0L } &&
            erroredTargets.all { it.id >= 0L } &&
            cancelledOperationIds.all(String::isNotBlank) &&
            erroredOperationIds.all(String::isNotBlank) &&
            cancelledCacheCleanupRequiredIds.all { id ->
                cancelledTargets.any { it.id == id }
            } &&
            cancelledCacheCleanupCompletedIds.all { id ->
                id in cancelledCacheCleanupRequiredIds
            } &&
            erroredCacheCleanupRequiredIds.all { id ->
                erroredTargets.any { it.id == id }
            } &&
            erroredCacheCleanupCompletedIds.all { id ->
                id in erroredCacheCleanupRequiredIds
            } &&
            validBindings(
                required = cancelledCacheCleanupBindings.orEmpty(),
                completed = cancelledCacheCleanupCompletedBindings.orEmpty(),
                targets = cancelledTargets,
            ) &&
            (cancelledCacheCleanupBindings.orEmpty().isEmpty() ||
                cancelledCacheCleanupRequiredIds.orEmpty().all { id ->
                    cancelledCacheCleanupBindings.orEmpty().any { it.downloadId == id }
                }) &&
            validBindings(
                required = erroredCacheCleanupBindings.orEmpty(),
                completed = erroredCacheCleanupCompletedBindings.orEmpty(),
                targets = erroredTargets,
            ) &&
            (erroredCacheCleanupBindings.orEmpty().isEmpty() ||
                erroredCacheCleanupRequiredIds.orEmpty().all { id ->
                    erroredCacheCleanupBindings.orEmpty().any { it.downloadId == id }
                }) &&
            (!tempCleanupRequired || (
                tempSnapshot != null &&
                    tempSnapshot.category == AppCacheCategory.DOWNLOAD_TEMP &&
                    tempSnapshot.files.all { it.relativePath.isNotBlank() }
                ))
    }.getOrDefault(false)

    val isComplete: Boolean
        get() = cancelledDeletionComplete &&
            cacheBindingsComplete(
                required = cancelledCacheCleanupBindings.orEmpty(),
                completed = cancelledCacheCleanupCompletedBindings.orEmpty(),
                requiredIds = cancelledCacheCleanupRequiredIds,
                completedIds = cancelledCacheCleanupCompletedIds,
            ) &&
            cancelledRefreshComplete &&
            erroredDeletionComplete &&
            cacheBindingsComplete(
                required = erroredCacheCleanupBindings.orEmpty(),
                completed = erroredCacheCleanupCompletedBindings.orEmpty(),
                requiredIds = erroredCacheCleanupRequiredIds,
                completedIds = erroredCacheCleanupCompletedIds,
            ) &&
            erroredRefreshComplete &&
            (!tempCleanupRequired || tempCleanupComplete)

    private fun validBindings(
        required: List<DownloadCacheOwnership.CacheBinding>,
        completed: List<DownloadCacheOwnership.CacheBinding>,
        targets: List<DownloadItem>,
    ): Boolean = required.distinct().size == required.size &&
        completed.distinct().size == completed.size &&
        required.all { binding ->
            binding.downloadId > 0L &&
                targets.any { target -> target.id == binding.downloadId } &&
                runCatching {
                    val root = java.io.File(binding.rootPath).canonicalFile
                    root.isAbsolute && root.absolutePath == binding.rootPath
                }.getOrDefault(false)
        } && completed.all { it in required }

    private fun cacheBindingsComplete(
        required: List<DownloadCacheOwnership.CacheBinding>,
        completed: List<DownloadCacheOwnership.CacheBinding>,
        requiredIds: List<Long>,
        completedIds: List<Long>,
    ): Boolean = if (required.isNotEmpty()) {
        requiredIds.orEmpty().all { id -> required.any { it.downloadId == id } } &&
            required.all { it in completed }
    } else {
        requiredIds.all { it in completedIds }
    }
}
