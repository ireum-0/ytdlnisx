package com.ireum.ytdl.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryRepository
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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FindingAProductionWiringTest {
    private lateinit var db: DBManager
    private lateinit var historyRepository: HistoryRepository

    @Before
    fun createDb() {
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
        DownloadExecutionRecovery.recoveryReadFailureCountForTesting = 0
        DownloadExecutionRecovery.failCommittedHistoryFinalizationForTesting = false
        YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
        YtdlpNativeProcessBarrier.markerEnumerationFailureForTesting = false
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
        val staleId = db.downloadDao.insertRaw(
            hardSubDownload(1001L).copy(
                status = DownloadRepository.Status.Active.name,
                executionId = "stale-hard-E1",
            )
        )
        val healthyId = db.downloadDao.insertRaw(
            hardSubDownload(1002L).copy(
                status = DownloadRepository.Status.Queued.name,
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
        val liveId = db.downloadDao.insertRaw(
            hardSubDownload(1101L).copy(
                status = DownloadRepository.Status.Active.name,
                executionId = "live-hard-E1",
            )
        )
        val queuedId = db.downloadDao.insertRaw(
            hardSubDownload(1102L).copy(
                status = DownloadRepository.Status.Queued.name,
                executionId = "",
            )
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
        val postProcessingId = db.downloadDao.insertRaw(
            hardSubDownload(1201L).copy(
                status = DownloadRepository.Status.PostProcessing.name,
                executionId = "live-post-hard-E1",
            )
        )
        val queuedId = db.downloadDao.insertRaw(
            hardSubDownload(1202L).copy(
                status = DownloadRepository.Status.Queued.name,
                executionId = "",
            )
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
