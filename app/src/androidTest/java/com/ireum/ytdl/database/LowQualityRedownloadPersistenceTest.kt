package com.ireum.ytdl.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadOperationState
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.ui.downloads.shouldPresentLowQualitySelection
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.work.HistoryReplacementPersistenceResult
import com.ireum.ytdl.work.dispatchLowQualityRedownloadRecovery
import com.ireum.ytdl.work.persistHistoryReplacementTerminalState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LowQualityRedownloadPersistenceTest {
    private lateinit var database: DBManager
    private lateinit var repository: LowQualityRedownloadRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        repository = LowQualityRedownloadRepository(database)
    }

    @After
    fun closeDatabase() {
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
        val token = DownloadRepository(database)
            .beginUndoableCancellation(linkedId)
            .pendingToken
        assertTrue(token!!.startsWith(DownloadRepository.PENDING_CANCELLATION_TOKEN_PREFIX))

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
        val activeId = linkDownload(operation.operationId, 87, DownloadRepository.Status.Active)

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
    }

    private suspend fun linkDownload(
        operationId: String,
        historyId: Long,
        status: DownloadRepository.Status
    ): Long {
        database.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operationId,
                historyId = historyId,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name
            )
        )
        val id = repository.linkDownloadAtomically(operationId, historyId, download(historyId))!!
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
}
