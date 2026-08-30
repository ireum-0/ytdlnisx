package com.ireum.ytdl.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.content.ContextCompat
import com.ireum.ytdl.database.dao.DownloadClaimTestHooks
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.receiver.CancelDownloadNotificationReceiver
import com.ireum.ytdl.receiver.PauseDownloadNotificationReceiver
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.download.DownloadIssue
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.DownloadWorkerAdmissionResult
import com.ireum.ytdl.work.DownloadWorkerEffectTestHooks
import com.ireum.ytdl.work.DownloadWorkerExecutionOwners
import com.ireum.ytdl.work.DownloadWorkerProcessOwners
import com.ireum.ytdl.work.claimDownloadThroughProductionAdmission
import com.ireum.ytdl.work.admitQueuedDownloadsThroughProductionPath
import com.ireum.ytdl.work.hasDurableUserStopRevokedAuthority
import com.ireum.ytdl.work.observeQueuedDownloadsAfterRecovery
import com.ireum.ytdl.work.publishNoCacheMediaWithOwnedExecution
import com.ireum.ytdl.work.publishTerminalWorkerProgressWithOwnedExecution
import com.ireum.ytdl.work.persistHistoryReplacementTerminalStateWithOwnedExecution
import com.ireum.ytdl.work.YtdlpProcessIdentity
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.Process

