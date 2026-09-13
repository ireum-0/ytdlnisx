package com.ireum.ytdl.work

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Calendar semantics for the automatic leftover-download cleanup cadence. */
internal object CleanupSchedulePolicy {
    const val DAILY = "daily"
    const val WEEKLY = "weekly"
    const val MONTHLY = "monthly"

    fun isEnabled(cadence: String?): Boolean = cadence == DAILY ||
        cadence == WEEKLY || cadence == MONTHLY

    /**
     * Computes the next occurrence in the calendar/time-zone represented by
     * [now]. Monthly schedules retain their original anchor day, so a Jan 31
     * schedule becomes Feb 28/29 and then returns to Mar 31 instead of
     * drifting permanently to the short-month day.
     */
    fun nextOccurrence(
        now: Calendar,
        cadence: String,
        monthlyAnchorDay: Int = now.get(Calendar.DAY_OF_MONTH),
    ): Calendar {
        val next = now.clone() as Calendar
        when (cadence) {
            DAILY -> next.add(Calendar.DATE, 1)
            WEEKLY -> next.add(Calendar.DATE, 7)
            MONTHLY -> {
                val anchor = monthlyAnchorDay.coerceAtLeast(1)
                // Set day 1 before adding a month; otherwise Jan 31 + one
                // month can overflow into March before the anchor is applied.
                next.set(Calendar.DAY_OF_MONTH, 1)
                next.add(Calendar.MONTH, 1)
                val lastDay = next.getActualMaximum(Calendar.DAY_OF_MONTH)
                next.set(Calendar.DAY_OF_MONTH, anchor.coerceAtMost(lastDay))
            }
            else -> error("Unsupported cleanup cadence: $cadence")
        }
        return next
    }
}

/**
 * Durable owner of the single logical automatic-cleanup WorkManager chain.
 * Preference changes, startup reconciliation, and worker successor
 * publication all validate the same persisted cadence/generation authority.
 */
internal object CleanupScheduleCoordinator {
    const val WORK_NAME = "cleanup_leftover_downloads_schedule"
    const val TAG = "cleanup_leftover_downloads"
    const val INPUT_GENERATION = "cleanup_generation"
    const val INPUT_CADENCE = "cleanup_cadence"
    const val INPUT_MONTHLY_ANCHOR_DAY = "cleanup_monthly_anchor_day"
    const val INPUT_OCCURRENCE_AT = "cleanup_occurrence_at"

    private const val PREF_CADENCE = "cleanup_leftover_downloads"
    private const val PREF_GENERATION = "cleanup_leftover_downloads_generation"
    private const val PREF_MONTHLY_ANCHOR_DAY = "cleanup_leftover_downloads_anchor_day"
    private const val PREF_PENDING_GENERATION = "cleanup_leftover_downloads_pending_generation"
    private const val PREF_PENDING_CADENCE = "cleanup_leftover_downloads_pending_cadence"
    private const val PREF_PENDING_ANCHOR_DAY = "cleanup_leftover_downloads_pending_anchor_day"
    private const val PREF_PENDING_OCCURRENCE_AT = "cleanup_leftover_downloads_pending_occurrence_at"

    private val lock = Any()

    /** Null-default seams used only by deterministic production-wiring tests. */
    @Volatile
    internal var workManagerForTesting: WorkManager? = null
    @Volatile
    internal var nowProviderForTesting: (() -> Calendar)? = null
    @Volatile
    internal var initialDelayOverrideForTesting: Long? = null
    @Volatile
    internal var successorDelayOverrideForTesting: Long? = null

    /** Null-default seam for modeling asynchronous enqueue acceptance/failure. */
    @Volatile
    internal var enqueueOverrideForTesting:
        ((String, ExistingWorkPolicy, OneTimeWorkRequest) -> Operation)? = null

    private data class EnqueueHandle(
        val request: OneTimeWorkRequest,
        val operation: Operation?,
        val alreadyPresent: Boolean = false,
        val generation: String,
        val cadence: String,
        val monthlyAnchorDay: Int,
        val occurrenceAt: Long,
    )

    fun configure(context: Context, cadence: String?): Boolean = synchronized(lock) {
        val appContext = context.applicationContext
        val normalizedCadence = cadence?.takeIf(CleanupSchedulePolicy::isEnabled)
        val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
        val generation = UUID.randomUUID().toString()
        val now = currentCalendar()
        val anchorDay = now.get(Calendar.DAY_OF_MONTH)

        // Commit the new authority before cancelling/replacing any old work.
        // A stale worker therefore observes the new generation even if
        // WorkManager cancellation is still in flight.
        val authorityCommitted = preferences.edit()
            .putString(PREF_CADENCE, normalizedCadence.orEmpty())
            .putString(PREF_GENERATION, generation)
            .putInt(PREF_MONTHLY_ANCHOR_DAY, anchorDay)
            .commit()
        if (!authorityCommitted) return@synchronized false

        val workManager = workManager(appContext)
        workManager.cancelAllWorkByTag(TAG)
        if (normalizedCadence == null) {
            return@synchronized true
        }

        enqueueNextLocked(
            context = appContext,
            workManager = workManager,
            generation = generation,
            cadence = normalizedCadence,
            monthlyAnchorDay = anchorDay,
            from = now,
            append = false,
            successor = false,
        )?.let { handle -> observeAcceptance(appContext, handle) }
        true
    }

