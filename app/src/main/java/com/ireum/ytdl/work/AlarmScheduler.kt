package com.ireum.ytdl.work

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.database.RestoreTransactionCoordinator.RestoreReconciliationAuthority
import com.ireum.ytdl.receiver.CancelScheduleAlarmReceiver
import com.ireum.ytdl.receiver.ScheduleAlarmReceiver
import kotlinx.coroutines.CancellationException
import java.util.Calendar
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

class AlarmScheduler(private val context: Context) {

    companion object {
        @Volatile
        internal var exactAlarmPublicationForTesting:
            ((AlarmManager, Long, PendingIntent) -> Unit)? = null

        @Volatile
        internal var schedulerTransitionStepForTesting: ((String) -> Unit)? = null

        @Volatile
        internal var beforeDisableSuccessorEnqueueForTesting:
            ((SchedulerSettingsTransitionCoordinator.Transition) -> Unit)? = null

        internal const val TRANSITION_STEP_AFTER_CANCELLATION =
            "after_scheduler_cancellation"
        internal const val TRANSITION_STEP_AFTER_START_PUBLICATION =
            "after_scheduler_start_publication"
        internal const val TRANSITION_STEP_AFTER_END_PUBLICATION =
            "after_scheduler_end_publication"
    }

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager


    suspend fun scheduleAt(at: Long) = RestoreMutationAdmission.withOrdinaryMutation(context) {
        SchedulerSettingsTransitionCoordinator.reconcilePendingWithinOrdinaryMutation(context) {
            applySchedulerTransitionEffectWithinOrdinaryMutation(it)
        }
        val handoffId = WorkManagerHandoffRecovery.prepareSchedulerBoundaryWithinOrdinaryMutation(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            at,
        )
        setAlarm(
            receiver = ScheduleAlarmReceiver::class.java,
            requestCode = 1,
            at = at,
            handoffId = handoffId,
        )
    }
    internal suspend fun scheduleAtForRestore(
        at: Long,
        authority: RestoreReconciliationAuthority,
    ) {
        val handoffId = WorkManagerHandoffRecovery.prepareSchedulerBoundaryForRestore(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            at,
            authority,
        )
        if (WorkManagerHandoffRecovery.restoreSuccessorNeedsFallbackRetry(context, handoffId, authority)) {
            WorkManagerHandoffRecovery.ensureConvergenceForRestoreAndAwait(
                context,
                handoffId,
                authority,
            )
            return
        }
        if (WorkManagerHandoffRecovery.restoreSuccessorAlreadyAccepted(context, handoffId, authority)) {
            return
        }
        setAlarmForRestore(
            receiver = ScheduleAlarmReceiver::class.java,
            requestCode = 1,
            at = at,
            handoffId = handoffId,
            restoreAuthority = authority,
        )
    }
    @SuppressLint("ScheduleExactAlarm")
    fun schedule() {
        RestoreMutationAdmission.tryOrdinaryMutationBlocking(context) {
            SchedulerSettingsTransitionCoordinator.reconcilePendingWithinOrdinaryMutation(context) {
                applySchedulerTransitionEffectWithinOrdinaryMutation(it)
            }
            scheduleWithinOrdinaryMutation()
        }
    }

    internal suspend fun scheduleSuspending() = RestoreMutationAdmission.withOrdinaryMutation(context) {
        SchedulerSettingsTransitionCoordinator.reconcilePendingWithinOrdinaryMutation(context) {
            applySchedulerTransitionEffectWithinOrdinaryMutation(it)
        }
        scheduleWithinOrdinaryMutation()
    }

