package com.ireum.ytdl.work

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.RestoreOperationStore
import com.ireum.ytdl.database.models.BackupSettingsItem
import java.util.Calendar
import java.util.UUID

/**
 * Durable owner for the small gap between a scheduler preference decision and
 * publication of the scheduler's external authority.
 *
 * The transition record and the target preference values are published by one
 * SharedPreferences commit.  The record is destination-local runtime state;
 * it is deliberately excluded from portable settings backup.  A record is
 * removed only after the existing scheduler effect path has returned, so a
 * process restart can replay the exact target transition.
 */
internal object SchedulerSettingsTransitionCoordinator {
    private const val TRANSITION_KEY = "scheduler_settings_transition_v1"
    private const val TRANSITION_PREFIX = "scheduler_settings_transition_"
    private const val PHASE_PREPARED = "PREPARED"
    private const val PHASE_EFFECT_IN_PROGRESS = "EFFECT_IN_PROGRESS"
    private const val PHASE_COMPLETE = "COMPLETE"

    private val gson = Gson()

    internal enum class Kind {
        SCHEDULE_START,
        SCHEDULE_END,
        USE_SCHEDULER,
        RESTORE,
    }

    internal data class Transition(
        val id: String,
        val kind: String,
        val targetScheduleStart: String,
        val targetScheduleEnd: String,
        val targetUseScheduler: Boolean,
        val phase: String,
        val createdAt: Long,
        val successorAttempt: Int = 0,
        val restoreOperationId: String = "",
        val startAt: Long = 0L,
        val endAt: Long = 0L,
        val priorOwnersRevoked: Boolean = false,
        val startPublished: Boolean = false,
        val endPublished: Boolean = false,
    ) {
        fun checkedKind(): Kind = runCatching { Kind.valueOf(kind) }
            .getOrElse { throw IllegalStateException("Unknown scheduler transition kind: $kind") }

        fun checked(): Transition {
            check(runCatching { UUID.fromString(id) }.isSuccess) {
                "Malformed scheduler transition identity"
            }
            val kind = checkedKind()
            SchedulerSettingsValidation.requireTime(targetScheduleStart)
            SchedulerSettingsValidation.requireTime(targetScheduleEnd)
            check(phase == PHASE_PREPARED || phase == PHASE_EFFECT_IN_PROGRESS ||
                (kind == Kind.RESTORE && phase == PHASE_COMPLETE)) {
                "Malformed scheduler transition phase"
            }
            if (kind == Kind.RESTORE) {
                check(restoreOperationId.isBlank() || restoreOperationId == id) {
                    "Scheduler Restore operation identity mismatch"
                }
                check(startAt > 0L && endAt > 0L) { "Scheduler Restore has no fixed boundaries" }
            }
            return this
        }
    }

    @Volatile
    internal var beforePreferencePublicationForTesting: ((Transition) -> Unit)? = null

    @Volatile
    internal var beforeExternalEffectForTesting: ((Transition) -> Unit)? = null

    @Volatile
    internal var afterExternalEffectForTesting: ((Transition) -> Unit)? = null

    @Volatile
    internal var beforeRetirementForTesting: ((Transition) -> Unit)? = null

    internal fun transitionKeyForTesting(): String = TRANSITION_KEY

    /**
     * Runs one ordinary scheduler preference transition under the existing
     * Restore admission.  The callback is the actual external-effect path;
     * it must not acquire the admission in reverse or perform long work.
     */
    internal fun runOrdinaryTransition(
        context: Context,
        kind: Kind,
        targetScheduleStart: () -> String,
        targetScheduleEnd: () -> String,
        targetUseScheduler: () -> Boolean,
        applyEffect: (Transition) -> Unit,
    ) {
        RestoreMutationAdmission.withOrdinaryMutationBlocking(context) {
            reconcilePendingWithinOrdinaryMutation(context, applyEffect)

            val transition = Transition(
                id = UUID.randomUUID().toString(),
                kind = kind.name,
                targetScheduleStart = targetScheduleStart(),
                targetScheduleEnd = targetScheduleEnd(),
                targetUseScheduler = targetUseScheduler(),
                phase = PHASE_PREPARED,
                createdAt = System.currentTimeMillis(),
            ).checked()

            publishPreferenceAndTransition(context, transition)
            val inProgress = transition.copy(phase = PHASE_EFFECT_IN_PROGRESS)
            writeTransition(context, inProgress)
            beforeExternalEffectForTesting?.invoke(inProgress)
            applyEffect(inProgress)
            afterExternalEffectForTesting?.invoke(inProgress)
            beforeRetirementForTesting?.invoke(inProgress)
            retire(context, inProgress)
        }
    }

