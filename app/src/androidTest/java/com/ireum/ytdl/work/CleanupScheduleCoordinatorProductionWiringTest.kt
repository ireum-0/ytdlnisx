package com.ireum.ytdl.work

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Operation
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.ireum.ytdl.ui.more.settings.CleanupSchedulePreferenceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.Calendar
import java.util.UUID

/**
 * Drives the real coordinator and CleanUpLeftoverDownloads WorkManager path.
 * Cleanup itself is replaced only at the narrow side-effect boundary so the
 * schedule/generation/retry decisions remain production code.
 */
@RunWith(AndroidJUnit4::class)
class CleanupScheduleCoordinatorProductionWiringTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var preferences: android.content.SharedPreferences
    private val controlledOperations = Collections.synchronizedList(mutableListOf<ControlledOperation>())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        clearSchedulePreferences()
        clearTestSeams()
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        clearTestSeams()
        clearSchedulePreferences()
        controlledOperations.clear()
    }

    @Test
    fun cadenceChangesAndRepeatedReconciliationLeaveOneStableLogicalRequest() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        awaitUnfinishedCount(1)
        assertTrue(
            unfinishedCurrentWork().single().tags.contains(
                cadenceTag(CleanupSchedulePolicy.DAILY),
            )
        )

        CleanupScheduleCoordinator.reconcile(context)
        assertEquals(1, unfinishedCurrentWork().size)

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY)
        awaitUnfinishedCount(1)
        assertTrue(
            unfinishedCurrentWork().single().tags.contains(
                cadenceTag(CleanupSchedulePolicy.WEEKLY),
            )
        )

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.MONTHLY)
        CleanupScheduleCoordinator.reconcile(context)
        awaitUnfinishedCount(1)
        val finalWork = unfinishedCurrentWork()
        assertEquals(1, finalWork.size)
        assertTrue(finalWork.single().tags.contains(cadenceTag(CleanupSchedulePolicy.MONTHLY)))
    }

    @Test
    fun startupReconciliationRepairsMissingChainWithoutDuplicatingIt() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        assertEquals(1, unfinishedCurrentWork().size)

        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        assertTrue(unfinishedCurrentWork().isEmpty())

        CleanupScheduleCoordinator.reconcile(context)
        CleanupScheduleCoordinator.reconcile(context)
        val repaired = unfinishedCurrentWork()
        assertEquals(1, repaired.size)
        assertTrue(repaired.single().tags.contains(cadenceTag(CleanupSchedulePolicy.DAILY)))
    }

    @Test
    fun startupMissingGenerationCommitFailureDoesNotPublishNewAuthorityOrDebt() = runBlocking {
        assertTrue(
            preferences.edit()
                .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                .remove("cleanup_leftover_downloads_generation")
                .remove("cleanup_leftover_downloads_anchor_day")
                .remove("cleanup_leftover_downloads_pending_generation")
                .remove("cleanup_leftover_downloads_pending_cadence")
                .remove("cleanup_leftover_downloads_pending_anchor_day")
                .remove("cleanup_leftover_downloads_pending_occurrence_at")
                .commit()
        )
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }

        CleanupScheduleCoordinator.reconcile(context)

        assertEquals(
            CleanupSchedulePolicy.DAILY,
            preferences.getString("cleanup_leftover_downloads", null),
        )
        assertNull(preferences.getString("cleanup_leftover_downloads_generation", null))
        assertNull(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
        )
        assertTrue(unfinishedCurrentWork().isEmpty())
    }

    @Test
    fun startupMissingGenerationCommitsDebtBeforeEnqueueAttempt() = runBlocking {
        assertTrue(
            preferences.edit()
                .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                .remove("cleanup_leftover_downloads_generation")
                .remove("cleanup_leftover_downloads_anchor_day")
                .commit()
        )
        val operation = ControlledOperation().also { controlledOperations += it }
        var enqueueSawMatchingDebt = false
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { editor ->
            editor.commit()
        }
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, request ->
            val generation = request.workSpec.input.getString(
                CleanupScheduleCoordinator.INPUT_GENERATION,
            )
            val cadence = request.workSpec.input.getString(
                CleanupScheduleCoordinator.INPUT_CADENCE,
            )
            val anchorDay = request.workSpec.input.getInt(
                CleanupScheduleCoordinator.INPUT_MONTHLY_ANCHOR_DAY,
                -1,
            )
            val occurrenceAt = request.workSpec.input.getLong(
                CleanupScheduleCoordinator.INPUT_OCCURRENCE_AT,
                -1L,
            )
            enqueueSawMatchingDebt = generation == preferences.getString(
                "cleanup_leftover_downloads_pending_generation",
                null,
            ) && cadence == preferences.getString(
                "cleanup_leftover_downloads_pending_cadence",
                null,
            ) && anchorDay == preferences.getInt(
                "cleanup_leftover_downloads_pending_anchor_day",
                -1,
            ) && occurrenceAt == preferences.getLong(
                "cleanup_leftover_downloads_pending_occurrence_at",
                -1L,
            )
            operation
        }

        CleanupScheduleCoordinator.reconcile(context)

        assertTrue(enqueueSawMatchingDebt)
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_generation", null)
                ?.isNotBlank() == true
        )
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
    }

    @Test
    fun startupMissingGenerationEnqueueFailureReplaysInProcess() = runBlocking {
        assertTrue(
            preferences.edit()
                .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                .remove("cleanup_leftover_downloads_generation")
                .remove("cleanup_leftover_downloads_anchor_day")
                .commit()
        )
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 25L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 100L
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            if (enqueueCalls.getAndIncrement() == 0) {
                throw IllegalStateException("startup enqueue failure")
            }
            workManager.enqueueUniqueWork(name, policy, request)
        }

        CleanupScheduleCoordinator.reconcile(context)

        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        assertTrue(enqueueCalls.get() >= 2)
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun successorDiscoveryFailurePersistsExactDebtBeforeQueryAndRecovers() = runBlocking {
        val scheduleNow = Calendar.getInstance()
        CleanupScheduleCoordinator.nowProviderForTesting = {
            scheduleNow.clone() as Calendar
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        assertEquals(1, unfinishedCurrentWork().size)
        val predecessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = scheduleNow,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        val expectedSuccessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = Calendar.getInstance().apply { timeInMillis = predecessorAt },
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        CleanupScheduleCoordinator.workInfoQueryOverrideForTesting = {
            error("scheduler discovery unavailable")
        }

        assertFalse(
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = context,
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
                completedOccurrenceAt = predecessorAt,
            )
        )
        assertEquals(
            generation,
            preferences.getString("cleanup_leftover_downloads_pending_generation", null),
        )
        assertEquals(
            expectedSuccessorAt,
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L),
        )

        CleanupScheduleCoordinator.workInfoQueryOverrideForTesting = null
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun successorDebtPersistenceFailureUsesLiveFallbackUntilItCanPersist() = runBlocking {
        val scheduleNow = Calendar.getInstance()
        CleanupScheduleCoordinator.nowProviderForTesting = {
            scheduleNow.clone() as Calendar
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        assertEquals(1, unfinishedCurrentWork().size)
        val predecessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = scheduleNow,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }

        assertFalse(
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = context,
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = anchorDay,
                completedOccurrenceAt = predecessorAt,
            )
        )
        assertNull(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
        )

        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun startupRecoveryDebtFailureRetainsCurrentProcessOwnership() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        assertTrue(awaitPreference(timeoutMs = 2_000L) { unfinishedCurrentWork().isEmpty() })
        preferences.edit()
            .remove("cleanup_leftover_downloads_pending_generation")
            .remove("cleanup_leftover_downloads_pending_cadence")
            .remove("cleanup_leftover_downloads_pending_anchor_day")
            .remove("cleanup_leftover_downloads_pending_occurrence_at")
            .commit()

        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }
        CleanupScheduleCoordinator.reconcile(context)

        assertNull(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
        )
        assertTrue(unfinishedCurrentWork().isEmpty())

        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            enqueueCalls.incrementAndGet()
            workManager.enqueueUniqueWork(name, policy, request)
        }
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                enqueueCalls.get() >= 1 && unfinishedCurrentWork().size == 1
            }
        )
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun restartReconstructsReplayOwnerForUnmatchedSuccessorDebt() = runBlocking {
        val scheduleNow = Calendar.getInstance()
        CleanupScheduleCoordinator.nowProviderForTesting = {
            scheduleNow.clone() as Calendar
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val anchorDay = preferences.getInt("cleanup_leftover_downloads_anchor_day", -1)
        val predecessor = unfinishedCurrentWork().single()
        val predecessorAt = CleanupSchedulePolicy.nextOccurrence(
            now = scheduleNow,
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        val successorAt = CleanupSchedulePolicy.nextOccurrence(
            now = Calendar.getInstance().apply { timeInMillis = predecessorAt },
            cadence = CleanupSchedulePolicy.DAILY,
            monthlyAnchorDay = anchorDay,
        ).timeInMillis
        preferences.edit()
            .putString("cleanup_leftover_downloads_pending_generation", generation)
            .putString("cleanup_leftover_downloads_pending_cadence", CleanupSchedulePolicy.DAILY)
            .putInt("cleanup_leftover_downloads_pending_anchor_day", anchorDay)
            .putLong("cleanup_leftover_downloads_pending_occurrence_at", successorAt)
            .commit()
        CleanupScheduleCoordinator.resetReplayOwnerForTesting()

        CleanupScheduleCoordinator.reconcile(context)
        assertEquals(
            successorAt,
            preferences.getLong("cleanup_leftover_downloads_pending_occurrence_at", -1L),
        )
        workManager.cancelWorkById(predecessor.id).result.get(20, TimeUnit.SECONDS)

        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        val repaired = unfinishedCurrentWork()
        assertEquals(1, repaired.size)
        assertTrue(repaired.single().tags.contains(occurrenceTag(generation, successorAt)))
    }

    @Test
    fun schedulerDiscoveryFailureDoesNotCancelOrReplacePossibleLiveWork() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val before = unfinishedCurrentWork().single()
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = TimeUnit.MINUTES.toMillis(1)
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            enqueueCalls.incrementAndGet()
            workManager.enqueueUniqueWork(name, policy, request)
        }
        CleanupScheduleCoordinator.workInfoQueryOverrideForTesting = {
            error("scheduler discovery unavailable")
        }

        CleanupScheduleCoordinator.reconcile(context)

        val after = unfinishedCurrentWork()
        assertEquals(listOf(before.id), after.map { it.id })
        assertEquals(0, enqueueCalls.get())
    }

    @Test
    fun disableCancelsScheduleAndFencesStaleSuccessor() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )

        assertFalse(
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = context,
                generation = "stale-generation",
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = 1,
            )
        )
        CleanupScheduleCoordinator.configure(context, null)
        assertTrue(awaitUnfinishedCount(0))
        assertFalse(
            CleanupScheduleCoordinator.scheduleSuccessor(
                context = context,
                generation = generation,
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = 1,
            )
        )
        assertTrue(unfinishedCurrentWork().isEmpty())
    }

    @Test
    fun disableRetiresPendingDebtBeforeLateEnqueueCompletion() = runBlocking {
        val first = ControlledOperation().also { controlledOperations += it }
        val second = ControlledOperation().also { controlledOperations += it }
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(1)
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, _ ->
            if (enqueueCalls.getAndIncrement() == 0) first else second
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )

        assertTrue(CleanupScheduleCoordinator.configure(context, null))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
        )
        assertEquals(1, enqueueCalls.get())

        // A late completion from the superseded operation cannot recreate
        // work or clear/mutate any newer authority.
        first.succeed()
        assertTrue(awaitUnfinishedCount(0))
        assertTrue(unfinishedCurrentWork().isEmpty())
        assertEquals(1, enqueueCalls.get())

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
        assertTrue(CleanupScheduleCoordinator.configure(context, null))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
        )
        second.fail(IllegalStateException("late superseded failure"))
        assertTrue(awaitUnfinishedCount(0))
        assertTrue(unfinishedCurrentWork().isEmpty())
        assertEquals(2, enqueueCalls.get())
    }

    @Test
    fun supersededReplayCannotRecreateOldCadenceWork() = runBlocking {
        val first = ControlledOperation().also { controlledOperations += it }
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 25L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 100L
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            if (enqueueCalls.getAndIncrement() == 0) {
                first
            } else {
                workManager.enqueueUniqueWork(name, policy, request)
            }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val firstGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY))
        val secondGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        assertTrue(firstGeneration != secondGeneration)

        // Complete the superseded operation after the new generation is
        // authoritative. Only the weekly replay may establish work.
        first.succeed()
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        val current = unfinishedCurrentWork()
        assertEquals(1, current.size)
        assertTrue(current.single().tags.contains(cadenceTag(CleanupSchedulePolicy.WEEKLY)))
        assertTrue(current.single().tags.contains(generationTag(secondGeneration)))
        assertTrue(current.single().tags.none { it.contains(firstGeneration) })
    }

    @Test
    fun successfulRunAppendsExactlyOneCalendarSuccessor() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val successor = awaitWork(timeoutMs = 30_000L) { infos ->
            cleanupRuns.get() >= 1 && infos.any { info ->
                info.tags.contains(generationTag(generation)) &&
                    info.tags.any { tag -> tag.startsWith("${CleanupScheduleCoordinator.TAG}_occurrence_") } &&
                    info.state == WorkInfo.State.ENQUEUED
            }
        }

        assertTrue(successor.any { it.tags.contains(cadenceTag(CleanupSchedulePolicy.DAILY)) })
        assertEquals(1, unfinishedCurrentWork().size)
        assertTrue(cleanupRuns.get() >= 1)
    }

    @Test
    fun retryDoesNotPublishSuccessorBeforeCleanupEventuallySucceeds() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val attempts = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            if (attempts.getAndIncrement() == 0) {
                throw IllegalStateException("deterministic cleanup retry")
            }
        }

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        awaitWork(timeoutMs = 30_000L) { infos ->
            infos.any { it.runAttemptCount >= 1 && it.state == WorkInfo.State.ENQUEUED }
        }
        assertEquals(1, unfinishedCurrentWork().size)

        awaitWork(timeoutMs = 40_000L) { infos ->
            infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0 } &&
                infos.any { it.state == WorkInfo.State.SUCCEEDED }
        }
        assertEquals(1, unfinishedCurrentWork().size)
        assertEquals(2, attempts.get())
    }

    @Test
    fun staleWorkerCannotResurrectOldCadenceAfterChange() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocations = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            if (invocations.getAndIncrement() == 0) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "old cleanup did not release" }
            }
        }

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val reconfigure = async(Dispatchers.Default) {
            CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY)
        }
        Thread.sleep(100L)
        assertFalse(reconfigure.isCompleted)
        release.countDown()
        assertTrue(reconfigure.await())

        val weekly = awaitWork(timeoutMs = 30_000L) { infos ->
            infos.any { info ->
                info.state == WorkInfo.State.ENQUEUED &&
                    info.tags.contains(cadenceTag(CleanupSchedulePolicy.WEEKLY))
            }
        }
        assertTrue(weekly.any { it.tags.contains(cadenceTag(CleanupSchedulePolicy.WEEKLY)) })
        assertTrue(
            unfinishedCurrentWork().all {
                it.tags.contains(cadenceTag(CleanupSchedulePolicy.WEEKLY))
            }
        )
    }

    @Test
    fun staleWorkerCannotResurrectAfterDisable() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "cleanup did not release" }
        }

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val disable = async(Dispatchers.Default) {
            CleanupScheduleCoordinator.configure(context, null)
        }
        Thread.sleep(100L)
        assertFalse(disable.isCompleted)
        release.countDown()
        assertTrue(disable.await())

        awaitUnfinishedCount(0, timeoutMs = 30_000L)
        assertTrue(unfinishedCurrentWork().isEmpty())
    }

    @Test
    fun staleOccurrenceIsRejectedBeforeCleanupEffectAfterDisable() = runBlocking {
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val oldGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        assertTrue(CleanupScheduleCoordinator.configure(context, null))

        // WorkManager cancellation is asynchronous.  Submit an old-generation
        // request directly so the real worker must prove current authority at
        // its effect boundary rather than relying on cancellation timing.
        val staleRequest = OneTimeWorkRequestBuilder<CleanUpLeftoverDownloads>()
            .setInputData(
                workDataOf(
                    CleanupScheduleCoordinator.INPUT_GENERATION to oldGeneration,
                    CleanupScheduleCoordinator.INPUT_CADENCE to CleanupSchedulePolicy.DAILY,
                )
            )
            .build()
        workManager.enqueue(staleRequest).result.get(20, TimeUnit.SECONDS)

        val terminal = awaitWorkById(staleRequest.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertTrue(terminal.outputData.getBoolean("cleanup_schedule_stale", false))
        assertEquals(0, cleanupRuns.get())
    }

    @Test
    fun disableBeforeDestructiveAdmissionFencesPausedOldWorker() = runBlocking {
        assertPausedWorkerCannotEnterAfterTransition(null)
    }

    @Test
    fun enabledSupersessionBeforeDestructiveAdmissionFencesPausedOldWorker() = runBlocking {
        assertPausedWorkerCannotEnterAfterTransition(CleanupSchedulePolicy.WEEKLY)
    }

    @Test
    fun settingsTransitionDoesNotBlockMainWhileCleanupEffectIsActive() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "cleanup did not release" }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(entered.await(10, TimeUnit.SECONDS))

        val applied = Collections.synchronizedList(mutableListOf<String>())
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = CleanupSchedulePreferenceController(
                context = context,
                scope = controllerScope,
                applyPersistedCadence = applied::add,
            )
            val callbackReturned = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                controller.request(CleanupSchedulePolicy.WEEKLY)
                callbackReturned.countDown()
            }

            assertTrue(callbackReturned.await(2, TimeUnit.SECONDS))
            assertEquals(
                CleanupSchedulePolicy.DAILY,
                preferences.getString("cleanup_leftover_downloads", null),
            )

            release.countDown()
            assertTrue(
                awaitPreference(timeoutMs = 5_000L) {
                    preferences.getString("cleanup_leftover_downloads", null) ==
                        CleanupSchedulePolicy.WEEKLY
                }
            )
            assertTrue(
                awaitPreference(timeoutMs = 5_000L) {
                    synchronized(applied) {
                        CleanupSchedulePolicy.WEEKLY in applied
                    }
                }
            )
        } finally {
            release.countDown()
            controllerScope.cancel()
        }
    }

    @Test
    fun settingsCommitFailureLeavesPreviousDurableCadenceVisible() = runBlocking {
        assertTrue(
            preferences.edit()
                .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                .commit()
        )
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { false }

        val applied = Collections.synchronizedList(mutableListOf<String>())
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = CleanupSchedulePreferenceController(
                context = context,
                scope = controllerScope,
                applyPersistedCadence = applied::add,
            )
            controller.request(CleanupSchedulePolicy.WEEKLY)

            assertTrue(
                awaitPreference(timeoutMs = 5_000L) {
                    synchronized(applied) {
                        CleanupSchedulePolicy.DAILY in applied
                    }
                }
            )
            assertEquals(
                CleanupSchedulePolicy.DAILY,
                preferences.getString("cleanup_leftover_downloads", null),
            )
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun enabledAuthorityAndInitialDebtCommitBeforeEnqueueAttempt() = runBlocking {
        val operation = ControlledOperation().also { controlledOperations += it }
        val authorityCommits = AtomicInteger(0)
        var enqueueSawMatchingDebt = false
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = { editor ->
            authorityCommits.incrementAndGet()
            editor.commit()
        }
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, request ->
            val generation = request.workSpec.input.getString(
                CleanupScheduleCoordinator.INPUT_GENERATION,
            )
            val cadence = request.workSpec.input.getString(
                CleanupScheduleCoordinator.INPUT_CADENCE,
            )
            val anchorDay = request.workSpec.input.getInt(
                CleanupScheduleCoordinator.INPUT_MONTHLY_ANCHOR_DAY,
                -1,
            )
            val occurrenceAt = request.workSpec.input.getLong(
                CleanupScheduleCoordinator.INPUT_OCCURRENCE_AT,
                -1L,
            )
            enqueueSawMatchingDebt =
                generation == preferences.getString(
                    "cleanup_leftover_downloads_pending_generation",
                    null,
                ) &&
                    cadence == preferences.getString(
                        "cleanup_leftover_downloads_pending_cadence",
                        null,
                    ) &&
                    anchorDay == preferences.getInt(
                        "cleanup_leftover_downloads_pending_anchor_day",
                        -1,
                    ) &&
                    occurrenceAt == preferences.getLong(
                        "cleanup_leftover_downloads_pending_occurrence_at",
                        -1L,
                    )
            operation
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertEquals(1, authorityCommits.get())
        assertTrue(enqueueSawMatchingDebt)
    }

    @Test
    fun rapidSettingsRequestsLeaveLatestCadenceAuthoritative() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        assertTrue(
            preferences.edit()
                .putString("cleanup_leftover_downloads", CleanupSchedulePolicy.DAILY)
                .commit()
        )
        val applied = Collections.synchronizedList(mutableListOf<String>())
        val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = CleanupSchedulePreferenceController(
                context = context,
                scope = controllerScope,
                applyPersistedCadence = applied::add,
            )

            Handler(Looper.getMainLooper()).post {
                controller.request(CleanupSchedulePolicy.WEEKLY)
                controller.request("")
            }

            assertTrue(
                awaitPreference(timeoutMs = 5_000L) {
                    preferences.getString("cleanup_leftover_downloads", null).orEmpty().isEmpty()
                }
            )
            assertTrue(
                awaitPreference(timeoutMs = 5_000L) {
                    synchronized(applied) {
                        applied.lastOrNull().orEmpty().isEmpty()
                    }
                }
            )
        } finally {
            controllerScope.cancel()
        }
    }

    @Test
    fun finalCleanupFailurePreservesFutureOccurrenceAndReportsFailure() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val attempts = AtomicInteger(0)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            attempts.incrementAndGet()
            throw IllegalStateException("deterministic final cleanup failure")
        }

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        val infos = awaitWork(timeoutMs = 140_000L) { current ->
            attempts.get() >= CleanUpLeftoverDownloads.MAX_ATTEMPTS &&
                current.any { it.state == WorkInfo.State.ENQUEUED &&
                    it.tags.any { tag -> tag.startsWith("${CleanupScheduleCoordinator.TAG}_occurrence_") } } &&
                current.any { it.state == WorkInfo.State.SUCCEEDED &&
                    it.outputData.getBoolean("cleanup_failure", false) }
        }

        assertTrue(attempts.get() >= CleanUpLeftoverDownloads.MAX_ATTEMPTS)
        assertTrue(infos.any { it.state == WorkInfo.State.SUCCEEDED &&
            it.outputData.getBoolean("cleanup_failure", false) })
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun successorDebtReplaySurvivesWorkerRetryExhaustion() = runBlocking {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 500L
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 10L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 20L
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting = 10L
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = { }
        val successorEnqueueAttempts = AtomicInteger(0)

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val generation = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            if (successorEnqueueAttempts.getAndIncrement() < CleanUpLeftoverDownloads.MAX_ATTEMPTS) {
                throw IllegalStateException("successor enqueue failure")
            }
            workManager.enqueueUniqueWork(name, policy, request)
        }
        val infos = awaitWork(timeoutMs = 60_000L) { current ->
            current.any { info ->
                info.state == WorkInfo.State.FAILED &&
                    info.tags.contains(generationTag(generation))
            } && current.any { info ->
                info.state == WorkInfo.State.ENQUEUED &&
                    info.tags.contains(generationTag(generation)) &&
                    info.tags.any { tag ->
                        tag.startsWith("${CleanupScheduleCoordinator.TAG}_occurrence_")
                    }
            }
        }

        assertTrue(infos.any { info -> info.state == WorkInfo.State.FAILED })
        assertTrue(successorEnqueueAttempts.get() > CleanUpLeftoverDownloads.MAX_ATTEMPTS)
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun asynchronousEnqueueFailureLeavesDebtForStartupReconciliation() = runBlocking {
        val first = ControlledOperation().also { controlledOperations += it }
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 25L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 100L
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            if (enqueueCalls.getAndIncrement() == 0) {
                first
            } else {
                workManager.enqueueUniqueWork(name, policy, request)
            }
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
        first.fail(IllegalStateException("async enqueue failure"))
        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        assertTrue(enqueueCalls.get() >= 2)
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun synchronousEnqueueFailureReplaysInProcessWithoutManualReconcile() = runBlocking {
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = 25L
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = 100L
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { name, policy, request ->
            if (enqueueCalls.getAndIncrement() == 0) {
                throw IllegalStateException("synchronous enqueue failure")
            }
            workManager.enqueueUniqueWork(name, policy, request)
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
        assertTrue(enqueueCalls.get() >= 2)
        assertEquals(1, unfinishedCurrentWork().size)
    }

    @Test
    fun asynchronousEnqueueAcceptanceClearsMatchingDebt() = runBlocking {
        val operation = ControlledOperation().also { controlledOperations += it }
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, _ -> operation }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
        operation.succeed()
        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
    }

    @Test
    fun staleAcceptanceCannotClearNewGenerationDebtAfterCadenceChange() = runBlocking {
        val first = ControlledOperation().also { controlledOperations += it }
        val second = ControlledOperation().also { controlledOperations += it }
        val enqueueCalls = AtomicInteger(0)
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, _ ->
            if (enqueueCalls.getAndIncrement() == 0) first else second
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val firstGeneration = preferences.getString("cleanup_leftover_downloads_generation", null)
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY))
        val secondGeneration = preferences.getString("cleanup_leftover_downloads_generation", null)
        assertTrue(firstGeneration != secondGeneration)

        first.succeed()
        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == secondGeneration
            }
        )
        second.succeed()
        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
    }

    private fun clearSchedulePreferences() {
        preferences.edit()
            .remove("cleanup_leftover_downloads")
            .remove("cleanup_leftover_downloads_generation")
            .remove("cleanup_leftover_downloads_anchor_day")
            .remove("cleanup_leftover_downloads_pending_generation")
            .remove("cleanup_leftover_downloads_pending_cadence")
            .remove("cleanup_leftover_downloads_pending_anchor_day")
            .remove("cleanup_leftover_downloads_pending_occurrence_at")
            .commit()
    }

    private fun clearTestSeams() {
        CleanupScheduleCoordinator.resetReplayOwnerForTesting()
        CleanupScheduleCoordinator.workManagerForTesting = null
        CleanupScheduleCoordinator.nowProviderForTesting = null
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = null
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = null
        CleanupScheduleCoordinator.enqueueOverrideForTesting = null
        CleanupScheduleCoordinator.workInfoQueryOverrideForTesting = null
        CleanupScheduleCoordinator.authorityCommitOverrideForTesting = null
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = null
        CleanupScheduleCoordinator.replayMaxDelayOverrideForTesting = null
        CleanupScheduleCoordinator.retryBackoffDelayOverrideForTesting = null
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = null
        CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting = null
    }

    private suspend fun assertPausedWorkerCannotEnterAfterTransition(
        replacementCadence: String?,
    ) = coroutineScope {
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupRuns = AtomicInteger(0)
        CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting = {
            admitted.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "paused cleanup did not release" }
        }
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {
            cleanupRuns.incrementAndGet()
        }

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        val oldGeneration = requireNotNull(
            preferences.getString("cleanup_leftover_downloads_generation", null)
        )
        val staleRequest = OneTimeWorkRequestBuilder<CleanUpLeftoverDownloads>()
            .setInputData(
                workDataOf(
                    CleanupScheduleCoordinator.INPUT_GENERATION to oldGeneration,
                    CleanupScheduleCoordinator.INPUT_CADENCE to CleanupSchedulePolicy.DAILY,
                )
            )
            .build()
        workManager.enqueue(staleRequest).result.get(20, TimeUnit.SECONDS)
        assertTrue(admitted.await(10, TimeUnit.SECONDS))

        val transition = async(Dispatchers.Default) {
            CleanupScheduleCoordinator.configure(context, replacementCadence)
        }
        assertTrue(transition.await())
        if (replacementCadence == null) {
            assertEquals(
                "",
                preferences.getString("cleanup_leftover_downloads", null),
            )
        } else {
            assertEquals(
                replacementCadence,
                preferences.getString("cleanup_leftover_downloads", null),
            )
        }

        release.countDown()
        val terminal = awaitWorkById(staleRequest.id) { info ->
            info.state == WorkInfo.State.SUCCEEDED ||
                info.state == WorkInfo.State.FAILED ||
                info.state == WorkInfo.State.CANCELLED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, terminal.state)
        assertTrue(terminal.outputData.getBoolean("cleanup_schedule_stale", false))
        assertEquals(0, cleanupRuns.get())
    }

    private fun unfinishedCurrentWork(): List<WorkInfo> = workManager
        .getWorkInfosForUniqueWork(CleanupScheduleCoordinator.WORK_NAME)
        .get(20, TimeUnit.SECONDS)
        .filter { it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.BLOCKED }

    private fun currentGenerationWork(generation: String): List<WorkInfo> = workManager
        .getWorkInfosForUniqueWork(CleanupScheduleCoordinator.WORK_NAME)
        .get(20, TimeUnit.SECONDS)
        .filter { it.tags.contains(generationTag(generation)) }

    private suspend fun awaitWork(
        timeoutMs: Long,
        predicate: (List<WorkInfo>) -> Boolean,
    ): List<WorkInfo> = withTimeout(timeoutMs) {
        while (true) {
            val infos = workManager.getWorkInfosForUniqueWork(
                CleanupScheduleCoordinator.WORK_NAME
            ).get(20, TimeUnit.SECONDS)
            if (predicate(infos)) return@withTimeout infos
            Thread.sleep(50L)
        }
        error("unreachable")
    }

    private suspend fun awaitWorkById(
        id: UUID,
        predicate: (WorkInfo) -> Boolean,
    ): WorkInfo = withTimeout(30_000L) {
        while (true) {
            val info = workManager.getWorkInfoById(id).get(20, TimeUnit.SECONDS)
            if (info != null && predicate(info)) return@withTimeout info
            Thread.sleep(50L)
        }
        error("unreachable")
    }

    private suspend fun awaitUnfinishedCount(
        expected: Int,
        timeoutMs: Long = 10_000L,
    ): Boolean {
        awaitWork(timeoutMs) { unfinishedCurrentWork().size == expected }
        return true
    }

    private suspend fun awaitPreference(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): Boolean = withTimeout(timeoutMs) {
        while (!predicate()) {
            Thread.sleep(25L)
        }
        true
    }

    private fun cadenceTag(cadence: String): String =
        "${CleanupScheduleCoordinator.TAG}_cadence_$cadence"

    private fun generationTag(generation: String): String =
        "${CleanupScheduleCoordinator.TAG}_generation_$generation"

    private fun occurrenceTag(generation: String, occurrenceAt: Long): String =
        "${CleanupScheduleCoordinator.TAG}_occurrence_${generation}_$occurrenceAt"

    private class ControlledOperation : Operation {
        private val state = MutableLiveData<Operation.State>(Operation.IN_PROGRESS)
        private val result = SettableFuture.create<Operation.State.SUCCESS>()

        override fun getState(): LiveData<Operation.State> = state

        override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = result

        fun succeed() {
            state.postValue(Operation.SUCCESS)
            result.set(Operation.SUCCESS)
        }

        fun fail(error: Throwable) {
            state.postValue(Operation.State.FAILURE(error))
            result.setException(error)
        }
    }
}
