package com.ireum.ytdl.work

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.preference.PreferenceManager
import androidx.work.ExistingWorkPolicy
import androidx.work.Operation
import androidx.work.OneTimeWorkRequest
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WorkManagerHandoffProductionTest {
    private lateinit var context: Application
    private lateinit var database: DBManager
    private val operations = Collections.synchronizedList(mutableListOf<ControlledOperation>())
    private val workNames = Collections.synchronizedList(mutableListOf<String>())
    private val policies = Collections.synchronizedList(mutableListOf<ExistingWorkPolicy>())
    private val cancelledRequestIds = Collections.synchronizedList(mutableListOf<String>())
    private val requests = Collections.synchronizedList(mutableListOf<OneTimeWorkRequest>())

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerHandoffRecovery.clearForTesting()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        WorkManagerHandoffRecovery.databaseForTesting = database
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        WorkManagerHandoffRecovery.cancelUniqueWorkOverrideForTesting = {}
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { name, policy, request ->
            workNames += name
            policies += policy
            requests += request
            ControlledOperation().also { operations += it }
        }
        ObserveSourcesRepository.beforeFinalEffectForTesting = null
    }

    @After
    fun tearDown() {
        WorkManagerHandoffRecovery.clearForTesting()
        if (::database.isInitialized) database.close()
        operations.clear()
        workNames.clear()
        policies.clear()
        cancelledRequestIds.clear()
        requests.clear()
        ObserveSourcesRepository.beforeFinalEffectForTesting = null
    }

    @Test
    fun hardSubScanAcceptedOnlyAfterOperationSuccess() = runBlocking {
        val callback = CompletableDeferred<WorkManagerHandoffRecovery.EnqueueOutcome>()
        val callbackIds = Collections.synchronizedList(mutableListOf<String>())
        val handoffId = checkNotNull(
            HardSubScanWorker.enqueueWithGeneration(
                context = context,
                onPrepared = {},
            ) { callbackId, outcome ->
                callbackIds += callbackId
                callback.complete(outcome)
            }
        )

        val operation = awaitOperation()
        assertFalse(callback.isCompleted)
        assertNotNull(database.workManagerHandoffCarrierDao.get(handoffId))
        operation.succeed()

        val outcome = withTimeout(2_000L) { callback.await() }
        assertTrue(outcome.accepted)
        assertEquals(listOf(handoffId), callbackIds)
        assertNull(database.workManagerHandoffCarrierDao.get(handoffId))
        assertEquals(listOf(HardSubScanWorker.UNIQUE_WORK_NAME), workNames)
        assertEquals(listOf(ExistingWorkPolicy.REPLACE), policies)
    }

    @Test
    fun hardSubScanSynchronousEnqueueThrowRetainsExactCarrierForRetry() = runBlocking {
        val calls = AtomicInteger(0)
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, _ ->
            calls.incrementAndGet()
            throw IllegalStateException("synchronous enqueue failure")
        }
        val id = WorkManagerHandoffRecovery.prepareHardSub(context)
        val outcome = WorkManagerHandoffRecovery.enqueueAndAwait(context, id).await()

        assertEquals(WorkManagerHandoffRecovery.OutcomeKind.RETRYING, outcome.kind)
        assertEquals(1, calls.get())
        assertEquals(
            WorkManagerHandoffCarrier.PENDING_ENQUEUE,
            database.workManagerHandoffCarrierDao.get(id)?.state,
        )
    }

    @Test
    fun hardSubScanAsyncOperationFailureRetainsExactCarrierForRetry() = runBlocking {
        val id = WorkManagerHandoffRecovery.prepareHardSub(context)
        val deferred = WorkManagerHandoffRecovery.enqueueAndAwait(context, id)
        val operation = awaitOperation()
        operation.fail(IllegalStateException("asynchronous enqueue failure"))

        assertEquals(WorkManagerHandoffRecovery.OutcomeKind.RETRYING, deferred.await().kind)
        val carrier = database.workManagerHandoffCarrierDao.get(id)
        assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE, carrier?.state)
        assertNotNull(carrier)
        assertTrue(carrier?.requestId?.isNotBlank() == true)
    }

    @Test
    fun hardSubRepeatedReplaceDoesNotReportSupersededGeneration() = runBlocking {
        val firstId = WorkManagerHandoffRecovery.prepareHardSub(context)
        val firstAttempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, firstId)
        val firstOperation = awaitOperation()

        val secondId = WorkManagerHandoffRecovery.prepareHardSub(context)
        assertNull(database.workManagerHandoffCarrierDao.get(firstId))
        assertNotNull(database.workManagerHandoffCarrierDao.get(secondId))

        val secondAttempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, secondId)
        val secondOperation = awaitOperation()
        secondOperation.succeed()
        assertTrue(secondAttempt.await().accepted)

        // Completing a superseded Operation cannot make the old carrier live
        // again or alter the accepted newer generation.
        firstOperation.succeed()
        val firstOutcome = runCatching { firstAttempt.await() }.getOrNull()
        assertTrue(firstOutcome?.superseded == true || firstAttempt.isCancelled)
        assertNull(database.workManagerHandoffCarrierDao.get(secondId))
    }

    @Test
    fun schedulerStartAndEndUseIndependentExactHandoffCarriers() = runBlocking {
        val startId = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            0L,
        )
        val startAttempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, startId)
        val startOperation = awaitOperation()
        startOperation.succeed()
        assertTrue(startAttempt.await().accepted)

        val endId = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.END_BOUNDARY,
            0L,
        )
        val endAttempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, endId)
        val endOperation = awaitOperation()
        endOperation.succeed()
        assertTrue(endAttempt.await().accepted)

        assertEquals(
            listOf("scheduled_download_start", "scheduled_download_end"),
            workNames,
        )
        assertEquals(
            listOf(ExistingWorkPolicy.REPLACE, ExistingWorkPolicy.REPLACE),
            policies,
        )
    }

    @Test
    fun schedulerHandoffSynchronousFailureRetainsExactStartBoundary() = runBlocking {
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, _ ->
            throw IllegalStateException("scheduler enqueue failure")
        }
        val id = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            0L,
        )
        val outcome = WorkManagerHandoffRecovery.enqueueAndAwait(context, id).await()

        assertEquals(WorkManagerHandoffRecovery.OutcomeKind.RETRYING, outcome.kind)
        assertEquals(
            WorkManagerHandoffCarrier.START_BOUNDARY,
            database.workManagerHandoffCarrierDao.get(id)?.boundary,
        )
    }

    @Test
    fun schedulerHandoffAsyncFailureRetainsExactEndBoundary() = runBlocking {
        val id = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.END_BOUNDARY,
            0L,
        )
        val attempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, id)
        awaitOperation().fail(IllegalStateException("scheduler Operation failure"))

        assertEquals(WorkManagerHandoffRecovery.OutcomeKind.RETRYING, attempt.await().kind)
        assertEquals(
            WorkManagerHandoffCarrier.END_BOUNDARY,
            database.workManagerHandoffCarrierDao.get(id)?.boundary,
        )
    }

    @Test
    fun schedulerProcessDeathReconcileKeepsPersistedExactRequestBeforeAcceptance() = runBlocking {
        val id = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            0L,
        )
        val requestId = database.workManagerHandoffCarrierDao.get(id)?.requestId
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, _ ->
            throw IllegalStateException("still unavailable")
        }

        WorkManagerHandoffRecovery.reconcile(context)

        assertEquals(requestId, database.workManagerHandoffCarrierDao.get(id)?.requestId)
        assertEquals(
            WorkManagerHandoffCarrier.PENDING_ENQUEUE,
            database.workManagerHandoffCarrierDao.get(id)?.state,
        )
    }

    @Test
    fun schedulerCancellationTombstonePreventsLateGenerationAcceptance() = runBlocking {
        val id = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            0L,
        )
        val attempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, id)
        awaitOperation()

        // The production scheduler invokes this through AlarmScheduler.cancel;
        // the durable exact row and in-process retry owner are both revoked.
        WorkManagerHandoffRecovery.cancelScheduledHandoffs(context)
        assertNull(database.workManagerHandoffCarrierDao.get(id))
        assertTrue(attempt.isCancelled || attempt.isCompleted)
    }

    @Test
    fun lateAcceptedSchedulerStartIsRevokedByDurableTombstone(): Unit = runBlocking {
        lateAcceptedSchedulerRequestIsRevoked(WorkManagerHandoffCarrier.START_BOUNDARY)
    }

    @Test
    fun lateAcceptedSchedulerEndIsRevokedByDurableTombstone(): Unit = runBlocking {
        lateAcceptedSchedulerRequestIsRevoked(WorkManagerHandoffCarrier.END_BOUNDARY)
    }

    @Test
    fun acceptedSchedulerCarrierMissingWorkInfoRetainsExactRequest(): Unit = runBlocking {
        val handoffId = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            0L,
        )
        val carrier = requireNotNull(database.workManagerHandoffCarrierDao.get(handoffId))
        database.workManagerHandoffCarrierDao.markAccepted(
            handoffId,
            carrier.requestId,
            System.currentTimeMillis(),
        )

        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        WorkManagerHandoffRecovery.reconcile(context)

        val afterRecovery = requireNotNull(database.workManagerHandoffCarrierDao.get(handoffId))
        assertEquals(WorkManagerHandoffCarrier.ACCEPTED, afterRecovery.state)
        assertEquals(carrier.requestId, afterRecovery.requestId)
    }
    @Test
    fun supersededSchedulerRequestIsRetiredAfterProcessDeathRecovery(): Unit = runBlocking {
        val handoffId = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            WorkManagerHandoffCarrier.START_BOUNDARY,
            0L,
        )
        val requestId = requireNotNull(database.workManagerHandoffCarrierDao.get(handoffId)).requestId
        assertEquals(
            1,
            database.workManagerHandoffCarrierDao.markSuperseded(
                handoffId,
                requestId,
                System.currentTimeMillis(),
            ),
        )

        // Simulate a new process: only the durable tombstone remains authoritative.
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        WorkManagerHandoffRecovery.reconcile(context)

        assertNull(database.workManagerHandoffCarrierDao.get(handoffId))
    }

    @Test
    fun observeRecurrenceStagesExactDurableRequestBeforeOperationAcceptance(): Unit = runBlocking {
        val carrier = stageObserveRecurrence(801L)
        val current = requireNotNull(database.observeSourcesDao.getByIDOrNull(801L))
        assertEquals(1, current.runCount)
        assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE, carrier.state)
        assertEquals(801L, carrier.sourceId)
        assertEquals(current.configurationGeneration, carrier.sourceConfigurationGeneration)
        assertTrue(
            "recurrence should be durably enqueued before its scheduled time",
            carrier.notBeforeAt > System.currentTimeMillis(),
        )

        val attempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, carrier.handoffId)
        val operation = awaitOperation()
        val request = requests.single()
        assertFalse(attempt.isCompleted)
        assertEquals(carrier.requestId, request.id.toString())
        assertEquals("OBSERVE801", workNames.single())
        assertEquals(ExistingWorkPolicy.REPLACE, policies.single())
        assertEquals(801L, request.workSpec.input.getLong(ObserveSourceWorker.INPUT_SOURCE_ID, 0L))
        assertEquals(
            current.configurationGeneration,
            request.workSpec.input.getLong(ObserveSourceWorker.INPUT_CONFIGURATION_GENERATION, 0L),
        )
        assertEquals(
            carrier.handoffId,
            request.workSpec.input.getString(ObserveSourceWorker.INPUT_RECURRENCE_HANDOFF_ID),
        )
        assertEquals(
            carrier.requestId,
            request.workSpec.input.getString(ObserveSourceWorker.INPUT_RECURRENCE_REQUEST_ID),
        )
        assertTrue(request.workSpec.initialDelay > 0L)
        assertTrue("observeSources" in request.tags)
        assertTrue(
            ObserveSourceWorker.configurationGenerationTag(current.configurationGeneration) in request.tags,
        )
        assertEquals(
            WorkManagerHandoffCarrier.PENDING_ENQUEUE,
            database.workManagerHandoffCarrierDao.get(carrier.handoffId)?.state,
        )

        operation.succeed()
        assertTrue(withTimeout(2_000L) { attempt.await() }.accepted)
        assertEquals(
            WorkManagerHandoffCarrier.ACCEPTED,
            database.workManagerHandoffCarrierDao.get(carrier.handoffId)?.state,
        )
    }

    @Test
    fun observeRecurrenceOperationFailureRetainsRetryableDebtAcrossRecovery(): Unit = runBlocking {
        val carrier = stageObserveRecurrence(802L)
        val attempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, carrier.handoffId)
        val operation = awaitOperation()

        operation.fail(IllegalStateException("recurrence enqueue acceptance failed"))
        assertEquals(
            WorkManagerHandoffRecovery.OutcomeKind.RETRYING,
            withTimeout(2_000L) { attempt.await() }.kind,
        )
        val retryable = requireNotNull(database.workManagerHandoffCarrierDao.get(carrier.handoffId))
        assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE, retryable.state)
        assertEquals(carrier.handoffId, retryable.generationId)
        assertEquals(carrier.sourceId, retryable.sourceId)
        assertEquals(carrier.sourceConfigurationGeneration, retryable.sourceConfigurationGeneration)
        assertEquals(1, retryable.attempt)
        assertTrue(retryable.requestId.isNotBlank())
        assertTrue(retryable.requestId != carrier.requestId)

        val retryRequestId = retryable.requestId
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, request ->
            requests += request
            ControlledOperation().also { operations += it }
        }
        WorkManagerHandoffRecovery.reconcile(context)

        val recoveredOperation = withTimeout(5_000L) {
            while (operations.isEmpty()) delay(5L)
            operations.removeAt(0)
        }
        val recoveredRequest = requests.last()
        assertEquals(retryRequestId, recoveredRequest.id.toString())
        assertTrue(recoveredRequest.workSpec.initialDelay > 0L)
        recoveredOperation.succeed()
        withTimeout(2_000L) {
            while (database.workManagerHandoffCarrierDao.get(carrier.handoffId)?.state !=
                WorkManagerHandoffCarrier.ACCEPTED
            ) {
                delay(5L)
            }
        }
        val recovered = requireNotNull(database.workManagerHandoffCarrierDao.get(carrier.handoffId))
        assertEquals(WorkManagerHandoffCarrier.ACCEPTED, recovered.state)
        assertEquals(retryRequestId, recovered.requestId)
        assertEquals(1, recovered.attempt)
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
    }

    @Test
    fun acceptedObserveRecurrenceWithMissingWorkInfoRetainsExactOwnerOnRestart(): Unit = runBlocking {
        val carrier = stageObserveRecurrence(803L)
        val attempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, carrier.handoffId)
        awaitOperation().succeed()
        assertTrue(withTimeout(2_000L) { attempt.await() }.accepted)
        val accepted = requireNotNull(database.workManagerHandoffCarrierDao.get(carrier.handoffId))
        assertEquals(WorkManagerHandoffCarrier.ACCEPTED, accepted.state)

        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        val enqueueCountBeforeRecovery = requests.size
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, request ->
            requests += request
            ControlledOperation().also { operations += it }
        }
        WorkManagerHandoffRecovery.reconcile(context)

        val recovered = requireNotNull(database.workManagerHandoffCarrierDao.get(carrier.handoffId))
        assertEquals(WorkManagerHandoffCarrier.ACCEPTED, recovered.state)
        assertEquals(accepted.requestId, recovered.requestId)
        assertEquals(enqueueCountBeforeRecovery, requests.size)
    }

    private suspend fun lateAcceptedSchedulerRequestIsRevoked(boundary: String) {
        val handoffId = WorkManagerHandoffRecovery.prepareSchedulerBoundary(
            context,
            boundary,
            0L,
        )
        val attempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, handoffId)
        val operation = awaitOperation()
        val carrier = requireNotNull(database.workManagerHandoffCarrierDao.get(handoffId))
        assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE, carrier.state)
        assertEquals(
            1,
            database.workManagerHandoffCarrierDao.markSuperseded(
                handoffId,
                carrier.requestId,
                System.currentTimeMillis(),
            ),
        )
        WorkManagerHandoffRecovery.cancelWorkByIdOperationOverrideForTesting = { requestId ->
            cancelledRequestIds += requestId
            ControlledOperation().also { it.succeed() }
        }

        // The WorkManager acceptance arrives after Restore/quiescence has installed
        // the exact durable stale-owner tombstone.
        operation.succeed()
        val outcome = withTimeout(2_000L) { attempt.await() }

        assertEquals(WorkManagerHandoffRecovery.OutcomeKind.SUPERSEDED, outcome.kind)
        assertEquals(listOf(carrier.requestId), cancelledRequestIds)
        assertEquals(
            WorkManagerHandoffCarrier.SUPERSEDED,
            database.workManagerHandoffCarrierDao.get(handoffId)?.state,
        )
    }
    private suspend fun awaitOperation(): ControlledOperation {
        var operation: ControlledOperation? = null
        withTimeout(2_000L) {
            while (operation == null) {
                operation = operations.firstOrNull()?.also { operations.remove(it) }
                if (operation == null) delay(5L)
            }
        }
        return requireNotNull(operation)
    }

    private suspend fun stageObserveRecurrence(sourceId: Long): WorkManagerHandoffCarrier {
        val now = System.currentTimeMillis()
        val template = DownloadItem(
            id = 0L,
            url = "https://example.com/observe-$sourceId",
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
        )
        database.observeSourcesDao.insert(
            ObserveSourcesItem(
                id = sourceId,
                name = "observe source $sourceId",
                url = "https://example.com/observe-$sourceId",
                downloadItemTemplate = template,
                everyNr = 1,
                everyCategory = ObserveSourcesRepository.EveryCategory.HOUR,
                everyTime = now,
                weeklyConfig = null,
                monthlyConfig = null,
                status = ObserveSourcesRepository.SourceStatus.ACTIVE,
                startsTime = now + TimeUnit.DAYS.toMillis(2),
                endsDate = 0L,
                endsAfterCount = 0,
                runCount = 0,
                getOnlyNewUploads = false,
                retryMissingDownloads = false,
                ignoredLinks = mutableListOf(),
                alreadyProcessedLinks = mutableListOf(),
                syncWithSource = false,
            ),
        )
        val source = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        val result = RestoreMutationAdmission.withOrdinaryMutation(context) {
            WorkManagerHandoffRecovery.commitObserveRunAndStageRecurrence(
                context = context,
                item = source.copy(
                    runCount = 1,
                    runHistory = mutableListOf("completed run"),
                ),
                revoke = false,
                recurrenceHandoffId = "",
                recurrenceRequestId = "",
            )
        }
        assertTrue(result.committed)
        return requireNotNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.OBSERVE_RECURRENCE,
                sourceId.toString(),
            ),
        )
    }

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