    @SuppressLint("ScheduleExactAlarm")
    internal fun scheduleWithinOrdinaryMutation() {
        WorkManagerHandoffRecovery.cancelScheduledHandoffsWithinOrdinaryMutation(context)
        cancelAlarmsWithinOrdinaryMutation()
        schedulerTransitionStepForTesting?.invoke(TRANSITION_STEP_AFTER_CANCELLATION)

        val startingTime = preferences.getString("schedule_start", "00:00")!!
        val sTime = Calendar.getInstance()
        sTime.set(Calendar.HOUR_OF_DAY, startingTime.split(":")[0].toInt())
        sTime.set(Calendar.MINUTE, startingTime.split(":")[1].toInt())
        sTime.set(Calendar.SECOND, 0)
        val time = calculateNextTime(sTime)

        val startHandoffId = WorkManagerHandoffRecovery.prepareSchedulerBoundaryWithinOrdinaryMutation(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            time.timeInMillis,
        )
        setAlarm(
            receiver = ScheduleAlarmReceiver::class.java,
            requestCode = 0,
            at = time.timeInMillis,
            handoffId = startHandoffId,
        )
        schedulerTransitionStepForTesting?.invoke(TRANSITION_STEP_AFTER_START_PUBLICATION)

        val endingTime = preferences.getString("schedule_end", "05:00")!!
        val eTime = Calendar.getInstance()
        eTime.set(Calendar.HOUR_OF_DAY, endingTime.split(":")[0].toInt())
        eTime.set(Calendar.MINUTE, endingTime.split(":")[1].toInt())
        sTime.set(Calendar.SECOND, 0)
        val calendar = calculateNextTime(eTime)

        val endHandoffId = WorkManagerHandoffRecovery.prepareSchedulerBoundaryWithinOrdinaryMutation(
            context,
            WorkManagerHandoffCarrier.END_BOUNDARY,
            calendar.timeInMillis,
        )
        setAlarm(
            receiver = CancelScheduleAlarmReceiver::class.java,
            requestCode = 0,
            at = calendar.timeInMillis,
            handoffId = endHandoffId,
        )
        schedulerTransitionStepForTesting?.invoke(TRANSITION_STEP_AFTER_END_PUBLICATION)
    }

    internal fun updateSchedulerEnabled(enabled: Boolean): Boolean = try {
        SchedulerSettingsTransitionCoordinator.runOrdinaryTransition(
            context = context,
            kind = SchedulerSettingsTransitionCoordinator.Kind.USE_SCHEDULER,
            targetScheduleStart = { preferences.getString("schedule_start", "00:00")!! },
            targetScheduleEnd = { preferences.getString("schedule_end", "05:00")!! },
            targetUseScheduler = { enabled },
        ) { transition ->
            applySchedulerTransitionEffectWithinOrdinaryMutation(transition)
        }
        true
    } catch (blocked: IllegalStateException) {
        if (blocked.message == "Restore transaction is active") false else throw blocked
    }

    /**
     * Used by queue producers that immediately start their own durable queue
     * after exact-alarm permission is unavailable.  Scheduler authority is
     * still disabled through the same restart-safe transition, but the caller
     * remains the owner of that already-existing queue-start operation.
     */
    internal fun disableForImmediateQueueStart(): Boolean = try {
        SchedulerSettingsTransitionCoordinator.runOrdinaryTransition(
            context = context,
            kind = SchedulerSettingsTransitionCoordinator.Kind.USE_SCHEDULER,
            targetScheduleStart = { preferences.getString("schedule_start", "00:00")!! },
            targetScheduleEnd = { preferences.getString("schedule_end", "05:00")!! },
            targetUseScheduler = { false },
        ) {
            cancelWithinOrdinaryMutation()
        }
        true
    } catch (blocked: IllegalStateException) {
        if (blocked.message == "Restore transaction is active") false else throw blocked
    }

