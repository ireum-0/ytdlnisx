package com.ireum.ytdl.database

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.DownloadPrimarySuccessAuthorityRepository
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.VideoFileQualityState
import com.ireum.ytdl.util.VideoMediaQuality
import com.ireum.ytdl.util.runtime.BundledFfmpegRuntime
import com.ireum.ytdl.util.runtime.BundledFfmpegRuntimeResolution
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.DownloadProducerRecovery
import com.ireum.ytdl.work.DownloadOutputProvenance
import com.ireum.ytdl.work.DownloadWorker
import com.ireum.ytdl.work.DownloadWorkerEffectTestHooks
import com.ireum.ytdl.work.DownloadWorkerExecutionOwners
import com.ireum.ytdl.work.DownloadWorkerProcessOwners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Selects the original method through its runner, including its real fixture lifecycle. */
@RunWith(AndroidJUnit4::class)
class DownloadOutputCleanupMethodVerificationTest {
    @Test
    fun exactUnavailableFfmpegMethod() {
        val method = "unavailableFfmpegRuntimeFailsHardSubBeforePublishingOutput"
        val executed = mutableListOf<String>()
        var assumptionFailures = 0
        val core = org.junit.runner.JUnitCore()
        core.addListener(object : org.junit.runner.notification.RunListener() {
            override fun testStarted(description: org.junit.runner.Description) {
                executed.add("${description.className}#${description.methodName}")
            }

            override fun testAssumptionFailure(failure: org.junit.runner.notification.Failure) {
                assumptionFailures++
            }
        })
        val result = core.run(org.junit.runner.Request.method(DownloadOutputProductionWiringTest::class.java, method))
        android.util.Log.i(
            "OutputMethodVerification",
            "method=$method executed=$executed run=${result.runCount} ignored=${result.ignoreCount} " +
                "assumptions=$assumptionFailures failures=${result.failureCount}",
        )
        result.failures.firstOrNull()?.let { first ->
            result.failures.drop(1).forEach { first.exception.addSuppressed(it.exception) }
            throw first.exception
        }
        assertEquals(listOf("${DownloadOutputProductionWiringTest::class.java.name}#$method"), executed)
        assertEquals(1, result.runCount)
        assertEquals(0, result.ignoreCount)
        assertEquals(0, assumptionFailures)
        assertEquals(0, result.failureCount)
    }
}

/** Diagnostic selection preserves the original method's runner and fixture. */
@RunWith(AndroidJUnit4::class)
class DownloadOutputVerifiedQualityMethodDiagnosticTest {
    @Test
    fun exactVerifiedQualityMethod() {
        val method = "realWorkerVerifiedQualityCannotUseAmbientHighQualityForReplacement"
        val executed = mutableListOf<String>()
        var assumptionFailures = 0
        val core = org.junit.runner.JUnitCore()
        core.addListener(object : org.junit.runner.notification.RunListener() {
            override fun testStarted(description: org.junit.runner.Description) {
                executed.add("${description.className}#${description.methodName}")
            }

            override fun testAssumptionFailure(failure: org.junit.runner.notification.Failure) {
                assumptionFailures++
            }
        })
        val result = core.run(org.junit.runner.Request.method(DownloadOutputProductionWiringTest::class.java, method))
        val summary = "VerifiedQualityMethodDiagnostic method=$method executed=$executed run=${result.runCount} " +
            "ignored=${result.ignoreCount} assumptions=$assumptionFailures failures=${result.failureCount}"
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply { putString("stream", "\n$summary\n") },
        )
        println(summary)
        result.failures.firstOrNull()?.let { first ->
            result.failures.drop(1).forEach { first.exception.addSuppressed(it.exception) }
            throw first.exception
        }
        assertEquals(listOf("${DownloadOutputProductionWiringTest::class.java.name}#$method"), executed)
        assertEquals(1, result.runCount)
        assertEquals(0, result.ignoreCount)
        assertEquals(0, assumptionFailures)
        assertEquals(0, result.failureCount)
    }
}

/** Diagnostic selection preserves the original method's runner and fixture. */
@RunWith(AndroidJUnit4::class)
class DownloadOutputAmbientMethodDiagnosticTest {
    @Test
    fun exactAmbientMethod() {
        val method = "realWorkerRejectsAmbientRecentAndSameNameFiles"
        val executed = mutableListOf<String>()
        var assumptionFailures = 0
        val core = org.junit.runner.JUnitCore()
        core.addListener(object : org.junit.runner.notification.RunListener() {
            override fun testStarted(description: org.junit.runner.Description) {
                executed.add("${description.className}#${description.methodName}")
            }

            override fun testAssumptionFailure(failure: org.junit.runner.notification.Failure) {
                assumptionFailures++
            }
        })
        val result = core.run(org.junit.runner.Request.method(DownloadOutputProductionWiringTest::class.java, method))
        val summary = "AmbientMethodDiagnostic method=$method executed=$executed run=${result.runCount} " +
            "ignored=${result.ignoreCount} assumptions=$assumptionFailures failures=${result.failureCount}"
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply { putString("stream", "\n$summary\n") },
        )
        println(summary)
        result.failures.firstOrNull()?.let { first ->
            result.failures.drop(1).forEach { first.exception.addSuppressed(it.exception) }
            throw first.exception
        }
        assertEquals(listOf("${DownloadOutputProductionWiringTest::class.java.name}#$method"), executed)
        assertEquals(1, result.runCount)
        assertEquals(0, result.ignoreCount)
        assertEquals(0, assumptionFailures)
        assertEquals(0, result.failureCount)
    }
}

private val outputWiringDownloadIds = AtomicLong(
    System.currentTimeMillis().coerceAtLeast(10_000_000L),
)

@RunWith(AndroidJUnit4::class)
class DownloadOutputProductionWiringTest {
    private lateinit var db: DBManager
    private lateinit var testRoot: File

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        cancelStaleRealWorkerRequests(context)
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(context)
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        clearOutputHooks()
        testRoot = File(
            requireNotNull(context.getExternalFilesDir(null)),
            "bug-output-${UUID.randomUUID()}",
        )
        assertTrue(testRoot.mkdirs())
        db = Room.inMemoryDatabaseBuilder(
            context,
            DBManager::class.java,
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        cancelStaleRealWorkerRequests(ApplicationProvider.getApplicationContext())
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(ApplicationProvider.getApplicationContext())
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        clearOutputHooks()
        db.close()
        testRoot.deleteRecursively()
    }

