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
    private const val PREF_ACTIVE_GENERATION = "cleanup_leftover_downloads_active_generation"
    private const val PREF_ACTIVE_CADENCE = "cleanup_leftover_downloads_active_cadence"
    private const val PREF_ACTIVE_ANCHOR_DAY = "cleanup_leftover_downloads_active_anchor_day"
    private const val PREF_ACTIVE_OCCURRENCE_AT = "cleanup_leftover_downloads_active_occurrence_at"
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
    private var replayBootstrapCadence: String? = null

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
    internal var workInfoQueryOverrideForTesting: (() -> List<WorkInfo>)? = null
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
                    .remove(PREF_ACTIVE_GENERATION)
                    .remove(PREF_ACTIVE_CADENCE)
                    .remove(PREF_ACTIVE_ANCHOR_DAY)
                    .remove(PREF_ACTIVE_OCCURRENCE_AT)
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

                enqueueNextLocked(
                    workManager = workManager,
                    generation = generation,
                    cadence = normalizedCadence,
                    monthlyAnchorDay = anchorDay,
                    from = now,
                    append = false,
                    successor = false,
                    occurrenceAtOverride = initialOccurrenceAt,
                ).also { handle ->
                    handle.operation?.let { observeAcceptance(appContext, handle) }
                    ensureReplayOwnerLocked(appContext)
                }
                true
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
                        .remove(PREF_ACTIVE_GENERATION)
                        .remove(PREF_ACTIVE_CADENCE)
                        .remove(PREF_ACTIVE_ANCHOR_DAY)
                        .remove(PREF_ACTIVE_OCCURRENCE_AT)
                        .putString(PREF_PENDING_GENERATION, bootstrappedGeneration)
                        .putString(PREF_PENDING_CADENCE, cadence)
                        .putInt(PREF_PENDING_ANCHOR_DAY, bootstrappedAnchorDay)
                        .putLong(PREF_PENDING_OCCURRENCE_AT, bootstrappedOccurrenceAt)
                    if (!commitAuthority(bootstrapEditor)) {
                        // The enabled cadence is legacy durable authority, but
                        // generation/debt publication failed atomically. Keep
                        // a process-owned bootstrap recovery owner so the
                        // running process can retry the same logical
                        // bootstrap without exposing partial authority.
                        ensureBootstrapReplayOwnerLocked(appContext, cadence)
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
                val currentGeneration = requireNotNull(generation)
                val currentCadence = requireNotNull(cadence)

                val pendingDebt = readSchedulingDebt(preferences)?.takeIf { debt ->
                    debt.matchesAuthority(currentGeneration, currentCadence, anchorDay)
                }
                val activeDebt = readActiveSchedulingDebt(preferences)?.takeIf { debt ->
                    debt.matchesAuthority(currentGeneration, currentCadence, anchorDay)
                }
                if (pendingDebt != null && activeDebt != null) {
                    // A transition commit should never publish both slots. Do
                    // not guess which one owns the schedule if storage shows
                    // an impossible mixed state.
                    ensureReplayOwnerLocked(appContext, pendingDebt)
                    return@synchronized
                }
                val current = queryCurrentWork(workManager)
                if (current == null) {
                    val recoveryDebt = when {
                        pendingDebt != null -> pendingDebt
                        activeDebt != null -> successorDebtOf(activeDebt)
                        else -> nextSchedulingDebt(
                            generation = currentGeneration,
                            cadence = currentCadence,
                            monthlyAnchorDay = anchorDay,
                            from = now,
                        )
                    }
                    val published = when {
                        pendingDebt != null -> true
                        activeDebt != null -> persistSuccessorDebtLocked(
                            preferences = preferences,
                            successor = recoveryDebt,
                            completedOccurrenceAt = activeDebt.occurrenceAt,
                        )
                        else -> persistSchedulingDebtLocked(preferences, recoveryDebt)
                    }
                    if (!published) {
                        ensureReplayOwnerLocked(appContext, recoveryDebt)
                        return@synchronized
                    }
                    ensureReplayOwnerLocked(appContext, recoveryDebt)
                    return@synchronized
                }
                val expectedGenerationTag = generationTag(currentGeneration)
                val expectedCadenceTag = cadenceTag(currentCadence)
                val currentIds = current.map { it.id }.toSet()
                val unfinishedCurrent = current.filter { info ->
                    isUnfinished(info) &&
                        info.tags.contains(expectedGenerationTag) &&
                        info.tags.contains(expectedCadenceTag)
                }

                val pendingIsCurrent = pendingDebt?.let { debt ->
                    unfinishedCurrent.any { it.matchesOccurrence(debt) }
                } == true
                val activeIsCurrent = activeDebt?.let { debt ->
                    unfinishedCurrent.any { it.matchesOccurrence(debt) }
                } == true
                if (pendingIsCurrent) {
                    val currentPendingDebt = pendingDebt
                    if (!promotePendingDebtLocked(appContext, currentPendingDebt)) {
                        ensureReplayOwnerLocked(appContext, currentPendingDebt)
                    }
                    retireLegacyWork(workManager, currentIds)
                    return@synchronized
                }
                if (activeIsCurrent) {
                    // The active occurrence itself is the durable owner. It
                    // must remain recorded until that occurrence publishes
                    // its successor; acceptance is not a safe clear point.
                    // If successor recovery was already requested after a
                    // successful effect, retain that exact successor owner
                    // while the predecessor is still running.  Stopping it
                    // here would lose the only in-process handoff retry just
                    // because the predecessor has not terminalized yet.
                    val successorRecovery = successorDebtOf(activeDebt)
                        .takeIf { successor ->
                            pendingDebt == successor || replayDebt == successor
                        }
                    if (successorRecovery != null) {
                        ensureReplayOwnerLocked(appContext, successorRecovery)
                    } else {
                        stopReplayOwnerLocked()
                    }
                    retireLegacyWork(workManager, currentIds)
                    return@synchronized
                }
                if (unfinishedCurrent.isNotEmpty()) {
                    val replayDebt = pendingDebt ?: activeDebt?.let(::successorDebtOf)
                    if (replayDebt == null) {
                        // Adopt an exact occurrence tag left by an older
                        // version that did not persist the active marker. Do
                        // not infer ownership from a work item without our
                        // exact occurrence identity.
                        unfinishedCurrent.asSequence()
                            .mapNotNull {
                                it.debtFromOccurrenceTag(
                                    generation = currentGeneration,
                                    cadence = currentCadence,
                                    monthlyAnchorDay = anchorDay,
                                )
                            }
                            .maxByOrNull { it.occurrenceAt }
                            ?.let { adopted ->
                                if (commitAuthority(preferences.edit().putActiveDebt(adopted))) {
                                    stopReplayOwnerLocked()
                                } else {
                                    ensureReplayOwnerLocked(appContext, successorDebtOf(adopted))
                                }
                            }
                    }
                    replayDebt?.let { ensureReplayOwnerLocked(appContext, it) }
                    retireLegacyWork(workManager, currentIds)
                    return@synchronized
                }

                val pendingIsTerminal = pendingDebt?.let { debt ->
                    current.any { !isUnfinished(it) && it.matchesOccurrence(debt) }
                } == true
                val recoveryDebt: SchedulingDebt
                val debtPublished: Boolean
                if (activeDebt != null) {
                    recoveryDebt = successorDebtOf(activeDebt)
                    debtPublished = persistSuccessorDebtLocked(
                        preferences = preferences,
                        successor = recoveryDebt,
                        completedOccurrenceAt = activeDebt.occurrenceAt,
                    )
                } else if (pendingIsTerminal) {
                    val terminalPendingDebt = pendingDebt
                    recoveryDebt = successorDebtOf(terminalPendingDebt)
                    debtPublished = persistSuccessorDebtLocked(
                        preferences = preferences,
                        successor = recoveryDebt,
                        completedOccurrenceAt = terminalPendingDebt.occurrenceAt,
                    )
                } else if (pendingDebt != null) {
                    recoveryDebt = pendingDebt
                    debtPublished = true
                } else {
                    val observedTerminalDebt = current.asSequence()
                        .filter { !isUnfinished(it) }
                        .mapNotNull {
                            it.debtFromOccurrenceTag(
                                generation = currentGeneration,
                                cadence = currentCadence,
                                monthlyAnchorDay = anchorDay,
                            )
                        }
                        .maxByOrNull { it.occurrenceAt }
                    if (observedTerminalDebt != null) {
                        recoveryDebt = successorDebtOf(observedTerminalDebt)
                        debtPublished = persistSuccessorDebtLocked(
                            preferences = preferences,
                            successor = recoveryDebt,
                            completedOccurrenceAt = observedTerminalDebt.occurrenceAt,
                        )
                    } else {
                        recoveryDebt = nextSchedulingDebt(
                            generation = currentGeneration,
                            cadence = currentCadence,
                            monthlyAnchorDay = anchorDay,
                            from = now,
                        )
                        debtPublished = persistSchedulingDebtLocked(preferences, recoveryDebt)
                    }
                }
                if (!debtPublished) {
                    ensureReplayOwnerLocked(appContext, recoveryDebt)
                    return@synchronized
                }
                ensureReplayOwnerLocked(appContext, recoveryDebt)
                workManager.cancelAllWorkByTag(TAG)
                enqueueNextLocked(
                    workManager = workManager,
                    generation = currentGeneration,
                    cadence = currentCadence,
                    monthlyAnchorDay = anchorDay,
                    from = now,
                    append = false,
                    successor = false,
                    occurrenceAtOverride = recoveryDebt.occurrenceAt,
                ).also { handle ->
                    handle.operation?.let { observeAcceptance(appContext, handle) }
                    ensureReplayOwnerLocked(appContext, recoveryDebt)
                }
            }
        }
    }

    private fun retireLegacyWork(workManager: WorkManager, currentIds: Set<UUID>) {
        // Retire timestamp-named legacy requests without touching the current
        // stable chain, which may include a running occurrence.
        runCatching {
            workManager.getWorkInfosByTag(TAG).get()
                .filter { info -> isUnfinished(info) && info.id !in currentIds }
                .forEach { info -> workManager.cancelWorkById(info.id) }
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
            val successorDebt = SchedulingDebt(
                generation = generation,
                cadence = currentCadence,
                monthlyAnchorDay = monthlyAnchorDay,
                occurrenceAt = next.timeInMillis,
            )
            if (!persistSuccessorDebtLocked(
                    preferences = preferences,
                    successor = successorDebt,
                    completedOccurrenceAt = completedOccurrenceAt ?: 0L,
                )
            ) {
                ensureReplayOwnerLocked(appContext, successorDebt)
                return@synchronized null
            }
            ensureReplayOwnerLocked(appContext, successorDebt)

            val occurrenceTag = occurrenceTag(generation, successorDebt.occurrenceAt)
            val workManager = workManager(appContext)
            val current = queryCurrentWork(workManager) ?: return@synchronized null
            if (current.any { info -> info.tags.contains(occurrenceTag) }) {
                promotePendingDebtLocked(appContext, successorDebt)
                return@synchronized EnqueueHandle(
                    request = OneTimeWorkRequestBuilder<CleanUpLeftoverDownloads>().build(),
                    operation = null,
                    alreadyPresent = true,
                    generation = generation,
                    cadence = currentCadence,
                    monthlyAnchorDay = monthlyAnchorDay,
                    occurrenceAt = successorDebt.occurrenceAt,
                )
            }

            enqueueNextLocked(
                workManager = workManager,
                generation = generation,
                cadence = currentCadence,
                monthlyAnchorDay = monthlyAnchorDay,
                from = from,
                append = true,
                successor = true,
                occurrenceAtOverride = successorDebt.occurrenceAt,
            ).also { ensureReplayOwnerLocked(appContext, successorDebt) }
        } ?: return false

        if (handle.alreadyPresent) return true
        return awaitAcceptance(appContext, handle)
    }

    private fun enqueueNextLocked(
        workManager: WorkManager,
        generation: String,
        cadence: String,
        monthlyAnchorDay: Int,
        from: Calendar,
        append: Boolean,
        successor: Boolean,
        occurrenceAtOverride: Long? = null,
    ): EnqueueHandle {
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
            // Callers commit the generation-bound debt before reaching this
            // function. Keep returning a handle so they can retain durable
            // recovery responsibility even though this enqueue attempt failed.
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
                    .onSuccess { promoteAcceptedDebt(context, handle) }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun commitAuthority(editor: android.content.SharedPreferences.Editor): Boolean =
        authorityCommitOverrideForTesting?.invoke(editor) ?: editor.commit()

    private fun persistSchedulingDebtLocked(
        preferences: android.content.SharedPreferences,
        debt: SchedulingDebt,
    ): Boolean = commitAuthority(preferences.edit().putPendingDebt(debt))

    /**
     * Promotes an accepted occurrence into the durable active slot. The
     * predecessor remains recorded until its exact successor is published, so
     * a failed successor write can still be reconstructed after process death.
     */
    private fun promotePendingDebtLocked(
        context: Context,
        debt: SchedulingDebt,
    ): Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (readActiveSchedulingDebt(preferences) == debt) {
            stopReplayOwnerLocked()
            return true
        }
        if (readSchedulingDebt(preferences) != debt) return false
        val committed = commitAuthority(
            preferences.edit()
                .removePendingDebt()
                .putActiveDebt(debt)
        )
        if (committed) {
            stopReplayOwnerLocked()
        } else {
            // The pending tuple remains the durable recovery carrier. Do not
            // stop its owner merely because promotion failed.
            ensureReplayOwnerLocked(context, debt)
        }
        return committed
    }

    /**
     * Atomically advances the durable predecessor marker to the exact
     * successor. On a failed commit, the predecessor remains durable and can
     * be advanced again by restart reconciliation.
     */
    private fun persistSuccessorDebtLocked(
        preferences: android.content.SharedPreferences,
        successor: SchedulingDebt,
        completedOccurrenceAt: Long,
    ): Boolean {
        val predecessor = readActiveSchedulingDebt(preferences)
            ?: readSchedulingDebt(preferences)
        if (readSchedulingDebt(preferences) == successor ||
            readActiveSchedulingDebt(preferences) == successor
        ) {
            return true
        }
        if (completedOccurrenceAt > 0L && predecessor != null &&
            predecessor.occurrenceAt != completedOccurrenceAt
        ) {
            // A late predecessor callback cannot overwrite newer debt.
            return false
        }
        return commitAuthority(
            preferences.edit()
                .removePendingDebt()
                .removeActiveDebt()
                .putPendingDebt(successor)
        )
    }

    private fun nextSchedulingDebt(
        generation: String,
        cadence: String,
        monthlyAnchorDay: Int,
        from: Calendar,
    ): SchedulingDebt = SchedulingDebt(
        generation = generation,
        cadence = cadence,
        monthlyAnchorDay = monthlyAnchorDay,
        occurrenceAt = CleanupSchedulePolicy.nextOccurrence(
            now = from,
            cadence = cadence,
            monthlyAnchorDay = monthlyAnchorDay,
        ).timeInMillis,
    )

    private fun queryCurrentWork(workManager: WorkManager): List<WorkInfo>? = runCatching {
        workInfoQueryOverrideForTesting?.invoke()
            ?: workManager.getWorkInfosForUniqueWork(WORK_NAME).get()
    }.getOrNull()

    private suspend fun awaitAcceptance(context: Context, handle: EnqueueHandle): Boolean =
        try {
            requireNotNull(handle.operation).result.get(30L, TimeUnit.SECONDS)
            promoteAcceptedDebt(context, handle)
            true
        } catch (_: Exception) {
            false
        }

    private fun promoteAcceptedDebt(context: Context, handle: EnqueueHandle) {
        synchronized(lock) {
            promotePendingDebtLocked(
                context,
                SchedulingDebt(
                    generation = handle.generation,
                    cadence = handle.cadence,
                    monthlyAnchorDay = handle.monthlyAnchorDay,
                    occurrenceAt = handle.occurrenceAt,
                ),
            )
        }
    }

    /**
     * A process-local owner retries durable scheduling debt while the app is
     * alive. The persisted generation/cadence/occurrence tuple remains the
     * authority; this job is only the bounded replay mechanism.
     */
    private fun ensureReplayOwnerLocked(
        context: Context,
        fallbackDebt: SchedulingDebt? = null,
    ): Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        // An explicit fallback is used only when it is the exact persisted
        // tuple or the immediate successor of the persisted predecessor.  In
        // the latter case it must take precedence: the predecessor has
        // already completed and its failed clear/write must not make replay
        // resurrect that occurrence instead of advancing to this successor.
        val debt = fallbackDebt?.takeIf { canUseFallbackDebt(preferences, it) }
            ?: readSchedulingDebt(preferences)
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

    /**
     * Owns recovery for the legacy enabled state in which cadence is durable
     * but generation publication has not yet succeeded.  There is no
     * generation-bound debt to persist in this state, so the owner retries
     * reconciliation only while the same enabled legacy authority remains
     * current.  A successful bootstrap replaces this owner with the normal
     * exact-debt owner; disable/supersession cancels it through
     * [stopReplayOwnerLocked].
     */
    private fun ensureBootstrapReplayOwnerLocked(
        context: Context,
        cadence: String,
    ): Boolean {
        if (!CleanupSchedulePolicy.isEnabled(cadence)) {
            stopReplayOwnerLocked()
            return false
        }
        if (replayJob?.isActive == true &&
            replayDebt == null &&
            replayBootstrapCadence == cadence
        ) {
            return true
        }

        stopReplayOwnerLocked()
        replayBootstrapCadence = cadence
        replayJob = replayScope.launch {
            var backoff = replayInitialDelayOverrideForTesting ?: REPLAY_INITIAL_DELAY_MS
            val maxBackoff = replayMaxDelayOverrideForTesting ?: REPLAY_MAX_DELAY_MS
            while (currentCoroutineContext().isActive) {
                delay(backoff.coerceAtLeast(1L))
                val stillNeedsBootstrap = synchronized(lock) {
                    val preferences = PreferenceManager
                        .getDefaultSharedPreferences(context.applicationContext)
                    preferences.getString(PREF_CADENCE, null) == cadence &&
                        preferences.getString(PREF_GENERATION, null).isNullOrBlank()
                }
                if (!stillNeedsBootstrap) return@launch

                try {
                    reconcileSuspending(context.applicationContext)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the enabled legacy authority and retry.  The
                    // atomic bootstrap commit remains the only publication
                    // boundary for generation and its initial debt.
                }

                val remainsUnpublished = synchronized(lock) {
                    val preferences = PreferenceManager
                        .getDefaultSharedPreferences(context.applicationContext)
                    preferences.getString(PREF_CADENCE, null) == cadence &&
                        preferences.getString(PREF_GENERATION, null).isNullOrBlank()
                }
                if (!remainsUnpublished) return@launch
                backoff = (backoff * 2L).coerceAtMost(maxBackoff.coerceAtLeast(backoff))
            }
        }
        return true
    }

    private suspend fun replaySchedulingDebt(context: Context, expected: SchedulingDebt) {
        var backoff = replayInitialDelayOverrideForTesting ?: REPLAY_INITIAL_DELAY_MS
        val maxBackoff = replayMaxDelayOverrideForTesting ?: REPLAY_MAX_DELAY_MS
        while (currentCoroutineContext().isActive) {
            delay(backoff.coerceAtLeast(1L))
            if (!isSchedulingDebtCurrent(context, expected)) return
            if (!isSchedulingDebtPersistedOrRepaired(context, expected)) {
                backoff = (backoff * 2L).coerceAtMost(maxBackoff.coerceAtLeast(backoff))
                continue
            }
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
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val pending = readSchedulingDebt(preferences)
            val active = readActiveSchedulingDebt(preferences)
            when {
                pending == expected || active == expected -> true
                pending != null -> successorDebtOf(pending) == expected
                active != null -> successorDebtOf(active) == expected
                else -> authorityMatchesDebt(preferences, expected)
            }
        }

    private fun isSchedulingDebtPersistedOrRepaired(
        context: Context,
        expected: SchedulingDebt,
    ): Boolean = synchronized(lock) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (readSchedulingDebt(preferences) == expected ||
            readActiveSchedulingDebt(preferences) == expected
        ) {
            return@synchronized true
        }
        val predecessor = readSchedulingDebt(preferences)
            ?: readActiveSchedulingDebt(preferences)
        if (predecessor != null) {
            if (successorDebtOf(predecessor) != expected) return@synchronized false
            return@synchronized persistSuccessorDebtLocked(
                preferences = preferences,
                successor = expected,
                completedOccurrenceAt = predecessor.occurrenceAt,
            )
        }
        if (!authorityMatchesDebt(preferences, expected)) return@synchronized false
        persistSchedulingDebtLocked(preferences, expected)
    }

    private fun authorityMatchesDebt(
        preferences: android.content.SharedPreferences,
        debt: SchedulingDebt,
    ): Boolean = preferences.getString(PREF_GENERATION, null) == debt.generation &&
        preferences.getString(PREF_CADENCE, null) == debt.cadence &&
        preferences.getInt(PREF_MONTHLY_ANCHOR_DAY, -1) == debt.monthlyAnchorDay

    private fun canUseFallbackDebt(
        preferences: android.content.SharedPreferences,
        fallback: SchedulingDebt,
    ): Boolean {
        val pending = readSchedulingDebt(preferences)
        val active = readActiveSchedulingDebt(preferences)
        return when {
            pending == null && active == null -> authorityMatchesDebt(preferences, fallback)
            pending == fallback || active == fallback -> true
            pending != null -> successorDebtOf(pending) == fallback
            active != null -> successorDebtOf(active) == fallback
            else -> false
        }
    }

    private fun successorDebtOf(predecessor: SchedulingDebt): SchedulingDebt =
        nextSchedulingDebt(
            generation = predecessor.generation,
            cadence = predecessor.cadence,
            monthlyAnchorDay = predecessor.monthlyAnchorDay,
            from = currentCalendar().apply { timeInMillis = predecessor.occurrenceAt },
        )

    private fun readSchedulingDebt(preferences: android.content.SharedPreferences): SchedulingDebt? =
        readDebt(
            preferences = preferences,
            generationKey = PREF_PENDING_GENERATION,
            cadenceKey = PREF_PENDING_CADENCE,
            anchorKey = PREF_PENDING_ANCHOR_DAY,
            occurrenceKey = PREF_PENDING_OCCURRENCE_AT,
        )

    private fun readActiveSchedulingDebt(
        preferences: android.content.SharedPreferences,
    ): SchedulingDebt? = readDebt(
        preferences = preferences,
        generationKey = PREF_ACTIVE_GENERATION,
        cadenceKey = PREF_ACTIVE_CADENCE,
        anchorKey = PREF_ACTIVE_ANCHOR_DAY,
        occurrenceKey = PREF_ACTIVE_OCCURRENCE_AT,
    )

    private fun readDebt(
        preferences: android.content.SharedPreferences,
        generationKey: String,
        cadenceKey: String,
        anchorKey: String,
        occurrenceKey: String,
    ): SchedulingDebt? {
        val generation = preferences.getString(generationKey, null) ?: return null
        val cadence = preferences.getString(cadenceKey, null) ?: return null
        val anchorDay = preferences.getInt(anchorKey, -1)
        val occurrenceAt = preferences.getLong(occurrenceKey, -1L)
        if (!CleanupSchedulePolicy.isEnabled(cadence) || anchorDay < 1 || occurrenceAt <= 0L) {
            return null
        }
        return SchedulingDebt(generation, cadence, anchorDay, occurrenceAt)
    }

    private fun SchedulingDebt.matchesAuthority(
        generation: String,
        cadence: String,
        monthlyAnchorDay: Int,
    ): Boolean = this.generation == generation &&
        this.cadence == cadence &&
        this.monthlyAnchorDay == monthlyAnchorDay

    private fun WorkInfo.matchesOccurrence(debt: SchedulingDebt): Boolean =
        tags.contains(occurrenceTag(debt.generation, debt.occurrenceAt))

    private fun WorkInfo.debtFromOccurrenceTag(
        generation: String,
        cadence: String,
        monthlyAnchorDay: Int,
    ): SchedulingDebt? {
        val prefix = "${TAG}_occurrence_${generation}_"
        val occurrenceAt = tags.firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.toLongOrNull()
            ?: return null
        return SchedulingDebt(
            generation = generation,
            cadence = cadence,
            monthlyAnchorDay = monthlyAnchorDay,
            occurrenceAt = occurrenceAt,
        )
    }

    private fun android.content.SharedPreferences.Editor.putPendingDebt(
        debt: SchedulingDebt,
    ): android.content.SharedPreferences.Editor = putString(
        PREF_PENDING_GENERATION,
        debt.generation,
    ).putString(
        PREF_PENDING_CADENCE,
        debt.cadence,
    ).putInt(
        PREF_PENDING_ANCHOR_DAY,
        debt.monthlyAnchorDay,
    ).putLong(
        PREF_PENDING_OCCURRENCE_AT,
        debt.occurrenceAt,
    )

    private fun android.content.SharedPreferences.Editor.putActiveDebt(
        debt: SchedulingDebt,
    ): android.content.SharedPreferences.Editor = putString(
        PREF_ACTIVE_GENERATION,
        debt.generation,
    ).putString(
        PREF_ACTIVE_CADENCE,
        debt.cadence,
    ).putInt(
        PREF_ACTIVE_ANCHOR_DAY,
        debt.monthlyAnchorDay,
    ).putLong(
        PREF_ACTIVE_OCCURRENCE_AT,
        debt.occurrenceAt,
    )

    private fun android.content.SharedPreferences.Editor.removePendingDebt():
        android.content.SharedPreferences.Editor = remove(PREF_PENDING_GENERATION)
            .remove(PREF_PENDING_CADENCE)
            .remove(PREF_PENDING_ANCHOR_DAY)
            .remove(PREF_PENDING_OCCURRENCE_AT)

    private fun android.content.SharedPreferences.Editor.removeActiveDebt():
        android.content.SharedPreferences.Editor = remove(PREF_ACTIVE_GENERATION)
            .remove(PREF_ACTIVE_CADENCE)
            .remove(PREF_ACTIVE_ANCHOR_DAY)
            .remove(PREF_ACTIVE_OCCURRENCE_AT)

    private fun stopReplayOwnerLocked() {
        replayJob?.cancel()
        replayJob = null
        replayDebt = null
        replayBootstrapCadence = null
    }

    private fun retirePendingDebtLocked(preferences: android.content.SharedPreferences): Boolean {
        val retired = commitAuthority(
            preferences.edit()
                .removePendingDebt()
                .removeActiveDebt()
        )
        if (retired) stopReplayOwnerLocked()
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
