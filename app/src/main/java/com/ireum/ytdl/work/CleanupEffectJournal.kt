package com.ireum.ytdl.work

import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.util.storage.AppCacheCategory
import com.ireum.ytdl.util.storage.AppCacheExactSnapshot

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
    val cancelledRefreshComplete: Boolean = false,
    val erroredDeletionComplete: Boolean = false,
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
            (!tempCleanupRequired || (
                tempSnapshot != null &&
                    tempSnapshot.category == AppCacheCategory.DOWNLOAD_TEMP &&
                    tempSnapshot.files.all { it.relativePath.isNotBlank() }
                ))
    }.getOrDefault(false)

    val isComplete: Boolean
        get() = cancelledDeletionComplete &&
            cancelledRefreshComplete &&
            erroredDeletionComplete &&
            erroredRefreshComplete &&
            (!tempCleanupRequired || tempCleanupComplete)
}