    /** Reconciles persisted cadence authority with WorkManager after restart. */
    fun reconcile(context: Context) = synchronized(lock) {
        val appContext = context.applicationContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
        val cadence = preferences.getString(PREF_CADENCE, null)
        val workManager = workManager(appContext)

        if (!CleanupSchedulePolicy.isEnabled(cadence)) {
            workManager.cancelAllWorkByTag(TAG)
            return@synchronized
        }

        val now = currentCalendar()
        var generation = preferences.getString(PREF_GENERATION, null)
        if (generation.isNullOrBlank()) {
            generation = UUID.randomUUID().toString()
            if (!preferences.edit()
                    .putString(PREF_GENERATION, generation)
                    .putInt(PREF_MONTHLY_ANCHOR_DAY, now.get(Calendar.DAY_OF_MONTH))
                    .commit()
            ) {
                return@synchronized
            }
        }
        val anchorDay = preferences.getInt(
            PREF_MONTHLY_ANCHOR_DAY,
            now.get(Calendar.DAY_OF_MONTH),
        )

        val current = runCatching {
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
        }.getOrDefault(emptyList())
        val expectedGenerationTag = generationTag(generation)
        val expectedCadenceTag = cadenceTag(cadence!!)
        val currentIds = current.map { it.id }.toSet()
        val hasCurrent = current.any { info ->
            isUnfinished(info) &&
                info.tags.contains(expectedGenerationTag) &&
                info.tags.contains(expectedCadenceTag)
        }

        if (hasCurrent) {
            // Retire timestamp-named legacy requests without touching the
            // current stable chain, which may include a running occurrence.
            runCatching {
                workManager.getWorkInfosByTag(TAG).get()
                    .filter { info -> isUnfinished(info) && info.id !in currentIds }
                    .forEach { info -> workManager.cancelWorkById(info.id) }
            }
            return@synchronized
        }

        // Missing, finished, or stale-generation work is repaired as one new
        // stable chain. REPLACE is used here only because there is no current
        // occurrence to preserve.
        workManager.cancelAllWorkByTag(TAG)
        enqueueNextLocked(
            context = appContext,
            workManager = workManager,
            generation = generation,
            cadence = cadence,
            monthlyAnchorDay = anchorDay,
            from = now,
            append = false,
            successor = false,
        )?.let { handle -> observeAcceptance(appContext, handle) }
    }

    /**
     * Publishes exactly one successor after a successful occurrence. The
     * current running request is appended rather than replaced, so it can
     * finish normally. Generation/cadence are revalidated before any enqueue.
     */
    suspend fun scheduleSuccessor(
        context: Context,
        generation: String?,
        cadence: String?,
        monthlyAnchorDay: Int,
        completedOccurrenceAt: Long? = null,
    ): Boolean {
        val appContext = context.applicationContext
        val handle = synchronized(lock) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
            val currentGeneration = preferences.getString(PREF_GENERATION, null)
            val currentCadence = preferences.getString(PREF_CADENCE, null)
            if (generation.isNullOrBlank() || generation != currentGeneration || cadence != currentCadence) {
                return@synchronized null
            }
            if (!CleanupSchedulePolicy.isEnabled(currentCadence)) {
                return@synchronized null
            }

            val from = currentCalendar().apply {
                if (completedOccurrenceAt != null && completedOccurrenceAt > 0L) {
                    timeInMillis = completedOccurrenceAt
                }
            }
            val next = CleanupSchedulePolicy.nextOccurrence(
                now = from,
                cadence = currentCadence!!,
                monthlyAnchorDay = monthlyAnchorDay,
            )
            val occurrenceTag = occurrenceTag(generation, next.timeInMillis)
            val workManager = workManager(appContext)
            val current = workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
            if (current.any { info -> info.tags.contains(occurrenceTag) }) {
                return@synchronized EnqueueHandle(
                    request = OneTimeWorkRequestBuilder<CleanUpLeftoverDownloads>().build(),
                    operation = null,
                    alreadyPresent = true,
                    generation = generation,
                    cadence = currentCadence,
                    monthlyAnchorDay = monthlyAnchorDay,
                    occurrenceAt = next.timeInMillis,
                )
            }

            enqueueNextLocked(
                context = appContext,
                workManager = workManager,
                generation = generation,
                cadence = currentCadence,
                monthlyAnchorDay = monthlyAnchorDay,
                from = from,
                append = true,
                successor = true,
            )
        } ?: return false

