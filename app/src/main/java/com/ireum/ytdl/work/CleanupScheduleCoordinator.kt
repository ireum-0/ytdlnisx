package com.ireum.ytdl.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Calendar-based recurrence policy for leftover-download cleanup. */
internal object CleanupSchedulePolicy {
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    const val MONTHLY = "monthly"

    fun isEnabled(cadence: String?): Boolean = cadence == DAILY ||
        cadence == WEEKLY || cadence == MONTHLY

    /**
     * Compute the next local-calendar occurrence.  Monthly schedules keep an
     * anchor day so Jan 31 -> Feb 28/29 -> Mar 31 rather than drifting to the
     * 28th/29th after the short month.
     */
    fun nextOccurrence(
        now: Calendar,
        cadence: String,
        monthlyAnchorDay: Int = now.get(Calendar.DAY_OF_MONTH),
    ): Calendar {
        val next = now.clone() as Calendar
        when (cadence) {
            DAILY -> next.add(Calendar.DAY_OF_YEAR, 1)
            WEEKLY -> next.add(Calendar.DAY_OF_YEAR, 7)
            MONTHLY -> {
                next.add(Calendar.MONTH, 1)
                val lastDay = next.getActualMaximum(Calendar.DAY_OF_MONTH)
                next.set(
                    Calendar.DAY_OF_MONTH,
                    monthlyAnchorDay.coerceIn(1, lastDay),
                )
            }
            else -> error("Unsupported cleanup cadence: $cadence")
        }
        return next
    }
}

/**
 * Owns the single logical cleanup schedule.  Preference changes, startup
 * reconciliation, and successful worker completion all use this coordinator
 * so a timestamp-derived WorkManager name cannot create parallel chains.
 */
internal object CleanupScheduleCoordinator {
    const val WORK_NAME = "cleanup_leftover_downloads_schedule"
    const val TAG = "cleanup_leftover_downloads"
    const val INPUT_GENERATION = "cleanup_generation"
    const val INPUT_CADENCE = "cleanup_cadence"
    const val INPUT_MONTHLY_ANCHOR_DAY = "cleanup_monthly_anchor_day"

    private const val PREF_CADENCE = "cleanup_leftover_downloads"
    private const val PREF_GENERATION = "cleanup_leftover_downloads_generation"
    private const val PREF_MONTHLY_ANCHOR_DAY = "cleanup_leftover_downloads_anchor_day"

    private val lock = Any()

    /** Apply a user cadence change and atomically publish its new generation. */
    fun configure(context: Context, cadence: String?): Boolean = synchronized(lock) {
        val appContext = context.applicationContext
        val preferences = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(appContext)
        val generation = UUID.randomUUID().toString()
        val anchorDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        preferences.edit()
            .putString(PREF_CADENCE, cadence.orEmpty())
            .putString(PREF_GENERATION, generation)
            .putInt(PREF_MONTHLY_ANCHOR_DAY, anchorDay)
            .commit()

        val workManager = WorkManager.getInstance(appContext)
        // This also retires legacy timestamp-named requests from pre-coordinator
        // installs.  The stable request is then the only future owner.
        workManager.cancelAllWorkByTag(TAG)
        if (!CleanupSchedulePolicy.isEnabled(cadence)) {
            return@synchronized true
        }
        enqueueNextLocked(
            workManager = workManager,
            generation = generation,
            cadence = cadence!!,
            monthlyAnchorDay = anchorDay,
            from = Calendar.getInstance(),
        )
        true
    }

    /** Reconcile preference authority with WorkManager after process restart. */
    fun reconcile(context: Context) = synchronized(lock) {
        val appContext = context.applicationContext
        val preferences = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(appContext)
        val cadence = preferences.getString(PREF_CADENCE, null)
        if (!CleanupSchedulePolicy.isEnabled(cadence)) {
            WorkManager.getInstance(appContext).cancelAllWorkByTag(TAG)
            return@synchronized
        }
        val generation = preferences.getString(PREF_GENERATION, null).takeUnless { it.isNullOrBlank() }
            ?: UUID.randomUUID().toString().also { createdGeneration ->
                preferences.edit()
                    .putString(PREF_GENERATION, createdGeneration)
                    .putInt(
                        PREF_MONTHLY_ANCHOR_DAY,
                        Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
                    )
                    .commit()
            }

        val workManager = WorkManager.getInstance(appContext)
        val current = runCatching {
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
        }.getOrDefault(emptyList())
        val currentGenerationTag = generationTag(generation)
        val hasCurrent = current.any { info ->
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED
        } && current.any { info -> info.tags.contains(currentGenerationTag) }

        if (hasCurrent) {
            // Remove only legacy requests; keep the valid stable chain intact.
            runCatching {
                workManager.getWorkInfosByTag(TAG).get()
                    .filter { info ->
                        info.id !in current.map(WorkInfo::id) &&
                            (info.state == WorkInfo.State.ENQUEUED ||
                                info.state == WorkInfo.State.RUNNING ||
                                info.state == WorkInfo.State.BLOCKED)
                    }
                    .forEach { info -> workManager.cancelWorkById(info.id) }
            }
            return@synchronized
        }

        workManager.cancelAllWorkByTag(TAG)
        val enabledCadence = cadence ?: return@synchronized
        enqueueNextLocked(
            workManager = workManager,
            generation = generation,
            cadence = enabledCadence,
            monthlyAnchorDay = preferences.getInt(
                PREF_MONTHLY_ANCHOR_DAY,
                Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
            ),
            from = Calendar.getInstance(),
        )
    }

    /**
     * Called only after cleanup has completed successfully.  The generation
     * and cadence are revalidated while holding the same lock used by
     * configure(), so a stale worker cannot resurrect an old schedule.
     */
    fun scheduleSuccessor(
        context: Context,
        generation: String?,
        cadence: String?,
        monthlyAnchorDay: Int,
    ): Boolean = synchronized(lock) {
        val appContext = context.applicationContext
        val preferences = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(appContext)
        val currentGeneration = preferences.getString(PREF_GENERATION, null)
        val currentCadence = preferences.getString(PREF_CADENCE, null)
        if (generation.isNullOrBlank() || generation != currentGeneration || cadence != currentCadence) {
            return@synchronized false
        }
        if (!CleanupSchedulePolicy.isEnabled(currentCadence)) {
            return@synchronized false
        }
        enqueueNextLocked(
            workManager = WorkManager.getInstance(appContext),
            generation = generation,
            cadence = currentCadence!!,
            monthlyAnchorDay = monthlyAnchorDay,
            from = Calendar.getInstance(),
        )
        true
    }

    private fun enqueueNextLocked(
        workManager: WorkManager,
        generation: String,
        cadence: String,
        monthlyAnchorDay: Int,
        from: Calendar,
    ): OneTimeWorkRequest {
        val next = CleanupSchedulePolicy.nextOccurrence(from, cadence, monthlyAnchorDay)
        val delayMs = (next.timeInMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<CleanUpLeftoverDownloads>()
            .setConstraints(Constraints.Builder().build())
            .setInputData(
                Data.Builder()
                    .putString(INPUT_GENERATION, generation)
                    .putString(INPUT_CADENCE, cadence)
                    .putInt(INPUT_MONTHLY_ANCHOR_DAY, monthlyAnchorDay)
                    .build()
            )
            .addTag(TAG)
            .addTag(generationTag(generation))
            .addTag(cadenceTag(cadence))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        return request
    }

    private fun generationTag(generation: String): String = "${TAG}_$generation"

    private fun cadenceTag(cadence: String): String = "${TAG}_cadence_$cadence"
}
