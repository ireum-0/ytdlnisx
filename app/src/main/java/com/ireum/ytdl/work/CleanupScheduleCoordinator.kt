package com.ireum.ytdl.work

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private const val REPLAY_INITIAL_DELAY_MS = 1_000L
    private const val REPLAY_MAX_DELAY_MS = 60_000L

    private val lock = Any()
    /**
     * Serializes durable authority transitions with the destructive cleanup
     * effect itself.  This is a coroutine mutex rather than a JVM monitor so
     * the worker may suspend while it owns the effect lease without pinning a
     * thread-affine lock.
     */
    private val destructiveEffectMutex = Mutex()
    private val replayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var replayJob: Job? = null
    private var replayDebt: SchedulingDebt? = null

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
    @Volatile
    internal var authorityCommitOverrideForTesting:
        ((android.content.SharedPreferences.Editor) -> Boolean)? = null
    @Volatile
    internal var replayInitialDelayOverrideForTesting: Long? = null
    @Volatile
    internal var replayMaxDelayOverrideForTesting: Long? = null
    @Volatile
    internal var retryBackoffDelayOverrideForTesting: Long? = null

    private data class SchedulingDebt(
        val generation: String,
        val cadence: String,
        val monthlyAnchorDay: Int,
        val occurrenceAt: Long,
    )

    private data class EnqueueHandle(
        val request: OneTimeWorkRequest,
        val operation: Operation?,
        val alreadyPresent: Boolean = false,
        val generation: String,
        val cadence: String,
        val monthlyAnchorDay: Int,
        val occurrenceAt: Long,
    )

    suspend fun configure(context: Context, cadence: String?): Boolean =
        destructiveEffectMutex.withLock {
            synchronized(lock) {
                val appContext = context.applicationContext
                val normalizedCadence = cadence?.takeIf(CleanupSchedulePolicy::isEnabled)
                val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
                val generation = UUID.randomUUID().toString()
                val now = currentCalendar()
                val anchorDay = now.get(Calendar.DAY_OF_MONTH)
                val initialOccurrenceAt = normalizedCadence?.let {
                    CleanupSchedulePolicy.nextOccurrence(
                        now = now,
                        cadence = it,
                        monthlyAnchorDay = anchorDay,
                    ).timeInMillis
                }

                // Commit the new authority before cancelling/replacing any old work.
                // A stale worker therefore observes the new generation even if
                // WorkManager cancellation is still in flight.  The effect gate
                // also prevents this commit from overtaking a cleanup effect
                // that has already acquired the current generation lease.
                val authorityEditor = preferences.edit()
                    .putString(PREF_CADENCE, normalizedCadence.orEmpty())
                    .putString(PREF_GENERATION, generation)
                    .putInt(PREF_MONTHLY_ANCHOR_DAY, anchorDay)
                    // Retire the previous generation's scheduling debt in the same
                    // durable transition as the new authority.  A later independent
                    // commit must not be able to fail after the new generation has
                    // already become authoritative.
                    .remove(PREF_PENDING_GENERATION)
                    .remove(PREF_PENDING_CADENCE)
                    .remove(PREF_PENDING_ANCHOR_DAY)
                    .remove(PREF_PENDING_OCCURRENCE_AT)
                if (normalizedCadence != null) {
                    authorityEditor
                        .putString(PREF_PENDING_GENERATION, generation)
                        .putString(PREF_PENDING_CADENCE, normalizedCadence)
                        .putInt(PREF_PENDING_ANCHOR_DAY, anchorDay)
                        .putLong(PREF_PENDING_OCCURRENCE_AT, initialOccurrenceAt!!)
                }
                val authorityCommitted = commitAuthority(authorityEditor)
                if (!authorityCommitted) return@synchronized false

                // The new generation wins before any old asynchronous owner can
                // observe or mutate scheduling debt. Stop the superseded process-local
                // replay owner before replacing/cancelling work.
                stopReplayOwnerLocked()

                val workManager = workManager(appContext)
                workManager.cancelAllWorkByTag(TAG)
                if (normalizedCadence == null) {
                    return@synchronized true
                }

                val handle = enqueueNextLocked(
                    context = appContext,
                    workManager = workManager,
                    generation = generation,
                    cadence = normalizedCadence,
                    monthlyAnchorDay = anchorDay,
                    from = now,
                    append = false,
                    successor = false,
                    occurrenceAtOverride = initialOccurrenceAt,
                    persistDebt = false,
                )?.also { handle ->
                    handle.operation?.let { observeAcceptance(appContext, handle) }
                    ensureReplayOwnerLocked(appContext)
                }
                handle != null
            }
        }

    /** Reconciles persisted cadence authority with WorkManager after restart. */
    suspend fun reconcile(context: Context) {
        reconcileSuspending(context)
    }

    private suspend fun reconcileSuspending(context: Context) {
        destructiveEffectMutex.withLock {
            synchronized(lock) {
                val appContext = context.applicationContext
                val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
                val cadence = preferences.getString(PREF_CADENCE, null)
                val workManager = workManager(appContext)

                if (!CleanupSchedulePolicy.isEnabled(cadence)) {
                    workManager.cancelAllWorkByTag(TAG)
                    retirePendingDebtLocked(preferences)
                    return@synchronized
                }

                val now = currentCalendar()
                var generation = preferences.getString(PREF_GENERATION, null)
                val anchorDay: Int
                if (generation.isNullOrBlank()) {
                    val bootstrappedGeneration = UUID.randomUUID().toString()
                    val bootstrappedAnchorDay = now.get(Calendar.DAY_OF_MONTH)
                    val bootstrappedOccurrenceAt = CleanupSchedulePolicy.nextOccurrence(
                        now = now,
                        cadence = cadence!!,
                        monthlyAnchorDay = bootstrappedAnchorDay,
                    ).timeInMillis
                    val bootstrapEditor = preferences.edit()
                        .putString(PREF_GENERATION, bootstrappedGeneration)
                        .putInt(PREF_MONTHLY_ANCHOR_DAY, bootstrappedAnchorDay)
                        .remove(PREF_PENDING_GENERATION)
                        .remove(PREF_PENDING_CADENCE)
                        .remove(PREF_PENDING_ANCHOR_DAY)
                        .remove(PREF_PENDING_OCCURRENCE_AT)
                        .putString(PREF_PENDING_GENERATION, bootstrappedGeneration)
                        .putString(PREF_PENDING_CADENCE, cadence)
                        .putInt(PREF_PENDING_ANCHOR_DAY, bootstrappedAnchorDay)
                        .putLong(PREF_PENDING_OCCURRENCE_AT, bootstrappedOccurrenceAt)
                    if (!commitAuthority(bootstrapEditor)) {
                        return@synchronized
                    }
                    stopReplayOwnerLocked()
                    generation = bootstrappedGeneration
                    anchorDay = bootstrappedAnchorDay
                } else {
                    anchorDay = preferences.getInt(
                        PREF_MONTHLY_ANCHOR_DAY,
                        now.get(Calendar.DAY_OF_MONTH),
                    )
                }

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
                    clearDebtForCurrentWorkLocked(appContext, generation, cadence, anchorDay, current)
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
                val persistedDebt = readSchedulingDebt(preferences)?.takeIf { debt ->
                    debt.generation == generation &&
                        debt.cadence == cadence &&
                        debt.monthlyAnchorDay == anchorDay
                }
                enqueueNextLocked(
                    context = appContext,
                    workManager = workManager,
                    generation = generation,
                    cadence = cadence,
                    monthlyAnchorDay = anchorDay,
                    from = now,
                    append = false,
                    successor = false,
                    occurrenceAtOverride = persistedDebt?.occurrenceAt,
                    persistDebt = persistedDebt == null,
                )?.also { handle ->
                    handle.operation?.let { observeAcceptance(appContext, handle) }
                    ensureReplayOwnerLocked(appContext)
                }
            }
        }
    }

    /**
     * Returns whether a scheduled occurrence still belongs to the current
     * durable cleanup authority. Workers must check this immediately before
     * entering cleanup effects; WorkManager cancellation is asynchronous and
     * can otherwise leave a superseded occurrence executable.
     */
    internal fun isCurrentOccurrence(
        context: Context,
        generation: String?,
        cadence: String?,
    ): Boolean = synchronized(lock) {
        isCurrentOccurrenceLocked(context, generation, cadence)
    }

    private fun isCurrentOccurrenceLocked(
        context: Context,
        generation: String?,
        cadence: String?,
    ): Boolean {
        if (generation.isNullOrBlank() || !CleanupSchedulePolicy.isEnabled(cadence)) {
            return false
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        return preferences.getString(PREF_GENERATION, null) == generation &&
            preferences.getString(PREF_CADENCE, null) == cadence
    }

    internal sealed interface DestructiveEffectResult<out T> {
        data class Completed<T>(val value: T) : DestructiveEffectResult<T>
        data object Stale : DestructiveEffectResult<Nothing>
    }

    /**
     * Admits and executes one occurrence's destructive cleanup as one
     * generation-owned effect.  Configuration/reconciliation acquire the
     * same mutex before committing new or disabled authority, so a commit
     * cannot occur between this admission and the effect body.
     */
    internal suspend fun <T> withCurrentDestructiveEffect(
        context: Context,
        generation: String?,
        cadence: String?,
        effect: suspend () -> T,
    ): DestructiveEffectResult<T> = destructiveEffectMutex.withLock {
        if (!isCurrentOccurrenceLocked(context, generation, cadence)) {
            DestructiveEffectResult.Stale
        } else {
            DestructiveEffectResult.Completed(effect())
        }
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
                ).also { ensureReplayOwnerLocked(appContext) }
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
            )?.also { ensureReplayOwnerLocked(appContext) }
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
        occurrenceAtOverride: Long? = null,
        persistDebt: Boolean = true,
    ): EnqueueHandle? {
        val next = CleanupSchedulePolicy.nextOccurrence(from, cadence, monthlyAnchorDay)
        val occurrenceAt = occurrenceAtOverride ?: next.timeInMillis
        val delayOverride = if (successor) {
            successorDelayOverrideForTesting
        } else {
            initialDelayOverrideForTesting
        }
        val delay = (delayOverride ?: (occurrenceAt - System.currentTimeMillis()))
            .coerceAtLeast(0L)
        val requestBuilder = OneTimeWorkRequestBuilder<CleanUpLeftoverDownloads>()
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
        retryBackoffDelayOverrideForTesting?.let { retryBackoffDelay ->
            requestBuilder.setBackoffCriteria(
                BackoffPolicy.LINEAR,
                retryBackoffDelay.coerceAtLeast(1L),
                TimeUnit.MILLISECONDS,
            )
        }
        val request = requestBuilder.build()

        val policy = if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (persistDebt && !preferences.edit()
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
            // The generation-bound debt was committed above.  Keep returning
            // a handle so callers can report durable recovery responsibility
            // even though this enqueue attempt itself failed.
            EnqueueHandle(
                request = request,
                operation = null,
                generation = generation,
                cadence = cadence,
                monthlyAnchorDay = monthlyAnchorDay,
                occurrenceAt = occurrenceAt,
            )
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

    private fun commitAuthority(editor: android.content.SharedPreferences.Editor): Boolean =
        authorityCommitOverrideForTesting?.invoke(editor) ?: editor.commit()

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
                stopReplayOwnerLocked()
            }
        }
    }

    /**
     * A process-local owner retries durable scheduling debt while the app is
     * alive. The persisted generation/cadence/occurrence tuple remains the
     * authority; this job is only the bounded replay mechanism.
     */
    private fun ensureReplayOwnerLocked(context: Context): Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val debt = readSchedulingDebt(preferences)
        if (debt == null) {
            stopReplayOwnerLocked()
            return true
        }
        if (replayJob?.isActive == true && replayDebt == debt) return true
        stopReplayOwnerLocked()
        replayDebt = debt
        replayJob = replayScope.launch {
            replaySchedulingDebt(context.applicationContext, debt)
        }
        return true
    }

    private suspend fun replaySchedulingDebt(context: Context, expected: SchedulingDebt) {
        var backoff = replayInitialDelayOverrideForTesting ?: REPLAY_INITIAL_DELAY_MS
        val maxBackoff = replayMaxDelayOverrideForTesting ?: REPLAY_MAX_DELAY_MS
        while (currentCoroutineContext().isActive) {
            delay(backoff.coerceAtLeast(1L))
            if (!isSchedulingDebtCurrent(context, expected)) return
            try {
                // Reconciliation uses the same durable generation and unique
                // WorkManager identity as normal startup recovery.
                reconcileSuspending(context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the debt and continue with bounded backoff.
            }
            if (!isSchedulingDebtCurrent(context, expected)) return
            backoff = (backoff * 2L).coerceAtMost(maxBackoff.coerceAtLeast(backoff))
        }
    }

    private fun isSchedulingDebtCurrent(context: Context, expected: SchedulingDebt): Boolean =
        synchronized(lock) {
            readSchedulingDebt(
                PreferenceManager.getDefaultSharedPreferences(context)
            ) == expected
        }

    private fun readSchedulingDebt(preferences: android.content.SharedPreferences): SchedulingDebt? {
        val generation = preferences.getString(PREF_PENDING_GENERATION, null)
            ?: return null
        val cadence = preferences.getString(PREF_PENDING_CADENCE, null)
            ?: return null
        val anchorDay = preferences.getInt(PREF_PENDING_ANCHOR_DAY, -1)
        val occurrenceAt = preferences.getLong(PREF_PENDING_OCCURRENCE_AT, -1L)
        if (!CleanupSchedulePolicy.isEnabled(cadence) || anchorDay < 1 || occurrenceAt <= 0L) {
            return null
        }
        return SchedulingDebt(generation, cadence, anchorDay, occurrenceAt)
    }

    private fun clearDebtForCurrentWorkLocked(
        context: Context,
        generation: String,
        cadence: String,
        monthlyAnchorDay: Int,
        current: List<WorkInfo>,
    ) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val debt = readSchedulingDebt(preferences) ?: return
        if (
            debt.generation == generation &&
            debt.cadence == cadence &&
            debt.monthlyAnchorDay == monthlyAnchorDay &&
            current.any { it.tags.contains(occurrenceTag(generation, debt.occurrenceAt)) }
        ) {
            preferences.edit()
                .remove(PREF_PENDING_GENERATION)
                .remove(PREF_PENDING_CADENCE)
                .remove(PREF_PENDING_ANCHOR_DAY)
                .remove(PREF_PENDING_OCCURRENCE_AT)
                .commit()
            stopReplayOwnerLocked()
        }
    }

    private fun stopReplayOwnerLocked() {
        replayJob?.cancel()
        replayJob = null
        replayDebt = null
    }

    private fun retirePendingDebtLocked(preferences: android.content.SharedPreferences): Boolean {
        val retired = preferences.edit()
            .remove(PREF_PENDING_GENERATION)
            .remove(PREF_PENDING_CADENCE)
            .remove(PREF_PENDING_ANCHOR_DAY)
            .remove(PREF_PENDING_OCCURRENCE_AT)
            .commit()
        stopReplayOwnerLocked()
        return retired
    }

    internal fun resetReplayOwnerForTesting() = synchronized(lock) {
        stopReplayOwnerLocked()
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