    @Test
    fun realWorkerDirectNoCachePublishesOnlyExactCurrentOutput() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "direct").apply { mkdirs() }
            val unrelated = File(destination, "unrelated.m4a").apply { writeBytes(byteArrayOf(7, 7, 7)) }
            var currentSource: File? = null
            var currentOutputDirectory: File? = null
            db.downloadDao.insertRaw(download(downloadId, destination.absolutePath))
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    currentOutputDirectory = outputDirectory
                    File(destination, "created-during-attempt.m4a").writeBytes(byteArrayOf(2, 2, 2))
                    currentSource = File(outputDirectory, "current.m4a").apply {
                        writeBytes(byteArrayOf(1, 2, 3))
                    }
                    "[download] Destination: '${requireNotNull(currentSource).absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            val ownedOutputDirectory = requireNotNull(currentOutputDirectory)
            assertEquals(".ytdlnisx-output", ownedOutputDirectory.parentFile?.name)
            assertEquals(destination.canonicalFile, ownedOutputDirectory.parentFile?.parentFile?.canonicalFile)
            val history = db.historyDao.getItemByDownloadId(downloadId)
            assertNotNull(history)
            val persisted = requireNotNull(history)
            assertEquals(1, persisted.downloadPath.size)
            assertTrue(persisted.downloadPath.single().endsWith("current.m4a"))
            assertFalse(persisted.downloadPath.any { it.endsWith("unrelated.m4a") })
            assertTrue(unrelated.exists())
            assertTrue(File(destination, "created-during-attempt.m4a").exists())
            assertFalse(currentSource!!.exists())
            assertFalse(File(ownedOutputDirectory, ".ytdlnisx-owner").exists())
            assertFalse(ownedOutputDirectory.exists())
            assertFalse(File(destination, ".ytdlnisx-output").exists())
        }
    }

    @Test
    fun realWorkerRejectsAmbientRecentAndSameNameFiles() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "ambient").apply { mkdirs() }
            val recent = File(destination, "recent.m4a").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val sameName = File(destination, "same-name.m4a").apply { writeBytes(byteArrayOf(8, 9, 0)) }
            db.downloadDao.insertRaw(download(downloadId, destination.absolutePath))
            val context = ApplicationProvider.getApplicationContext<Context>()
            val hookCandidate = AtomicReference<Long?>()
            fun snapshot(boundary: String): String {
                fun read(label: String, observation: () -> Any?): String =
                    "$label=" + runCatching { observation().toString() }
                        .getOrElse { "OBSERVATION_FAILED:${it::class.java.simpleName}" }
                return listOf(
                    "AmbientDiagnostic boundary=$boundary download=$downloadId hookCandidate=${hookCandidate.get()}",
                    read("row") { db.downloadDao.getNullableDownloadById(downloadId)?.let { "${it.id}/${it.status}/${it.operationId}/${it.executionId}/${it.lastIssueCode}/${it.lastIssueStage}" } },
                    read("history") { db.historyDao.getItemByDownloadId(downloadId)?.let { "${it.id}/${it.downloadId}/${it.downloadPath}" } },
                    read("executionOwner") { DownloadWorkerExecutionOwners.ownerOf(downloadId) },
                    read("processOwner") { DownloadWorkerProcessOwners.ownerOf(downloadId) },
                    read("genericPending") { DownloadExecutionRecovery.hasPendingRecovery(context, downloadId) },
                    read("genericDisposition") { DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId) },
                    read("genericPhase") { DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId) },
                    read("workerRecoveryResponsibility") { DownloadExecutionRecovery.hasRecoveryResponsibility(context, db) },
                    read("producer") { DownloadProducerRecovery.discover(context).let { "${it::class.java.simpleName}:${it.records.filter { record -> record.downloadId == downloadId }}" } },
                    read("producerBlocking") { DownloadProducerRecovery.hasBlockingForAdmission(context, downloadId) },
                    read("nativeRegistered") { DownloadWorker.hasAnyRegisteredNativeProcess(downloadId) },
                    read("markerDebt") { YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(downloadId) },
                    read("preferences") { PreferenceManager.getDefaultSharedPreferences(context).let { "scheduler=${it.getBoolean("use_scheduler", false)} concurrent=${it.getInt("concurrent_downloads", 1)} cache=${it.getBoolean("cache_downloads", true)}" } },
                    "ambientRecentExists=${recent.exists()} ambientSameNameExists=${sameName.exists()}",
                ).joinToString("\n")
            }
            fun emit(text: String) {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(
                    0, android.os.Bundle().apply { putString("stream", "\n$text\n") },
                )
            }
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, _ ->
                if (candidateId != downloadId) {
                    null
                } else {
                    hookCandidate.set(candidateId)
                    emit(snapshot("YTDLP_ENTRY"))
                    "[download] Destination: '${sameName.absolutePath}'\n" +
                        hookCandidate.set(candidateId)
                    emit(snapshot("YTDLP_ENTRY"))
                    "[download] Destination: '${recent.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(context, downloadId) { workInfo, cleanupExecutionId, requestId ->
                "request=$requestId workState=${workInfo?.state} attempt=${workInfo?.runAttemptCount} cleanupExecution=$cleanupExecutionId\n" + snapshot("OBSERVER")
            }

            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(recent.exists())
            assertTrue(sameName.exists())
        }
    }

    @Test
    fun realWorkerDirectBaselineFailureDoesNotPromoteAmbientOutput() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "baseline-failure").apply { mkdirs() }
            val current = File(destination, "ambient.m4a").apply { writeBytes(byteArrayOf(1, 1, 1)) }
            var currentOutputDirectory: File? = null
            db.downloadDao.insertRaw(download(downloadId, destination.absolutePath))
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.outputBaselineReaderForTesting = { directory ->
                if (directory.parentFile?.name == ".ytdlnisx-output") {
                    DownloadOutputProvenance.BaselineSnapshot.Failed("injected direct baseline failure")
                } else {
                    DownloadOutputProvenance.BaselineSnapshot.Complete(emptySet())
                }
            }
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    currentOutputDirectory = outputDirectory
                    val reported = File(outputDirectory, "reported.m4a").apply {
                        writeBytes(byteArrayOf(2, 2, 2))
                    }
                    "[download] Destination: '${reported.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(current.exists())
            assertTrue(
                File(
                    requireNotNull(currentOutputDirectory),
                    ".ytdlnisx-owner",
                ).exists()
            )
        }
    }

    @Test
    fun realWorkerVerifiedQualityCannotUseAmbientHighQualityForReplacement() = runBlocking {
        withDiagnosticAdmissionPreferences {
            val requestedDownloadId = outputWiringDownloadIds.getAndIncrement()
            val sourceUrl = "https://example.com/$requestedDownloadId"
            val destination = File(testRoot, "verified-quality-negative").apply { mkdirs() }
            val oldMedia = File(destination, "old.mp4").apply { writeBytes(byteArrayOf(9, 9, 9)) }
            val ambientHighQuality = File(destination, "requested.mp4").apply {
                writeBytes(byteArrayOf(8, 8, 8))
            }
            val historyId = db.historyDao.insertAndGetIdRaw(
                history(oldMedia.absolutePath).copy(
                    url = sourceUrl,
                    type = DownloadType.video,
                    format = Format(container = "mp4", format_note = "720p"),
                    downloadId = 0L,
                )
            )
            val ledgerRepository = LowQualityRedownloadRepository(db)
            val operation = ledgerRepository.createOrReconnect()
            ledgerRepository.checkpointScan(
                operationId = operation.operationId,
                historyId = historyId,
                candidate = LowQualityRedownloadItem(
                    operationId = operation.operationId,
                    historyId = historyId,
                    intendedSourceUrl = sourceUrl,
                    intendedType = DownloadType.video.name,
                    requestedHeight = 720,
                    expectedHeight = 720,
                    selected = true,
                    itemState = LowQualityRedownloadItemState.CHECKING.name,
                ),
                failed = false,
            )
            val downloadId = requireNotNull(ledgerRepository.linkDownloadAtomically(
                operationId = operation.operationId,
                historyId = historyId,
                downloadItem = download(
                    id = requestedDownloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.video,
                    formatNote = "720p",
                    container = "mp4",
                    videoPreferences = VideoPreferences(embedSubs = false),
                    playlistUrl = HistoryRedownloadMarker.quality(historyId, 720),
                ),
            ))
            assertEquals(requestedDownloadId, downloadId)
            val linkedItem = requireNotNull(db.lowQualityRedownloadDao.getItemByDownloadId(downloadId))
            assertEquals(operation.operationId, linkedItem.operationId)
            assertEquals(historyId, linkedItem.historyId)
            assertEquals(sourceUrl, linkedItem.intendedSourceUrl)
            assertEquals(DownloadType.video.name, linkedItem.intendedType)
            assertEquals(LowQualityRedownloadItemState.QUEUED, linkedItem.stateValue)
            assertEquals("RUNNING", requireNotNull(ledgerRepository.getOperation(operation.operationId)).state)
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            var stagedLowQuality: File? = null
            val ytdlpEntered = AtomicBoolean(false)
            val ytdlpExited = AtomicBoolean(false)
            val probeEntered = AtomicBoolean(false)
            val probeExited = AtomicBoolean(false)
            val probePaths = AtomicReference<List<String>>(emptyList())
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    ytdlpEntered.set(true)
                    try {
                        stagedLowQuality = File(outputDirectory, "requested.mp4").apply {
                            writeBytes(byteArrayOf(1, 2, 3))
                        }
                        "${DownloadOutputProvenance.PRINT_MARKER}'${requireNotNull(stagedLowQuality).absolutePath}'"
                    } finally {
                        ytdlpExited.set(true)
                    }
                }
            }
            DownloadWorkerEffectTestHooks.videoQualityProbeForTesting = { paths ->
                probeEntered.set(true)
                probePaths.set(paths.toList())
                try {
                    assertFalse(paths.any { it == ambientHighQuality.canonicalPath })
                    assertEquals(listOf(File(destination, "requested (1).mp4").canonicalPath), paths)
                    VideoMediaQuality(
                        state = VideoFileQualityState.READY,
                        width = 640,
                        height = 360,
                        hasAudio = true,
                        path = paths.first(),
                    )
                } finally {
                    probeExited.set(true)
                }
            }

            val context = ApplicationProvider.getApplicationContext<Context>()
            enqueueAndAwaitDownloadWorker(context, downloadId) { workInfo, cleanupExecutionId, requestId ->
                fun read(label: String, observation: () -> Any?): String =
                    "$label=" + runCatching { observation().toString() }
                        .getOrElse { "OBSERVATION_FAILED:${it::class.java.simpleName}" }
                val row = runCatching { db.downloadDao.getNullableDownloadById(downloadId) }
                val executionId = row.getOrNull()?.executionId?.takeIf { it.isNotBlank() }
                    ?: cleanupExecutionId
                val queueWindow = System.currentTimeMillis() + 6000
                val queuedIds = runCatching {
                    db.downloadDao.getQueuedScheduledDownloadsUntil(queueWindow).first().map { it.id }
                }
                listOf(
                    "VerifiedQualityDiagnostic download=$downloadId ytdlpEntered=${ytdlpEntered.get()} " +
                        "ytdlpExited=${ytdlpExited.get()} probeEntered=${probeEntered.get()} " +
                        "probeExited=${probeExited.get()} probePaths=${probePaths.get()} " +
                        "cleanupExecution=$cleanupExecutionId workRequest=$requestId workState=${workInfo?.state} " +
                        "runAttemptCount=${workInfo?.runAttemptCount}",
                    "queueWindow=$queueWindow queueIds=${queuedIds.getOrNull()} " +
                        "targetInQueue=${queuedIds.getOrNull()?.contains(downloadId)} " +
                        "queueReadFailure=${queuedIds.exceptionOrNull()?.javaClass?.simpleName}",
                    "rowReadFailure=${row.exceptionOrNull()?.javaClass?.simpleName} " +
                        "row=${row.getOrNull()?.let { "${it.status}/${it.executionId}/${it.operationId}/${it.lastIssueCode}/${it.lastIssueStage}" }}",
                    read("executionOwner") { DownloadWorkerExecutionOwners.ownerOf(downloadId) },
                    read("processOwner") { DownloadWorkerProcessOwners.ownerOf(downloadId) },
                    read("genericPending") { downloadId in DownloadExecutionRecovery.pendingDownloadIds(context) },
                    read("targetPendingRecovery") { DownloadExecutionRecovery.hasPendingRecovery(context, downloadId) },
                    read("workerRecoveryResponsibility") { DownloadExecutionRecovery.hasRecoveryResponsibility(context, db) },
                    read("genericDisposition") { DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId) },
                    read("genericPhase") { DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId) },
                    read("producer") {
                        val discovered = DownloadProducerRecovery.discover(context)
                        "${discovered::class.java.simpleName}:${discovered.records.filter { it.downloadId == downloadId }}"
                    },
                    read("producerBlocking") { DownloadProducerRecovery.hasBlockingForAdmission(context, downloadId) },
                    read("primaryCommitted") { DownloadPrimarySuccessAuthorityRepository.hasCommittedForDownloadBlocking(db, downloadId) },
                    read("primaryAuthorities") { db.downloadPrimarySuccessAuthorityDao.getByDownloadBlocking(downloadId) },
                    read("processClaimable") { DownloadWorkerProcessOwners.canClaimNewExecution(downloadId) },
                    read("anyRegisteredNative") { DownloadWorker.hasAnyRegisteredNativeProcess(downloadId) },
                    read("anyMarkerDebt") { YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(downloadId) },
                    read("lowQualityCancellation") { db.lowQualityRedownloadDao.hasCancellationRequestedByDownload(downloadId) },
                    "lowQualityItem=" + runCatching { db.lowQualityRedownloadDao.getItemByDownloadId(downloadId).toString() }
                        .getOrElse { "OBSERVATION_FAILED:${it::class.java.simpleName}" },
                    read("runningRows") { db.downloadDao.getActiveAndPostProcessingDownloadsList().map { "${it.id}/${it.status}/${it.executionId}" } },
                    read("preferences") {
                        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
                        "scheduler=${preferences.getBoolean("use_scheduler", false)} concurrent=${preferences.getInt("concurrent_downloads", 1)} " +
                            "cache=${preferences.getBoolean("cache_downloads", true)}"
                    },
                    read("nativeRegistered") { executionId?.let { DownloadWorker.hasRegisteredNativeProcess(downloadId, it) } ?: "EXECUTION_UNKNOWN" },
                    read("markerDebt") { executionId?.let { YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(downloadId, it) } ?: "EXECUTION_UNKNOWN" },
                    read("targetHistory") { db.historyDao.getNullableItem(historyId)?.let { "${it.id}/${it.downloadId}/${it.downloadPath}" } },
                    read("currentHistory") { db.historyDao.getItemByDownloadId(downloadId)?.let { "${it.id}/${it.downloadId}/${it.downloadPath}" } },
                    read("oldMedia") { "${oldMedia.absolutePath}/exists=${oldMedia.exists()}" },
                    read("ambientHighQuality") { "${ambientHighQuality.absolutePath}/exists=${ambientHighQuality.exists()}" },
                    read("stagedLowQuality") { stagedLowQuality?.let { "${it.absolutePath}/exists=${it.exists()}" } },
                ).joinToString("\n")
            }

            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                oldMedia.absolutePath,
                requireNotNull(db.historyDao.getNullableItem(historyId)).downloadPath.single(),
            )
            assertTrue(oldMedia.exists())
            assertTrue(ambientHighQuality.exists())
        }
    }

    @Test
    fun realWorkerVerifiedQualityAcceptsAuthoritativeCurrentOutput() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "verified-quality-positive").apply { mkdirs() }
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.video,
                    formatNote = "720p",
                    container = "mp4",
                    videoPreferences = VideoPreferences(embedSubs = false),
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            var stagedCurrent: File? = null
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    stagedCurrent = File(outputDirectory, "verified.mp4").apply {
                        writeBytes(byteArrayOf(4, 5, 6))
                    }
                    "${DownloadOutputProvenance.PRINT_MARKER}'${requireNotNull(stagedCurrent).absolutePath}'"
                }
            }
            DownloadWorkerEffectTestHooks.videoQualityProbeForTesting = { paths ->
                assertEquals(listOf(requireNotNull(stagedCurrent).canonicalPath), paths)
                VideoMediaQuality(
                    state = VideoFileQualityState.READY,
                    width = 1280,
                    height = 720,
                    hasAudio = true,
                    path = paths.single(),
                )
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            val history = requireNotNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(1, history.downloadPath.size)
            assertTrue(history.downloadPath.single().endsWith("verified.mp4"))
            assertFalse(requireNotNull(stagedCurrent).exists())
        }
    }

    @Test
    fun realWorkerUsesExplicitCustomCommandPathAsFinalDestination() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val configuredDestination = File(testRoot, "configured").apply { mkdirs() }
            val effectiveDestination = File(testRoot, "effective").apply { mkdirs() }
            val effectiveTempDestination = File(testRoot, "effective-temp").apply { mkdirs() }
            val commandPath = effectiveDestination.absolutePath.replace('\\', '/')
            val tempPath = effectiveTempDestination.absolutePath.replace('\\', '/')
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = configuredDestination.absolutePath,
                    type = DownloadType.command,
                    formatNote = "--paths home:\"$commandPath\" --paths temp:\"$tempPath\" --no-playlist",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            var currentOutputDirectory: File? = null
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    currentOutputDirectory = outputDirectory
                    val reported = File(outputDirectory, "command-output.m4a").apply {
                        writeBytes(byteArrayOf(3, 3, 3))
                    }
                    "${DownloadOutputProvenance.PRINT_MARKER}'${reported.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            val history = requireNotNull(db.historyDao.getItemByDownloadId(downloadId))
            assertTrue(history.downloadPath.single().startsWith(effectiveDestination.canonicalPath))
            assertTrue(history.downloadPath.single().endsWith("command-output.m4a"))
            assertTrue(configuredDestination.listFiles().orEmpty().none { it.name == "command-output.m4a" })
            val ownedOutputDirectory = requireNotNull(currentOutputDirectory)
            assertEquals(
                effectiveTempDestination.canonicalFile,
                ownedOutputDirectory.parentFile?.parentFile?.canonicalFile,
            )
            assertFalse(ownedOutputDirectory.exists())
        }
    }

    @Test
    fun realWorkerIgnoresFixedArityMetadataPathLookingValueForPublication() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "metadata-arity-command").apply { mkdirs() }
            val metadataReplacement = File(testRoot, "metadata-replacement")
            val metadataReplacementPath = metadataReplacement.absolutePath.replace('\\', '/')
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.command,
                    formatNote = "--replace-in-metadata title -P \"$metadataReplacementPath\" --no-playlist",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            val nativeBoundaryReached = AtomicBoolean(false)
            var ownedOutputDirectory: File? = null
            var stagedOutput: File? = null
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == downloadId) nativeBoundaryReached.set(true)
            }
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    ownedOutputDirectory = outputDirectory.canonicalFile
                    stagedOutput = File(outputDirectory, "metadata-safe.m4a").apply {
                        writeBytes(byteArrayOf(6, 7, 8))
                    }
                    "${DownloadOutputProvenance.PRINT_MARKER}'${requireNotNull(stagedOutput).absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertTrue(nativeBoundaryReached.get())
            val staging = requireNotNull(ownedOutputDirectory)
            val history = requireNotNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(1, history.downloadPath.size)
            assertTrue(history.downloadPath.single().startsWith(destination.canonicalPath))
            assertTrue(history.downloadPath.single().endsWith("metadata-safe.m4a"))
            assertFalse(requireNotNull(stagedOutput).exists())
            assertFalse(
                "staging remnants=${staging.listFiles().orEmpty().map { it.relativeTo(staging).path }}",
                staging.exists(),
            )
            assertFalse(metadataReplacement.exists())
            assertTrue(destination.listFiles().orEmpty().any { it.name == "metadata-safe.m4a" })
        }
    }

    @Test
    fun realWorkerBurnsHardSubBeforeDirectPublication() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "hard-sub-direct").apply { mkdirs() }
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.video,
                    formatNote = "best",
                    container = "mp4",
                    videoPreferences = VideoPreferences(embedSubs = true),
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            val nativeBoundaryReached = AtomicBoolean(false)
            val burnReached = AtomicBoolean(false)
            var ownedOutputDirectory: File? = null
            var stagedOutput: File? = null
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == downloadId) nativeBoundaryReached.set(true)
            }
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    ownedOutputDirectory = outputDirectory.canonicalFile
                    stagedOutput = File(outputDirectory, "hard-sub.mp4").apply {
                        writeBytes(byteArrayOf(1, 2, 3, 4))
                    }
                    "${DownloadOutputProvenance.PRINT_MARKER}'${requireNotNull(stagedOutput).absolutePath}'"
                }
            }
            DownloadWorkerEffectTestHooks.hardSubBurnForTesting = { paths ->
                burnReached.set(true)
                val staging = requireNotNull(ownedOutputDirectory)
                assertTrue(paths.isNotEmpty())
                assertTrue(paths.all { File(it).canonicalPath.startsWith(staging.canonicalPath + File.separator) })
                assertTrue(destination.listFiles().orEmpty().none { it.isFile })
                true
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertTrue(nativeBoundaryReached.get())
            assertTrue(burnReached.get())
            val history = requireNotNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(1, history.downloadPath.size)
            assertTrue(history.downloadPath.single().startsWith(destination.canonicalPath))
            assertTrue(history.downloadPath.single().endsWith("hard-sub.mp4"))
            assertFalse(requireNotNull(stagedOutput).exists())
            assertFalse(requireNotNull(ownedOutputDirectory).exists())
            assertTrue(destination.listFiles().orEmpty().any { it.name == "hard-sub.mp4" })
        }
    }

    @Test
    fun realWorkerHardSubFailureDoesNotPublishDirectOutput() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "hard-sub-failure").apply { mkdirs() }
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.video,
                    formatNote = "best",
                    container = "mp4",
                    videoPreferences = VideoPreferences(embedSubs = true),
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            val nativeBoundaryReached = AtomicBoolean(false)
            val burnReached = AtomicBoolean(false)
            var stagedOutput: File? = null
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == downloadId) nativeBoundaryReached.set(true)
            }
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    stagedOutput = File(outputDirectory, "hard-sub-failure.mp4").apply {
                        writeBytes(byteArrayOf(5, 6, 7, 8))
                    }
                    "${DownloadOutputProvenance.PRINT_MARKER}'${requireNotNull(stagedOutput).absolutePath}'"
                }
            }
            DownloadWorkerEffectTestHooks.hardSubBurnForTesting = {
                burnReached.set(true)
                false
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertTrue(nativeBoundaryReached.get())
            assertTrue(burnReached.get())
            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(destination.listFiles().orEmpty().none { it.isFile })
            assertFalse(requireNotNull(stagedOutput).exists())
        }
    }

    @Test
    fun unavailableFfmpegRuntimeFailsHardSubBeforePublishingOutput() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "hard-sub-runtime-unavailable").apply { mkdirs() }
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.video,
                    formatNote = "best",
                    container = "mp4",
                    videoPreferences = VideoPreferences(embedSubs = true),
                ),
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            BundledFfmpegRuntime.resolutionForTesting = {
                BundledFfmpegRuntimeResolution.Unavailable("test runtime is incomplete")
            }
            var stagedOutput: File? = null
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    stagedOutput = File(outputDirectory, "hard-sub-unavailable.mp4").apply {
                        writeBytes(byteArrayOf(1, 2, 3, 4))
                    }
                    "${DownloadOutputProvenance.PRINT_MARKER}'${requireNotNull(stagedOutput).absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            val context = ApplicationProvider.getApplicationContext<Context>()
            val failedRow = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
            assertTrue(failedRow.executionId.isNotBlank())
            assertFalse(downloadId in DownloadExecutionRecovery.pendingDownloadIds(context))
            assertNull(DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId))
            assertNull(DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId))
            val producerDiscovery = DownloadProducerRecovery.discover(context)
            assertTrue(producerDiscovery is DownloadProducerRecovery.DiscoveryResult.Healthy)
            val producers = producerDiscovery.records.filter { it.downloadId == downloadId }
            producers.forEach { producer ->
                assertEquals(failedRow.operationId, producer.operationId)
                assertEquals(failedRow.executionId, producer.executionId)
                assertTrue(producer.generationId.isNotBlank())
                assertTrue(producer.phase in setOf(
                    DownloadProducerRecovery.Phase.COMPLETE,
                    DownloadProducerRecovery.Phase.FINALIZED,
                ))
            }
            assertFalse(DownloadProducerRecovery.hasBlockingForAdmission(context, downloadId))
            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(destination.listFiles().orEmpty().none { it.isFile })
            assertFalse(requireNotNull(stagedOutput).exists())
        }
    }

    @Test
    fun unavailableFfmpegRuntimeDoesNotBlockIndependentVideoDownload() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "video-without-ffmpeg-runtime").apply { mkdirs() }
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.video,
                    formatNote = "best",
                    container = "mp4",
                    videoPreferences = VideoPreferences(embedSubs = false, addChapters = false),
                ),
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            BundledFfmpegRuntime.resolutionForTesting = {
                BundledFfmpegRuntimeResolution.Unavailable("test runtime is incomplete")
            }
            var stagedOutput: File? = null
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    stagedOutput = File(outputDirectory, "independent-video.mp4").apply {
                        writeBytes(byteArrayOf(5, 6, 7, 8))
                    }
                    "${DownloadOutputProvenance.PRINT_MARKER}'${requireNotNull(stagedOutput).absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            val history = requireNotNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(1, history.downloadPath.size)
            assertTrue(history.downloadPath.single().startsWith(destination.canonicalPath))
            assertTrue(File(history.downloadPath.single()).isFile)
            assertFalse(requireNotNull(stagedOutput).exists())
        }
    }

    @Test
    fun realWorkerIgnoresShlexProtectedPathLookingValueForPublication() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "shlex-protected-command").apply { mkdirs() }
            val falseDestination = File(testRoot, "shlex-false-destination").apply { mkdirs() }
            val falseDestinationPath = falseDestination.absolutePath.replace('\\', '/')
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.command,
                    formatNote = "\"\\-P\" $falseDestinationPath --no-playlist",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            val nativeBoundaryReached = AtomicBoolean(false)
            var ownedOutputDirectory: File? = null
            var stagedOutput: File? = null
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == downloadId) nativeBoundaryReached.set(true)
            }
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    ownedOutputDirectory = outputDirectory.canonicalFile
                    stagedOutput = File(outputDirectory, "shlex-safe.m4a").apply {
                        writeBytes(byteArrayOf(4, 5, 6))
                    }
                    "${DownloadOutputProvenance.PRINT_MARKER}'${requireNotNull(stagedOutput).absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertTrue(nativeBoundaryReached.get())
            val staging = requireNotNull(ownedOutputDirectory)
            assertEquals(downloadId.toString(), staging.name)
            assertEquals(
                File(FileUtil.getCachePath(ApplicationProvider.getApplicationContext())).canonicalFile,
                staging.parentFile?.canonicalFile,
            )
            val history = requireNotNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(1, history.downloadPath.size)
            assertTrue(history.downloadPath.single().startsWith(destination.canonicalPath))
            assertTrue(history.downloadPath.single().endsWith("shlex-safe.m4a"))
            assertFalse(history.downloadPath.single().startsWith(falseDestination.canonicalPath))
            assertFalse(requireNotNull(stagedOutput).exists())
            assertFalse(staging.exists())
            assertTrue(falseDestination.listFiles().orEmpty().isEmpty())
            assertTrue(destination.listFiles().orEmpty().any { it.name == "shlex-safe.m4a" })
        }
    }

    @Test
    fun realWorkerRejectsEnvironmentExpandableCommandPathBeforeNativeBoundary() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "environment-expandable-command").apply { mkdirs() }
            val authoredPathRoot = File(testRoot, "environment-expandable-path").apply { mkdirs() }
            val authoredPath = authoredPathRoot.absolutePath.replace('\\', '/') + "/\$HOME"
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.command,
                    formatNote = "--paths=home:$authoredPath --no-playlist",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            val nativeBoundaryReached = AtomicBoolean(false)
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == downloadId) nativeBoundaryReached.set(true)
            }
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, _ ->
                if (candidateId != downloadId) null else {
                    error("environment-expandable path must be rejected before native output")
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertFalse(nativeBoundaryReached.get())
            assertFalse(File(authoredPathRoot, "\$HOME").exists())
            assertTrue(authoredPathRoot.walkTopDown().none { it.isFile })
            assertTrue(destination.listFiles().orEmpty().isEmpty())
            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        }
    }

    @Test
    fun realWorkerRejectsRmCacheDirBeforeDestructiveBoundary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuredCache = File(testRoot, "configured-cache").apply { mkdirs() }
        val sentinel = File(configuredCache, "unrelated-sentinel.txt").apply {
            writeText("must survive")
        }
        val unrelatedStaging = File(configuredCache, "other-download").apply { mkdirs() }
        val unrelatedFile = File(unrelatedStaging, "partial.m4a").apply {
            writeBytes(byteArrayOf(9, 9, 9))
        }
        withConfiguredCachePath(configuredCache) {
            withCacheDownloads(true) {
                val downloadId = outputWiringDownloadIds.getAndIncrement()
                val destination = File(testRoot, "rm-cache-command").apply { mkdirs() }
                db.downloadDao.insertRaw(
                    download(
                        id = downloadId,
                        destination = destination.absolutePath,
                        type = DownloadType.command,
                        formatNote = "--rm-cache-dir --no-playlist",
                    )
                )
                DownloadWorkerEffectTestHooks.dbManagerForTesting = db
                val nativeBoundaryReached = AtomicBoolean(false)
                DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                    if (candidateId == downloadId) nativeBoundaryReached.set(true)
                }
                DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting =
                    { candidateId, _, _ ->
                        if (candidateId != downloadId) null else {
                            error("--rm-cache-dir must be rejected before native/destructive execution")
                        }
                    }

                enqueueAndAwaitDownloadWorker(context, downloadId)

                assertFalse(nativeBoundaryReached.get())
                assertTrue(sentinel.exists())
                assertTrue(unrelatedFile.exists())
                assertTrue(destination.listFiles().orEmpty().isEmpty())
                assertNull(db.historyDao.getItemByDownloadId(downloadId))
                assertEquals(
                    DownloadRepository.Status.Error.name,
                    db.downloadDao.getNullableDownloadById(downloadId)?.status,
                )
            }
        }
    }

    @Test
    fun realWorkerRejectsUnsafeCustomOutputBeforeNativeBoundary() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "pre-native-command").apply { mkdirs() }
            val escaped = File(testRoot, "escaped-command/escaped.m4a")
            val escapedPath = escaped.absolutePath.replace('\\', '/')
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.command,
                    formatNote = "-o \"$escapedPath\" --no-playlist",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            val nativeBoundaryReached = AtomicBoolean(false)
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == downloadId) nativeBoundaryReached.set(true)
            }
            // This reproduces the old physical-side-effect gap: if validation
            // ever lets execution reach this seam, an escaped artifact appears
            // before downstream provenance gets a chance to reject it.
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, _ ->
                if (candidateId != downloadId) {
                    null
                } else {
                    escaped.parentFile?.mkdirs()
                    escaped.writeBytes(byteArrayOf(8, 8, 8))
                    "${DownloadOutputProvenance.PRINT_MARKER}'${escaped.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertFalse(nativeBoundaryReached.get())
            assertFalse(escaped.exists())
            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        }
    }

    @Test
    fun realWorkerRejectsUnsafeClusteredCustomOutputBeforeNativeBoundary() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "pre-native-clustered-command").apply { mkdirs() }
            val escaped = File(testRoot, "escaped-clustered-command/escaped.m4a")
            val escapedPath = escaped.absolutePath.replace('\\', '/')
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.command,
                    formatNote = "-qo$escapedPath --no-playlist",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            val nativeBoundaryReached = AtomicBoolean(false)
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == downloadId) nativeBoundaryReached.set(true)
            }
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, _ ->
                if (candidateId != downloadId) {
                    null
                } else {
                    escaped.parentFile?.mkdirs()
                    escaped.writeBytes(byteArrayOf(8, 8, 8))
                    "${DownloadOutputProvenance.PRINT_MARKER}'${escaped.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertFalse(nativeBoundaryReached.get())
            assertFalse(escaped.exists())
            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        }
    }

    @Test
    fun realWorkerPublishesSafeRelativeCustomOutputFromOwnedStaging() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "relative-command").apply { mkdirs() }
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    type = DownloadType.command,
                    formatNote = "-o \"safe/%(title)s.%(ext)s\" --no-playlist",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            val nativeBoundaryReached = AtomicBoolean(false)
            var ownedOutputDirectory: File? = null
            var stagedOutput: File? = null
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == downloadId) nativeBoundaryReached.set(true)
            }
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    ownedOutputDirectory = outputDirectory.canonicalFile
                    val output = File(outputDirectory, "safe/relative.m4a").apply {
                        parentFile?.mkdirs()
                        writeBytes(byteArrayOf(5, 5, 5))
                    }
                    stagedOutput = output
                    "${DownloadOutputProvenance.PRINT_MARKER}'${output.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertTrue(nativeBoundaryReached.get())
            val stagingRoot = requireNotNull(ownedOutputDirectory)
            val staged = requireNotNull(stagedOutput)
            assertTrue(staged.canonicalPath.startsWith(stagingRoot.canonicalPath + File.separator))
            val history = requireNotNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(1, history.downloadPath.size)
            assertTrue(history.downloadPath.single().startsWith(destination.canonicalPath))
            assertTrue(history.downloadPath.single().endsWith("relative.m4a"))
            assertFalse(staged.exists())
        }
    }

    @Test
    fun realWorkerRejectsUnsafeExtraCommandOutputBeforeNativeBoundary() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "pre-native-extra").apply { mkdirs() }
            val escaped = File(testRoot, "escaped-extra/escaped.m4a")
            val escapedPath = escaped.absolutePath.replace('\\', '/')
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    extraCommands = "-o \"$escapedPath\"",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            val nativeBoundaryReached = AtomicBoolean(false)
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == downloadId) nativeBoundaryReached.set(true)
            }
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, _ ->
                if (candidateId != downloadId) {
                    null
                } else {
                    escaped.parentFile?.mkdirs()
                    escaped.writeBytes(byteArrayOf(7, 7, 7))
                    "${DownloadOutputProvenance.PRINT_MARKER}'${escaped.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertFalse(nativeBoundaryReached.get())
            assertFalse(escaped.exists())
            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
        }
    }

    @Test
    fun realWorkerRejectsUnsafeClusteredExtraCommandPathBeforeNativeBoundary() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "pre-native-clustered-extra").apply { mkdirs() }
            val oldMedia = File(destination, "old.m4a").apply { writeBytes(byteArrayOf(9, 9, 9)) }
            val escaped = File(testRoot, "escaped-clustered-extra/escaped.m4a")
            val escapedPath = escaped.absolutePath.replace('\\', '/')
            val historyId = db.historyDao.insertAndGetIdRaw(
                history(oldMedia.absolutePath).copy(downloadId = 0L)
            )
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    playlistUrl = HistoryRedownloadMarker.regular(historyId),
                    extraCommands = "-qP $escapedPath",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            val nativeBoundaryReached = AtomicBoolean(false)
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == downloadId) nativeBoundaryReached.set(true)
            }
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, _ ->
                if (candidateId != downloadId) {
                    null
                } else {
                    escaped.parentFile?.mkdirs()
                    escaped.writeBytes(byteArrayOf(7, 7, 7))
                    "${DownloadOutputProvenance.PRINT_MARKER}'${escaped.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertFalse(nativeBoundaryReached.get())
            assertFalse(escaped.exists())
            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals(
                oldMedia.absolutePath,
                requireNotNull(db.historyDao.getNullableItem(historyId)).downloadPath.single(),
            )
            assertTrue(oldMedia.exists())
        }
    }

    @Test
    fun realWorkerRejectsCustomCommandOutputOutsideEffectiveDestination() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val configuredDestination = File(testRoot, "configured-outside").apply { mkdirs() }
            val effectiveDestination = File(testRoot, "effective-outside").apply { mkdirs() }
            val foreignDestination = File(testRoot, "foreign-outside").apply { mkdirs() }
            val commandPath = effectiveDestination.absolutePath.replace('\\', '/')
            val foreign = File(foreignDestination, "foreign-output.m4a").apply {
                writeBytes(byteArrayOf(4, 4, 4))
            }
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = configuredDestination.absolutePath,
                    type = DownloadType.command,
                    formatNote = "-P \"$commandPath\" --no-playlist",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, _ ->
                if (candidateId == downloadId) {
                    "${DownloadOutputProvenance.PRINT_MARKER}'${foreign.absolutePath}'"
                } else {
                    null
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(foreign.exists())
            assertTrue(effectiveDestination.listFiles().orEmpty().none { it.name == foreign.name })
        }
    }

    @Test
    fun unprovenOutputCannotReachHistoryReplacementOrOldMediaDeletion() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "replacement").apply { mkdirs() }
            val oldMedia = File(destination, "old.m4a").apply { writeBytes(byteArrayOf(9, 9, 9)) }
            val ambient = File(destination, "ambient.m4a").apply { writeBytes(byteArrayOf(6, 6, 6)) }
            val historyId = db.historyDao.insertAndGetIdRaw(
                history(oldMedia.absolutePath).copy(downloadId = 0L)
            )
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    playlistUrl = HistoryRedownloadMarker.regular(historyId),
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, _ ->
                if (candidateId != downloadId) {
                    null
                } else {
                    "[download] Destination: '${ambient.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext(), downloadId)

            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(oldMedia.absolutePath, requireNotNull(db.historyDao.getNullableItem(historyId)).downloadPath.single())
            assertTrue(oldMedia.exists())
            assertTrue(ambient.exists())
        }
    }

    private fun clearOutputHooks() {
        DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting = null
        DownloadWorkerEffectTestHooks.dbManagerForTesting = null
        DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        DownloadWorkerEffectTestHooks.ytdlpSuccessForTesting = null
        DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = null
        DownloadWorkerEffectTestHooks.outputBaselineReaderForTesting = null
        DownloadWorkerEffectTestHooks.videoQualityProbeForTesting = null
        DownloadWorkerEffectTestHooks.hardSubBurnForTesting = null
        BundledFfmpegRuntime.resolutionForTesting = null
        DownloadWorkerEffectTestHooks.beforeNoCacheMediaPublicationForTesting = null
        DownloadWorkerEffectTestHooks.beforeNoCacheMediaScanForTesting = null
    }

    private suspend fun <T> withDiagnosticAdmissionPreferences(block: suspend () -> T): T {
        val preferences = PreferenceManager.getDefaultSharedPreferences(
            ApplicationProvider.getApplicationContext<Context>(),
        )
        val hadScheduler = preferences.contains("use_scheduler")
        val oldScheduler = preferences.getBoolean("use_scheduler", false)
        val hadConcurrent = preferences.contains("concurrent_downloads")
        val oldConcurrent = preferences.getInt("concurrent_downloads", 1)
        return try {
            assertTrue(preferences.edit()
                .putBoolean("use_scheduler", false)
                .putInt("concurrent_downloads", 1)
                .commit())
            withCacheDownloads(false, block)
        } finally {
            val restore = preferences.edit()
            if (hadScheduler) restore.putBoolean("use_scheduler", oldScheduler)
            else restore.remove("use_scheduler")
            if (hadConcurrent) restore.putInt("concurrent_downloads", oldConcurrent)
            else restore.remove("concurrent_downloads")
            assertTrue(restore.commit())
            assertEquals(hadScheduler, preferences.contains("use_scheduler"))
            assertEquals(oldScheduler, preferences.getBoolean("use_scheduler", false))
            assertEquals(hadConcurrent, preferences.contains("concurrent_downloads"))
            assertEquals(oldConcurrent, preferences.getInt("concurrent_downloads", 1))
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                android.os.Bundle().apply {
                    putString("stream", "\nAdmissionPreferencesRestored schedulerPresent=$hadScheduler " +
                        "scheduler=$oldScheduler concurrentPresent=$hadConcurrent concurrent=$oldConcurrent\n")
                },
            )
        }
    }

    private suspend fun <T> withCacheDownloads(enabled: Boolean, block: suspend () -> T): T {
        val preferences = PreferenceManager.getDefaultSharedPreferences(
            ApplicationProvider.getApplicationContext()
        )
        val hadSetting = preferences.contains("cache_downloads")
        val previous = preferences.getBoolean("cache_downloads", true)
        assertTrue(preferences.edit().putBoolean("cache_downloads", enabled).commit())
        return try {
            block()
        } finally {
            val editor = preferences.edit()
            if (hadSetting) editor.putBoolean("cache_downloads", previous) else editor.remove("cache_downloads")
            assertTrue(editor.commit())
        }
    }

    private suspend fun <T> withConfiguredCachePath(
        cachePath: File,
        block: suspend () -> T,
    ): T {
        val preferences = PreferenceManager.getDefaultSharedPreferences(
            ApplicationProvider.getApplicationContext(),
        )
        val hadSetting = preferences.contains("cache_path")
        val previous = preferences.getString("cache_path", null)
        assertTrue(preferences.edit().putString("cache_path", cachePath.absolutePath).commit())
        return try {
            block()
        } finally {
            val editor = preferences.edit()
            if (hadSetting) {
                editor.putString("cache_path", previous)
            } else {
                editor.remove("cache_path")
            }
            assertTrue(editor.commit())
        }
    }

    private fun download(
        id: Long,
        destination: String,
        type: DownloadType = DownloadType.audio,
        formatNote: String = "audio only",
        container: String = "m4a",
        videoPreferences: VideoPreferences = VideoPreferences(),
        playlistUrl: String? = "",
        extraCommands: String = "",
        customFileNameTemplate: String = "",
    ) = DownloadItem(
        id = id,
        url = "https://example.com/$id",
        title = "output provenance $id",
        author = "author",
        thumb = "",
        duration = "00:01",
        type = type,
        format = Format(container = container, format_note = formatNote),
        container = container,
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = destination,
        website = "example",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = videoPreferences,
        extraCommands = extraCommands,
        customFileNameTemplate = customFileNameTemplate,
        SaveThumb = false,
        status = DownloadRepository.Status.Queued.name,
        downloadStartTime = 0L,
        logID = null,
        playlistURL = playlistUrl,
        operationId = "bug-output-$id-${UUID.randomUUID()}",
    )

    private fun history(path: String) = HistoryItem(
        id = 0L,
        url = "https://example.com/replacement",
        title = "old",
        author = "author",
        artist = "",
        duration = "00:01",
        durationSeconds = 1L,
        thumb = "",
        type = DownloadType.audio,
        time = 1L,
        downloadPath = listOf(path),
        website = "example",
        format = Format(container = "m4a"),
        filesize = 3L,
        downloadId = 0L,
    )

    private fun cancelStaleRealWorkerRequests(context: Context) {
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
    }

    private suspend fun enqueueAndAwaitDownloadWorker(
        context: Context,
        downloadId: Long,
        diagnostic: (suspend (WorkInfo?, String?, UUID) -> String)? = null,
    ): WorkInfo {
        val workManager = WorkManager.getInstance(context)
        val cleanedExecution = AtomicReference<String?>()
        DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting = { candidateId, executionId ->
            if (candidateId == downloadId && executionId.isNotBlank()) {
                cleanedExecution.compareAndSet(null, executionId)
            }
        }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag("bug-output-real-worker")
            .build()
        fun emitDiagnostic(text: String): String {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                android.os.Bundle().apply { putString("stream", "\n$text\n") },
            )
            android.util.Log.i("VerifiedQualityDiagnostic", text)
            return text
        }
        if (diagnostic != null) {
            withContext(Dispatchers.IO) {
                emitDiagnostic("phase=BEFORE_ENQUEUE\n" + diagnostic(null, null, request.id))
            }
        }
        workManager.enqueue(request)
        return withContext(Dispatchers.IO) {
            var lastWorkInfo: WorkInfo? = null
            val transitions = mutableListOf<String>()
            suspend fun reportDiagnostic(phase: String): String? =
                diagnostic?.invoke(lastWorkInfo, cleanedExecution.get(), request.id)?.let { text ->
                    emitDiagnostic("phase=$phase observedWorkInfoTransitions=$transitions\n$text")
                }
            repeat(240) {
                val workInfo = runCatching {
                    workManager.getWorkInfoById(request.id).get(1, TimeUnit.SECONDS)
                }.getOrNull()
                lastWorkInfo = workInfo
                if (diagnostic != null && workInfo != null) {
                    val stopReason = runCatching {
                        workInfo.javaClass.getMethod("getStopReason").invoke(workInfo)
                    }.getOrElse { "STOP_REASON_OBSERVATION_FAILED:${it::class.java.simpleName}" }
                    val observation = "${workInfo.state}/attempt=${workInfo.runAttemptCount}/stopReason=$stopReason"
                    if (transitions.lastOrNull() != observation) transitions.add(observation)
                }
                if (workInfo != null && (workInfo.state.isFinished || cleanedExecution.get() != null)) {
                    reportDiagnostic("TERMINAL_OR_CLEANUP")
                    val executionId = cleanedExecution.get()
                    captureAndAssertPostAttempt(context, downloadId, executionId, workInfo)
                    return@withContext workInfo
                }
                Thread.sleep(250L)
            }
            val details = reportDiagnostic("TIMEOUT")
            error("Timed out waiting for real DownloadWorker ${request.id}" +
                (details?.let { "\n$it" } ?: ""))
        }
    }

    private fun captureAndAssertPostAttempt(
        context: Context,
        downloadId: Long,
        cleanedExecutionId: String?,
        workInfo: WorkInfo,
    ) {
        val row = db.downloadDao.getNullableDownloadById(downloadId)
        val history = db.historyDao.getItemByDownloadId(downloadId)
        val executionOwner = DownloadWorkerExecutionOwners.ownerOf(downloadId)
        val processOwner = DownloadWorkerProcessOwners.ownerOf(downloadId)
        val disposition = DownloadExecutionRecovery.pendingDispositionForExecution(context, downloadId)
        val phase = DownloadExecutionRecovery.pendingPhaseForTesting(context, downloadId)
        val genericPending = downloadId in DownloadExecutionRecovery.pendingDownloadIds(context)
        val producerDiscovery = DownloadProducerRecovery.discover(context)
        val producers = producerDiscovery.records.filter { it.downloadId == downloadId }
        val producerBlocking = DownloadProducerRecovery.hasBlockingForAdmission(context, downloadId)
        val executionId = cleanedExecutionId ?: row?.executionId.orEmpty()
        val nativeRegistered = DownloadWorker.hasRegisteredNativeProcess(downloadId, executionId)
        val markerDebt = YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(downloadId, executionId)
        android.util.Log.i(
            "OutputAttemptCleanupProof",
            "download=$downloadId cleanupExecution=$cleanedExecutionId row=${row?.status}/${row?.executionId} " +
                "history=${history?.id} historyDownload=${history?.downloadId} outputCount=${history?.downloadPath?.size} " +
                "executionOwner=$executionOwner processOwner=$processOwner genericPending=$genericPending " +
                "disposition=$disposition phase=$phase producerDiscovery=${producerDiscovery::class.java.simpleName} " +
                "producers=${producers.map { "${it.operationId}/${it.executionId}/${it.generationId}/${it.phase}" }} " +
                "producerBlocking=$producerBlocking nativeRegistered=$nativeRegistered markerDebt=$markerDebt " +
                "workRequest=${workInfo.id} workState=${workInfo.state}",
        )
        assertTrue("Producer discovery must remain readable", producerDiscovery is DownloadProducerRecovery.DiscoveryResult.Healthy)
        assertNotNull("The exact real attempt must complete its cleanup observation", cleanedExecutionId)
        if (cleanedExecutionId != null) {
            assertTrue(cleanedExecutionId.isNotBlank())
            if (row != null) assertEquals("Cleanup must belong to the exact row execution", cleanedExecutionId, row.executionId)
        }
        assertTrue("Attempt cleanup must not abandon a running row", row?.status !in setOf(
            DownloadRepository.Status.Active.name,
            DownloadRepository.Status.PostProcessing.name,
        ))
        assertNull("A terminal attempt must release execution authority", executionOwner)
        assertNull("A terminal attempt must release process authority", processOwner)
        assertFalse("A completed synthetic attempt must not retain native execution", nativeRegistered)
        assertFalse("A completed synthetic attempt must not retain native marker debt", markerDebt)
    }
}
