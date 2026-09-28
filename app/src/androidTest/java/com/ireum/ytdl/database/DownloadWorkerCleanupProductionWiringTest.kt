package com.ireum.ytdl.database

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.preference.PreferenceManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.dao.DownloadClaimTestHooks
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.work.HistoryReplacementPersistenceResult
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.DownloadProducerRecovery
import com.ireum.ytdl.work.DownloadWorker
import com.ireum.ytdl.work.DownloadWorkerEffectTestHooks
import com.ireum.ytdl.work.DownloadWorkerExecutionOwners
import com.ireum.ytdl.work.DownloadWorkerProcessOwners
import com.ireum.ytdl.work.YtdlpProcessIdentity
import com.ireum.ytdl.work.admitQueuedDownloadsThroughProductionPath
import com.ireum.ytdl.work.observeQueuedDownloadsAfterRecovery
import com.ireum.ytdl.work.claimDownloadThroughProductionAdmission
import com.ireum.ytdl.work.cleanupStoppedDownloadExecution
import com.ireum.ytdl.work.persistHistoryReplacementTerminalState
import com.ireum.ytdl.util.storage.DownloadCacheOwnership
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

private val realWorkerTestDownloadIds = AtomicLong(
    System.currentTimeMillis().coerceAtLeast(9_000_000L),
)

@RunWith(AndroidJUnit4::class)
class DownloadWorkerCleanupProductionWiringTest {
    private lateinit var db: DBManager
    private val failAuthorityQuery = ThreadLocal<Boolean>()
    private val roomReadFailures = AtomicInteger(0)

