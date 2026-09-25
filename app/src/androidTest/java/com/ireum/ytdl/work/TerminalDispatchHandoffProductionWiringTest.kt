package com.ireum.ytdl.work

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.TerminalItem
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.database.viewmodel.TerminalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production-wiring coverage for the durable Terminal dispatch handoff.
 * Operation and WorkInfo seams control publication acceptance; the worker
 * refusal/execution tests still instantiate the production worker and use the
 * real WorkManager runner.
 */
@RunWith(AndroidJUnit4::class)
class TerminalDispatchHandoffProductionWiringTest {
    private lateinit var context: Application
    private lateinit var database: DBManager
    private val operations = Collections.synchronizedList(mutableListOf<ControlledOperation>())
    private val requests = Collections.synchronizedList(mutableListOf<OneTimeWorkRequest>())
    private val workNames = Collections.synchronizedList(mutableListOf<String>())
    private val policies = Collections.synchronizedList(mutableListOf<ExistingWorkPolicy>())
    private val cancelledRequestIds = Collections.synchronizedList(mutableListOf<String>())
    private val realRequests = Collections.synchronizedList(mutableListOf<OneTimeWorkRequest>())
    private val recoveryIds = Collections.synchronizedList(mutableListOf<Long>())

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
        WorkManagerHandoffRecovery.cancelWorkByIdOperationOverrideForTesting = { requestId ->
            cancelledRequestIds += requestId
            ControlledOperation().also { it.succeed() }
        }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { name, policy, request ->
            workNames += name
            policies += policy
            requests += request
            ControlledOperation().also { operations += it }
        }
        TerminalDownloadWorkerEffectTestHooks.databaseForTesting = database
        TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterAdmissionForTesting = null
    }

    @After
    fun tearDown() {
        WorkManagerHandoffRecovery.clearForTesting()
        runBlocking {
            realRequests.forEach { request ->
                runCatching {
                    WorkManager.getInstance(context).cancelWorkById(request.id)
                        .result
                        .get(5, TimeUnit.SECONDS)
                }
            }
        }
        TerminalDownloadWorkerEffectTestHooks.databaseForTesting = null
        TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterAdmissionForTesting = null
        runBlocking {
            recoveryIds.forEach { terminalId ->
                TerminalExecutionRecovery.clearForTesting(
                    File(context.filesDir, "terminal-execution-recovery"),
                    terminalId,
                )
            }
        }
        if (::database.isInitialized) database.close()
        operations.clear()
        requests.clear()
        workNames.clear()
        policies.clear()
        cancelledRequestIds.clear()
        realRequests.clear()
        recoveryIds.clear()
    }

    @Test
    fun initialCreateStagesExactCarrierBeforeOperationAcceptance(): Unit = runBlocking {
        val viewModel = viewModel()
        val command = "--simulate https://example.com/terminal-stage"
        val terminalId = viewModel.insert(TerminalItem(command = command))
        recoveryIds += terminalId
        val carrier = carrier(terminalId)

        viewModel.startTerminalDownloadWorker(TerminalItem(terminalId, command))
        val operation = awaitOperation()
        val request = requests.single()

        assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE, carrier.state)
        assertEquals(carrier.requestId, request.id.toString())
        assertEquals(carrier.handoffId, request.workSpec.input.getString(TerminalDownloadWorker.INPUT_HANDOFF_ID))
        assertEquals(carrier.generationId, request.workSpec.input.getString(TerminalDownloadWorker.INPUT_GENERATION_ID))
        assertEquals(carrier.boundary, request.workSpec.input.getString(TerminalDownloadWorker.INPUT_BOUNDARY))
        assertEquals(carrier.configFingerprint, request.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND_FINGERPRINT))
        assertEquals(command, request.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND))
        assertEquals(terminalId.toString(), workNames.single())
        assertEquals(ExistingWorkPolicy.REPLACE, policies.single())

        operation.succeed()
        awaitCarrier(terminalId) { current -> current?.state == WorkManagerHandoffCarrier.ACCEPTED }
        assertEquals(terminalId, database.terminalDao.getTerminalById(terminalId)?.id)
        assertNotNull(database.workManagerHandoffCarrierDao.get(carrier.handoffId))
    }

    @Test
    fun asynchronousEnqueueFailureRetainsSemanticGenerationAndAdvancesRequest(): Unit = runBlocking {
        val viewModel = viewModel()
        val command = "--simulate https://example.com/terminal-retry"
        val terminalId = viewModel.insert(TerminalItem(command = command))
        recoveryIds += terminalId
        val first = carrier(terminalId)
        viewModel.startTerminalDownloadWorker(TerminalItem(terminalId, command))
        val operation = awaitOperation()
        val firstRequestId = first.requestId

        operation.fail(IllegalStateException("controlled enqueue failure"))
        val retried = awaitCarrier(terminalId) { it?.requestId != firstRequestId }

        assertEquals(first.handoffId, retried.handoffId)
        assertEquals(first.generationId, retried.generationId)
        assertEquals(first.sourceId, retried.sourceId)
        assertEquals(first.confirmedUrl, retried.confirmedUrl)
        assertEquals(first.configFingerprint, retried.configFingerprint)
        assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE, retried.state)
        assertTrue(retried.requestId != firstRequestId)
        assertTrue(retried.attempt >= 1)
    }

    @Test
    fun restartBeforeAcceptanceRepublishesSameOwnerWithoutDuplicateSuccessor(): Unit = runBlocking {
        val viewModel = viewModel()
        val command = "--simulate https://example.com/terminal-restart"
        val terminalId = viewModel.insert(TerminalItem(command = command))
        recoveryIds += terminalId
        val beforeRestart = carrier(terminalId)

        // Simulate process-local state loss while retaining the Room owner.
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { name, policy, request ->
            workNames += name
            policies += policy
            requests += request
            ControlledOperation().also { operations += it }
        }
        WorkManagerHandoffRecovery.reconcile(context)
        val operation = awaitOperation()
        val republished = requests.single()
        assertEquals(beforeRestart.handoffId, republished.workSpec.input.getString(TerminalDownloadWorker.INPUT_HANDOFF_ID))
        assertEquals(beforeRestart.requestId, republished.id.toString())
        assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE, carrier(terminalId).state)

        // A second startup pass observes the same durable owner while its first
        // enqueue is unresolved; it must not create another semantic successor.
        WorkManagerHandoffRecovery.reconcile(context)
        awaitCondition { requests.size == 1 }
        assertEquals(1, requests.size)
        assertEquals(1, database.workManagerHandoffCarrierDao.getOutstanding().size)
        operation.succeed()
        awaitCarrier(terminalId) { it?.state == WorkManagerHandoffCarrier.ACCEPTED }
    }

    @Test
    fun acceptedOwnerWithTransientMissingWorkInfoIsNotDuplicated(): Unit = runBlocking {
        val viewModel = viewModel()
        val terminalId = viewModel.insert(TerminalItem(command = "--simulate https://example.com/terminal-missing-info"))
        recoveryIds += terminalId
        val carrier = carrier(terminalId)
        database.workManagerHandoffCarrierDao.markAccepted(
            carrier.handoffId,
            carrier.requestId,
            System.currentTimeMillis(),
        )
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }

        WorkManagerHandoffRecovery.reconcile(context)
        WorkManagerHandoffRecovery.reconcile(context)

        assertTrue(requests.isEmpty())
        assertEquals(carrier.requestId, carrier(terminalId).requestId)
        assertEquals(WorkManagerHandoffCarrier.ACCEPTED, carrier(terminalId).state)
    }

    @Test
    fun cancellationSupersedesBeforeOldOperationCanResurrectTerminal(): Unit = runBlocking {
        val viewModel = viewModel()
        val command = "--simulate https://example.com/terminal-cancel"
        val terminalId = viewModel.insert(TerminalItem(command = command))
        recoveryIds += terminalId
        val carrier = carrier(terminalId)
        viewModel.startTerminalDownloadWorker(TerminalItem(terminalId, command))
        val operation = awaitOperation()

        viewModel.cancelTerminalDownload(terminalId)
        awaitCondition { database.terminalDao.getTerminalById(terminalId) == null }
        assertEquals(
            WorkManagerHandoffCarrier.SUPERSEDED,
            database.workManagerHandoffCarrierDao.get(carrier.handoffId)?.state,
        )

        // A delayed success for the old request is harmless and cannot recreate
        // the row or mark the superseded owner accepted.
        operation.succeed()
        awaitCondition {
            database.workManagerHandoffCarrierDao.get(carrier.handoffId)?.state ==
                WorkManagerHandoffCarrier.SUPERSEDED
        }
        assertNull(database.terminalDao.getTerminalById(terminalId))
        assertTrue(cancelledRequestIds.contains(carrier.requestId))
    }

    @Test
    fun staleUnboundAndWrongIdentityRequestsCannotEnterTerminalAdmission(): Unit = runBlocking {
        val viewModel = viewModel()
        val terminalId = viewModel.insert(TerminalItem(command = "--simulate https://example.com/terminal-stale"))
        recoveryIds += terminalId
        val carrier = carrier(terminalId)
        val admitted = AtomicInteger(0)
        TerminalDownloadWorkerEffectTestHooks.afterAdmissionForTesting = { id, _ ->
            if (id == terminalId.toInt()) admitted.incrementAndGet()
        }
        TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { id, _ ->
            if (id == terminalId.toInt()) admitted.incrementAndGet()
        }

        assertFalse(
            WorkManagerHandoffRecovery.isCurrentTerminalDispatchRequest(
                context,
                terminalId,
                carrier.confirmedUrl,
                carrier.handoffId,
                UUID.randomUUID().toString(),
                carrier.generationId,
                carrier.boundary,
                carrier.configFingerprint,
                UUID.randomUUID().toString(),
            ),
        )
        assertFalse(
            WorkManagerHandoffRecovery.isCurrentTerminalDispatchRequest(
                context,
                terminalId,
                carrier.confirmedUrl,
                carrier.handoffId,
                carrier.requestId,
                UUID.randomUUID().toString(),
                carrier.boundary,
                carrier.configFingerprint,
                carrier.requestId,
            ),
        )
        assertFalse(
            WorkManagerHandoffRecovery.isCurrentTerminalDispatchRequest(
                context,
                terminalId,
                carrier.confirmedUrl,
                carrier.handoffId,
                carrier.requestId,
                carrier.generationId,
                UUID.randomUUID().toString(),
                carrier.configFingerprint,
                carrier.requestId,
            ),
        )
        assertFalse(
            WorkManagerHandoffRecovery.isCurrentTerminalDispatchRequest(
                context,
                terminalId,
                carrier.confirmedUrl,
                carrier.handoffId,
                carrier.requestId,
                carrier.generationId,
                carrier.boundary,
                UUID.randomUUID().toString(),
                carrier.requestId,
            ),
        )

        database.workManagerHandoffCarrierDao.markSuperseded(
            carrier.handoffId,
            carrier.requestId,
            System.currentTimeMillis(),
        )
        val supersededRequest = OneTimeWorkRequestBuilder<TerminalDownloadWorker>()
            .setId(UUID.fromString(carrier.requestId))
            .setInputData(
                androidx.work.workDataOf(
                    TerminalDownloadWorker.INPUT_ID to terminalId.toInt(),
                    TerminalDownloadWorker.INPUT_COMMAND to carrier.confirmedUrl,
                    TerminalDownloadWorker.INPUT_HANDOFF_ID to carrier.handoffId,
                    TerminalDownloadWorker.INPUT_REQUEST_ID to carrier.requestId,
                    TerminalDownloadWorker.INPUT_GENERATION_ID to carrier.generationId,
                    TerminalDownloadWorker.INPUT_BOUNDARY to carrier.boundary,
                    TerminalDownloadWorker.INPUT_COMMAND_FINGERPRINT to carrier.configFingerprint,
                ),
            )
            .build()
        enqueueReal(supersededRequest)
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(supersededRequest).state)

        val legacyRequest = OneTimeWorkRequestBuilder<TerminalDownloadWorker>()
            .setInputData(
                androidx.work.workDataOf(
                    TerminalDownloadWorker.INPUT_ID to terminalId.toInt(),
                    TerminalDownloadWorker.INPUT_COMMAND to carrier.confirmedUrl,
                ),
            )
            .build()
        enqueueReal(legacyRequest)
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(legacyRequest).state)
        assertEquals(0, admitted.get())
        assertNotNull(database.terminalDao.getTerminalById(terminalId))
    }

    @Test
    fun exactCurrentOwnerReachesExistingTerminalAdmissionAndPublication(): Unit = runBlocking {
        val viewModel = viewModel()
        val command = "--simulate -P ${context.filesDir.absolutePath} https://example.com/terminal-current"
        val terminalId = viewModel.insert(TerminalItem(command = command))
        recoveryIds += terminalId
        val carrier = carrier(terminalId)
        val admitted = AtomicBoolean(false)
        val nativeReached = AtomicBoolean(false)
        TerminalDownloadWorkerEffectTestHooks.afterAdmissionForTesting = { id, _ ->
            if (id == terminalId.toInt()) admitted.set(true)
        }
        TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { id, _ ->
            if (id == terminalId.toInt()) nativeReached.set(true)
        }
        TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = { id, _ ->
            if (id == terminalId.toInt()) "" else null
        }

        val request = OneTimeWorkRequestBuilder<TerminalDownloadWorker>()
            .setId(UUID.fromString(carrier.requestId))
            .setInputData(
                androidx.work.workDataOf(
                    TerminalDownloadWorker.INPUT_ID to terminalId.toInt(),
                    TerminalDownloadWorker.INPUT_COMMAND to command,
                    TerminalDownloadWorker.INPUT_HANDOFF_ID to carrier.handoffId,
                    TerminalDownloadWorker.INPUT_REQUEST_ID to carrier.requestId,
                    TerminalDownloadWorker.INPUT_GENERATION_ID to carrier.generationId,
                    TerminalDownloadWorker.INPUT_BOUNDARY to carrier.boundary,
                    TerminalDownloadWorker.INPUT_COMMAND_FINGERPRINT to carrier.configFingerprint,
                ),
            )
            .build()
        enqueueReal(request)
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(request).state)
        assertTrue(admitted.get())
        assertTrue(nativeReached.get())
        assertNull(database.terminalDao.getTerminalById(terminalId))
        assertEquals(
            WorkManagerHandoffCarrier.RESOLVED,
            database.workManagerHandoffCarrierDao.get(carrier.handoffId)?.state,
        )
    }

    private fun viewModel(): TerminalViewModel = TerminalViewModel(context, database, true)

    private suspend fun carrier(terminalId: Long): WorkManagerHandoffCarrier = requireNotNull(
        database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
            WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
            terminalId.toString(),
        ),
    )

    private suspend fun awaitOperation(): ControlledOperation = withTimeout(5_000L) {
        var operation: ControlledOperation? = null
        while (operation == null) {
            operation = operations.removeFirstOrNull()
            if (operation == null) delay(5L)
        }
        checkNotNull(operation)
    }

    private suspend fun awaitCarrier(
        terminalId: Long,
        predicate: (WorkManagerHandoffCarrier?) -> Boolean,
    ): WorkManagerHandoffCarrier = withTimeout(5_000L) {
        var result: WorkManagerHandoffCarrier? = null
        while (result == null || !predicate(result)) {
            result = database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                terminalId.toString(),
            )
            if (result == null || !predicate(result)) delay(5L)
        }
        checkNotNull(result)
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) = withTimeout(5_000L) {
        while (!condition()) delay(5L)
    }

    private fun enqueueReal(request: OneTimeWorkRequest) {
        realRequests += request
        WorkManager.getInstance(context).enqueue(request)
    }

    private suspend fun awaitFinished(request: OneTimeWorkRequest): WorkInfo = withContext(Dispatchers.IO) {
        withTimeout(30_000L) {
            while (true) {
                val info = runCatching {
                    WorkManager.getInstance(context).getWorkInfoById(request.id)
                        .get(1, TimeUnit.SECONDS)
                }.getOrNull()
                if (info?.state?.isFinished == true) return@withTimeout checkNotNull(info)
                delay(25L)
            }
            error("unreachable")
        }
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
