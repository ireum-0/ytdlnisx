package com.ireum.ytdl.database

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.HistoryReplacementBarrier
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadOperationState
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.PendingUndoResolutionIntent
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.DownloadExecutionOwnershipLostException
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.runStartupCancellationReconciliation
import com.ireum.ytdl.runStartupReconciliation
import com.ireum.ytdl.ui.downloads.shouldPresentLowQualitySelection
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.work.HistoryReplacementPersistenceResult
import com.ireum.ytdl.work.DownloadCancellationRegistry
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.DownloadWorker
import com.ireum.ytdl.work.DownloadWorkerExecutionOwners
import com.ireum.ytdl.work.DownloadWorkerProcessOwners
import com.ireum.ytdl.work.LowQualityRedownloadLedger
import com.ireum.ytdl.work.LowQualityRedownloadManager
import com.ireum.ytdl.work.LowQualityRedownloadWorker
import com.ireum.ytdl.work.YtdlpProcessIdentity
import com.ireum.ytdl.work.claimDownloadThroughProductionAdmission
import com.ireum.ytdl.work.dispatchLowQualityRedownloadRecovery
import com.ireum.ytdl.work.persistHistoryReplacementTerminalState
import com.ireum.ytdl.work.withDownloadWorkerExecutionSideEffectLease
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LowQualityRedownloadPersistenceTest {
    private lateinit var database: DBManager
    private lateinit var repository: LowQualityRedownloadRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(context)
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        DownloadRepository.pendingCancellationCommitFailureForTesting = null
        DownloadRepository.pendingRemovalCommitFailureForTesting = null
        DownloadRepository.pendingRemovalRestoreFailureForTesting = null
        DownloadRepository.pendingUndoResolutionWriteFailureForTesting = null
        DownloadRepository.pendingRemovalAfterTransactionForTesting = null
        DownloadRepository.pendingCancellationAfterTransactionForTesting = null
        DownloadRepository.pendingRemovalResolverClaimedForTesting = null
        DownloadRepository.pendingRemovalResolvedBeforeCleanupForTesting = null
        DownloadRepository.pendingCancellationResolverClaimedForTesting = null
        DownloadRepository.pendingRemovalBeforeResolverClaimedForTesting = null
        DownloadRepository.pendingCancellationBeforeResolverClaimedForTesting = null
        DownloadRepository.terminalizeLinkedChildrenFailureForTesting = null
        LowQualityRedownloadRepository.abandonedPendingRemovalCommitFailureForTesting = null
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 0
        LowQualityRedownloadLedger.refreshFailureForTesting = null
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        repository = LowQualityRedownloadRepository(database)
        YtdlpNativeProcessBarrier.configure(context)
    }

    @After
    fun closeDatabase() {
        LowQualityRedownloadLedger.cancelAllAbandonedUndoConvergenceJobsForTesting()
        LowQualityRedownloadLedger.cancelAllCancellationConvergenceJobsForTesting()
        LowQualityRedownloadLedger.cancelAllEnqueueConvergenceJobsForTesting()
        LowQualityRedownloadRepository.getItemsFailureCountForTesting = 0
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 0
        LowQualityRedownloadRepository.abandonedPendingRemovalCommitFailureForTesting = null
        DownloadRepository.pendingCancellationCommitFailureForTesting = null
        DownloadRepository.pendingRemovalCommitFailureForTesting = null
        DownloadRepository.pendingRemovalRestoreFailureForTesting = null
        DownloadRepository.pendingUndoResolutionWriteFailureForTesting = null
        DownloadRepository.pendingRemovalAfterTransactionForTesting = null
        DownloadRepository.pendingCancellationAfterTransactionForTesting = null
        DownloadRepository.pendingRemovalResolverClaimedForTesting = null
        DownloadRepository.pendingRemovalResolvedBeforeCleanupForTesting = null
        DownloadRepository.pendingCancellationResolverClaimedForTesting = null
        DownloadRepository.pendingRemovalBeforeResolverClaimedForTesting = null
        DownloadRepository.pendingCancellationBeforeResolverClaimedForTesting = null
        DownloadRepository.terminalizeLinkedChildrenFailureForTesting = null
        LowQualityRedownloadLedger.refreshFailureForTesting = null
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(ApplicationProvider.getApplicationContext())
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        DownloadExecutionRecovery.recoveryReadFailureCountForTesting = 0
        DownloadExecutionRecovery.failCommittedHistoryFinalizationForTesting = false
        DownloadExecutionRecovery.commitOverride = null
        DownloadRepository.clearLivePendingRemovalTokensForTest()
        repository.beforeFinalLinkedExecutionRevalidationForTesting = null
        repository.beforeFinalLinkedExecutionActionForTesting = null
        YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
        YtdlpNativeProcessBarrier.markerEnumerationFailureForTesting = false
        if (::database.isInitialized) database.close()
    }

    @Test
    fun activeCreationIsUniqueAndReconstructionPreservesSelectionAndCheckpoint() = runBlocking {
        val first = repository.createOrReconnect(now = 100)
        val second = repository.createOrReconnect(now = 200)
        assertEquals(first.operationId, second.operationId)

        repository.checkpointScan(
            first.operationId,
            historyId = 42,
            candidate = LowQualityRedownloadItem(
                operationId = first.operationId,
                historyId = 42,
                candidateReason = "BELOW_EXPECTED",
                requestedHeight = 1080,
                expectedHeight = 1080,
                selected = false,
                updatedAt = 300
            ),
            failed = false
        )
        repository.setSelected(first.operationId, 42, true)

        val reconstructed = LowQualityRedownloadRepository(database)
        val operation = reconstructed.getOperation(first.operationId)!!
        val item = reconstructed.getItems(first.operationId).single()
        assertEquals(42L, operation.scanCursorHistoryId)
        assertEquals(1, operation.scanProcessed)
        assertTrue(item.selected)
        assertEquals(LowQualityRedownloadItemState.PENDING, item.stateValue)
    }

    @Test
    fun queueLinkageIsAtomicAndRestartDoesNotInsertDuplicateDownload() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        repository.checkpointScan(
            operation.operationId,
            historyId = 7,
            candidate = LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = 7,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name
            ),
            failed = false
        )
        val firstId = repository.linkDownloadAtomically(operation.operationId, 7, download(7))!!
        val secondId = repository.linkDownloadAtomically(operation.operationId, 7, download(7))!!

        assertEquals(firstId, secondId)
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM downloads")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        val linked = repository.getItems(operation.operationId).single()
        assertEquals(firstId, linked.downloadId)
        assertEquals(LowQualityRedownloadItemState.QUEUED, linked.stateValue)
    }

    @Test
    fun newOperationKeepsMostRecentTerminalSummaryAndGetsNewIdentity() = runBlocking {
        val first = repository.createOrReconnect(now = 100)
        repository.finishNoCandidates(first.operationId)
        val second = repository.createOrReconnect(now = 200)
        assertNotEquals(first.operationId, second.operationId)

        database.openHelper.readableDatabase
            .query("SELECT state FROM low_quality_redownload_operations ORDER BY createdAt")
            .use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("COMPLETED", cursor.getString(0))
                assertTrue(cursor.moveToNext())
                assertEquals("RUNNING", cursor.getString(0))
            }
    }

    @Test
    fun cancellationReturnsOnlyLinkedChildrenAndLeavesUnrelatedDownloadsUntouched() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        repository.checkpointScan(
            operation.operationId,
            historyId = 7,
            candidate = LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = 7,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name
            ),
            failed = false
        )
        val linkedId = repository.linkDownloadAtomically(operation.operationId, 7, download(7))!!
        val unrelatedId = database.downloadDao.insert(download(99))

        assertEquals(listOf(linkedId), repository.requestCancellation(operation.operationId))
        assertEquals(DownloadRepository.Status.Queued.name, database.downloadDao.getDownloadById(unrelatedId).status)
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue
        )
    }

    @Test
    fun terminalCancellationRetainsNativeDebtUntilStartupQuiescence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(
            operationId = operation.operationId,
            historyId = 7,
            status = DownloadRepository.Status.Active,
            executionId = "E1",
        )
        repository.requestCancellation(operation.operationId)
        val processId = YtdlpProcessIdentity.download(linkedId, "E1")
        val process = ControlledNativeProcess(acknowledgeOnForce = false)
        YoutubeDLCompat.registerProcessForTesting(processId, process)

        try {
            repository.completePersistedCancellationWithPublications(
                operationId = operation.operationId,
                context = context,
            )
            assertEquals(
                LowQualityRedownloadOperationState.CANCELLED,
                repository.getOperation(operation.operationId)?.stateValue,
            )
            assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(linkedId))
            assertFalse(DownloadWorkerProcessOwners.claim(linkedId, "E2"))

            val recovery = async(Dispatchers.IO) {
                DownloadExecutionRecovery.reconcile(context, database)
            }
            process.destroyRequested.await()
            yield()
            assertFalse(recovery.isCompleted)
            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                database.downloadDao.getNullableDownloadById(linkedId)?.status,
            )

            process.acknowledgeTermination()
            recovery.await()
            assertFalse(YoutubeDLCompat.hasProcessById(processId))
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(linkedId))
            assertTrue(DownloadWorkerProcessOwners.claim(linkedId, "E2"))
        } finally {
            process.acknowledgeTermination()
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(linkedId, "E1")
            DownloadWorkerProcessOwners.release(linkedId, "E2")
        }
    }

    @Test
    fun terminalCancellationWaitsForRecoveryPublicationAfterFirstCommitFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(
            operationId = operation.operationId,
            historyId = 8,
            status = DownloadRepository.Status.Active,
            executionId = "E1",
        )
        repository.requestCancellation(operation.operationId)
        DownloadExecutionRecovery.commitOverride = { operationType, _ ->
            operationType != DownloadExecutionRecovery.JournalCommitOperation.RECORD
        }

        var publicationFailed = false
        try {
            repository.completePersistedCancellationWithPublications(
                operationId = operation.operationId,
                context = context,
            )
        } catch (_: IllegalStateException) {
            publicationFailed = true
        }
        assertTrue(publicationFailed)
        assertEquals(
            LowQualityRedownloadOperationState.RUNNING,
            repository.getOperation(operation.operationId)?.stateValue,
        )
        assertEquals(
            DownloadRepository.Status.Active.name,
            database.downloadDao.getNullableDownloadById(linkedId)?.status,
        )
        assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(linkedId))

        DownloadExecutionRecovery.commitOverride = null
        repository.completePersistedCancellationWithPublications(
            operationId = operation.operationId,
            context = context,
        )
        DownloadExecutionRecovery.reconcile(context, database)

        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
        assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(linkedId))
    }

    @Test
    fun terminalCancellationNativeDebtReconcilesAfterProcessDeath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(
            operationId = operation.operationId,
            historyId = 9,
            status = DownloadRepository.Status.Active,
            executionId = "E1",
        )
        repository.requestCancellation(operation.operationId)
        repository.completePersistedCancellationWithPublications(
            operationId = operation.operationId,
            context = context,
        )
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
        assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(linkedId))

        // Cold start has no process-local owner or registry. The durable
        // terminal operation plus exact journal token still drives the native
        // quiescence acknowledgement and debt cleanup.
        DownloadWorkerProcessOwners.release(linkedId, "E1")
        DownloadExecutionRecovery.reconcile(context, database)

        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getNullableDownloadById(linkedId)?.status,
        )
        assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(linkedId))
    }

    @Test
    fun cancellationConvergenceRetriesFirstGetItemsReadInSameProcess() = runBlocking {
        assertCancellationConvergenceRetriesGetItemsFailures(failureCount = 1, historyId = 901)
    }

    @Test
    fun cancellationConvergenceRetriesRepeatedGetItemsReadsInSameProcess() = runBlocking {
        assertCancellationConvergenceRetriesGetItemsFailures(failureCount = 4, historyId = 902)
    }

    @Test
    fun cancellationConvergenceCancellationStopsOwnerWithoutRetryLoop() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(
            operationId = operation.operationId,
            historyId = 903,
            status = DownloadRepository.Status.Active,
            executionId = "E1",
        )

        repository.requestCancellation(operation.operationId)
        LowQualityRedownloadRepository.getItemsFailureCountForTesting = Int.MAX_VALUE
        LowQualityRedownloadLedger.scheduleCancellationConvergence(
            context = context,
            operationId = operation.operationId,
            dbManager = database,
        )
        awaitCancellationOwnerActive(operation.operationId)

        LowQualityRedownloadLedger.cancelCancellationConvergenceForTesting(operation.operationId)
        awaitCancellationOwnerStopped(operation.operationId)

        val interrupted = requireNotNull(repository.getOperation(operation.operationId))
        assertEquals(LowQualityRedownloadOperationState.RUNNING, interrupted.stateValue)
        assertTrue(interrupted.cancelRequested)
        LowQualityRedownloadRepository.getItemsFailureCountForTesting = 0
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue,
        )

        // A later live owner can still complete the durable debt; this is a
        // test cleanup and also proves cancellation did not corrupt the
        // operation into an unretryable state.
        LowQualityRedownloadLedger.scheduleCancellationConvergence(
            context = context,
            operationId = operation.operationId,
            dbManager = database,
        )
        awaitOperationState(operation.operationId, LowQualityRedownloadOperationState.CANCELLED)
        awaitCancellationOwnerStopped(operation.operationId)
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).single().stateValue,
        )

        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getNullableDownloadById(linkedId)?.status,
        )
    }

    @Test
    fun requestCancellationClaimWinsAndRetriesWithClaimedChild() = runBlocking {
        assertA11ClaimWins(A11Surface.REQUEST_CANCELLATION, 904L)
    }

    @Test
    fun completePersistedCancellationClaimWinsAndWaitsForClaimedChildLease() = runBlocking {
        assertA11ClaimWins(A11Surface.COMPLETE_CANCELLATION, 905L)
    }

    @Test
    fun failCoordinatorClaimWinsAndWaitsForClaimedChildLease() = runBlocking {
        assertA11ClaimWins(A11Surface.COORDINATOR_FAILURE, 906L)
    }

    @Test
    fun requestCancellationRevocationWinsBeforeProductionClaim() = runBlocking {
        assertA11TerminalWins(A11Surface.REQUEST_CANCELLATION, 907L)
    }

    @Test
    fun completePersistedCancellationWinsBeforeProductionClaim() = runBlocking {
        assertA11TerminalWins(A11Surface.COMPLETE_CANCELLATION, 908L)
    }

    @Test
    fun failCoordinatorWinsBeforeProductionClaim() = runBlocking {
        assertA11TerminalWins(A11Surface.COORDINATOR_FAILURE, 909L)
    }

    @Test
    fun noCandidateFinalizationCannotOverwriteDurableCancellation() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 74, DownloadRepository.Status.Queued)

        repository.requestCancellation(operation.operationId)
        repository.finishNoCandidates(operation.operationId)

        val currentOperation = repository.getOperation(operation.operationId)!!
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadOperationState.CANCELLED, currentOperation.stateValue)
        assertEquals(
            LowQualityRedownloadRepository.REASON_USER_CANCELLED,
            currentOperation.terminalReason,
        )
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(LowQualityRedownloadRepository.REASON_USER_CANCELLED, child.reasonCode)
        assertEquals(DownloadRepository.Status.Queued.name, database.downloadDao.getDownloadById(linkedId).status)
    }

    @Test
    fun noCandidateFinalizationWinsBeforeLaterCancellation() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        repository.finishNoCandidates(operation.operationId)

        repository.requestCancellation(operation.operationId)

        val currentOperation = repository.getOperation(operation.operationId)!!
        assertEquals(LowQualityRedownloadOperationState.COMPLETED, currentOperation.stateValue)
        assertEquals(LowQualityRedownloadRepository.REASON_NO_CANDIDATES, currentOperation.terminalReason)
    }

    @Test
    fun terminalChildStateCannotBeOverwrittenByLateWorkerCallback() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        repository.checkpointScan(
            operation.operationId,
            historyId = 7,
            candidate = LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = 7,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name
            ),
            failed = false
        )
        val linkedId = repository.linkDownloadAtomically(operation.operationId, 7, download(7))!!

        assertEquals(
            operation.operationId,
            repository.markDownloadState(linkedId, LowQualityRedownloadItemState.SUCCEEDED)
        )
        assertNull(
            repository.markDownloadState(linkedId, LowQualityRedownloadItemState.FAILED, "LATE_FAILURE")
        )

        assertEquals(
            LowQualityRedownloadItemState.SUCCEEDED,
            repository.getItems(operation.operationId).single().stateValue
        )
    }

    @Test
    fun historyMismatchPersistsDownloadErrorAndLinkedFailureReason() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 8, DownloadRepository.Status.Active)
        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)
        val terminalItem = database.downloadDao.getDownloadById(linkedId).apply {
            status = DownloadRepository.Status.Error.name
            lastIssueCode = issue.code.name
            lastIssueStage = issue.stage.name
        }

        val result = persistHistoryReplacementTerminalState(
            issue = issue,
            persistDownload = { database.downloadDao.update(terminalItem) },
            transitionLinkedDownload = { reason ->
                repository.markDownloadState(
                    linkedId,
                    LowQualityRedownloadItemState.FAILED,
                    reason,
                )
            },
        )

        assertEquals(HistoryReplacementPersistenceResult.Persisted, result)
        val persistedDownload = database.downloadDao.getDownloadById(linkedId)
        assertEquals(DownloadRepository.Status.Error.name, persistedDownload.status)
        assertEquals(issue.code.name, persistedDownload.lastIssueCode)
        assertEquals(issue.stage.name, persistedDownload.lastIssueStage)
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.FAILED, child.stateValue)
        assertEquals(issue.code.name, child.reasonCode)
    }

    @Test
    fun refusedReplacementStatusTransitionsConvergeDownloadAndLedgerSemantics() = runBlocking {
        suspend fun addBarrier(
            operationId: String,
            downloadId: Long,
            historyId: Long,
            issueCode: String,
        ) {
            database.historyReplacementBarrierDao.insertIfAbsent(
                HistoryReplacementBarrier(
                    downloadId = downloadId,
                    operationId = operationId,
                    historyId = historyId,
                    expectedSourceUrl = "https://example.com/$historyId",
                    expectedType = DownloadType.video.name,
                    issueCode = issueCode,
                    issueStage = "HISTORY",
                    createdAt = 1L,
                )
            )
        }

        val sourceOperation = repository.createOrReconnect(now = 100)
        val sourceId = linkDownload(
            sourceOperation.operationId,
            301,
            DownloadRepository.Status.Queued,
        )
        addBarrier(
            sourceOperation.operationId,
            sourceId,
            301,
            DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH.name,
        )
        DownloadRepository(database).moveToSaved(sourceId)

        val sourceDownload = database.downloadDao.getDownloadById(sourceId)
        assertEquals(DownloadRepository.Status.Error.name, sourceDownload.status)
        assertEquals(
            DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH.name,
            sourceDownload.lastIssueCode,
        )
        assertEquals(
            LowQualityRedownloadItemState.FAILED,
            repository.getItems(sourceOperation.operationId).single().stateValue,
        )
        assertEquals(
            LowQualityRedownloadOperationState.FAILED,
            repository.getOperation(sourceOperation.operationId)?.stateValue,
        )

        val targetOperation = repository.createOrReconnect(now = 200)
        val targetId = linkDownload(
            targetOperation.operationId,
            302,
            DownloadRepository.Status.Cancelled,
        )
        addBarrier(
            targetOperation.operationId,
            targetId,
            302,
            DownloadIssueCode.HISTORY_TARGET_DELETED.name,
        )
        DownloadRepository(database).cancelByUser(targetId)

        val targetDownload = database.downloadDao.getDownloadById(targetId)
        assertEquals(DownloadRepository.Status.Cancelled.name, targetDownload.status)
        assertEquals(DownloadIssueCode.HISTORY_TARGET_DELETED.name, targetDownload.lastIssueCode)
        assertEquals(
            LowQualityRedownloadItemState.SKIPPED,
            repository.getItems(targetOperation.operationId).single().stateValue,
        )
        assertEquals(
            LowQualityRedownloadOperationState.COMPLETED,
            repository.getOperation(targetOperation.operationId)?.stateValue,
        )
    }

    @Test
    fun undoDoesNotRewriteNewerSiblingDerivedTerminalParent() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val deletedId = linkDownload(operation.operationId, 303, DownloadRepository.Status.Queued)
        val siblingId = linkDownload(operation.operationId, 304, DownloadRepository.Status.Queued)
        val issueCode = DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH.name
        database.historyReplacementBarrierDao.insertIfAbsent(
            HistoryReplacementBarrier(
                downloadId = deletedId,
                operationId = operation.operationId,
                historyId = 303,
                expectedSourceUrl = "https://example.com/303",
                expectedType = DownloadType.video.name,
                issueCode = issueCode,
                issueStage = "HISTORY",
                createdAt = 1L,
            )
        )

        val downloadRepository = DownloadRepository(database)
        val undoHandle = downloadRepository.deleteForUndo(deletedId)!!
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).first { it.historyId == 303L }.stateValue,
        )
        repository.markDownloadState(siblingId, LowQualityRedownloadItemState.SUCCEEDED)

        val restoredId = downloadRepository.restoreUndo(undoHandle.token)!!
        val restored = database.downloadDao.getDownloadById(restoredId)
        assertEquals(DownloadRepository.Status.Error.name, restored.status)
        assertEquals(issueCode, restored.lastIssueCode)
        assertEquals(
            LowQualityRedownloadItemState.FAILED,
            repository.getItems(operation.operationId).first { it.historyId == 303L }.stateValue,
        )
        assertEquals(
            LowQualityRedownloadItemState.SUCCEEDED,
            repository.getItems(operation.operationId).first { it.historyId == 304L }.stateValue,
        )
        val parentAfterUndo = repository.getOperation(operation.operationId)!!
        assertEquals(LowQualityRedownloadOperationState.PARTIAL_FAILURE, parentAfterUndo.stateValue)
    }

    @Test
    fun ordinaryUndoRebindsLinkedChildWithoutRestoringAStaleParentSnapshot() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 306, DownloadRepository.Status.Queued)
        val downloadRepository = DownloadRepository(database)
        val undoHandle = downloadRepository.deleteForUndo(linkedId)!!

        val restoredId = downloadRepository.restoreUndo(undoHandle.token)!!
        assertEquals(DownloadRepository.Status.Queued.name, database.downloadDao.getDownloadById(restoredId).status)
        val restoredChild = repository.getItems(operation.operationId).single()
        assertEquals(restoredId, restoredChild.downloadId)
        assertEquals(LowQualityRedownloadItemState.QUEUED, restoredChild.stateValue)
        assertEquals(LowQualityRedownloadOperationState.RUNNING, repository.getOperation(operation.operationId)?.stateValue)
    }

    @Test
    fun ordinaryUndoDoesNotReopenAParentThatBecameTerminalWhilePending() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 307, DownloadRepository.Status.Queued)
        val downloadRepository = DownloadRepository(database)
        val undoHandle = downloadRepository.deleteForUndo(linkedId)!!

        assertEquals(
            1,
            database.lowQualityRedownloadDao.finishOperation(
                operation.operationId,
                LowQualityRedownloadOperationState.CANCELLED.name,
                "independent-cancel",
                101L,
            ),
        )

        assertNull(downloadRepository.restoreUndo(undoHandle.token))
        assertNull(database.downloadDao.getNullableDownloadById(linkedId))
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        val currentOperation = repository.getOperation(operation.operationId)!!
        assertEquals(LowQualityRedownloadOperationState.CANCELLED, currentOperation.stateValue)
        assertEquals("independent-cancel", currentOperation.terminalReason)
    }

    @Test
    fun ordinaryUndoDoesNotReopenWhenParentCancellationWasRequested() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 312, DownloadRepository.Status.Queued)
        val downloadRepository = DownloadRepository(database)
        val undoHandle = downloadRepository.deleteForUndo(linkedId)!!

        assertEquals(
            1,
            database.lowQualityRedownloadDao.requestCancellation(operation.operationId, 101L),
        )

        assertNull(downloadRepository.restoreUndo(undoHandle.token))
        assertNull(database.downloadDao.getNullableDownloadById(linkedId))
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertTrue(repository.getOperation(operation.operationId)!!.cancelRequested)
    }

    @Test
    fun ordinaryUndoCommitTerminalizesPendingChildWithAuthoritativeReason() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 308, DownloadRepository.Status.Queued)
        val downloadRepository = DownloadRepository(database)
        val undoHandle = downloadRepository.deleteForUndo(linkedId)!!

        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(
            LowQualityRedownloadOperationState.RUNNING,
            repository.getOperation(operation.operationId)?.stateValue,
        )

        assertEquals(
            setOf(operation.operationId),
            downloadRepository.commitUndo(undoHandle.token),
        )
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(DownloadRepository.REASON_USER_REMOVED, child.reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
        assertNull(downloadRepository.restoreUndo(undoHandle.token))
    }

    @Test
    fun liveUndoTokenSurvivesRoutineReconciliation() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 309, DownloadRepository.Status.Queued)
        val downloadRepository = DownloadRepository(database)
        val undoHandle = downloadRepository.deleteForUndo(linkedId)!!

        LowQualityRedownloadRepository(database).reconcileLinkedDownloads(operation.operationId)

        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        val restoredId = downloadRepository.restoreUndo(undoHandle.token)!!
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(
            DownloadRepository.Status.Queued,
            DownloadRepository.Status.valueOf(database.downloadDao.getDownloadById(restoredId).status),
        )
    }

    @Test
    fun liveUndoTokenSurvivesReconciliationUntilExplicitCommit() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 310, DownloadRepository.Status.Queued)
        val downloadRepository = DownloadRepository(database)
        val undoHandle = downloadRepository.deleteForUndo(linkedId)!!

        LowQualityRedownloadRepository(database).reconcileLinkedDownloads(operation.operationId)
        assertEquals(
            setOf(operation.operationId),
            downloadRepository.commitUndo(undoHandle.token),
        )
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(DownloadRepository.REASON_USER_REMOVED, child.reasonCode)
    }

    @Test
    fun disposingTheUndoOwnerReleasesItsLiveTokenForConvergence() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 315, DownloadRepository.Status.Error)
        val downloadRepository = DownloadRepository(database)
        downloadRepository.deleteForUndo(linkedId)!!

        // Simulate the activity/ViewModel owner disappearing while the app
        // process remains alive.  The process-level snapshot owner commits the
        // exact pending token instead of leaving reconciliation blocked forever.
        downloadRepository.abandonPendingUndoSnapshots()
        LowQualityRedownloadRepository(database).reconcileLinkedDownloads(operation.operationId)

        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(DownloadRepository.REASON_USER_REMOVED, child.reasonCode)
        assertEquals(LowQualityRedownloadOperationState.CANCELLED, repository.getOperation(operation.operationId)?.stateValue)
    }

    @Test
    fun cancellationRequestedChildCannotBeOverwrittenByLateWorkerFailure() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 316, DownloadRepository.Status.Active, "cancel-race")

        assertEquals(1, repository.requestCancellation(operation.operationId).size)
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue,
        )

        // A stale E1 callback arrives after phase one.  It must converge the
        // revocation to cancellation rather than write FAILED.
        assertEquals(
            operation.operationId,
            repository.markDownloadState(
                linkedId,
                LowQualityRedownloadItemState.FAILED,
                "LATE_FAILURE",
                expectedExecutionId = "cancel-race",
            ),
        )
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(LowQualityRedownloadRepository.REASON_USER_CANCELLED, child.reasonCode)
        assertEquals(LowQualityRedownloadOperationState.CANCELLED, repository.getOperation(operation.operationId)?.stateValue)
    }

    @Test
    fun durableDownloadErrorCanReconcileLinkedLedgerWithoutRestart() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 317, DownloadRepository.Status.Error)

        val affectedOperation = repository.reconcileDownload(linkedId)
        assertEquals(operation.operationId, affectedOperation)
        assertEquals(LowQualityRedownloadItemState.FAILED, repository.getItems(operation.operationId).single().stateValue)
        assertEquals(LowQualityRedownloadOperationState.FAILED, repository.getOperation(operation.operationId)?.stateValue)

        // Reconciliation is idempotent once the durable child is terminal.
        repository.reconcileDownload(linkedId)
        assertEquals(LowQualityRedownloadItemState.FAILED, repository.getItems(operation.operationId).single().stateValue)
        assertEquals(LowQualityRedownloadOperationState.FAILED, repository.getOperation(operation.operationId)?.stateValue)
    }

    @Test
    fun bulkErrorDeletionConvergesLinkedLedgerAndReturnsOperationIds() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 318, DownloadRepository.Status.Error)
        database.historyReplacementBarrierDao.insertIfAbsent(
            HistoryReplacementBarrier(
                downloadId = linkedId,
                operationId = operation.operationId,
                historyId = 318,
                expectedSourceUrl = "https://example.com/318",
                expectedType = DownloadType.video.name,
                issueCode = DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH.name,
                issueStage = "HISTORY",
                createdAt = 1L,
            )
        )

        assertEquals(setOf(operation.operationId), DownloadRepository(database).deleteErrored())
        assertNull(database.downloadDao.getNullableDownloadById(linkedId))
        assertNull(database.historyReplacementBarrierDao.getByDownloadId(linkedId))
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(DownloadRepository.REASON_USER_REMOVED, child.reasonCode)
        assertEquals(LowQualityRedownloadOperationState.CANCELLED, repository.getOperation(operation.operationId)?.stateValue)
    }

    @Test
    fun reconstructedProcessCommitsAbandonedPendingRemoval() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 311, DownloadRepository.Status.Queued)
        val downloadRepository = DownloadRepository(database)
        downloadRepository.deleteForUndo(linkedId)!!

        // A new process has no live in-memory Undo token registry.
        DownloadRepository.clearLivePendingRemovalTokensForTest()
        LowQualityRedownloadRepository(database).reconcileLinkedDownloads(operation.operationId)

        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(DownloadRepository.REASON_USER_REMOVED, child.reasonCode)
    }

    @Test
    fun terminalLowQualityLedgerRejectsHistorySuccessCompletion() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 305, DownloadRepository.Status.Active)
        repository.markDownloadState(
            linkedId,
            LowQualityRedownloadItemState.FAILED,
            "HISTORY_REPLACEMENT_SOURCE_MISMATCH",
        )

        var rejected = false
        try {
            DownloadRepository(database).completeAndDelete(linkedId)
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(
            DownloadRepository.Status.Active.name,
            database.downloadDao.getDownloadById(linkedId).status,
        )
        assertEquals(
            LowQualityRedownloadItemState.FAILED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(
            LowQualityRedownloadOperationState.FAILED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
    }

    @Test
    fun terminalLowQualityLedgerCannotBeClaimedAsAReplacementRetry() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 307, DownloadRepository.Status.Queued)
        repository.markDownloadState(
            linkedId,
            LowQualityRedownloadItemState.FAILED,
            "HISTORY_REPLACEMENT_TYPE_MISMATCH",
        )
        database.downloadDao.setStatus(linkedId, DownloadRepository.Status.Queued.name)

        val current = database.downloadDao.getDownloadById(linkedId)
        assertEquals(
            0,
            database.downloadDao.claimDownloadForWorker(
                id = linkedId,
                expectedOperationId = current.operationId,
                expectedRetryAttempt = current.retryAttempt,
                executionId = "terminal-retry-attempt",
            )
        )
        assertEquals(
            DownloadRepository.Status.Queued.name,
            database.downloadDao.getDownloadById(linkedId).status,
        )
        assertEquals(
            LowQualityRedownloadItemState.FAILED,
            repository.getItems(operation.operationId).single().stateValue,
        )
    }

    @Test
    fun queueReorderChangesOnlyOrderPositionAndKeepsLedgerLink() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        repository.checkpointScan(
            operation.operationId,
            historyId = 7,
            candidate = LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = 7,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name
            ),
            failed = false
        )
        val linkedId = repository.linkDownloadAtomically(operation.operationId, 7, download(7))!!
        val secondId = database.downloadDao.insert(download(8))
        val thirdId = database.downloadDao.insert(download(9))
        val originalIds = setOf(linkedId, secondId, thirdId)

        database.downloadDao.putAtBottomOfTheQueue(listOf(linkedId))
        assertEquals(listOf(secondId, thirdId, linkedId), database.downloadDao.getQueuedDownloadsListIDs())
        database.downloadDao.putAtTopOfTheQueue(listOf(linkedId, thirdId))
        assertEquals(listOf(thirdId, linkedId, secondId), database.downloadDao.getQueuedDownloadsListIDs())
        database.downloadDao.putAtPosition(linkedId, secondId)
        assertEquals(listOf(thirdId, secondId, linkedId), database.downloadDao.getQueuedDownloadsListIDs())

        assertEquals(originalIds, database.downloadDao.getQueuedDownloadsListIDs().toSet())
        assertEquals(linkedId, repository.getItems(operation.operationId).single().downloadId)
    }

    @Test
    fun knownQueueRemovalCancelsLinkedChildBeforeDeletingDownload() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        repository.checkpointScan(
            operation.operationId,
            historyId = 7,
            candidate = LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = 7,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name
            ),
            failed = false
        )
        val linkedId = repository.linkDownloadAtomically(operation.operationId, 7, download(7))!!

        DownloadRepository(database).deleteAllWithIDs(listOf(linkedId))

        assertEquals(null, database.downloadDao.getNullableDownloadById(linkedId))
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(DownloadRepository.REASON_USER_REMOVED, child.reasonCode)
        assertEquals(
            com.ireum.ytdl.database.models.LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun queuedAndActiveExplicitCancellationAreAtomicAndFinalizeOnceReady() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val queuedId = linkDownload(operation.operationId, 7, DownloadRepository.Status.Queued)
        val activeId = linkDownload(operation.operationId, 8, DownloadRepository.Status.Active)
        val downloadRepository = DownloadRepository(database)

        assertEquals(setOf(operation.operationId), downloadRepository.cancelByUser(queuedId))
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getDownloadById(queuedId).status
        )
        assertEquals(LowQualityRedownloadOperationState.RUNNING, repository.getActiveOperation()?.stateValue)

        assertEquals(setOf(operation.operationId), downloadRepository.cancelByUser(activeId))
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getDownloadById(activeId).status
        )
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue
        )
        assertNull(repository.getActiveOperation())
    }

    @Test
    fun clearQueuePrimitiveCancelsLinkedQueuedChildAndFinalizesLedger() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(
            operation.operationId,
            historyId = 81,
            status = DownloadRepository.Status.Queued,
        )

        val result = DownloadRepository(database).cancelActiveQueuedWithResult()

        assertEquals(null, result.failure)
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getNullableDownloadById(linkedId)?.status,
        )
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
    }

    @Test
    fun waitingScheduledAndPausedUserActionsKeepDistinctReasons() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val waitingId = linkDownload(
            operation.operationId,
            11,
            DownloadRepository.Status.WaitingForMembership
        )
        val scheduledId = linkDownload(
            operation.operationId,
            12,
            DownloadRepository.Status.Scheduled
        )
        val pausedId = linkDownload(operation.operationId, 13, DownloadRepository.Status.Paused)
        val downloadRepository = DownloadRepository(database)

        downloadRepository.cancelByUser(waitingId)
        downloadRepository.cancelByUser(scheduledId)
        assertEquals(setOf(operation.operationId), downloadRepository.deleteAllWithIDs(listOf(pausedId)))

        val byHistoryId = repository.getItems(operation.operationId).associateBy { it.historyId }
        assertEquals(DownloadRepository.REASON_USER_CANCELLED, byHistoryId.getValue(11).reasonCode)
        assertEquals(DownloadRepository.REASON_USER_CANCELLED, byHistoryId.getValue(12).reasonCode)
        assertEquals(DownloadRepository.REASON_USER_REMOVED, byHistoryId.getValue(13).reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun mixedSuccessAndIndividualCancellationProducePartialFailureSummary() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val succeededId = linkDownload(operation.operationId, 21, DownloadRepository.Status.Active)
        val cancelledId = linkDownload(operation.operationId, 22, DownloadRepository.Status.Queued)

        repository.markDownloadState(succeededId, LowQualityRedownloadItemState.SUCCEEDED)
        DownloadRepository(database).cancelByUser(cancelledId)

        assertEquals(
            LowQualityRedownloadOperationState.PARTIAL_FAILURE,
            repository.getOperation(operation.operationId)?.stateValue
        )
        val progress = repository.progress(operation.operationId)!!
        assertEquals(1, progress.succeeded)
        assertEquals(1, progress.cancelled)
    }

    @Test
    fun unrelatedExplicitCancellationDoesNotTouchLedger() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        linkDownload(operation.operationId, 31, DownloadRepository.Status.Queued)
        val unrelatedId = database.downloadDao.insert(download(99))

        assertTrue(DownloadRepository(database).cancelByUser(unrelatedId).isEmpty())
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue
        )
        assertEquals(LowQualityRedownloadOperationState.RUNNING, repository.getActiveOperation()?.stateValue)
    }

    @Test
    fun repeatedCancellationAndLateCallbacksAreNotificationIneligible() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 41, DownloadRepository.Status.Active)
        val downloadRepository = DownloadRepository(database)

        assertEquals(setOf(operation.operationId), downloadRepository.cancelByUser(linkedId))
        assertTrue(downloadRepository.cancelByUser(linkedId).isEmpty())
        assertNull(repository.markDownloadState(linkedId, LowQualityRedownloadItemState.SUCCEEDED))
        assertNull(repository.markDownloadState(linkedId, LowQualityRedownloadItemState.FAILED))
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).single().stateValue
        )
    }

    @Test
    fun recoverableActiveToQueuedStopRemainsNonterminalDuringReconciliation() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 51, DownloadRepository.Status.Active)
        database.downloadDao.setStatus(linkedId, DownloadRepository.Status.Queued.name)

        val downloads = repository.reconcileLinkedDownloads(operation.operationId)

        assertEquals(DownloadRepository.Status.Queued.name, downloads.single().status)
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue
        )
        assertEquals(LowQualityRedownloadOperationState.RUNNING, repository.getActiveOperation()?.stateValue)
    }

    @Test
    fun cancelledRowCleanupTerminalizesLegacyChildBeforeDeletion() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 61, DownloadRepository.Status.Active)
        database.downloadDao.setStatus(linkedId, DownloadRepository.Status.Cancelled.name)

        assertEquals(setOf(operation.operationId), DownloadRepository(database).deleteCancelled())

        assertNull(database.downloadDao.getNullableDownloadById(linkedId))
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(DownloadRepository.REASON_USER_REMOVED, child.reasonCode)
    }

    @Test
    fun recreationPreservesUserCancellationWithoutMissingChildFailure() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 71, DownloadRepository.Status.Active)
        DownloadRepository(database).cancelByUser(linkedId)

        val reconstructed = LowQualityRedownloadRepository(database)
        reconstructed.reconcileLinkedDownloads(operation.operationId)

        val child = reconstructed.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(DownloadRepository.REASON_USER_CANCELLED, child.reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            reconstructed.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun startupRecoveryCompletesPersistedBatchCancellationWithoutRestartingDownloads() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 72, DownloadRepository.Status.Queued)
        assertTrue(
            repository.advancePhase(
                operation.operationId,
                LowQualityRedownloadPhase.SCANNING,
                LowQualityRedownloadPhase.DOWNLOADING
            )
        )
        assertEquals(listOf(linkedId), repository.requestCancellation(operation.operationId))

        val reconstructed = LowQualityRedownloadRepository(database)
        val recoveredOperation = reconstructed.getActiveOperation()!!
        var enqueueSignalled = false
        var downloadWorkerRestartSignalled = false
        var notificationRefreshed = false
        var cleanedUpIds = emptyList<Long>()

        dispatchLowQualityRedownloadRecovery(
            operation = recoveredOperation,
            completeCancellation = { operationId ->
                cleanedUpIds = reconstructed.completePersistedCancellation(operationId)
                notificationRefreshed = true
            },
            enqueuePhase = { _, _ -> enqueueSignalled = true },
            reconcileDownloads = { downloadWorkerRestartSignalled = true },
            refreshNotification = { notificationRefreshed = true }
        )

        assertEquals(listOf(linkedId), cleanedUpIds)
        assertFalse(enqueueSignalled)
        assertFalse(downloadWorkerRestartSignalled)
        assertTrue(notificationRefreshed)
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getDownloadById(linkedId).status
        )
        val child = reconstructed.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(LowQualityRedownloadRepository.REASON_USER_CANCELLED, child.reasonCode)
        val terminal = reconstructed.getOperation(operation.operationId)!!
        assertEquals(LowQualityRedownloadOperationState.CANCELLED, terminal.stateValue)
        assertEquals(LowQualityRedownloadRepository.REASON_USER_CANCELLED, terminal.terminalReason)
        assertNull(reconstructed.getActiveOperation())
    }

    @Test
    fun terminalSummaryRemainsQueryableButIsExcludedFromStartupRecovery() = runBlocking {
        val completed = repository.createOrReconnect(now = 100)
        repository.finishNoCandidates(completed.operationId)

        assertNull(repository.getActiveOperation())
        assertEquals(completed.operationId, repository.getCurrentOperation()?.operationId)
        assertTrue(repository.progress(completed.operationId)!!.isTerminal)

        val active = repository.createOrReconnect(now = 200)
        assertEquals(active.operationId, repository.getActiveOperation()?.operationId)
        assertEquals(active.operationId, repository.getCurrentOperation()?.operationId)
        assertNotEquals(completed.operationId, active.operationId)
    }

    @Test
    fun waitingForMembershipPendingCancellationUndoRestoresExactWaitingAuthority() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 84, DownloadRepository.Status.Queued)
        val sourceId = database.observeSourcesDao.insert(
            ObserveSourcesItem(
                id = 0,
                name = "Membership source",
                url = "https://example.com/membership-source",
                downloadItemTemplate = download(84),
                everyNr = 1,
                everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
                everyTime = 0,
                weeklyConfig = null,
                monthlyConfig = null,
                status = ObserveSourcesRepository.SourceStatus.ACTIVE,
                startsTime = 0,
                endsDate = 0,
                endsAfterCount = 0,
                runCount = 0,
                getOnlyNewUploads = false,
                retryMissingDownloads = false,
                ignoredLinks = mutableListOf(),
                alreadyProcessedLinks = mutableListOf(),
                syncWithSource = false,
            )
        )
        val linkedDownload = database.downloadDao.getDownloadById(linkedId)
        database.downloadDao.updateRaw(linkedDownload.copy(observeSourceId = sourceId))
        assertEquals(
            1,
            database.observeSourcesDao.parkDownloadForMembership(
                downloadId = linkedId,
                sourceId = sourceId,
                expectedStatus = DownloadRepository.Status.Queued.name,
                issueCode = DownloadIssueCode.MEMBERSHIP_REQUIRED.name,
                issueStage = "DOWNLOAD",
            )
        )
        assertEquals(
            1,
            database.lowQualityRedownloadDao.setItemStateByDownloadId(
                downloadId = linkedId,
                state = LowQualityRedownloadItemState.WAITING.name,
                reason = "",
                updatedAt = System.currentTimeMillis(),
            )
        )
        assertEquals(
            LowQualityRedownloadItemState.WAITING,
            repository.getItems(operation.operationId).single().stateValue,
        )

        val downloadRepository = DownloadRepository(database)
        val pending = downloadRepository.beginUndoableCancellation(linkedId)
        val token = pending.pendingToken!!
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue,
        )

        val restored = downloadRepository.undoPendingCancellation(
            id = linkedId,
            token = token,
            originalStatus = DownloadRepository.Status.WaitingForMembership,
        )

        assertEquals(DownloadRepository.Status.WaitingForMembership, restored.restoredStatus)
        val restoredDownload = database.downloadDao.getDownloadById(linkedId)
        assertEquals(
            DownloadRepository.Status.WaitingForMembership.name,
            restoredDownload.status,
        )
        assertEquals(sourceId, restoredDownload.observeSourceId)
        assertEquals(DownloadIssueCode.MEMBERSHIP_REQUIRED.name, restoredDownload.lastIssueCode)
        assertEquals("DOWNLOAD", restoredDownload.lastIssueStage)
        assertEquals(
            LowQualityRedownloadItemState.WAITING,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(
            "",
            repository.getItems(operation.operationId).single().reasonCode,
        )
        assertEquals(
            LowQualityRedownloadOperationState.RUNNING,
            repository.getOperation(operation.operationId)?.stateValue,
        )
        assertFalse(repository.getOperation(operation.operationId)?.cancelRequested ?: true)
        assertFalse(database.downloadDao.getQueuedDownloadsListIDs().contains(linkedId))
        assertEquals(
            0,
            database.downloadDao.claimDownloadForWorker(
                id = linkedId,
                expectedOperationId = restoredDownload.operationId,
                expectedRetryAttempt = restoredDownload.retryAttempt,
                executionId = "waiting-undo-claim",
            )
        )
    }

    @Test
    fun pendingCancellationUndoUsesBeginTimeStatusNotStaleUiStatus() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 85, DownloadRepository.Status.Queued)
        val sourceId = database.observeSourcesDao.insert(
            ObserveSourcesItem(
                id = 0,
                name = "Membership source",
                url = "https://example.com/membership-source-stale-undo",
                downloadItemTemplate = download(85),
                everyNr = 1,
                everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
                everyTime = 0,
                weeklyConfig = null,
                monthlyConfig = null,
                status = ObserveSourcesRepository.SourceStatus.ACTIVE,
                startsTime = 0,
                endsDate = 0,
                endsAfterCount = 0,
                runCount = 0,
                getOnlyNewUploads = false,
                retryMissingDownloads = false,
                ignoredLinks = mutableListOf(),
                alreadyProcessedLinks = mutableListOf(),
                syncWithSource = false,
            )
        )
        val linkedDownload = database.downloadDao.getDownloadById(linkedId)
        database.downloadDao.updateRaw(linkedDownload.copy(observeSourceId = sourceId))
        assertEquals(
            1,
            database.observeSourcesDao.parkDownloadForMembership(
                downloadId = linkedId,
                sourceId = sourceId,
                expectedStatus = DownloadRepository.Status.Queued.name,
                issueCode = DownloadIssueCode.MEMBERSHIP_REQUIRED.name,
                issueStage = "DOWNLOAD",
            )
        )
        assertEquals(
            1,
            database.lowQualityRedownloadDao.setItemStateByDownloadId(
                downloadId = linkedId,
                state = LowQualityRedownloadItemState.WAITING.name,
                reason = "",
                updatedAt = System.currentTimeMillis(),
            )
        )

        // This is the stale item retained by the confirmation UI.  The real
        // membership requeue changes the exact Download/child retry state;
        // the Undo authority must still bind to the state seen when
        // cancellation begins.
        val staleUiStatus = DownloadRepository.Status.WaitingForMembership
        assertEquals(listOf(linkedId), database.observeSourcesDao.requeueMembershipWaiting(sourceId))
        assertEquals(
            DownloadRepository.Status.Queued.name,
            database.downloadDao.getDownloadById(linkedId).status,
        )

        val downloadRepository = DownloadRepository(database)
        val pending = downloadRepository.beginUndoableCancellation(linkedId)
        val token = pending.pendingToken!!
        val restored = downloadRepository.undoPendingCancellation(
            id = linkedId,
            token = token,
            originalStatus = staleUiStatus,
        )

        assertEquals(DownloadRepository.Status.Queued, restored.restoredStatus)
        assertEquals(
            DownloadRepository.Status.Queued.name,
            database.downloadDao.getDownloadById(linkedId).status,
        )
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(
            "",
            repository.getItems(operation.operationId).single().reasonCode,
        )
        assertEquals(LowQualityRedownloadOperationState.RUNNING, repository.getOperation(operation.operationId)?.stateValue)
        assertFalse(repository.getOperation(operation.operationId)?.cancelRequested ?: true)
        assertFalse(
            database.downloadDao.getMembershipWaitingDownloads().any { it.id == linkedId }
        )
        assertTrue(database.downloadDao.getQueuedDownloadsListIDs().contains(linkedId))

        val claimed = claimDownloadThroughProductionAdmission(
            context = ApplicationProvider.getApplicationContext(),
            dbManager = database,
            candidate = database.downloadDao.getDownloadById(linkedId),
            concurrentDownloadLimit = 1,
        )
        assertTrue(claimed != null)
        val claimedItem = checkNotNull(claimed)
        assertEquals(DownloadRepository.Status.Active.name, claimedItem.status)
        assertTrue(claimedItem.executionId.isNotBlank())
        assertEquals(claimedItem.executionId, DownloadWorkerExecutionOwners.ownerOf(linkedId))
        DownloadWorkerExecutionOwners.release(linkedId, claimedItem.executionId)
    }

    @Test
    fun lowQualityMembershipRetryRequeueIsActuallyClaimable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 86, DownloadRepository.Status.Queued)
        val sourceId = database.observeSourcesDao.insert(
            ObserveSourcesItem(
                id = 0,
                name = "Membership retry source",
                url = "https://example.com/membership-retry-source",
                downloadItemTemplate = download(86),
                everyNr = 1,
                everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
                everyTime = 0,
                weeklyConfig = null,
                monthlyConfig = null,
                status = ObserveSourcesRepository.SourceStatus.ACTIVE,
                startsTime = 0,
                endsDate = 0,
                endsAfterCount = 0,
                runCount = 0,
                getOnlyNewUploads = false,
                retryMissingDownloads = false,
                ignoredLinks = mutableListOf(),
                alreadyProcessedLinks = mutableListOf(),
                syncWithSource = false,
            )
        )
        database.downloadDao.updateRaw(
            database.downloadDao.getDownloadById(linkedId).copy(observeSourceId = sourceId)
        )
        assertEquals(
            1,
            database.observeSourcesDao.parkDownloadForMembership(
                downloadId = linkedId,
                sourceId = sourceId,
                expectedStatus = DownloadRepository.Status.Queued.name,
                issueCode = DownloadIssueCode.MEMBERSHIP_REQUIRED.name,
                issueStage = "DOWNLOAD",
            )
        )
        assertEquals(
            1,
            database.lowQualityRedownloadDao.setItemStateByDownloadId(
                downloadId = linkedId,
                state = LowQualityRedownloadItemState.WAITING.name,
                reason = "",
                updatedAt = System.currentTimeMillis(),
            )
        )

        val requeuedIds = database.observeSourcesDao.requeueMembershipWaiting(sourceId)
        assertEquals(listOf(linkedId), requeuedIds)
        val requeuedDownload = database.downloadDao.getDownloadById(linkedId)
        assertEquals(DownloadRepository.Status.Queued.name, requeuedDownload.status)
        assertEquals(DownloadIssueCode.MEMBERSHIP_REQUIRED.name, requeuedDownload.lastIssueCode)
        assertEquals("DOWNLOAD", requeuedDownload.lastIssueStage)
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(LowQualityRedownloadOperationState.RUNNING, repository.getOperation(operation.operationId)?.stateValue)
        assertFalse(repository.getOperation(operation.operationId)?.cancelRequested ?: true)

        // ObserveSourceWorker uses this guarded transition when starting the
        // retry path fails. It must restore the complete waiting authority,
        // including the linked child, before a later retry.
        assertEquals(
            1,
            database.observeSourcesDao.restoreMembershipWaiting(sourceId, requeuedIds),
        )
        val compensatedDownload = database.downloadDao.getDownloadById(linkedId)
        assertEquals(
            DownloadRepository.Status.WaitingForMembership.name,
            compensatedDownload.status,
        )
        assertEquals(DownloadIssueCode.MEMBERSHIP_REQUIRED.name, compensatedDownload.lastIssueCode)
        assertEquals("DOWNLOAD", compensatedDownload.lastIssueStage)
        assertEquals(
            LowQualityRedownloadItemState.WAITING,
            repository.getItems(operation.operationId).single().stateValue,
        )

        assertEquals(listOf(linkedId), database.observeSourcesDao.requeueMembershipWaiting(sourceId))
        val claimCandidate = database.downloadDao.getDownloadById(linkedId)
        assertEquals(DownloadRepository.Status.Queued.name, claimCandidate.status)
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertTrue(repository.reconcileDownload(linkedId) != null)
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue,
        )

        val claimed = claimDownloadThroughProductionAdmission(
            context = context,
            dbManager = database,
            candidate = claimCandidate,
            concurrentDownloadLimit = 1,
        )
        assertTrue(claimed != null)
        val claimedItem = checkNotNull(claimed)
        assertEquals(DownloadRepository.Status.Active.name, claimedItem.status)
        assertTrue(claimedItem.executionId.isNotBlank())
        assertEquals(claimedItem.executionId, DownloadWorkerExecutionOwners.ownerOf(linkedId))
        assertEquals("", database.downloadDao.getDownloadById(linkedId).lastIssueCode)
        DownloadWorkerExecutionOwners.release(linkedId, claimedItem.executionId)
    }

    @Test
    fun membershipPendingUndoCannotRestoreAfterSourceRevocation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 87, DownloadRepository.Status.Queued)
        val sourceId = insertActiveMembershipSource(
            historyId = 87,
            url = "https://example.com/membership-source-pending-undo-revoked",
        )
        database.downloadDao.updateRaw(
            database.downloadDao.getDownloadById(linkedId).copy(observeSourceId = sourceId)
        )
        assertEquals(
            1,
            database.observeSourcesDao.parkDownloadForMembership(
                downloadId = linkedId,
                sourceId = sourceId,
                expectedStatus = DownloadRepository.Status.Queued.name,
                issueCode = DownloadIssueCode.MEMBERSHIP_REQUIRED.name,
                issueStage = "DOWNLOAD",
            )
        )
        assertEquals(listOf(linkedId), database.observeSourcesDao.requeueMembershipWaiting(sourceId))
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue,
        )

        val downloadRepository = DownloadRepository(database)
        val pending = downloadRepository.beginUndoableCancellation(linkedId)
        val token = checkNotNull(pending.pendingToken)
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue,
        )

        val sourceRepository = ObserveSourcesRepository(
            observeSourcesDao = database.observeSourcesDao,
            workManager = WorkManager.getInstance(context),
            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context),
        )
        val stoppedSource = database.observeSourcesDao
            .getByID(sourceId)
            .copy(status = ObserveSourcesRepository.SourceStatus.STOPPED)
        assertEquals(listOf(linkedId), sourceRepository.update(stoppedSource))

        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getDownloadById(linkedId).status,
        )
        val revokedChild = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, revokedChild.stateValue)
        assertEquals(LowQualityRedownloadRepository.REASON_USER_CANCELLED, revokedChild.reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )

        val undo = downloadRepository.undoPendingCancellation(
            id = linkedId,
            token = token,
            originalStatus = DownloadRepository.Status.Queued,
        )
        assertNull(undo.restoredStatus)
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getDownloadById(linkedId).status,
        )
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertNull(
            claimDownloadThroughProductionAdmission(
                context = context,
                dbManager = database,
                candidate = database.downloadDao.getDownloadById(linkedId),
                concurrentDownloadLimit = 1,
            )
        )

        // A delete-for-Undo carrier has no Download row for source
        // revocation to inspect.  Restoration must therefore revalidate the
        // exact membership source before recreating the row, then commit the
        // removal token when the source has already been stopped.
        val removalOperation = repository.createOrReconnect(now = 200)
        val removalId = linkDownload(
            removalOperation.operationId,
            89,
            DownloadRepository.Status.Queued,
        )
        val removalSourceId = insertActiveMembershipSource(
            historyId = 89,
            url = "https://example.com/membership-source-pending-removal",
        )
        database.downloadDao.updateRaw(
            database.downloadDao.getDownloadById(removalId).copy(observeSourceId = removalSourceId)
        )
        assertEquals(
            1,
            database.observeSourcesDao.parkDownloadForMembership(
                downloadId = removalId,
                sourceId = removalSourceId,
                expectedStatus = DownloadRepository.Status.Queued.name,
                issueCode = DownloadIssueCode.MEMBERSHIP_REQUIRED.name,
                issueStage = "DOWNLOAD",
            )
        )
        assertEquals(
            listOf(removalId),
            database.observeSourcesDao.requeueMembershipWaiting(removalSourceId),
        )
        val removalHandle = checkNotNull(downloadRepository.deleteForUndo(removalId))
        assertNull(database.downloadDao.getNullableDownloadById(removalId))
        assertEquals(
            emptyList<Long>(),
            sourceRepository.update(
                database.observeSourcesDao
                    .getByID(removalSourceId)
                    .copy(status = ObserveSourcesRepository.SourceStatus.STOPPED)
            ),
        )
        assertNull(downloadRepository.restoreUndo(removalHandle.token))
        assertNull(database.downloadDao.getNullableDownloadById(removalId))
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(removalOperation.operationId).single().stateValue,
        )
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(removalOperation.operationId)?.stateValue,
        )
    }

    @Test
    fun sourceRevocationConvergesLinkedMembershipCancellation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 88, DownloadRepository.Status.Queued)
        val sourceId = insertActiveMembershipSource(
            historyId = 88,
            url = "https://example.com/membership-source-revocation",
        )
        database.downloadDao.updateRaw(
            database.downloadDao.getDownloadById(linkedId).copy(observeSourceId = sourceId)
        )
        assertEquals(
            1,
            database.observeSourcesDao.parkDownloadForMembership(
                downloadId = linkedId,
                sourceId = sourceId,
                expectedStatus = DownloadRepository.Status.Queued.name,
                issueCode = DownloadIssueCode.MEMBERSHIP_REQUIRED.name,
                issueStage = "DOWNLOAD",
            )
        )
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue,
        )

        val sourceRepository = ObserveSourcesRepository(
            observeSourcesDao = database.observeSourcesDao,
            workManager = WorkManager.getInstance(context),
            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context),
        )
        val stoppedSource = database.observeSourcesDao
            .getByID(sourceId)
            .copy(status = ObserveSourcesRepository.SourceStatus.STOPPED)
        assertEquals(listOf(linkedId), sourceRepository.update(stoppedSource))

        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getDownloadById(linkedId).status,
        )
        val cancelledChild = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, cancelledChild.stateValue)
        assertEquals(LowQualityRedownloadRepository.REASON_USER_CANCELLED, cancelledChild.reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
        assertNull(
            claimDownloadThroughProductionAdmission(
                context = context,
                dbManager = database,
                candidate = database.downloadDao.getDownloadById(linkedId),
                concurrentDownloadLimit = 1,
            )
        )

        // The startup-independent owner consumes both durable Undo carrier
        // types after the process-local Snackbar authority is gone.  The
        // ordinary runtime-gated reconciliation remains intentionally
        // uncalled when readiness is false.
        val downloadRepository = DownloadRepository(database)
        val abandonedOperation = repository.createOrReconnect(now = 200)
        val abandonedCancellationId = linkDownload(
            abandonedOperation.operationId,
            90,
            DownloadRepository.Status.Queued,
        )
        val abandonedCancellationToken = checkNotNull(
            downloadRepository.beginUndoableCancellation(abandonedCancellationId).pendingToken
        )
        assertTrue(
            abandonedCancellationToken.startsWith(
                DownloadRepository.PENDING_CANCELLATION_TOKEN_PREFIX
            )
        )

        val abandonedRemovalId = linkDownload(
            abandonedOperation.operationId,
            91,
            DownloadRepository.Status.Queued,
        )
        val abandonedRemovalSourceId = insertActiveMembershipSource(
            historyId = 91,
            url = "https://example.com/membership-source-abandoned-removal",
        )
        database.downloadDao.updateRaw(
            database.downloadDao.getDownloadById(abandonedRemovalId)
                .copy(observeSourceId = abandonedRemovalSourceId)
        )
        assertEquals(
            1,
            database.observeSourcesDao.parkDownloadForMembership(
                downloadId = abandonedRemovalId,
                sourceId = abandonedRemovalSourceId,
                expectedStatus = DownloadRepository.Status.Queued.name,
                issueCode = DownloadIssueCode.MEMBERSHIP_REQUIRED.name,
                issueStage = "DOWNLOAD",
            )
        )
        assertEquals(
            listOf(abandonedRemovalId),
            database.observeSourcesDao.requeueMembershipWaiting(abandonedRemovalSourceId),
        )
        val abandonedRemovalHandle = checkNotNull(
            downloadRepository.deleteForUndo(abandonedRemovalId)
        )
        assertNull(database.downloadDao.getNullableDownloadById(abandonedRemovalId))
        // Simulate process death: the in-memory removal snapshot and its live
        // Undo ownership are gone, while the exact durable child token stays.
        DownloadRepository.clearLivePendingRemovalTokensForTest()

        var ordinaryReconciliationRan = false
        runStartupReconciliation(
            readiness = CompletableDeferred(false),
            reconcile = { ordinaryReconciliationRan = true },
            reportFailure = { throw AssertionError("Unexpected ordinary recovery failure", it) },
        )
        assertFalse(ordinaryReconciliationRan)
        var abandonedUndoRecovered = false
        var abandonedUndoFailure: Exception? = null
        runStartupCancellationReconciliation(
            reconcile = {
                abandonedUndoRecovered =
                    LowQualityRedownloadManager.get(context)
                        .reconcileCancellationDebt(database)
            },
            reportFailure = { abandonedUndoFailure = it },
        )
        assertTrue(abandonedUndoRecovered)
        assertNull(abandonedUndoFailure)
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(abandonedOperation.operationId)
                .single { it.downloadId == abandonedCancellationId }
                .stateValue,
        )
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(abandonedOperation.operationId)?.stateValue,
        )
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(abandonedOperation.operationId)
                .single { it.downloadId == abandonedRemovalId }
                .stateValue,
        )
        assertEquals(
            DownloadRepository.REASON_USER_REMOVED,
            repository.getItems(abandonedOperation.operationId)
                .single { it.downloadId == abandonedRemovalId }
                .reasonCode,
        )
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(abandonedOperation.operationId)?.stateValue,
        )
        assertNull(database.downloadDao.getNullableDownloadById(abandonedRemovalId))
        // The local handle is intentionally no longer restorable after the
        // simulated process death.
        assertNull(downloadRepository.restoreUndo(abandonedRemovalHandle.token))
    }

    @Test
    fun queuedBatchCancellationUndoRestoresSameDownloadAndAllowsSuccess() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 81, DownloadRepository.Status.Queued)
        val originalOrder = database.downloadDao.getDownloadById(linkedId).orderPosition
        val downloadRepository = DownloadRepository(database)

        val pending = downloadRepository.beginUndoableCancellation(linkedId)
        val token = pending.pendingToken!!
        assertTrue(pending.affectedOperationIds.isEmpty())
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getDownloadById(linkedId).status
        )
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue
        )
        assertEquals(LowQualityRedownloadOperationState.RUNNING, repository.getActiveOperation()?.stateValue)

        val restored = downloadRepository.undoPendingCancellation(
            linkedId,
            token,
            DownloadRepository.Status.Queued
        )
        assertEquals(DownloadRepository.Status.Queued, restored.restoredStatus)
        val restoredDownload = database.downloadDao.getDownloadById(linkedId)
        assertEquals(linkedId, restoredDownload.id)
        assertEquals(originalOrder, restoredDownload.orderPosition)
        assertEquals(DownloadRepository.Status.Queued.name, restoredDownload.status)
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue
        )

        assertEquals(
            operation.operationId,
            repository.markDownloadState(linkedId, LowQualityRedownloadItemState.SUCCEEDED)
        )
        assertEquals(
            LowQualityRedownloadOperationState.COMPLETED,
            repository.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun pendingCancellationCommitsOnlyForMatchingTokenAndFinalizesLastChild() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 82, DownloadRepository.Status.Queued)
        val downloadRepository = DownloadRepository(database)
        val token = downloadRepository.beginUndoableCancellation(linkedId).pendingToken!!

        assertTrue(downloadRepository.commitPendingCancellation(linkedId, "stale-token").isEmpty())
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue
        )
        assertEquals(
            setOf(operation.operationId),
            downloadRepository.commitPendingCancellation(linkedId, token)
        )
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).single().stateValue
        )
        assertEquals(
            DownloadRepository.REASON_USER_CANCELLED,
            repository.getItems(operation.operationId).single().reasonCode
        )
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue
        )
        assertTrue(downloadRepository.commitPendingCancellation(linkedId, token).isEmpty())
    }

    @Test
    fun restartReconciliationCommitsAbandonedPendingCancellation() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 83, DownloadRepository.Status.Queued)
        val owner = DownloadRepository(database)
        val token = owner.beginUndoableCancellation(linkedId).pendingToken
        assertTrue(token!!.startsWith(DownloadRepository.PENDING_CANCELLATION_TOKEN_PREFIX))

        // Model process death: the durable pending token survives, but the
        // process-local Undo owner does not.
        DownloadRepository.clearLivePendingRemovalTokensForTest()

        val reconstructed = LowQualityRedownloadRepository(database)
        reconstructed.reconcileLinkedDownloads(operation.operationId)

        val child = reconstructed.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.CANCELLED, child.stateValue)
        assertEquals(DownloadRepository.REASON_USER_CANCELLED, child.reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            reconstructed.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun livePendingCancellationSurvivesStartupReconciliationUntilUndo() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 84, DownloadRepository.Status.Queued)
        val owner = DownloadRepository(database)
        val token = checkNotNull(owner.beginUndoableCancellation(linkedId).pendingToken)
        owner.acknowledgeUndoPublication(token)

        assertTrue(DownloadRepository.isLivePendingCancellationToken(token))
        assertFalse(
            LowQualityRedownloadManager.get(context)
                .reconcileCancellationDebt(database)
        )
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(token, repository.getItems(operation.operationId).single().reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.RUNNING,
            repository.getOperation(operation.operationId)?.stateValue,
        )

        assertEquals(
            DownloadRepository.Status.Queued,
            owner.undoPendingCancellation(
                linkedId,
                token,
                DownloadRepository.Status.Queued,
            ).restoredStatus,
        )
        assertFalse(DownloadRepository.isLivePendingCancellationToken(token))
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue,
        )
    }

    @Test
    fun livePendingCancellationSurvivesOrdinaryReconciliation() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 85, DownloadRepository.Status.Queued)
        val owner = DownloadRepository(database)
        val token = checkNotNull(owner.beginUndoableCancellation(linkedId).pendingToken)
        val reconciler = LowQualityRedownloadRepository(database)

        assertEquals(operation.operationId, reconciler.reconcileDownload(linkedId))
        reconciler.reconcileLinkedDownloads(operation.operationId)
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(token, repository.getItems(operation.operationId).single().reasonCode)

        assertEquals(
            DownloadRepository.Status.Queued,
            owner.undoPendingCancellation(
                linkedId,
                token,
                DownloadRepository.Status.Queued,
            ).restoredStatus,
        )
    }

    @Test
    fun abandoningPendingCancellationTransfersAuthorityToRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 86, DownloadRepository.Status.Queued)
        val owner = DownloadRepository(database)
        val token = checkNotNull(owner.beginUndoableCancellation(linkedId).pendingToken)

        owner.abandonPendingUndoSnapshots()
        assertFalse(DownloadRepository.isLivePendingCancellationToken(token))
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )

        // Recovery remains idempotent after the lifecycle handoff has already
        // converged the exact carrier.
        assertFalse(
            LowQualityRedownloadManager.get(context)
                .reconcileCancellationDebt(database)
        )
    }

    @Test
    fun lifecycleAbandonmentRetriesPendingCancellationAfterFirstWriteFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val abandonedId = linkDownload(
            operation.operationId,
            318,
            DownloadRepository.Status.Queued,
        )
        val siblingId = linkDownload(
            operation.operationId,
            319,
            DownloadRepository.Status.Queued,
        )
        val viewModel = DownloadViewModel(context, database, true)
        val siblingOwner = DownloadRepository(database)
        val abandonedToken = checkNotNull(
            viewModel.repository.beginUndoableCancellation(abandonedId).pendingToken
        )
        val siblingToken = checkNotNull(
            siblingOwner.beginUndoableCancellation(siblingId).pendingToken
        )
        siblingOwner.acknowledgeUndoPublication(siblingToken)

        DownloadRepository.pendingCancellationCommitFailureForTesting = {
            IllegalStateException("injected abandoned cancellation commit failure")
        }
        try {
            viewModel.clearForTesting()
            awaitAbandonedUndoOwnerActive(abandonedToken)
            assertFalse(DownloadRepository.isLivePendingCancellationToken(abandonedToken))
            assertTrue(DownloadRepository.isLivePendingCancellationToken(siblingToken))
            assertEquals(
                LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
                repository.getItems(operation.operationId)
                    .single { it.downloadId == abandonedId }
                    .stateValue,
            )

            DownloadRepository.pendingCancellationCommitFailureForTesting = null
            withTimeout(20_000L) {
                while (
                    repository.getItems(operation.operationId)
                        .single { it.downloadId == abandonedId }
                        .stateValue != LowQualityRedownloadItemState.CANCELLED
                ) {
                    delay(25L)
                }
            }
            assertFalse(
                LowQualityRedownloadLedger.isAbandonedUndoConvergenceActiveForTesting(
                    abandonedToken
                )
            )
            assertEquals(
                DownloadRepository.Status.Queued,
                siblingOwner.undoPendingCancellation(
                    siblingId,
                    siblingToken,
                    DownloadRepository.Status.Queued,
                ).restoredStatus,
            )
        } finally {
            DownloadRepository.pendingCancellationCommitFailureForTesting = null
            if (DownloadRepository.isLivePendingCancellationToken(siblingToken)) {
                siblingOwner.undoPendingCancellation(
                    siblingId,
                    siblingToken,
                    DownloadRepository.Status.Queued,
                )
            }
        }
    }

    @Test
    fun lifecycleAbandonmentRetriesPendingRemovalAfterFirstWriteFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(
            operation.operationId,
            320,
            DownloadRepository.Status.Queued,
        )
        val viewModel = DownloadViewModel(context, database, true)
        val handle = checkNotNull(viewModel.repository.deleteForUndo(downloadId))
        val token = handle.token.value
        assertNull(database.downloadDao.getNullableDownloadById(downloadId))

        DownloadRepository.pendingRemovalCommitFailureForTesting = {
            IllegalStateException("injected abandoned removal commit failure")
        }
        LowQualityRedownloadRepository.abandonedPendingRemovalCommitFailureForTesting = {
            IllegalStateException("injected successor removal commit failure")
        }
        try {
            viewModel.clearForTesting()
            awaitAbandonedUndoOwnerActive(token)
            assertFalse(DownloadRepository.isLivePendingRemovalToken(token))
            assertEquals(
                LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
                repository.getItems(operation.operationId).single().stateValue,
            )

            DownloadRepository.pendingRemovalCommitFailureForTesting = null
            LowQualityRedownloadRepository.abandonedPendingRemovalCommitFailureForTesting = null
            withTimeout(20_000L) {
                while (
                    repository.getItems(operation.operationId).single().stateValue !=
                        LowQualityRedownloadItemState.CANCELLED
                ) {
                    delay(25L)
                }
            }
            assertFalse(
                LowQualityRedownloadLedger.isAbandonedUndoConvergenceActiveForTesting(token)
            )
            assertNull(database.downloadDao.getNullableDownloadById(downloadId))
            assertEquals(
                LowQualityRedownloadOperationState.CANCELLED,
                repository.getOperation(operation.operationId)?.stateValue,
            )
        } finally {
            DownloadRepository.pendingRemovalCommitFailureForTesting = null
            LowQualityRedownloadRepository.abandonedPendingRemovalCommitFailureForTesting = null
        }
    }

    @Test
    fun pendingCancellationViewLossBeforePublicationTransfersExactCarrierToRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 324, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val committed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        DownloadRepository.pendingCancellationAfterTransactionForTesting = {
            committed.complete(Unit)
            resume.await()
        }
        val begin = async(Dispatchers.IO) {
            viewModel.beginUndoableCancellation(downloadId)
        }

        committed.await()
        val token = repository.getItems(operation.operationId).single().reasonCode
        DownloadRepository.pendingCancellationCommitFailureForTesting = {
            IllegalStateException("injected unpublished cancellation recovery failure")
        }
        viewModel.abandonPendingUndoCapabilitiesForView()
        awaitAbandonedUndoOwnerActive(token)
        assertFalse(DownloadRepository.isLivePendingCancellationToken(token))
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue,
        )

        resume.complete(Unit)
        assertEquals(token, begin.await())
        DownloadRepository.pendingCancellationCommitFailureForTesting = null
        withTimeout(20_000L) {
            while (repository.getItems(operation.operationId).single().stateValue !=
                LowQualityRedownloadItemState.CANCELLED
            ) {
                delay(25L)
            }
        }
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
    }

    @Test
    fun pendingRemovalViewLossBeforePublicationDoesNotRestoreDeletedDownload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 325, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val committed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        DownloadRepository.pendingRemovalAfterTransactionForTesting = {
            committed.complete(Unit)
            resume.await()
        }
        val deletion = async(Dispatchers.IO) {
            viewModel.deleteDownloadForUndo(downloadId)
        }

        committed.await()
        val token = repository.getItems(operation.operationId).single().reasonCode
        LowQualityRedownloadRepository.abandonedPendingRemovalCommitFailureForTesting = {
            IllegalStateException("injected unpublished removal recovery failure")
        }
        viewModel.abandonPendingUndoCapabilitiesForView()
        awaitAbandonedUndoOwnerActive(token)
        assertFalse(DownloadRepository.isLivePendingRemovalToken(token))
        assertNull(database.downloadDao.getNullableDownloadById(downloadId))
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue,
        )

        resume.complete(Unit)
        assertEquals(downloadId, deletion.await()?.item?.id)
        LowQualityRedownloadRepository.abandonedPendingRemovalCommitFailureForTesting = null
        withTimeout(20_000L) {
            while (repository.getItems(operation.operationId).single().stateValue !=
                LowQualityRedownloadItemState.CANCELLED
            ) {
                delay(25L)
            }
        }
        assertNull(database.downloadDao.getNullableDownloadById(downloadId))
    }

    @Test
    fun removalRestoreFailureAfterOwnerClearKeepsRecoveryOwnership() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 326, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val handle = checkNotNull(viewModel.repository.deleteForUndo(downloadId))
        viewModel.acknowledgeUndoPublication(handle.token.value)
        val claimed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        DownloadRepository.pendingRemovalResolverClaimedForTesting = {
            claimed.complete(Unit)
            resume.await()
        }
        val restore = async(Dispatchers.IO) {
            runCatching { viewModel.restoreDownloadUndo(handle) }
        }

        claimed.await()
        DownloadRepository.pendingRemovalRestoreFailureForTesting = {
            IllegalStateException("injected resolver restore failure")
        }
        LowQualityRedownloadRepository.abandonedPendingRemovalCommitFailureForTesting = {
            IllegalStateException("injected recovery failure")
        }
        viewModel.clearForTesting()
        awaitAbandonedUndoOwnerActive(handle.token.value)
        resume.complete(Unit)
        assertTrue(restore.await().isFailure)
        assertFalse(DownloadRepository.isLivePendingRemovalToken(handle.token.value))
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId).single().stateValue,
        )

        DownloadRepository.pendingRemovalRestoreFailureForTesting = null
        LowQualityRedownloadRepository.abandonedPendingRemovalCommitFailureForTesting = null
        withTimeout(20_000L) {
            while (repository.getItems(operation.operationId).single().stateValue !=
                LowQualityRedownloadItemState.CANCELLED
            ) {
                delay(25L)
            }
        }
        assertNull(database.downloadDao.getNullableDownloadById(downloadId))
    }

    @Test
    fun removalRestoreSuccessAfterOwnerClearResolvesExactlyOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 328, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val handle = checkNotNull(viewModel.repository.deleteForUndo(downloadId))
        viewModel.acknowledgeUndoPublication(handle.token.value)
        val claimed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        DownloadRepository.pendingRemovalResolverClaimedForTesting = {
            claimed.complete(Unit)
            resume.await()
        }
        val restore = async(Dispatchers.IO) {
            viewModel.restoreDownloadUndo(handle)
        }

        claimed.await()
        viewModel.clearForTesting()
        awaitAbandonedUndoOwnerActive(handle.token.value)
        resume.complete(Unit)
        val restoredId = restore.await()
        assertTrue(restoredId != null)
        withTimeout(20_000L) {
            while (repository.getItems(operation.operationId).single().stateValue !=
                LowQualityRedownloadItemState.QUEUED
            ) {
                delay(25L)
            }
        }
        assertFalse(DownloadRepository.isLivePendingRemovalToken(handle.token.value))
        assertFalse(LowQualityRedownloadLedger.isAbandonedUndoConvergenceActiveForTesting(handle.token.value))
        assertTrue(database.downloadDao.getNullableDownloadById(restoredId!!) != null)
    }

    @Test
    fun removalCommitSuccessAfterOwnerClearResolvesExactlyOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 327, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val handle = checkNotNull(viewModel.repository.deleteForUndo(downloadId))
        viewModel.acknowledgeUndoPublication(handle.token.value)
        val claimed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        DownloadRepository.pendingRemovalResolverClaimedForTesting = {
            claimed.complete(Unit)
            resume.await()
        }
        val commit = async(Dispatchers.IO) {
            viewModel.commitDownloadUndo(handle)
        }

        claimed.await()
        viewModel.clearForTesting()
        awaitAbandonedUndoOwnerActive(handle.token.value)
        resume.complete(Unit)
        commit.await()
        withTimeout(20_000L) {
            while (repository.getItems(operation.operationId).single().stateValue !=
                LowQualityRedownloadItemState.CANCELLED
            ) {
                delay(25L)
            }
        }
        assertFalse(DownloadRepository.isLivePendingRemovalToken(handle.token.value))
        assertFalse(LowQualityRedownloadLedger.isAbandonedUndoConvergenceActiveForTesting(handle.token.value))
        assertNull(database.downloadDao.getNullableDownloadById(downloadId))
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
    }

    @Test
    fun pendingCancellationPostCommitRefreshFailureTransfersUnpublishedCarrierToRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 329, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val failRefreshOnce = AtomicBoolean(true)
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 1
        LowQualityRedownloadLedger.refreshFailureForTesting = {
            if (failRefreshOnce.compareAndSet(true, false)) {
                IllegalStateException("injected post-commit refresh failure")
            } else {
                null
            }
        }

        val failure = runCatching {
            viewModel.beginUndoableCancellation(downloadId)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)

        val token = repository.getItems(operation.operationId).single().reasonCode
        assertTrue(DownloadRepository.isValidPendingCancellationToken(token))
        assertFalse(DownloadRepository.isLivePendingCancellationToken(token))
        assertTrue(database.pendingUndoCarrierDao.get(token) != null)
        awaitAbandonedUndoOwnerActive(token)

        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 0
        withTimeout(20_000L) {
            while (repository.getItems(operation.operationId).single().stateValue !=
                LowQualityRedownloadItemState.CANCELLED
            ) {
                delay(25L)
            }
        }
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
        assertNull(database.pendingUndoCarrierDao.get(token))
        assertFalse(LowQualityRedownloadLedger.isAbandonedUndoConvergenceActiveForTesting(token))
    }

    @Test
    fun undoTapBeforeResolverCoroutineClaimPreservesRestoreIntentAcrossViewLoss() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 330, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val handle = checkNotNull(viewModel.repository.deleteForUndo(downloadId))
        viewModel.acknowledgeUndoPublication(handle.token.value)
        val boundaryReached = CompletableDeferred<Unit>()
        val releaseBoundary = CompletableDeferred<Unit>()
        DownloadRepository.pendingRemovalBeforeResolverClaimedForTesting = {
            boundaryReached.complete(Unit)
            releaseBoundary.await()
        }
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 1

        viewModel.restoreDownloadUndoFromUi(handle)
        boundaryReached.await()
        assertEquals(
            PendingUndoResolutionIntent.RESTORE,
            DownloadRepository(database).pendingUndoResolutionIntent(handle.token.value),
        )

        viewModel.abandonPendingUndoCapabilitiesForView()
        awaitAbandonedUndoOwnerActive(handle.token.value)
        assertFalse(DownloadRepository.isLivePendingRemovalToken(handle.token.value))

        releaseBoundary.complete(Unit)
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 0
        withTimeout(20_000L) {
            while (database.downloadDao.getNullableDownloadById(downloadId) == null) {
                delay(25L)
            }
        }
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertNull(database.pendingUndoCarrierDao.get(handle.token.value))
    }

    @Test
    fun pendingCancellationRestoreFailureRetriesRestoreNotCommit() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 331, DownloadRepository.Status.Queued)
        val owner = DownloadRepository(database)
        val token = checkNotNull(owner.beginUndoableCancellation(downloadId).pendingToken)
        owner.acknowledgeUndoPublication(token)
        val failRestoreOnce = AtomicBoolean(true)
        DownloadRepository.pendingCancellationRestoreFailureForTesting = {
            if (failRestoreOnce.compareAndSet(true, false)) {
                IllegalStateException("injected cancellation restore failure")
            } else {
                null
            }
        }
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 1

        assertTrue(
            runCatching {
                owner.undoPendingCancellation(
                    downloadId,
                    token,
                    DownloadRepository.Status.Queued,
                )
            }.isFailure
        )
        assertFalse(DownloadRepository.isLivePendingCancellationToken(token))
        assertEquals(
            PendingUndoResolutionIntent.RESTORE,
            owner.pendingUndoResolutionIntent(token),
        )
        awaitAbandonedUndoOwnerActive(token)
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 0

        withTimeout(20_000L) {
            while (repository.getItems(operation.operationId).single().stateValue !=
                LowQualityRedownloadItemState.QUEUED
            ) {
                delay(25L)
            }
        }
        assertEquals(
            DownloadRepository.Status.Queued.name,
            database.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
        assertNull(database.pendingUndoCarrierDao.get(token))
    }

    @Test
    fun pendingRemovalRestoreFailureWhileViewAliveDoesNotReturnFalseLiveUi() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 332, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val handle = checkNotNull(viewModel.repository.deleteForUndo(downloadId))
        viewModel.acknowledgeUndoPublication(handle.token.value)
        val failRestoreOnce = AtomicBoolean(true)
        DownloadRepository.pendingRemovalRestoreFailureForTesting = {
            if (failRestoreOnce.compareAndSet(true, false)) {
                IllegalStateException("injected removal restore failure")
            } else {
                null
            }
        }
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 1

        assertTrue(runCatching { viewModel.restoreDownloadUndo(handle) }.isFailure)
        assertFalse(DownloadRepository.isLivePendingRemovalToken(handle.token.value))
        assertEquals(
            PendingUndoResolutionIntent.RESTORE,
            viewModel.repository.pendingUndoResolutionIntent(handle.token.value),
        )
        awaitAbandonedUndoOwnerActive(handle.token.value)
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 0

        withTimeout(20_000L) {
            while (database.downloadDao.getNullableDownloadById(downloadId) == null) {
                delay(25L)
            }
        }
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertNull(database.pendingUndoCarrierDao.get(handle.token.value))
    }

    @Test
    fun dismissCommitFailureRetriesCommit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 333, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val handle = checkNotNull(viewModel.repository.deleteForUndo(downloadId))
        viewModel.acknowledgeUndoPublication(handle.token.value)
        val failCommitOnce = AtomicBoolean(true)
        DownloadRepository.pendingRemovalCommitFailureForTesting = {
            if (failCommitOnce.compareAndSet(true, false)) {
                IllegalStateException("injected removal commit failure")
            } else {
                null
            }
        }
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 1

        assertTrue(runCatching { viewModel.commitDownloadUndo(handle) }.isFailure)
        assertFalse(DownloadRepository.isLivePendingRemovalToken(handle.token.value))
        assertEquals(
            PendingUndoResolutionIntent.COMMIT,
            viewModel.repository.pendingUndoResolutionIntent(handle.token.value),
        )
        awaitAbandonedUndoOwnerActive(handle.token.value)
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 0

        withTimeout(20_000L) {
            while (repository.getItems(operation.operationId).single().stateValue !=
                LowQualityRedownloadItemState.CANCELLED
            ) {
                delay(25L)
            }
        }
        assertNull(database.downloadDao.getNullableDownloadById(downloadId))
        assertNull(database.pendingUndoCarrierDao.get(handle.token.value))
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
    }

    @Test
    fun selectedRestoreProcessDeathMatrixRetainsIntentAtEveryResolverBoundary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val owner = DownloadRepository(database)

        // Selection accepted before the asynchronous resolver claims its
        // generation.
        val beforeClaimId = linkDownload(
            operation.operationId,
            334,
            DownloadRepository.Status.Queued,
        )
        val beforeClaim = checkNotNull(owner.deleteForUndo(beforeClaimId))
        owner.acknowledgeUndoPublication(beforeClaim.token.value)
        assertTrue(
            owner.acceptRemovalUndoResolution(
                beforeClaim.token.value,
                PendingUndoResolutionIntent.RESTORE,
            )
        )
        DownloadRepository.clearLivePendingRemovalTokensForTest()
        runStartupUndoRecovery(context)
        assertTrue(database.downloadDao.getNullableDownloadById(beforeClaimId) != null)

        // Resolver generation claimed, but no Room RESTORE write completed.
        val inFlightId = linkDownload(
            operation.operationId,
            335,
            DownloadRepository.Status.Queued,
        )
        val inFlight = checkNotNull(owner.deleteForUndo(inFlightId))
        owner.acknowledgeUndoPublication(inFlight.token.value)
        val claimed = CompletableDeferred<Unit>()
        val releaseClaim = CompletableDeferred<Unit>()
        DownloadRepository.pendingRemovalResolverClaimedForTesting = {
            claimed.complete(Unit)
            releaseClaim.await()
        }
        val inFlightResolver = async(Dispatchers.IO) {
            owner.restoreUndo(inFlight.token)
        }
        claimed.await()
        DownloadRepository.clearLivePendingRemovalTokensForTest()
        inFlightResolver.cancelAndJoin()
        DownloadRepository.pendingRemovalResolverClaimedForTesting = null
        runStartupUndoRecovery(context)
        assertTrue(database.downloadDao.getNullableDownloadById(inFlightId) != null)

        // A first Room restore write failed; the durable selected intent must
        // still drive startup recovery rather than an implicit COMMIT.
        val failedId = linkDownload(
            operation.operationId,
            336,
            DownloadRepository.Status.Queued,
        )
        val failed = checkNotNull(owner.deleteForUndo(failedId))
        owner.acknowledgeUndoPublication(failed.token.value)
        DownloadRepository.pendingRemovalRestoreFailureForTesting = {
            IllegalStateException("injected process-death restore failure")
        }
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 1
        assertTrue(runCatching { owner.restoreUndo(failed.token) }.isFailure)
        awaitAbandonedUndoOwnerActive(failed.token.value)
        LowQualityRedownloadLedger.cancelAbandonedUndoConvergenceForTesting(failed.token.value)
        DownloadRepository.clearLivePendingRemovalTokensForTest()
        DownloadRepository.pendingRemovalRestoreFailureForTesting = null
        LowQualityRedownloadRepository.pendingUndoItemsReadFailureCountForTesting = 0
        runStartupUndoRecovery(context)
        assertTrue(database.downloadDao.getNullableDownloadById(failedId) != null)

        // Durable RESTORE completed before process-local cleanup. Startup must
        // observe the resolved carrier and never insert a second Download.
        val resolvedId = linkDownload(
            operation.operationId,
            337,
            DownloadRepository.Status.Queued,
        )
        val resolved = checkNotNull(owner.deleteForUndo(resolvedId))
        owner.acknowledgeUndoPublication(resolved.token.value)
        val resolvedBeforeCleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        DownloadRepository.pendingRemovalResolvedBeforeCleanupForTesting = {
            resolvedBeforeCleanup.complete(Unit)
            releaseCleanup.await()
        }
        val resolvedResolver = async(Dispatchers.IO) {
            owner.restoreUndo(resolved.token)
        }
        resolvedBeforeCleanup.await()
        assertNull(database.pendingUndoCarrierDao.get(resolved.token.value))
        assertTrue(database.downloadDao.getNullableDownloadById(resolvedId) != null)
        DownloadRepository.clearLivePendingRemovalTokensForTest()
        resolvedResolver.cancelAndJoin()
        releaseCleanup.complete(Unit)
        DownloadRepository.pendingRemovalResolvedBeforeCleanupForTesting = null
        runStartupUndoRecovery(context)
        assertEquals(
            1,
            database.downloadDao.getAllDownloadsList().count { it.id == resolvedId },
        )
    }

    @Test
    fun siblingRestoreAndCommitIntentsRemainIsolatedAcrossRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val owner = DownloadRepository(database)
        val restoreId = linkDownload(
            operation.operationId,
            338,
            DownloadRepository.Status.Queued,
        )
        val commitId = linkDownload(
            operation.operationId,
            339,
            DownloadRepository.Status.Queued,
        )
        val restore = checkNotNull(owner.deleteForUndo(restoreId))
        val commit = checkNotNull(owner.deleteForUndo(commitId))
        owner.acknowledgeUndoPublication(restore.token.value)
        owner.acknowledgeUndoPublication(commit.token.value)
        assertTrue(
            owner.acceptRemovalUndoResolution(
                restore.token.value,
                PendingUndoResolutionIntent.RESTORE,
            )
        )
        assertTrue(
            owner.acceptRemovalUndoResolution(
                commit.token.value,
                PendingUndoResolutionIntent.COMMIT,
            )
        )

        DownloadRepository.clearLivePendingRemovalTokensForTest()
        runStartupUndoRecovery(context)
        withTimeout(20_000L) {
            while (database.downloadDao.getNullableDownloadById(restoreId) == null) {
                delay(25L)
            }
        }
        assertNull(database.downloadDao.getNullableDownloadById(commitId))
        val children = repository.getItems(operation.operationId).associateBy { it.downloadId }
        assertEquals(LowQualityRedownloadItemState.QUEUED, children[restoreId]?.stateValue)
        assertEquals(LowQualityRedownloadItemState.CANCELLED, children[commitId]?.stateValue)
        assertNull(database.pendingUndoCarrierDao.get(restore.token.value))
        assertNull(database.pendingUndoCarrierDao.get(commit.token.value))
    }

    @Test
    fun removalRestoreIntentFirstWriteFailureThenProcessDeath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 344, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val owner = viewModel.beginUndoPresentationOwner()
        val handle = checkNotNull(viewModel.deleteDownloadForUndo(downloadId, owner))
        viewModel.acknowledgeUndoPublication(handle.token.value, owner)
        val firstWriteFailure = AtomicBoolean(true)
        DownloadRepository.pendingUndoResolutionWriteFailureForTesting = {
            if (firstWriteFailure.compareAndSet(true, false)) {
                IllegalStateException("injected first removal intent write failure")
            } else {
                null
            }
        }
        val resolverBoundary = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        DownloadRepository.pendingRemovalBeforeResolverClaimedForTesting = {
            resolverBoundary.complete(Unit)
            releaseResolver.await()
        }

        try {
            assertTrue(viewModel.restoreDownloadUndoFromUi(handle))
            resolverBoundary.await()
            assertEquals(
                PendingUndoResolutionIntent.RESTORE,
                database.pendingUndoCarrierDao.get(handle.token.value)?.resolutionIntent
                    ?.let { PendingUndoResolutionIntent.valueOf(it) },
            )

            // The exact durable intent was accepted before the asynchronous
            // resolver claim. Clearing process-local ownership therefore
            // cannot reinterpret RESTORE as removal COMMIT.
            DownloadRepository.clearLivePendingRemovalTokensForTest()
            runStartupUndoRecovery(context)
            assertTrue(database.downloadDao.getNullableDownloadById(downloadId) != null)
            assertEquals(
                LowQualityRedownloadItemState.QUEUED,
                repository.getItems(operation.operationId).single().stateValue,
            )
        } finally {
            releaseResolver.complete(Unit)
            DownloadRepository.pendingRemovalBeforeResolverClaimedForTesting = null
            DownloadRepository.pendingUndoResolutionWriteFailureForTesting = null
        }
    }

    @Test
    fun cancellationRestoreIntentFirstWriteFailureThenProcessDeath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 345, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val owner = viewModel.beginUndoPresentationOwner()
        val original = database.downloadDao.getDownloadById(downloadId)
        val token = checkNotNull(
            viewModel.repository.beginUndoableCancellation(
                id = downloadId,
                owner = owner,
            ).pendingToken
        )
        viewModel.acknowledgeUndoPublication(token, owner)
        val firstWriteFailure = AtomicBoolean(true)
        DownloadRepository.pendingUndoResolutionWriteFailureForTesting = {
            if (firstWriteFailure.compareAndSet(true, false)) {
                IllegalStateException("injected first cancellation intent write failure")
            } else {
                null
            }
        }
        val resolverBoundary = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        DownloadRepository.pendingCancellationBeforeResolverClaimedForTesting = {
            resolverBoundary.complete(Unit)
            releaseResolver.await()
        }

        try {
            assertTrue(viewModel.undoPendingCancellation(original, token, owner))
            resolverBoundary.await()
            assertEquals(
                PendingUndoResolutionIntent.RESTORE,
                database.pendingUndoCarrierDao.get(token)?.resolutionIntent
                    ?.let { PendingUndoResolutionIntent.valueOf(it) },
            )
            DownloadRepository.clearLivePendingRemovalTokensForTest()
            runStartupUndoRecovery(context)
            assertEquals(
                DownloadRepository.Status.Queued.name,
                database.downloadDao.getDownloadById(downloadId).status,
            )
            assertEquals(
                LowQualityRedownloadItemState.QUEUED,
                repository.getItems(operation.operationId).single().stateValue,
            )
        } finally {
            releaseResolver.complete(Unit)
            DownloadRepository.pendingCancellationBeforeResolverClaimedForTesting = null
            DownloadRepository.pendingUndoResolutionWriteFailureForTesting = null
        }
    }

    @Test
    fun removalCommitIntentFirstWriteFailure() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 346, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(
            ApplicationProvider.getApplicationContext(),
            database,
            true,
        )
        val owner = viewModel.beginUndoPresentationOwner()
        val handle = checkNotNull(viewModel.deleteDownloadForUndo(downloadId, owner))
        viewModel.acknowledgeUndoPublication(handle.token.value, owner)
        val firstWriteFailure = AtomicBoolean(true)
        DownloadRepository.pendingUndoResolutionWriteFailureForTesting = {
            if (firstWriteFailure.compareAndSet(true, false)) {
                IllegalStateException("injected first removal COMMIT intent write failure")
            } else {
                null
            }
        }
        val resolverBoundary = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        DownloadRepository.pendingRemovalResolverClaimedForTesting = {
            resolverBoundary.complete(Unit)
            releaseResolver.await()
        }
        try {
            assertTrue(viewModel.commitDownloadUndoFromUi(handle))
            resolverBoundary.await()
            assertEquals(
                PendingUndoResolutionIntent.COMMIT,
                database.pendingUndoCarrierDao.get(handle.token.value)?.resolutionIntent
                    ?.let { PendingUndoResolutionIntent.valueOf(it) },
            )
        } finally {
            releaseResolver.complete(Unit)
            DownloadRepository.pendingRemovalResolverClaimedForTesting = null
            DownloadRepository.pendingUndoResolutionWriteFailureForTesting = null
        }
    }

    @Test
    fun cancellationCommitIntentFirstWriteFailure() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 347, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(
            ApplicationProvider.getApplicationContext(),
            database,
            true,
        )
        val owner = viewModel.beginUndoPresentationOwner()
        val token = checkNotNull(
            viewModel.repository.beginUndoableCancellation(
                id = downloadId,
                owner = owner,
            ).pendingToken
        )
        viewModel.acknowledgeUndoPublication(token, owner)
        val firstWriteFailure = AtomicBoolean(true)
        DownloadRepository.pendingUndoResolutionWriteFailureForTesting = {
            if (firstWriteFailure.compareAndSet(true, false)) {
                IllegalStateException("injected first cancellation COMMIT intent write failure")
            } else {
                null
            }
        }
        val resolverBoundary = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        DownloadRepository.pendingCancellationResolverClaimedForTesting = {
            resolverBoundary.complete(Unit)
            releaseResolver.await()
        }
        try {
            assertTrue(viewModel.commitPendingCancellation(downloadId, token, owner))
            resolverBoundary.await()
            assertEquals(
                PendingUndoResolutionIntent.COMMIT,
                database.pendingUndoCarrierDao.get(token)?.resolutionIntent
                    ?.let { PendingUndoResolutionIntent.valueOf(it) },
            )
        } finally {
            releaseResolver.complete(Unit)
            DownloadRepository.pendingCancellationResolverClaimedForTesting = null
            DownloadRepository.pendingUndoResolutionWriteFailureForTesting = null
        }
    }

    @Test
    fun undoIntentFirstWriteFailureAndRecoveryWriteFailureRetainsIntent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(operation.operationId, 348, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val owner = viewModel.beginUndoPresentationOwner()
        val handle = checkNotNull(viewModel.deleteDownloadForUndo(downloadId, owner))
        viewModel.acknowledgeUndoPublication(handle.token.value, owner)
        val twoWriteFailures = AtomicInteger(2)
        DownloadRepository.pendingUndoResolutionWriteFailureForTesting = {
            val remaining = twoWriteFailures.get()
            if (remaining > 0 && twoWriteFailures.compareAndSet(remaining, remaining - 1)) {
                IllegalStateException("injected intent barrier failure $remaining")
            } else {
                null
            }
        }
        val resolverBoundary = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        DownloadRepository.pendingRemovalBeforeResolverClaimedForTesting = {
            resolverBoundary.complete(Unit)
            releaseResolver.await()
        }
        try {
            assertTrue(viewModel.restoreDownloadUndoFromUi(handle))
            resolverBoundary.await()
            assertEquals(
                PendingUndoResolutionIntent.RESTORE,
                database.pendingUndoCarrierDao.get(handle.token.value)?.resolutionIntent
                    ?.let { PendingUndoResolutionIntent.valueOf(it) },
            )
            DownloadRepository.clearLivePendingRemovalTokensForTest()
            runStartupUndoRecovery(context)
            assertTrue(database.downloadDao.getNullableDownloadById(downloadId) != null)
        } finally {
            releaseResolver.complete(Unit)
            DownloadRepository.pendingRemovalBeforeResolverClaimedForTesting = null
            DownloadRepository.pendingUndoResolutionWriteFailureForTesting = null
        }
    }

    @Test
    fun intentBarrierSiblingIsolation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val restoreId = linkDownload(operation.operationId, 349, DownloadRepository.Status.Queued)
        val commitId = linkDownload(operation.operationId, 350, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val owner = viewModel.beginUndoPresentationOwner()
        val restore = checkNotNull(viewModel.deleteDownloadForUndo(restoreId, owner))
        val commit = checkNotNull(viewModel.deleteDownloadForUndo(commitId, owner))
        viewModel.acknowledgeUndoPublication(restore.token.value, owner)
        viewModel.acknowledgeUndoPublication(commit.token.value, owner)
        val firstWriteFailure = AtomicBoolean(true)
        DownloadRepository.pendingUndoResolutionWriteFailureForTesting = {
            if (firstWriteFailure.compareAndSet(true, false)) {
                IllegalStateException("injected sibling RESTORE intent failure")
            } else {
                null
            }
        }
        try {
            assertTrue(
                viewModel.repository.acceptRemovalUndoResolution(
                    restore.token.value,
                    PendingUndoResolutionIntent.RESTORE,
                    owner,
                )
            )
            assertTrue(
                viewModel.repository.acceptRemovalUndoResolution(
                    commit.token.value,
                    PendingUndoResolutionIntent.COMMIT,
                    owner,
                )
            )
            assertEquals(
                PendingUndoResolutionIntent.RESTORE,
                viewModel.repository.pendingUndoResolutionIntent(restore.token.value),
            )
            assertEquals(
                PendingUndoResolutionIntent.COMMIT,
                viewModel.repository.pendingUndoResolutionIntent(commit.token.value),
            )
            DownloadRepository.clearLivePendingRemovalTokensForTest()
            runStartupUndoRecovery(context)
            assertTrue(database.downloadDao.getNullableDownloadById(restoreId) != null)
            assertNull(database.downloadDao.getNullableDownloadById(commitId))
        } finally {
            DownloadRepository.pendingUndoResolutionWriteFailureForTesting = null
        }
    }

    @Test
    fun twoActivityScopedViewsKeepIndependentUndoOwners() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val firstId = linkDownload(operation.operationId, 351, DownloadRepository.Status.Queued)
        val secondId = linkDownload(operation.operationId, 352, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val ownerA = viewModel.beginUndoPresentationOwner()
        val ownerB = viewModel.beginUndoPresentationOwner()
        val tokenA = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = firstId, owner = ownerA).pendingToken
        )
        val tokenB = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = secondId, owner = ownerB).pendingToken
        )
        viewModel.acknowledgeUndoPublication(tokenA, ownerA)
        viewModel.acknowledgeUndoPublication(tokenB, ownerB)
        assertNotEquals(ownerA, ownerB)
        assertTrue(DownloadRepository.isLivePendingCancellationToken(tokenA))
        assertTrue(DownloadRepository.isLivePendingCancellationToken(tokenB))
    }

    @Test
    fun creatingSecondOwnerDoesNotInvalidateFirstSnackbar() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val firstId = linkDownload(operation.operationId, 353, DownloadRepository.Status.Queued)
        val secondId = linkDownload(operation.operationId, 354, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val ownerA = viewModel.beginUndoPresentationOwner()
        val handleA = checkNotNull(viewModel.deleteDownloadForUndo(firstId, ownerA))
        viewModel.acknowledgeUndoPublication(handleA.token.value, ownerA)
        val ownerB = viewModel.beginUndoPresentationOwner()
        viewModel.repository.beginUndoableCancellation(id = secondId, owner = ownerB)
        assertEquals(firstId, viewModel.restoreDownloadUndo(handleA))
    }

    @Test
    fun destroyingFirstViewDoesNotAbandonSecondOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val firstId = linkDownload(operation.operationId, 355, DownloadRepository.Status.Queued)
        val secondId = linkDownload(operation.operationId, 356, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val ownerA = viewModel.beginUndoPresentationOwner()
        val ownerB = viewModel.beginUndoPresentationOwner()
        val tokenA = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = firstId, owner = ownerA).pendingToken
        )
        val tokenB = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = secondId, owner = ownerB).pendingToken
        )
        viewModel.acknowledgeUndoPublication(tokenA, ownerA)
        viewModel.acknowledgeUndoPublication(tokenB, ownerB)
        viewModel.abandonPendingUndoCapabilitiesForView(ownerA)
        assertFalse(DownloadRepository.isLivePendingCancellationToken(tokenA))
        assertTrue(DownloadRepository.isLivePendingCancellationToken(tokenB))
        assertEquals(
            DownloadRepository.Status.Queued,
            viewModel.repository.undoPendingCancellation(
                secondId,
                tokenB,
                DownloadRepository.Status.Queued,
                ownerB,
            ).restoredStatus,
        )
    }

    @Test
    fun destroyingSecondViewDoesNotAbandonFirstOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val firstId = linkDownload(operation.operationId, 357, DownloadRepository.Status.Queued)
        val secondId = linkDownload(operation.operationId, 358, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val ownerA = viewModel.beginUndoPresentationOwner()
        val ownerB = viewModel.beginUndoPresentationOwner()
        val tokenA = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = firstId, owner = ownerA).pendingToken
        )
        val tokenB = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = secondId, owner = ownerB).pendingToken
        )
        viewModel.acknowledgeUndoPublication(tokenA, ownerA)
        viewModel.acknowledgeUndoPublication(tokenB, ownerB)
        viewModel.abandonPendingUndoCapabilitiesForView(ownerB)
        assertTrue(DownloadRepository.isLivePendingCancellationToken(tokenA))
        assertFalse(DownloadRepository.isLivePendingCancellationToken(tokenB))
        assertEquals(
            DownloadRepository.Status.Queued,
            viewModel.repository.undoPendingCancellation(
                firstId,
                tokenA,
                DownloadRepository.Status.Queued,
                ownerA,
            ).restoredStatus,
        )
    }

    @Test
    fun crossOwnerAckAndResolutionFailClosed() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val firstId = linkDownload(operation.operationId, 359, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(
            ApplicationProvider.getApplicationContext(),
            database,
            true,
        )
        val ownerA = viewModel.beginUndoPresentationOwner()
        val ownerB = viewModel.beginUndoPresentationOwner()
        val tokenA = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = firstId, owner = ownerA).pendingToken
        )
        viewModel.acknowledgeUndoPublication(tokenA, ownerB)
        assertFalse(DownloadRepository.isLivePendingCancellationToken(tokenA))
        viewModel.acknowledgeUndoPublication(tokenA, ownerA)
        assertTrue(DownloadRepository.isLivePendingCancellationToken(tokenA))
        assertFalse(
            viewModel.repository.acceptCancellationUndoResolution(
                tokenA,
                PendingUndoResolutionIntent.RESTORE,
                ownerB,
            )
        )
        viewModel.abandonPendingUndoCapabilitiesForView(ownerB)
        assertTrue(DownloadRepository.isLivePendingCancellationToken(tokenA))
        assertEquals(
            DownloadRepository.Status.Queued,
            viewModel.repository.undoPendingCancellation(
                firstId,
                tokenA,
                DownloadRepository.Status.Queued,
                ownerA,
            ).restoredStatus,
        )
    }

    @Test
    fun activityViewModelClearAbandonsAllIssuedOwners() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val firstId = linkDownload(operation.operationId, 360, DownloadRepository.Status.Queued)
        val secondId = linkDownload(operation.operationId, 361, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(context, database, true)
        val ownerA = viewModel.beginUndoPresentationOwner()
        val ownerB = viewModel.beginUndoPresentationOwner()
        val tokenA = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = firstId, owner = ownerA).pendingToken
        )
        val tokenB = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = secondId, owner = ownerB).pendingToken
        )
        viewModel.acknowledgeUndoPublication(tokenA, ownerA)
        viewModel.acknowledgeUndoPublication(tokenB, ownerB)
        DownloadRepository.pendingCancellationCommitFailureForTesting = {
            IllegalStateException("hold clear successor convergence")
        }
        viewModel.clearForTesting()
        awaitAbandonedUndoOwnerActive(tokenA)
        awaitAbandonedUndoOwnerActive(tokenB)
        assertFalse(DownloadRepository.isLivePendingCancellationToken(tokenA))
        assertFalse(DownloadRepository.isLivePendingCancellationToken(tokenB))
        DownloadRepository.pendingCancellationCommitFailureForTesting = null
        runStartupUndoRecovery(context)
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).first { it.downloadId == firstId }.stateValue,
        )
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).first { it.downloadId == secondId }.stateValue,
        )
    }

    @Test
    fun viewPagerLikeOwnerLifecycleKeepsExactPresentationAssignments() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val firstId = linkDownload(operation.operationId, 362, DownloadRepository.Status.Queued)
        val secondId = linkDownload(operation.operationId, 363, DownloadRepository.Status.Queued)
        val thirdId = linkDownload(operation.operationId, 364, DownloadRepository.Status.Queued)
        val viewModel = DownloadViewModel(
            ApplicationProvider.getApplicationContext(),
            database,
            true,
        )
        val ownerA = viewModel.beginUndoPresentationOwner()
        val ownerB = viewModel.beginUndoPresentationOwner()
        val tokenA = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = firstId, owner = ownerA).pendingToken
        )
        val tokenB = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = secondId, owner = ownerB).pendingToken
        )
        viewModel.acknowledgeUndoPublication(tokenA, ownerA)
        viewModel.acknowledgeUndoPublication(tokenB, ownerB)
        val ownerC = viewModel.beginUndoPresentationOwner()
        val tokenC = checkNotNull(
            viewModel.repository.beginUndoableCancellation(id = thirdId, owner = ownerC).pendingToken
        )
        viewModel.acknowledgeUndoPublication(tokenC, ownerC)

        viewModel.abandonPendingUndoCapabilitiesForView(ownerB)
        assertTrue(DownloadRepository.isLivePendingCancellationToken(tokenA))
        assertFalse(DownloadRepository.isLivePendingCancellationToken(tokenB))
        assertTrue(DownloadRepository.isLivePendingCancellationToken(tokenC))
        assertEquals(
            DownloadRepository.Status.Queued,
            viewModel.repository.undoPendingCancellation(
                firstId,
                tokenA,
                DownloadRepository.Status.Queued,
                ownerA,
            ).restoredStatus,
        )
        assertTrue(DownloadRepository.isLivePendingCancellationToken(tokenC))
        assertEquals(
            DownloadRepository.Status.Queued,
            viewModel.repository.undoPendingCancellation(
                thirdId,
                tokenC,
                DownloadRepository.Status.Queued,
                ownerC,
            ).restoredStatus,
        )
    }

    @Test
    fun confirmationPreparingEnqueueSynchronousFailureRetriesExactPhase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operationId = createAwaitingSelectionOperationWithIdentity(340)
        val calls = AtomicInteger(0)
        val policies = Collections.synchronizedList(mutableListOf<ExistingWorkPolicy>())
        val workNames = Collections.synchronizedList(mutableListOf<String>())
        val accepted = ControlledWorkManagerOperation()
        val manager = LowQualityRedownloadManager.createForTesting(
            context = context,
            database = database,
        ) { workName, policy, _ ->
            workNames += workName
            policies += policy
            if (calls.getAndIncrement() == 0) {
                throw IllegalStateException("injected synchronous enqueue failure")
            }
            accepted
        }

        manager.confirm(operationId)
        awaitEnqueueCalls(calls, 2)
        assertEquals(
            LowQualityRedownloadPhase.PREPARING,
            repository.getOperation(operationId)?.phaseValue,
        )
        accepted.succeed()
        awaitEnqueueOwnerStopped(operationId)
        assertEquals(
            listOf(
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                ExistingWorkPolicy.KEEP,
            ),
            policies,
        )
        assertTrue(workNames.all { it == LowQualityRedownloadWorker.uniqueWorkName(operationId) })
    }

    @Test
    fun confirmationPreparingAsyncOperationFailureRetriesExactPhase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operationId = createAwaitingSelectionOperationWithIdentity(341)
        val calls = AtomicInteger(0)
        val policies = Collections.synchronizedList(mutableListOf<ExistingWorkPolicy>())
        val first = ControlledWorkManagerOperation()
        val second = ControlledWorkManagerOperation()
        val manager = LowQualityRedownloadManager.createForTesting(
            context = context,
            database = database,
        ) { _, policy, _ ->
            policies += policy
            if (calls.getAndIncrement() == 0) first else second
        }

        manager.confirm(operationId)
        awaitEnqueueCalls(calls, 1)
        assertEquals(
            LowQualityRedownloadPhase.PREPARING,
            repository.getOperation(operationId)?.phaseValue,
        )
        first.fail(IllegalStateException("injected asynchronous enqueue failure"))
        awaitEnqueueCalls(calls, 2)
        second.succeed()
        awaitEnqueueOwnerStopped(operationId)
        assertEquals(
            listOf(
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                ExistingWorkPolicy.KEEP,
            ),
            policies,
        )
    }

    @Test
    fun initialScanningEnqueueFailureConvergesWithoutFeatureReentry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val calls = AtomicInteger(0)
        val second = ControlledWorkManagerOperation()
        val manager = LowQualityRedownloadManager.createForTesting(
            context = context,
            database = database,
        ) { _, _, _ ->
            if (calls.getAndIncrement() == 0) {
                throw IllegalStateException("injected scanning enqueue failure")
            }
            second
        }

        manager.startOrReconnect()
        awaitEnqueueCalls(calls, 2)
        val operationId = checkNotNull(repository.getActiveOperation()).operationId
        assertEquals(
            LowQualityRedownloadPhase.SCANNING,
            repository.getOperation(operationId)?.phaseValue,
        )
        second.succeed()
        awaitEnqueueOwnerStopped(operationId)
        assertEquals(2, calls.get())
    }

    @Test
    fun preparingRecoveryEnqueueFailureConvergesSameProcess() = runBlocking {
        assertRecoveryEnqueueFailureConverges(
            phase = LowQualityRedownloadPhase.PREPARING,
            historyId = 342,
        )
    }

    @Test
    fun queueingRecoveryEnqueueFailureConvergesSameProcess() = runBlocking {
        assertRecoveryEnqueueFailureConverges(
            phase = LowQualityRedownloadPhase.QUEUEING,
            historyId = 343,
        )
    }

    @Test
    fun enqueueRetryProcessDeathReconstructsExactPhaseCarrier() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        assertTrue(
            repository.advancePhase(
                operation.operationId,
                LowQualityRedownloadPhase.SCANNING,
                LowQualityRedownloadPhase.PREPARING,
            )
        )
        LowQualityRedownloadLedger.cancelAllEnqueueConvergenceJobsForTesting()
        val calls = AtomicInteger(0)
        val accepted = ControlledWorkManagerOperation()
        val recreatedManager = LowQualityRedownloadManager.createForTesting(
            context = context,
            database = database,
        ) { _, _, _ ->
            calls.incrementAndGet()
            accepted
        }

        runStartupReconciliation(
            readiness = CompletableDeferred(true),
            reconcile = { recreatedManager.reconcile() },
            reportFailure = { throw AssertionError("Unexpected low-quality startup failure", it) },
        )
        awaitEnqueueCalls(calls, 1)
        assertEquals(
            LowQualityRedownloadPhase.PREPARING,
            repository.getOperation(operation.operationId)?.phaseValue,
        )
        accepted.succeed()
        awaitEnqueueOwnerStopped(operation.operationId)
    }

    @Test
    fun cancellationRevokesPendingEnqueueRetryBeforeLateAcceptance() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        val calls = AtomicInteger(0)
        val lateAcceptance = ControlledWorkManagerOperation()
        val manager = LowQualityRedownloadManager.createForTesting(
            context = context,
            database = database,
        ) { _, _, _ ->
            if (calls.getAndIncrement() == 0) {
                throw IllegalStateException("injected retryable enqueue failure")
            }
            lateAcceptance
        }

        manager.startOrReconnect()
        awaitEnqueueCalls(calls, 2)
        assertTrue(
            LowQualityRedownloadLedger.isEnqueueConvergenceActiveForTesting(
                operation.operationId,
            )
        )
        val cancelled = CompletableDeferred<Unit>()
        manager.cancel(operation.operationId) { cancelled.complete(Unit) }
        cancelled.await()
        awaitEnqueueOwnerStopped(operation.operationId)
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
        val callsAfterCancel = calls.get()
        lateAcceptance.succeed()
        delay(250L)
        assertEquals(callsAfterCancel, calls.get())
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
    }

    @Test
    fun strongerSaveRollbackPreservesExactLiveUndoOwner() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(
            operation.operationId,
            321,
            DownloadRepository.Status.Queued,
        )
        val owner = DownloadRepository(database)
        val token = checkNotNull(owner.beginUndoableCancellation(downloadId).pendingToken)
        owner.acknowledgeUndoPublication(token)
        DownloadRepository.terminalizeLinkedChildrenFailureForTesting = {
            IllegalStateException("injected stronger-transition rollback")
        }

        try {
            assertTrue(runCatching { owner.moveToSaved(downloadId) }.isFailure)
            assertTrue(DownloadRepository.isLivePendingCancellationToken(token))
            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                database.downloadDao.getDownloadById(downloadId).status,
            )
            assertEquals(
                LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
                repository.getItems(operation.operationId).single().stateValue,
            )
            repository.reconcileLinkedDownloads(operation.operationId)
            assertEquals(
                LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
                repository.getItems(operation.operationId).single().stateValue,
            )

            DownloadRepository.terminalizeLinkedChildrenFailureForTesting = null
            assertEquals(
                DownloadRepository.Status.Queued,
                owner.undoPendingCancellation(
                    downloadId,
                    token,
                    DownloadRepository.Status.Queued,
                ).restoredStatus,
            )

            val committedId = linkDownload(
                operation.operationId,
                322,
                DownloadRepository.Status.Queued,
            )
            val committedOwner = DownloadRepository(database)
            val committedToken = checkNotNull(
                committedOwner.beginUndoableCancellation(committedId).pendingToken
            )
            committedOwner.moveToSaved(committedId)
            assertFalse(DownloadRepository.isLivePendingCancellationToken(committedToken))
            assertNull(
                committedOwner.undoPendingCancellation(
                    committedId,
                    committedToken,
                    DownloadRepository.Status.Queued,
                ).restoredStatus,
            )
            assertEquals(
                LowQualityRedownloadItemState.CANCELLED,
                repository.getItems(operation.operationId)
                    .single { it.downloadId == committedId }
                    .stateValue,
            )
        } finally {
            DownloadRepository.terminalizeLinkedChildrenFailureForTesting = null
        }
    }

    @Test
    fun terminalizeLinkedChildrenRollbackPreservesExactLiveUndoOwner() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(
            operation.operationId,
            323,
            DownloadRepository.Status.Queued,
        )
        val owner = DownloadRepository(database)
        val token = checkNotNull(owner.beginUndoableCancellation(downloadId).pendingToken)
        owner.acknowledgeUndoPublication(token)
        DownloadRepository.terminalizeLinkedChildrenFailureForTesting = {
            IllegalStateException("injected remove rollback")
        }

        try {
            assertTrue(runCatching { owner.delete(downloadId) }.isFailure)
            assertTrue(DownloadRepository.isLivePendingCancellationToken(token))
            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                database.downloadDao.getDownloadById(downloadId).status,
            )
            assertEquals(
                LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
                repository.getItems(operation.operationId).single().stateValue,
            )
            DownloadRepository.terminalizeLinkedChildrenFailureForTesting = null
            assertEquals(
                DownloadRepository.Status.Queued,
                owner.undoPendingCancellation(
                    downloadId,
                    token,
                    DownloadRepository.Status.Queued,
                ).restoredStatus,
            )
            assertTrue(database.downloadDao.getNullableDownloadById(downloadId) != null)
        } finally {
            DownloadRepository.terminalizeLinkedChildrenFailureForTesting = null
        }
    }

    @Test
    fun operationCancellationSupersedesLivePendingCancellationUndo() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 87, DownloadRepository.Status.Queued)
        val owner = DownloadRepository(database)
        val token = checkNotNull(owner.beginUndoableCancellation(linkedId).pendingToken)

        repository.requestCancellation(operation.operationId)
        assertTrue(repository.getOperation(operation.operationId)?.cancelRequested == true)
        assertTrue(
            LowQualityRedownloadManager.get(context)
                .reconcileCancellationDebt(database)
        )
        assertFalse(DownloadRepository.isLivePendingCancellationToken(token))
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
        assertNull(
            owner.undoPendingCancellation(
                linkedId,
                token,
                DownloadRepository.Status.Queued,
            ).restoredStatus,
        )
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getDownloadById(linkedId).status,
        )
    }

    @Test
    fun pendingCancellationUndoOwnershipIsScopedToItsOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val firstId = linkDownload(operation.operationId, 88, DownloadRepository.Status.Queued)
        val secondId = linkDownload(operation.operationId, 89, DownloadRepository.Status.Queued)
        val firstOwner = DownloadRepository(database)
        val secondOwner = DownloadRepository(database)
        val firstToken = checkNotNull(firstOwner.beginUndoableCancellation(firstId).pendingToken)
        val secondToken = checkNotNull(secondOwner.beginUndoableCancellation(secondId).pendingToken)
        secondOwner.acknowledgeUndoPublication(secondToken)

        firstOwner.abandonPendingUndoSnapshots()
        assertFalse(DownloadRepository.isLivePendingCancellationToken(firstToken))
        assertTrue(DownloadRepository.isLivePendingCancellationToken(secondToken))
        assertFalse(
            LowQualityRedownloadManager.get(context)
                .reconcileCancellationDebt(database)
        )
        assertEquals(
            LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
            repository.getItems(operation.operationId)
                .single { it.downloadId == secondId }
                .stateValue,
        )

        assertEquals(
            DownloadRepository.Status.Queued,
            secondOwner.undoPendingCancellation(
                secondId,
                secondToken,
                DownloadRepository.Status.Queued,
            ).restoredStatus,
        )
    }

    @Test
    fun linkedQueuedReplacementMovedToSavedIsSkippedAtomically() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 90, DownloadRepository.Status.Queued)

        assertEquals(
            setOf(operation.operationId),
            DownloadRepository(database).moveToSaved(linkedId)
        )

        assertEquals(
            DownloadRepository.Status.Saved.name,
            database.downloadDao.getDownloadById(linkedId).status
        )
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.SKIPPED, child.stateValue)
        assertEquals(DownloadRepository.REASON_SAVED_FOR_LATER, child.reasonCode)
    }

    @Test
    fun lastNonterminalSavedChildFinalizesCompletedParent() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val succeededId = linkDownload(operation.operationId, 91, DownloadRepository.Status.Active)
        val savedId = linkDownload(operation.operationId, 92, DownloadRepository.Status.Queued)
        repository.markDownloadState(succeededId, LowQualityRedownloadItemState.SUCCEEDED)

        DownloadRepository(database).moveToSaved(savedId)

        assertEquals(
            LowQualityRedownloadOperationState.COMPLETED,
            repository.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun mixedSucceededAndSkippedChildrenCompleteWhileFailedAndSkippedArePartial() = runBlocking {
        val completed = repository.createOrReconnect(now = 100)
        val successId = linkDownload(completed.operationId, 93, DownloadRepository.Status.Active)
        val firstSavedId = linkDownload(completed.operationId, 94, DownloadRepository.Status.Queued)
        repository.markDownloadState(successId, LowQualityRedownloadItemState.SUCCEEDED)
        DownloadRepository(database).moveToSaved(firstSavedId)
        assertEquals(
            LowQualityRedownloadOperationState.COMPLETED,
            repository.getOperation(completed.operationId)?.stateValue
        )

        val partial = repository.createOrReconnect(now = 200)
        val failedId = linkDownload(partial.operationId, 95, DownloadRepository.Status.Active)
        val secondSavedId = linkDownload(partial.operationId, 96, DownloadRepository.Status.Queued)
        repository.markDownloadState(failedId, LowQualityRedownloadItemState.FAILED)
        DownloadRepository(database).moveToSaved(secondSavedId)
        assertEquals(
            LowQualityRedownloadOperationState.PARTIAL_FAILURE,
            repository.getOperation(partial.operationId)?.stateValue
        )
    }

    @Test
    fun unrelatedSavedRowRemainsLedgerIndependent() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        linkDownload(operation.operationId, 97, DownloadRepository.Status.Queued)
        val unrelatedId = database.downloadDao.insert(download(98))

        assertTrue(DownloadRepository(database).moveToSaved(unrelatedId).isEmpty())

        assertEquals(
            DownloadRepository.Status.Saved.name,
            database.downloadDao.getDownloadById(unrelatedId).status
        )
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            repository.getItems(operation.operationId).single().stateValue
        )
        assertEquals(LowQualityRedownloadOperationState.RUNNING, repository.getActiveOperation()?.stateValue)
    }

    @Test
    fun startupReconstructionRepairsStrandedSavedChild() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 99, DownloadRepository.Status.Queued)
        database.downloadDao.setStatus(linkedId, DownloadRepository.Status.Saved.name)

        val reconstructed = LowQualityRedownloadRepository(database)
        reconstructed.reconcileLinkedDownloads(operation.operationId)

        val child = reconstructed.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.SKIPPED, child.stateValue)
        assertEquals(DownloadRepository.REASON_SAVED_FOR_LATER, child.reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.COMPLETED,
            reconstructed.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun startupReconstructionDoesNotRegressSucceededChildForSavedDownload() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 100, DownloadRepository.Status.Active)
        repository.markDownloadState(linkedId, LowQualityRedownloadItemState.SUCCEEDED)
        database.downloadDao.setStatus(linkedId, DownloadRepository.Status.Saved.name)

        LowQualityRedownloadRepository(database).reconcileLinkedDownloads(operation.operationId)

        assertEquals(
            LowQualityRedownloadItemState.SUCCEEDED,
            repository.getItems(operation.operationId).single().stateValue
        )
        assertEquals(
            LowQualityRedownloadOperationState.COMPLETED,
            repository.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun confirmationAndPhaseAdvancesAreConditionalAndMonotonic() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        database.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = 84,
                intendedSourceUrl = "https://example.com/84",
                intendedType = DownloadType.video.name,
                selected = true,
                itemState = LowQualityRedownloadItemState.PENDING.name
            )
        )
        assertTrue(
            repository.advancePhase(
                operation.operationId,
                LowQualityRedownloadPhase.SCANNING,
                LowQualityRedownloadPhase.AWAITING_SELECTION
            )
        )
        assertTrue(repository.confirmSelection(operation.operationId))
        assertFalse(repository.confirmSelection(operation.operationId))
        val confirmed = repository.getOperation(operation.operationId)!!
        assertEquals(
            LowQualityRedownloadPhase.PREPARING,
            confirmed.phaseValue
        )
        assertEquals(LowQualityRedownloadOperationState.RUNNING, confirmed.stateValue)
        assertFalse(shouldPresentLowQualitySelection(confirmed, candidateCount = 1))
        assertFalse(
            repository.advancePhase(
                operation.operationId,
                LowQualityRedownloadPhase.SCANNING,
                LowQualityRedownloadPhase.AWAITING_SELECTION
            )
        )
        assertTrue(
            repository.advancePhase(
                operation.operationId,
                LowQualityRedownloadPhase.PREPARING,
                LowQualityRedownloadPhase.QUEUEING
            )
        )
        assertFalse(
            repository.advancePhase(
                operation.operationId,
                LowQualityRedownloadPhase.PREPARING,
                LowQualityRedownloadPhase.QUEUEING
            )
        )
    }

    @Test
    fun selectedSourceIdentityCannotRebindAfterHistoryMutation() = runBlocking {
        val historyId = 201L
        val originalUrl = "https://youtu.be/dQw4w9WgXcQ"
        insertHistory(historyId, originalUrl, DownloadType.video)
        val operation = repository.createOrReconnect(now = 100)
        database.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = historyId,
                intendedSourceUrl = originalUrl,
                intendedType = DownloadType.video.name,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name
            )
        )

        val reconstructed = LowQualityRedownloadRepository(database)
        val persistedIdentity = reconstructed.getItems(operation.operationId).single()
        assertEquals(originalUrl, persistedIdentity.intendedSourceUrl)
        assertEquals(DownloadType.video.name, persistedIdentity.intendedType)

        database.historyDao.updateRaw(
            database.historyDao.getItem(historyId).copy(
                url = "https://youtu.be/9bZkp7q19f0"
            )
        )

        assertNull(
            reconstructed.linkDownloadAtomically(
                operation.operationId,
                historyId,
                download(historyId).copy(url = "https://youtu.be/9bZkp7q19f0")
            )
        )
        val item = reconstructed.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.SKIPPED, item.stateValue)
        assertEquals("SELECTION_SOURCE_CHANGED", item.reasonCode)
        assertEquals(0, database.downloadDao.getQueuedDownloadsList().size)
    }

    @Test
    fun selectedTypeIdentityCannotRebindAfterHistoryMutation() = runBlocking {
        val historyId = 202L
        val sourceUrl = "https://youtu.be/dQw4w9WgXcQ"
        insertHistory(historyId, sourceUrl, DownloadType.video)
        val operation = repository.createOrReconnect(now = 100)
        database.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = historyId,
                intendedSourceUrl = sourceUrl,
                intendedType = DownloadType.video.name,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name
            )
        )

        database.historyDao.updateRaw(
            database.historyDao.getItem(historyId).copy(type = DownloadType.audio)
        )

        assertNull(
            repository.linkDownloadAtomically(
                operation.operationId,
                historyId,
                download(historyId).copy(type = DownloadType.audio)
            )
        )
        val item = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.SKIPPED, item.stateValue)
        assertEquals("SELECTION_TYPE_CHANGED", item.reasonCode)
        assertEquals(0, database.downloadDao.getQueuedDownloadsList().size)
    }

    @Test
    fun unchangedSelectedIdentityStillLinksReplacementNormallyAfterReconstruction() = runBlocking {
        val historyId = 203L
        val sourceUrl = "https://youtu.be/dQw4w9WgXcQ"
        insertHistory(historyId, sourceUrl, DownloadType.video)
        val operation = repository.createOrReconnect(now = 100)
        database.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = historyId,
                intendedSourceUrl = sourceUrl,
                intendedType = DownloadType.video.name,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name
            )
        )

        val reconstructed = LowQualityRedownloadRepository(database)
        val linkedId = reconstructed.linkDownloadAtomically(
            operation.operationId,
            historyId,
            download(historyId).copy(url = sourceUrl)
        )

        assertTrue(linkedId != null)
        assertEquals(linkedId, reconstructed.getItems(operation.operationId).single().downloadId)
        assertEquals(
            LowQualityRedownloadItemState.QUEUED,
            reconstructed.getItems(operation.operationId).single().stateValue
        )
    }

    @Test
    fun staleExecutionCannotAuthorizeHistoryReplacementOrDeleteNewerDownload() = runBlocking {
        val historyId = 204L
        val sourceUrl = "https://youtu.be/dQw4w9WgXcQ"
        insertHistory(historyId, sourceUrl, DownloadType.video)
        val downloadId = database.downloadDao.insert(
            download(historyId).apply {
                executionId = "E2"
                status = DownloadRepository.Status.Active.name
            }
        )
        val beforeHistory = database.historyDao.getItem(historyId)
        var ownershipLost = false

        try {
            HistoryKeywordAssignmentRepository(database)
                .replaceHistoryPreservingAssignmentsAuthorized(
                    historyId = historyId,
                    expectedSourceUrl = sourceUrl,
                    expectedType = DownloadType.video,
                    replacementDownloadId = downloadId,
                    replacementOperationId = "stale-operation",
                    expectedExecutionId = "E1",
                ) { current -> current.copy(title = "stale replacement") }
        } catch (_: com.ireum.ytdl.database.repository.HistoryReplacementExecutionOwnershipLostException) {
            ownershipLost = true
        }

        assertTrue(ownershipLost)
        assertEquals(beforeHistory.title, database.historyDao.getItem(historyId).title)
        assertEquals("E2", database.downloadDao.getDownloadById(downloadId).executionId)
        assertTrue(database.downloadDao.getDownloadById(downloadId).status == DownloadRepository.Status.Active.name)
    }

    @Test
    fun staleExecutionCannotCompleteOrDeleteNewerDownload() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val downloadId = linkDownload(
            operationId = operation.operationId,
            historyId = 205,
            status = DownloadRepository.Status.Active,
            executionId = "E2",
        )
        var ownershipLost = false

        try {
            DownloadRepository(database).completeAndDelete(
                id = downloadId,
                expectedExecutionId = "E1",
            )
        } catch (_: DownloadExecutionOwnershipLostException) {
            ownershipLost = true
        }

        assertTrue(ownershipLost)
        assertEquals("E2", database.downloadDao.getDownloadById(downloadId).executionId)
        assertEquals(
            DownloadRepository.Status.Active.name,
            database.downloadDao.getDownloadById(downloadId).status,
        )
        assertEquals(downloadId, repository.getItems(operation.operationId).single().downloadId)
    }

    private suspend fun insertHistory(
        id: Long,
        url: String,
        type: DownloadType,
    ) {
        database.historyDao.insertRaw(
            HistoryItem(
                id = id,
                url = url,
                title = "Selected history",
                author = "Creator",
                duration = "1:00",
                thumb = "",
                type = type,
                time = id,
                downloadPath = listOf("/downloads/$id.mp4"),
                website = "YouTube",
                format = Format(format_id = "1080p"),
                downloadId = 0L,
            )
        )
    }

    @Test
    fun awaitingSelectionReconstructionKeepsIdentityAndCanPresentAgain() = runBlocking {
        val operation = createAwaitingSelectionOperation(historyId = 89)

        val reconstructed = LowQualityRedownloadRepository(database)
        val recovered = reconstructed.getActiveOperation()!!
        val candidates = reconstructed.getItems(recovered.operationId)

        assertEquals(operation.operationId, recovered.operationId)
        assertEquals(LowQualityRedownloadOperationState.RUNNING, recovered.stateValue)
        assertEquals(LowQualityRedownloadPhase.AWAITING_SELECTION, recovered.phaseValue)
        assertEquals(listOf(89L), candidates.map(LowQualityRedownloadItem::historyId))
        assertTrue(shouldPresentLowQualitySelection(recovered, candidates.size))
    }

    @Test
    fun systemAbandonCancellationTerminalizesAwaitingSelection() = runBlocking {
        val operation = createAwaitingSelectionOperation(historyId = 90)

        assertTrue(repository.requestCancellation(operation.operationId).isEmpty())
        repository.completePersistedCancellation(operation.operationId)

        val terminal = repository.getOperation(operation.operationId)!!
        val candidate = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadOperationState.CANCELLED, terminal.stateValue)
        assertEquals(LowQualityRedownloadPhase.FINALIZING, terminal.phaseValue)
        assertEquals(LowQualityRedownloadItemState.CANCELLED, candidate.stateValue)
        assertFalse(shouldPresentLowQualitySelection(terminal, candidateCount = 1))
        assertNull(repository.getActiveOperation())
    }

    @Test
    fun warningSuccessAndDownloadDeletionCommitTogether() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 85, DownloadRepository.Status.Active)

        assertEquals(
            setOf(operation.operationId),
            DownloadRepository(database).completeAndDelete(
                linkedId,
                "SUCCESS_WITH_WARNINGS"
            )
        )

        assertNull(database.downloadDao.getNullableDownloadById(linkedId))
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.SUCCEEDED, child.stateValue)
        assertEquals("SUCCESS_WITH_WARNINGS", child.reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.COMPLETED,
            repository.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun deletedHistoryTargetSkipsLinkedItemFinalizesOperationAndRemovesQueueRow() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 101, DownloadRepository.Status.Active)

        assertEquals(
            setOf(operation.operationId),
            DownloadRepository(database).completeHistoryTargetDeletedAndDelete(linkedId)
        )

        assertNull(database.downloadDao.getNullableDownloadById(linkedId))
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.SKIPPED, child.stateValue)
        assertEquals(DownloadRepository.REASON_HISTORY_TARGET_DELETED, child.reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.COMPLETED,
            repository.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun unauthorizedHistoryReplacementFailsLinkedItemAndPreservesQueueRow() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 102, DownloadRepository.Status.Active)
        val issueCode = DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED.name

        database.downloadDao.setStatus(linkedId, DownloadRepository.Status.Error.name)
        assertEquals(
            operation.operationId,
            repository.markDownloadState(
                linkedId,
                LowQualityRedownloadItemState.FAILED,
                issueCode
            )
        )

        assertEquals(
            DownloadRepository.Status.Error.name,
            database.downloadDao.getNullableDownloadById(linkedId)?.status
        )
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.FAILED, child.stateValue)
        assertEquals(issueCode, child.reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.FAILED,
            repository.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun downloadDeletionFailureRollsBackLedgerAndOperationTerminalization() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 88, DownloadRepository.Status.Active)
        repository.markDownloadState(linkedId, LowQualityRedownloadItemState.ACTIVE)
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER fail_completed_download_delete " +
                "BEFORE DELETE ON downloads WHEN OLD.id = $linkedId " +
                "BEGIN SELECT RAISE(ABORT, 'forced download delete failure'); END"
        )

        val failure = try {
            DownloadRepository(database).completeAndDelete(
                linkedId,
                "SUCCESS_WITH_WARNINGS"
            )
            null
        } catch (error: Exception) {
            error
        } finally {
            sqlite.execSQL("DROP TRIGGER fail_completed_download_delete")
        }

        assertTrue(failure != null)
        assertEquals(
            DownloadRepository.Status.Active.name,
            database.downloadDao.getNullableDownloadById(linkedId)?.status
        )
        val child = repository.getItems(operation.operationId).single()
        assertEquals(LowQualityRedownloadItemState.ACTIVE, child.stateValue)
        assertEquals("", child.reasonCode)
        assertEquals(
            LowQualityRedownloadOperationState.RUNNING,
            repository.getOperation(operation.operationId)?.stateValue
        )
    }

    @Test
    fun allFailedInspectionsFinishAsScanFailureInsteadOfNoCandidates() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        repository.checkpointScan(
            operation.operationId,
            historyId = 1,
            candidate = null,
            failed = true
        )

        repository.finishNoCandidates(operation.operationId)

        val finished = repository.getOperation(operation.operationId)!!
        assertEquals(LowQualityRedownloadOperationState.FAILED, finished.stateValue)
        assertEquals(LowQualityRedownloadRepository.REASON_SCAN_FAILED, finished.terminalReason)
    }

    @Test
    fun coordinatorFailureAtomicallyCancelsDownloadsAndTerminalizesChildren() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val queuedId = linkDownload(operation.operationId, 86, DownloadRepository.Status.Queued)
        val activeExecutionId = "low-quality-active-execution"
        val activeId = linkDownload(
            operation.operationId,
            87,
            DownloadRepository.Status.Active,
            activeExecutionId,
        )

        assertEquals(
            setOf(queuedId, activeId),
            repository.failCoordinator(operation.operationId).toSet()
        )

        assertEquals(
            setOf(DownloadRepository.Status.Cancelled.name),
            database.downloadDao.getDownloadsByIdsSuspend(listOf(queuedId, activeId))
                .map { it.status }
                .toSet()
        )
        repository.getItems(operation.operationId).forEach { child ->
            assertEquals(LowQualityRedownloadItemState.FAILED, child.stateValue)
            assertEquals(LowQualityRedownloadRepository.REASON_COORDINATOR_FAILURE, child.reasonCode)
        }
        val failed = repository.getOperation(operation.operationId)!!
        assertEquals(LowQualityRedownloadOperationState.FAILED, failed.stateValue)
        assertEquals(LowQualityRedownloadRepository.REASON_COORDINATOR_FAILURE, failed.terminalReason)
        assertNull(repository.markDownloadState(activeId, LowQualityRedownloadItemState.SUCCEEDED))
        assertTrue(DownloadCancellationRegistry.belongsTo(activeId, activeExecutionId))
        assertFalse(DownloadCancellationRegistry.belongsTo(activeId, "newer-execution"))
    }

    @Test
    fun lowQualityCoordinatorCannotReplaceHistoryRefusalWithCancellation() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val executionId = "refused-coordinator-execution"
        val linkedId = linkDownload(
            operation.operationId,
            207,
            DownloadRepository.Status.Active,
            executionId,
        )
        val issueCode = DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH.name
        database.historyReplacementBarrierDao.insertIfAbsent(
            HistoryReplacementBarrier(
                downloadId = linkedId,
                operationId = operation.operationId,
                historyId = 207,
                expectedSourceUrl = "https://example.com/207",
                expectedType = DownloadType.video.name,
                issueCode = issueCode,
                issueStage = "HISTORY",
                createdAt = 1L,
            )
        )

        repository.failCoordinator(operation.operationId)

        val persisted = database.downloadDao.getDownloadById(linkedId)
        assertEquals(DownloadRepository.Status.Error.name, persisted.status)
        assertEquals(issueCode, persisted.lastIssueCode)
        assertEquals(LowQualityRedownloadItemState.FAILED, repository.getItems(operation.operationId).single().stateValue)
        assertEquals(LowQualityRedownloadOperationState.FAILED, repository.getOperation(operation.operationId)?.stateValue)
        assertTrue(DownloadCancellationRegistry.belongsTo(linkedId, executionId))
    }

    @Test
    fun targetDeletedCarrierCannotBeReturnedToRunnableQueue() = runBlocking {
        val item = download(206).apply {
            status = DownloadRepository.Status.Queued.name
            lastIssueCode = DownloadIssueCode.HISTORY_TARGET_DELETED.name
            lastIssueStage = "HISTORY"
        }
        val id = database.downloadDao.insert(item)

        assertTrue(database.downloadDao.getQueuedDownloadsList().none { it.id == id })
        assertEquals(0, database.downloadDao.reQueueDownloadItems(listOf(id)))
        assertEquals(
            0,
            database.downloadDao.claimDownloadForWorker(
                id = id,
                expectedOperationId = item.operationId,
                expectedRetryAttempt = item.retryAttempt,
                executionId = "target-deleted-attempt",
            )
        )

        val persisted = database.downloadDao.getDownloadById(id)
        assertEquals(DownloadRepository.Status.Queued.name, persisted.status)
        assertEquals(DownloadIssueCode.HISTORY_TARGET_DELETED.name, persisted.lastIssueCode)
    }

    private suspend fun assertCancellationConvergenceRetriesGetItemsFailures(
        failureCount: Int,
        historyId: Long,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(
            operationId = operation.operationId,
            historyId = historyId,
            status = DownloadRepository.Status.Active,
            executionId = "E1",
        )
        val siblingId = database.downloadDao.insert(
            download(historyId + 1_000L).apply { playlistURL = "" },
        )
        val processId = YtdlpProcessIdentity.download(linkedId, "E1")
        val process = ControlledNativeProcess(acknowledgeOnForce = true)
        assertTrue(DownloadWorkerProcessOwners.claim(linkedId, "E1"))
        YoutubeDLCompat.registerProcessForTesting(processId, process)

        try {
            assertEquals(listOf(linkedId), repository.requestCancellation(operation.operationId))
            assertEquals(
                LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
                repository.getItems(operation.operationId).single().stateValue,
            )

            LowQualityRedownloadRepository.getItemsFailureCountForTesting = failureCount
            LowQualityRedownloadLedger.scheduleCancellationConvergence(
                context = context,
                operationId = operation.operationId,
                dbManager = database,
            )
            awaitCancellationOwnerActive(operation.operationId)
            awaitOperationState(operation.operationId, LowQualityRedownloadOperationState.CANCELLED)
            process.destroyRequested.await()
            LowQualityRedownloadRepository.getItemsFailureCountForTesting = 0
            awaitCancellationOwnerStopped(operation.operationId)

            assertEquals(1, process.destroyRequests)
            assertEquals(1, process.destroyForciblyRequests)
            assertEquals(
                LowQualityRedownloadItemState.CANCELLED,
                repository.getItems(operation.operationId).single().stateValue,
            )
            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                database.downloadDao.getNullableDownloadById(linkedId)?.status,
            )
            assertEquals(
                DownloadRepository.Status.Queued.name,
                database.downloadDao.getNullableDownloadById(siblingId)?.status,
            )
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(linkedId))
            assertFalse(DownloadWorker.hasAnyRegisteredNativeProcess(linkedId))
            assertFalse(YoutubeDLCompat.hasProcessById(processId))
        } finally {
            LowQualityRedownloadRepository.getItemsFailureCountForTesting = 0
            process.acknowledgeTermination()
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(linkedId, "E1")
        }
    }

    private suspend fun assertA11ClaimWins(
        surface: A11Surface,
        historyId: Long,
    ) = coroutineScope {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createA11Fixture(
            historyId = historyId,
            initiallyCancelRequested = false,
        )
        val queuedCandidate = requireNotNull(
            database.downloadDao.getNullableDownloadById(fixture.claimedChildId),
        )
        val firstBoundaryReached = CompletableDeferred<Unit>()
        val releaseFirstBoundary = CompletableDeferred<Unit>()
        val retriedBoundary = CompletableDeferred<List<Pair<Long, String>>>()
        val firstInvocation = AtomicBoolean(false)
        repository.beforeFinalLinkedExecutionRevalidationForTesting = { operationId, executions ->
            if (operationId == fixture.operationId) {
                if (firstInvocation.compareAndSet(false, true)) {
                    firstBoundaryReached.complete(Unit)
                    releaseFirstBoundary.await()
                } else {
                    retriedBoundary.complete(executions)
                }
            }
        }

        val coordinator = async(Dispatchers.IO) {
            invokeA11Surface(surface, fixture.operationId, context)
        }
        var claimedExecutionId: String? = null
        var claimLeaseJob: kotlinx.coroutines.Deferred<Unit>? = null
        val releaseClaimLease = CompletableDeferred<Unit>()
        try {
            firstBoundaryReached.await()

            val claimed = requireNotNull(claimA11Child(context, queuedCandidate))
            claimedExecutionId = claimed.executionId
            assertTrue(claimed.executionId.isNotBlank())
            assertEquals(
                DownloadRepository.Status.Active.name,
                database.downloadDao.getNullableDownloadById(fixture.claimedChildId)?.status,
            )

            if (surface == A11Surface.COMPLETE_CANCELLATION) {
                // The real phase-one request is already represented by the
                // running coordinator. Keep this write outside the helper's
                // final boundary so the production E2 claim can win first.
                assertEquals(
                    1,
                    database.lowQualityRedownloadDao.requestCancellation(
                        fixture.operationId,
                        102L,
                    ),
                )
            }

            val claimedExecutionLeaseHeld = CompletableDeferred<Unit>()
            val executionId = claimed.executionId
            val leaseJob = async(Dispatchers.IO) {
                withDownloadWorkerExecutionSideEffectLease(
                    downloadId = fixture.claimedChildId,
                    executionId = executionId,
                ) {
                    claimedExecutionLeaseHeld.complete(Unit)
                    releaseClaimLease.await()
                }
            }
            claimLeaseJob = leaseJob
            claimedExecutionLeaseHeld.await()

            releaseFirstBoundary.complete(Unit)
            val bypassedLease = withTimeoutOrNull(1_000L) {
                retriedBoundary.await()
            }
            assertNull(
                "Coordinator bypassed the claimed E2 execution side-effect lease",
                bypassedLease,
            )
            assertEquals(
                LowQualityRedownloadOperationState.RUNNING,
                repository.getOperation(fixture.operationId)?.stateValue,
            )
            assertEquals(
                DownloadRepository.Status.Active.name,
                database.downloadDao.getNullableDownloadById(fixture.claimedChildId)?.status,
            )

            releaseClaimLease.complete(Unit)
            leaseJob.await()
            val retryTokens = withTimeout(5_000L) { retriedBoundary.await() }
            assertTrue(retryTokens.contains(fixture.firstId to fixture.firstExecutionId))
            assertTrue(retryTokens.contains(fixture.claimedChildId to executionId))

            coordinator.await()
            assertA11Outcome(surface, fixture, context)
        } finally {
            releaseFirstBoundary.complete(Unit)
            releaseClaimLease.complete(Unit)
            claimLeaseJob?.cancel()
            if (!coordinator.isCompleted) coordinator.cancel()
            claimedExecutionId?.let { executionId ->
                DownloadWorkerExecutionOwners.release(
                    fixture.claimedChildId,
                    executionId,
                )
            }
            repository.beforeFinalLinkedExecutionRevalidationForTesting = null
            repository.beforeFinalLinkedExecutionActionForTesting = null
        }
    }

    private suspend fun assertA11TerminalWins(
        surface: A11Surface,
        historyId: Long,
    ) = coroutineScope {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createA11Fixture(
            historyId = historyId,
            initiallyCancelRequested = surface == A11Surface.COMPLETE_CANCELLATION,
        )
        val queuedCandidate = requireNotNull(
            database.downloadDao.getNullableDownloadById(fixture.claimedChildId),
        )
        val finalBoundaryReached = CompletableDeferred<Unit>()
        val releaseFinalBoundary = CompletableDeferred<Unit>()
        val firstInvocation = AtomicBoolean(false)
        repository.beforeFinalLinkedExecutionActionForTesting = { operationId, _ ->
            if (
                operationId == fixture.operationId &&
                firstInvocation.compareAndSet(false, true)
            ) {
                finalBoundaryReached.complete(Unit)
                releaseFinalBoundary.await()
            }
        }

        val coordinator = async(Dispatchers.IO) {
            invokeA11Surface(surface, fixture.operationId, context)
        }
        var claimJob: kotlinx.coroutines.Deferred<DownloadItem?>? = null
        try {
            finalBoundaryReached.await()
            val productionClaim = async(Dispatchers.IO) {
                claimA11Child(context, queuedCandidate)
            }
            claimJob = productionClaim
            val claimWhileAtomicActionHeld = withTimeoutOrNull(1_000L) {
                productionClaim.await()
            }
            assertNull(
                "Production claim completed while coordinator held the atomic action lock",
                claimWhileAtomicActionHeld,
            )
            assertFalse(productionClaim.isCompleted)
            releaseFinalBoundary.complete(Unit)
            coordinator.await()

            repository.beforeFinalLinkedExecutionRevalidationForTesting = null
            repository.beforeFinalLinkedExecutionActionForTesting = null
            assertNull(productionClaim.await())
            assertNull(DownloadWorkerExecutionOwners.ownerOf(fixture.claimedChildId))
            assertA11Outcome(surface, fixture, context)
        } finally {
            releaseFinalBoundary.complete(Unit)
            claimJob?.cancel()
            if (!coordinator.isCompleted) coordinator.cancel()
            DownloadWorkerExecutionOwners.ownerOf(fixture.claimedChildId)?.let { executionId ->
                DownloadWorkerExecutionOwners.release(
                    fixture.claimedChildId,
                    executionId,
                )
            }
            repository.beforeFinalLinkedExecutionRevalidationForTesting = null
            repository.beforeFinalLinkedExecutionActionForTesting = null
        }
    }

    private suspend fun createA11Fixture(
        historyId: Long,
        initiallyCancelRequested: Boolean,
    ): A11Fixture {
        val operation = repository.createOrReconnect(now = 100)
        val firstId = linkDownload(
            operationId = operation.operationId,
            historyId = historyId,
            status = DownloadRepository.Status.Active,
            executionId = "a11-E1",
        )
        val claimedChildId = linkDownload(
            operationId = operation.operationId,
            historyId = historyId + 1L,
            status = DownloadRepository.Status.Queued,
        )
        if (initiallyCancelRequested) {
            assertEquals(
                1,
                database.lowQualityRedownloadDao.requestCancellation(
                    operation.operationId,
                    101L,
                ),
            )
        }
        return A11Fixture(
            operationId = operation.operationId,
            firstId = firstId,
            firstExecutionId = "a11-E1",
            claimedChildId = claimedChildId,
        )
    }

    private suspend fun invokeA11Surface(
        surface: A11Surface,
        operationId: String,
        context: Context,
    ): Any = when (surface) {
        A11Surface.REQUEST_CANCELLATION -> repository.requestCancellation(operationId)
        A11Surface.COMPLETE_CANCELLATION ->
            repository.completePersistedCancellationWithPublications(
                operationId = operationId,
                context = context,
            )
        A11Surface.COORDINATOR_FAILURE ->
            repository.failCoordinatorWithPublications(
                operationId = operationId,
                context = context,
            )
    }

    private suspend fun claimA11Child(
        context: Context,
        candidate: DownloadItem,
    ): DownloadItem? = claimDownloadThroughProductionAdmission(
        context = context,
        dbManager = database,
        candidate = candidate,
        // Keep A's linked E1 live while leaving one real admission slot for
        // B to win the coordinator race.
        concurrentDownloadLimit = 2,
    )

    private suspend fun assertA11Outcome(
        surface: A11Surface,
        fixture: A11Fixture,
        context: Context,
    ) {
        repository.beforeFinalLinkedExecutionRevalidationForTesting = null
        repository.beforeFinalLinkedExecutionActionForTesting = null
        when (surface) {
            A11Surface.REQUEST_CANCELLATION -> {
                val phaseOne = requireNotNull(repository.getOperation(fixture.operationId))
                assertEquals(LowQualityRedownloadOperationState.RUNNING, phaseOne.stateValue)
                assertTrue(phaseOne.cancelRequested)
                assertEquals(
                    LowQualityRedownloadItemState.CANCELLATION_REQUESTED,
                    repository.getItems(fixture.operationId)
                        .single { it.downloadId == fixture.claimedChildId }
                        .stateValue,
                )
                repository.completePersistedCancellationWithPublications(
                    operationId = fixture.operationId,
                    context = context,
                )
                assertEquals(
                    LowQualityRedownloadOperationState.CANCELLED,
                    repository.getOperation(fixture.operationId)?.stateValue,
                )
            }
            A11Surface.COMPLETE_CANCELLATION -> assertEquals(
                LowQualityRedownloadOperationState.CANCELLED,
                repository.getOperation(fixture.operationId)?.stateValue,
            )
            A11Surface.COORDINATOR_FAILURE -> assertEquals(
                LowQualityRedownloadOperationState.FAILED,
                repository.getOperation(fixture.operationId)?.stateValue,
            )
        }
        DownloadExecutionRecovery.reconcile(context, database)
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getNullableDownloadById(fixture.claimedChildId)?.status,
        )
    }

    private enum class A11Surface {
        REQUEST_CANCELLATION,
        COMPLETE_CANCELLATION,
        COORDINATOR_FAILURE,
    }

    private data class A11Fixture(
        val operationId: String,
        val firstId: Long,
        val firstExecutionId: String,
        val claimedChildId: Long,
    )

    private suspend fun awaitOperationState(
        operationId: String,
        state: LowQualityRedownloadOperationState,
    ) {
        withTimeout(20_000L) {
            while (repository.getOperation(operationId)?.stateValue != state) {
                delay(25L)
            }
        }
    }

    private suspend fun awaitCancellationOwnerActive(operationId: String) {
        withTimeout(2_000L) {
            while (!LowQualityRedownloadLedger.isCancellationConvergenceActiveForTesting(operationId)) {
                delay(10L)
            }
        }
    }

    private suspend fun awaitCancellationOwnerStopped(operationId: String) {
        withTimeout(20_000L) {
            while (LowQualityRedownloadLedger.isCancellationConvergenceActiveForTesting(operationId)) {
                delay(25L)
            }
        }
    }

    private suspend fun awaitAbandonedUndoOwnerActive(token: String) {
        withTimeout(2_000L) {
            while (!LowQualityRedownloadLedger.isAbandonedUndoConvergenceActiveForTesting(token)) {
                delay(10L)
            }
        }
    }

    private suspend fun awaitEnqueueCalls(
        calls: AtomicInteger,
        expected: Int,
    ) {
        withTimeout(5_000L) {
            while (calls.get() < expected) {
                delay(10L)
            }
        }
    }

    private suspend fun awaitEnqueueOwnerStopped(operationId: String) {
        withTimeout(20_000L) {
            while (LowQualityRedownloadLedger.isEnqueueConvergenceActiveForTesting(operationId)) {
                delay(25L)
            }
        }
    }

    private suspend fun assertRecoveryEnqueueFailureConverges(
        phase: LowQualityRedownloadPhase,
        historyId: Long,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val operation = repository.createOrReconnect(now = 100)
        assertTrue(
            repository.advancePhase(
                operation.operationId,
                LowQualityRedownloadPhase.SCANNING,
                phase,
            )
        )
        val calls = AtomicInteger(0)
        val first = ControlledWorkManagerOperation()
        val second = ControlledWorkManagerOperation()
        val manager = LowQualityRedownloadManager.createForTesting(
            context = context,
            database = database,
        ) { _, _, _ ->
            if (calls.getAndIncrement() == 0) first else second
        }

        manager.reconcile()
        awaitEnqueueCalls(calls, 1)
        assertEquals(phase, repository.getOperation(operation.operationId)?.phaseValue)
        first.fail(IllegalStateException("injected $phase enqueue failure ($historyId)"))
        awaitEnqueueCalls(calls, 2)
        second.succeed()
        awaitEnqueueOwnerStopped(operation.operationId)
        assertEquals(phase, repository.getOperation(operation.operationId)?.phaseValue)
    }

    private suspend fun createAwaitingSelectionOperationWithIdentity(historyId: Long): String {
        val operation = repository.createOrReconnect(now = 100)
        database.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = historyId,
                intendedSourceUrl = "https://example.com/$historyId",
                intendedType = DownloadType.video.name,
                selected = true,
                itemState = LowQualityRedownloadItemState.PENDING.name,
            )
        )
        assertTrue(
            repository.advancePhase(
                operation.operationId,
                LowQualityRedownloadPhase.SCANNING,
                LowQualityRedownloadPhase.AWAITING_SELECTION,
            )
        )
        return operation.operationId
    }

    private suspend fun runStartupUndoRecovery(context: Context) {
        runStartupCancellationReconciliation(
            reconcile = {
                LowQualityRedownloadManager.get(context)
                    .reconcileCancellationDebt(database)
            },
            reportFailure = { throw AssertionError("Unexpected startup Undo recovery failure", it) },
        )
    }

    @Test
    fun cancelRequestedQualityReplacementCannotBeQueuedOrClaimed() = runBlocking {
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(operation.operationId, 313, DownloadRepository.Status.Queued)

        assertEquals(1, database.lowQualityRedownloadDao.requestCancellation(operation.operationId, 101L))
        assertTrue(database.downloadDao.getQueuedDownloadsList().none { it.id == linkedId })
        assertEquals(0, database.downloadDao.reQueueDownloadItems(listOf(linkedId)))
        assertEquals(
            0,
            database.downloadDao.claimDownloadForWorker(
                id = linkedId,
                expectedOperationId = "download-operation",
                expectedRetryAttempt = 0,
                executionId = "cancel-requested-worker",
            ),
        )

        val childOperation = repository.createOrReconnect(now = 200)
        val childPendingId = linkDownload(childOperation.operationId, 314, DownloadRepository.Status.Queued)
        assertEquals(
            1,
            database.lowQualityRedownloadDao.markPendingUserCancellation(
                downloadId = childPendingId,
                token = "PENDING_USER_CANCEL:314",
                updatedAt = 201L,
            ),
        )
        assertTrue(database.downloadDao.getQueuedDownloadsList().none { it.id == childPendingId })
        assertEquals(0, database.downloadDao.reQueueDownloadItems(listOf(childPendingId)))
        assertEquals(
            0,
            database.downloadDao.claimDownloadForWorker(
                id = childPendingId,
                expectedOperationId = "download-operation",
                expectedRetryAttempt = 0,
                executionId = "child-cancel-requested-worker",
            ),
        )
    }

    @Test
    fun startupCancellationDebtConvergesWhenRuntimeInitializationFails() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val operation = repository.createOrReconnect(now = 100)
        val linkedId = linkDownload(
            operationId = operation.operationId,
            historyId = 315,
            status = DownloadRepository.Status.Active,
            executionId = "a6-startup-E1",
        )

        assertEquals(
            listOf(linkedId),
            repository.requestCancellation(operation.operationId),
        )

        var ordinaryReconciliationRan = false
        runStartupReconciliation(
            readiness = CompletableDeferred(false),
            reconcile = { ordinaryReconciliationRan = true },
            reportFailure = { throw AssertionError("Unexpected ordinary recovery failure", it) },
        )
        assertFalse(ordinaryReconciliationRan)

        var cancellationReconciliationRan = false
        var cancellationFailure: Exception? = null
        runStartupCancellationReconciliation(
            reconcile = {
                cancellationReconciliationRan =
                    LowQualityRedownloadManager.get(context)
                        .reconcileCancellationDebt(database)
            },
            reportFailure = { cancellationFailure = it },
        )

        assertTrue(cancellationReconciliationRan)
        assertNull(cancellationFailure)
        assertEquals(
            LowQualityRedownloadOperationState.CANCELLED,
            repository.getOperation(operation.operationId)?.stateValue,
        )
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            repository.getItems(operation.operationId).single().stateValue,
        )
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            database.downloadDao.getNullableDownloadById(linkedId)?.status,
        )
        assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(linkedId))
        assertTrue(database.downloadDao.getQueuedDownloadsList().none { it.id == linkedId })
    }

    private suspend fun linkDownload(
        operationId: String,
        historyId: Long,
        status: DownloadRepository.Status,
        executionId: String = "",
    ): Long {
        database.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operationId,
                historyId = historyId,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name
            )
        )
        val id = repository.linkDownloadAtomically(
            operationId,
            historyId,
            download(historyId).apply { this.executionId = executionId },
        )!!
        database.downloadDao.setStatus(id, status.name)
        return id
    }

    private suspend fun createAwaitingSelectionOperation(
        historyId: Long
    ): com.ireum.ytdl.database.models.LowQualityRedownloadOperation {
        val operation = repository.createOrReconnect(now = 100)
        database.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = historyId,
                selected = true,
                itemState = LowQualityRedownloadItemState.PENDING.name
            )
        )
        assertTrue(
            repository.advancePhase(
                operation.operationId,
                LowQualityRedownloadPhase.SCANNING,
                LowQualityRedownloadPhase.AWAITING_SELECTION
            )
        )
        return operation
    }

    private suspend fun insertActiveMembershipSource(
        historyId: Long,
        url: String,
    ): Long = database.observeSourcesDao.insert(
        ObserveSourcesItem(
            id = 0,
            name = "Membership source",
            url = url,
            downloadItemTemplate = download(historyId),
            everyNr = 1,
            everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
            everyTime = 0,
            weeklyConfig = null,
            monthlyConfig = null,
            status = ObserveSourcesRepository.SourceStatus.ACTIVE,
            startsTime = 0,
            endsDate = 0,
            endsAfterCount = 0,
            runCount = 0,
            getOnlyNewUploads = false,
            retryMissingDownloads = false,
            ignoredLinks = mutableListOf(),
            alreadyProcessedLinks = mutableListOf(),
            syncWithSource = false,
        )
    )

    private fun download(historyId: Long) = DownloadItem(
        id = 0,
        url = "https://example.com/$historyId",
        title = "Title",
        author = "Creator",
        thumb = "",
        duration = "1:00",
        type = DownloadType.video,
        format = Format(format_id = "1080p"),
        container = "mp4",
        downloadSections = "",
        allFormats = arrayListOf(),
        downloadPath = "/downloads",
        website = "example.com",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "%(title)s",
        SaveThumb = false,
        status = DownloadRepository.Status.Queued.name,
        downloadStartTime = 0,
        logID = null,
        playlistURL = HistoryRedownloadMarker.quality(historyId, 1080),
        operationId = "download-operation"
    )

    private class ControlledWorkManagerOperation : Operation {
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

    private class ControlledNativeProcess(
        private val acknowledgeOnForce: Boolean,
    ) : Process() {
        private val terminated = CountDownLatch(1)
        private var alive = true
        val destroyRequested = CompletableDeferred<Unit>()
        var destroyRequests = 0
            private set
        var destroyForciblyRequests = 0
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            terminated.await()
            return 143
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            terminated.await(timeout, unit)

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return 143
        }

        override fun destroy() {
            destroyRequests += 1
            destroyRequested.complete(Unit)
        }

        override fun destroyForcibly(): Process {
            destroyForciblyRequests += 1
            if (acknowledgeOnForce) acknowledgeTermination()
            return this
        }

        override fun isAlive(): Boolean = alive

        fun acknowledgeTermination() {
            alive = false
            terminated.countDown()
        }
    }
}