    /**
     * Completes an already-published transition while ordinary admission is
     * held.  Startup invokes this before scheduler carrier reconciliation and
     * every scheduler producer can use it as a narrow re-entry guard.
     */
    internal fun reconcile(
        context: Context,
        applyEffect: (Transition) -> Unit = { transition ->
            AlarmScheduler(context).applySchedulerTransitionEffectWithinOrdinaryMutation(transition)
        },
    ) {
        if (RestoreTransactionCoordinator.currentReconciliationAuthorityOrNull(context) != null) {
            return
        }
        RestoreMutationAdmission.withOrdinaryMutationBlocking(context) {
            reconcilePendingWithinOrdinaryMutation(context, applyEffect)
        }
    }

    internal fun reconcilePendingWithinOrdinaryMutation(
        context: Context,
        applyEffect: (Transition) -> Unit,
    ) {
        val existing = read(context) ?: return
        if (existing.phase == PHASE_COMPLETE) return
        if (existing.checkedKind() == Kind.RESTORE) {
            check(existing.restoreOperationId.isBlank()) { "Reset owns scheduler recovery" }
        }
        ensureTargetPreferences(context, existing)
        val inProgress = if (existing.phase == PHASE_PREPARED) {
            val next = existing.copy(phase = PHASE_EFFECT_IN_PROGRESS)
            writeTransition(context, next)
            next
        } else {
            existing
        }
        beforeExternalEffectForTesting?.invoke(inProgress)
        applyEffect(inProgress)
        afterExternalEffectForTesting?.invoke(inProgress)
        beforeRetirementForTesting?.invoke(inProgress)
        retire(context, inProgress)
    }

    /**
     * Restore explicitly supersedes an old ordinary transition after the
     * restored preference image is durable.  This is deliberately not an
     * accidental clear during backup/settings publication.
     */
    internal fun supersedeForRestore(
        context: Context,
        authority: RestoreTransactionCoordinator.RestoreReconciliationAuthority,
    ) {
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (read(context)?.let { it.checkedKind() == Kind.RESTORE && it.restoreOperationId == authority.operationId } == true) return
        if (preferences.contains(TRANSITION_KEY)) {
            check(preferences.edit().remove(TRANSITION_KEY).commit()) {
                "Scheduler transition supersession was not durable"
            }
        }
    }

    internal fun readForTesting(context: Context): Transition? = read(context)

