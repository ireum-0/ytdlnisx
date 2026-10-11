package com.ireum.ytdl.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.database.BackupRestoreParser
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreOperationStore
import com.ireum.ytdl.database.RestoreOutcome
import com.ireum.ytdl.database.models.RestorePlan
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.receiver.ObserveRetryDecisionReceiver
import androidx.preference.PreferenceManager
import androidx.work.Data
import androidx.work.WorkInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Production-wiring proof for ordinary handoff-carrier final mutations. */
@RunWith(AndroidJUnit4::class)
class F11HandoffCarrierMutationAdmissionProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    @get:Rule
    val currentTest = TestName()
    private var resetOneShot: ResetOneShotDiagnostic? = null

    @Before
    fun setUp(): Unit = runBlocking {
        resetOneShot = if (currentTest.methodName == "schedulerResetWinsBeforeOrdinaryCarrierMutation") {
            ResetOneShotDiagnostic()
        } else null
        resetOneShot?.event("setup_started")
        context = ApplicationProvider.getApplicationContext()
        resetOneShot?.snapshot("fixture_before_cancel", context, resetHookState())
        resetOneShot?.event("workmanager_cancel_started")
        WorkManager.getInstance(context).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        resetOneShot?.event("workmanager_cancel_completed")
        resetOneShot?.event("fixture_recovery_started")
        runCatching { RestoreTransactionCoordinator.recover(context) }
            .onSuccess { resetOneShot?.fixtureRecovery?.set(it); resetOneShot?.event("fixture_recovery_returned") }
            .onFailure { resetOneShot?.fixtureFailure?.set(it); resetOneShot?.event("fixture_recovery_threw") }
        resetOneShot?.snapshot("fixture_after_recovery_before_delete", context, resetHookState())
        val rootDeleted = RestoreOperationStore.root(context).deleteRecursively()
        resetOneShot?.event("fixture_root_delete_result=$rootDeleted")
        resetOneShot?.snapshot("fixture_after_delete", context, resetHookState())
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        resetOneShot?.event("setup_completed")
        resetOneShot?.flush("setup_completed")
    }

    @After
    fun tearDown(): Unit = runBlocking {
        resetOneShot?.event("teardown_started")
        if (::context.isInitialized) {
            resetOneShot?.snapshot("teardown_before_hook_clear", context, resetHookState())
        }
        try {
            clearHooks()
            resetOneShot?.event("hooks_cleared")
            WorkManagerHandoffRecovery.clearForTesting()
            if (::database.isInitialized) database.close()
            resetOneShot?.event("teardown_cancel_started")
            WorkManager.getInstance(context).cancelAllWork().result.get(20, TimeUnit.SECONDS)
            resetOneShot?.event("teardown_cancel_completed")
            val rootDeleted = RestoreOperationStore.root(context).deleteRecursively()
            resetOneShot?.event("teardown_root_delete_result=$rootDeleted")
            resetOneShot?.event("teardown_completed")
        } finally {
            if (::context.isInitialized) {
                resetOneShot?.snapshot("teardown_finished", context, resetHookState())
            } else {
                resetOneShot?.flush("teardown_context_unavailable")
            }
        }
    }

    @Test
    fun hardSubOrdinaryCarrierMutationCompletesBeforeRestorePublication(): Unit = runBlocking {
        ordinaryWriterWins { WorkManagerHandoffRecovery.prepareHardSub(context) }
    }

    @Test
    fun schedulerOrdinaryCarrierMutationCompletesBeforeRestorePublication(): Unit = runBlocking {
        ordinaryWriterWins(schedulerSettingsReset = true) {
            WorkManagerHandoffRecovery.prepareSchedulerBoundary(
                context,
                WorkManagerHandoffCarrier.START_BOUNDARY,
                System.currentTimeMillis() + 60_000L,
            )
        }
    }

    @Test
    fun observeRetryOrdinaryCarrierMutationCompletesBeforeRestorePublication(): Unit = runBlocking {
        insertObserveSource(701L)
        val fingerprint = WorkManagerHandoffRecovery.observeConfigFingerprint(
            requireNotNull(database.observeSourcesDao.getByIDOrNull(701L)),
        )
        ordinaryWriterWins {
            WorkManagerHandoffRecovery.prepareObserveRetryDownload(
                context,
                sourceId = 701L,
                sourceConfigurationGeneration = 1L,
                confirmedUrl = "https://example.com/f11-observe-retry",
                configFingerprint = fingerprint,
            )
        }
    }

    @Test
    fun hardSubResetWinsBeforeOrdinaryCarrierMutation(): Unit = runBlocking {
        resetWins { WorkManagerHandoffRecovery.prepareHardSub(context) }
    }

    @Test
    fun schedulerResetWinsBeforeOrdinaryCarrierMutation(): Unit = runBlocking {
        resetWins {
            WorkManagerHandoffRecovery.prepareSchedulerBoundary(
                context,
                WorkManagerHandoffCarrier.START_BOUNDARY,
                System.currentTimeMillis() + 60_000L,
            )
        }
    }

    @Test
    fun observeRetryResetWinsBeforeOrdinaryCarrierMutation(): Unit = runBlocking {
        insertObserveSource(702L)
        val fingerprint = WorkManagerHandoffRecovery.observeConfigFingerprint(
            requireNotNull(database.observeSourcesDao.getByIDOrNull(702L)),
        )
        resetWins(diagnosePublication = true) {
            WorkManagerHandoffRecovery.prepareObserveRetryDownload(
                context,
                sourceId = 702L,
                sourceConfigurationGeneration = 1L,
                confirmedUrl = "https://example.com/f11-observe-retry-reset-wins",
                configFingerprint = fingerprint,
            )
        }
    }

    @Test
    fun startupRecoveryRetiresStaleObserveRetryWithoutReplacingNewGenerationWork(): Unit = runBlocking {
        val sourceId = 703L
        insertObserveSource(
            sourceId,
            everyTime = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(5),
        )
        val oldSource = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        val oldFingerprint = WorkManagerHandoffRecovery.observeConfigFingerprint(oldSource)
        val handoffId = WorkManagerHandoffRecovery.prepareObserveRetryDownload(
            context,
            sourceId = sourceId,
            sourceConfigurationGeneration = oldSource.configurationGeneration,
            confirmedUrl = "https://example.com/f11-stale-observe-retry",
            configFingerprint = oldFingerprint,
        )
        val staleCarrier = requireNotNull(database.workManagerHandoffCarrierDao.get(handoffId))
        val staleRequest = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
            .setId(java.util.UUID.fromString(staleCarrier.requestId))
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS)
            .setInputData(
                Data.Builder()
                    .putLong(ObserveSourceWorker.INPUT_SOURCE_ID, sourceId)
                    .putLong(
                        ObserveSourceWorker.INPUT_CONFIGURATION_GENERATION,
                        oldSource.configurationGeneration,
                    )
                    .build(),
            )
            .addTag(
                ObserveSourceWorker.configurationGenerationTag(oldSource.configurationGeneration),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("OBSERVE$sourceId", ExistingWorkPolicy.REPLACE, staleRequest)
            .result.get(20, TimeUnit.SECONDS)

        val repository = ObserveSourcesRepository(
            database.observeSourcesDao,
            WorkManager.getInstance(context),
            PreferenceManager.getDefaultSharedPreferences(context),
            context,
        )
        assertTrue(repository.reconfigure(oldSource.copy(name = "generation 2"), resetProcessedLinks = false))
        val currentSource = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(oldSource.configurationGeneration + 1L, currentSource.configurationGeneration)
        val currentRequest = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS)
            .setInputData(
                Data.Builder()
                    .putLong(ObserveSourceWorker.INPUT_SOURCE_ID, sourceId)
                    .putLong(
                        ObserveSourceWorker.INPUT_CONFIGURATION_GENERATION,
                        currentSource.configurationGeneration,
                    )
                    .build(),
            )
            .addTag(
                ObserveSourceWorker.configurationGenerationTag(currentSource.configurationGeneration),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("OBSERVE$sourceId", ExistingWorkPolicy.REPLACE, currentRequest)
            .result.get(20, TimeUnit.SECONDS)
        val beforeRecovery = requireNotNull(
            WorkManager.getInstance(context).getWorkInfoById(currentRequest.id).get(20, TimeUnit.SECONDS),
        )
        assertEquals(WorkInfo.State.ENQUEUED, beforeRecovery.state)
        assertTrue(
            ObserveSourceWorker.configurationGenerationTag(currentSource.configurationGeneration) in
                beforeRecovery.tags,
        )

        WorkManagerHandoffRecovery.reconcile(context)

        assertNull(database.workManagerHandoffCarrierDao.get(handoffId))
        val afterRecovery = requireNotNull(
            WorkManager.getInstance(context).getWorkInfoById(currentRequest.id).get(20, TimeUnit.SECONDS),
        )
        assertEquals(WorkInfo.State.ENQUEUED, afterRecovery.state)
        assertEquals(beforeRecovery.id, afterRecovery.id)
        // WorkManager may prune terminal WorkSpecs; an absent stale request cannot execute.
        val staleWorkAfterRecovery = WorkManager.getInstance(context)
            .getWorkInfoById(java.util.UUID.fromString(staleCarrier.requestId))
            .get(20, TimeUnit.SECONDS)
        assertTrue(
            staleWorkAfterRecovery == null || staleWorkAfterRecovery.state == WorkInfo.State.CANCELLED,
        )
    }

    private suspend fun ordinaryWriterWins(
        schedulerSettingsReset: Boolean = false,
        create: () -> String,
    ) {
        val scope = CoroutineScope(currentCoroutineContext())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val events = AtomicInteger(0)
        var handoffId: String? = null
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                assertTrue(events.compareAndSet(0, 1))
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = {
            assertEquals(2, events.get())
            events.compareAndSet(2, 3)
        }

        val writer = scope.async(Dispatchers.IO) {
            handoffId = create()
            assertTrue(events.compareAndSet(1, 2))
        }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val reset = scope.async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(context, plan())
        }
        assertFalse(reset.isCompleted)
        release.countDown()
        writer.await()
        val resetOutcome = reset.await()
        assertTrue(resetOutcome is RestoreOutcome.Completed)
        assertEquals(3, events.get())
        val persistedId = requireNotNull(handoffId)
        if (schedulerSettingsReset) {
            assertRestoredSchedulerAuthority(
                persistedId,
                (resetOutcome as RestoreOutcome.Completed).operationId,
            )
        } else {
            assertTrue(database.workManagerHandoffCarrierDao.get(persistedId) != null)
        }
    }

    private suspend fun assertRestoredSchedulerAuthority(oldHandoffId: String, resetOperationId: String) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals("00:00", preferences.getString("schedule_start", null))
        assertEquals("05:00", preferences.getString("schedule_end", null))
        assertTrue(preferences.contains("use_scheduler"))
        assertFalse(preferences.getBoolean("use_scheduler", true))
        val restored = requireNotNull(SchedulerSettingsTransitionCoordinator.readForTesting(context))
        assertEquals(resetOperationId, restored.id)
        assertEquals(resetOperationId, restored.restoreOperationId)
        assertEquals(SchedulerSettingsTransitionCoordinator.Kind.RESTORE, restored.checkedKind())
        assertEquals("COMPLETE", restored.phase)
        assertEquals("00:00", restored.targetScheduleStart)
        assertEquals("05:00", restored.targetScheduleEnd)
        assertFalse(restored.targetUseScheduler)
        assertTrue(restored.priorOwnersRevoked)
        assertFalse(restored.startPublished)
        assertFalse(restored.endPublished)
        assertNull(database.workManagerHandoffCarrierDao.get(oldHandoffId))
        assertTrue(database.workManagerHandoffCarrierDao.getOutstanding().none {
            it.kind == WorkManagerHandoffCarrier.SCHEDULE_START ||
                it.kind == WorkManagerHandoffCarrier.SCHEDULE_END
        })
        for (name in listOf("scheduled_download_start", "scheduled_download_end")) {
            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name)
                .get(20, TimeUnit.SECONDS)
            assertTrue(work.all { it.state.isFinished })
        }
    }

    private suspend fun resetWins(
        diagnosePublication: Boolean = false,
        create: () -> String,
    ) {
        val oneShot = resetOneShot
        val publicationEntered = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val ordinaryEntered = CountDownLatch(1)
        val releaseOrdinary = CountDownLatch(1)
        val first = AtomicBoolean(true)
        lateinit var writer: Deferred<Result<String>>
        val writerScope = CoroutineScope(currentCoroutineContext())
        // The blocking producer uses runBlocking on this exact caller thread.
        // Background reconcilers must not consume this writer's test latch.
        val ordinaryWriterThread = AtomicReference<Thread?>(null)
        oneShot?.parent?.set(currentCoroutineContext()[kotlinx.coroutines.Job])
        oneShot?.parent?.get()?.invokeOnCompletion { cause ->
            oneShot?.parentCause?.set(cause)
            oneShot?.event("parent_completed")
        }
        val diagnosticStartedAt = android.os.SystemClock.elapsedRealtime()
        val beforeActiveReached = AtomicBoolean(false)
        val diagnosticPhase = AtomicReference("reset_not_started")
        val resetOutcome = AtomicReference<RestoreOutcome?>(null)
        val resetException = AtomicReference<Throwable?>(null)
        val resetReference = AtomicReference<Deferred<RestoreOutcome>?>(null)
        fun recordDiagnostic(event: String) {
            if (diagnosePublication) {
                recordResetPublicationDiagnostic(
                    event, diagnosticStartedAt, diagnosticPhase.get(),
                    beforeActiveReached.get(), publicationEntered.count == 0L,
                    resetReference.get()?.isCompleted ?: false,
                    resetOutcome.get(), resetException.get(),
                )
            }
        }
        if (diagnosePublication) {
            RestoreTransactionCoordinator.beforeActivePublicationForTesting = {
                beforeActiveReached.set(true)
                diagnosticPhase.set("before_active_publication")
                recordDiagnostic("before_active_publication")
            }
            RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
                diagnosticPhase.set("prepared_before_quiescence")
                recordDiagnostic("prepared_before_quiescence")
            }
        }
        if (oneShot != null) {
            val priorBeforeActive = RestoreTransactionCoordinator.beforeActivePublicationForTesting
            val priorPrepared = RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting
            oneShot.priorHooks.set(listOf(priorBeforeActive, priorPrepared))
            RestoreTransactionCoordinator.beforeActivePublicationForTesting = {
                beforeActiveReached.set(true)
                oneShot.event("before_active_publication_hook")
                try {
                    priorBeforeActive?.invoke()
                } catch (failure: Throwable) {
                    oneShot.hookFailure.compareAndSet(null, failure)
                    oneShot.event("prior_before_active_hook_threw")
                    throw failure
                }
            }
            RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
                oneShot.event("prepared_before_quiescence_hook")
                try {
                    priorPrepared?.invoke()
                } catch (failure: Throwable) {
                    oneShot.hookFailure.compareAndSet(null, failure)
                    oneShot.event("prior_prepared_hook_threw")
                    throw failure
                }
            }
        }
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            oneShot?.event("ordinary_authority_hook")
            if (Thread.currentThread() === ordinaryWriterThread.get() &&
                first.compareAndSet(true, false)
            ) {
                ordinaryEntered.countDown()
                oneShot?.event("ordinary_entered_countdown")
                check(releaseOrdinary.await(20, TimeUnit.SECONDS))
                oneShot?.event("ordinary_release_observed")
            }
        }
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = {
            diagnosticPhase.set("publication_authority_acquired")
            oneShot?.publicationCalls?.incrementAndGet()
            oneShot?.event("publication_authority_hook")
            publicationEntered.countDown()
            oneShot?.event("publication_latch_countdown")
            recordDiagnostic("publication_authority_acquired")
            writer = writerScope.async(Dispatchers.IO) {
                writerStarted.countDown()
                oneShot?.event("ordinary_writer_body_started")
                runCatching {
                    val thread = Thread.currentThread()
                    ordinaryWriterThread.set(thread)
                    try {
                        create()
                    } finally {
                        ordinaryWriterThread.compareAndSet(thread, null)
                    }
                }.also {
                    oneShot?.event("ordinary_writer_result_success=${it.isSuccess} exception=${it.exceptionOrNull()?.javaClass?.name}")
                }
            }
        }

        oneShot?.expectedHooks?.set(resetHookState())
        oneShot?.event("hooks_registered")
        recordDiagnostic("before_reset_launch")
        oneShot?.event("before_reset_launch")
        val reset = writerScope.async(Dispatchers.IO) {
            oneShot?.bodyEntered?.set(true)
            oneShot?.event("reset_body_entered")
            diagnosticPhase.set("begin_entered")
            recordDiagnostic("begin_entered")
            try {
                oneShot?.event("plan_construction_started")
                val resetPlan = plan()
                oneShot?.event("plan_construction_completed")
                oneShot?.event("begin_invocation_attempted")
                RestoreTransactionCoordinator.begin(context, resetPlan).also {
                    oneShot?.outcome?.set(it)
                    oneShot?.event("begin_returned")
                    resetOutcome.set(it)
                    diagnosticPhase.set("begin_returned")
                    recordDiagnostic("begin_returned")
                }
            } catch (failure: Throwable) {
                oneShot?.failure?.set(failure)
                oneShot?.event("reset_body_threw")
                resetException.set(failure)
                diagnosticPhase.set("begin_threw")
                recordDiagnostic("begin_threw")
                throw failure
            }
        }
        resetReference.set(reset)
        oneShot?.reset?.set(reset)
        oneShot?.event("deferred_created")
        if (oneShot != null) {
            reset.invokeOnCompletion { cause ->
                oneShot.completionCause.set(cause)
                oneShot.event("deferred_completed")
            }
        }
        try {
            val publicationObserved = publicationEntered.await(20, TimeUnit.SECONDS)
            val waitSummary = oneShot?.summary()
            oneShot?.event("publication_wait_result=$publicationObserved latchCount=${publicationEntered.count}")
            recordDiagnostic("publication_wait_finished")
            if (!publicationObserved) {
                oneShot?.snapshot("publication_timeout", context, resetHookState(), threads = true)
                oneShot?.snapshot("publication_timeout_second_snapshot_no_delay", context, resetHookState(), threads = true)
            }
            val assertionMessage = "Restore publication was not observed; see F11HandoffDiagnostic" +
                (waitSummary?.let { "; F11ResetOneShot waitReturn $it" } ?: "")
            assertTrue(assertionMessage, publicationObserved)
            oneShot?.event("publication_observed")
            assertTrue(writerStarted.await(20, TimeUnit.SECONDS))
            oneShot?.event("writer_start_observed")
            assertTrue(ordinaryEntered.await(20, TimeUnit.SECONDS))
            oneShot?.event("ordinary_admission_observed")
            releaseOrdinary.countDown()
            oneShot?.event("ordinary_release_countdown")
            val failure = writer.await().exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            oneShot?.event("ordinary_writer_assertion_passed")
            assertTrue(reset.await() is RestoreOutcome.Completed)
            oneShot?.event("reset_completed_assertion_passed")
            assertTrue(database.workManagerHandoffCarrierDao.getOutstanding().isEmpty())
            oneShot?.event("final_carrier_assertion_passed")
        } finally {
            oneShot?.snapshot("test_exit", context, resetHookState())
        }
    }

    /** Bounded observations only; no waits, cancellation, or file I/O in event(). */
    private class ResetOneShotDiagnostic {
        private data class Event(val sequence: Int, val nanos: Long, val thread: String, val name: String)
        private val events = java.util.concurrent.atomic.AtomicReferenceArray<Event>(128)
        private val sequence = AtomicInteger()
        val phase = AtomicReference("fixture_not_started")
        val bodyEntered = AtomicBoolean(false)
        val publicationCalls = AtomicInteger()
        val outcome = AtomicReference<RestoreOutcome?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val completionCause = AtomicReference<Throwable?>(null)
        val parentCause = AtomicReference<Throwable?>(null)
        val reset = AtomicReference<Deferred<RestoreOutcome>?>(null)
        val parent = AtomicReference<kotlinx.coroutines.Job?>(null)
        val fixtureRecovery = AtomicReference<RestoreOutcome?>(null)
        val fixtureFailure = AtomicReference<Throwable?>(null)
        val hookFailure = AtomicReference<Throwable?>(null)
        val priorHooks = AtomicReference<List<Any?>?>(null)
        val expectedHooks = AtomicReference<List<Any?>?>(null)
        private val testThread = Thread.currentThread().id

        fun event(name: String) {
            // Diagnostics must never replace a production exception or assertion.
            runCatching {
                val next = sequence.incrementAndGet()
                if (!name.startsWith("publication_wait_") && !name.startsWith("deferred_") &&
                    !name.startsWith("parent_")) phase.set(name)
                if (next <= events.length()) {
                    events.set(next - 1, Event(next, android.os.SystemClock.elapsedRealtimeNanos(),
                        Thread.currentThread().name, name))
                }
            }
        }

        fun summary(): String = runCatching {
            "phase=${phase.get()} started=${bodyEntered.get()}" +
                " reset=${jobState(reset.get())} parent=${jobState(parent.get())}" +
                " outcome=${outcome.get()?.javaClass?.simpleName}" +
                " exception=${failure.get()?.javaClass?.name}" +
                " publicationCalls=${publicationCalls.get()} sequence=${sequence.get()}"
        }.getOrDefault("diagnostic_summary_unavailable")

        fun snapshot(label: String, context: Context, hookState: List<Any?>, threads: Boolean = false) {
            runCatching {
                log("snapshot=$label monoNs=${android.os.SystemClock.elapsedRealtimeNanos()} ${summary()}")
                log("outcome=${outcome.get()} fixtureRecovery=${fixtureRecovery.get()}" +
                    " priorHooksPresent=${priorHooks.get()?.map { it != null }}" +
                    " hooksPresent=${hookState.map { it != null }}" +
                    " hooksMatch=${expectedHooks.get()?.zip(hookState)?.map { (expected, actual) -> expected === actual }}")
                listOf("reset" to failure.get(), "completion" to completionCause.get(),
                    "parent" to parentCause.get(), "fixture" to fixtureFailure.get(),
                    "priorHook" to hookFailure.get())
                    .forEach { (origin, error) ->
                        if (error != null) log("origin=$origin stack=${android.util.Log.getStackTraceString(error)}")
                    }
                if (threads) threadSnapshot(label)
                // Metadata only: never load a plan or take an active-pointer file lock.
                // No journal content read while an unfinished Reset may be stalled in file I/O.
                if (reset.get()?.isCompleted != false) {
                    val root = RestoreOperationStore.root(context)
                    val pointer = java.io.File(root, "active.json")
                    log("restoreRootExists=${root.exists()} activePointerExists=${pointer.exists()}" +
                        " activePointerBytes=${pointer.length()} filesystemSnapshotNonAtomic=true")
                } else {
                    log("filesystemSnapshot=SKIPPED_UNFINISHED_RESET_TO_AVOID_IO_STALL")
                }
                log("journalPhase=NOT_OBSERVED mutexOwner=NOT_OBSERVED coroutineSuspensionStack=NOT_OBSERVED")
            }.onFailure { log("snapshot=$label observationError=${it.javaClass.name}:${it.message}") }
            flush(label)
        }

        private fun threadSnapshot(label: String) {
            val all = Thread.getAllStackTraces()
            val relevant = all.entries.filter { (thread, stack) ->
                thread.id == testThread || thread.name.contains("DefaultDispatcher") ||
                    thread.name.contains("WM.") || thread.name.contains("WorkManager") ||
                    thread.name.contains("Instrumentation") || thread.name.contains("arch_disk") ||
                    thread.name.contains("pool-") || stack.any {
                        it.className.startsWith("com.ireum.ytdl.work.") ||
                            it.className.contains("RestoreTransactionCoordinator") ||
                            it.className.contains("RestoreMutationAdmission")
                    }
            }.sortedWith(compareBy<Map.Entry<Thread, Array<StackTraceElement>>> { (thread, stack) ->
                when {
                    thread.id == testThread -> 0
                    stack.any { it.className.contains("RestoreTransactionCoordinator") ||
                        it.className.contains("RestoreMutationAdmission") } -> 1
                    thread.name.contains("DefaultDispatcher") -> 2
                    else -> 3
                }
            }.thenBy { it.key.id })
            log("threadSnapshot=$label monoNs=${android.os.SystemClock.elapsedRealtimeNanos()}" +
                " relevant=${relevant.size} captured=${minOf(relevant.size, 24)}")
            relevant.take(24).forEach { (thread, stack) ->
                log("threadId=${thread.id} name=${thread.name} state=${thread.state}" +
                    " totalFrames=${stack.size} stack=${stack.take(40).joinToString(" | ")}")
            }
            // A Java snapshot does not identify the owner of a suspended coroutine mutex.
        }

        fun flush(label: String) {
            runCatching {
                val count = sequence.get()
                for (index in 0 until minOf(count, events.length())) {
                    events.get(index)?.let {
                        log("dump=$label seq=${it.sequence} monoNs=${it.nanos} thread=${it.thread} event=${it.name}")
                    }
                }
                if (count > events.length()) log("eventsOmitted=${count - events.length()}")
            }
        }

        private fun jobState(job: kotlinx.coroutines.Job?): String = if (job == null) "unavailable" else
            "active:${job.isActive},completed:${job.isCompleted},cancelled:${job.isCancelled}"

        private fun log(message: String) {
            runCatching {
                message.chunked(1800).forEachIndexed { part, chunk ->
                    android.util.Log.i("F11ResetOneShot", "part=$part $chunk")
                }
            }
        }
    }

    private fun resetHookState(): List<Any?> = listOf(
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting,
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting,
        RestoreTransactionCoordinator.beforeActivePublicationForTesting,
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting,
    )

    private fun recordResetPublicationDiagnostic(
        event: String,
        startedAt: Long,
        phase: String,
        beforeActive: Boolean,
        publication: Boolean,
        resetCompleted: Boolean,
        outcome: RestoreOutcome?,
        failure: Throwable?,
    ) {
        val pointer = java.io.File(RestoreOperationStore.root(context), "active.json")
        val pointerExists = pointer.exists()
        val record = runCatching { RestoreOperationStore.load(context) }
        val journal = record.getOrNull()?.journal
        android.util.Log.i(
            "F11HandoffDiagnostic",
            "event=$event elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}" +
                " boundary=$phase beforeActive=$beforeActive publication=$publication" +
                " resetCompleted=$resetCompleted outcome=$outcome" +
                " exception=${failure?.javaClass?.name}:${failure?.message}" +
                " activePointerExists=$pointerExists operation=${journal?.operationId}" +
                " journalPhase=${journal?.phase}" +
                " recordReadException=${record.exceptionOrNull()?.javaClass?.name}:" +
                "${record.exceptionOrNull()?.message}",
        )
        if (event == "publication_wait_finished" && !publication && !resetCompleted) {
            Thread.getAllStackTraces().entries.filter { (_, stack) ->
                stack.any {
                    it.className.contains("RestoreTransactionCoordinator") ||
                        it.className.contains("RestoreMutationAdmission")
                }
            }.take(10).forEach { (thread, stack) ->
                android.util.Log.i(
                    "F11HandoffDiagnostic",
                    "blockedThread=${thread.name} state=${thread.state} stack=" +
                        stack.take(60).joinToString(" | "),
                )
            }
        }
    }

    private fun plan(): RestorePlan = BackupRestoreParser.fromTyped(
        RestoreAppDataItem(
            settings = listOf(
                BackupSettingsItem(
                    key = "f11_handoff_admission_marker",
                    value = "restore-won",
                    type = "String",
                ),
            ),
        ),
    )

    private suspend fun insertObserveSource(
        id: Long,
        everyTime: Long = System.currentTimeMillis(),
    ) {
        database.observeSourcesDao.insert(
            ObserveSourcesItem(
                id = id,
                name = "handoff source $id",
                url = "https://example.com/handoff-source-$id",
                downloadItemTemplate = DownloadItem(
                    id = 0L,
                    url = "https://example.com/handoff-source-$id",
                    title = "",
                    author = "",
                    thumb = "",
                    duration = "",
                    type = DownloadType.video,
                    format = Format(),
                    container = "",
                    downloadSections = "",
                    allFormats = mutableListOf(),
                    downloadPath = "",
                    website = "",
                    downloadSize = "",
                    playlistTitle = "",
                    audioPreferences = AudioPreferences(),
                    videoPreferences = VideoPreferences(),
                    extraCommands = "",
                    customFileNameTemplate = "",
                    SaveThumb = false,
                    status = "Queued",
                    downloadStartTime = 0L,
                    logID = null,
                ),
                everyNr = 1,
                everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
                everyTime = everyTime,
                weeklyConfig = null,
                monthlyConfig = null,
                status = ObserveSourcesRepository.SourceStatus.ACTIVE,
                startsTime = System.currentTimeMillis(),
                endsDate = 0L,
                endsAfterCount = 0,
                runCount = 0,
                getOnlyNewUploads = false,
                retryMissingDownloads = true,
                ignoredLinks = mutableListOf(),
                alreadyProcessedLinks = mutableListOf(),
                syncWithSource = false,
            ),
        )
    }

    private fun clearHooks() {
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = null
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = null
        RestoreTransactionCoordinator.beforeActivePublicationForTesting = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = null
        RestoreTransactionCoordinator.afterQuiescedBeforeFilesReadyForTesting = null
        RestoreTransactionCoordinator.afterFilesReadyBeforeApplyForTesting = null
        RestoreTransactionCoordinator.afterDataCommittedBeforeReconciliationForTesting = null
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = null
        RestoreTransactionCoordinator.afterCompleteBeforeRetirementForTesting = null
    }
}
