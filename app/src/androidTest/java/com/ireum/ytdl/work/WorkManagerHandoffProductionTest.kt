package com.ireum.ytdl.work

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.Operation
import androidx.work.OneTimeWorkRequest
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WorkManagerHandoffProductionTest {
    private lateinit var context: Application
    private lateinit var database: DBManager
    private val operations = Collections.synchronizedList(mutableListOf<ControlledOperation>())
    private val workNames = Collections.synchronizedList(mutableListOf<String>())
    private val policies = Collections.synchronizedList(mutableListOf<ExistingWorkPolicy>())

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
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { name, policy, _ ->
            workNames += name
            policies += policy
            ControlledOperation().also { operations += it }
        }
    }

    @After
    fun tearDown() {
        WorkManagerHandoffRecovery.clearForTesting()
        if (::database.isInitialized) database.close()
        operations.clear()
        workNames.clear()
        policies.clear()
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
