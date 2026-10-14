package com.ireum.ytdl.work

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.*
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FormatBatchHandoffProductionWiringTest {
    private lateinit var context: Application
    private lateinit var database: DBManager
    private var publications = Channel<Publication>(Channel.UNLIMITED)
    private val staged = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerHandoffRecovery.clearForTesting()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters()).allowMainThreadQueries().build()
        attachController()
        // The seam ends at the ephemeral dispatch boundary. Room staging and
        // subsequent startup/enqueue/worker observers always use production code.
        FormatBatchHandoff.convergenceForTesting = { staged += it }
    }

    private fun attachController() {
        WorkManagerHandoffRecovery.databaseForTesting = database
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        WorkManagerHandoffRecovery.cancelUniqueWorkOverrideForTesting = {}
        WorkManagerHandoffRecovery.cancelWorkByIdOperationOverrideForTesting = { id ->
            cancelled += id
            ControlledOperation().also { it.succeed() }
        }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { name, policy, request ->
            ControlledOperation().also { op ->
                check(publications.trySend(Publication(name, policy, request, op)).isSuccess)
            }
        }
    }

    @After fun cleanup() {
        FormatBatchHandoff.afterCaptureForTesting = null
        FormatBatchHandoff.afterTransitionsForTesting = null
        FormatBatchHandoff.convergenceForTesting = null
        WorkManagerHandoffRecovery.clearForTesting()
        database.close()
        publications.close()
    }

    private suspend fun diagnosed(block: suspend () -> Unit) {
        try { block() } catch (failure: Throwable) {
            // Capture before @After closes Room or cancels pending requests.
            runCatching {
                database.workManagerHandoffCarrierDao.getOutstanding().forEach {
                    Log.e("FormatBatchDiagnostic", "carrier=$it payload=${database.formatBatchPayloadDao.get(it.handoffId)}")
                }
            }
            Log.e("FormatBatchDiagnostic", "first failure", failure)
            throw failure
        }
    }

    @Test fun realProducerCapturesAAndBButLeavesLateCProcessing() = runBlocking { diagnosed {
        val a = insert(1); val b = insert(2)
        FormatBatchHandoff.convergenceForTesting = null
        FormatBatchHandoff.afterCaptureForTesting = { capture ->
            check(capture.map { it.id }.toSet() == setOf(a.id, b.id))
            insert(3)
            FormatBatchHandoff.afterCaptureForTesting = null
        }
        val producer = async { DownloadViewModel(context, database, true).continueUpdatingFormatsOnBackground(listOf(a.id)) }
        val published = nextPublication()
        val carrier = database.workManagerHandoffCarrierDao.getOutstanding().single()
        assertEquals(carrier.requestId, published.request.id.toString())
        assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE, carrier.state)
        assertFalse(producer.isCompleted)
        assertEquals("Saved", database.downloadDao.getDownloadById(1).status)
        assertEquals("Saved", database.downloadDao.getDownloadById(2).status)
        assertEquals("Processing", database.downloadDao.getDownloadById(3).status)
        assertPayload(carrier.handoffId, listOf(1, 2), listOf(1), listOf(2))
        published.operation.succeed()
        withTimeout(5_000) { producer.await() }
        assertEquals(WorkManagerHandoffCarrier.ACCEPTED, database.workManagerHandoffCarrierDao.get(carrier.handoffId)?.state)
    } }

    @Test fun advancedCapturedBIsNotOverwrittenOrOwned() = runBlocking { diagnosed {
        val a = insert(1); val b = insert(2)
        database.openHelper.writableDatabase.execSQL("UPDATE downloads SET status='Active', executionId='new-owner' WHERE id=2")
        val batch = stage(listOf(a, b), listOf(1))
        assertEquals(listOf(2L), batch.refusedOrStaleIds)
        assertEquals("Active", database.downloadDao.getDownloadById(2).status)
        assertEquals("new-owner", database.downloadDao.getDownloadById(2).executionId)
        assertPayload(batch.handoffId!!, listOf(1, 2), listOf(1), emptyList())
    } }

    @Test fun deletedCapturedRowIsNeverRecreated() = runBlocking { diagnosed {
        val a = insert(1); val b = insert(2)
        database.downloadDao.delete(b.id)
        val batch = stage(listOf(a, b), listOf(1, 2))
        assertNull(database.downloadDao.getNullableDownloadById(2))
        assertPayload(batch.handoffId!!, listOf(1, 2), listOf(1), emptyList())
    } }

    @Test fun historyIssueAndBarrierRefuseSavedTransition() = runBlocking { diagnosed {
        val a = insert(1); val b = insert(2); val c = insert(3)
        database.openHelper.writableDatabase.execSQL("UPDATE downloads SET lastIssueCode='HISTORY_TARGET_DELETED' WHERE id=2")
        database.historyReplacementBarrierDao.insertIfAbsent(HistoryReplacementBarrier(3, "op", 7, c.url, "video", "HISTORY_TARGET_DELETED", "validate", 1))
        val batch = stage(listOf(a, b, c), null)
        assertEquals(listOf(2L, 3L), batch.refusedOrStaleIds)
        assertEquals("Processing", database.downloadDao.getDownloadById(2).status)
        assertEquals("Processing", database.downloadDao.getDownloadById(3).status)
        assertPayload(batch.handoffId!!, listOf(1, 2, 3), listOf(1), emptyList())
    } }

    @Test fun changedProcessingOwnerIsNotBorrowed() = runBlocking { diagnosed {
        val a = insert(1)
        database.openHelper.writableDatabase.execSQL("UPDATE downloads SET operationId='replacement' WHERE id=1")
        val batch = stage(listOf(a), null)
        assertNull(batch.handoffId)
        assertEquals("Processing", database.downloadDao.getDownloadById(1).status)
        assertTrue(database.workManagerHandoffCarrierDao.getOutstanding().isEmpty())
    } }

    @Test fun nullSelectionRefreshesWholeCapturedBundle() = runBlocking { diagnosed {
        val batch = stage(listOf(insert(1), insert(2)), null)
        assertPayload(batch.handoffId!!, listOf(1, 2), listOf(1, 2), emptyList())
    } }

    @Test fun emptySelectionRefreshesWholeCapturedBundle() = runBlocking { diagnosed {
        val batch = stage(listOf(insert(1), insert(2)), emptyList())
        assertPayload(batch.handoffId!!, listOf(1, 2), listOf(1, 2), emptyList())
    } }

    @Test fun noncapturedSelectionDoesNotWidenMutationOrPayload() = runBlocking { diagnosed {
        val a = insert(1); insert(9)
        val batch = stage(listOf(a), listOf(9))
        assertPayload(batch.handoffId!!, listOf(1), emptyList(), listOf(1))
        assertEquals("Processing", database.downloadDao.getDownloadById(9).status)
    } }

    @Test fun faultBetweenLifecycleAndPayloadRollsBackBoth() = runBlocking { diagnosed {
        val a = insert(1)
        FormatBatchHandoff.afterTransitionsForTesting = { ids ->
            if (ids == listOf(1L)) {
                FormatBatchHandoff.afterTransitionsForTesting = null
                error("scoped transaction fault")
            }
        }
        assertTrue(runCatching { stage(listOf(a), null) }.exceptionOrNull()?.message == "scoped transaction fault")
        assertEquals("Processing", database.downloadDao.getDownloadById(1).status)
        assertTrue(database.workManagerHandoffCarrierDao.getOutstanding().isEmpty())
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM format_batch_payloads").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
    } }

    @Test fun asyncOperationFailureRetainsPayloadAndAdvancesOnlyRequest() = runBlocking { diagnosed {
        val batch = stage(listOf(insert(1)), null)
        val before = carrier(batch)
        val attempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, before.handoffId)
        val first = nextPublication()
        first.operation.fail(IllegalStateException("R1 operation failure"))
        assertEquals(WorkManagerHandoffRecovery.OutcomeKind.RETRYING, withTimeout(5_000) { attempt.await() }.kind)
        val after = database.workManagerHandoffCarrierDao.get(before.handoffId)!!
        assertEquals(before.generationId, after.generationId)
        assertNotEquals(before.requestId, after.requestId)
        assertEquals(before.attempt + 1, after.attempt)
        assertPayload(before.handoffId, listOf(1), listOf(1), emptyList())
        val successor = nextPublication()
        assertEquals(after.requestId, successor.request.id.toString())
        successor.operation.succeed()
        assertTrue(withTimeout(5_000) { WorkManagerHandoffRecovery.enqueueAndAwait(context, after.handoffId).await() }.accepted)
        assertEquals(WorkManagerHandoffCarrier.ACCEPTED, database.workManagerHandoffCarrierDao.get(after.handoffId)?.state)
    } }

    @Test fun stagedPendingRestartDiscoversExactRequest() = runBlocking { diagnosed {
        val batch = stage(listOf(insert(1)), null)
        val before = carrier(batch)
        assertTrue(publications.tryReceive().isFailure)
        FormatBatchHandoff.convergenceForTesting = null // Restore the dispatch seam before successor observation.
        WorkManagerHandoffRecovery.reconcile(context)
        val published = nextPublication()
        assertEquals(before.requestId, published.request.id.toString())
        assertEquals(before.uniqueWorkName, published.name)
        published.operation.succeed()
        assertTrue(withTimeout(5_000) { WorkManagerHandoffRecovery.enqueueAndAwait(context, before.handoffId).await() }.accepted)
    } }

    @Test fun crashAfterWorkManagerAcceptBeforeCarrierAckDoesNotDuplicate() = runBlocking { diagnosed {
        val batch = stage(listOf(insert(1)), null)
        val before = carrier(batch)
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { request -> if (request == before.requestId) info(request, WorkInfo.State.ENQUEUED) else null }
        WorkManagerHandoffRecovery.reconcile(context)
        assertEquals(WorkManagerHandoffCarrier.ACCEPTED, database.workManagerHandoffCarrierDao.get(before.handoffId)?.state)
        assertTrue(WorkManagerHandoffRecovery.enqueueAndAwait(context, before.handoffId).await().accepted)
        assertTrue(publications.tryReceive().isFailure)
    } }

    @Test fun acceptedMissingAndUnfinishedInfoRetainExactOwnerAcrossReconcile() = runBlocking { diagnosed {
        val before = accepted(stage(listOf(insert(1)), null))
        WorkManagerHandoffRecovery.clearForTesting(); attachController()
        WorkManagerHandoffRecovery.reconcile(context)
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { request -> info(request, WorkInfo.State.RUNNING) }
        repeat(2) { WorkManagerHandoffRecovery.reconcile(context) }
        assertEquals(before, database.workManagerHandoffCarrierDao.get(before.handoffId))
        assertTrue(publications.tryReceive().isFailure)
        assertNotNull(database.formatBatchPayloadDao.get(before.handoffId))
    } }

    @Test fun independentBatchesHaveDistinctSemanticAndRequestIdentities() = runBlocking { diagnosed {
        val a = async { stage(listOf(insert(1)), null) }
        val b = async { stage(listOf(insert(2)), null) }
        val first = carrier(a.await()); val second = carrier(b.await())
        assertNotEquals(first.handoffId, second.handoffId)
        assertNotEquals(first.generationId, second.generationId)
        assertNotEquals(first.requestId, second.requestId)
        assertNotEquals(first.uniqueWorkName, second.uniqueWorkName)
        val attemptA = WorkManagerHandoffRecovery.enqueueAndAwait(context, first.handoffId)
        val attemptB = WorkManagerHandoffRecovery.enqueueAndAwait(context, second.handoffId)
        val p1 = nextPublication(); val p2 = nextPublication()
        assertEquals(setOf(first.requestId, second.requestId), setOf(p1.request.id.toString(), p2.request.id.toString()))
        p1.operation.succeed(); p2.operation.succeed()
        assertTrue(attemptA.await().accepted); assertTrue(attemptB.await().accepted)
    } }

    @Test fun lateR1AcceptanceAndCompletionCannotMutateR2() = runBlocking { diagnosed {
        val before = carrier(stage(listOf(insert(1)), null))
        val attempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, before.handoffId)
        val old = nextPublication()
        val nextId = UUID.randomUUID().toString()
        assertEquals(1, database.workManagerHandoffCarrierDao.advanceRetry(before.handoffId, before.requestId, nextId, 1, System.currentTimeMillis()))
        old.operation.succeed()
        assertTrue(withTimeout(5_000) { attempt.await() }.superseded)
        assertEquals(listOf(before.requestId), cancelled)
        assertFalse(FormatBatchHandoff.complete(context, database, before.handoffId, before.generationId, before.requestId))
        assertNull(FormatBatchHandoff.currentPayload(context, database, before.handoffId, before.generationId, before.requestId))
        val current = database.workManagerHandoffCarrierDao.get(before.handoffId)!!
        assertEquals(nextId, current.requestId)
        assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE, current.state)
        val successor = WorkManagerHandoffRecovery.enqueueAndAwait(context, current.handoffId)
        val published = nextPublication(); assertEquals(nextId, published.request.id.toString())
        published.operation.succeed(); assertTrue(successor.await().accepted)
    } }

    @Test fun failedAcceptedWorkRetriesExactGeneration() = runBlocking { diagnosed { terminalRetry(WorkInfo.State.FAILED) } }
    @Test fun cancelledAcceptedWorkRetriesExactGeneration() = runBlocking { diagnosed { terminalRetry(WorkInfo.State.CANCELLED) } }
    @Test fun succeededWorkWithoutSemanticAckRetainsRetryResponsibility() = runBlocking { diagnosed { terminalRetry(WorkInfo.State.SUCCEEDED) } }

    @Test fun exactWorkerAckIsFinalAndPayloadIsNotReplayed() = runBlocking { diagnosed {
        val before = accepted(stage(listOf(insert(1)), null))
        assertFalse(FormatBatchHandoff.complete(context, database, before.handoffId, before.generationId, before.requestId))
        val identity = FormatBatchIdentity(before.handoffId, before.generationId, before.requestId)
        val start = FormatBatchHandoff.prepareFetch(context, database, identity, 1)
        assertTrue(start is FormatBatchHandoff.FetchStart.Ready)
        val formats = listOf(Format(format_id = "settled-before-ack"))
        assertTrue(FormatAuthority.publish(context, database, (start as FormatBatchHandoff.FetchStart.Ready).lease, formats, identity))
        assertEquals(formats, database.downloadDao.getDownloadById(1).allFormats)
        assertEquals("COMPLETE", FormatBatchHandoff.members(database.formatBatchPayloadDao.get(before.handoffId)!!).single().state)
        assertTrue(FormatBatchHandoff.complete(context, database, before.handoffId, before.generationId, before.requestId))
        assertFalse(FormatBatchHandoff.complete(context, database, before.handoffId, before.generationId, before.requestId))
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { request -> info(request, WorkInfo.State.SUCCEEDED) }
        repeat(2) { WorkManagerHandoffRecovery.reconcile(context) }
        assertNull(database.workManagerHandoffCarrierDao.get(before.handoffId))
        assertNull(database.formatBatchPayloadDao.get(before.handoffId))
        assertTrue(publications.tryReceive().isFailure)
    } }

    @Test fun workerUuidCannotBeReplacedByDisplayNotificationInt() = runBlocking { diagnosed {
        val before = accepted(stage(listOf(insert(1)), null))
        val data = Data.Builder().putString(WorkManagerHandoffRecovery.INPUT_HANDOFF_ID, before.handoffId)
            .putString(WorkManagerHandoffRecovery.INPUT_REQUEST_ID, before.requestId)
            .putString(WorkManagerHandoffRecovery.INPUT_GENERATION_ID, before.generationId).build()
        val request = OneTimeWorkRequestBuilder<UpdateMultipleDownloadsFormatsWorker>()
            .setId(UUID.randomUUID()).setInputData(data).build()
        assertEquals(WorkInfo.State.FAILED, runRealWorker(request).state)
        assertEquals(before, database.workManagerHandoffCarrierDao.get(before.handoffId))
    } }

    @Test fun exactWorkerCompletesCachedFormatBatchAndRetiresOnlyItsPayload() = runBlocking { diagnosed {
        val row = insert(1)
        row.allFormats.add(Format(format_id = "already-known"))
        database.downloadDao.updateWithoutUpsert(row)
        val before = accepted(stage(listOf(row), null))
        val data = Data.Builder().putString(WorkManagerHandoffRecovery.INPUT_HANDOFF_ID, before.handoffId)
            .putString(WorkManagerHandoffRecovery.INPUT_REQUEST_ID, before.requestId)
            .putString(WorkManagerHandoffRecovery.INPUT_GENERATION_ID, before.generationId).build()
        val request = OneTimeWorkRequestBuilder<UpdateMultipleDownloadsFormatsWorker>()
            .setId(UUID.fromString(before.requestId)).setInputData(data).build()
        val previousFetch = UpdateMultipleDownloadsFormatsWorker.fetchForTesting
        val fetches = AtomicInteger()
        val freshFormats = listOf(Format(format_id = "fresh-worker-fetch"))
        UpdateMultipleDownloadsFormatsWorker.fetchForTesting = { identity, memberId ->
            if (identity.handoffId == before.handoffId && identity.generationId == before.generationId &&
                identity.requestId == before.requestId && memberId == row.id) {
                { fetches.incrementAndGet(); freshFormats }
            } else previousFetch?.invoke(identity, memberId)
        }
        try {
            assertEquals(WorkInfo.State.SUCCEEDED, runRealWorker(request).state)
            assertEquals(1, fetches.get())
            assertEquals(freshFormats, database.downloadDao.getDownloadById(1).allFormats)
            assertEquals(WorkManagerHandoffCarrier.RESOLVED, database.workManagerHandoffCarrierDao.get(before.handoffId)?.state)
            assertEquals("Saved", database.downloadDao.getDownloadById(1).status)
            WorkManagerHandoffRecovery.reconcile(context)
            assertNull(database.formatBatchPayloadDao.get(before.handoffId))
            assertTrue(publications.tryReceive().isFailure)
        } finally {
            UpdateMultipleDownloadsFormatsWorker.fetchForTesting = previousFetch
        }
    } }

    @Test fun restoreSupersessionRejectsLateAcceptanceAndWorkerAck() = runBlocking { diagnosed {
        val before = carrier(stage(listOf(insert(1)), null))
        val attempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, before.handoffId)
        val published = nextPublication()
        database.workManagerHandoffCarrierDao.markSupersededForKinds(listOf(WorkManagerHandoffCarrier.FORMAT_BATCH), System.currentTimeMillis())
        published.operation.succeed()
        assertTrue(attempt.await().superseded)
        assertFalse(FormatBatchHandoff.complete(context, database, before.handoffId, before.generationId, before.requestId))
        WorkManagerHandoffRecovery.reconcile(context)
        assertEquals(WorkManagerHandoffCarrier.SUPERSEDED, database.workManagerHandoffCarrierDao.get(before.handoffId)?.state)
        assertNotNull(database.formatBatchPayloadDao.get(before.handoffId))
    } }

    @Test fun realLegacyWorkerCreatesRowlessIssueWithoutBorrowingH2() = runBlocking { diagnosed {
        val row = insert(1)
        val cached = insertResult(9, row.url)
        val h2 = accepted(stage(listOf(row), null))
        fetchLease(h2, row.id)
        val cacheAuthority = database.formatAuthorityDao.get("RESULT_URL", row.url)
        val h2Payload = database.formatBatchPayloadDao.get(h2.handoffId)
        val before = database.downloadDao.getDownloadById(row.id)
        val request = OneTimeWorkRequestBuilder<UpdateMultipleDownloadsFormatsWorker>()
            .setInputData(Data.Builder().putLongArray("ids", longArrayOf(row.id)).build()).build()
        assertEquals(WorkInfo.State.FAILED, runRealWorker(request).state)
        val issue = withTimeout(5_000) { FormatRecoveryIssues.observeOpen(database).first { it.isNotEmpty() } }.single()
        assertEquals(request.id.toString(), issue.requestId)
        assertTrue(issue.origin.contains("UNPROVABLE"))
        assertEquals("", issue.handoffId)
        assertTrue(FormatRecoveryIssues.acknowledge(context, database, issue))
        assertFalse(FormatRecoveryIssues.acknowledge(context, database, issue))
        assertEquals(before, database.downloadDao.getDownloadById(row.id))
        assertEquals(cached, database.resultDao.getResultByID(cached.id))
        assertEquals(h2, database.workManagerHandoffCarrierDao.get(h2.handoffId))
        assertEquals(h2Payload, database.formatBatchPayloadDao.get(h2.handoffId))
        assertEquals(cacheAuthority, database.formatAuthorityDao.get("RESULT_URL", row.url))
        assertTrue(cancelled.isEmpty())
    } }

    @Test fun rowlessIssueDiscoveryAndDismissalHaveNoDownloadOrResultEffects() = runBlocking { diagnosed {
        val row = insert(1)
        val cached = insertResult(9, row.url)
        val h2 = accepted(stage(listOf(row), null))
        val before = database.downloadDao.getDownloadById(row.id)
        val oldRequest = UUID.randomUUID().toString()
        val oldDebt = FormatLegacyDebt(oldRequest, "[1]", "LEGACY_IDS_REQUIRE_CURRENT_PRODUCER")
        database.formatAuthorityDao.retainLegacy(oldDebt) // Existing pre-V21 producer shape, no issue/carrier.
        val issue = FormatRecoveryIssues.discoverRetained(context, database).single()
        assertEquals(listOf(issue), withTimeout(5_000) { FormatRecoveryIssues.observeOpen(database).first() })
        assertTrue(FormatRecoveryIssues.acknowledge(context, database, issue))
        assertTrue(FormatRecoveryIssues.discoverRetained(context, database).isEmpty())
        assertTrue(withTimeout(5_000) { FormatRecoveryIssues.observeOpen(database).first() }.isEmpty())
        assertEquals(oldDebt, database.formatAuthorityDao.getLegacy(oldRequest))
        assertEquals(FormatRecoveryIssue.ACKNOWLEDGED, database.formatAuthorityDao.recoveryIssue(issue.issueId)?.state)
        assertEquals(before, database.downloadDao.getDownloadById(row.id))
        assertEquals(cached, database.resultDao.getResultByID(cached.id))
        assertEquals(h2, database.workManagerHandoffCarrierDao.get(h2.handoffId))
    } }

    @Test fun missingAndCorruptPayloadWaitForExplicitIssueAcknowledgement() = runBlocking { diagnosed {
        for (id in listOf(1L, 2L)) {
            val before = accepted(stage(listOf(insert(id)), null))
            val download = database.downloadDao.getDownloadById(id)
            if (id == 1L) database.openHelper.writableDatabase.execSQL(
                "DELETE FROM format_batch_payloads WHERE handoffId=?", arrayOf(before.handoffId))
            else database.openHelper.writableDatabase.execSQL(
                "UPDATE format_batch_payloads SET authority='[]' WHERE handoffId=?", arrayOf(before.handoffId))
            repeat(2) {
                assertEquals(WorkManagerHandoffRecovery.OutcomeKind.USER_ACTION_REQUIRED,
                    WorkManagerHandoffRecovery.enqueueAndAwait(context, before.handoffId).await().kind)
            }
            assertTrue(publications.tryReceive().isFailure)
            assertEquals(before, database.workManagerHandoffCarrierDao.get(before.handoffId))
            val issue = database.formatAuthorityDao.recoveryIssues().single { it.requestId == before.requestId }
            assertEquals(FormatRecoveryIssue.OPEN, issue.state)
            assertTrue(FormatRecoveryIssues.acknowledge(context, database, issue))
            assertEquals(WorkManagerHandoffCarrier.RESOLVED, database.workManagerHandoffCarrierDao.get(before.handoffId)?.state)
            assertEquals(download, database.downloadDao.getDownloadById(id))
        }
    } }

    @Test fun rowlessAcknowledgementCannotRetireNewerRequest() = runBlocking { diagnosed {
        val r1 = accepted(stage(listOf(insert(1)), null))
        database.openHelper.writableDatabase.execSQL("UPDATE format_batch_payloads SET authority='[]' WHERE handoffId=?", arrayOf(r1.handoffId))
        assertEquals(FormatRecoveryIssues.CarrierDisposition.USER_ACTION_REQUIRED,
            FormatRecoveryIssues.carrierDisposition(context, database, r1))
        val issue = database.formatAuthorityDao.recoveryIssues().single()
        val next = UUID.randomUUID().toString()
        assertEquals(1, database.workManagerHandoffCarrierDao.advanceRetry(r1.handoffId, r1.requestId, next, 1, System.currentTimeMillis()))
        val r2 = database.workManagerHandoffCarrierDao.get(r1.handoffId)!!
        assertFalse(FormatRecoveryIssues.acknowledge(context, database, issue.copy(requestId = next)))
        assertTrue(FormatRecoveryIssues.acknowledge(context, database, issue))
        assertEquals(r2, database.workManagerHandoffCarrierDao.get(r1.handoffId))
        assertFalse(FormatBatchHandoff.complete(context, database, r1.handoffId, r1.generationId, r1.requestId))
        assertTrue(cancelled.isEmpty())
    } }

    @Test fun undispatchedFormatCandidateIsNotFabricatedAsLegacyDebt() = runBlocking { diagnosed {
        val request = OneTimeWorkRequestBuilder<UpdateMultipleDownloadsFormatsWorker>()
            .setInputData(Data.Builder().putLongArray("ids", longArrayOf(7)).build())
            .setInitialDelay(1, TimeUnit.DAYS).build()
        val manager = WorkManager.getInstance(context)
        withContext(Dispatchers.IO) { manager.enqueue(request).result.get(5, TimeUnit.SECONDS) }
        try {
            val observation = FormatRecoveryIssues.observeCandidates(context)
            assertFalse(observation.truncated)
            assertTrue(request.id.toString() in observation.requestIds)
            assertTrue(FormatRecoveryIssues.discoverRetained(context, database).isEmpty())
            assertNull(database.formatAuthorityDao.getLegacy(request.id.toString()))
        } finally {
            withContext(Dispatchers.IO) { manager.cancelWorkById(request.id).result.get(5, TimeUnit.SECONDS) }
        }
    } }

    @Test fun staleMemberResponseCannotSurviveSavedProcessingSavedABA() = runBlocking { diagnosed {
        val h1 = accepted(stage(listOf(insert(1)), null))
        val old = fetchLease(h1, 1)
        val saved = database.downloadDao.getDownloadById(1)
        database.downloadDao.updateWithoutUpsert(saved.copy(status = "Processing"))
        database.downloadDao.updateWithoutUpsert(saved.copy(status = "Saved"))
        database.downloadDao.updateWithoutUpsert(saved.copy(status = "Processing"))
        val h2 = accepted(stage(listOf(database.downloadDao.getDownloadById(1)), null))
        val fresh = fetchLease(h2, 1)
        val currentFormats = listOf(Format(format_id = "h2-current"))
        assertNotEquals(old.members.single().revision, fresh.members.single().revision)
        assertEquals("", database.downloadDao.getDownloadById(1).executionId)
        assertEquals("", database.downloadDao.getDownloadById(1).operationId)
        assertTrue(FormatAuthority.publish(context, database, fresh, currentFormats, identity(h2)))
        assertFalse(FormatAuthority.publish(context, database, old, listOf(Format(format_id = "h1-stale")), identity(h1)))
        assertEquals(currentFormats, database.downloadDao.getDownloadById(1).allFormats)
        assertTrue(FormatBatchHandoff.complete(context, database, h2.handoffId, h2.generationId, h2.requestId))
        assertEquals(FormatBatchHandoff.FetchStart.Settled, FormatBatchHandoff.prepareFetch(context, database, identity(h1), 1))
        assertEquals("SUPERSEDED", FormatBatchHandoff.members(database.formatBatchPayloadDao.get(h1.handoffId)!!).single().state)
        assertFalse(FormatAuthority.publish(context, database, old, currentFormats, identity(h1)))
    } }

    @Test fun independentCacheOwnerRejectsOldMemberEvenWhenDownloadUnchanged() = runBlocking { diagnosed {
        val first = insert(1)
        val cached = insertResult(9, first.url)
        val h1 = accepted(stage(listOf(first), null))
        val old = fetchLease(h1, first.id)
        val before = database.downloadDao.getDownloadById(first.id)
        val second = insert(2).copy(url = first.url)
        database.downloadDao.updateWithoutUpsert(second)
        val h2 = accepted(stage(listOf(second), null))
        val fresh = fetchLease(h2, second.id)
        val formats = listOf(Format(format_id = "new-cache-owner"))
        assertTrue(FormatAuthority.publish(context, database, fresh, formats, identity(h2)))
        assertFalse(FormatAuthority.publish(context, database, old, listOf(Format(format_id = "old-cache-owner")), identity(h1)))
        assertEquals(before, database.downloadDao.getDownloadById(first.id))
        assertEquals(formats, database.resultDao.getResultByID(cached.id)?.formats)
        assertFalse(FormatBatchHandoff.complete(context, database, h1.handoffId, h1.generationId, h1.requestId))
        assertTrue(FormatBatchHandoff.complete(context, database, h2.handoffId, h2.generationId, h2.requestId))
    } }

    @Test fun resultDeleteRecreateSameIdRejectsOldResponse() = runBlocking { diagnosed {
        val first = insert(1)
        val cached = insertResult(9, first.url)
        val h1 = accepted(stage(listOf(first), null))
        val old = fetchLease(h1, first.id)
        val before = database.downloadDao.getDownloadById(first.id)
        database.resultDao.delete(cached.id)
        val replacement = cached.copy(formats = listOf(Format(format_id = "replacement")))
        database.resultDao.insert(replacement)
        assertFalse(FormatAuthority.publish(context, database, old, listOf(Format(format_id = "stale")), identity(h1)))
        assertEquals(before, database.downloadDao.getDownloadById(first.id))
        assertEquals(replacement, database.resultDao.getResultByID(cached.id))
    } }

    @Test fun deletedDownloadIdReusedByNewBatchRejectsOldLease() = runBlocking { diagnosed {
        val h1 = accepted(stage(listOf(insert(1)), null))
        val old = fetchLease(h1, 1)
        database.downloadDao.delete(1)
        val h2 = accepted(stage(listOf(insert(1)), null))
        val fresh = fetchLease(h2, 1)
        assertNotEquals(old.members.single().incarnation, fresh.members.single().incarnation)
        val formats = listOf(Format(format_id = "new-incarnation"))
        assertTrue(FormatAuthority.publish(context, database, fresh, formats, identity(h2)))
        assertFalse(FormatAuthority.publish(context, database, old, listOf(Format(format_id = "deleted-incarnation")), identity(h1)))
        assertEquals(formats, database.downloadDao.getDownloadById(1).allFormats)
    } }

    @Test fun changedSourceOrConfigurationRequiresFreshRecapture() = runBlocking { diagnosed {
        for (id in listOf(1L, 2L)) {
            val row = insert(id)
            val cached = insertResult(90 + id, row.url)
            val h1 = accepted(stage(listOf(row), null))
            val old = fetchLease(h1, id)
            val saved = database.downloadDao.getDownloadById(id)
            val changed = if (id == 1L) saved.copy(url = "https://example.com/new-source")
                else saved.copy(container = "mp4")
            database.downloadDao.updateWithoutUpsert(changed)
            assertFalse(FormatAuthority.publish(context, database, old, listOf(Format(format_id = "old-response")), identity(h1)))
            assertEquals(changed, database.downloadDao.getDownloadById(id))
            assertEquals(cached, database.resultDao.getResultByID(cached.id))
            assertEquals(FormatBatchHandoff.FetchStart.Recapture,
                FormatBatchHandoff.prepareFetch(context, database, identity(h1), id))
            val previous = database.workManagerHandoffCarrierDao.getOutstanding().map { it.handoffId }.toSet()
            assertTrue(FormatBatchHandoff.recapture(context, database, identity(h1), id))
            val successor = database.workManagerHandoffCarrierDao.getOutstanding().single { it.handoffId !in previous }
            val member = FormatBatchHandoff.members(database.formatBatchPayloadDao.get(successor.handoffId)!!).single()
            assertNotEquals(h1.generationId, successor.generationId)
            assertNotEquals(h1.requestId, successor.requestId)
            assertEquals(changed.url, member.url)
            assertEquals(FormatAuthority.configuration(changed), member.configuration)
            assertEquals("PENDING", member.state)
            assertTrue(FormatBatchHandoff.complete(context, database, h1.handoffId, h1.generationId, h1.requestId))
            assertEquals(successor, database.workManagerHandoffCarrierDao.get(successor.handoffId))
            assertEquals(changed, database.downloadDao.getDownloadById(id))
        }
    } }

    @Test fun formatPublicationRollsBackDownloadWhenResultWriteFails() = runBlocking { diagnosed {
        val row = insert(1)
        val cached = insertResult(9, row.url)
        val owner = accepted(stage(listOf(row), null))
        val lease = fetchLease(owner, row.id)
        val before = database.downloadDao.getDownloadById(row.id)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_exact_format_result BEFORE UPDATE OF formats ON results WHEN NEW.id=9 AND instr(NEW.formats,'fault-format')>0 BEGIN SELECT RAISE(ABORT,'format-publication-fault'); END")
        try {
            val failure = runCatching { FormatAuthority.publish(context, database, lease,
                listOf(Format(format_id = "fault-format")), identity(owner)) }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(generateSequence(failure) { it.cause }.any { it.message?.contains("format-publication-fault") == true })
            assertEquals(before, database.downloadDao.getDownloadById(row.id))
            assertEquals(cached, database.resultDao.getResultByID(cached.id))
            assertEquals("PENDING", FormatBatchHandoff.members(database.formatBatchPayloadDao.get(owner.handoffId)!!).single().state)
            assertFalse(FormatBatchHandoff.complete(context, database, owner.handoffId, owner.generationId, owner.requestId))
        } finally { sql.execSQL("DROP TRIGGER fail_exact_format_result") }
    } }

    @Test fun firstLegacyOrIssueWriteFailureKeepsOriginalWorkManagerRetryOwner() = runBlocking { diagnosed {
        val row = insert(1)
        val h2 = accepted(stage(listOf(row), null))
        val before = database.downloadDao.getDownloadById(row.id)
        val manager = WorkManager.getInstance(context)
        for (table in listOf("format_legacy_debts", "format_recovery_issues")) {
            val request = OneTimeWorkRequestBuilder<UpdateMultipleDownloadsFormatsWorker>()
                .setInputData(Data.Builder().putLongArray("ids", longArrayOf(row.id)).build()).build()
            val sql = database.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER fail_exact_format_debt BEFORE INSERT ON $table WHEN NEW.requestId='${request.id}' BEGIN SELECT RAISE(ABORT,'format-debt-write-fault'); END")
            try {
                val retry = enqueueAndAwaitRetry(request)
                assertFalse(retry.state.isFinished)
                assertTrue(retry.runAttemptCount >= 1)
                assertNull(database.formatAuthorityDao.getLegacy(request.id.toString()))
                assertTrue(database.formatAuthorityDao.recoveryIssues().isEmpty())
                assertEquals(before, database.downloadDao.getDownloadById(row.id))
                assertEquals(h2, database.workManagerHandoffCarrierDao.get(h2.handoffId))
            } catch (failure: Throwable) {
                Log.e("FormatBatchDiagnostic", "First legacy persistence assertion before exact request cleanup: ${request.id}", failure)
                throw failure
            } finally {
                try {
                    withContext(Dispatchers.IO) { manager.cancelWorkById(request.id).result.get(5, TimeUnit.SECONDS) }
                } finally { sql.execSQL("DROP TRIGGER fail_exact_format_debt") }
            }
        }
    } }

    @Test fun partialMemberFailureRetainsReceiptTruthAndOutstandingBatch() = runBlocking { diagnosed {
        val a = insert(1); val b = insert(2)
        insertResult(9, a.url); val oldB = insertResult(10, b.url)
        val owner = accepted(stage(listOf(a, b), null))
        val data = Data.Builder().putString(WorkManagerHandoffRecovery.INPUT_HANDOFF_ID, owner.handoffId)
            .putString(WorkManagerHandoffRecovery.INPUT_GENERATION_ID, owner.generationId)
            .putString(WorkManagerHandoffRecovery.INPUT_REQUEST_ID, owner.requestId).build()
        val request = OneTimeWorkRequestBuilder<UpdateMultipleDownloadsFormatsWorker>()
            .setId(UUID.fromString(owner.requestId)).setInputData(data).build()
        val previous = UpdateMultipleDownloadsFormatsWorker.fetchForTesting
        val formats = listOf(Format(format_id = "member-a-completed"))
        UpdateMultipleDownloadsFormatsWorker.fetchForTesting = { current, member ->
            if (current == identity(owner) && member in setOf(a.id, b.id)) {
                { if (member == b.id) error("member-b-fetch-fault"); formats }
            } else previous?.invoke(current, member)
        }
        try {
            val retry = enqueueAndAwaitRetry(request)
            assertFalse(retry.state.isFinished)
            val members = FormatBatchHandoff.members(database.formatBatchPayloadDao.get(owner.handoffId)!!)
            assertEquals("COMPLETE", members.single { it.id == a.id }.state)
            assertEquals("PENDING", members.single { it.id == b.id }.state)
            assertEquals(formats, database.downloadDao.getDownloadById(a.id).allFormats)
            assertEquals(oldB, database.resultDao.getResultByID(oldB.id))
            assertNull(FormatBatchHandoff.notificationMembers(context, database, identity(owner)))
            assertFalse(FormatBatchHandoff.complete(context, database, owner.handoffId, owner.generationId, owner.requestId))
            assertEquals(owner, database.workManagerHandoffCarrierDao.get(owner.handoffId))
        } catch (failure: Throwable) {
            Log.e("FormatBatchDiagnostic", "First partial-member assertion before exact request cleanup", failure)
            throw failure
        } finally {
            try {
                withContext(Dispatchers.IO) { WorkManager.getInstance(context).cancelWorkById(request.id).result.get(5, TimeUnit.SECONDS) }
            } finally { UpdateMultipleDownloadsFormatsWorker.fetchForTesting = previous }
        }
    } }

    private suspend fun enqueueAndAwaitRetry(request: OneTimeWorkRequest): WorkInfo {
        val manager = WorkManager.getInstance(context)
        withContext(Dispatchers.IO) { manager.enqueue(request).result.get(5, TimeUnit.SECONDS) }
        return withTimeout(15_000) {
            var retried: WorkInfo? = null
            while (retried == null) {
                val current = withContext(Dispatchers.IO) { manager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS) }
                if (current?.state == WorkInfo.State.ENQUEUED && current.runAttemptCount > 0) retried = current
                else {
                    check(current?.state?.isFinished != true) { "Expected durable retry, observed $current" }
                    delay(10)
                }
            }
            checkNotNull(retried)
        }
    }

    private fun identity(carrier: WorkManagerHandoffCarrier) =
        FormatBatchIdentity(carrier.handoffId, carrier.generationId, carrier.requestId)

    private suspend fun fetchLease(carrier: WorkManagerHandoffCarrier, member: Long): FormatFetchAuthority {
        val start = FormatBatchHandoff.prepareFetch(context, database, identity(carrier), member)
        assertTrue(start is FormatBatchHandoff.FetchStart.Ready)
        return (start as FormatBatchHandoff.FetchStart.Ready).lease
    }

    private suspend fun insertResult(id: Long, url: String): ResultItem {
        val item = ResultItem(id = id, url = url, title = "cached", author = "author", duration = "",
            thumb = "", website = "example.com", playlistTitle = "", formats = listOf(Format(format_id = "cached")),
            urls = "", chapters = null)
        database.resultDao.insert(item)
        return database.resultDao.getResultByID(id)!!
    }

    private suspend fun terminalRetry(state: WorkInfo.State) {
        val before = accepted(stage(listOf(insert(1)), null))
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { request -> if (request == before.requestId) info(request, state) else null }
        WorkManagerHandoffRecovery.reconcile(context)
        val successor = nextPublication()
        val after = database.workManagerHandoffCarrierDao.get(before.handoffId)!!
        assertEquals(before.generationId, after.generationId)
        assertNotEquals(before.requestId, after.requestId)
        assertEquals(after.requestId, successor.request.id.toString())
        assertEquals(1, after.attempt)
        assertNotNull(database.formatBatchPayloadDao.get(before.handoffId))
        successor.operation.succeed()
        assertTrue(WorkManagerHandoffRecovery.enqueueAndAwait(context, after.handoffId).await().accepted)
    }

    private suspend fun accepted(batch: FormatBatchHandoff.StagedBatch): WorkManagerHandoffCarrier {
        val before = carrier(batch)
        val attempt = WorkManagerHandoffRecovery.enqueueAndAwait(context, before.handoffId)
        val published = nextPublication()
        assertEquals(before.requestId, published.request.id.toString())
        assertEquals(ExistingWorkPolicy.REPLACE, published.policy)
        assertEquals(before.generationId, published.request.workSpec.input.getString(WorkManagerHandoffRecovery.INPUT_GENERATION_ID))
        published.operation.succeed()
        assertTrue(withTimeout(5_000) { attempt.await() }.accepted)
        return database.workManagerHandoffCarrierDao.get(before.handoffId)!!
    }
    private suspend fun carrier(batch: FormatBatchHandoff.StagedBatch) = database.workManagerHandoffCarrierDao.get(batch.handoffId!!)!!
    private suspend fun stage(items: List<DownloadItem>, selected: List<Long>?) =
        FormatBatchHandoff.stage(context, database, FormatBatchHandoff.capture(context, database, items), selected)
    private suspend fun nextPublication() = withTimeout(5_000) { publications.receive() }
    private suspend fun runRealWorker(request: OneTimeWorkRequest): WorkInfo {
        val manager = WorkManager.getInstance(context)
        withContext(Dispatchers.IO) { manager.enqueue(request).result.get(5, TimeUnit.SECONDS) }
        return withTimeout(15_000) {
            var finished: WorkInfo? = null
            while (finished == null) {
                val current = withContext(Dispatchers.IO) { manager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS) }
                if (current?.state?.isFinished == true) finished = current else delay(10)
            }
            finished
        }
    }
    private suspend fun assertPayload(id: String, captured: List<Long>, selected: List<Long>, other: List<Long>) {
        val p = database.formatBatchPayloadDao.get(id)!!
        assertEquals(captured, FormatBatchHandoff.decode(p.capturedIds))
        assertEquals(selected, FormatBatchHandoff.decode(p.selectedIds))
        assertEquals(other, FormatBatchHandoff.decode(p.otherIds))
        assertNotEquals(0, p.notificationId)
    }
    private suspend fun insert(id: Long): DownloadItem {
        val item = DownloadItem(id, "https://example.com/$id", "item $id", "author", "", "", DownloadType.video,
            Format(), "Default", "", mutableListOf(), "", "example.com", "", "", AudioPreferences(), VideoPreferences(),
            "", "", false, "Processing", 0, null)
        database.downloadDao.insert(item)
        return database.downloadDao.getDownloadById(id)
    }
    private fun info(id: String, state: WorkInfo.State) = WorkInfo(UUID.fromString(id), state, emptySet(), Data.EMPTY, Data.EMPTY, 0, 0)
    private data class Publication(val name: String, val policy: ExistingWorkPolicy, val request: OneTimeWorkRequest, val operation: ControlledOperation)
    private class ControlledOperation : Operation {
        private val state = MutableLiveData<Operation.State>(Operation.IN_PROGRESS)
        private val result = SettableFuture.create<Operation.State.SUCCESS>()
        override fun getState(): LiveData<Operation.State> = state
        override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = result
        fun succeed() { state.postValue(Operation.SUCCESS); result.set(Operation.SUCCESS) }
        fun fail(error: Throwable) { state.postValue(Operation.State.FAILURE(error)); result.setException(error) }
    }
}
