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
import com.google.gson.Gson
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
    private const val PREF_PENDING_EFFECT_PHASE = "cleanup_leftover_downloads_pending_effect_phase"
    private const val PREF_ACTIVE_EFFECT_PHASE = "cleanup_leftover_downloads_active_effect_phase"
    private const val PREF_EFFECT_JOURNAL = "cleanup_leftover_downloads_effect_journal"
    private const val EFFECT_PHASE_ELIGIBLE = "eligible"
    private const val EFFECT_PHASE_IN_PROGRESS = "in_progress"
    private const val EFFECT_PHASE_CONSUMED = "consumed"
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
    private val gson = Gson()
    private var replayJob: Job? = null
    private var replayDebt: SchedulingDebt? = null
    private var replayBootstrapCadence: String? = null
    private var replayBootstrapIntent: BootstrapRecoveryIntent? = null

    /**
     * SharedPreferences.commit() publishes an editor to the in-process map
     * before it reports whether the disk write succeeded.  Once a critical
     * commit reports failure, this fence makes all coordinator reads use the
     * last known durable snapshot instead of that memory-only map.  A later
     * successful critical commit clears the fence; process restart naturally
     * discards it and reloads the disk state.
     */
    @Volatile
    private var criticalDurabilityFence: CriticalPreferencesSnapshot? = null

    private data class CriticalPreferencesSnapshot(
        val values: Map<String, Any?>,
    ) {
        fun string(key: String): String? = values[key] as? String
        fun int(key: String, default: Int): Int = (values[key] as? Int) ?: default
        fun long(key: String, default: Long): Long = (values[key] as? Long) ?: default
    }

    private data class BootstrapRecoveryIntent(
        val cadence: String,
        val generation: String,
        val monthlyAnchorDay: Int,
        val occurrenceAt: Long,
    )

    private val criticalPreferenceKeys = listOf(
        PREF_CADENCE,
        PREF_GENERATION,
        PREF_MONTHLY_ANCHOR_DAY,
        PREF_PENDING_GENERATION,
        PREF_PENDING_CADENCE,
        PREF_PENDING_ANCHOR_DAY,
        PREF_PENDING_OCCURRENCE_AT,
        PREF_ACTIVE_GENERATION,
        PREF_ACTIVE_CADENCE,
        PREF_ACTIVE_ANCHOR_DAY,
        PREF_ACTIVE_OCCURRENCE_AT,
        PREF_PENDING_EFFECT_PHASE,
        PREF_ACTIVE_EFFECT_PHASE,
        PREF_EFFECT_JOURNAL,
    )

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
    internal var effectPhaseCommitOverrideForTesting:
        ((android.content.SharedPreferences.Editor) -> Boolean)? = null
    /**
     * Models Android's commit(false) behavior for production-wiring tests:
     * the editor is applied to the current-process map even though the
     * durability result remains failure.  The coordinator must fence those
     * values until a later successful commit or process restart.
     */
    @Volatile
    internal var commitFailureAppliesMemoryForTesting: Boolean = false
    @Volatile
    internal var replayInitialDelayOverrideForTesting: Long? = null
    @Volatile
    internal var replayMaxDelayOverrideForTesting: Long? = null
    @Volatile
    internal var retryBackoffDelayOverrideForTesting: Long? = null

    internal data class SchedulingDebt(
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

    private enum class EffectOccurrenceSlot {
        PENDING,
        ACTIVE,
    }

    private enum class EffectPhase {
        ELIGIBLE,
        IN_PROGRESS,
        CONSUMED,
        UNKNOWN,
    }

    private data class OwnedEffectOccurrence(
        val debt: SchedulingDebt,
        val slot: EffectOccurrenceSlot,
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
                    .remove(PREF_PENDING_EFFECT_PHASE)
                    .remove(PREF_ACTIVE_GENERATION)
                    .remove(PREF_ACTIVE_CADENCE)
                    .remove(PREF_ACTIVE_ANCHOR_DAY)
                    .remove(PREF_ACTIVE_OCCURRENCE_AT)
                    .remove(PREF_ACTIVE_EFFECT_PHASE)
                    .remove(PREF_EFFECT_JOURNAL)
                if (normalizedCadence != null) {
                    authorityEditor
                        .putString(PREF_PENDING_GENERATION, generation)
                        .putString(PREF_PENDING_CADENCE, normalizedCadence)
                        .putInt(PREF_PENDING_ANCHOR_DAY, anchorDay)
                        .putLong(PREF_PENDING_OCCURRENCE_AT, initialOccurrenceAt!!)
                        .putString(PREF_PENDING_EFFECT_PHASE, EFFECT_PHASE_ELIGIBLE)
                }
                val authorityCommitted = commitAuthority(preferences, authorityEditor)
                if (!authorityCommitted) return@synchronized false

                // The new generation wins before any old asynchronous owner can
                // observe or mutate scheduling debt. Stop the superseded process-local
                // replay owner before replacing/cancelling work.
                replayBootstrapIntent = null
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
                val cadence = criticalString(preferences, PREF_CADENCE)
                val workManager = workManager(appContext)

                if (!CleanupSchedulePolicy.isEnabled(cadence)) {
                    workManager.cancelAllWorkByTag(TAG)
                    retirePendingDebtLocked(preferences)
                    return@synchronized
                }

                val now = currentCalendar()
                var generation = criticalString(preferences, PREF_GENERATION)
                val anchorDay: Int
                if (generation.isNullOrBlank()) {
                    val enabledCadence = requireNotNull(cadence)
                    val bootstrapIntent = replayBootstrapIntent?.takeIf {
                        it.cadence == enabledCadence
                    } ?: BootstrapRecoveryIntent(
                        cadence = enabledCadence,
                        generation = UUID.randomUUID().toString(),
                        monthlyAnchorDay = now.get(Calendar.DAY_OF_MONTH),
                        occurrenceAt = CleanupSchedulePolicy.nextOccurrence(
                            now = now,
                            cadence = enabledCadence,
                            monthlyAnchorDay = now.get(Calendar.DAY_OF_MONTH),
                        ).timeInMillis,
                    )
                    val bootstrappedGeneration = bootstrapIntent.generation
                    val bootstrappedAnchorDay = bootstrapIntent.monthlyAnchorDay
                    val bootstrappedOccurrenceAt = bootstrapIntent.occurrenceAt
                    val bootstrapEditor = preferences.edit()
                        .putString(PREF_GENERATION, bootstrappedGeneration)
                        .putInt(PREF_MONTHLY_ANCHOR_DAY, bootstrappedAnchorDay)
                        .remove(PREF_PENDING_GENERATION)
                        .remove(PREF_PENDING_CADENCE)
                        .remove(PREF_PENDING_ANCHOR_DAY)
                        .remove(PREF_PENDING_OCCURRENCE_AT)
                        .remove(PREF_PENDING_EFFECT_PHASE)
                        .remove(PREF_ACTIVE_GENERATION)
                        .remove(PREF_ACTIVE_CADENCE)
                        .remove(PREF_ACTIVE_ANCHOR_DAY)
                        .remove(PREF_ACTIVE_OCCURRENCE_AT)
                        .remove(PREF_ACTIVE_EFFECT_PHASE)
                        .remove(PREF_EFFECT_JOURNAL)
                        .putString(PREF_PENDING_GENERATION, bootstrappedGeneration)
                        .putString(PREF_PENDING_CADENCE, enabledCadence)
                        .putInt(PREF_PENDING_ANCHOR_DAY, bootstrappedAnchorDay)
                        .putLong(PREF_PENDING_OCCURRENCE_AT, bootstrappedOccurrenceAt)
                        .putString(PREF_PENDING_EFFECT_PHASE, EFFECT_PHASE_ELIGIBLE)
                    if (!commitAuthority(preferences, bootstrapEditor)) {
                        // The enabled cadence is legacy durable authority, but
                        // generation/debt publication failed atomically. Keep
                        // a process-owned bootstrap recovery owner so the
                        // running process can retry the same logical
                        // bootstrap without exposing partial authority.
                        replayBootstrapIntent = bootstrapIntent
                        ensureBootstrapReplayOwnerLocked(
                            context = appContext,
                            cadence = enabledCadence,
                            intent = bootstrapIntent,
                        )
                        return@synchronized
                    }
                    replayBootstrapIntent = null
                    stopReplayOwnerLocked()
                    generation = bootstrappedGeneration
                    anchorDay = bootstrappedAnchorDay
                } else {
                    anchorDay = criticalInt(
                        preferences = preferences,
                        key = PREF_MONTHLY_ANCHOR_DAY,
                        default = now.get(Calendar.DAY_OF_MONTH),
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
                        activeDebt != null && isEffectCompleteForOccurrenceLocked(
                            preferences,
                            activeDebt,
                        ) -> successorDebtOf(activeDebt)
                        activeDebt != null -> activeDebt
                        else -> nextSchedulingDebt(
                            generation = currentGeneration,
                            cadence = currentCadence,
                            monthlyAnchorDay = anchorDay,
                            from = now,
                        )
                    }
                    val published = when {
                        pendingDebt != null -> true
                        activeDebt != null && recoveryDebt != activeDebt -> persistSuccessorDebtLocked(
                            preferences = preferences,
                            successor = recoveryDebt,
                            completedOccurrenceAt = activeDebt.occurrenceAt,
                        )
                        activeDebt != null -> true
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
                    } else if (!isEffectCompleteForOccurrenceLocked(
                            preferences,
                            currentPendingDebt,
                        ) && readEffectPhase(
                            preferences,
                            EffectOccurrenceSlot.ACTIVE,
                        ) != EffectPhase.ELIGIBLE
                    ) {
                        // An admitted but unfinished effect must retain the
                        // exact predecessor as its recovery owner.  Do not
                        // advance to its successor until the frozen journal
                        // is complete.
                        ensureReplayOwnerLocked(appContext, currentPendingDebt)
                    } else if (
                        isEffectCompleteForOccurrenceLocked(preferences, currentPendingDebt)
                    ) {
                        // A process may restart after this exact occurrence
                        // has completed its frozen effect but before the
                        // worker's terminal result is durable. Rebuild the
                        // immediate successor owner without re-running it.
                        ensureReplayOwnerLocked(
                            appContext,
                            successorDebtOf(currentPendingDebt),
                        )
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
                    val effectComplete = isEffectCompleteForOccurrenceLocked(
                        preferences,
                        activeDebt,
                    )
                    if (!effectComplete && readEffectPhase(
                            preferences,
                            EffectOccurrenceSlot.ACTIVE,
                        ) != EffectPhase.ELIGIBLE
                    ) {
                        ensureReplayOwnerLocked(appContext, activeDebt)
                    } else if (effectComplete) {
                        val successorRecovery = successorDebtOf(activeDebt)
                            .takeIf { successor ->
                                pendingDebt == successor || replayDebt == successor
                            }
                        ensureReplayOwnerLocked(
                            appContext,
                            successorRecovery ?: successorDebtOf(activeDebt),
                        )
                    } else {
                        stopReplayOwnerLocked()
                    }
                    retireLegacyWork(workManager, currentIds)
                    return@synchronized
                }
                if (unfinishedCurrent.isNotEmpty()) {
                    val replayDebt = pendingDebt ?: activeDebt?.let { active ->
                        if (isEffectCompleteForOccurrenceLocked(preferences, active)) {
                            successorDebtOf(active)
                        } else {
                            active
                        }
                    }
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
                                if (commitAuthority(preferences, preferences.edit().putActiveDebt(adopted))) {
                                    stopReplayOwnerLocked()
                                } else {
                                    // Without the active marker there is no
                                    // durable effect phase proving that this
                                    // exact legacy occurrence was consumed.
                                    // Keep replay responsible for the adopted
                                    // occurrence itself; publishing its
                                    // successor would silently skip unknown
                                    // destructive work.
                                    ensureReplayOwnerLocked(appContext, adopted)
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
                    if (isEffectCompleteForOccurrenceLocked(preferences, activeDebt)) {
                        recoveryDebt = successorDebtOf(activeDebt)
                        debtPublished = persistSuccessorDebtLocked(
                            preferences = preferences,
                            successor = recoveryDebt,
                            completedOccurrenceAt = activeDebt.occurrenceAt,
                        )
                    } else {
                        // A terminal/restarted predecessor whose exact
                        // journal is not complete still owns D1. Requeue the
                        // same occurrence so recovery can resume its frozen
                        // suffix; never publish D2 over unfinished work.
                        recoveryDebt = activeDebt
                        debtPublished = true
                    }
                } else if (pendingIsTerminal) {
                    val terminalPendingDebt = pendingDebt
                    if (isEffectCompleteForOccurrenceLocked(preferences, terminalPendingDebt)) {
                        recoveryDebt = successorDebtOf(terminalPendingDebt)
                        debtPublished = persistSuccessorDebtLocked(
                            preferences = preferences,
                            successor = recoveryDebt,
                            completedOccurrenceAt = terminalPendingDebt.occurrenceAt,
                        )
                    } else {
                        recoveryDebt = terminalPendingDebt
                        debtPublished = true
                    }
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
        return criticalString(preferences, PREF_GENERATION) == generation &&
            criticalString(preferences, PREF_CADENCE) == cadence
    }

    internal sealed interface DestructiveEffectResult<out T> {
        data class Completed<T>(val value: T) : DestructiveEffectResult<T>
        data class AlreadyConsumed(
            val recoveryRequired: Boolean,
        ) : DestructiveEffectResult<Nothing>

        data object PhaseUnavailable : DestructiveEffectResult<Nothing>
        data object Stale : DestructiveEffectResult<Nothing>
    }

    /**
     * The effect may already have started, but its exact target/progress
     * journal could not be durably advanced.  Re-running a live query would
     * therefore be unsafe; the worker must retain/recover the exact
     * occurrence without widening its destructive responsibility.
     */
    internal class EffectPhaseRecoveryRequired(cause: Exception) :
        Exception("Cleanup effect phase could not be durably recovered", cause)

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
        monthlyAnchorDay: Int,
        occurrenceAt: Long?,
        prepare: suspend () -> CleanupEffectJournal,
        effect: suspend (CleanupEffectJournal) -> T,
    ): DestructiveEffectResult<T> = destructiveEffectMutex.withLock {
        if (!isCurrentOccurrenceLocked(context, generation, cadence)) {
            DestructiveEffectResult.Stale
        } else {
            val ownedOccurrence = currentEffectOccurrence(
                context = context,
                generation = requireNotNull(generation),
                cadence = requireNotNull(cadence),
                monthlyAnchorDay = monthlyAnchorDay,
                occurrenceAt = occurrenceAt,
            )
            if (ownedOccurrence == null) {
                // Exact occurrence ownership is required for destructive
                // admission.  Generation/cadence alone cannot distinguish a
                // stale re-entry from the current occurrence.
                DestructiveEffectResult.Stale
            } else {
                when (val phase = readEffectPhase(context, ownedOccurrence.slot)) {
                    EffectPhase.CONSUMED -> {
                        val rawJournal = readEffectJournalRaw(context)
                        val journal = readEffectJournal(context)
                        if (rawJournal != null &&
                            (journal == null ||
                                !journal.matches(ownedOccurrence.debt) ||
                                !journal.isStructurallyValid() ||
                                !journal.isComplete)
                        ) {
                            ensureReplayOwnerLocked(context, ownedOccurrence.debt)
                            DestructiveEffectResult.PhaseUnavailable
                        } else {
                            DestructiveEffectResult.AlreadyConsumed(
                                recoveryRequired = false,
                            )
                        }
                    }
                    EffectPhase.UNKNOWN -> {
                        ensureReplayOwnerLocked(context, ownedOccurrence.debt)
                        DestructiveEffectResult.PhaseUnavailable
                    }
                    EffectPhase.ELIGIBLE,
                    EffectPhase.IN_PROGRESS -> {
                        val existingJournal = readEffectJournal(context)
                        val journal = when {
                            existingJournal != null -> existingJournal.takeIf {
                                it.matches(ownedOccurrence.debt) && it.isStructurallyValid()
                            }
                            phase == EffectPhase.IN_PROGRESS -> null
                            else -> try {
                                prepare()
                            } catch (failure: Exception) {
                                ensureReplayOwnerLocked(context, ownedOccurrence.debt)
                                throw failure
                            }
                        }
                        if (journal == null ||
                            !journal.matches(ownedOccurrence.debt) ||
                            !journal.isStructurallyValid()
                        ) {
                            ensureReplayOwnerLocked(context, ownedOccurrence.debt)
                            DestructiveEffectResult.PhaseUnavailable
                        } else if (phase == EffectPhase.IN_PROGRESS && journal.isComplete) {
                            if (commitEffectPhaseForOccurrence(
                                    context = context,
                                    generation = generation,
                                    cadence = cadence,
                                    monthlyAnchorDay = monthlyAnchorDay,
                                    occurrenceAt = occurrenceAt,
                                    phase = EffectPhase.CONSUMED,
                                )
                            ) {
                                DestructiveEffectResult.AlreadyConsumed(
                                    recoveryRequired = false,
                                )
                            } else {
                                ensureReplayOwnerLocked(context, ownedOccurrence.debt)
                                DestructiveEffectResult.AlreadyConsumed(
                                    recoveryRequired = true,
                                )
                            }
                        } else {
                            val markedInProgress = if (phase == EffectPhase.IN_PROGRESS) {
                                true
                            } else {
                                commitEffectPhaseForOccurrence(
                                    context = context,
                                    generation = generation,
                                    cadence = cadence,
                                    monthlyAnchorDay = monthlyAnchorDay,
                                    occurrenceAt = occurrenceAt,
                                    phase = EffectPhase.IN_PROGRESS,
                                    journal = journal,
                                )
                            }
                            if (!markedInProgress) {
                                ensureReplayOwnerLocked(context, ownedOccurrence.debt)
                                DestructiveEffectResult.PhaseUnavailable
                            } else {
                                executeJournaledEffect(
                                    context = context,
                                    ownedOccurrence = ownedOccurrence,
                                    journal = journal,
                                    effect = effect,
                                )
                            }
                        }
                    }
                }
            }
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
            val currentGeneration = criticalString(preferences, PREF_GENERATION)
            val currentCadence = criticalString(preferences, PREF_CADENCE)
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

    private fun commitAuthority(
        preferences: android.content.SharedPreferences,
        editor: android.content.SharedPreferences.Editor,
    ): Boolean = commitCritical(preferences, editor) { authorityCommitOverrideForTesting?.invoke(it) }

    private fun commitEffectPhase(
        preferences: android.content.SharedPreferences,
        editor: android.content.SharedPreferences.Editor,
    ): Boolean = commitCritical(preferences, editor) { effectPhaseCommitOverrideForTesting?.invoke(it) }

    /**
     * SharedPreferences has no public disk-read API.  A false commit result
     * is therefore treated as an unconfirmed critical transition: the
     * current process must use the snapshot taken before the attempted
     * mutation until a later critical commit succeeds.  This is intentionally
     * stricter than assuming that the same SharedPreferences instance is a
     * restart-safe observation.
     */
    private fun commitCritical(
        preferences: android.content.SharedPreferences,
        editor: android.content.SharedPreferences.Editor,
        override: (android.content.SharedPreferences.Editor) -> Boolean?,
    ): Boolean {
        val durableBefore = criticalSnapshot(preferences) ?: return false
        val committed = try {
            override(editor) ?: editor.commit()
        } catch (failure: Exception) {
            if (criticalDurabilityFence == null) {
                criticalDurabilityFence = durableBefore
            }
            throw failure
        }
        if (!committed && commitFailureAppliesMemoryForTesting) {
            // Test-only model of Android's memory-visible commit(false)
            // behavior.  The fence below remains the authority boundary.
            editor.apply()
        }
        if (committed) {
            criticalDurabilityFence = null
        } else if (criticalDurabilityFence == null) {
            criticalDurabilityFence = durableBefore
        }
        return committed
    }

    private fun criticalSnapshot(
        preferences: android.content.SharedPreferences,
    ): CriticalPreferencesSnapshot? = criticalDurabilityFence ?: runCatching {
        val all = preferences.all
        CriticalPreferencesSnapshot(
            values = criticalPreferenceKeys.associateWith { key -> all[key] },
        )
    }.getOrNull()

    private fun criticalString(
        preferences: android.content.SharedPreferences,
        key: String,
    ): String? {
        val fence = criticalDurabilityFence
        return if (fence != null) fence.string(key) else preferences.getString(key, null)
    }

    private fun criticalInt(
        preferences: android.content.SharedPreferences,
        key: String,
        default: Int,
    ): Int {
        val fence = criticalDurabilityFence
        return if (fence != null) fence.int(key, default) else preferences.getInt(key, default)
    }

    private fun criticalLong(
        preferences: android.content.SharedPreferences,
        key: String,
        default: Long,
    ): Long {
        val fence = criticalDurabilityFence
        return if (fence != null) fence.long(key, default) else preferences.getLong(key, default)
    }

    private fun persistSchedulingDebtLocked(
        preferences: android.content.SharedPreferences,
        debt: SchedulingDebt,
    ): Boolean = commitAuthority(preferences, preferences.edit().putPendingDebt(debt))

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
            retainRecoveryOwnerAfterPromotionLocked(context, debt)
            return true
        }
        if (readSchedulingDebt(preferences) != debt) return false
        val pendingEffectPhase = readEffectPhase(preferences, EffectOccurrenceSlot.PENDING)
        val committed = commitAuthority(
            preferences = preferences,
            editor = preferences.edit()
                .removePendingDebt()
                .putActiveDebt(debt, pendingEffectPhase)
        )
        if (committed) {
            retainRecoveryOwnerAfterPromotionLocked(context, debt)
        } else {
            // The pending tuple remains the durable recovery carrier. Do not
            // stop its owner merely because promotion failed.
            ensureReplayOwnerLocked(context, debt)
        }
        return committed
    }

    /**
     * A pending-to-active promotion may retire the older enqueue replay owner,
     * but it must not retire the only owner of an admitted/incomplete effect.
     * The active slot is the durable occurrence identity after promotion.
     */
    private fun retainRecoveryOwnerAfterPromotionLocked(
        context: Context,
        debt: SchedulingDebt,
    ) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        when {
            isEffectCompleteForOccurrenceLocked(preferences, debt) ->
                ensureReplayOwnerLocked(context, successorDebtOf(debt))
            readEffectPhase(preferences, EffectOccurrenceSlot.ACTIVE) == EffectPhase.ELIGIBLE ->
                stopReplayOwnerLocked()
            else -> ensureReplayOwnerLocked(context, debt)
        }
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
        if (completedOccurrenceAt > 0L && predecessor != null &&
            predecessor.occurrenceAt != completedOccurrenceAt
        ) {
            // A late predecessor callback cannot overwrite newer debt.
            return false
        }
        if (predecessor != null &&
            completedOccurrenceAt == predecessor.occurrenceAt &&
            !isEffectCompleteForOccurrenceLocked(preferences, predecessor)
        ) {
            // A successor must never supersede an occurrence whose exact
            // destructive target journal still has unfinished responsibility.
            return false
        }
        if (readSchedulingDebt(preferences) == successor ||
            readActiveSchedulingDebt(preferences) == successor
        ) {
            return true
        }
        return commitAuthority(
            preferences = preferences,
            editor = preferences.edit()
                .removePendingDebt()
                .removeActiveDebt()
                .remove(PREF_EFFECT_JOURNAL)
                .putPendingDebt(successor)
        )
    }

    private fun isEffectCompleteForOccurrenceLocked(
        preferences: android.content.SharedPreferences,
        debt: SchedulingDebt,
    ): Boolean {
        val pending = readSchedulingDebt(preferences)
        val active = readActiveSchedulingDebt(preferences)
        val slot = when {
            pending == debt && active == null -> EffectOccurrenceSlot.PENDING
            active == debt && pending == null -> EffectOccurrenceSlot.ACTIVE
            else -> return false
        }
        return when (readEffectPhase(preferences, slot)) {
            EffectPhase.CONSUMED -> {
                val raw = readEffectJournalRaw(preferences)
                val journal = readEffectJournal(preferences)
                raw == null || (
                    journal != null &&
                        journal.matches(debt) &&
                        journal.isStructurallyValid() &&
                        journal.isComplete
                    )
            }
            EffectPhase.IN_PROGRESS -> {
                val journal = readEffectJournal(preferences)
                journal != null &&
                    journal.matches(debt) &&
                    journal.isStructurallyValid() &&
                    journal.isComplete
            }
            EffectPhase.ELIGIBLE,
            EffectPhase.UNKNOWN -> false
        }
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
        intent: BootstrapRecoveryIntent? = null,
    ): Boolean {
        if (!CleanupSchedulePolicy.isEnabled(cadence)) {
            stopReplayOwnerLocked()
            return false
        }
        if (replayJob?.isActive == true &&
            replayDebt == null &&
            replayBootstrapCadence == cadence &&
            (intent == null || replayBootstrapIntent == intent)
        ) {
            return true
        }

        stopReplayOwnerLocked()
        replayBootstrapCadence = cadence
        replayBootstrapIntent = intent ?: replayBootstrapIntent?.takeIf {
            it.cadence == cadence
        }
        replayJob = replayScope.launch {
            var backoff = replayInitialDelayOverrideForTesting ?: REPLAY_INITIAL_DELAY_MS
            val maxBackoff = replayMaxDelayOverrideForTesting ?: REPLAY_MAX_DELAY_MS
            while (currentCoroutineContext().isActive) {
                delay(backoff.coerceAtLeast(1L))
                val stillNeedsBootstrap = synchronized(lock) {
                    val preferences = PreferenceManager
                        .getDefaultSharedPreferences(context.applicationContext)
                    criticalString(preferences, PREF_CADENCE) == cadence &&
                        criticalString(preferences, PREF_GENERATION).isNullOrBlank()
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
                    criticalString(preferences, PREF_CADENCE) == cadence &&
                        criticalString(preferences, PREF_GENERATION).isNullOrBlank()
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
                pending != null -> successorDebtOf(pending) == expected &&
                    isEffectCompleteForOccurrenceLocked(preferences, pending)
                active != null -> successorDebtOf(active) == expected &&
                    isEffectCompleteForOccurrenceLocked(preferences, active)
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
            if (!isEffectCompleteForOccurrenceLocked(preferences, predecessor)) {
                return@synchronized false
            }
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
    ): Boolean = criticalString(preferences, PREF_GENERATION) == debt.generation &&
        criticalString(preferences, PREF_CADENCE) == debt.cadence &&
        criticalInt(preferences, PREF_MONTHLY_ANCHOR_DAY, -1) == debt.monthlyAnchorDay

    private fun canUseFallbackDebt(
        preferences: android.content.SharedPreferences,
        fallback: SchedulingDebt,
    ): Boolean {
        val pending = readSchedulingDebt(preferences)
        val active = readActiveSchedulingDebt(preferences)
        return when {
            pending == null && active == null -> authorityMatchesDebt(preferences, fallback)
            pending == fallback || active == fallback -> true
            pending != null -> successorDebtOf(pending) == fallback &&
                isEffectCompleteForOccurrenceLocked(preferences, pending)
            active != null -> successorDebtOf(active) == fallback &&
                isEffectCompleteForOccurrenceLocked(preferences, active)
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

    private fun currentEffectOccurrence(
        context: Context,
        generation: String,
        cadence: String,
        monthlyAnchorDay: Int,
        occurrenceAt: Long?,
    ): OwnedEffectOccurrence? {
        if (occurrenceAt == null || occurrenceAt <= 0L || monthlyAnchorDay < 1) {
            return null
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val pending = readSchedulingDebt(preferences)
        val active = readActiveSchedulingDebt(preferences)
        if (pending != null && active != null) {
            // Both slots being populated is an impossible transition state;
            // do not grant destructive authority to whichever tuple happens
            // to match the caller.
            return null
        }
        val matchingOccurrences = buildList {
            pending
                ?.takeIf { debt ->
                    debt.matchesAuthority(generation, cadence, monthlyAnchorDay) &&
                        debt.occurrenceAt == occurrenceAt
                }
                ?.let { add(OwnedEffectOccurrence(it, EffectOccurrenceSlot.PENDING)) }
            active
                ?.takeIf { debt ->
                    debt.matchesAuthority(generation, cadence, monthlyAnchorDay) &&
                        debt.occurrenceAt == occurrenceAt
                }
                ?.let { add(OwnedEffectOccurrence(it, EffectOccurrenceSlot.ACTIVE)) }
        }
        return matchingOccurrences.singleOrNull()
    }

    private fun readEffectPhase(
        context: Context,
        slot: EffectOccurrenceSlot,
    ): EffectPhase {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        return readEffectPhase(preferences, slot)
    }

    private fun readEffectPhase(
        preferences: android.content.SharedPreferences,
        slot: EffectOccurrenceSlot,
    ): EffectPhase {
        val key = when (slot) {
            EffectOccurrenceSlot.PENDING -> PREF_PENDING_EFFECT_PHASE
            EffectOccurrenceSlot.ACTIVE -> PREF_ACTIVE_EFFECT_PHASE
        }
        return when (criticalString(preferences, key)) {
            // Occurrence slots written by this protocol always carry an
            // explicit phase. A missing phase on an older durable slot is
            // not proof that its effect is still eligible, so re-entry must
            // fail closed rather than repeat an unknown effect.
            null -> EffectPhase.UNKNOWN
            EFFECT_PHASE_ELIGIBLE -> EffectPhase.ELIGIBLE
            EFFECT_PHASE_IN_PROGRESS -> EffectPhase.IN_PROGRESS
            EFFECT_PHASE_CONSUMED -> EffectPhase.CONSUMED
            else -> EffectPhase.UNKNOWN
        }
    }

    private fun readEffectJournalRaw(
        preferences: android.content.SharedPreferences,
    ): String? = criticalString(preferences, PREF_EFFECT_JOURNAL)

    private fun readEffectJournalRaw(context: Context): String? =
        readEffectJournalRaw(
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext),
        )

    private fun readEffectJournal(
        preferences: android.content.SharedPreferences,
    ): CleanupEffectJournal? = readEffectJournalRaw(preferences)?.let { raw ->
        runCatching { gson.fromJson(raw, CleanupEffectJournal::class.java) }
            .getOrNull()
    }

    private fun readEffectJournal(context: Context): CleanupEffectJournal? =
        readEffectJournal(
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext),
        )

    /**
     * Durably advances one exact occurrence journal while its effect mutex is
     * held by the caller.  A null result means the occurrence or its journal
     * could no longer be proven current; callers must stop rather than widen
     * or guess the unfinished responsibility.
     */
    internal fun updateEffectJournal(
        context: Context,
        generation: String,
        cadence: String,
        monthlyAnchorDay: Int,
        occurrenceAt: Long,
        update: (CleanupEffectJournal) -> CleanupEffectJournal,
    ): CleanupEffectJournal? = synchronized(lock) {
        val ownedOccurrence = currentEffectOccurrence(
            context = context,
            generation = generation,
            cadence = cadence,
            monthlyAnchorDay = monthlyAnchorDay,
            occurrenceAt = occurrenceAt,
        ) ?: return@synchronized null
        val current = readEffectJournal(context)
            ?.takeIf { it.matches(ownedOccurrence.debt) && it.isStructurallyValid() }
            ?: return@synchronized null
        if (readEffectPhase(context, ownedOccurrence.slot) != EffectPhase.IN_PROGRESS) {
            return@synchronized null
        }
        val updated = runCatching { update(current) }.getOrNull()
            ?.takeIf { it.matches(ownedOccurrence.debt) && it.isStructurallyValid() }
            ?: return@synchronized null
        val phaseKey = when (ownedOccurrence.slot) {
            EffectOccurrenceSlot.PENDING -> PREF_PENDING_EFFECT_PHASE
            EffectOccurrenceSlot.ACTIVE -> PREF_ACTIVE_EFFECT_PHASE
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        if (!commitEffectPhase(
                preferences = preferences,
                editor = preferences.edit()
                    .putString(phaseKey, EFFECT_PHASE_IN_PROGRESS)
                    .putString(PREF_EFFECT_JOURNAL, gson.toJson(updated))
            )
        ) {
            return@synchronized null
        }
        updated
    }

    private suspend fun <T> executeJournaledEffect(
        context: Context,
        ownedOccurrence: OwnedEffectOccurrence,
        journal: CleanupEffectJournal,
        effect: suspend (CleanupEffectJournal) -> T,
    ): DestructiveEffectResult<T> {
        try {
            val result = effect(journal)
            val completedJournal = readEffectJournal(context)
            if (
                completedJournal == null ||
                !completedJournal.matches(ownedOccurrence.debt) ||
                !completedJournal.isStructurallyValid() ||
                !completedJournal.isComplete
            ) {
                ensureReplayOwnerLocked(context, ownedOccurrence.debt)
                throw EffectPhaseRecoveryRequired(
                    IllegalStateException("cleanup effect journal is incomplete after effect"),
                )
            }
            return if (commitEffectPhaseForOccurrence(
                    context = context,
                    generation = ownedOccurrence.debt.generation,
                    cadence = ownedOccurrence.debt.cadence,
                    monthlyAnchorDay = ownedOccurrence.debt.monthlyAnchorDay,
                    occurrenceAt = ownedOccurrence.debt.occurrenceAt,
                    phase = EffectPhase.CONSUMED,
                )
            ) {
                DestructiveEffectResult.Completed(result)
            } else {
                // The complete journal remains the exact process-death-safe
                // carrier even when the final phase write is unavailable.
                ensureReplayOwnerLocked(context, ownedOccurrence.debt)
                DestructiveEffectResult.AlreadyConsumed(recoveryRequired = true)
            }
        } catch (cancelled: CancellationException) {
            ensureReplayOwnerLocked(context, ownedOccurrence.debt)
            throw cancelled
        } catch (recovery: EffectPhaseRecoveryRequired) {
            ensureReplayOwnerLocked(context, ownedOccurrence.debt)
            throw recovery
        } catch (failure: Exception) {
            // The body contains independently committing effects.  Never
            // reset the occurrence to ELIGIBLE after an arbitrary failure;
            // the journal is the exact resume carrier for the unfinished
            // suffix and keeps later targets outside this occurrence.
            ensureReplayOwnerLocked(context, ownedOccurrence.debt)
            throw EffectPhaseRecoveryRequired(failure)
        }
    }

    private fun commitEffectPhaseForOccurrence(
        context: Context,
        generation: String?,
        cadence: String?,
        monthlyAnchorDay: Int,
        occurrenceAt: Long?,
        phase: EffectPhase,
        journal: CleanupEffectJournal? = null,
    ): Boolean = synchronized(lock) {
        val ownedOccurrence = currentEffectOccurrence(
            context = context,
            generation = requireNotNull(generation),
            cadence = requireNotNull(cadence),
            monthlyAnchorDay = monthlyAnchorDay,
            occurrenceAt = occurrenceAt,
        ) ?: return@synchronized false
        val key = when (ownedOccurrence.slot) {
            EffectOccurrenceSlot.PENDING -> PREF_PENDING_EFFECT_PHASE
            EffectOccurrenceSlot.ACTIVE -> PREF_ACTIVE_EFFECT_PHASE
        }
        val storedPhase = when (phase) {
            EffectPhase.ELIGIBLE -> EFFECT_PHASE_ELIGIBLE
            EffectPhase.IN_PROGRESS -> EFFECT_PHASE_IN_PROGRESS
            EffectPhase.CONSUMED -> EFFECT_PHASE_CONSUMED
            EffectPhase.UNKNOWN -> return@synchronized false
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val editor = preferences
            .edit()
            .putString(key, storedPhase)
        if (journal != null) {
            if (!journal.matches(ownedOccurrence.debt) || !journal.isStructurallyValid()) {
                return@synchronized false
            }
            editor.putString(PREF_EFFECT_JOURNAL, gson.toJson(journal))
        }
        commitEffectPhase(preferences, editor)
    }

    private fun readDebt(
        preferences: android.content.SharedPreferences,
        generationKey: String,
        cadenceKey: String,
        anchorKey: String,
        occurrenceKey: String,
    ): SchedulingDebt? {
        val generation = criticalString(preferences, generationKey) ?: return null
        val cadence = criticalString(preferences, cadenceKey) ?: return null
        val anchorDay = criticalInt(preferences, anchorKey, -1)
        val occurrenceAt = criticalLong(preferences, occurrenceKey, -1L)
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
        effectPhase: EffectPhase = EffectPhase.ELIGIBLE,
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
    ).putString(
        PREF_PENDING_EFFECT_PHASE,
        effectPhase.storageValue(),
    )

    private fun android.content.SharedPreferences.Editor.putActiveDebt(
        debt: SchedulingDebt,
        effectPhase: EffectPhase = EffectPhase.ELIGIBLE,
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
    ).putString(
        PREF_ACTIVE_EFFECT_PHASE,
        effectPhase.storageValue(),
    )

    private fun android.content.SharedPreferences.Editor.removePendingDebt():
        android.content.SharedPreferences.Editor = remove(PREF_PENDING_GENERATION)
            .remove(PREF_PENDING_CADENCE)
            .remove(PREF_PENDING_ANCHOR_DAY)
            .remove(PREF_PENDING_OCCURRENCE_AT)
            .remove(PREF_PENDING_EFFECT_PHASE)

    private fun android.content.SharedPreferences.Editor.removeActiveDebt():
        android.content.SharedPreferences.Editor = remove(PREF_ACTIVE_GENERATION)
            .remove(PREF_ACTIVE_CADENCE)
            .remove(PREF_ACTIVE_ANCHOR_DAY)
            .remove(PREF_ACTIVE_OCCURRENCE_AT)
            .remove(PREF_ACTIVE_EFFECT_PHASE)

    private fun EffectPhase.storageValue(): String = when (this) {
        EffectPhase.ELIGIBLE -> EFFECT_PHASE_ELIGIBLE
        EffectPhase.IN_PROGRESS -> EFFECT_PHASE_IN_PROGRESS
        EffectPhase.CONSUMED -> EFFECT_PHASE_CONSUMED
        // An unrecognized persisted phase must never become eligible merely
        // because it crossed a pending -> active transition.
        EffectPhase.UNKNOWN -> EFFECT_PHASE_IN_PROGRESS
    }

    private fun stopReplayOwnerLocked() {
        replayJob?.cancel()
        replayJob = null
        replayDebt = null
        replayBootstrapCadence = null
    }

    private fun retirePendingDebtLocked(preferences: android.content.SharedPreferences): Boolean {
        val retired = commitAuthority(
            preferences = preferences,
            editor = preferences.edit()
                .removePendingDebt()
                .removeActiveDebt()
                .remove(PREF_EFFECT_JOURNAL)
        )
        if (retired) {
            replayBootstrapIntent = null
            stopReplayOwnerLocked()
        }
        return retired
    }

    internal fun resetReplayOwnerForTesting() = synchronized(lock) {
        stopReplayOwnerLocked()
    }

    /** Returns the last coordinator-confirmed cadence for the settings UI. */
    internal fun currentCadenceForSettings(context: Context): String = synchronized(lock) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        criticalString(preferences, PREF_CADENCE).orEmpty()
    }

    /** Models process death for tests by discarding process-local durability fences. */
    internal fun resetDurabilityFenceForTesting() = synchronized(lock) {
        criticalDurabilityFence = null
        replayBootstrapIntent = null
    }

    /**
     * Test-only model of a process restart after a critical commit returned
     * false.  Android reloads the last disk-backed SharedPreferences values in
     * the new process; this restores the coordinator's captured durable
     * snapshot and also removes process-local replay ownership.  Production
     * process death naturally provides the same boundary.
     */
    internal fun simulateProcessRestartForTesting(context: Context) = synchronized(lock) {
        val snapshot = criticalDurabilityFence
        if (snapshot != null) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            val editor = preferences.edit()
            criticalPreferenceKeys.forEach { key ->
                when (val value = snapshot.values[key]) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    else -> error("unsupported critical preference type for $key")
                }
            }
            check(editor.commit()) { "test restart could not restore durable preferences" }
        }
        criticalDurabilityFence = null
        replayBootstrapIntent = null
        stopReplayOwnerLocked()
    }

    internal fun seedEffectJournalForTesting(
        context: Context,
        journal: CleanupEffectJournal,
        phase: String = EFFECT_PHASE_IN_PROGRESS,
    ): Boolean = synchronized(lock) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val slot = currentEffectOccurrence(
            context = context,
            generation = journal.generation,
            cadence = journal.cadence,
            monthlyAnchorDay = journal.monthlyAnchorDay,
            occurrenceAt = journal.occurrenceAt,
        ) ?: return@synchronized false
        val phaseKey = when (slot.slot) {
            EffectOccurrenceSlot.PENDING -> PREF_PENDING_EFFECT_PHASE
            EffectOccurrenceSlot.ACTIVE -> PREF_ACTIVE_EFFECT_PHASE
        }
        commitEffectPhase(
            preferences = preferences,
            editor = preferences.edit()
                .putString(PREF_EFFECT_JOURNAL, gson.toJson(journal))
                .putString(phaseKey, phase)
        )
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