@RunWith(AndroidJUnit4::class)
class FindingAProductionWiringTest {
    private lateinit var db: DBManager
    private lateinit var historyRepository: HistoryRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(context)
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        CancelDownloadNotificationReceiver.beforeAsyncBodyForTesting = null
        CancelDownloadNotificationReceiver.finishObserverForTesting = null
        CancelDownloadNotificationReceiver.beforeStopLeaseForTesting = null
        PauseDownloadNotificationReceiver.beforeAsyncBodyForTesting = null
        PauseDownloadNotificationReceiver.finishObserverForTesting = null
        PauseDownloadNotificationReceiver.beforeStopLeaseForTesting = null
        PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = null
        DownloadRepository.userStopWriteFailureForTesting = null
        DownloadRepository.userStopWriteNoOpForTesting = null
        DownloadWorkerEffectTestHooks.beforeNoCacheMediaPublicationForTesting = null
        DownloadWorkerEffectTestHooks.beforeNoCacheMediaScanForTesting = null
        DownloadWorkerEffectTestHooks.beforeTerminalProgressPublicationForTesting = null
        DownloadWorkerEffectTestHooks.beforeTerminalProgressPostForTesting = null
        DownloadWorkerEffectTestHooks.beforeFailureTerminalPersistenceForTesting = null
        DownloadWorkerEffectTestHooks.dbManagerForTesting = null
        DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        DownloadWorkerEffectTestHooks.failureTerminalPersistenceForTesting = null
        DownloadWorkerEffectTestHooks.failureTerminalPersistenceNoOpForTesting = null
        DownloadWorkerEffectTestHooks.beforeUnexpectedErrorNotificationForTesting = null
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DBManager::class.java,
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
        historyRepository = HistoryRepository(db.historyDao, db.playlistDao)
        YtdlpNativeProcessBarrier.configure(ApplicationProvider.getApplicationContext())
    }

    @After
    fun closeDb() {
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(ApplicationProvider.getApplicationContext())
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        CancelDownloadNotificationReceiver.beforeAsyncBodyForTesting = null
        CancelDownloadNotificationReceiver.finishObserverForTesting = null
        CancelDownloadNotificationReceiver.beforeStopLeaseForTesting = null
        PauseDownloadNotificationReceiver.beforeAsyncBodyForTesting = null
        PauseDownloadNotificationReceiver.finishObserverForTesting = null
        PauseDownloadNotificationReceiver.beforeStopLeaseForTesting = null
        PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = null
        DownloadRepository.userStopWriteFailureForTesting = null
        DownloadRepository.userStopWriteNoOpForTesting = null
        DownloadWorkerEffectTestHooks.beforeNoCacheMediaPublicationForTesting = null
        DownloadWorkerEffectTestHooks.beforeNoCacheMediaScanForTesting = null
        DownloadWorkerEffectTestHooks.beforeTerminalProgressPublicationForTesting = null
        DownloadWorkerEffectTestHooks.beforeTerminalProgressPostForTesting = null
        DownloadWorkerEffectTestHooks.beforeFailureTerminalPersistenceForTesting = null
        DownloadWorkerEffectTestHooks.dbManagerForTesting = null
        DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        DownloadWorkerEffectTestHooks.failureTerminalPersistenceForTesting = null
        DownloadWorkerEffectTestHooks.failureTerminalPersistenceNoOpForTesting = null
        DownloadWorkerEffectTestHooks.beforeUnexpectedErrorNotificationForTesting = null
        DownloadExecutionRecovery.recoveryReadFailureCountForTesting = 0
        DownloadExecutionRecovery.failCommittedHistoryFinalizationForTesting = false
        YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
        YtdlpNativeProcessBarrier.markerEnumerationFailureForTesting = false
        DownloadClaimTestHooks.resetForTesting()
        db.close()
    }

    @Test
    fun staleArtistAndThumbnailWritesPreserveReplacementMetadata() = runBlocking {
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val stale = db.historyDao.getItem(historyId)
        db.historyDao.updateRaw(
            stale.copy(
                title = "replacement title",
                duration = "99:00",
                durationSeconds = 5940,
                thumb = "replacement-thumb",
                format = stale.format.copy(container = "mkv"),
                filesize = 99_000,
            )
        )

        assertTrue(historyRepository.updateArtist(historyId, "new artist"))
        assertFalse(historyRepository.updateThumb(historyId, stale.thumb, "local-thumb"))

        val current = db.historyDao.getItem(historyId)
        assertEquals("new artist", current.artist)
        assertEquals("replacement-thumb", current.thumb)
        assertEquals("replacement title", current.title)
        assertEquals("99:00", current.duration)
        assertEquals(5940, current.durationSeconds)
        assertEquals("mkv", current.format.container)
        assertEquals(99_000, current.filesize)
    }

    @Test
    fun staleKeywordIntentPreservesReplacementMetadata() = runBlocking {
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val stale = db.historyDao.getItem(historyId)
        db.historyDao.updateRaw(
            stale.copy(
                title = "replacement title",
                durationSeconds = 3600,
                format = stale.format.copy(container = "webm"),
                filesize = 360_000,
            )
        )

        HistoryKeywordAssignmentRepository(db)
            .updateManualFromMaterializedEditor(historyId, listOf("manual-keyword"))

        val current = db.historyDao.getItem(historyId)
        assertEquals("manual-keyword", current.keywords)
        assertEquals("replacement title", current.title)
        assertEquals(3600, current.durationSeconds)
        assertEquals("webm", current.format.container)
        assertEquals(360_000, current.filesize)
    }

    @Test
    fun fieldScopedWriterTreatsDeletedTargetAsNoOp() {
        assertFalse(historyRepository.updateArtist(404L, "ignored"))
        assertFalse(historyRepository.updateThumb(404L, "missing", "ignored"))
    }

    @Test
    fun committedHistoryReplacementIsDebtForTheRunningRequeuePrimitive() = runBlocking {
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val download = download()
        val downloadId = db.downloadDao.insertRaw(download)
        val committed = db.historyDao.getItem(historyId).copy(downloadId = downloadId)
        db.historyDao.updateRaw(committed)
        db.downloadDao.updateRaw(
            download.copy(
                id = downloadId,
                playlistURL = "history-redownload:$historyId",
                executionId = "E1",
                status = DownloadRepository.Status.Active.name,
            )
        )

        val result = DownloadRepository(db).requeueRunningDownload(downloadId, "E1")

        assertEquals(
            DownloadRepository.RunningDownloadRequeueResult.COMMITTED_HISTORY_FINALIZATION_DEBT,
            result,
        )
        assertEquals(DownloadRepository.Status.Active.name, db.downloadDao.getNullableDownloadById(downloadId)?.status)
        assertEquals("E1", db.downloadDao.getNullableDownloadById(downloadId)?.executionId)
    }

    @Test
    fun ordinaryExactTokenRunningRowStillRequeues() = runBlocking {
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "E1"))

        val result = DownloadRepository(db).requeueRunningDownload(downloadId, "E1")

        assertEquals(DownloadRepository.RunningDownloadRequeueResult.REQUEUED, result)
        assertEquals(DownloadRepository.Status.Queued.name, db.downloadDao.getNullableDownloadById(downloadId)?.status)
        assertEquals("", db.downloadDao.getNullableDownloadById(downloadId)?.executionId)
    }

    @Test
    fun claimZeroRowsAndWriteFailurePublishNoExecutionOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                operationId = "admission-control",
                executionId = "",
            )
        )
        val candidate = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        var callbackCount = 0

        assertNull(
            claimDownloadThroughProductionAdmission(
                context = context,
                dbManager = db,
                candidate = candidate.copy(operationId = "stale-operation"),
                concurrentDownloadLimit = 1,
                onClaimed = { callbackCount += 1 },
            )
        )
        assertEquals(0, callbackCount)
        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
        assertNull(DownloadWorkerExecutionOwners.ownerOf(downloadId))

        DownloadClaimTestHooks.failNextClaimWriteForTesting()
        val failure = try {
            claimDownloadThroughProductionAdmission(
                context = context,
                dbManager = db,
                candidate = candidate,
                concurrentDownloadLimit = 1,
                onClaimed = { callbackCount += 1 },
            )
            null
        } catch (error: IllegalStateException) {
            error
        }

        assertNotNull(failure)
        assertEquals(0, callbackCount)
        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
        assertEquals("", db.downloadDao.getNullableDownloadById(downloadId)?.executionId)
        assertNull(DownloadWorkerExecutionOwners.ownerOf(downloadId))
        assertNull(DownloadWorkerProcessOwners.ownerOf(downloadId))
    }

    @Test
    fun postClaimMaterializationFailureRollsBackAndSiblingCanClaimCapacity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val firstId = db.downloadDao.insertRaw(
            download().copy(
                title = "first",
                status = DownloadRepository.Status.Queued.name,
                operationId = "admission-first",
                executionId = "",
            )
        )
        val siblingId = db.downloadDao.insertRaw(
            download().copy(
                title = "sibling",
                status = DownloadRepository.Status.Queued.name,
                operationId = "admission-sibling",
                retryAttempt = 3,
                executionId = "",
            )
        )
        var callbackCount = 0
        val firstCandidate = requireNotNull(db.downloadDao.getNullableDownloadById(firstId))

        DownloadClaimTestHooks.failNextMaterializationReadForTesting()
        val failure = try {
            claimDownloadThroughProductionAdmission(
                context = context,
                dbManager = db,
                candidate = firstCandidate,
                concurrentDownloadLimit = 1,
                onClaimed = { callbackCount += 1 },
            )
            null
        } catch (error: IllegalStateException) {
            error
        }

        assertNotNull(failure)
        assertEquals(0, callbackCount)
        val rolledBack = requireNotNull(db.downloadDao.getNullableDownloadById(firstId))
        assertEquals(DownloadRepository.Status.Queued.name, rolledBack.status)
        assertEquals("", rolledBack.executionId)
        assertNull(DownloadWorkerExecutionOwners.ownerOf(firstId))
        assertNull(DownloadWorkerProcessOwners.ownerOf(firstId))

        val siblingCandidate = requireNotNull(db.downloadDao.getNullableDownloadById(siblingId))
        try {
            val siblingClaimed = requireNotNull(
                claimDownloadThroughProductionAdmission(
                    context = context,
                    dbManager = db,
                    candidate = siblingCandidate,
                    concurrentDownloadLimit = 1,
                    onClaimed = { callbackCount += 1 },
                )
            )

            assertEquals(1, callbackCount)
            assertEquals(siblingId, siblingClaimed.id)
            assertEquals(siblingCandidate.url, siblingClaimed.url)
            assertEquals(siblingCandidate.operationId, siblingClaimed.operationId)
            assertEquals(siblingCandidate.retryAttempt, siblingClaimed.retryAttempt)
            assertEquals(DownloadRepository.Status.Active.name, siblingClaimed.status)
            assertTrue(siblingClaimed.executionId.isNotBlank())
            assertEquals(
                siblingClaimed.executionId,
                DownloadWorkerExecutionOwners.ownerOf(siblingId),
            )
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(firstId)?.status,
            )
        } finally {
            DownloadWorkerExecutionOwners.ownerOf(siblingId)?.let { executionId ->
                DownloadWorkerExecutionOwners.release(siblingId, executionId)
            }
            DownloadExecutionRecovery.reconcile(context, db)
        }
    }

    @Test
    fun exactClaimHandoffRecoversAtProcessDeathWindows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val beforeOwnerId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                operationId = "admission-before-owner",
                executionId = "",
            )
        )
        val beforeOwnerCandidate = requireNotNull(
            db.downloadDao.getNullableDownloadById(beforeOwnerId),
        )
        DownloadClaimTestHooks.afterClaimMaterializationBeforeOwnerPublicationForTesting = {
            throw IllegalStateException("simulated process death before owner publication")
        }

        val beforeOwnerFailure = try {
            claimDownloadThroughProductionAdmission(
                context = context,
                dbManager = db,
                candidate = beforeOwnerCandidate,
                concurrentDownloadLimit = 1,
            )
            null
        } catch (error: IllegalStateException) {
            error
        }
        DownloadClaimTestHooks.afterClaimMaterializationBeforeOwnerPublicationForTesting = null

        assertNotNull(beforeOwnerFailure)
        assertNull(DownloadWorkerExecutionOwners.ownerOf(beforeOwnerId))
        DownloadExecutionRecovery.reconcile(context, db)
        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(beforeOwnerId)?.status,
        )
        assertEquals("", db.downloadDao.getNullableDownloadById(beforeOwnerId)?.executionId)

        val afterOwnerId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                operationId = "admission-after-owner",
                executionId = "",
            )
        )
        val afterOwnerCandidate = requireNotNull(
            db.downloadDao.getNullableDownloadById(afterOwnerId),
        )
        DownloadClaimTestHooks.afterExecutionOwnerPublicationForTesting = { claimed ->
            DownloadWorkerExecutionOwners.release(claimed.id, claimed.executionId)
            throw IllegalStateException("simulated process death after owner publication")
        }

        val afterOwnerFailure = try {
            claimDownloadThroughProductionAdmission(
                context = context,
                dbManager = db,
                candidate = afterOwnerCandidate,
                concurrentDownloadLimit = 1,
            )
            null
        } catch (error: IllegalStateException) {
            error
        }
        DownloadClaimTestHooks.afterExecutionOwnerPublicationForTesting = null

        assertNotNull(afterOwnerFailure)
        assertNull(DownloadWorkerExecutionOwners.ownerOf(afterOwnerId))
        DownloadExecutionRecovery.reconcile(context, db)
        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(afterOwnerId)?.status,
        )
        assertEquals("", db.downloadDao.getNullableDownloadById(afterOwnerId)?.executionId)
    }

    @Test
    fun normalClaimAttachesExactStateAndRestartRecoversAfterAttachment() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                title = "exact claimed configuration",
                status = DownloadRepository.Status.Queued.name,
                operationId = "admission-normal",
                retryAttempt = 4,
                executionId = "",
            )
        )
        val candidate = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        var attached: DownloadItem? = null

        val claimed = requireNotNull(
            claimDownloadThroughProductionAdmission(
                context = context,
                dbManager = db,
                candidate = candidate,
                concurrentDownloadLimit = 1,
                onClaimed = { attached = it },
            )
        )
        assertEquals(claimed, attached)
        assertEquals(candidate.url, claimed.url)
        assertEquals(candidate.operationId, claimed.operationId)
        assertEquals(candidate.retryAttempt, claimed.retryAttempt)
        assertEquals(DownloadRepository.Status.Active.name, claimed.status)
        assertTrue(claimed.executionId.isNotBlank())
        assertEquals(claimed.executionId, DownloadWorkerExecutionOwners.ownerOf(downloadId))
        assertEquals(claimed, db.downloadDao.getNullableDownloadById(downloadId))

        // A process restart removes process-local ownership. Recovery must
        // converge this exact E1 before a later claim can create another ID.
        DownloadWorkerExecutionOwners.release(downloadId, claimed.executionId)
        DownloadExecutionRecovery.reconcile(context, db)
        assertNull(DownloadWorkerExecutionOwners.ownerOf(downloadId))
        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
        assertEquals("", db.downloadDao.getNullableDownloadById(downloadId)?.executionId)
    }

    @Test
    fun committedHistoryReplacementCannotBeClaimedAsFreshQueueAttempt() = runBlocking {
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                status = DownloadRepository.Status.Queued.name,
                executionId = "",
            )
        )
        db.historyDao.updateRaw(db.historyDao.getItem(historyId).copy(downloadId = downloadId))

        val candidate = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertEquals(
            null,
            claimDownloadThroughProductionAdmission(
                context = ApplicationProvider.getApplicationContext(),
                dbManager = db,
                candidate = candidate,
                concurrentDownloadLimit = 1,
            )
        )
        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
    }

    @Test
    fun markerOnlyRecoveryDebtFencesBlankCandidateButNotHealthySibling() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staleExecutionId = "marker-only-E1"
        val staleId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                executionId = "",
            )
        )
        val healthyId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                playlistURL = "",
                executionId = "",
            )
        )
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = YtdlpProcessIdentity.download(staleId, staleExecutionId),
            state = "RUNNING",
            generationToken = "marker-only-generation-${UUID.randomUUID()}",
        )

        try {
            // The marker is the only E1 carrier: there is no recovery journal
            // and no process-local owner. Make startup recovery defer it on an
            // unreadable pass, while keeping the filename debt visible to the
            // production claim predicate.
            YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = marker.absolutePath
            val recovery = observeQueuedDownloadsAfterRecovery(
                context = context,
                dbManager = db,
                priorityItemIds = emptyList(),
                currentTimeMillis = System.currentTimeMillis() + 10_000L,
            )
            recovery.queuedItems.first()

            assertTrue(recovery.recovery.deferredDownloadIds.contains(staleId))
            assertTrue(
                DownloadExecutionRecovery.pendingDownloadIds(context)
                    .none { it == staleId },
            )
            assertNull(DownloadWorkerProcessOwners.ownerOf(staleId))
            assertTrue(YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(staleId))

            assertNull(
                claimDownloadThroughProductionAdmission(
                    context = context,
                    dbManager = db,
                    candidate = requireNotNull(db.downloadDao.getNullableDownloadById(staleId)),
                    concurrentDownloadLimit = 1,
                ),
            )
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(staleId)?.status,
            )
            assertEquals("", db.downloadDao.getNullableDownloadById(staleId)?.executionId)

            // A's marker debt is scoped to A. B still crosses the same
            // production admission and claim boundary successfully.
            val siblingAdmission = admitThroughProductionPath(
                items = listOf(
                    requireNotNull(db.downloadDao.getNullableDownloadById(healthyId)),
                    requireNotNull(db.downloadDao.getNullableDownloadById(staleId)),
                ),
                concurrentDownloadLimit = 1,
            )
            assertTrue(siblingAdmission.selectedCandidates.any { it.id == healthyId })
            assertTrue(siblingAdmission.claimedItems.any { it.id == healthyId })
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(healthyId)?.status,
            )
            siblingAdmission.claimedItems.forEach { claimed ->
                DownloadWorkerExecutionOwners.release(claimed.id, claimed.executionId)
            }
        } finally {
            DownloadWorkerExecutionOwners.ownerOf(healthyId)?.let { executionId ->
                DownloadWorkerExecutionOwners.release(healthyId, executionId)
            }
            YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(staleId)
            DownloadExecutionRecovery.reconcile(context, db)
            marker.delete()
        }
    }

    @Test
    fun scheduledRecoveryRetriesFirstOwnerReadAfterFinalizationFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                executionId = "dead-finalization-E1",
                status = DownloadRepository.Status.Active.name,
            )
        )
        db.historyDao.updateRaw(db.historyDao.getItem(historyId).copy(downloadId = downloadId))

        try {
            // Model a committed History replacement whose first finalization
            // pass fails. The recovery owner itself then loses its first DB
            // read; that ordinary failure must not remove retryJobs.
            DownloadExecutionRecovery.recoveryReadFailureCountForTesting = 1
            DownloadExecutionRecovery.failCommittedHistoryFinalizationForTesting = true
            val first = DownloadExecutionRecovery.reconcile(context, db)
            assertTrue(first.deferredDownloadIds.contains(downloadId))
            assertNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            awaitRecoveryJobActive(downloadId)

            withTimeout(20_000L) {
                while (
                    db.downloadDao.getNullableDownloadById(downloadId) != null ||
                        DownloadExecutionRecovery.isRecoveryJobActiveForTesting(downloadId)
                ) {
                    delay(25L)
                }
            }

            assertNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertEquals(downloadId, db.historyDao.getItem(historyId).downloadId)

            // A second authoritative pass is a no-op: the committed History
            // finalization was completed once and cannot be reinterpreted as
            // a fresh queued execution.
            DownloadExecutionRecovery.reconcile(context, db)
            assertNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertEquals(downloadId, db.historyDao.getItem(historyId).downloadId)
        } finally {
            DownloadExecutionRecovery.recoveryReadFailureCountForTesting = 0
            DownloadExecutionRecovery.failCommittedHistoryFinalizationForTesting = false
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
        }
    }

    @Test
    fun staleRecoveryOwnedActiveRowDoesNotConsumeProductionConcurrencySlot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staleId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = "stale-E1",
            )
        )
        val healthyId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                playlistURL = "",
                executionId = "",
            )
        )
        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context,
                requireNotNull(db.downloadDao.getNullableDownloadById(staleId)),
            )
        )

        try {
            val admission = admitThroughProductionPath(
                items = listOf(requireNotNull(db.downloadDao.getNullableDownloadById(healthyId))),
                concurrentDownloadLimit = 1,
            )

            assertTrue(admission.ownership.recoveryOwnedIds.contains(staleId))
            assertFalse(admission.ownership.liveCapacityIds.contains(staleId))
            assertTrue(admission.selectedCandidates.any { it.id == healthyId })
            assertTrue(admission.claimedItems.any { it.id == healthyId })
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(staleId)?.status,
            )
            assertEquals(
                "stale-E1",
                db.downloadDao.getNullableDownloadById(staleId)?.executionId,
            )
        } finally {
            DownloadWorkerExecutionOwners.ownerOf(healthyId)?.let { executionId ->
                DownloadWorkerExecutionOwners.release(healthyId, executionId)
            }
            DownloadExecutionRecovery.reconcile(context, db)
        }
    }

    @Test
    fun staleRecoveryOwnedHardSubDoesNotConsumeProductionHardSubAdmission() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staleId = insertHardSubDownload(
            historyId = 1001L,
            status = DownloadRepository.Status.Active,
            executionId = "stale-hard-E1",
        )
        val healthyId = insertHardSubDownload(
            historyId = 1002L,
            status = DownloadRepository.Status.Queued,
        )
        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context,
                requireNotNull(db.downloadDao.getNullableDownloadById(staleId)),
            )
        )

        try {
            val admission = admitThroughProductionPath(
                items = listOf(requireNotNull(db.downloadDao.getNullableDownloadById(healthyId))),
                concurrentDownloadLimit = 1,
            )

            assertTrue(admission.ownership.recoveryOwnedIds.contains(staleId))
            assertTrue(admission.ownership.liveHardSubIds.isEmpty())
            assertTrue(admission.selectedCandidates.any { it.id == healthyId })
            assertTrue(admission.claimedItems.any { it.id == healthyId })
        } finally {
            DownloadWorkerExecutionOwners.ownerOf(healthyId)?.let { executionId ->
                DownloadWorkerExecutionOwners.release(healthyId, executionId)
            }
            DownloadExecutionRecovery.reconcile(context, db)
        }
    }

    @Test
    fun staleRecoveryOwnedRowDoesNotMasqueradeAsLivePriorityExecution() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staleId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = "stale-priority-E1",
            )
        )
        val priorityId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                playlistURL = "",
                executionId = "",
            )
        )
        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context,
                requireNotNull(db.downloadDao.getNullableDownloadById(staleId)),
            )
        )

        try {
            val admission = admitThroughProductionPath(
                items = listOf(requireNotNull(db.downloadDao.getNullableDownloadById(priorityId))),
                priorityItemIds = listOf(priorityId),
                concurrentDownloadLimit = 1,
            )

            assertFalse(admission.ownership.liveExecutionIds.contains(staleId))
            assertTrue(admission.prioritySnapshot.selectableIds.contains(priorityId))
            assertTrue(admission.claimedItems.any { it.id == priorityId })
        } finally {
            DownloadWorkerExecutionOwners.ownerOf(priorityId)?.let { executionId ->
                DownloadWorkerExecutionOwners.release(priorityId, executionId)
            }
            DownloadExecutionRecovery.reconcile(context, db)
        }
    }

    @Test
    fun ownAttemptCannotBeClaimedWhileExactRecoveryDebtRemains() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staleId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = "stale-own-E1",
            )
        )
        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context,
                requireNotNull(db.downloadDao.getNullableDownloadById(staleId)),
            )
        )

        try {
            assertEquals(
                null,
                claimDownloadThroughProductionAdmission(
                    context = context,
                    dbManager = db,
                    candidate = requireNotNull(db.downloadDao.getNullableDownloadById(staleId)),
                    concurrentDownloadLimit = 2,
                ),
            )
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(staleId)?.status,
            )
            assertEquals(
                "stale-own-E1",
                db.downloadDao.getNullableDownloadById(staleId)?.executionId,
            )
            assertNull(DownloadWorkerExecutionOwners.ownerOf(staleId))
        } finally {
            DownloadExecutionRecovery.reconcile(context, db)
        }
    }

    @Test
    fun liveActiveWorkerExecutionSurvivesRecoveryReconciliation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "live-recovery-active-E2"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = executionId,
            )
        )
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)

        try {
            val recovery = DownloadExecutionRecovery.reconcile(context, db)

            assertTrue(recovery.completedCleanly)
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                executionId,
                db.downloadDao.getNullableDownloadById(downloadId)?.executionId,
            )
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, executionId))
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    @Test
    fun livePostProcessingWorkerExecutionSurvivesRecoveryReconciliation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "live-recovery-post-E2"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.PostProcessing.name,
                executionId = executionId,
            )
        )
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)

        try {
            val recovery = DownloadExecutionRecovery.reconcile(context, db)

            assertTrue(recovery.completedCleanly)
            assertEquals(
                DownloadRepository.Status.PostProcessing.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                executionId,
                db.downloadDao.getNullableDownloadById(downloadId)?.executionId,
            )
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, executionId))
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    @Test
    fun liveWorkerExecutionPreservesCurrentRecoveryJournalUntilOwnerRelease() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "live-recovery-journal-E2"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = executionId,
            )
        )
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context,
                requireNotNull(db.downloadDao.getNullableDownloadById(downloadId)),
            )
        )

        try {
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                executionId,
                db.downloadDao.getNullableDownloadById(downloadId)?.executionId,
            )
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, executionId))
            assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))

            // The retry owner may have been scheduled by the durable debt
            // pass. Remove only that test-owned retry coroutine before
            // releasing the worker token and making the next recovery pass
            // deterministic.
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals("", db.downloadDao.getNullableDownloadById(downloadId)?.executionId)
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    @Test
    fun staleRecoveryJournalCannotTouchLiveCurrentWorkerExecution() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staleExecutionId = "stale-recovery-journal-E1"
        val liveExecutionId = "live-recovery-after-journal-E2"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = staleExecutionId,
            )
        )
        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context,
                requireNotNull(db.downloadDao.getNullableDownloadById(downloadId)),
            )
        )
        db.downloadDao.updateRaw(
            requireNotNull(db.downloadDao.getNullableDownloadById(downloadId)).copy(
                status = DownloadRepository.Status.Active.name,
                executionId = liveExecutionId,
            )
        )
        DownloadWorkerExecutionOwners.claim(downloadId, liveExecutionId)

        try {
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                liveExecutionId,
                db.downloadDao.getNullableDownloadById(downloadId)?.executionId,
            )
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, liveExecutionId))
            assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))

            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerExecutionOwners.release(downloadId, liveExecutionId)
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals("", db.downloadDao.getNullableDownloadById(downloadId)?.executionId)
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerExecutionOwners.release(downloadId, liveExecutionId)
        }
    }

    @Test
    fun liveWorkerExecutionPreservesCurrentNativeMarkerDebt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "live-recovery-marker-E2"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = executionId,
            )
        )
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = YtdlpProcessIdentity.download(downloadId, executionId),
            state = "RUNNING",
            generationToken = "live-recovery-marker-${UUID.randomUUID()}",
        )

        try {
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                executionId,
                db.downloadDao.getNullableDownloadById(downloadId)?.executionId,
            )
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, executionId))
            assertTrue(marker.exists())

            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertFalse(marker.exists())
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
            marker.delete()
        }
    }

    @Test
    fun recoveryDiscoveryRaceDoesNotRequeueNewlyClaimedLiveExecution() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staleExecutionId = "discovery-race-E1"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = staleExecutionId,
            )
        )
        val discovered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        DownloadExecutionRecovery.beforeCandidateRecoveryLeaseForTesting = { candidateId ->
            if (candidateId == downloadId) {
                discovered.countDown()
                check(proceed.await(5L, TimeUnit.SECONDS)) {
                    "Timed out waiting to release recovery discovery race"
                }
            }
        }
        val recovery = async(Dispatchers.IO) {
            DownloadExecutionRecovery.reconcile(context, db)
        }

        var claimed: DownloadItem? = null
        try {
            assertTrue(discovered.await(5L, TimeUnit.SECONDS))
            val queuedCandidate = requireNotNull(
                db.downloadDao.getNullableDownloadById(downloadId),
            ).copy(
                status = DownloadRepository.Status.Queued.name,
                executionId = "",
            )
            db.downloadDao.updateRaw(queuedCandidate)

            val claimedItem = requireNotNull(
                claimDownloadThroughProductionAdmission(
                    context = context,
                    dbManager = db,
                    candidate = queuedCandidate,
                    concurrentDownloadLimit = 1,
                )
            )
            claimed = claimedItem
            assertNotEquals(staleExecutionId, claimedItem.executionId)
            proceed.countDown()
            recovery.await()

            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                claimedItem.executionId,
                db.downloadDao.getNullableDownloadById(downloadId)?.executionId,
            )
            assertTrue(
                DownloadWorkerExecutionOwners.isOwnedBy(
                    downloadId,
                    claimedItem.executionId,
                )
            )

            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerExecutionOwners.release(downloadId, claimedItem.executionId)
            DownloadExecutionRecovery.reconcile(context, db)
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            proceed.countDown()
            DownloadExecutionRecovery.beforeCandidateRecoveryLeaseForTesting = null
            recovery.cancel()
            claimed?.let { item ->
                DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
                DownloadWorkerExecutionOwners.release(downloadId, item.executionId)
            }
        }
    }

    @Test
    fun liveWorkerExecutionDoesNotBlockUnrelatedAbandonedSiblingRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val liveExecutionId = "live-sibling-A-E2"
        val abandonedExecutionId = "abandoned-sibling-B-E2"
        val liveId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = liveExecutionId,
            )
        )
        val abandonedId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = abandonedExecutionId,
            )
        )
        DownloadWorkerExecutionOwners.claim(liveId, liveExecutionId)

        try {
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(liveId)?.status,
            )
            assertEquals(
                liveExecutionId,
                db.downloadDao.getNullableDownloadById(liveId)?.executionId,
            )
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(liveId, liveExecutionId))
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(abandonedId)?.status,
            )
            assertEquals("", db.downloadDao.getNullableDownloadById(abandonedId)?.executionId)
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(liveId)
            DownloadWorkerExecutionOwners.release(liveId, liveExecutionId)
        }
    }

    @Test
    fun liveCommittedHistoryRowIsNotFinalizedAsAbandoned() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val executionId = "live-committed-history-E2"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                status = DownloadRepository.Status.Active.name,
                executionId = executionId,
            )
        )
        db.historyDao.updateRaw(db.historyDao.getItem(historyId).copy(downloadId = downloadId))
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)

        try {
            DownloadExecutionRecovery.reconcile(context, db)

            assertNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                executionId,
                db.downloadDao.getNullableDownloadById(downloadId)?.executionId,
            )
            assertEquals(downloadId, db.historyDao.getItem(historyId).downloadId)
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, executionId))
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    @Test
    fun productionClaimCannotCreateE3WhileLiveE2RowRemainsActive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "live-claim-fence-E2"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = executionId,
            )
        )
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)

        try {
            val current = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertNull(
                claimDownloadThroughProductionAdmission(
                    context = context,
                    dbManager = db,
                    candidate = current.copy(
                        status = DownloadRepository.Status.Queued.name,
                        executionId = "",
                    ),
                    // Leave capacity for the CAS to demonstrate that the
                    // durable Active/E2 row itself fences a fresh claim.
                    concurrentDownloadLimit = 2,
                )
            )
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                executionId,
                db.downloadDao.getNullableDownloadById(downloadId)?.executionId,
            )
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, executionId))
        } finally {
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    @Test
    fun genuinelyLiveActiveExecutionConsumesProductionConcurrencySlot() = runBlocking {
        val liveId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = "live-E1",
            )
        )
        val queuedId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                playlistURL = "",
                executionId = "",
            )
        )
        DownloadWorkerExecutionOwners.claim(liveId, "live-E1")

        try {
            val admission = admitThroughProductionPath(
                items = listOf(requireNotNull(db.downloadDao.getNullableDownloadById(queuedId))),
                concurrentDownloadLimit = 1,
            )

            assertTrue(admission.ownership.liveCapacityIds.contains(liveId))
            assertTrue(admission.selectedCandidates.isEmpty())
            assertTrue(admission.claimedItems.isEmpty())
        } finally {
            DownloadWorkerExecutionOwners.release(liveId, "live-E1")
        }
    }

    @Test
    fun genuinelyLiveActiveHardSubBlocksSecondHardSub() = runBlocking {
        val liveId = insertHardSubDownload(
            historyId = 1101L,
            status = DownloadRepository.Status.Active,
            executionId = "live-hard-E1",
        )
        val queuedId = insertHardSubDownload(
            historyId = 1102L,
            status = DownloadRepository.Status.Queued,
        )
        DownloadWorkerExecutionOwners.claim(liveId, "live-hard-E1")

        try {
            val admission = admitThroughProductionPath(
                items = listOf(requireNotNull(db.downloadDao.getNullableDownloadById(queuedId))),
                concurrentDownloadLimit = 2,
            )

            assertTrue(admission.ownership.liveHardSubIds.contains(liveId))
            assertTrue(admission.selectedCandidates.isEmpty())
            assertTrue(admission.claimedItems.isEmpty())
        } finally {
            DownloadWorkerExecutionOwners.release(liveId, "live-hard-E1")
        }
    }

    @Test
    fun livePostProcessingHardSubKeepsPriorActiveOnlyHardSubPolicy() = runBlocking {
        val postProcessingId = insertHardSubDownload(
            historyId = 1201L,
            status = DownloadRepository.Status.PostProcessing,
            executionId = "live-post-hard-E1",
        )
        val queuedId = insertHardSubDownload(
            historyId = 1202L,
            status = DownloadRepository.Status.Queued,
        )
        DownloadWorkerExecutionOwners.claim(postProcessingId, "live-post-hard-E1")

        try {
            val admission = admitThroughProductionPath(
                items = listOf(requireNotNull(db.downloadDao.getNullableDownloadById(queuedId))),
                concurrentDownloadLimit = 1,
            )

            // The prior scheduler gated hard-sub work from live Active rows;
            // PostProcessing has already released that gate.
            assertTrue(admission.ownership.liveExecutionIds.contains(postProcessingId))
            assertTrue(admission.ownership.liveHardSubIds.isEmpty())
            assertTrue(admission.claimedItems.any { it.id == queuedId })
        } finally {
            DownloadWorkerExecutionOwners.release(postProcessingId, "live-post-hard-E1")
        }
    }

    @Test
    fun deferredStartupSnapshotDoesNotPermanentlyExcludeLegitimateE2() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staleId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = "stale-snapshot-E1",
            )
        )
        val queuedId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                playlistURL = "",
                executionId = "",
            )
        )
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = YtdlpProcessIdentity.download(staleId, "stale-snapshot-E1"),
            state = "RUNNING",
            generationToken = "stale-snapshot-generation-${UUID.randomUUID()}",
        )
        try {
            YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = marker.absolutePath
            val deferred = observeQueuedDownloadsAfterRecovery(
                context = context,
                dbManager = db,
                priorityItemIds = emptyList(),
                currentTimeMillis = System.currentTimeMillis() + 10_000L,
            )
            deferred.queuedItems.first()
            assertTrue(deferred.recovery.deferredDownloadIds.contains(staleId))

            YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(staleId)
            DownloadExecutionRecovery.reconcile(context, db)
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(staleId)?.status,
            )

            val e2Admission = admitThroughProductionPath(
                items = listOf(
                    requireNotNull(db.downloadDao.getNullableDownloadById(staleId)),
                    requireNotNull(db.downloadDao.getNullableDownloadById(queuedId)),
                ),
                concurrentDownloadLimit = 1,
            )
            val e2 = e2Admission.claimedItems.single { it.id == staleId }
            assertTrue(e2.executionId.isNotBlank())
            assertNotEquals("stale-snapshot-E1", e2.executionId)

            val whileLive = admitThroughProductionPath(
                items = listOf(requireNotNull(db.downloadDao.getNullableDownloadById(queuedId))),
                concurrentDownloadLimit = 1,
            )
            assertTrue(whileLive.ownership.liveCapacityIds.contains(staleId))
            assertTrue(whileLive.claimedItems.isEmpty())
            DownloadWorkerExecutionOwners.release(staleId, e2.executionId)
        } finally {
            YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(staleId)
            marker.delete()
            DownloadWorkerExecutionOwners.ownerOf(staleId)?.let { executionId ->
                DownloadWorkerExecutionOwners.release(staleId, executionId)
            }
        }
    }

    @Test
    fun recoveryOnlyCarrierKeepsLiveOwnerUntilSafeRequeue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staleId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = "recovery-only-E1",
            )
        )
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = YtdlpProcessIdentity.download(staleId, "recovery-only-E1"),
            state = "RUNNING",
            generationToken = "recovery-only-generation-${UUID.randomUUID()}",
        )
        try {
            YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = marker.absolutePath
            val admission = observeQueuedDownloadsAfterRecovery(
                context = context,
                dbManager = db,
                priorityItemIds = emptyList(),
                currentTimeMillis = System.currentTimeMillis() + 10_000L,
            )
            admission.queuedItems.first()
            assertTrue(admission.recovery.deferredDownloadIds.contains(staleId))
            awaitRecoveryJobActive(staleId)
            assertTrue(YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(staleId, "recovery-only-E1"))
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(staleId)?.status,
            )

            YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(staleId)
            DownloadExecutionRecovery.reconcile(context, db)
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(staleId)?.status,
            )
            assertFalse(YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(staleId, "recovery-only-E1"))
        } finally {
            YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(staleId)
            marker.delete()
        }
    }

    private suspend fun admitThroughProductionPath(
        items: List<DownloadItem>,
        priorityItemIds: List<Long> = emptyList(),
        concurrentDownloadLimit: Int,
    ): DownloadWorkerAdmissionResult {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return admitQueuedDownloadsThroughProductionPath(
            dbManager = db,
            items = items,
            priorityItemIds = priorityItemIds,
            currentTimeMillis = System.currentTimeMillis() + 10_000L,
            concurrentDownloadLimit = concurrentDownloadLimit,
            continueAfterPriorityItems = true,
            claim = { candidate ->
                claimDownloadThroughProductionAdmission(
                    context = context,
                    dbManager = db,
                    candidate = candidate,
                    concurrentDownloadLimit = concurrentDownloadLimit,
                )
            },
        )
    }

    private suspend fun awaitRecoveryJobActive(downloadId: Long) {
        withTimeout(2_000L) {
            while (!DownloadExecutionRecovery.isRecoveryJobActiveForTesting(downloadId)) {
                delay(10L)
            }
        }
    }

    private fun hardSubDownload(historyId: Long): DownloadItem =
        download().copy(
            playlistURL = HistoryRedownloadMarker.quality(historyId, 1080),
        )

    private suspend fun insertHardSubDownload(
        historyId: Long,
        status: DownloadRepository.Status,
        executionId: String = "",
    ): Long {
        val repository = LowQualityRedownloadRepository(db)
        val operation = repository.createOrReconnect()
        db.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = historyId,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name,
            )
        )
        val id = repository.linkDownloadAtomically(
            operationId = operation.operationId,
            historyId = historyId,
            downloadItem = hardSubDownload(historyId).copy(
                operationId = operation.operationId,
                status = DownloadRepository.Status.Queued.name,
                executionId = executionId,
            ),
        )
        val linkedId = requireNotNull(id)
        if (status != DownloadRepository.Status.Queued) {
            db.downloadDao.setStatus(linkedId, status.name)
        }
        return linkedId
    }

    @Test
    fun applicationRecoveryRequeuesAbandonedOrdinaryRowWithoutDownloadWorker() = runBlocking {
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "dead-E1"))

        DownloadExecutionRecovery.reconcile(ApplicationProvider.getApplicationContext(), db)

        assertEquals(DownloadRepository.Status.Queued.name, db.downloadDao.getNullableDownloadById(downloadId)?.status)
    }

    @Test
    fun userStopQuiescenceSuccessAcknowledgesAndClearsExactRecoveryCarrier() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(executionId = "stop-E1")
        )
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))

        assertTrue(DownloadExecutionRecovery.recordPending(context, item))
        assertTrue(
            DownloadExecutionRecovery.quiesceAfterDurableStop(
                context = context,
                downloadId = downloadId,
                executionId = "stop-E1",
                dbManager = db,
            )
        )
        assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
    }

    @Test
    fun userStopQuiescenceFalseRetainsE1CarrierWhenE2OwnsNativeDomain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(executionId = "stop-E1")
        )
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(DownloadExecutionRecovery.recordPending(context, item))
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, "stop-E2"))

        assertFalse(
            DownloadExecutionRecovery.quiesceAfterDurableStop(
                context = context,
                downloadId = downloadId,
                executionId = "stop-E1",
                dbManager = db,
            )
        )
        assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        assertEquals("stop-E2", DownloadWorkerProcessOwners.ownerOf(downloadId))
        DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
    }

    @Test
    fun userStopQuiescenceExceptionRetainsE1CarrierAndProcessOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(executionId = "stop-E1")
        )
        val processId = YtdlpProcessIdentity.download(downloadId, "stop-E1")
        val process = NeverQuiescingProcess()
        DownloadWorkerExecutionOwners.claim(downloadId, "stop-E1")
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, "stop-E1"))
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))

        try {
            assertTrue(DownloadExecutionRecovery.recordPending(context, item))
            assertFalse(
                DownloadExecutionRecovery.quiesceAfterDurableStop(
                    context = context,
                    downloadId = downloadId,
                    executionId = "stop-E1",
                    dbManager = db,
                )
            )
            assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            assertEquals("stop-E1", DownloadWorkerProcessOwners.ownerOf(downloadId))
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, "stop-E1")
            DownloadWorkerExecutionOwners.release(downloadId, "stop-E1")
        }
    }

    @Test
    fun pausedExecutionRecoveryCarrierRemainsDiscoverableUntilQuiescenceIsProven() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                executionId = "pause-E1",
                status = DownloadRepository.Status.Paused.name,
            )
        )
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))

        assertTrue(DownloadExecutionRecovery.recordPending(context, item))
        assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        // The same durable carrier is what resume admission consults; a
        // Paused row alone is not evidence that E1 has surrendered authority.
        assertEquals(DownloadRepository.Status.Paused.name, item.status)
    }

    @Test
    fun notificationCancelTrueCompletesAfterNativeQuiescence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "receiver-cancel-true-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val receiver = CancelDownloadNotificationReceiver(db)

        sendReceiverAndAwait(
            context = context,
            receiver = receiver,
            intent = receiverIntent(
                action = "cancel-true",
                downloadId = downloadId,
                executionId = executionId,
            ),
        ) {
            db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                DownloadRepository.Status.Cancelled.name &&
                !DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId)
        }

        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
    }

    @Test
    fun notificationCancelFalseKeepsPendingRecoveryInsteadOfCompletingNormally() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "receiver-cancel-false-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, "receiver-cancel-false-E2"))
        val receiver = CancelDownloadNotificationReceiver(db)

        try {
            sendReceiverAndAwait(
                context = context,
                receiver = receiver,
                intent = receiverIntent(
                    action = "cancel-false",
                    downloadId = downloadId,
                    executionId = executionId,
                ),
            ) {
                db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                    DownloadRepository.Status.Cancelled.name &&
                    DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId)
            }

            assertEquals(
                "receiver-cancel-false-E2",
                DownloadWorkerProcessOwners.ownerOf(downloadId),
            )
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerProcessOwners.release(downloadId, "receiver-cancel-false-E2")
        }
    }

    @Test
    fun notificationCancelExceptionKeepsPendingRecoveryInsteadOfCompletingNormally() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "receiver-cancel-exception-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val processId = YtdlpProcessIdentity.download(downloadId, executionId)
        val process = NeverQuiescingProcess()
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, executionId))
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        val receiver = CancelDownloadNotificationReceiver(db)

        try {
            sendReceiverAndAwait(
                context = context,
                receiver = receiver,
                intent = receiverIntent(
                    action = "cancel-exception",
                    downloadId = downloadId,
                    executionId = executionId,
                ),
            ) {
                db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                    DownloadRepository.Status.Cancelled.name &&
                    DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId)
            }

            assertEquals(executionId, DownloadWorkerProcessOwners.ownerOf(downloadId))
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, executionId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    @Test
    fun notificationCancelSemanticWriteExceptionRetainsUserDispositionUntilRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "receiver-cancel-write-exception-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        DownloadRepository.userStopWriteFailureForTesting = { targetId, status ->
            if (targetId == downloadId && status == DownloadRepository.Status.Cancelled) {
                IllegalStateException("injected Cancelled write failure")
            } else {
                null
            }
        }

        try {
            sendReceiverAndAwait(
                context = context,
                receiver = CancelDownloadNotificationReceiver(db),
                intent = receiverIntent(
                    action = "cancel-write-exception",
                    downloadId = downloadId,
                    executionId = executionId,
                ),
            ) {
                db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                    DownloadRepository.Status.Active.name &&
                    DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId) ==
                        DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL &&
                    DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId) ==
                        DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING
            }

            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
        }
    }

    @Test
    fun notificationCancelSemanticWriteNoOpRetainsUserDispositionUntilRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "receiver-cancel-write-noop-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        DownloadRepository.userStopWriteNoOpForTesting = { targetId, status ->
            targetId == downloadId && status == DownloadRepository.Status.Cancelled
        }

        try {
            sendReceiverAndAwait(
                context = context,
                receiver = CancelDownloadNotificationReceiver(db),
                intent = receiverIntent(
                    action = "cancel-write-noop",
                    downloadId = downloadId,
                    executionId = executionId,
                ),
            ) {
                db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                    DownloadRepository.Status.Active.name &&
                    DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId) ==
                        DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL &&
                    DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId) ==
                        DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING
            }

            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadRepository.userStopWriteNoOpForTesting = null
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            DownloadRepository.userStopWriteNoOpForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
        }
    }

    @Test
    fun viewModelCancelSemanticWriteExceptionInstallsSameProcessRecoveryOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val executionId = "viewmodel-cancel-write-exception-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        DownloadRepository.userStopWriteFailureForTesting = { targetId, status ->
            if (targetId == downloadId && status == DownloadRepository.Status.Cancelled) {
                IllegalStateException("injected ViewModel Cancelled write failure")
            } else {
                null
            }
        }
        val viewModel = DownloadViewModel(context, db, true)

        try {
            assertTrue(runCatching { viewModel.cancelDownload(downloadId) }.isFailure)
            awaitRecoveryJobActive(downloadId)
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId),
            )
            assertEquals(
                DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
                DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId),
            )

            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadExecutionRecovery.reconcile(context, db)
            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
        }
    }

    @Test
    fun notificationPauseSemanticWriteExceptionWithholdsResumeUntilRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "receiver-pause-write-exception-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val resumePublicationCount = AtomicInteger(0)
        PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = {
            resumePublicationCount.incrementAndGet()
        }
        DownloadRepository.userStopWriteFailureForTesting = { targetId, status ->
            if (targetId == downloadId && status == DownloadRepository.Status.Paused) {
                IllegalStateException("injected Paused write failure")
            } else {
                null
            }
        }

        try {
            sendReceiverAndAwait(
                context = context,
                receiver = PauseDownloadNotificationReceiver(db),
                intent = receiverIntent(
                    action = "pause-write-exception",
                    downloadId = downloadId,
                    executionId = executionId,
                ),
            ) {
                db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                    DownloadRepository.Status.Active.name &&
                    DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId) ==
                        DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE &&
                    DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId) ==
                        DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING
            }

            assertEquals(0, resumePublicationCount.get())
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Paused.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            assertEquals(0, resumePublicationCount.get())
        } finally {
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = null
        }
    }

    @Test
    fun notificationPauseSemanticWriteNoOpWithholdsResumeUntilRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "receiver-pause-write-noop-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val resumePublicationCount = AtomicInteger(0)
        PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = {
            resumePublicationCount.incrementAndGet()
        }
        DownloadRepository.userStopWriteNoOpForTesting = { targetId, status ->
            targetId == downloadId && status == DownloadRepository.Status.Paused
        }

        try {
            sendReceiverAndAwait(
                context = context,
                receiver = PauseDownloadNotificationReceiver(db),
                intent = receiverIntent(
                    action = "pause-write-noop",
                    downloadId = downloadId,
                    executionId = executionId,
                ),
            ) {
                db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                    DownloadRepository.Status.Active.name &&
                    DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId) ==
                        DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE &&
                    DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId) ==
                        DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING
            }

            assertEquals(0, resumePublicationCount.get())
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadRepository.userStopWriteNoOpForTesting = null
            DownloadExecutionRecovery.reconcile(context, db)
            assertEquals(
                DownloadRepository.Status.Paused.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            DownloadRepository.userStopWriteNoOpForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = null
        }
    }

    @Test
    fun viewModelPauseSemanticWriteExceptionInstallsSameProcessRecoveryOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val executionId = "viewmodel-pause-write-exception-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        DownloadRepository.userStopWriteFailureForTesting = { targetId, status ->
            if (targetId == downloadId && status == DownloadRepository.Status.Paused) {
                IllegalStateException("injected ViewModel Paused write failure")
            } else {
                null
            }
        }
        val viewModel = DownloadViewModel(context, db, true)

        try {
            assertTrue(runCatching { viewModel.pauseDownload(downloadId) }.isFailure)
            awaitRecoveryJobActive(downloadId)
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId),
            )
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadExecutionRecovery.reconcile(context, db)
            assertEquals(
                DownloadRepository.Status.Paused.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
        }
    }

    @Test
    fun incognitoCancelSemanticWriteFailureKeepsPrimaryRecoveryCarrier() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val preferences = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        val previousIncognito = preferences.getBoolean("incognito", false)
        val executionId = "incognito-write-failure-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        preferences.edit().putBoolean("incognito", true).commit()
        DownloadRepository.userStopWriteFailureForTesting = { targetId, status ->
            if (targetId == downloadId && status == DownloadRepository.Status.Cancelled) {
                IllegalStateException("injected incognito Cancelled write failure")
            } else {
                null
            }
        }
        val viewModel = DownloadViewModel(context, db, true)

        try {
            val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertTrue(
                runCatching {
                    viewModel.updateDownload(item.copy(status = DownloadRepository.Status.Cancelled.name))
                }.isFailure
            )
            awaitRecoveryJobActive(downloadId)
            assertNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId),
            )
        } finally {
            preferences.edit().putBoolean("incognito", previousIncognito).commit()
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
        }
    }

    @Test
    fun pauseAllSemanticWriteFailureLeavesSiblingIndependent() = runBlocking(Dispatchers.Main) {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val failingId = db.downloadDao.insertRaw(
            download().copy(executionId = "pause-all-write-failure-E1")
        )
        val siblingId = db.downloadDao.insertRaw(
            download().copy(executionId = "pause-all-write-sibling-E1")
        )
        DownloadRepository.userStopWriteFailureForTesting = { targetId, status ->
            if (targetId == failingId && status == DownloadRepository.Status.Paused) {
                IllegalStateException("injected Pause All write failure")
            } else {
                null
            }
        }
        val viewModel = DownloadViewModel(context, db, true)

        try {
            viewModel.pauseAllDownloads()
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(failingId)?.status,
            )
            assertEquals(
                DownloadRepository.Status.Paused.name,
                db.downloadDao.getNullableDownloadById(siblingId)?.status,
            )
            assertEquals(
                DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                DownloadExecutionRecovery.pendingDispositionForExecution(context, failingId),
            )
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(siblingId))
        } finally {
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(failingId)
        }
    }

    @Test
    fun explicitCancelSupersedesPauseWithoutAllowingPauseDowngrade() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(executionId = "disposition-replacement-E1")
        )
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))

        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context = context,
                item = item,
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
            )
        )
        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context = context,
                item = item,
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
            )
        )
        assertFalse(
            DownloadExecutionRecovery.recordPending(
                context = context,
                item = item,
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
            )
        )
        assertEquals(
            DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
            DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId),
        )
        assertEquals(
            DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
            DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId),
        )
    }

    @Test
    fun legacyGenericRecoveryCarrierRetainsGenericRequeueSemantics() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "legacy-generic-carrier-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        context.getSharedPreferences("download-execution-recovery", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(downloadId.toString(), executionId)
            .putBoolean("$downloadId:native-quiescence", false)
            .commit()

        DownloadExecutionRecovery.reconcile(context, db)

        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
        assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
    }

    @Test
    fun provenUserStopCarrierForDeletedRowUsesOperationSpecificClear() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "deleted-user-stop-carrier-E1"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                executionId = executionId,
                status = DownloadRepository.Status.Cancelled.name,
            )
        )
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))

        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context = context,
                item = item,
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
            )
        )
        assertTrue(
            DownloadExecutionRecovery.markUserStopSemanticCommitted(
                context = context,
                downloadId = downloadId,
                executionId = executionId,
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
            )
        )
        assertTrue(
            DownloadExecutionRecovery.markNativeQuiescent(
                context = context,
                downloadId = downloadId,
                executionId = executionId,
                exactGenerationProof = true,
            )
        )
        db.downloadDao.delete(downloadId)

        DownloadExecutionRecovery.reconcile(context, db)

        assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
    }

    @Test
    fun notificationPauseTruePublishesResumeOnlyAfterNativeQuiescence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "receiver-pause-true-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val resumePublicationCount = AtomicInteger(0)
        PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = {
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            resumePublicationCount.incrementAndGet()
        }
        val receiver = PauseDownloadNotificationReceiver(db)

        try {
            sendReceiverAndAwait(
                context = context,
                receiver = receiver,
                intent = receiverIntent(
                    action = "pause-true",
                    downloadId = downloadId,
                    executionId = executionId,
                ),
            ) {
                db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                    DownloadRepository.Status.Paused.name &&
                    !DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId) &&
                    resumePublicationCount.get() == 1
            }

            assertEquals(1, resumePublicationCount.get())
        } finally {
            PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = null
        }

        assertEquals(
            DownloadRepository.Status.Paused.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
    }

    @Test
    fun notificationPauseFalseWithholdsResumePublication() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "receiver-pause-false-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val resumePublicationCount = AtomicInteger(0)
        PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = {
            resumePublicationCount.incrementAndGet()
        }
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, "receiver-pause-false-E2"))
        val receiver = PauseDownloadNotificationReceiver(db)

        try {
            sendReceiverAndAwait(
                context = context,
                receiver = receiver,
                intent = receiverIntent(
                    action = "pause-false",
                    downloadId = downloadId,
                    executionId = executionId,
                ),
            ) {
                db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                    DownloadRepository.Status.Paused.name &&
                    DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId)
            }

            assertEquals(
                DownloadRepository.Status.Paused.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(0, resumePublicationCount.get())
        } finally {
            PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerProcessOwners.release(downloadId, "receiver-pause-false-E2")
        }
    }

    @Test
    fun notificationPauseExceptionWithholdsResumePublication() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "receiver-pause-exception-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val resumePublicationCount = AtomicInteger(0)
        PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = {
            resumePublicationCount.incrementAndGet()
        }
        val processId = YtdlpProcessIdentity.download(downloadId, executionId)
        val process = NeverQuiescingProcess()
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, executionId))
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        val receiver = PauseDownloadNotificationReceiver(db)

        try {
            sendReceiverAndAwait(
                context = context,
                receiver = receiver,
                intent = receiverIntent(
                    action = "pause-exception",
                    downloadId = downloadId,
                    executionId = executionId,
                ),
            ) {
                db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                    DownloadRepository.Status.Paused.name &&
                    DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId)
            }

            assertEquals(executionId, DownloadWorkerProcessOwners.ownerOf(downloadId))
            assertEquals(0, resumePublicationCount.get())
        } finally {
            PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, executionId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    @Test
    fun launchedReceiverBodyFailureFinishesPendingResultExactlyOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(executionId = "receiver-body-failure-E1")
        )
        val finishCount = AtomicInteger(0)
        CancelDownloadNotificationReceiver.beforeAsyncBodyForTesting = {
            throw IllegalStateException("injected receiver body failure")
        }
        CancelDownloadNotificationReceiver.finishObserverForTesting = {
            finishCount.incrementAndGet()
        }

        try {
            sendReceiverAndAwait(
                context = context,
                receiver = CancelDownloadNotificationReceiver(db),
                intent = receiverIntent(
                    action = "body-failure",
                    downloadId = downloadId,
                    executionId = "receiver-body-failure-E1",
                ),
            ) { finishCount.get() == 1 }

            assertEquals(1, finishCount.get())
        } finally {
            CancelDownloadNotificationReceiver.beforeAsyncBodyForTesting = null
            CancelDownloadNotificationReceiver.finishObserverForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
        }
    }

    @Test
    fun liveWorkerCancelFirstSemanticWriteExceptionRevokesE1BeforeNativeQuiescence() =
        runBlocking {
            exerciseLiveWorkerFirstWriteFailure(
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                expectedStatus = DownloadRepository.Status.Cancelled,
                noOp = false,
            )
        }

    @Test
    fun liveWorkerCancelFirstSemanticWriteNoOpRevokesE1BeforeNativeQuiescence() =
        runBlocking {
            exerciseLiveWorkerFirstWriteFailure(
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                expectedStatus = DownloadRepository.Status.Cancelled,
                noOp = true,
            )
        }

    @Test
    fun liveWorkerPauseFirstSemanticWriteExceptionRevokesE1BeforeNativeQuiescence() =
        runBlocking {
            exerciseLiveWorkerFirstWriteFailure(
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                expectedStatus = DownloadRepository.Status.Paused,
                noOp = false,
            )
        }

    @Test
    fun liveWorkerPauseFirstSemanticWriteNoOpRevokesE1BeforeNativeQuiescence() =
        runBlocking {
            exerciseLiveWorkerFirstWriteFailure(
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                expectedStatus = DownloadRepository.Status.Paused,
                noOp = true,
            )
        }

    @Test
    fun liveWorkerNoCacheMediaScanDoesNotRunAfterCancelFirstWriteException() = runBlocking {
        exerciseLiveWorkerEffectBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
            effect = WorkerEffectBoundary.NO_CACHE_MEDIA,
            noOp = false,
        )
    }

    @Test
    fun liveWorkerNoCacheMediaScanDoesNotRunAfterCancelFirstWriteNoOp() = runBlocking {
        exerciseLiveWorkerEffectBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
            effect = WorkerEffectBoundary.NO_CACHE_MEDIA,
            noOp = true,
        )
    }

    @Test
    fun liveWorkerNoCacheMediaScanDoesNotRunAfterPauseFirstWriteException() = runBlocking {
        exerciseLiveWorkerEffectBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
            effect = WorkerEffectBoundary.NO_CACHE_MEDIA,
            noOp = false,
        )
    }

    @Test
    fun liveWorkerNoCacheMediaScanDoesNotRunAfterPauseFirstWriteNoOp() = runBlocking {
        exerciseLiveWorkerEffectBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
            effect = WorkerEffectBoundary.NO_CACHE_MEDIA,
            noOp = true,
        )
    }

    @Test
    fun liveWorkerTerminalProgressDoesNotPublishAfterCancelFirstWriteException() = runBlocking {
        exerciseLiveWorkerEffectBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
            effect = WorkerEffectBoundary.TERMINAL_PROGRESS,
            noOp = false,
        )
    }

    @Test
    fun liveWorkerTerminalProgressDoesNotPublishAfterCancelFirstWriteNoOp() = runBlocking {
        exerciseLiveWorkerEffectBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
            effect = WorkerEffectBoundary.TERMINAL_PROGRESS,
            noOp = true,
        )
    }

    @Test
    fun liveWorkerTerminalProgressDoesNotPublishAfterPauseFirstWriteException() = runBlocking {
        exerciseLiveWorkerEffectBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
            effect = WorkerEffectBoundary.TERMINAL_PROGRESS,
            noOp = false,
        )
    }

    @Test
    fun liveWorkerTerminalProgressDoesNotPublishAfterPauseFirstWriteNoOp() = runBlocking {
        exerciseLiveWorkerEffectBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
            effect = WorkerEffectBoundary.TERMINAL_PROGRESS,
            noOp = true,
        )
    }

    @Test
    fun liveWorkerFailureTerminalWriteDoesNotOverrideCancelFirstWriteException() = runBlocking {
        exerciseLiveWorkerFailureTerminalBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
            noOp = false,
        )
    }

    @Test
    fun liveWorkerFailureTerminalWriteDoesNotOverrideCancelFirstWriteNoOp() = runBlocking {
        exerciseLiveWorkerFailureTerminalBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
            noOp = true,
        )
    }

    @Test
    fun liveWorkerFailureTerminalWriteDoesNotOverridePauseFirstWriteException() = runBlocking {
        exerciseLiveWorkerFailureTerminalBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
            noOp = false,
        )
    }

    @Test
    fun liveWorkerFailureTerminalWriteDoesNotOverridePauseFirstWriteNoOp() = runBlocking {
        exerciseLiveWorkerFailureTerminalBoundary(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
            noOp = true,
        )
    }

    @Test
    fun terminalErrorWinsBeforeLateCancelDoesNotCreateRecoveryCarrier() = runBlocking {
        exerciseTerminalErrorWinsBeforeLateUserStop(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
        )
    }

    @Test
    fun terminalErrorWinsBeforeLatePauseDoesNotCreateRecoveryCarrier() = runBlocking {
        exerciseTerminalErrorWinsBeforeLateUserStop(
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
        )
    }

    @Test
    fun userStopBeforeHistoryRefusalTerminalWritePreservesHistoryProof() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val executionId = "history-refusal-stop-race-E1"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                executionId = executionId,
            )
        )
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        val refusal = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        try {
            assertTrue(
                DownloadExecutionRecovery.recordPending(
                    context = context,
                    item = item,
                    disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                    phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
                )
            )
            val terminalResult = runCatching {
                persistHistoryReplacementTerminalStateWithOwnedExecution(
                    context = context,
                    dbManager = db,
                    downloadItem = item.copy(
                        status = DownloadRepository.Status.Error.name,
                        lastIssueCode = refusal.code.name,
                        lastIssueStage = refusal.stage.name,
                    ),
                    issue = refusal,
                    preserveRefusalIssue = refusal,
                    persistDownload = {
                        error("ordinary Error must not win a durable user stop")
                    },
                    transitionLinkedDownload = {},
                )
            }
            assertTrue(terminalResult.isFailure)
            assertTrue(terminalResult.exceptionOrNull() is CancellationException)
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            val barrier = db.historyReplacementBarrierDao.getByDownloadId(downloadId)
            assertNotNull(barrier)
            assertEquals(refusal.code.name, barrier?.issueCode)
            assertEquals(refusal.stage.name, barrier?.issueStage)
        } finally {
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    @Test
    fun ownedNoCacheMediaScanPublishesWithExactLiveWorkerAuthority() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "owned-no-cache-control-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val scanCount = AtomicInteger(0)
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        DownloadWorkerEffectTestHooks.beforeNoCacheMediaScanForTesting = {
            scanCount.incrementAndGet()
        }

        try {
            publishNoCacheMediaWithOwnedExecution(
                context = context,
                dbManager = db,
                downloadItem = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId)),
                finalPaths = emptyList(),
                eventBus = EventBus(),
            )
            assertEquals(1, scanCount.get())
        } finally {
            DownloadWorkerEffectTestHooks.beforeNoCacheMediaScanForTesting = null
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    @Test
    fun terminalProgressPublishesWithExactLiveWorkerAuthority() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "owned-terminal-progress-control-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val progressCount = AtomicInteger(0)
        val eventBus = EventBus()
        val subscriber = WorkerProgressSubscriber(downloadId, "terminal-summary", progressCount)
        eventBus.register(subscriber)
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)

        try {
            publishTerminalWorkerProgressWithOwnedExecution(
                context = context,
                dbManager = db,
                downloadItem = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId)),
                eventBus = eventBus,
                summary = "terminal-summary",
            )
            assertEquals(1, progressCount.get())
        } finally {
            eventBus.unregister(subscriber)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    @Test
    fun committedHistoryWinsLateCancelAndFinalizesExactlyOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val executionId = "late-history-cancel-E1"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                executionId = executionId,
            )
        )
        db.historyDao.updateRaw(db.historyDao.getItem(historyId).copy(downloadId = downloadId))
        sendReceiverAndAwait(
            context = context,
            receiver = CancelDownloadNotificationReceiver(db),
            intent = receiverIntent(
                action = "late-history-cancel",
                downloadId = downloadId,
                executionId = executionId,
            ),
        ) {
            db.downloadDao.getNullableDownloadById(downloadId) == null &&
                !DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId)
        }
        DownloadExecutionRecovery.reconcile(context, db)

        assertNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertEquals(downloadId, db.historyDao.getItem(historyId).downloadId)
        assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
    }

    @Test
    fun committedHistoryWinsLatePauseWithoutPublishingResume() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val executionId = "late-history-pause-E1"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                executionId = executionId,
            )
        )
        db.historyDao.updateRaw(db.historyDao.getItem(historyId).copy(downloadId = downloadId))
        val resumePublicationCount = AtomicInteger(0)
        PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = {
            resumePublicationCount.incrementAndGet()
        }
        try {
            sendReceiverAndAwait(
                context = context,
                receiver = PauseDownloadNotificationReceiver(db),
                intent = receiverIntent(
                    action = "late-history-pause",
                    downloadId = downloadId,
                    executionId = executionId,
                ),
            ) {
                db.downloadDao.getNullableDownloadById(downloadId) == null &&
                    !DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId)
            }
            DownloadExecutionRecovery.reconcile(context, db)

            assertNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertEquals(downloadId, db.historyDao.getItem(historyId).downloadId)
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            assertEquals(0, resumePublicationCount.get())
        } finally {
            PauseDownloadNotificationReceiver.resumePublicationObserverForTesting = null
        }
    }

    @Test
    fun journalFirstLateStopIsSupersededByCommittedHistoryBeforeRecoveryClearsIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val executionId = "journal-first-history-E1"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                executionId = executionId,
            )
        )
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context = context,
                item = item,
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
            )
        )
        db.historyDao.updateRaw(db.historyDao.getItem(historyId).copy(downloadId = downloadId))

        assertEquals(
            DownloadExecutionRecovery.UserStopPreparation.COMMITTED_HISTORY_ALREADY_WON,
            DownloadExecutionRecovery.prepareUserStopBeforeNative(
                context = context,
                dbManager = db,
                downloadId = downloadId,
                executionId = executionId,
            ),
        )
        assertEquals(
            DownloadExecutionRecovery.RecoveryDisposition.HISTORY_FINALIZATION,
            DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId),
        )
        assertTrue(
            DownloadExecutionRecovery.convergeCommittedHistoryFinalization(
                context = context,
                dbManager = db,
                downloadId = downloadId,
                executionId = executionId,
            )
        )
        assertNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
    }

    @Test
    fun lateStopCarrierSurvivesHistoryCommitAndRowDeletionAcrossRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val executionId = "history-row-deleted-late-stop-E1"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                executionId = executionId,
            )
        )
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context = context,
                item = item,
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
            )
        )

        // Model process death after the History replacement commit and its
        // Download-row deletion, but before the late user-stop carrier is
        // consumed by recovery.
        db.historyDao.updateRaw(db.historyDao.getItem(historyId).copy(downloadId = downloadId))
        db.downloadDao.delete(downloadId)

        assertTrue(
            DownloadExecutionRecovery.convergeCommittedHistoryFinalization(
                context = context,
                dbManager = db,
                downloadId = downloadId,
                executionId = executionId,
            )
        )
        assertNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertEquals(downloadId, db.historyDao.getItem(historyId).downloadId)
        assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
    }

    @Test
    fun coldStartRecoveryFinalizesRowAbsentLateStopCarrier() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val executionId = "history-row-deleted-cold-start-E1"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                executionId = executionId,
            )
        )
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context = context,
                item = item,
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
            )
        )

        // Leave only the durable History result plus the late stop carrier,
        // matching the process-death window before startup reconciliation.
        db.historyDao.updateRaw(db.historyDao.getItem(historyId).copy(downloadId = downloadId))
        db.downloadDao.delete(downloadId)

        val result = DownloadExecutionRecovery.reconcile(context, db)

        assertTrue(result.completedCleanly)
        assertNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertEquals(downloadId, db.historyDao.getItem(historyId).downloadId)
        assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
    }

    @Test
    fun liveWorkerHistoryCompletionConsumesLateStopCarrierWithoutRevokingHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val executionId = "live-history-late-stop-E1"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                executionId = executionId,
            )
        )
        db.historyDao.updateRaw(db.historyDao.getItem(historyId).copy(downloadId = downloadId))
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        try {
            assertTrue(
                DownloadExecutionRecovery.recordPending(
                    context = context,
                    item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId)),
                    disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                    phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
                )
            )
            val current = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertFalse(hasDurableUserStopRevokedAuthority(context, db, current))
            assertTrue(
                DownloadExecutionRecovery.convergeCommittedHistoryFinalization(
                    context = context,
                    dbManager = db,
                    downloadId = downloadId,
                    executionId = executionId,
                )
            )
            assertNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    private suspend fun exerciseLiveWorkerFirstWriteFailure(
        disposition: DownloadExecutionRecovery.RecoveryDisposition,
        expectedStatus: DownloadRepository.Status,
        noOp: Boolean,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "live-worker-${disposition.name.lowercase()}-${if (noOp) "noop" else "exception"}-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val processId = YtdlpProcessIdentity.download(downloadId, executionId)
        val process = QuiescingProcess()
        val finishCount = AtomicInteger(0)
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, executionId))
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        val receiver: android.content.BroadcastReceiver = if (
            disposition == DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL
        ) {
            CancelDownloadNotificationReceiver(db)
        } else {
            PauseDownloadNotificationReceiver(db)
        }
        if (disposition == DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL) {
            CancelDownloadNotificationReceiver.finishObserverForTesting = {
                finishCount.incrementAndGet()
            }
        } else {
            PauseDownloadNotificationReceiver.finishObserverForTesting = {
                finishCount.incrementAndGet()
            }
        }
        if (noOp) {
            DownloadRepository.userStopWriteNoOpForTesting = { targetId, status ->
                targetId == downloadId && status == expectedStatus
            }
        } else {
            DownloadRepository.userStopWriteFailureForTesting = { targetId, status ->
                if (targetId == downloadId && status == expectedStatus) {
                    IllegalStateException("injected first ${expectedStatus.name} write failure")
                } else {
                    null
                }
            }
        }

        try {
            sendReceiverAndAwait(
                context = context,
                receiver = receiver,
                intent = receiverIntent(
                    action = "live-worker-stop-${UUID.randomUUID()}",
                    downloadId = downloadId,
                    executionId = executionId,
                ),
            ) {
                db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                    DownloadRepository.Status.Active.name &&
                    DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId) ==
                        disposition &&
                    DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId) ==
                        DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING &&
                    finishCount.get() == 1
            }

            val current = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertTrue(hasDurableUserStopRevokedAuthority(context, db, current))
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, executionId))
            assertEquals(0, process.destroyCount.get())
            assertTrue(process.isAlive)

            // Recovery discovery respects the positive live E1 owner; it does
            // not reinterpret the still-running row as generic requeue work.
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadExecutionRecovery.reconcile(context, db)
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, executionId))
            assertEquals(0, process.destroyCount.get())

            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadRepository.userStopWriteNoOpForTesting = null
            assertTrue(
                DownloadExecutionRecovery.convergeUserStopBeforeGenericCleanup(
                    context = context,
                    dbManager = db,
                    downloadId = downloadId,
                    executionId = executionId,
                )
            )
            assertEquals(
                expectedStatus.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            assertEquals(null, DownloadWorkerProcessOwners.ownerOf(downloadId))
            assertEquals(1, process.destroyCount.get())
        } finally {
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadRepository.userStopWriteNoOpForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, executionId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
            CancelDownloadNotificationReceiver.finishObserverForTesting = null
            PauseDownloadNotificationReceiver.finishObserverForTesting = null
        }
    }

    private enum class WorkerEffectBoundary {
        NO_CACHE_MEDIA,
        TERMINAL_PROGRESS,
    }

    private suspend fun exerciseLiveWorkerFailureTerminalBoundary(
        disposition: DownloadExecutionRecovery.RecoveryDisposition,
        noOp: Boolean,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expectedStatus = if (
            disposition == DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL
        ) {
            DownloadRepository.Status.Cancelled
        } else {
            DownloadRepository.Status.Paused
        }
        val executionId =
            "worker-failure-terminal-${disposition.name.lowercase()}-${if (noOp) "noop" else "exception"}-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val processId = YtdlpProcessIdentity.download(downloadId, executionId)
        val process = QuiescingProcess()
        val boundaryEntered = CountDownLatch(1)
        val boundaryRelease = CountDownLatch(1)
        val terminalMutationCount = AtomicInteger(0)
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, executionId))
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        if (noOp) {
            DownloadRepository.userStopWriteNoOpForTesting = { targetId, status ->
                targetId == downloadId && status == expectedStatus
            }
        } else {
            DownloadRepository.userStopWriteFailureForTesting = { targetId, status ->
                if (targetId == downloadId && status == expectedStatus) {
                    IllegalStateException("injected first ${expectedStatus.name} write failure")
                } else {
                    null
                }
            }
        }
        DownloadWorkerEffectTestHooks.beforeFailureTerminalPersistenceForTesting = {
            boundaryEntered.countDown()
            check(boundaryRelease.await(5_000L, TimeUnit.MILLISECONDS)) {
                "Timed out waiting to release failure terminal boundary"
            }
        }

        val issue = DownloadIssue.create(
            stage = DownloadIssueStage.DOWNLOAD,
            code = DownloadIssueCode.UNKNOWN,
            details = "deterministic failure terminal boundary",
        )
        try {
            coroutineScope {
                val terminalResult = async(Dispatchers.IO) {
                    runCatching {
                        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
                        persistHistoryReplacementTerminalStateWithOwnedExecution(
                            context = context,
                            dbManager = db,
                            downloadItem = item,
                            issue = issue,
                            persistDownload = {
                                terminalMutationCount.incrementAndGet()
                                check(
                                    db.downloadDao.updateIfExecutionOwnedAndRunning(
                                        item.copy(status = DownloadRepository.Status.Error.name),
                                        executionId,
                                    )
                                )
                            },
                            transitionLinkedDownload = {},
                        )
                    }
                }

                assertTrue(boundaryEntered.await(5_000L, TimeUnit.MILLISECONDS))
                sendReceiverAndAwait(
                    context = context,
                    receiver = if (
                        disposition == DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL
                    ) {
                        CancelDownloadNotificationReceiver(db)
                    } else {
                        PauseDownloadNotificationReceiver(db)
                    },
                    intent = receiverIntent(
                        action = "failure-terminal-stop-${UUID.randomUUID()}",
                        downloadId = downloadId,
                        executionId = executionId,
                    ),
                ) {
                    db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                        DownloadRepository.Status.Active.name &&
                        DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId) ==
                            disposition &&
                        DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId) ==
                            DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING
                }
                DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)

                boundaryRelease.countDown()
                val result = terminalResult.await()
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is CancellationException)
                assertEquals(0, terminalMutationCount.get())
                assertEquals(
                    DownloadRepository.Status.Active.name,
                    db.downloadDao.getNullableDownloadById(downloadId)?.status,
                )
                assertEquals(0, process.destroyCount.get())
                assertTrue(process.isAlive)

                DownloadRepository.userStopWriteFailureForTesting = null
                DownloadRepository.userStopWriteNoOpForTesting = null
                assertTrue(
                    DownloadExecutionRecovery.convergeUserStopBeforeGenericCleanup(
                        context = context,
                        dbManager = db,
                        downloadId = downloadId,
                        executionId = executionId,
                    )
                )
                assertEquals(
                    expectedStatus.name,
                    db.downloadDao.getNullableDownloadById(downloadId)?.status,
                )
                assertEquals(1, process.destroyCount.get())
                assertFalse(process.isAlive)
                assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            }
        } finally {
            boundaryRelease.countDown()
            DownloadWorkerEffectTestHooks.beforeFailureTerminalPersistenceForTesting = null
            DownloadRepository.userStopWriteFailureForTesting = null
            DownloadRepository.userStopWriteNoOpForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, executionId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    private suspend fun exerciseTerminalErrorWinsBeforeLateUserStop(
        disposition: DownloadExecutionRecovery.RecoveryDisposition,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "terminal-error-before-stop-${disposition.name.lowercase()}-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val processId = YtdlpProcessIdentity.download(downloadId, executionId)
        val process = QuiescingProcess()
        val publisherEntered = CountDownLatch(1)
        val publisherRelease = CountDownLatch(1)
        val finishCount = AtomicInteger(0)
        val receiver = if (
            disposition == DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL
        ) {
            CancelDownloadNotificationReceiver(db)
        } else {
            PauseDownloadNotificationReceiver(db)
        }
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, executionId))
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        if (disposition == DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL) {
            CancelDownloadNotificationReceiver.beforeStopLeaseForTesting = {
                publisherEntered.countDown()
                check(publisherRelease.await(5_000L, TimeUnit.MILLISECONDS))
            }
            CancelDownloadNotificationReceiver.finishObserverForTesting = {
                finishCount.incrementAndGet()
            }
        } else {
            PauseDownloadNotificationReceiver.beforeStopLeaseForTesting = {
                publisherEntered.countDown()
                check(publisherRelease.await(5_000L, TimeUnit.MILLISECONDS))
            }
            PauseDownloadNotificationReceiver.finishObserverForTesting = {
                finishCount.incrementAndGet()
            }
        }
        val issue = DownloadIssue.create(
            stage = DownloadIssueStage.DOWNLOAD,
            code = DownloadIssueCode.UNKNOWN,
            details = "deterministic terminal winner",
        )
        try {
            coroutineScope {
                val receiverJob = async(Dispatchers.IO) {
                    sendReceiverAndAwait(
                        context = context,
                        receiver = receiver,
                        intent = receiverIntent(
                            action = "terminal-error-late-stop-${UUID.randomUUID()}",
                            downloadId = downloadId,
                            executionId = executionId,
                        ),
                    ) { finishCount.get() == 1 }
                }
                assertTrue(publisherEntered.await(5_000L, TimeUnit.MILLISECONDS))

                val result = persistHistoryReplacementTerminalStateWithOwnedExecution(
                    context = context,
                    dbManager = db,
                    downloadItem = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId)),
                    issue = issue,
                    persistDownload = {
                        val current = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
                        check(
                            db.downloadDao.updateIfExecutionOwnedAndRunning(
                                current.copy(status = DownloadRepository.Status.Error.name),
                                executionId,
                            )
                        )
                    },
                    transitionLinkedDownload = {},
                )
                assertTrue(result is com.ireum.ytdl.work.HistoryReplacementPersistenceResult.Persisted)
                assertEquals(
                    DownloadRepository.Status.Error.name,
                    db.downloadDao.getNullableDownloadById(downloadId)?.status,
                )
                publisherRelease.countDown()
                receiverJob.await()
                assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
                assertEquals(0, process.destroyCount.get())
                assertTrue(process.isAlive)
            }
        } finally {
            publisherRelease.countDown()
            CancelDownloadNotificationReceiver.beforeStopLeaseForTesting = null
            CancelDownloadNotificationReceiver.finishObserverForTesting = null
            PauseDownloadNotificationReceiver.beforeStopLeaseForTesting = null
            PauseDownloadNotificationReceiver.finishObserverForTesting = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, executionId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    private suspend fun exerciseLiveWorkerEffectBoundary(
        disposition: DownloadExecutionRecovery.RecoveryDisposition,
        effect: WorkerEffectBoundary,
        noOp: Boolean,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expectedStatus = if (
            disposition == DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL
        ) {
            DownloadRepository.Status.Cancelled
        } else {
            DownloadRepository.Status.Paused
        }
        val executionId =
            "worker-effect-${disposition.name.lowercase()}-${effect.name.lowercase()}-${if (noOp) "noop" else "exception"}-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val processId = YtdlpProcessIdentity.download(downloadId, executionId)
        val process = QuiescingProcess()
        val boundaryEntered = CountDownLatch(1)
        val boundaryRelease = CountDownLatch(1)
        val mediaScanCount = AtomicInteger(0)
        val terminalProgressCount = AtomicInteger(0)
        val terminalPostCount = AtomicInteger(0)
        val eventBus = EventBus()
        val subscriber = WorkerProgressSubscriber(
            downloadId = downloadId,
            output = "terminal-summary",
            count = terminalProgressCount,
        )
        if (effect == WorkerEffectBoundary.TERMINAL_PROGRESS) {
            eventBus.register(subscriber)
        }
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, executionId))
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        if (effect == WorkerEffectBoundary.NO_CACHE_MEDIA) {
            DownloadWorkerEffectTestHooks.beforeNoCacheMediaPublicationForTesting = {
                boundaryEntered.countDown()
                check(boundaryRelease.await(5_000L, TimeUnit.MILLISECONDS)) {
                    "Timed out waiting to release no-cache media boundary"
                }
            }
            DownloadWorkerEffectTestHooks.beforeNoCacheMediaScanForTesting = {
                mediaScanCount.incrementAndGet()
            }
        } else {
            DownloadWorkerEffectTestHooks.beforeTerminalProgressPublicationForTesting = {
                boundaryEntered.countDown()
                check(boundaryRelease.await(5_000L, TimeUnit.MILLISECONDS)) {
                    "Timed out waiting to release terminal progress boundary"
                }
            }
            DownloadWorkerEffectTestHooks.beforeTerminalProgressPostForTesting = {
                terminalPostCount.incrementAndGet()
            }
        }
        if (noOp) {
            DownloadRepository.userStopWriteNoOpForTesting = { targetId, status ->
                targetId == downloadId && status == expectedStatus
            }
        } else {
            DownloadRepository.userStopWriteFailureForTesting = { targetId, status ->
                if (targetId == downloadId && status == expectedStatus) {
                    IllegalStateException("injected first ${expectedStatus.name} write failure")
                } else {
                    null
                }
            }
        }

        coroutineScope {
            val effectResult = async(Dispatchers.IO) {
                runCatching {
                    val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
                    when (effect) {
                        WorkerEffectBoundary.NO_CACHE_MEDIA ->
                            publishNoCacheMediaWithOwnedExecution(
                                context = context,
                                dbManager = db,
                                downloadItem = item,
                                finalPaths = emptyList(),
                                eventBus = eventBus,
                            )
                        WorkerEffectBoundary.TERMINAL_PROGRESS ->
                            publishTerminalWorkerProgressWithOwnedExecution(
                                context = context,
                                dbManager = db,
                                downloadItem = item,
                                eventBus = eventBus,
                                summary = "terminal-summary",
                            )
                    }
                }
            }

            try {
                assertTrue(boundaryEntered.await(5_000L, TimeUnit.MILLISECONDS))
                sendReceiverAndAwait(
                    context = context,
                    receiver = if (
                        disposition == DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL
                    ) {
                        CancelDownloadNotificationReceiver(db)
                    } else {
                        PauseDownloadNotificationReceiver(db)
                    },
                    intent = receiverIntent(
                        action = "worker-effect-stop-${UUID.randomUUID()}",
                        downloadId = downloadId,
                        executionId = executionId,
                    ),
                ) {
                    db.downloadDao.getNullableDownloadById(downloadId)?.status ==
                        DownloadRepository.Status.Active.name &&
                        DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId) ==
                            disposition &&
                        DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId) ==
                            DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING
                }
                DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)

                boundaryRelease.countDown()
                val result = effectResult.await()
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is CancellationException)
                assertEquals(0, mediaScanCount.get())
                assertEquals(0, terminalProgressCount.get())
                assertEquals(0, terminalPostCount.get())
                assertEquals(0, process.destroyCount.get())
                assertTrue(process.isAlive)
                assertTrue(DownloadWorkerExecutionOwners.isOwnedBy(downloadId, executionId))

                DownloadRepository.userStopWriteFailureForTesting = null
                DownloadRepository.userStopWriteNoOpForTesting = null
                assertTrue(
                    DownloadExecutionRecovery.convergeUserStopBeforeGenericCleanup(
                        context = context,
                        dbManager = db,
                        downloadId = downloadId,
                        executionId = executionId,
                    )
                )
                assertEquals(expectedStatus.name, db.downloadDao.getNullableDownloadById(downloadId)?.status)
                assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
                assertEquals(1, process.destroyCount.get())
                assertFalse(process.isAlive)
                assertNull(DownloadWorkerProcessOwners.ownerOf(downloadId))
            } finally {
                boundaryRelease.countDown()
                DownloadRepository.userStopWriteFailureForTesting = null
                DownloadRepository.userStopWriteNoOpForTesting = null
                DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
                DownloadWorkerEffectTestHooks.beforeNoCacheMediaPublicationForTesting = null
                DownloadWorkerEffectTestHooks.beforeNoCacheMediaScanForTesting = null
                DownloadWorkerEffectTestHooks.beforeTerminalProgressPublicationForTesting = null
                DownloadWorkerEffectTestHooks.beforeTerminalProgressPostForTesting = null
                if (effect == WorkerEffectBoundary.TERMINAL_PROGRESS) {
                    eventBus.unregister(subscriber)
                }
                YoutubeDLCompat.clearProcessForTesting(processId)
                DownloadWorkerProcessOwners.release(downloadId, executionId)
                DownloadWorkerExecutionOwners.release(downloadId, executionId)
            }
        }
    }

    private class WorkerProgressSubscriber(
        private val downloadId: Long,
        private val output: String,
        private val count: AtomicInteger,
    ) {
        @Subscribe
        fun onWorkerProgress(progress: com.ireum.ytdl.work.DownloadWorker.WorkerProgress) {
            if (
                progress.downloadItemID == downloadId &&
                    progress.progress == 100 &&
                    progress.output == output
            ) {
                count.incrementAndGet()
            }
        }
    }

    @Test
    fun committedCancelCannotBeDowngradedByAnOlderPauseCarrier() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "cancel-wins-over-pause-E1"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))

        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context = context,
                item = item,
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
            )
        )
        db.downloadDao.updateRaw(
            item.copy(status = DownloadRepository.Status.Cancelled.name)
        )

        val result = DownloadRepository(db).convergeUserStopSemantic(
            id = downloadId,
            expectedExecutionId = executionId,
            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
        )
        assertEquals(
            DownloadRepository.UserStopSemanticOutcome.STRONGER_USER_CANCEL_ALREADY_WON,
            result.outcome,
        )
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )

        assertEquals(
            DownloadExecutionRecovery.UserStopPreparation.READY_FOR_NATIVE_QUIESCENCE,
            DownloadExecutionRecovery.prepareUserStopBeforeNative(
                context = context,
                dbManager = db,
                downloadId = downloadId,
                executionId = executionId,
            ),
        )
        assertEquals(
            DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
            DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId),
        )
        assertEquals(
            DownloadRepository.Status.Cancelled.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
    }

    @Test
    fun viewModelCancelFalseKeepsCancelledE1CarrierAndForeignE2Owner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(executionId = "cancel-E1")
        )
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, "cancel-E2"))
        val viewModel = DownloadViewModel(context, db, true)

        try {
            assertTrue(runCatching { viewModel.cancelDownload(downloadId) }.isFailure)
            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            assertEquals("cancel-E2", DownloadWorkerProcessOwners.ownerOf(downloadId))
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerProcessOwners.release(downloadId, "cancel-E2")
        }
    }

    @Test
    fun viewModelCancelExceptionKeepsCancelledE1CarrierAndExactProcessOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(executionId = "cancel-exception-E1")
        )
        val processId = YtdlpProcessIdentity.download(downloadId, "cancel-exception-E1")
        val process = NeverQuiescingProcess()
        DownloadWorkerExecutionOwners.claim(downloadId, "cancel-exception-E1")
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, "cancel-exception-E1"))
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        val viewModel = DownloadViewModel(context, db, true)

        try {
            assertTrue(runCatching { viewModel.cancelDownload(downloadId) }.isFailure)
            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            assertEquals(
                "cancel-exception-E1",
                DownloadWorkerProcessOwners.ownerOf(downloadId),
            )
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, "cancel-exception-E1")
            DownloadWorkerExecutionOwners.release(downloadId, "cancel-exception-E1")
        }
    }

    @Test
    fun viewModelIncognitoCancelDoesNotDeleteUnresolvedE1Row() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val preferences = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        val previousIncognito = preferences.getBoolean("incognito", false)
        val downloadId = db.downloadDao.insertRaw(
            download().copy(executionId = "incognito-E1")
        )
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, "incognito-E2"))
        preferences.edit().putBoolean("incognito", true).commit()
        val viewModel = DownloadViewModel(context, db, true)
        try {
            val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertTrue(runCatching { viewModel.updateDownload(item.copy(status = DownloadRepository.Status.Cancelled.name)) }.isFailure)
            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            preferences.edit().putBoolean("incognito", previousIncognito).commit()
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerProcessOwners.release(downloadId, "incognito-E2")
        }
    }

    @Test
    fun viewModelPauseFalseWithholdsResumeAndPreservesPausedE1Barrier() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(executionId = "pause-E1")
        )
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, "pause-E2"))
        val viewModel = DownloadViewModel(context, db, true)

        try {
            assertTrue(runCatching { viewModel.pauseDownload(downloadId) }.isFailure)
            assertEquals(
                DownloadRepository.Status.Paused.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            assertFalse(viewModel.resumePausedDownloadAndWait(downloadId, "pause-E1"))
            assertEquals(
                DownloadRepository.Status.Paused.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerProcessOwners.release(downloadId, "pause-E2")
        }
    }

    @Test
    fun bulkRequeueRefusesAnExecutionWithPendingNativeRecovery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                executionId = "requeue-barrier-E1",
                status = DownloadRepository.Status.Cancelled.name,
            )
        )
        val viewModel = DownloadViewModel(context, db, true)

        try {
            assertTrue(
                DownloadExecutionRecovery.recordPending(
                    context,
                    requireNotNull(db.downloadDao.getNullableDownloadById(downloadId)),
                )
            )
            viewModel.reQueueDownloadItemsAndWait(listOf(downloadId))

            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                "requeue-barrier-E1",
                db.downloadDao.getNullableDownloadById(downloadId)?.executionId,
            )
            assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
        }
    }

    @Test
    fun processOwnerOnlyPausedE1IsAResumeBarrierWithoutJournal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                executionId = "owner-only-E1",
                status = DownloadRepository.Status.Paused.name,
            )
        )
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, "owner-only-E1"))
        val viewModel = DownloadViewModel(context, db, true)

        try {
            assertTrue(DownloadExecutionRecovery.hasPendingRecovery(context, downloadId))
            assertFalse(viewModel.resumePausedDownloadAndWait(downloadId, "owner-only-E1"))
            assertEquals(
                DownloadRepository.Status.Paused.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            DownloadWorkerProcessOwners.release(downloadId, "owner-only-E1")
        }
    }

    @Test
    fun viewModelPauseAllIsolatesOneQuiescenceFailureFromHealthySibling() = runBlocking(Dispatchers.Main) {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val failingId = db.downloadDao.insertRaw(
            download().copy(executionId = "pause-all-fail-E1")
        )
        val siblingId = db.downloadDao.insertRaw(
            download().copy(executionId = "pause-all-sibling-E1")
        )
        assertTrue(DownloadWorkerProcessOwners.claim(failingId, "pause-all-fail-E2"))
        val viewModel = DownloadViewModel(context, db, true)

        try {
            viewModel.pauseAllDownloads()
            assertEquals(
                DownloadRepository.Status.Paused.name,
                db.downloadDao.getNullableDownloadById(failingId)?.status,
            )
            assertEquals(
                DownloadRepository.Status.Paused.name,
                db.downloadDao.getNullableDownloadById(siblingId)?.status,
            )
            assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(failingId))
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(siblingId))
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(failingId)
            DownloadWorkerProcessOwners.release(failingId, "pause-all-fail-E2")
        }
    }

    @Test
    fun newerExecutionCannotReplaceUnresolvedStaleRecoveryCarrier() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staleExecutionId = "carrier-stale-E1"
        val currentExecutionId = "carrier-current-E2"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(executionId = staleExecutionId)
        )
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = YtdlpProcessIdentity.download(downloadId, staleExecutionId),
            state = "RUNNING",
            generationToken = "carrier-stale-generation-${UUID.randomUUID()}",
        )

        try {
            assertTrue(
                DownloadExecutionRecovery.recordPending(
                    context,
                    requireNotNull(db.downloadDao.getNullableDownloadById(downloadId)),
                )
            )
            db.downloadDao.updateRaw(
                requireNotNull(db.downloadDao.getNullableDownloadById(downloadId)).copy(
                    executionId = currentExecutionId,
                )
            )
            DownloadWorkerExecutionOwners.claim(downloadId, currentExecutionId)

            assertFalse(
                DownloadExecutionRecovery.recordPending(
                    context,
                    requireNotNull(db.downloadDao.getNullableDownloadById(downloadId)),
                )
            )
            assertTrue(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            assertEquals(
                currentExecutionId,
                db.downloadDao.getNullableDownloadById(downloadId)?.executionId,
            )
            assertTrue(marker.exists())
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            DownloadWorkerExecutionOwners.release(downloadId, currentExecutionId)
            marker.delete()
        }
    }

    @Test
    fun staleUserStopCarrierCannotMutateCurrentExecution() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val staleExecutionId = "user-carrier-stale-E1"
        val currentExecutionId = "user-carrier-current-E2"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                executionId = staleExecutionId,
                status = DownloadRepository.Status.Cancelled.name,
            )
        )
        val staleItem = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(
            DownloadExecutionRecovery.recordPending(
                context = context,
                item = staleItem,
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
            )
        )
        assertTrue(
            DownloadExecutionRecovery.markUserStopSemanticCommitted(
                context = context,
                downloadId = downloadId,
                executionId = staleExecutionId,
                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
            )
        )
        db.downloadDao.updateRaw(
            staleItem.copy(
                executionId = currentExecutionId,
                status = DownloadRepository.Status.Active.name,
            )
        )
        DownloadExecutionRecovery.commitOverride = { operation, _ ->
            operation != DownloadExecutionRecovery.JournalCommitOperation.CLEAR
        }

        try {
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                currentExecutionId,
                db.downloadDao.getNullableDownloadById(downloadId)?.executionId,
            )
            assertEquals(
                staleExecutionId,
                context.getSharedPreferences(
                    "download-execution-recovery",
                    android.content.Context.MODE_PRIVATE,
                ).getString(downloadId.toString(), null),
            )
            assertEquals(
                DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId),
            )

            DownloadExecutionRecovery.commitOverride = null
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            DownloadExecutionRecovery.commitOverride = null
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
        }
    }

    @Test
    fun cancelledE1RecoveryCarrierIsRediscoveredAfterProcessDeath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val executionId = "cancelled-process-death-E1"
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                executionId = executionId,
                status = DownloadRepository.Status.Cancelled.name,
            )
        )
        val processId = YtdlpProcessIdentity.download(downloadId, executionId)
        val process = NeverQuiescingProcess()
        DownloadWorkerExecutionOwners.claim(downloadId, executionId)
        assertTrue(DownloadWorkerProcessOwners.claim(downloadId, executionId))
        YoutubeDLCompat.registerProcessForTesting(processId, process)

        try {
            val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertTrue(DownloadExecutionRecovery.recordPending(context, item))
            assertFalse(
                DownloadExecutionRecovery.quiesceAfterDurableStop(
                    context = context,
                    downloadId = downloadId,
                    executionId = executionId,
                    dbManager = db,
                )
            )

            // Model the app process dying: process-local owners and the
            // registered Process disappear, while the journal remains.
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, executionId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
            DownloadExecutionRecovery.reconcile(context, db)

            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, executionId)
            DownloadWorkerExecutionOwners.release(downloadId, executionId)
        }
    }

    @Test
    fun applicationRecoveryFinalizesCommittedDebtIdempotently() = runBlocking {
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                executionId = "dead-E1",
            )
        )
        db.historyDao.updateRaw(db.historyDao.getItem(historyId).copy(downloadId = downloadId))

        DownloadExecutionRecovery.reconcile(ApplicationProvider.getApplicationContext(), db)
        DownloadExecutionRecovery.reconcile(ApplicationProvider.getApplicationContext(), db)

        assertEquals(null, db.downloadDao.getNullableDownloadById(downloadId))
    }

    @Test
    fun clearQueueCancelsEveryOrdinaryQueueIntentDurably() = runBlocking {
        val queuedId = db.downloadDao.insertRaw(
            download().copy(status = DownloadRepository.Status.Queued.name)
        )
        val waitingId = db.downloadDao.insertRaw(
            download().copy(status = DownloadRepository.Status.WaitingForMembership.name)
        )
        val scheduledId = db.downloadDao.insertRaw(
            download().copy(status = DownloadRepository.Status.Scheduled.name)
        )
        val activeId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Active.name,
                executionId = "clear-E1",
            )
        )

        val result = DownloadRepository(db).cancelActiveQueuedWithResult()

        assertEquals(null, result.failure)
        listOf(queuedId, waitingId, scheduledId, activeId).forEach { id ->
            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                db.downloadDao.getNullableDownloadById(id)?.status,
            )
        }
        assertTrue(result.publications.any { it.downloadId == activeId && it.executionId == "clear-E1" })

        // A later worker claim/recovery pass cannot resurrect the cleared
        // queue intent from its durable Cancelled state.
        listOf(queuedId, waitingId, scheduledId, activeId).forEach { id ->
            val current = requireNotNull(db.downloadDao.getNullableDownloadById(id))
            assertEquals(
                0,
                db.downloadDao.claimDownloadForWorker(
                    id = id,
                    expectedOperationId = current.operationId,
                    expectedRetryAttempt = current.retryAttempt,
                    executionId = "unrelated-E2-$id",
                )
            )
        }
        DownloadExecutionRecovery.reconcile(ApplicationProvider.getApplicationContext(), db)
        assertTrue(
            listOf(queuedId, waitingId, scheduledId, activeId).all { id ->
                db.downloadDao.getNullableDownloadById(id)?.status ==
                    DownloadRepository.Status.Cancelled.name
            }
        )
    }

    @Test
    fun clearQueueCancellationFailureDoesNotSkipSiblingsOrReportSuccess() = runBlocking {
        val failingId = db.downloadDao.insertRaw(
            download().copy(status = DownloadRepository.Status.Queued.name)
        )
        val siblingId = db.downloadDao.insertRaw(
            download().copy(status = DownloadRepository.Status.Queued.name)
        )
        val repository = DownloadRepository(db)
        repository.cancelActiveQueuedFailureForTesting = { id ->
            if (id == failingId) IllegalStateException("injected first cancellation write failure") else null
        }

        try {
            val result = repository.cancelActiveQueuedWithResult()

            assertNotNull(result.failure)
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(failingId)?.status,
            )
            assertEquals(
                DownloadRepository.Status.Cancelled.name,
                db.downloadDao.getNullableDownloadById(siblingId)?.status,
            )
        } finally {
            repository.cancelActiveQueuedFailureForTesting = null
        }
    }

    private fun history() = HistoryItem(
        id = 0L,
        url = "https://example.com/video",
        title = "old title",
        author = "old author",
        artist = "old artist",
        duration = "01:00",
        durationSeconds = 60,
        thumb = "old-thumb",
        type = DownloadType.video,
        time = 1L,
        downloadPath = listOf("/old/file.mp4"),
        website = "example",
        format = Format(container = "mp4", filesize = 100),
        filesize = 100,
        downloadId = 0L,
    )

    private fun download() = DownloadItem(
        id = 0L,
        url = "https://example.com/video",
        title = "replacement",
        author = "author",
        thumb = "thumb",
        duration = "01:00",
        type = DownloadType.video,
        format = Format(container = "mp4"),
        container = "mp4",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = "/tmp/file.mp4",
        website = "example",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = DownloadRepository.Status.Active.name,
        downloadStartTime = 0L,
        logID = null,
        playlistURL = "",
    )

    private fun receiverIntent(
        action: String,
        downloadId: Long,
        executionId: String,
    ) = android.content.Intent("com.ireum.ytdl.test.$action.${UUID.randomUUID()}")
        .putExtra("itemID", downloadId.toInt())
        .putExtra("executionId", executionId)
        .putExtra("title", "test")

    private suspend fun sendReceiverAndAwait(
        context: android.content.Context,
        receiver: android.content.BroadcastReceiver,
        intent: android.content.Intent,
        completed: () -> Boolean,
    ) {
        ContextCompat.registerReceiver(
            context,
            receiver,
            android.content.IntentFilter(requireNotNull(intent.action)),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            context.sendBroadcast(intent.setPackage(context.packageName))
            withTimeout(5_000L) {
                while (!completed()) delay(25L)
            }
            // Allow the launched receiver body to reach its finally block
            // before unregistering the dynamically registered receiver.
            delay(100L)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private class NeverQuiescingProcess : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 1
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false
        override fun exitValue(): Int = throw IllegalThreadStateException("still running")
        override fun destroy() = Unit
        override fun destroyForcibly(): Process = this
        override fun isAlive(): Boolean = true
    }

    private class QuiescingProcess : Process() {
        private val aliveState = AtomicBoolean(true)
        val destroyCount = AtomicInteger(0)

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            aliveState.set(false)
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !aliveState.get()
        override fun exitValue(): Int = if (aliveState.get()) {
            throw IllegalThreadStateException("still running")
        } else {
            0
        }
        override fun destroy() {
            destroyCount.incrementAndGet()
            aliveState.set(false)
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
        override fun isAlive(): Boolean = aliveState.get()
    }
}
