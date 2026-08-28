package com.ireum.ytdl.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.DownloadWorkerAdmissionResult
import com.ireum.ytdl.work.DownloadWorkerExecutionOwners
import com.ireum.ytdl.work.DownloadWorkerProcessOwners
import com.ireum.ytdl.work.claimDownloadThroughProductionAdmission
import com.ireum.ytdl.work.admitQueuedDownloadsThroughProductionPath
import com.ireum.ytdl.work.observeQueuedDownloadsAfterRecovery
import com.ireum.ytdl.work.YtdlpProcessIdentity
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.UUID

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
}