    internal fun updateScheduleBoundary(
        key: String,
    value: String,
    ): Boolean = try {
        check(key == "schedule_start" || key == "schedule_end") {
            "Unsupported scheduler preference $key"
        }
        SchedulerSettingsTransitionCoordinator.runOrdinaryTransition(
            context = context,
            kind = if (key == "schedule_start") {
                SchedulerSettingsTransitionCoordinator.Kind.SCHEDULE_START
            } else {
                SchedulerSettingsTransitionCoordinator.Kind.SCHEDULE_END
            },
            targetScheduleStart = {
                if (key == "schedule_start") value
                else preferences.getString("schedule_start", "00:00")!!
            },
            targetScheduleEnd = {
                if (key == "schedule_end") value
                else preferences.getString("schedule_end", "05:00")!!
            },
            targetUseScheduler = { preferences.getBoolean("use_scheduler", false) },
        ) { transition ->
            applySchedulerTransitionEffectWithinOrdinaryMutation(transition)
        }
        true
    } catch (blocked: IllegalStateException) {
        if (blocked.message == "Restore transaction is active") false else throw blocked
    }
    fun cancel() {
        RestoreMutationAdmission.tryOrdinaryMutationBlocking(context) {
            SchedulerSettingsTransitionCoordinator.reconcilePendingWithinOrdinaryMutation(context) {
                applySchedulerTransitionEffectWithinOrdinaryMutation(it)
            }
            cancelWithinOrdinaryMutation()
        }
    }

    /** Applies one durable scheduler-settings transition while admission is held. */
    internal fun applySchedulerTransitionEffectWithinOrdinaryMutation(
        transition: SchedulerSettingsTransitionCoordinator.Transition,
    ) {
        when (transition.checkedKind()) {
            SchedulerSettingsTransitionCoordinator.Kind.SCHEDULE_START,
            SchedulerSettingsTransitionCoordinator.Kind.SCHEDULE_END,
            -> scheduleWithinOrdinaryMutation()

            SchedulerSettingsTransitionCoordinator.Kind.USE_SCHEDULER -> {
                if (transition.targetUseScheduler) {
                    scheduleWithinOrdinaryMutation()
                } else {
                    cancelWithinOrdinaryMutation()
                    schedulerTransitionStepForTesting?.invoke(TRANSITION_STEP_AFTER_CANCELLATION)
                    enqueueDisableSuccessorWithinOrdinaryMutation(transition)
                }
            }
        }
    }

