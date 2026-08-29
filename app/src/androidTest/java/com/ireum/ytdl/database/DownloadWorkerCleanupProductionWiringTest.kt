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
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.work.HistoryReplacementPersistenceResult
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.DownloadWorker
import com.ireum.ytdl.work.DownloadWorkerExecutionOwners
import com.ireum.ytdl.work.DownloadWorkerProcessOwners
import com.ireum.ytdl.work.YtdlpProcessIdentity
import com.ireum.ytdl.work.admitQueuedDownloadsThroughProductionPath
import com.ireum.ytdl.work.observeQueuedDownloadsAfterRecovery
import com.ireum.ytdl.work.claimDownloadThroughProductionAdmission
import com.ireum.ytdl.work.cleanupStoppedDownloadExecution
import com.ireum.ytdl.work.persistHistoryReplacementTerminalState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadWorkerCleanupProductionWiringTest {
    private lateinit var db: DBManager

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(context)
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        db = Room.inMemoryDatabaseBuilder(
            context,
            DBManager::class.java,
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(ApplicationProvider.getApplicationContext())
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        DownloadExecutionRecovery.recoveryReadFailureCountForTesting = 0
        DownloadExecutionRecovery.failCommittedHistoryFinalizationForTesting = false
        DownloadExecutionRecovery.commitOverride = null
        YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
        YtdlpNativeProcessBarrier.markerEnumerationFailureForTesting = false
        db.close()
    }

    @Test
    fun startupRecoveryFailureDoesNotBlockHealthyQueueAdmission() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val failingId = db.downloadDao.insertRaw(download().copy(executionId = "opaque-E1"))
        val healthyId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                executionId = "",
            )
        )
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = YtdlpProcessIdentity.download(failingId, "opaque-E1"),
            state = "RUNNING",
            generationToken = "opaque-generation-${UUID.randomUUID()}",
        )
        try {
            // Keep the recovery failure scoped to A's exact marker. A global
            // namespace read failure would correctly fail-closed the healthy
            // candidate's native absence check as well.
            YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = marker.absolutePath

            val admission = observeQueuedDownloadsAfterRecovery(
                context = appContext,
                dbManager = db,
                priorityItemIds = emptyList(),
                currentTimeMillis = System.currentTimeMillis() + 10_000L,
            )
            val queued = admission.queuedItems.first()

            assertTrue(admission.recovery.deferredDownloadIds.contains(failingId))
            assertTrue(queued.any { it.id == healthyId })
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(failingId)?.status,
            )
            assertTrue(
                YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(
                    failingId,
                    "opaque-E1",
                )
            )
            val productionAdmission = admitQueuedDownloadsThroughProductionPath(
                dbManager = db,
                items = queued,
                priorityItemIds = emptyList(),
                currentTimeMillis = System.currentTimeMillis() + 10_000L,
                concurrentDownloadLimit = 1,
                continueAfterPriorityItems = true,
                claim = { candidate ->
                    claimDownloadThroughProductionAdmission(
                        context = appContext,
                        dbManager = db,
                        candidate = candidate,
                        concurrentDownloadLimit = 1,
                    )
                },
            )
            assertTrue(productionAdmission.selectedCandidates.any { it.id == healthyId })
            assertTrue(productionAdmission.claimedItems.any { it.id == healthyId })
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(healthyId)?.status,
            )
            productionAdmission.claimedItems.forEach { claimed ->
                DownloadWorkerExecutionOwners.release(claimed.id, claimed.executionId)
            }
        } finally {
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
            YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
            DownloadWorkerExecutionOwners.ownerOf(healthyId)?.let { executionId ->
                DownloadWorkerExecutionOwners.release(healthyId, executionId)
            }
            marker.delete()
        }
    }

    @Test
    fun multiplePerDownloadRecoveryFailuresStillAdmitHealthySibling() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val firstId = db.downloadDao.insertRaw(download().copy(executionId = "opaque-A"))
        val secondId = db.downloadDao.insertRaw(download().copy(executionId = "opaque-B"))
        val healthyId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                executionId = "",
            )
        )
        val markers = listOf(
            YtdlpNativeProcessBarrier.writeMarkerForTesting(
                processId = YtdlpProcessIdentity.download(firstId, "opaque-A"),
                state = "RUNNING",
                generationToken = "opaque-generation-A-${UUID.randomUUID()}",
            ),
            YtdlpNativeProcessBarrier.writeMarkerForTesting(
                processId = YtdlpProcessIdentity.download(secondId, "opaque-B"),
                state = "RUNNING",
                generationToken = "opaque-generation-B-${UUID.randomUUID()}",
            ),
        )
        try {
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = true

            val admission = observeQueuedDownloadsAfterRecovery(
                context = appContext,
                dbManager = db,
                priorityItemIds = emptyList(),
                currentTimeMillis = System.currentTimeMillis() + 10_000L,
            )
            val queued = admission.queuedItems.first()

            assertTrue(admission.recovery.deferredDownloadIds.contains(firstId))
            assertTrue(admission.recovery.deferredDownloadIds.contains(secondId))
            assertTrue(queued.any { it.id == healthyId })
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(firstId)?.status,
            )
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(secondId)?.status,
            )
        } finally {
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
            markers.forEach { it.delete() }
        }
    }

    @Test
    fun globalMarkerDiscoveryFailureStillFailsQueueAdmission() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val healthyId = db.downloadDao.insertRaw(
            download().copy(
                status = DownloadRepository.Status.Queued.name,
                executionId = "",
            )
        )
        YtdlpNativeProcessBarrier.markerEnumerationFailureForTesting = true

        var failure: Throwable? = null
        try {
            observeQueuedDownloadsAfterRecovery(
                context = appContext,
                dbManager = db,
                priorityItemIds = emptyList(),
                currentTimeMillis = System.currentTimeMillis() + 10_000L,
            )
        } catch (error: Throwable) {
            failure = error
        }

        assertTrue(
            failure is YtdlpNativeProcessBarrier.NativeMarkerNamespaceUnavailableException
        )
        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(healthyId)?.status,
        )
    }

    @Test
    fun unreadableCurrentMarkerRetainsRecoveryOwnerUntilReadablePass() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val executionId = "opaque-row-${UUID.randomUUID()}"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = YtdlpProcessIdentity.download(downloadId, executionId),
            state = "RUNNING",
            generationToken = "opaque-row-generation-${UUID.randomUUID()}",
        )
        try {
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = true
            val failed = DownloadExecutionRecovery.reconcile(appContext, db)
            assertTrue(failed.deferredDownloadIds.contains(downloadId))
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(marker.exists())

            YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
            val recovered = DownloadExecutionRecovery.reconcile(appContext, db)
            assertTrue(recovered.completedCleanly)
            assertFalse(marker.exists())
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
            marker.delete()
        }
    }

    @Test
    fun unreadableOrphanMarkerRemainsVisibleWithoutInventingRow() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val downloadId = 918_101L
        val executionId = "opaque-orphan-${UUID.randomUUID()}"
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = YtdlpProcessIdentity.download(downloadId, executionId),
            state = "RUNNING",
            generationToken = "opaque-orphan-generation-${UUID.randomUUID()}",
        )
        try {
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = true
            val failed = DownloadExecutionRecovery.reconcile(appContext, db)
            assertTrue(failed.deferredDownloadIds.contains(downloadId))
            assertTrue(marker.exists())
            assertNull(db.downloadDao.getNullableDownloadById(downloadId))

            YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
            val recovered = DownloadExecutionRecovery.reconcile(appContext, db)
            assertTrue(recovered.completedCleanly)
            assertFalse(marker.exists())
            assertNull(db.downloadDao.getNullableDownloadById(downloadId))
        } finally {
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
            marker.delete()
        }
    }

    @Test
    fun opaqueMarkerKeepsRetryOwnerAliveUntilReadableRecovery() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val downloadId = 918_200L + (System.nanoTime() and 0xFFFF)
        val executionId = "opaque-retry-${UUID.randomUUID()}"
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = YtdlpProcessIdentity.download(downloadId, executionId),
            state = "RUNNING",
            generationToken = "opaque-retry-generation-${UUID.randomUUID()}",
        )
        try {
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = true
            DownloadExecutionRecovery.scheduleRecovery(appContext, downloadId)
            delay(250L)
            assertTrue(
                DownloadExecutionRecovery.isRecoveryJobActiveForTesting(downloadId),
            )

            YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
            assertTrue(
                YtdlpNativeProcessBarrier.recoverDownloadExecution(downloadId, executionId),
            )
            repeat(50) {
                if (DownloadExecutionRecovery.isRecoveryJobActiveForTesting(downloadId)) {
                    delay(20L)
                }
            }
            assertFalse(
                DownloadExecutionRecovery.isRecoveryJobActiveForTesting(downloadId),
            )
        } finally {
            DownloadExecutionRecovery.cancelRecoveryJobForTesting(downloadId)
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
            marker.delete()
        }
    }

    @Test
    fun nativeIdentityReadFailureIsDurablyUnknownAndLaterReadableRecoveryConverges() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val executionId = "identity-read-${UUID.randomUUID()}"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val processId = YtdlpProcessIdentity.download(downloadId, executionId)
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = processId,
            state = "RUNNING",
            generationToken = "identity-token-${UUID.randomUUID()}",
        )
        try {
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = true
            val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertTrue(DownloadExecutionRecovery.recordPending(appContext, item))
            assertEquals(
                "UNKNOWN",
                appContext.getSharedPreferences(
                    "download-execution-recovery",
                    android.content.Context.MODE_PRIVATE,
                ).getString("$downloadId:native-generation-kind", null),
            )

            YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
            DownloadExecutionRecovery.reconcile(appContext, db)

            assertFalse(marker.exists())
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            marker.delete()
        }
    }

    @Test
    fun legacyBlankExecutionDoesNotTurnAnExistingNativeMarkerIntoAbsence() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = ""))
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = "download:$downloadId:legacy-native",
            state = "RUNNING",
            generationToken = "legacy-token-${UUID.randomUUID()}",
        )
        try {
            val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertTrue(DownloadExecutionRecovery.recordPending(appContext, item))

            DownloadExecutionRecovery.reconcile(appContext, db)

            assertFalse(marker.exists())
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            marker.delete()
        }
    }

    @Test
    fun legacyBlankExecutionWithoutNativeCarrierConvergesSafely() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = ""))

        val result = DownloadExecutionRecovery.reconcile(appContext, db)

        assertTrue(result.completedCleanly)
        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
    }

    @Test
    fun legacyBlankExecutionWithUnreadableCarrierStaysFailClosedAndOwned() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = ""))
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = "download:$downloadId:legacy-native-opaque",
            state = "RUNNING",
            generationToken = "legacy-opaque-${UUID.randomUUID()}",
        )
        try {
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = true
            val failed = DownloadExecutionRecovery.reconcile(appContext, db)
            assertTrue(failed.deferredDownloadIds.contains(downloadId))
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(marker.exists())

            YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
            val recovered = DownloadExecutionRecovery.reconcile(appContext, db)
            assertTrue(recovered.completedCleanly)
            assertFalse(marker.exists())
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
            marker.delete()
        }
    }

    @Test
    fun failedRecoveryPublicationReleasesDeadWorkerTokenToDurableRowRecovery() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val firstId = db.downloadDao.insertRaw(download().copy(executionId = "E1"))
        val siblingId = db.downloadDao.insertRaw(download().copy(executionId = "E2"))
        DownloadWorkerExecutionOwners.claim(firstId, "E1")
        DownloadWorkerExecutionOwners.claim(siblingId, "E2")
        DownloadExecutionRecovery.commitOverride = { operation, _ ->
            operation != DownloadExecutionRecovery.JournalCommitOperation.RECORD
        }

        try {
            assertFalse(
                DownloadExecutionRecovery.recordPending(
                    appContext,
                    requireNotNull(db.downloadDao.getNullableDownloadById(firstId)),
                )
            )
            assertFalse(
                DownloadExecutionRecovery.recordPending(
                    appContext,
                    requireNotNull(db.downloadDao.getNullableDownloadById(siblingId)),
                )
            )

            // The worker has crossed its terminal cleanup boundary.  Its
            // process-local execution tokens are not a substitute for the
            // durable Active row after the carrier publication failed.
            DownloadWorkerExecutionOwners.release(firstId, "E1")
            DownloadWorkerExecutionOwners.release(siblingId, "E2")
            DownloadExecutionRecovery.reconcile(appContext, db)

            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(firstId)?.status,
            )
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(siblingId)?.status,
            )
            assertFalse(DownloadWorkerExecutionOwners.isOwnedBy(firstId, "E1"))
            assertFalse(DownloadWorkerExecutionOwners.isOwnedBy(siblingId, "E2"))
        } finally {
            DownloadWorkerExecutionOwners.release(firstId, "E1")
            DownloadWorkerExecutionOwners.release(siblingId, "E2")
        }
    }

    @Test
    fun staleE1JournalDoesNotSuppressAbandonedE2RecoveryWhenClearFails() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "E1"))
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(DownloadExecutionRecovery.recordPending(appContext, item))
        DownloadExecutionRecovery.commitOverride = { operation, _ ->
            operation != DownloadExecutionRecovery.JournalCommitOperation.CLEAR
        }

        DownloadExecutionRecovery.reconcile(appContext, db)
        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
        assertTrue(DownloadExecutionRecovery.pendingDownloadIds(appContext).contains(downloadId))

        val queued = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertEquals(
            1,
            db.downloadDao.claimDownloadForWorker(
                id = downloadId,
                expectedOperationId = queued.operationId,
                expectedRetryAttempt = queued.retryAttempt,
                executionId = "E2",
            )
        )
        DownloadExecutionRecovery.reconcile(appContext, db)

        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
        assertTrue(DownloadExecutionRecovery.pendingDownloadIds(appContext).contains(downloadId))

        DownloadExecutionRecovery.commitOverride = null
        DownloadExecutionRecovery.reconcile(appContext, db)
        assertTrue(DownloadExecutionRecovery.pendingDownloadIds(appContext).isEmpty())
    }

    @Test
    fun staleE1JournalCannotPreventE2CommittedHistoryFinalization() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val historyId = db.historyDao.insertAndGetIdRaw(history())
        val downloadId = db.downloadDao.insertRaw(
            download().copy(
                playlistURL = "history-redownload:$historyId",
                executionId = "E1",
            )
        )
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(DownloadExecutionRecovery.recordPending(appContext, item))
        DownloadExecutionRecovery.commitOverride = { operation, _ ->
            operation != DownloadExecutionRecovery.JournalCommitOperation.CLEAR
        }
        DownloadExecutionRecovery.reconcile(appContext, db)

        val queued = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertEquals(
            1,
            db.downloadDao.claimDownloadForWorker(
                id = downloadId,
                expectedOperationId = queued.operationId,
                expectedRetryAttempt = queued.retryAttempt,
                executionId = "E2",
            )
        )
        db.historyDao.updateRaw(history().copy(id = historyId, downloadId = downloadId))

        DownloadExecutionRecovery.reconcile(appContext, db)
        assertNull(db.downloadDao.getNullableDownloadById(downloadId))

        DownloadExecutionRecovery.commitOverride = null
        DownloadExecutionRecovery.reconcile(appContext, db)
        assertTrue(DownloadExecutionRecovery.pendingDownloadIds(appContext).isEmpty())
    }

    @Test
    fun qualityAuthorityLossUsesTerminalCarrierNotHistoryRefusalBarrier() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val issue = HistoryReplacementDiagnostic.qualityAuthorityLostIssue()
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "E1"))
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(DownloadExecutionRecovery.recordPending(appContext, item, issue))
        DownloadExecutionRecovery.reconcile(appContext, db)

        val current = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertEquals(DownloadRepository.Status.Error.name, current.status)
        assertEquals(issue.code.name, current.lastIssueCode)
        assertNull(db.historyReplacementBarrierDao.getByDownloadId(downloadId))
        assertTrue(DownloadExecutionRecovery.pendingDownloadIds(appContext).isEmpty())
    }

    @Test
    fun doublePersistenceFailureUsesTheProductionCleanupCarrierForEveryTypedRefusal() = runBlocking {
        listOf(
            HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE),
            HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.TYPE),
            HistoryReplacementDiagnostic.targetDeletedIssue(),
        ).forEach { issue ->
            val historyId = db.historyDao.insertAndGetIdRaw(history())
            val downloadId = db.downloadDao.insertRaw(
                download().copy(
                    playlistURL = "history-redownload:$historyId",
                    executionId = "E1",
                )
            )

            // Model the production worker's two failed authoritative writes:
            // the typed decision remains local when both attempts fail.
            val first = persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = { throw IOException("first terminal write failed") },
                transitionLinkedDownload = { error("ledger must not run") },
            )
            val second = persistHistoryReplacementTerminalState(
                issue = issue,
                persistDownload = { throw IOException("recovery terminal write failed") },
                transitionLinkedDownload = { error("ledger must not run") },
            )
            assertTrue(first is HistoryReplacementPersistenceResult.Failed)
            assertTrue(second is HistoryReplacementPersistenceResult.Failed)

            // This is the production cleanup seam called by
            // DownloadWorker.cleanupStoppedWorker after the DB becomes
            // writable again.  It receives the worker-local exact issue and
            // cannot route it through ordinary requeue.
            val result = cleanupStoppedDownloadExecution(
                repository = DownloadRepository(db),
                downloadId = downloadId,
                executionId = "E1",
                authoritativeIssue = issue,
            )

            assertNotEquals(
                DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
                result,
            )
            val current = db.downloadDao.getNullableDownloadById(downloadId)
            assertNotNull(current)
            assertEquals(DownloadRepository.Status.Error.name, current?.status)
            assertEquals(issue.code.name, current?.lastIssueCode)
            assertEquals(issue.stage.name, current?.lastIssueStage)
            val barrier = db.historyReplacementBarrierDao.getByDownloadId(downloadId)
            assertNotNull(barrier)
            assertEquals(issue.code.name, barrier?.issueCode)
            assertEquals(issue.stage.name, barrier?.issueStage)

            // Re-entry is idempotent and never turns the typed row into an
            // ordinary queued attempt.
            val reentry = cleanupStoppedDownloadExecution(
                repository = DownloadRepository(db),
                downloadId = downloadId,
                executionId = "E1",
                authoritativeIssue = issue,
            )
            assertNotEquals(
                DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
                reentry,
            )
        }
    }

    @Test
    fun aTypedRecoveryFailureDoesNotPreventAnUnrelatedSiblingFromConverging() = runBlocking {
        val failingId = db.downloadDao.insertRaw(
            download().copy(executionId = "E1")
        )
        val ordinaryId = db.downloadDao.insertRaw(
            download().copy(executionId = "E2")
        )
        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)

        var failed = false
        try {
            cleanupStoppedDownloadExecution(
                repository = DownloadRepository(db),
                downloadId = failingId,
                executionId = "E1",
                authoritativeIssue = issue,
            )
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)

        val siblingResult = cleanupStoppedDownloadExecution(
            repository = DownloadRepository(db),
            downloadId = ordinaryId,
            executionId = "E2",
        )
        assertEquals(
            DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
            siblingResult,
        )
        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(ordinaryId)?.status,
        )
        assertFalse(
            db.downloadDao.getNullableDownloadById(failingId)?.status ==
                DownloadRepository.Status.Queued.name
        )
    }

    @Test
    fun startupRecoveryWaitsForNativeTerminationBeforeRequeue() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "E1"))
        val processId = YtdlpProcessIdentity.download(downloadId, "E1")
        val process = ControlledProcess()
        DownloadWorkerProcessOwners.claim(downloadId, "E1")
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(DownloadExecutionRecovery.recordPending(appContext, item))

        try {
            val recovery = async(Dispatchers.IO) {
                DownloadExecutionRecovery.reconcile(appContext, db)
            }
            process.destroyRequested.await()
            yield()
            assertFalse(recovery.isCompleted)
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )

            process.acknowledgeTermination()
            recovery.await()
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            process.acknowledgeTermination()
            YoutubeDLCompat.clearProcessForTesting(processId)
            DownloadWorkerProcessOwners.release(downloadId, "E1")
        }
    }

    @Test
    fun startupRecoveryInspectsExactProcessRegistryAfterExecutionOwnerDisappears() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "E1"))
        val processId = YtdlpProcessIdentity.download(downloadId, "E1")
        val process = ControlledProcess()
        YoutubeDLCompat.registerProcessForTesting(processId, process)
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(DownloadExecutionRecovery.recordPending(appContext, item))
        // The durable journal says the prior cancellation barrier was
        // acknowledged, but an exact same-process native registry entry still
        // proves that the OS process has not quiesced.  No worker execution
        // owner is published in this scenario.
        assertTrue(DownloadExecutionRecovery.markNativeQuiescent(appContext, downloadId, "E1"))

        try {
            val recovery = async(Dispatchers.IO) {
                DownloadExecutionRecovery.reconcile(appContext, db)
            }
            process.destroyRequested.await()
            yield()
            assertFalse(recovery.isCompleted)
            assertEquals(
                DownloadRepository.Status.Active.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )

            process.acknowledgeTermination()
            recovery.await()
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            process.acknowledgeTermination()
            YoutubeDLCompat.clearProcessForTesting(processId)
        }
    }

    @Test
    fun durableNativeRecoveryCarrierSurvivesTheProcessRegistryBeingGone() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = "dead-E1"))
        val item = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        assertTrue(DownloadExecutionRecovery.recordPending(appContext, item))

        // Model a cold start after the app process and its in-memory process
        // registries disappeared.  The durable carrier still requires the
        // startup reconciler to establish that no exact process remains.
        DownloadExecutionRecovery.reconcile(appContext, db)

        assertEquals(
            DownloadRepository.Status.Queued.name,
            db.downloadDao.getNullableDownloadById(downloadId)?.status,
        )
    }

    @Test
    fun rowBackedUnjournaledNativeMarkerIsRecoveredBeforeRequeue() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val executionId = "row-marker-${UUID.randomUUID()}"
        val downloadId = db.downloadDao.insertRaw(download().copy(executionId = executionId))
        val processId = YtdlpProcessIdentity.download(downloadId, executionId)
        val generationToken = "row-marker-generation-${UUID.randomUUID()}"
        val process = ProcessBuilder(
            "/system/bin/sh",
            "-c",
            "exec sleep 60",
        ).apply {
            environment()[YtdlpNativeProcessBarrier.NATIVE_GENERATION_ENVIRONMENT] = generationToken
        }.start()
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = processId,
            state = "RUNNING",
            generationToken = generationToken,
        )

        try {
            DownloadExecutionRecovery.reconcile(appContext, db)

            assertFalse(isAliveCompat(process))
            assertFalse(marker.exists())
            assertEquals(
                DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            if (isAliveCompat(process)) process.destroy()
            awaitExitCompat(process)
            marker.delete()
        }
    }

    @Test
    fun orphanDownloadMarkerIsAdoptedWithoutInventingADownloadRow() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val downloadId = 918_001L
        val executionId = "orphan-${UUID.randomUUID()}"
        val processId = YtdlpProcessIdentity.download(downloadId, executionId)
        val generationToken = "orphan-generation-${UUID.randomUUID()}"
        val process = ProcessBuilder(
            "/system/bin/sh",
            "-c",
            "exec sleep 60",
        ).apply {
            environment()[YtdlpNativeProcessBarrier.NATIVE_GENERATION_ENVIRONMENT] = generationToken
        }.start()
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = processId,
            state = "RUNNING",
            generationToken = generationToken,
        )

        try {
            DownloadExecutionRecovery.reconcile(appContext, db)

            assertFalse(isAliveCompat(process))
            assertFalse(marker.exists())
            assertNull(db.downloadDao.getNullableDownloadById(downloadId))
        } finally {
            if (isAliveCompat(process)) process.destroy()
            awaitExitCompat(process)
            marker.delete()
        }
    }

    @Test
    fun orphanStartingMarkerConvergesAfterPreLaunchProcessDeath() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        YtdlpNativeProcessBarrier.configure(appContext)
        val downloadId = 918_002L
        val executionId = "pre-launch-${UUID.randomUUID()}"
        val marker = YtdlpNativeProcessBarrier.writeMarkerForTesting(
            processId = YtdlpProcessIdentity.download(downloadId, executionId),
            state = "STARTING",
            generationToken = "pre-launch-generation-${UUID.randomUUID()}",
        )

        try {
            DownloadExecutionRecovery.reconcile(appContext, db)

            assertFalse(marker.exists())
            assertNull(db.downloadDao.getNullableDownloadById(downloadId))
        } finally {
            marker.delete()
        }
    }

    private fun isAliveCompat(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun awaitExitCompat(process: Process) {
        repeat(80) {
            if (!isAliveCompat(process)) return
            Thread.sleep(25L)
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

    private class ControlledProcess : Process() {
        private val terminated = CountDownLatch(1)
        private var alive = true
        val destroyRequested = CompletableDeferred<Unit>()

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
            destroyRequested.complete(Unit)
        }

        override fun destroyForcibly(): Process {
            acknowledgeTermination()
            return this
        }

        override fun isAlive(): Boolean = alive

        fun acknowledgeTermination() {
            alive = false
            terminated.countDown()
        }
    }
}
