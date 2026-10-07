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

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        WorkManager.getInstance(context).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        runCatching { RestoreTransactionCoordinator.recover(context) }
        RestoreOperationStore.root(context).deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
    }

    @After
    fun tearDown(): Unit = runBlocking {
        clearHooks()
        WorkManagerHandoffRecovery.clearForTesting()
        if (::database.isInitialized) database.close()
        WorkManager.getInstance(context).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        RestoreOperationStore.root(context).deleteRecursively()
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
        val publicationEntered = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val ordinaryEntered = CountDownLatch(1)
        val releaseOrdinary = CountDownLatch(1)
        val first = AtomicBoolean(true)
        lateinit var writer: Deferred<Result<String>>
        val writerScope = CoroutineScope(currentCoroutineContext())
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
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                ordinaryEntered.countDown()
                check(releaseOrdinary.await(20, TimeUnit.SECONDS))
            }
        }
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = {
            diagnosticPhase.set("publication_authority_acquired")
            publicationEntered.countDown()
            recordDiagnostic("publication_authority_acquired")
            writer = writerScope.async(Dispatchers.IO) {
                writerStarted.countDown()
                runCatching { create() }
            }
        }

        recordDiagnostic("before_reset_launch")
        val reset = writerScope.async(Dispatchers.IO) {
            diagnosticPhase.set("begin_entered")
            recordDiagnostic("begin_entered")
            try {
                RestoreTransactionCoordinator.begin(context, plan()).also {
                    resetOutcome.set(it)
                    diagnosticPhase.set("begin_returned")
                    recordDiagnostic("begin_returned")
                }
            } catch (failure: Throwable) {
                resetException.set(failure)
                diagnosticPhase.set("begin_threw")
                recordDiagnostic("begin_threw")
                throw failure
            }
        }
        resetReference.set(reset)
        val publicationObserved = publicationEntered.await(20, TimeUnit.SECONDS)
        recordDiagnostic("publication_wait_finished")
        assertTrue("Restore publication was not observed; see F11HandoffDiagnostic", publicationObserved)
        assertTrue(writerStarted.await(20, TimeUnit.SECONDS))
        assertTrue(ordinaryEntered.await(20, TimeUnit.SECONDS))
        releaseOrdinary.countDown()
        val failure = writer.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(reset.await() is RestoreOutcome.Completed)
        assertTrue(database.workManagerHandoffCarrierDao.getOutstanding().isEmpty())
    }

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