    /**
     * The disable successor belongs to the durable transition, not to the
     * wall-clock invocation that happened to publish it.  Stable identity plus
     * KEEP makes replay after request/acceptance process death idempotent.
     */
    private fun enqueueDisableSuccessorWithinOrdinaryMutation(
        transition: SchedulerSettingsTransitionCoordinator.Transition,
    ) {
        val identity = "scheduler-disable-successor|${transition.id}|${transition.successorAttempt}"
        val requestId = UUID.nameUUIDFromBytes(identity.toByteArray(StandardCharsets.UTF_8))
        val uniqueName = "scheduler-disable-${transition.id}-${transition.successorAttempt}"
        val manager = WorkManager.getInstance(context)
        val existing = runCatching {
            manager.getWorkInfoById(requestId).get(5L, TimeUnit.SECONDS)
        }.getOrElse { failure ->
            throw IllegalStateException("Could not inspect scheduler disable successor", failure)
        }
        if (existing != null && !existing.state.isFinished) return
        if (existing?.state == WorkInfo.State.SUCCEEDED) return
        if (existing != null) {
            val next = transition.copy(successorAttempt = transition.successorAttempt + 1)
            SchedulerSettingsTransitionCoordinator.advanceSuccessorAttemptWithinOrdinaryMutation(
                context,
                next,
            )
            enqueueDisableSuccessorWithinOrdinaryMutation(next)
            return
        }

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(requestId)
            .addTag("download")
            .setConstraints(Constraints.Builder().build())
            .setInitialDelay(1000L, TimeUnit.MILLISECONDS)
            .build()
        beforeDisableSuccessorEnqueueForTesting?.invoke(transition)
        manager.enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.KEEP,
            workRequest,
        ).result.get(10L, TimeUnit.SECONDS)
    }

    internal fun cancelWithinOrdinaryMutation() {
        WorkManagerHandoffRecovery.cancelScheduledHandoffsWithinOrdinaryMutation(context)
        cancelAlarmsWithinOrdinaryMutation()
    }

    private fun cancelAlarmsWithinOrdinaryMutation() {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        val oneShotPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = Intent(context, CancelScheduleAlarmReceiver::class.java)
        val cancelPendingIntent = PendingIntent.getBroadcast(context, 0, cancelIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

        alarmManager?.let { manager ->
            pendingIntent?.let(manager::cancel)
            oneShotPendingIntent?.let(manager::cancel)
            cancelPendingIntent?.let(manager::cancel)
        }
    }
    private fun setAlarm(
        receiver: Class<out android.content.BroadcastReceiver>,
        requestCode: Int,
        at: Long,
        handoffId: String,
    ) {
        val pendingIntent = pendingIntent(receiver, requestCode, handoffId)
        try {
            if (!publishExactAlarm(at, pendingIntent)) {
                WorkManagerHandoffRecovery.ensureConvergence(context, handoffId)
            }
        } catch (_: Throwable) {
            // The durable carrier remains the successor if exact-alarm
            // publication itself is unavailable.
            WorkManagerHandoffRecovery.ensureConvergence(context, handoffId)
        }
    }

    private suspend fun setAlarmForRestore(
        receiver: Class<out android.content.BroadcastReceiver>,
        requestCode: Int,
        at: Long,
        handoffId: String,
        restoreAuthority: RestoreReconciliationAuthority,
    ) {
        val pendingIntent = pendingIntent(receiver, requestCode, handoffId)
        try {
            if (!publishExactAlarm(at, pendingIntent)) {
                WorkManagerHandoffRecovery.ensureConvergenceForRestoreAndAwait(
                    context,
                    handoffId,
                    restoreAuthority,
                )
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            try {
                WorkManagerHandoffRecovery.ensureConvergenceForRestoreAndAwait(
                    context,
                    handoffId,
                    restoreAuthority,
                )
            } catch (fallbackFailure: Throwable) {
                fallbackFailure.addSuppressed(failure)
                throw fallbackFailure
            }
        }
    }

    private fun pendingIntent(
        receiver: Class<out android.content.BroadcastReceiver>,
        requestCode: Int,
        handoffId: String,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, receiver).putExtra(
            WorkManagerHandoffRecovery.EXTRA_HANDOFF_ID,
            handoffId,
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("ScheduleExactAlarm")
    private fun publishExactAlarm(
        at: Long,
        pendingIntent: PendingIntent,
    ): Boolean {
        val manager = alarmManager ?: return false
        exactAlarmPublicationForTesting?.invoke(manager, at, pendingIntent)
            ?: manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                at,
                pendingIntent,
            )
        return true
    }
    private fun calculateNextTime(c: Calendar) : Calendar {
        val calendar = Calendar.getInstance()
        if (c.get(Calendar.HOUR_OF_DAY) < calendar.get(Calendar.HOUR_OF_DAY)){
            c.add(Calendar.DATE, 1)
        }else if (
            c.get(Calendar.HOUR_OF_DAY) == calendar.get(Calendar.HOUR_OF_DAY) &&
            c.get(Calendar.MINUTE) < calendar.get(Calendar.MINUTE)
            ){
            c.add(Calendar.DATE, 1)
        }
        return c
    }

    fun isDuringTheScheduledTime(): Boolean{
        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(Calendar.MINUTE)

        val startingTime = preferences.getString("schedule_start", "00:00")!!
        val startingHour = startingTime.split(":")[0].toInt()
        val startingMinute = startingTime.split(":")[1].toInt()

        val endingTime = preferences.getString("schedule_end", "05:00")!!
        var endingHour = endingTime.split(":")[0].toInt()
        if (endingHour < 12 && endingHour < startingHour){
            endingHour += 24
        }
        val endingMinute = endingTime.split(":")[1].toInt()

        if (currentHour in startingHour..endingHour){
            if (currentHour == endingHour){
                if (currentMinute > endingMinute) {
                    return false
                }
            }else if(currentHour == startingHour){
                if (currentMinute < startingMinute){
                    return false
                }
            }
            return true
        }

        return false
    }

    fun canSchedule() : Boolean {
        return if (Build.VERSION.SDK_INT >= 31){
            alarmManager?.canScheduleExactAlarms() == true
        }else {
            false
        }
    }
}
