package com.ireum.ytdl.work

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Operation
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun startupReconciliationRepairsMissingChainWithoutDuplicatingIt() {
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
        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY)
        release.countDown()

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
        CleanupScheduleCoordinator.configure(context, null)
        release.countDown()

        awaitUnfinishedCount(0, timeoutMs = 30_000L)
        assertTrue(unfinishedCurrentWork().isEmpty())
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
    fun asynchronousEnqueueFailureLeavesDebtForStartupReconciliation() = runBlocking {
        val first = ControlledOperation().also { controlledOperations += it }
        CleanupScheduleCoordinator.enqueueOverrideForTesting = { _, _, _ -> first }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = TimeUnit.DAYS.toMillis(2)

        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(
            preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                ?.isNotBlank() == true
        )
        first.fail(IllegalStateException("async enqueue failure"))
        assertTrue(
            awaitPreference(timeoutMs = 2_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null)
                    ?.isNotBlank() == true
            }
        )

        CleanupScheduleCoordinator.enqueueOverrideForTesting = null
        CleanupScheduleCoordinator.reconcile(context)
        assertTrue(
            awaitPreference(timeoutMs = 5_000L) {
                preferences.getString("cleanup_leftover_downloads_pending_generation", null) == null
            }
        )
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
        CleanupScheduleCoordinator.workManagerForTesting = null
        CleanupScheduleCoordinator.nowProviderForTesting = null
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = null
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = null
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = null
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