    // Throw at Room's actual SQLite query boundary, after the worker selected
    // the read. Other Room queries and sibling worker threads stay real.
    private fun faultingRoomFactory() = SupportSQLiteOpenHelper.Factory { configuration ->
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        fun wrap(database: SupportSQLiteDatabase): SupportSQLiteDatabase = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, arguments ->
            val query = arguments?.firstOrNull()
            val sql = (query as? SupportSQLiteQuery)?.sql ?: query as? String
            if (method.name == "query" && failAuthorityQuery.get() == true &&
                sql?.contains("FROM downloads WHERE id=") == true
            ) {
                failAuthorityQuery.remove()
                roomReadFailures.incrementAndGet()
                throw android.database.sqlite.SQLiteException("injected authoritative Room read failure")
            }
            try {
                method.invoke(database, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as SupportSQLiteDatabase
        Proxy.newProxyInstance(
            SupportSQLiteOpenHelper::class.java.classLoader,
            arrayOf(SupportSQLiteOpenHelper::class.java),
        ) { _, method, arguments ->
            val result = try {
                method.invoke(helper, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
            if (result is SupportSQLiteDatabase) wrap(result) else result
        } as SupportSQLiteOpenHelper
    }

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(context)
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        DownloadClaimTestHooks.resetForTesting()
        DownloadWorkerEffectTestHooks.dbManagerForTesting = null
        DownloadWorkerEffectTestHooks.beforeAuthorityReadForTesting = null
        DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        DownloadWorkerEffectTestHooks.ytdlpSuccessForTesting = null
        DownloadWorkerEffectTestHooks.beforeCommittedHistoryFinalizationForTesting = null
        DownloadWorkerEffectTestHooks.failureTerminalPersistenceForTesting = null
        DownloadWorkerEffectTestHooks.failureTerminalPersistenceNoOpForTesting = null
        DownloadWorkerEffectTestHooks.beforeUnexpectedErrorNotificationForTesting = null
        db = Room.inMemoryDatabaseBuilder(
            context,
            DBManager::class.java,
        ).openHelperFactory(faultingRoomFactory())
            .addTypeConverter(Converters()).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(ApplicationProvider.getApplicationContext())
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        DownloadClaimTestHooks.resetForTesting()
        DownloadWorkerEffectTestHooks.dbManagerForTesting = null
        DownloadWorkerEffectTestHooks.beforeAuthorityReadForTesting = null
        DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        DownloadWorkerEffectTestHooks.ytdlpSuccessForTesting = null
        DownloadWorkerEffectTestHooks.beforeCommittedHistoryFinalizationForTesting = null
        DownloadWorkerEffectTestHooks.failureTerminalPersistenceForTesting = null
        DownloadWorkerEffectTestHooks.failureTerminalPersistenceNoOpForTesting = null
        DownloadWorkerEffectTestHooks.beforeUnexpectedErrorNotificationForTesting = null
        DownloadExecutionRecovery.recoveryReadFailureCountForTesting = 0
        DownloadExecutionRecovery.failCommittedHistoryFinalizationForTesting = false
        DownloadExecutionRecovery.commitOverride = null
        YtdlpNativeProcessBarrier.markerReadFailureForTesting = false
        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
        YtdlpNativeProcessBarrier.markerEnumerationFailureForTesting = false
        db.close()
    }

    @Test
    fun realWorkerActiveAuthorityReadFailureEntersRecovery() = runBlocking {
        exerciseUnreadableAuthority(DownloadRepository.Status.Active.name, false, false)
    }

    @Test
    fun realWorkerPostProcessingAuthorityReadFailureEntersRecovery() = runBlocking {
        exerciseUnreadableAuthority(DownloadRepository.Status.PostProcessing.name, false, false)
    }

    @Test
    fun realWorkerUnreadableStopAndCleanupUseExactProducerRecoveryAfterCarrierFailure() = runBlocking {
        exerciseUnreadableAuthority(DownloadRepository.Status.Active.name, true, true)
    }

    @Test
    fun realWorkerPostProcessingUnreadableCleanupUsesExactProducerRecovery() = runBlocking {
        exerciseUnreadableAuthority(DownloadRepository.Status.PostProcessing.name, true, true)
    }

    private suspend fun exerciseUnreadableAuthority(
        status: String,
        failCleanup: Boolean,
        failFirstCarrier: Boolean,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        cancelStaleRealWorkerRequests(context)
        YtdlpNativeProcessBarrier.configure(context)
        val failedId = insertQueuedDownload("authority-read-failure")
        val siblingId = insertQueuedDownload("authority-readable-sibling")
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val previousConcurrency = preferences.getInt("concurrent_downloads", 1)
        val hadConcurrency = preferences.contains("concurrent_downloads")
        val failedStop = AtomicBoolean(false)
        val failedCleanup = AtomicBoolean(false)
        val rejectCarrier = AtomicBoolean(failFirstCarrier)
        val carrierFailures = AtomicInteger(0)
        val terminalAttempts = AtomicInteger(0)
        val exactExecution = java.util.concurrent.atomic.AtomicReference<String>()
        val exactOperation = java.util.concurrent.atomic.AtomicReference<String>()
        val exactProducerAtNativeBoundary =
            java.util.concurrent.atomic.AtomicReference<DownloadProducerRecovery.Record?>()
        val workerOwnerAtNativeBoundary = AtomicBoolean(false)
        val processOwnerAtNativeBoundary =
            java.util.concurrent.atomic.AtomicReference<String?>()
        try {
            assertTrue(preferences.edit().putInt("concurrent_downloads", 2).commit())
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { id ->
                if (id == failedId) {
                    val current = requireNotNull(db.downloadDao.getNullableDownloadById(id))
                    exactExecution.set(current.executionId)
                    exactOperation.set(current.operationId)
                    exactProducerAtNativeBoundary.set(
                        producerRecoveryRecord(context, id, current.executionId),
                    )
                    workerOwnerAtNativeBoundary.set(
                        DownloadWorkerExecutionOwners.isOwnedBy(id, current.executionId),
                    )
                    processOwnerAtNativeBoundary.set(DownloadWorkerProcessOwners.ownerOf(id))
                    db.downloadDao.updateMultipleRaw(listOf(current.copy(status = status)))
                }
                throw IOException("bounded producer failure reaches real repeated stop gate")
            }
            DownloadWorkerEffectTestHooks.beforeAuthorityReadForTesting = { id, boundary ->
                if (id == failedId && (
                        boundary == "stop_gate" && failedStop.compareAndSet(false, true) ||
                            boundary == "attempt_cleanup" && failCleanup &&
                            failedCleanup.compareAndSet(false, true)
                        )) failAuthorityQuery.set(true)
            }
            DownloadWorkerEffectTestHooks.failureTerminalPersistenceForTesting = { id ->
                if (id == failedId) terminalAttempts.incrementAndGet()
                null
            }
            DownloadExecutionRecovery.commitOverride = { operation, _ ->
                if (operation == DownloadExecutionRecovery.JournalCommitOperation.RECORD && rejectCarrier.get()) {
                    carrierFailures.incrementAndGet()
                    false
                } else true
            }
            val workInfo = enqueueAndAwaitDownloadWorker(context)
            assertEquals(WorkInfo.State.FAILED, workInfo.state)
            assertTrue(failedStop.get())
            assertEquals(failCleanup, failedCleanup.get())
            assertEquals(if (failCleanup) 2 else 1, roomReadFailures.get())
            assertEquals(0, terminalAttempts.get())
            val executionId = requireNotNull(exactExecution.get())
            val producerAtNativeBoundary = requireNotNull(exactProducerAtNativeBoundary.get())
            assertEquals(failedId, producerAtNativeBoundary.downloadId)
            assertEquals(executionId, producerAtNativeBoundary.executionId)
            assertEquals(requireNotNull(exactOperation.get()), producerAtNativeBoundary.operationId)
            assertEquals(DownloadProducerRecovery.Phase.RUNNING, producerAtNativeBoundary.phase)
            assertTrue(workerOwnerAtNativeBoundary.get())
            assertNull(processOwnerAtNativeBoundary.get())
            assertEquals(DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(siblingId)?.status)
            if (failFirstCarrier) {
                awaitAuthorityCondition { carrierFailures.get() > 0 }
                val exactProducer = producerRecoveryRecord(context, failedId, executionId)
                if (exactProducer != null) {
                    assertEquals(failedId, exactProducer.downloadId)
                    assertEquals(executionId, exactProducer.executionId)
                    assertEquals(
                        producerAtNativeBoundary.generationId,
                        exactProducer.generationId,
                    )
                    assertTrue(
                        exactProducer.phase in setOf(
                            DownloadProducerRecovery.Phase.RUNNING,
                            DownloadProducerRecovery.Phase.OUTPUT_UNPROVEN,
                            DownloadProducerRecovery.Phase.SUPERSEDED,
                        ),
                    )
                } else {
                    // The same-process retry may already have completed the
                    // exact native-quiescence and unpublished-producer
                    // retirement before this observer runs.
                    assertNull(DownloadWorkerExecutionOwners.ownerOf(failedId))
                    assertNull(DownloadWorkerProcessOwners.ownerOf(failedId))
                    assertFalse(
                        YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(
                            failedId,
                            executionId,
                        ),
                    )
                }
                rejectCarrier.set(false)
            }
            awaitAuthorityCondition {
                db.downloadDao.getNullableDownloadById(failedId)?.status == DownloadRepository.Status.Queued.name &&
                    db.downloadDao.getNullableDownloadById(failedId)?.executionId.isNullOrBlank() &&
                    !DownloadExecutionRecovery.hasRetiringAttempt(failedId, executionId) &&
                    !DownloadExecutionRecovery.isRecoveryJobActiveForTesting(failedId) &&
                    producerRecoveryRecord(context, failedId, executionId) == null &&
                    !YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(failedId, executionId)
            }
            assertNull(DownloadWorkerExecutionOwners.ownerOf(failedId))
            assertNull(DownloadWorkerProcessOwners.ownerOf(failedId))
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(failedId))
        } finally {
            rejectCarrier.set(false)
            DownloadExecutionRecovery.cancelAllRecoveryJobsAndJoinForTesting()
            DownloadWorkerEffectTestHooks.beforeAuthorityReadForTesting = null
            DownloadExecutionRecovery.commitOverride = null
            val editor = preferences.edit()
            if (hadConcurrency) editor.putInt("concurrent_downloads", previousConcurrency)
            else editor.remove("concurrent_downloads")
            assertTrue(editor.commit())
        }
    }

    @Test
    fun abandonedRunningProducerRecoveryFencesE2UntilStartupReconciliation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        cancelStaleRealWorkerRequests(context)
        YtdlpNativeProcessBarrier.configure(context)
        val downloadId = realWorkerTestDownloadIds.getAndIncrement()
        val operationId = "authority-recovery-$downloadId-${UUID.randomUUID()}"
        val e1 = "abandoned-e1-${UUID.randomUUID()}"
        val e1Item = download().copy(
            id = downloadId,
            operationId = operationId,
            executionId = e1,
            status = DownloadRepository.Status.Active.name,
        )
        val cacheRoot = File(context.cacheDir, "p2-producer-recovery-$downloadId")
        val outputRoot = File(cacheRoot, downloadId.toString())
        var admittedItem: DownloadItem? = null
        try {
            DownloadCacheOwnership.ensureMarker(cacheRoot, e1Item)
            val prepared = requireNotNull(
                DownloadProducerRecovery.prepare(
                    context = context,
                    downloadId = downloadId,
                    operationId = operationId,
                    executionId = e1,
                    semanticFingerprint = "authority-recovery-$downloadId",
                    outputRoot = outputRoot,
                ),
            )
            assertTrue(DownloadProducerRecovery.markRunning(context, prepared))
            db.downloadDao.insertRaw(
                e1Item.copy(
                    status = DownloadRepository.Status.Queued.name,
                    executionId = "",
                    downloadStartTime = 0L,
                ),
            )

            val queued = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertEquals(DownloadRepository.Status.Queued.name, queued.status)
            assertEquals("", queued.executionId)
            assertEquals(
                e1,
                producerRecoveryRecord(context, downloadId, e1)?.executionId,
            )
            assertTrue(DownloadProducerRecovery.hasBlockingForAdmission(context, downloadId))

            val blockedClaim = claimDownloadThroughProductionAdmission(
                context = context,
                dbManager = db,
                candidate = queued,
                concurrentDownloadLimit = 1,
            )
            admittedItem = blockedClaim
            assertNull("RUNNING E1 producer recovery must fence E2 admission", blockedClaim)
            assertNull(DownloadWorkerExecutionOwners.ownerOf(downloadId))
            assertEquals(DownloadRepository.Status.Queued.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status)

            // No process-local owner survives this boundary. Startup
            // reconciliation must use only the exact durable producer record
            // and native marker observation before allowing a new claim.
            DownloadWorkerExecutionOwners.clearForTesting()
            DownloadWorkerProcessOwners.clearForTesting()
            val recoveredQueue = observeQueuedDownloadsAfterRecovery(
                context = context,
                dbManager = db,
                priorityItemIds = emptyList(),
                currentTimeMillis = System.currentTimeMillis() + 10_000L,
            )
            assertFalse(recoveredQueue.recovery.deferredDownloadIds.contains(downloadId))
            assertNull(producerRecoveryRecord(context, downloadId, e1))
            assertFalse(DownloadProducerRecovery.hasBlockingForAdmission(context, downloadId))
            assertFalse(DownloadCacheOwnership.markerFile(cacheRoot, downloadId).exists())
            assertFalse(YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(downloadId, e1))

            val queueSnapshot = recoveredQueue.queuedItems.first()
            val currentQueued = requireNotNull(queueSnapshot.singleOrNull { it.id == downloadId })
            val admission = admitQueuedDownloadsThroughProductionPath(
                dbManager = db,
                items = listOf(currentQueued),
                priorityItemIds = emptyList(),
                currentTimeMillis = System.currentTimeMillis() + 10_000L,
                concurrentDownloadLimit = 1,
                continueAfterPriorityItems = true,
                claim = { candidate ->
                    claimDownloadThroughProductionAdmission(
                        context = context,
                        dbManager = db,
                        candidate = candidate,
                        concurrentDownloadLimit = 1,
                    )
                },
            )
            val e2 = requireNotNull(admission.claimedItems.singleOrNull())
            admittedItem = e2
            assertEquals(downloadId, e2.id)
            assertNotEquals(e1, e2.executionId)
            assertEquals(DownloadRepository.Status.Active.name, e2.status)
            assertEquals(e2.executionId, DownloadWorkerExecutionOwners.ownerOf(downloadId))

            assertEquals(
                DownloadRepository.RunningDownloadRequeueResult.REQUEUED,
                DownloadRepository(db).requeueRunningDownload(downloadId, e2.executionId),
            )
            DownloadWorkerExecutionOwners.release(downloadId, e2.executionId)
            admittedItem = null
            assertEquals("", db.downloadDao.getNullableDownloadById(downloadId)?.executionId)
        } finally {
            admittedItem?.let { claimed ->
                DownloadWorkerExecutionOwners.release(downloadId, claimed.executionId)
                DownloadWorkerProcessOwners.release(downloadId, claimed.executionId)
                runCatching {
                    DownloadRepository(db).requeueRunningDownload(downloadId, claimed.executionId)
                }
            }
            producerRecoveryRecord(context, downloadId, e1)?.let { record ->
                runCatching {
                    DownloadProducerRecovery.retireUnpublishedAfterQuiescence(context, record)
                }
            }
            cacheRoot.deleteRecursively()
        }
    }

    private suspend fun awaitAuthorityCondition(condition: () -> Boolean) {
        kotlinx.coroutines.withTimeout(30_000L) {
            while (!condition()) delay(25L)
        }
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

    @Test
    fun realWorkerRetriesOrdinaryErrorAfterFirstTerminalWriteException() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        cancelStaleRealWorkerRequests(context)
        val downloadId = insertQueuedDownload("first-error-exception")
        val enteredYtdlp = AtomicBoolean(false)
        val exactExecution = java.util.concurrent.atomic.AtomicReference<String>()
        val terminalAttempts = AtomicInteger(0)
        val notificationAttempts = AtomicInteger(0)
        var workerRequest: androidx.work.OneTimeWorkRequest? = null

        DownloadWorkerEffectTestHooks.dbManagerForTesting = db
        DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
            if (candidateId == downloadId) {
                enteredYtdlp.set(true)
                exactExecution.set(db.downloadDao.getNullableDownloadById(candidateId)?.executionId)
                throw IOException("injected ordinary yt-dlp failure")
            }
        }
        DownloadWorkerEffectTestHooks.failureTerminalPersistenceForTesting = { candidateId ->
            if (candidateId == downloadId && terminalAttempts.getAndIncrement() == 0) {
                IOException("injected first Error terminal write failure")
            } else {
                null
            }
        }
        DownloadWorkerEffectTestHooks.beforeUnexpectedErrorNotificationForTesting = { candidateId ->
            if (candidateId == downloadId) notificationAttempts.incrementAndGet()
        }

        try {
            val request = enqueueDownloadWorker(context)
            workerRequest = request
            val workInfo = awaitDownloadWorkerCondition(context, request.id) {
                val e1 = exactExecution.get()
                    val current = db.downloadDao.getNullableDownloadById(downloadId)
                    val producer = e1?.let { producerRecoveryRecord(context, downloadId, it) }
                enteredYtdlp.get() &&
                    terminalAttempts.get() >= 2 &&
                    notificationAttempts.get() == 1 &&
                    current?.status == DownloadRepository.Status.Error.name &&
                    current.executionId == e1 &&
                    e1 != null &&
                    producer?.executionId == e1 &&
                    producer?.phase == DownloadProducerRecovery.Phase.OUTPUT_UNPROVEN &&
                    DownloadWorkerExecutionOwners.ownerOf(downloadId) == null &&
                    DownloadWorkerProcessOwners.ownerOf(downloadId) == null &&
                    DownloadExecutionRecovery.pendingDownloadIds(context).none { it == downloadId }
            }
            val current = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            val e1 = requireNotNull(exactExecution.get())
            val producer = requireNotNull(producerRecoveryRecord(context, downloadId, e1))

            assertTrue("the real item execution boundary was not reached", enteredYtdlp.get())
            assertTrue("the real terminal writer was not retried", terminalAttempts.get() >= 2)
            assertEquals(1, notificationAttempts.get())
            assertEquals(DownloadRepository.Status.Error.name, current.status)
            assertEquals(e1, current.executionId)
            assertEquals(e1, producer.executionId)
            assertEquals(DownloadProducerRecovery.Phase.OUTPUT_UNPROVEN, producer.phase)
            assertTrue(DownloadProducerRecovery.hasBlockingForAdmission(context, downloadId))
            assertNull(DownloadWorkerExecutionOwners.ownerOf(downloadId))
            assertNull(DownloadWorkerProcessOwners.ownerOf(downloadId))
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            assertTrue(DownloadExecutionRecovery.hasRecoveryResponsibility(context, db))
            assertFalse("producer recovery keeps the queue worker alive", workInfo.state.isFinished)
            cancelAndAwaitWorker(context, request.id)
            workerRequest = null
            val recovery = DownloadExecutionRecovery.reconcile(context, db)
            assertFalse(recovery.deferredDownloadIds.contains(downloadId))
            assertNull(producerRecoveryRecord(context, downloadId, e1))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            workerRequest?.let { cancelAndAwaitWorker(context, it.id) }
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
            DownloadWorkerEffectTestHooks.failureTerminalPersistenceForTesting = null
            DownloadWorkerEffectTestHooks.beforeUnexpectedErrorNotificationForTesting = null
        }
    }