    /** Called with the existing Merge or Reset preference-publication admission held. */
    internal fun prepareRestoredPreferences(
        context: Context,
        editor: SharedPreferences.Editor,
        settings: List<BackupSettingsItem>,
        resetOperationId: String? = null,
    ) {
        if (resetOperationId == null && settings.none { it.key in setOf("schedule_start", "schedule_end", "use_scheduler") }) return
        if (resetOperationId != null) {
            check(RestoreOperationStore.load(context)?.journal?.operationId == resetOperationId) {
                "Scheduler preference publication has no exact Reset owner"
            }
        } else {
            reconcilePendingWithinOrdinaryMutation(context) {
                AlarmScheduler(context).applySchedulerTransitionEffectWithinOrdinaryMutation(it)
            }
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val start = settings.lastOrNull { it.key == "schedule_start" }?.value
            ?: if (resetOperationId != null) "00:00" else preferences.getString("schedule_start", "00:00")!!
        val end = settings.lastOrNull { it.key == "schedule_end" }?.value
            ?: if (resetOperationId != null) "05:00" else preferences.getString("schedule_end", "05:00")!!
        val enabled = settings.lastOrNull { it.key == "use_scheduler" }?.value?.toBooleanStrict()
            ?: (resetOperationId == null && preferences.getBoolean("use_scheduler", false))
        val existing = read(context)
        val transition = if (resetOperationId != null && existing?.id == resetOperationId &&
            existing.checkedKind() == Kind.RESTORE) {
            check(existing.targetScheduleStart == start && existing.targetScheduleEnd == end &&
                existing.targetUseScheduler == enabled) { "Scheduler Reset target changed" }
            existing
        } else {
            val now = Calendar.getInstance()
            val window = ScheduledDownloadWindow(start, end)
            Transition(resetOperationId ?: UUID.randomUUID().toString(), Kind.RESTORE.name,
                start, end, enabled, PHASE_PREPARED, now.timeInMillis,
                restoreOperationId = resetOperationId.orEmpty(),
                startAt = window.nextStart(now).timeInMillis, endAt = window.nextEnd(now).timeInMillis).checked()
        }
        // Persist the decision before the generic preference commit. Merge
        // has no Reset journal; this record remains recovery authority even
        // if SharedPreferences reports failure after updating its process map.
        writeTransition(context, transition)
        beforePreferencePublicationForTesting?.invoke(transition)
        editor.putString(TRANSITION_KEY, gson.toJson(transition))
            .putString("schedule_start", start).putString("schedule_end", end)
            .putBoolean("use_scheduler", enabled)
    }

    internal fun reconcileForRestore(
        context: Context,
        authority: RestoreTransactionCoordinator.RestoreReconciliationAuthority,
    ) {
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(context, authority)
        val existing = read(context) ?: return
        check(existing.checkedKind() == Kind.RESTORE && existing.restoreOperationId == authority.operationId) {
            "Scheduler transition is not owned by this Reset"
        }
        if (existing.phase == PHASE_COMPLETE) return
        ensureTargetPreferences(context, existing)
        beforeExternalEffectForTesting?.invoke(existing)
        AlarmScheduler(context).applyRestoredSchedulerEffectWithinMutation(existing, authority)
        afterExternalEffectForTesting?.invoke(existing)
    }

    internal fun persistRestoreProgress(context: Context, transition: Transition) {
        check(read(context)?.id == transition.id) { "Scheduler Restore owner changed" }
        writeTransition(context, transition.checked())
    }

    internal fun completeRestore(context: Context, transition: Transition) {
        persistRestoreProgress(context, transition.copy(phase = PHASE_COMPLETE))
    }

    internal fun pendingMergeRestore(context: Context): Transition? = read(context)?.takeIf {
        it.checkedKind() == Kind.RESTORE && it.restoreOperationId.isBlank() && it.phase != PHASE_COMPLETE
    }

    internal fun advanceSuccessorAttemptWithinOrdinaryMutation(
        context: Context,
        transition: Transition,
    ) {
        writeTransition(context, transition.checked())
    }

    internal fun clearForTesting(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(TRANSITION_KEY)
            .commit()
        beforePreferencePublicationForTesting = null
        beforeExternalEffectForTesting = null
        afterExternalEffectForTesting = null
        beforeRetirementForTesting = null
    }

    internal fun isRuntimePreferenceKey(key: String): Boolean =
        key == TRANSITION_KEY || key.startsWith(TRANSITION_PREFIX)

    private fun publishPreferenceAndTransition(
        context: Context,
        transition: Transition,
    ) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        // PREPARED is durably visible before the target preference is changed.
        // If the following commit is interrupted, startup can still complete
        // the target from this record.
        writeTransition(context, transition)
        beforePreferencePublicationForTesting?.invoke(transition)
        val editor = preferences.edit()
            .putString(TRANSITION_KEY, gson.toJson(transition.copy(phase = PHASE_PREPARED)))
            .putString("schedule_start", transition.targetScheduleStart)
            .putString("schedule_end", transition.targetScheduleEnd)
            .putBoolean("use_scheduler", transition.targetUseScheduler)
        check(editor.commit()) { "Scheduler preference transition was not durable" }
    }

    private fun ensureTargetPreferences(
        context: Context,
        transition: Transition,
    ) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val matches = preferences.getString("schedule_start", "00:00") ==
            transition.targetScheduleStart &&
            preferences.getString("schedule_end", "05:00") ==
            transition.targetScheduleEnd &&
            preferences.getBoolean("use_scheduler", false) == transition.targetUseScheduler
        if (matches) return
        check(
            preferences.edit()
                .putString("schedule_start", transition.targetScheduleStart)
                .putString("schedule_end", transition.targetScheduleEnd)
                .putBoolean("use_scheduler", transition.targetUseScheduler)
                .commit(),
        ) { "Scheduler preference recovery was not durable" }
    }

    private fun writeTransition(context: Context, transition: Transition) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        check(
            preferences.edit()
                .putString(TRANSITION_KEY, gson.toJson(transition.checked()))
                .commit(),
        ) { "Scheduler transition publication was not durable" }
    }

    private fun retire(context: Context, transition: Transition) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val current = read(context)
        if (current?.id != transition.id) return
        if (transition.checkedKind() == Kind.RESTORE) return // COMPLETE is the Reset replay witness.
        check(preferences.edit().remove(TRANSITION_KEY).commit()) {
            "Scheduler transition retirement was not durable"
        }
    }

    private fun read(context: Context): Transition? {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(TRANSITION_KEY, null)
            ?: return null
        return try {
            gson.fromJson(raw, Transition::class.java)?.checked()
                ?: throw IllegalStateException("Scheduler transition is null")
        } catch (error: JsonParseException) {
            throw IllegalStateException("Scheduler transition is malformed", error)
        }
    }
}
