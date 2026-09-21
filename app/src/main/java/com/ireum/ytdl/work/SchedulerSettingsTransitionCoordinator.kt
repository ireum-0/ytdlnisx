package com.ireum.ytdl.work

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreTransactionCoordinator
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

    private val gson = Gson()

    internal enum class Kind {
        SCHEDULE_START,
        SCHEDULE_END,
        USE_SCHEDULER,
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
    ) {
        fun checkedKind(): Kind = runCatching { Kind.valueOf(kind) }
            .getOrElse { throw IllegalStateException("Unknown scheduler transition kind: $kind") }

        fun checked(): Transition {
            check(runCatching { UUID.fromString(id) }.isSuccess) {
                "Malformed scheduler transition identity"
            }
            checkedKind()
            check(targetScheduleStart.matches(Regex("\\d{2}:\\d{2}"))) {
                "Malformed scheduler start target"
            }
            check(targetScheduleEnd.matches(Regex("\\d{2}:\\d{2}"))) {
                "Malformed scheduler end target"
            }
            check(phase == PHASE_PREPARED || phase == PHASE_EFFECT_IN_PROGRESS) {
                "Malformed scheduler transition phase"
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
        if (preferences.contains(TRANSITION_KEY)) {
            check(preferences.edit().remove(TRANSITION_KEY).commit()) {
                "Scheduler transition supersession was not durable"
            }
        }
    }

    internal fun readForTesting(context: Context): Transition? = read(context)

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
