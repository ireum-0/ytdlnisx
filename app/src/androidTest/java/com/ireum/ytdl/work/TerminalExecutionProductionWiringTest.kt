package com.ireum.ytdl.work

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.TerminalItem
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.storage.AppCacheCategory
import com.ireum.ytdl.util.storage.AppCacheManager
import com.ireum.ytdl.util.storage.TerminalCacheOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Production WorkManager wiring for the durable Terminal execution witness.
 * The native response seam only avoids network/native-runtime dependence; the
 * worker still creates its command plan, admits the item, advances the durable
 * witness, and performs its ordinary no-output terminal commit.
 */
@RunWith(AndroidJUnit4::class)
class TerminalExecutionProductionWiringTest {
    private lateinit var context: Context
    private lateinit var db: DBManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = DBManager.getInstance(context)
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = db
        TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterAdmissionForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterNativeFinishedForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterPostNativeEffectOwnershipForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterTerminalRowDeletedForTesting = null
        TerminalDownloadWorkerEffectTestHooks.databaseForTesting = null
    }

    @After
    fun tearDown() {
        WorkManagerHandoffRecovery.clearForTesting()
        TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterAdmissionForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterNativeFinishedForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterPostNativeEffectOwnershipForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterTerminalRowDeletedForTesting = null
        TerminalDownloadWorkerEffectTestHooks.databaseForTesting = null
    }

    @Test
    fun durableWitnessExistsBeforeNoCacheNativeBoundary() = runBlocking {
        // An authored absolute -P is an explicit direct/no-cache route. The
        // simulation keeps this production-boundary test independent of
        // network/native output while still exercising the no-cache path.
        val command = "--simulate -P /storage/emulated/0/Download https://example.com/terminal-witness"
        val itemId = db.withTransaction {
            val id = db.terminalDao.insert(TerminalItem(command = command))
            WorkManagerHandoffRecovery.stageTerminalDispatchWithinTransaction(db, id, command)
            id
        }
        val carrier = requireNotNull(
            db.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                itemId.toString(),
            ),
        )
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadCachePreference = preferences.contains("cache_downloads")
        val previousCache = preferences.getBoolean("cache_downloads", true)
        val hadCommandPath = preferences.contains("command_path")
        val previousCommandPath = preferences.getString("command_path", null)
        val witnessedBeforeNative = AtomicBoolean(false)
        var request: OneTimeWorkRequest? = null
        try {
            check(
                preferences.edit()
                    .putBoolean("cache_downloads", false)
                    .putString("command_path", context.filesDir.absolutePath)
                    .commit(),
            )
            TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { observedId, _ ->
                assertEquals(itemId.toInt(), observedId)
                assertEquals(
                    TerminalExecutionRecovery.Phase.ADMITTED,
                    TerminalExecutionRecovery.read(context, itemId)?.phase,
                )
                witnessedBeforeNative.set(true)
            }
            TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = { observedId, outputDirectory ->
                assertEquals(itemId.toInt(), observedId)
                assertNull(outputDirectory)
                ""
            }
            request = OneTimeWorkRequestBuilder<TerminalDownloadWorker>()
                .setId(UUID.fromString(carrier.requestId))
                .setInputData(
                    workDataOf(
                        TerminalDownloadWorker.INPUT_ID to itemId.toInt(),
                        TerminalDownloadWorker.INPUT_COMMAND to command,
                        TerminalDownloadWorker.INPUT_HANDOFF_ID to carrier.handoffId,
                        TerminalDownloadWorker.INPUT_REQUEST_ID to carrier.requestId,
                        TerminalDownloadWorker.INPUT_GENERATION_ID to carrier.generationId,
                        TerminalDownloadWorker.INPUT_BOUNDARY to carrier.boundary,
                        TerminalDownloadWorker.INPUT_COMMAND_FINGERPRINT to carrier.configFingerprint,
                    ),
                )
                .addTag("terminal-execution-recovery")
                .build()
            WorkManager.getInstance(context).enqueue(checkNotNull(request))
            val info = awaitFinished(checkNotNull(request))
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            assertEquals(true, witnessedBeforeNative.get())
            assertNull(db.terminalDao.getTerminalById(itemId))
            assertEquals(
                TerminalExecutionRecovery.Phase.COMMITTED,
                TerminalExecutionRecovery.read(context, itemId)?.phase,
            )
        } finally {
            request?.let {
                WorkManager.getInstance(context).cancelWorkById(it.id).result.get(10, TimeUnit.SECONDS)
            }
            db.terminalDao.delete(itemId)
            db.workManagerHandoffCarrierDao.delete(carrier.handoffId)
            TerminalExecutionRecovery.clearForTesting(
                File(context.filesDir, "terminal-execution-recovery"),
                itemId,
            )
            preferences.edit().apply {
                if (hadCachePreference) putBoolean("cache_downloads", previousCache)
                else remove("cache_downloads")
                if (hadCommandPath) putString("command_path", previousCommandPath)
                else remove("command_path")
            }.commit()
        }
    }

    @Test
    fun admittedTerminalKeepsItsCacheRootWhenPreferenceChangesBeforePlanning() = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadCachePath = preferences.contains("cache_path")
        val previousCachePath = preferences.getString("cache_path", null)
        val hadCacheDownloads = preferences.contains("cache_downloads")
        val previousCacheDownloads = preferences.getBoolean("cache_downloads", true)
        val externalFiles = requireNotNull(context.getExternalFilesDir(null))
        val admittedRoot = File(externalFiles, "terminal-bound-${System.nanoTime()}").canonicalFile
        // This case is about cache-root ownership, not persisted-generation
        // ambiguity, so the directly seeded row is materialized into the
        // current durable format.  A row seeded without that format is a
        // pre-materializer record and is correctly non-runnable.
        val command = com.ireum.ytdl.util.terminal.TerminalCommandIntentMaterializer
            .materialize("--simulate https://example.com/terminal-bound-root", context.filesDir.absolutePath)
        val itemId = db.withTransaction {
            val id = db.terminalDao.insert(TerminalItem(command = command))
            WorkManagerHandoffRecovery.stageTerminalDispatchWithinTransaction(db, id, command)
            id
        }
        val carrier = requireNotNull(
            db.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                itemId.toString(),
            ),
        )
        val observedStagingRoot = AtomicReference<File?>(null)
        val observedFallbackRoot = AtomicReference<File?>(null)
        var request: OneTimeWorkRequest? = null
        try {
            assertTrue(
                preferences.edit()
                    .putString("cache_path", admittedRoot.absolutePath)
                    .putBoolean("cache_downloads", true)
                    .commit(),
            )
            TerminalDownloadWorkerEffectTestHooks.afterAdmissionForTesting = { observedId, boundRoot ->
                assertEquals(itemId.toInt(), observedId)
                assertEquals(admittedRoot, boundRoot.canonicalFile)
                assertTrue(
                    preferences.edit().putString("cache_path", "").commit(),
                )

                // Model a carrier in the fallback namespace that admission
                // did not inspect.  A later plan/staging step must still use
                // the already admitted root rather than silently switching.
                val fallbackRoot = File(FileUtil.getCachePath(context)).canonicalFile
                val carrierRoot = File(fallbackRoot, "TERMINAL/stale-$itemId").apply { mkdirs() }
                val staleToken = "$itemId-stale"
                TerminalCacheOwnership.ensureMarker(carrierRoot, staleToken)
                val remainder = File(carrierRoot, "remainder.bin").apply { writeText("stale") }
                assertTrue(TerminalCacheOwnership.recordArtifacts(carrierRoot, listOf(remainder.absolutePath)))
                assertTrue(
                    TerminalCacheOwnership.recordRecoveryCarrier(
                        directory = carrierRoot,
                        taskToken = staleToken,
                        subjectId = itemId.toString(),
                    ),
                )
                assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(carrierRoot, staleToken))
                observedFallbackRoot.set(fallbackRoot)
            }
            TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { observedId, output ->
                assertEquals(itemId.toInt(), observedId)
                observedStagingRoot.set(output?.canonicalFile)
            }
            TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = { observedId, _ ->
                assertEquals(itemId.toInt(), observedId)
                ""
            }
            request = OneTimeWorkRequestBuilder<TerminalDownloadWorker>()
                .setId(UUID.fromString(carrier.requestId))
                .setInputData(
                    workDataOf(
                        TerminalDownloadWorker.INPUT_ID to itemId.toInt(),
                        TerminalDownloadWorker.INPUT_COMMAND to command,
                        TerminalDownloadWorker.INPUT_HANDOFF_ID to carrier.handoffId,
                        TerminalDownloadWorker.INPUT_REQUEST_ID to carrier.requestId,
                        TerminalDownloadWorker.INPUT_GENERATION_ID to carrier.generationId,
                        TerminalDownloadWorker.INPUT_BOUNDARY to carrier.boundary,
                        TerminalDownloadWorker.INPUT_COMMAND_FINGERPRINT to carrier.configFingerprint,
                    ),
                )
                .addTag("terminal-bound-cache-root")
                .build()
            WorkManager.getInstance(context).enqueue(checkNotNull(request))
            val info = awaitFinished(checkNotNull(request))

            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            val staging = checkNotNull(observedStagingRoot.get())
            assertEquals(
                File(admittedRoot, "TERMINAL").canonicalFile,
                staging.parentFile?.canonicalFile,
            )
            val fallbackRoot = checkNotNull(observedFallbackRoot.get())
            assertTrue(File(fallbackRoot, "TERMINAL/stale-$itemId").isDirectory)
            assertFalse(staging.path.startsWith(fallbackRoot.path))
            assertNull(db.terminalDao.getTerminalById(itemId))
        } finally {
            request?.let {
                WorkManager.getInstance(context).cancelWorkById(it.id).result.get(10, TimeUnit.SECONDS)
            }
            db.terminalDao.delete(itemId)
            db.workManagerHandoffCarrierDao.delete(carrier.handoffId)
            TerminalExecutionRecovery.clearForTesting(
                File(context.filesDir, "terminal-execution-recovery"),
                itemId,
            )
            observedFallbackRoot.get()?.let { fallbackRoot ->
                File(fallbackRoot, "TERMINAL/stale-$itemId").deleteRecursively()
            }
            admittedRoot.deleteRecursively()
            preferences.edit().apply {
                if (hadCachePath) putString("cache_path", previousCachePath) else remove("cache_path")
                if (hadCacheDownloads) putBoolean("cache_downloads", previousCacheDownloads)
                else remove("cache_downloads")
            }.commit()
        }
    }

    @Test
    fun cancellationAtNativeFinishedWinsBeforePostNativeEffectsBegin() = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadCacheDownloads = preferences.contains("cache_downloads")
        val previousCacheDownloads = preferences.getBoolean("cache_downloads", true)
        val hadCommandPath = preferences.contains("command_path")
        val previousCommandPath = preferences.getString("command_path", null)
        val command = "--simulate -P /storage/emulated/0/Download https://example.com/terminal-cancel-native-finished"
        val itemId = db.withTransaction {
            val id = db.terminalDao.insert(TerminalItem(command = command))
            WorkManagerHandoffRecovery.stageTerminalDispatchWithinTransaction(db, id, command)
            id
        }
        val carrier = requireNotNull(
            db.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                itemId.toString(),
            ),
        )
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executionToken = AtomicReference<String?>(null)
        var request: OneTimeWorkRequest? = null
        try {
            assertTrue(
                preferences.edit()
                    .putBoolean("cache_downloads", false)
                    .putString("command_path", context.filesDir.absolutePath)
                    .commit(),
            )
            TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = { observedId, output ->
                assertEquals(itemId.toInt(), observedId)
                assertNull(output)
                ""
            }
            TerminalDownloadWorkerEffectTestHooks.afterNativeFinishedForTesting = { observedId ->
                assertEquals(itemId.toInt(), observedId)
                val record = TerminalExecutionRecovery.read(context, itemId)
                assertEquals(TerminalExecutionRecovery.Phase.NATIVE_FINISHED, record?.phase)
                executionToken.set(record?.executionToken)
                entered.countDown()
                awaitRelease(resume)
            }
            request = requestFor(itemId, command, "terminal-cancel-native-finished")
            WorkManager.getInstance(context).enqueue(checkNotNull(request))
            assertTrue(entered.await(30, TimeUnit.SECONDS))

            val token = requireNotNull(executionToken.get())
            val cancellation = TerminalCancellationCoordinator.cancel(context, itemId)
            assertTrue(cancellation.executionConverged)
            assertEquals(cancellation.dispatchSuperseded, cancellation.rowDeletionAuthorized)
            assertEquals(
                TerminalExecutionRecovery.Phase.TERMINAL_STOPPED,
                TerminalExecutionRecovery.read(context, itemId)?.phase,
            )
            assertFalse(TerminalExecutionRegistry.isActiveNow(token))

            resume.countDown()
            assertTrue(awaitFinished(checkNotNull(request)).state.isFinished)
            assertNull(db.terminalDao.getTerminalById(itemId))
        } finally {
            resume.countDown()
            request?.let {
                WorkManager.getInstance(context).cancelWorkById(it.id).result.get(10, TimeUnit.SECONDS)
            }
            db.terminalDao.delete(itemId)
            db.workManagerHandoffCarrierDao.delete(carrier.handoffId)
            TerminalExecutionRecovery.clearForTesting(
                File(context.filesDir, "terminal-execution-recovery"),
                itemId,
            )
            preferences.edit().apply {
                if (hadCacheDownloads) putBoolean("cache_downloads", previousCacheDownloads)
                else remove("cache_downloads")
                if (hadCommandPath) putString("command_path", previousCommandPath)
                else remove("command_path")
            }.commit()
        }
    }

    @Test
    fun cancellationDuringPostNativeOwnershipDefersRowAndProtectsCacheUntilEffectQuiescence() = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadCachePath = preferences.contains("cache_path")
        val previousCachePath = preferences.getString("cache_path", null)
        val hadCacheDownloads = preferences.contains("cache_downloads")
        val previousCacheDownloads = preferences.getBoolean("cache_downloads", true)
        val hadCommandPath = preferences.contains("command_path")
        val previousCommandPath = preferences.getString("command_path", null)
        val externalFiles = requireNotNull(context.getExternalFilesDir(null))
        val admittedRoot = File(externalFiles, "terminal-effect-cancel-${UUID.randomUUID()}").canonicalFile
        val destination = File(context.filesDir, "terminal-effect-destination-${UUID.randomUUID()}")
        val command = com.ireum.ytdl.util.terminal.TerminalCommandIntentMaterializer.materialize(
            "--simulate https://example.com/terminal-post-native-cancel",
            context.filesDir.absolutePath,
        )
        val itemId = db.withTransaction {
            val id = db.terminalDao.insert(TerminalItem(command = command))
            WorkManagerHandoffRecovery.stageTerminalDispatchWithinTransaction(db, id, command)
            id
        }
        val carrier = requireNotNull(
            db.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                itemId.toString(),
            ),
        )
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executionToken = AtomicReference<String?>(null)
        val stagingRoot = AtomicReference<File?>(null)
        var request: OneTimeWorkRequest? = null
        try {
            assertTrue(destination.mkdirs())
            assertTrue(
                preferences.edit()
                    .putString("cache_path", admittedRoot.absolutePath)
                    .putBoolean("cache_downloads", true)
                    .putString("command_path", destination.absolutePath)
                    .commit(),
            )
            TerminalDownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting =
                { observedId, outputDirectory ->
                    assertEquals(itemId.toInt(), observedId)
                    val source = File(outputDirectory, "cancel-race.mp4").apply {
                        writeText("staged before publication")
                    }
                    source.absolutePath
                }
            TerminalDownloadWorkerEffectTestHooks.afterPostNativeEffectOwnershipForTesting =
                { observedId, outputDirectory ->
                    assertEquals(itemId.toInt(), observedId)
                    val record = TerminalExecutionRecovery.read(context, itemId)
                    assertEquals(TerminalExecutionRecovery.Phase.POST_NATIVE_EFFECTS, record?.phase)
                    executionToken.set(record?.executionToken)
                    stagingRoot.set(outputDirectory)
                    entered.countDown()
                    awaitRelease(resume)
                }
            request = requestFor(itemId, command, "terminal-post-native-cancel")
            WorkManager.getInstance(context).enqueue(checkNotNull(request))
            assertTrue(entered.await(30, TimeUnit.SECONDS))

            val token = requireNotNull(executionToken.get())
            val staging = requireNotNull(stagingRoot.get())
            val cancellation = TerminalCancellationCoordinator.cancel(context, itemId)
            assertFalse(cancellation.executionConverged)
            assertFalse(cancellation.rowDeletionAuthorized)
            assertEquals(
                TerminalExecutionRecovery.Phase.POST_NATIVE_EFFECTS,
                TerminalExecutionRecovery.read(context, itemId)?.phase,
            )
            assertEquals(
                TerminalExecutionRecovery.Outcome.STOPPED,
                TerminalExecutionRecovery.read(context, itemId)?.outcome,
            )
            assertNotNull(db.terminalDao.getTerminalById(itemId))
            assertTrue(TerminalExecutionRegistry.isActiveNow(token))
            assertTrue(TerminalCacheOwnership.isLiveOwnedRoot(staging))

            val deletion = AppCacheManager(context).delete(setOf(AppCacheCategory.TERMINAL_CACHE))
            assertTrue(deletion.failedEntries > 0)
            assertTrue(staging.isDirectory)
            assertTrue(TerminalCacheOwnership.isOwned(staging, token))

            resume.countDown()
            assertTrue(awaitFinished(checkNotNull(request)).state.isFinished)
            assertNull(db.terminalDao.getTerminalById(itemId))
            assertEquals(
                TerminalExecutionRecovery.Phase.TERMINAL_STOPPED,
                TerminalExecutionRecovery.read(context, itemId)?.phase,
            )
            assertFalse(File(destination, "cancel-race.mp4").exists())
            assertTrue(TerminalCacheOwnership.recoveryCarrierFile(staging).isFile)
        } finally {
            resume.countDown()
            request?.let {
                WorkManager.getInstance(context).cancelWorkById(it.id).result.get(10, TimeUnit.SECONDS)
            }
            db.terminalDao.delete(itemId)
            db.workManagerHandoffCarrierDao.delete(carrier.handoffId)
            TerminalExecutionRecovery.clearForTesting(
                File(context.filesDir, "terminal-execution-recovery"),
                itemId,
            )
            admittedRoot.deleteRecursively()
            destination.deleteRecursively()
            preferences.edit().apply {
                if (hadCachePath) putString("cache_path", previousCachePath) else remove("cache_path")
                if (hadCacheDownloads) putBoolean("cache_downloads", previousCacheDownloads)
                else remove("cache_downloads")
                if (hadCommandPath) putString("command_path", previousCommandPath)
                else remove("command_path")
            }.commit()
        }
    }

    @Test
    fun postNativeProcessDeathRecoveryDefersOnLiveLeaseAndNeverPromotesPublication() = runBlocking {
        val externalFiles = requireNotNull(context.getExternalFilesDir(null))
        val cacheRoot = File(externalFiles, "terminal-effect-recovery-${UUID.randomUUID()}").canonicalFile
        val subjectIds = mutableListOf<Long>()
        val openLeases = mutableListOf<TerminalExecutionRecovery.EffectLease>()
        try {
            listOf(
                TerminalExecutionRecovery.Phase.POST_NATIVE_EFFECTS,
                TerminalExecutionRecovery.Phase.POST_NATIVE_PUBLISHING,
            ).forEach { expectedPhase ->
                val tokenSuffix = UUID.randomUUID().toString()
                val itemId = db.terminalDao.insert(
                    TerminalItem(command = "--simulate https://example.com/$tokenSuffix"),
                )
                subjectIds += itemId
                val token = "$itemId-$tokenSuffix"
                assertTrue(
                    TerminalExecutionRecovery.begin(
                        context,
                        itemId,
                        token,
                        YtdlpProcessIdentity.terminal(itemId),
                    ),
                )
                assertTrue(TerminalExecutionRecovery.markNativeStarted(context, itemId, token))
                assertTrue(TerminalExecutionRecovery.markNativeFinished(context, itemId, token))
                val lease = requireNotNull(
                    TerminalExecutionRecovery.beginPostNativeEffects(context, itemId, token),
                )
                openLeases += lease
                if (expectedPhase == TerminalExecutionRecovery.Phase.POST_NATIVE_PUBLISHING) {
                    val staging = File(cacheRoot, "TERMINAL/$token").apply { mkdirs() }
                    TerminalCacheOwnership.ensureMarker(staging, token)
                    val source = File(staging, "recovery-race.mp4").apply {
                        writeText("unpublished remainder")
                    }
                    assertTrue(TerminalCacheOwnership.recordArtifacts(staging, listOf(source.absolutePath)))
                    val journal = requireNotNull(
                        PublicationRecoveryJournal.begin(
                            context = context,
                            kind = PublicationRecoveryJournal.Kind.TERMINAL,
                            subjectId = itemId.toString(),
                            operationId = "terminal-$itemId",
                            executionId = token,
                            attemptId = token,
                            sourceRoot = staging,
                            sourceFiles = listOf(source),
                        ),
                    )
                    assertTrue(journal.markPhase(PublicationRecoveryJournal.Phase.PUBLISHING))
                    assertTrue(
                        TerminalExecutionRecovery.markPublicationStarted(
                            context,
                            itemId,
                            token,
                            lease,
                        ),
                    )
                }
                assertEquals(expectedPhase, TerminalExecutionRecovery.read(context, itemId)?.phase)

                TerminalExecutionRecovery.reconcile(context, activeExecution = { false })
                assertEquals(expectedPhase, TerminalExecutionRecovery.read(context, itemId)?.phase)
                assertNotNull(db.terminalDao.getTerminalById(itemId))

                // Releasing the OS lock models process death. Recovery can
                // now settle the abandoned owner without replaying native work.
                lease.close()
                openLeases.remove(lease)
                TerminalExecutionRecovery.reconcile(context, activeExecution = { false })
                assertNull(db.terminalDao.getTerminalById(itemId))
                assertNull(TerminalExecutionRecovery.read(context, itemId))
                if (expectedPhase == TerminalExecutionRecovery.Phase.POST_NATIVE_PUBLISHING) {
                    TerminalPublicationRecovery.reconcile(context, cacheRoot)
                    val staging = File(cacheRoot, "TERMINAL/$token")
                    assertFalse(TerminalCacheOwnership.markerFile(staging).isFile)
                    assertFalse(File(staging, "recovery-race.mp4").exists())
                }
            }
        } finally {
            openLeases.forEach { it.close() }
            subjectIds.forEach { itemId ->
                db.terminalDao.delete(itemId)
                TerminalExecutionRecovery.clearForTesting(
                    File(context.filesDir, "terminal-execution-recovery"),
                    itemId,
                )
            }
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun cancellationAfterPublishedRowCommitCannotReopenTheTerminalResult() = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadCachePath = preferences.contains("cache_path")
        val previousCachePath = preferences.getString("cache_path", null)
        val hadCacheDownloads = preferences.contains("cache_downloads")
        val previousCacheDownloads = preferences.getBoolean("cache_downloads", true)
        val hadCommandPath = preferences.contains("command_path")
        val previousCommandPath = preferences.getString("command_path", null)
        val externalFiles = requireNotNull(context.getExternalFilesDir(null))
        val admittedRoot = File(externalFiles, "terminal-committed-cache-${UUID.randomUUID()}").canonicalFile
        val destination = File(context.filesDir, "terminal-committed-destination-${UUID.randomUUID()}")
        val command = com.ireum.ytdl.util.terminal.TerminalCommandIntentMaterializer.materialize(
            "--simulate https://example.com/terminal-publication-committed",
            context.filesDir.absolutePath,
        )
        val itemId = db.withTransaction {
            val id = db.terminalDao.insert(TerminalItem(command = command))
            WorkManagerHandoffRecovery.stageTerminalDispatchWithinTransaction(db, id, command)
            id
        }
        val carrier = requireNotNull(
            db.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                itemId.toString(),
            ),
        )
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executionToken = AtomicReference<String?>(null)
        var request: OneTimeWorkRequest? = null
        try {
            assertTrue(destination.mkdirs())
            assertTrue(
                preferences.edit()
                    .putString("cache_path", admittedRoot.absolutePath)
                    .putBoolean("cache_downloads", true)
                    .putString("command_path", destination.absolutePath)
                    .commit(),
            )
            TerminalDownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting =
                { observedId, outputDirectory ->
                    assertEquals(itemId.toInt(), observedId)
                    File(outputDirectory, "committed-race.mp4").apply {
                        writeText("committed output")
                    }.absolutePath
                }
            TerminalDownloadWorkerEffectTestHooks.afterTerminalRowDeletedForTesting = { observedId ->
                assertEquals(itemId.toInt(), observedId)
                val record = TerminalExecutionRecovery.read(context, itemId)
                assertEquals(TerminalExecutionRecovery.Phase.COMMITTING, record?.phase)
                executionToken.set(record?.executionToken)
                assertNull(db.terminalDao.getTerminalById(itemId))
                assertEquals("committed output", File(destination, "committed-race.mp4").readText())
                entered.countDown()
                awaitRelease(resume)
            }
            request = requestFor(itemId, command, "terminal-published-commit")
            WorkManager.getInstance(context).enqueue(checkNotNull(request))
            assertTrue(entered.await(60, TimeUnit.SECONDS))

            val token = requireNotNull(executionToken.get())
            val cancellation = TerminalCancellationCoordinator.cancel(context, itemId)
            assertFalse(cancellation.executionConverged)
            assertFalse(cancellation.rowDeletionAuthorized)
            assertEquals(
                TerminalExecutionRecovery.Phase.COMMITTING,
                TerminalExecutionRecovery.read(context, itemId)?.phase,
            )
            assertNull(db.terminalDao.getTerminalById(itemId))
            assertTrue(TerminalExecutionRegistry.isActiveNow(token))
            assertEquals("committed output", File(destination, "committed-race.mp4").readText())

            resume.countDown()
            assertTrue(awaitFinished(checkNotNull(request)).state.isFinished)
            assertNull(db.terminalDao.getTerminalById(itemId))
            assertEquals(
                TerminalExecutionRecovery.Phase.COMMITTED,
                TerminalExecutionRecovery.read(context, itemId)?.phase,
            )
            assertEquals("committed output", File(destination, "committed-race.mp4").readText())
        } finally {
            resume.countDown()
            request?.let {
                WorkManager.getInstance(context).cancelWorkById(it.id).result.get(10, TimeUnit.SECONDS)
            }
            db.terminalDao.delete(itemId)
            db.workManagerHandoffCarrierDao.delete(carrier.handoffId)
            TerminalExecutionRecovery.clearForTesting(
                File(context.filesDir, "terminal-execution-recovery"),
                itemId,
            )
            admittedRoot.deleteRecursively()
            destination.deleteRecursively()
            preferences.edit().apply {
                if (hadCachePath) putString("cache_path", previousCachePath) else remove("cache_path")
                if (hadCacheDownloads) putBoolean("cache_downloads", previousCacheDownloads)
                else remove("cache_downloads")
                if (hadCommandPath) putString("command_path", previousCommandPath)
                else remove("command_path")
            }.commit()
        }
    }

    private suspend fun requestFor(itemId: Long, command: String, tag: String): OneTimeWorkRequest {
        val carrier = requireNotNull(
            db.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                com.ireum.ytdl.database.models.WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                itemId.toString(),
            ),
        )
        return OneTimeWorkRequestBuilder<TerminalDownloadWorker>()
            .setId(UUID.fromString(carrier.requestId))
            .setInputData(
                workDataOf(
                    TerminalDownloadWorker.INPUT_ID to itemId.toInt(),
                    TerminalDownloadWorker.INPUT_COMMAND to command,
                    TerminalDownloadWorker.INPUT_HANDOFF_ID to carrier.handoffId,
                    TerminalDownloadWorker.INPUT_REQUEST_ID to carrier.requestId,
                    TerminalDownloadWorker.INPUT_GENERATION_ID to carrier.generationId,
                    TerminalDownloadWorker.INPUT_BOUNDARY to carrier.boundary,
                    TerminalDownloadWorker.INPUT_COMMAND_FINGERPRINT to carrier.configFingerprint,
                ),
            )
            .addTag(tag)
            .build()
    }

    private fun awaitRelease(resume: CountDownLatch) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var interrupted = false
        var released = false
        while (!released) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0L) break
            try {
                released = resume.await(remaining, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        check(released) { "Timed out waiting for production-wiring test barrier" }
    }

    private suspend fun awaitFinished(request: OneTimeWorkRequest): WorkInfo = withContext(Dispatchers.IO) {
        repeat(240) {
            val info = runCatching {
                WorkManager.getInstance(context).getWorkInfoById(request.id).get(1, TimeUnit.SECONDS)
            }.getOrNull()
            if (info?.state?.isFinished == true) return@withContext checkNotNull(info)
            Thread.sleep(250L)
        }
        error("Timed out waiting for TerminalDownloadWorker ${request.id}")
    }
}