        if (handle.alreadyPresent) return true
        return awaitAcceptance(appContext, handle)
    }

    private fun enqueueNextLocked(
        context: Context,
        workManager: WorkManager,
        generation: String,
        cadence: String,
        monthlyAnchorDay: Int,
        from: Calendar,
        append: Boolean,
        successor: Boolean,
    ): EnqueueHandle? {
        val next = CleanupSchedulePolicy.nextOccurrence(from, cadence, monthlyAnchorDay)
        val occurrenceAt = next.timeInMillis
        val delayOverride = if (successor) {
            successorDelayOverrideForTesting
        } else {
            initialDelayOverrideForTesting
        }
        val delay = (delayOverride ?: (occurrenceAt - System.currentTimeMillis()))
            .coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<CleanUpLeftoverDownloads>()
            .setConstraints(Constraints.Builder().build())
            .setInputData(
                Data.Builder()
                    .putString(INPUT_GENERATION, generation)
                    .putString(INPUT_CADENCE, cadence)
                    .putInt(INPUT_MONTHLY_ANCHOR_DAY, monthlyAnchorDay)
                    .putLong(INPUT_OCCURRENCE_AT, occurrenceAt)
                    .build()
            )
            .addTag(TAG)
            .addTag(generationTag(generation))
            .addTag(cadenceTag(cadence))
            .addTag(occurrenceTag(generation, occurrenceAt))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        val policy = if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (!preferences.edit()
                .putString(PREF_PENDING_GENERATION, generation)
                .putString(PREF_PENDING_CADENCE, cadence)
                .putInt(PREF_PENDING_ANCHOR_DAY, monthlyAnchorDay)
                .putLong(PREF_PENDING_OCCURRENCE_AT, occurrenceAt)
                .commit()
        ) {
            return null
        }
        return try {
            EnqueueHandle(
                request = request,
                operation = enqueueOverrideForTesting?.invoke(WORK_NAME, policy, request)
                    ?: workManager.enqueueUniqueWork(WORK_NAME, policy, request),
                generation = generation,
                cadence = cadence,
                monthlyAnchorDay = monthlyAnchorDay,
                occurrenceAt = occurrenceAt,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun observeAcceptance(context: Context, handle: EnqueueHandle) {
        requireNotNull(handle.operation).result.addListener(
            {
                runCatching { requireNotNull(handle.operation).result.get() }
                    .onSuccess { clearDebtIfCurrent(context, handle) }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private suspend fun awaitAcceptance(context: Context, handle: EnqueueHandle): Boolean =
        try {
            requireNotNull(handle.operation).result.get(30L, TimeUnit.SECONDS)
            clearDebtIfCurrent(context, handle)
            true
        } catch (_: Exception) {
            false
        }

    private fun clearDebtIfCurrent(context: Context, handle: EnqueueHandle) {
        synchronized(lock) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (
                preferences.getString(PREF_PENDING_GENERATION, null) == handle.generation &&
                preferences.getString(PREF_PENDING_CADENCE, null) == handle.cadence &&
                preferences.getInt(PREF_PENDING_ANCHOR_DAY, -1) == handle.monthlyAnchorDay &&
                preferences.getLong(PREF_PENDING_OCCURRENCE_AT, -1L) == handle.occurrenceAt
            ) {
                preferences.edit()
                    .remove(PREF_PENDING_GENERATION)
                    .remove(PREF_PENDING_CADENCE)
                    .remove(PREF_PENDING_ANCHOR_DAY)
                    .remove(PREF_PENDING_OCCURRENCE_AT)
                    .commit()
            }
        }
    }


    private fun currentCalendar(): Calendar =
        (nowProviderForTesting?.invoke() ?: Calendar.getInstance()).clone() as Calendar

    private fun workManager(context: Context): WorkManager =
        workManagerForTesting ?: WorkManager.getInstance(context)

    private fun isUnfinished(info: WorkInfo): Boolean =
        info.state == WorkInfo.State.ENQUEUED ||
            info.state == WorkInfo.State.RUNNING ||
            info.state == WorkInfo.State.BLOCKED

    private fun generationTag(generation: String): String = "${TAG}_generation_$generation"

    private fun cadenceTag(cadence: String): String = "${TAG}_cadence_$cadence"

    private fun occurrenceTag(generation: String, occurrenceAt: Long): String =
        "${TAG}_occurrence_${generation}_$occurrenceAt"
}