    @Test
    fun realWorkerRetriesOrdinaryErrorAfterFirstTerminalWriteNoOp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        cancelStaleRealWorkerRequests(context)
        val downloadId = insertQueuedDownload("first-error-no-op")
        val enteredYtdlp = AtomicBoolean(false)
        val exactExecution = java.util.concurrent.atomic.AtomicReference<String>()
        val terminalAttempts = AtomicInteger(0)
        val notificationAttempts = AtomicInteger(0)
        var workerRequest: androidx.work.OneTimeWorkRequest? = null

        DownloadWorkerEffectTestHooks.dbManagerForTesting = db
        DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
            if (candidateId == downloadId) {
                enteredYtdlp.set(true)
                exactExecution.set(db.downloadDao.getNullableDownloadById(candidateId)?.executionId)
                throw IOException("injected ordinary yt-dlp failure")
            }
        }
        DownloadWorkerEffectTestHooks.failureTerminalPersistenceNoOpForTesting = { candidateId ->
            candidateId == downloadId && terminalAttempts.getAndIncrement() == 0
        }
        DownloadWorkerEffectTestHooks.beforeUnexpectedErrorNotificationForTesting = { candidateId ->
            if (candidateId == downloadId) notificationAttempts.incrementAndGet()
        }

        try {
            val request = enqueueDownloadWorker(context)
            workerRequest = request
            val workInfo = awaitDownloadWorkerCondition(context, request.id) {
                val e1 = exactExecution.get()
                val current = db.downloadDao.getNullableDownloadById(downloadId)
                val producer = e1?.let { producerRecoveryRecord(context, downloadId, it) }
                enteredYtdlp.get() &&
                    terminalAttempts.get() >= 2 &&
                    notificationAttempts.get() == 1 &&
                    current?.status == DownloadRepository.Status.Error.name &&
                    current.executionId == e1 &&
                    e1 != null &&
                    producer?.executionId == e1 &&
                    producer?.phase == DownloadProducerRecovery.Phase.OUTPUT_UNPROVEN &&
                    DownloadWorkerExecutionOwners.ownerOf(downloadId) == null &&
                    DownloadWorkerProcessOwners.ownerOf(downloadId) == null &&
                    DownloadExecutionRecovery.pendingDownloadIds(context).none { it == downloadId }
            }
            val current = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            val e1 = requireNotNull(exactExecution.get())
            val producer = requireNotNull(producerRecoveryRecord(context, downloadId, e1))

            assertTrue("the real item execution boundary was not reached", enteredYtdlp.get())
            assertTrue("the real terminal writer was not retried", terminalAttempts.get() >= 2)
            assertEquals(1, notificationAttempts.get())
            assertEquals(DownloadRepository.Status.Error.name, current.status)
            assertEquals(e1, current.executionId)
            assertEquals(e1, producer.executionId)
            assertEquals(DownloadProducerRecovery.Phase.OUTPUT_UNPROVEN, producer.phase)
            assertTrue(DownloadProducerRecovery.hasBlockingForAdmission(context, downloadId))
            assertNull(DownloadWorkerExecutionOwners.ownerOf(downloadId))
            assertNull(DownloadWorkerProcessOwners.ownerOf(downloadId))
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            assertTrue(DownloadExecutionRecovery.hasRecoveryResponsibility(context, db))
            assertFalse("producer recovery keeps the queue worker alive", workInfo.state.isFinished)
            cancelAndAwaitWorker(context, request.id)
            workerRequest = null
            val recovery = DownloadExecutionRecovery.reconcile(context, db)
            assertFalse(recovery.deferredDownloadIds.contains(downloadId))
            assertNull(producerRecoveryRecord(context, downloadId, e1))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        } finally {
            workerRequest?.let { cancelAndAwaitWorker(context, it.id) }
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
            DownloadWorkerEffectTestHooks.failureTerminalPersistenceNoOpForTesting = null
            DownloadWorkerEffectTestHooks.beforeUnexpectedErrorNotificationForTesting = null
        }
    }

    @Test
    fun realWorkerEscapesPersistentTerminalFailureAfterSiblingAndReleasesDeadOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        cancelStaleRealWorkerRequests(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadConcurrentSetting = preferences.contains("concurrent_downloads")
        val previousConcurrentSetting = preferences.getInt("concurrent_downloads", 1)
        val failedId = insertQueuedDownload("persistent-terminal-failure")
        val siblingId = insertQueuedDownload("healthy-sibling")
        val enteredIds = mutableSetOf<Long>()
        val notificationAttempts = AtomicInteger(0)

        try {
            assertTrue(preferences.edit().putInt("concurrent_downloads", 2).commit())
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == failedId || candidateId == siblingId) {
                    synchronized(enteredIds) { enteredIds += candidateId }
                    throw IOException("injected ordinary yt-dlp failure $candidateId")
                }
            }
            DownloadWorkerEffectTestHooks.failureTerminalPersistenceForTesting = { candidateId ->
                if (candidateId == failedId) {
                    IOException("persistent Error terminal write failure")
                } else {
                    null
                }
            }
            DownloadWorkerEffectTestHooks.beforeUnexpectedErrorNotificationForTesting = { candidateId ->
                if (candidateId == failedId) {
                    notificationAttempts.incrementAndGet()
                }
            }

            val workInfo = enqueueAndAwaitDownloadWorker(context)
            val failed = requireNotNull(db.downloadDao.getNullableDownloadById(failedId))
            val sibling = requireNotNull(db.downloadDao.getNullableDownloadById(siblingId))

            assertEquals(setOf(failedId, siblingId), synchronized(enteredIds) { enteredIds.toSet() })
            assertEquals(DownloadRepository.Status.Queued.name, failed.status)
            assertEquals(DownloadRepository.Status.Error.name, sibling.status)
            assertEquals(0, notificationAttempts.get())
            assertNull(DownloadWorkerExecutionOwners.ownerOf(failedId))
            assertNull(DownloadWorkerExecutionOwners.ownerOf(siblingId))
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(failedId))
            assertTrue(workInfo.state == WorkInfo.State.FAILED)
        } finally {
            val editor = preferences.edit()
            if (hadConcurrentSetting) {
                editor.putInt("concurrent_downloads", previousConcurrentSetting)
            } else {
                editor.remove("concurrent_downloads")
            }
            assertTrue(editor.commit())
        }
    }

    @Test
    fun realWorkerKeepsCommittedHistoryAuthoritativeAfterFinalizationFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        cancelStaleRealWorkerRequests(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadCacheSetting = preferences.contains("cache_downloads")
        val previousCacheSetting = preferences.getBoolean("cache_downloads", true)
        val historyId = db.historyDao.insertAndGetIdRaw(
            history().copy(
                url = "https://example.com/audio",
                type = DownloadType.audio,
                format = Format(container = "m4a"),
                downloadPath = listOf("/old/file.m4a"),
            )
        )
        val downloadId = realWorkerTestDownloadIds.getAndIncrement()
        val outputPaths = AtomicInteger(0)
        val finalizationFailures = AtomicInteger(0)
        val exactExecution = java.util.concurrent.atomic.AtomicReference<String>()
        var workerRequest: androidx.work.OneTimeWorkRequest? = null

        try {
            assertTrue(preferences.edit().putBoolean("cache_downloads", false).commit())
            db.downloadDao.insertRaw(
                download().copy(
                    id = downloadId,
                    url = "https://example.com/audio",
                    type = DownloadType.audio,
                    format = Format(container = "m4a"),
                    container = "m4a",
                    downloadPath = context.cacheDir.absolutePath,
                    playlistURL = com.ireum.ytdl.util.HistoryRedownloadMarker.regular(historyId),
                    status = DownloadRepository.Status.Queued.name,
                    executionId = "",
                    operationId = "a9-history-finalization-${UUID.randomUUID()}",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.ytdlpSuccessForTesting = { candidateId, rawTempDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    exactExecution.set(
                        db.downloadDao.getNullableDownloadById(candidateId)?.executionId,
                    )
                    rawTempDirectory.mkdirs()
                    val output = File(rawTempDirectory, "replacement.m4a")
                    output.writeBytes(byteArrayOf(1, 2, 3))
                    outputPaths.incrementAndGet()
                    "[download] Destination: ${output.absolutePath}"
                }
            }
            DownloadWorkerEffectTestHooks.beforeCommittedHistoryFinalizationForTesting = { candidateId ->
                if (
                    candidateId == downloadId &&
                    finalizationFailures.getAndIncrement() == 0
                ) {
                    throw IOException("injected committed History finalization failure")
                }
            }

            val request = enqueueDownloadWorker(context)
            workerRequest = request
            val workInfo = awaitDownloadWorkerCondition(
                context = context,
                workId = request.id,
                timeoutDiagnostics = { lastState, elapsedMs ->
                    committedHistoryFinalizationTimeoutDiagnostics(
                        context = context,
                        historyId = historyId,
                        downloadId = downloadId,
                        outputPaths = outputPaths,
                        finalizationFailures = finalizationFailures,
                        exactExecution = exactExecution,
                        workInfoState = lastState,
                        elapsedMs = elapsedMs,
                    )
                },
            ) {
                val e1 = exactExecution.get()
                val replaced = db.historyDao.getNullableItem(historyId)
                val producer = e1?.let { producerRecoveryRecord(context, downloadId, it) }
                outputPaths.get() == 1 &&
                    finalizationFailures.get() == 1 &&
                    replaced?.title == "replacement" &&
                    replaced.downloadId == downloadId &&
                    db.downloadDao.getNullableDownloadById(downloadId) == null &&
                    e1 != null &&
                    producer?.phase == DownloadProducerRecovery.Phase.COMPLETE &&
                    DownloadWorkerExecutionOwners.ownerOf(downloadId) == null &&
                    DownloadWorkerProcessOwners.ownerOf(downloadId) == null &&
                    DownloadExecutionRecovery.pendingDownloadIds(context).none { it == downloadId }
            }
            val replaced = requireNotNull(db.historyDao.getNullableItem(historyId))
            val e1 = requireNotNull(exactExecution.get())
            val producer = requireNotNull(producerRecoveryRecord(context, downloadId, e1))

            assertEquals(1, outputPaths.get())
            assertEquals(1, finalizationFailures.get())
            assertEquals("replacement", replaced.title)
            assertEquals(downloadId, replaced.downloadId)
            assertNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertEquals(e1, producer.executionId)
            assertEquals(DownloadProducerRecovery.Phase.COMPLETE, producer.phase)
            assertFalse(DownloadProducerRecovery.hasBlockingForAdmission(context, downloadId))
            assertFalse(DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId))
            assertNull(DownloadWorkerExecutionOwners.ownerOf(downloadId))
            assertNull(DownloadWorkerProcessOwners.ownerOf(downloadId))
            assertTrue(DownloadExecutionRecovery.hasRecoveryResponsibility(context, db))
            assertFalse("completed producer finality keeps the queue worker alive", workInfo.state.isFinished)
            cancelAndAwaitWorker(context, request.id)
            workerRequest = null
            producerRecoveryRecord(context, downloadId, e1)?.let { retained ->
                assertTrue(DownloadProducerRecovery.retire(context, retained))
            }
            assertNull(producerRecoveryRecord(context, downloadId, e1))
        } finally {
            workerRequest?.let { cancelAndAwaitWorker(context, it.id) }
            DownloadWorkerEffectTestHooks.ytdlpSuccessForTesting = null
            DownloadWorkerEffectTestHooks.beforeCommittedHistoryFinalizationForTesting = null
            val editor = preferences.edit()
            if (hadCacheSetting) {
                editor.putBoolean("cache_downloads", previousCacheSetting)
            } else {
                editor.remove("cache_downloads")
            }
            assertTrue(editor.commit())
        }
    }

    private suspend fun insertQueuedDownload(operationSuffix: String): Long =
        realWorkerTestDownloadIds.getAndIncrement().let { testId ->
            db.downloadDao.insertRaw(
                download().copy(
                    id = testId,
                    status = DownloadRepository.Status.Queued.name,
                    executionId = "",
                    operationId = "a2-$operationSuffix-${UUID.randomUUID()}",
                    downloadStartTime = 0L,
                )
            )
        }

    private fun cancelStaleRealWorkerRequests(context: android.content.Context) {
        WorkManager.getInstance(context)
            .cancelAllWork()
            .result
            .get(10, TimeUnit.SECONDS)
        // Cancellation marks WorkManager rows synchronously, but an already
        // running worker can still be unwinding its finally/cleanup path.
        // Let that old worker finish before this test publishes its
        // in-memory DB hook; otherwise its dynamic test seam could inspect
        // and release the new test's exact owner by numeric ID.
        Thread.sleep(2_000L)
    }

    private suspend fun enqueueAndAwaitDownloadWorker(
        context: android.content.Context,
    ): WorkInfo {
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag("finding-a-a2-real-worker")
            .build()
        workManager.enqueue(request)
        return withContext(Dispatchers.IO) {
            repeat(240) {
                val workInfo = runCatching {
                    workManager.getWorkInfoById(request.id).get(1, TimeUnit.SECONDS)
                }.getOrNull()
                if (workInfo?.state?.isFinished == true) {
                    return@withContext workInfo
                }
                Thread.sleep(250L)
            }
            error("Timed out waiting for real DownloadWorker ${request.id}")
        }
    }

    private fun enqueueDownloadWorker(
        context: android.content.Context,
    ): androidx.work.OneTimeWorkRequest {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag("finding-a-a2-real-worker")
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return request
    }

    private suspend fun awaitDownloadWorkerCondition(
        context: android.content.Context,
        workId: UUID,
        timeoutDiagnostics: ((WorkInfo.State?, Long) -> String)? = null,
        condition: () -> Boolean,
    ): WorkInfo = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(context)
        val waitStartedAtNanos = System.nanoTime()
        var lastState: WorkInfo.State? = null
        repeat(240) {
            val workInfo = runCatching {
                workManager.getWorkInfoById(workId).get(1, TimeUnit.SECONDS)
            }.getOrNull()
            lastState = workInfo?.state
            if (workInfo?.state?.isFinished == true) {
                error(
                    "Real DownloadWorker $workId finished as ${workInfo.state} " +
                        "before its durable producer condition converged",
                )
            }
            if (workInfo != null && condition()) return@withContext workInfo
            Thread.sleep(250L)
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStartedAtNanos)
        val diagnosticSuffix = timeoutDiagnostics?.let { collect ->
            runCatching { collect(lastState, elapsedMs) }
                .fold(
                    onSuccess = { "; elapsedMs=$elapsedMs; timeout predicates: $it" },
                    onFailure = {
                        "; elapsedMs=$elapsedMs; timeout predicate diagnostics unavailable: " +
                            "${it.javaClass.simpleName}: ${it.message.orEmpty()}"
                    },
                )
        }.orEmpty()
        error(
            "Timed out waiting for real DownloadWorker $workId durable producer condition; " +
                "last WorkInfo state=$lastState$diagnosticSuffix",
        )
    }

    private fun committedHistoryFinalizationTimeoutDiagnostics(
        context: android.content.Context,
        historyId: Long,
        downloadId: Long,
        outputPaths: AtomicInteger,
        finalizationFailures: AtomicInteger,
        exactExecution: java.util.concurrent.atomic.AtomicReference<String>,
        workInfoState: WorkInfo.State?,
        elapsedMs: Long,
    ): String {
        val executionId = exactExecution.get()
        val historyObservation = runCatching { db.historyDao.getNullableItem(historyId) }
        val downloadObservation = runCatching { db.downloadDao.getNullableDownloadById(downloadId) }
        val producerObservation = executionId?.let {
            runCatching { producerRecoveryRecord(context, downloadId, it) }
        }
        val executionOwner = DownloadWorkerExecutionOwners.ownerOf(downloadId)
        val processOwner = DownloadWorkerProcessOwners.ownerOf(downloadId)
        val genericCarrierObservation = runCatching {
            DownloadExecutionRecovery.pendingDownloadIds(context).contains(downloadId)
        }
        val dispositionObservation = runCatching {
            DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId)
        }
        val phaseObservation = runCatching {
            DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId)
        }
        val history = historyObservation.getOrNull()
        val download = downloadObservation.getOrNull()
        val producer = producerObservation?.getOrNull()
        val historyMatches = history?.title == "replacement" && history.downloadId == downloadId
        val producerMatches = producer?.let {
            it.downloadId == downloadId &&
                it.executionId == executionId &&
                it.phase == DownloadProducerRecovery.Phase.COMPLETE
        } == true

        return listOf(
            "elapsedMs=$elapsedMs",
            "outputPaths={expected=1,actual=${outputPaths.get()},matches=${outputPaths.get() == 1}}",
            "finalizationHook={expected=1,actual=${finalizationFailures.get()},matches=${finalizationFailures.get() == 1}}",
            "replacementHistory={matches=$historyMatches,present=${history != null},id=${history?.id},title=${history?.title},downloadId=${history?.downloadId},readError=${historyObservation.exceptionOrNull()?.javaClass?.simpleName}}",
            "downloadRow={expected=absent,absent=${download == null},id=${download?.id},status=${download?.status},executionId=${download?.executionId},readError=${downloadObservation.exceptionOrNull()?.javaClass?.simpleName}}",
            "producerRecovery={matches=$producerMatches,expectedExecutionId=$executionId,downloadId=${producer?.downloadId},executionId=${producer?.executionId},generationId=${producer?.generationId},sequence=${producer?.sequence},phase=${producer?.phase},readError=${producerObservation?.exceptionOrNull()?.javaClass?.simpleName}}",
            "executionOwner={expected=null,actual=$executionOwner,matches=${executionOwner == null}}",
            "processOwner={expected=null,actual=$processOwner,matches=${processOwner == null}}",
            "genericRecoveryCarrier={expected=absent,targetPresent=${genericCarrierObservation.getOrNull()},disposition=${dispositionObservation.getOrNull()},phase=${phaseObservation.getOrNull()},readError=${genericCarrierObservation.exceptionOrNull()?.javaClass?.simpleName}}",
            "workInfo={state=$workInfoState,observed=${workInfoState != null},unfinished=${workInfoState?.isFinished == false}}",
        ).joinToString("; ")
    }

    private suspend fun cancelAndAwaitWorker(
        context: android.content.Context,
        workId: UUID,
    ) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelWorkById(workId).result.get(10, TimeUnit.SECONDS)
        val finished = withContext(Dispatchers.IO) {
            repeat(240) {
                val workInfo = runCatching {
                    workManager.getWorkInfoById(workId).get(1, TimeUnit.SECONDS)
                }.getOrNull()
                if (workInfo?.state?.isFinished == true) return@withContext workInfo
                Thread.sleep(250L)
            }
            error("Timed out joining canceled real DownloadWorker $workId")
        }
        assertTrue(finished.state.isFinished)
    }

    private fun producerRecoveryRecord(
        context: android.content.Context,
        downloadId: Long,
        executionId: String,
    ): DownloadProducerRecovery.Record? = when (val discovery = DownloadProducerRecovery.discover(context)) {
        is DownloadProducerRecovery.DiscoveryResult.Healthy -> {
            val exact = discovery.records.filter {
                it.downloadId == downloadId && it.executionId == executionId
            }
            check(exact.size <= 1) {
                "Multiple producer recovery generations claim $downloadId/$executionId"
            }
            exact.singleOrNull()
        }
        is DownloadProducerRecovery.DiscoveryResult.Unavailable -> error(
            "Producer recovery namespace unavailable: ${discovery.reason}",
        )
        is DownloadProducerRecovery.DiscoveryResult.Opaque -> error(
            "Producer recovery namespace contains opaque records: ${discovery.opaqueFiles}",
        )
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
