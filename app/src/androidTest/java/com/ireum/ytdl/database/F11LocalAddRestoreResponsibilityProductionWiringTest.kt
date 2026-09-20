package com.ireum.ytdl.database

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.RestorePlan
import com.ireum.ytdl.util.LocalAddEntryDto
import com.ireum.ytdl.util.LocalAddStorage
import com.ireum.ytdl.work.LocalAddWorker
import com.ireum.ytdl.work.LocalAddWorkerTestHooks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Production-wiring proof for F11 LocalAdd responsibility preservation. */
@RunWith(AndroidJUnit4::class)
class F11LocalAddRestoreResponsibilityProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var workManager: WorkManager
    private val sessions = mutableListOf<String>()

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = DBManager.getInstance(context)
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        runCatching { RestoreTransactionCoordinator.recover(context) }
        RestoreOperationStore.root(context).deleteRecursively()
        database.historyDao.nuke()
        database.downloadDao.deleteAll()
        LocalAddWorkerTestHooks.databaseForTesting = database
        LocalAddWorkerTestHooks.matchForTesting = null
        LocalAddWorkerTestHooks.metadataForTesting = { uri ->
            LocalAddWorkerTestHooks.MetadataOverride(
                displayName = "f11-local-add.mp4",
                size = 3L,
            )
        }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        LocalAddWorkerTestHooks.matchForTesting = null
        LocalAddWorkerTestHooks.metadataForTesting = null
        LocalAddWorkerTestHooks.databaseForTesting = null
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        sessions.forEach { sessionId ->
            LocalAddStorage.retireSession(context, sessionId)
            workManager.cancelUniqueWork(LocalAddStorage.uniqueWorkName(sessionId))
                .result
                .get(20, TimeUnit.SECONDS)
        }
        RestoreOperationStore.root(context).deleteRecursively()
    }

    @Test
    fun activeSessionIsQuiescedAndReconstructedWithOneExactOwner(): Unit = runBlocking {
        val initialEntered = CompletableDeferred<Unit>()
        val replacementEntered = CompletableDeferred<Unit>()
        val initialRelease = CompletableDeferred<Unit>()
        val replacementRelease = CompletableDeferred<Unit>()
        var calls = 0
        LocalAddWorkerTestHooks.matchForTesting = { _, _ ->
            calls += 1
            when (calls) {
                1 -> {
                    initialEntered.complete(Unit)
                    initialRelease.await()
                }
                2 -> {
                    replacementEntered.complete(Unit)
                    replacementRelease.await()
                }
            }
            null
        }

        val (sessionId, initialRequestId) = createLiveSession()
        withTimeout(20_000L) { initialEntered.await() }
        val initialInfo = requireNotNull(
            workManager.getWorkInfoById(UUID.fromString(initialRequestId))
                .get(10, TimeUnit.SECONDS)
        )
        assertFalse(initialInfo.state.isFinished)

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            historyPlan("https://example.com/f11-local-add-reset"),
        )
        assertTrue(outcome is RestoreOutcome.Completed)
        withTimeout(20_000L) { replacementEntered.await() }

        val owner = requireNotNull(LocalAddStorage.loadWorkOwner(context, sessionId))
        assertEquals(LocalAddStorage.OWNER_ACCEPTED, owner.state)
        assertNotEquals(initialRequestId, owner.requestId)
        assertEquals(LocalAddStorage.uniqueWorkName(sessionId), owner.uniqueWorkName)
        val replacementInfo = requireNotNull(
            workManager.getWorkInfoById(UUID.fromString(owner.requestId))
                .get(10, TimeUnit.SECONDS)
        )
        assertFalse(replacementInfo.state.isFinished)
        assertEquals(
            1,
            workManager.getWorkInfosForUniqueWork(owner.uniqueWorkName)
                .get(10, TimeUnit.SECONDS)
                .count { !it.state.isFinished },
        )
        assertTrue(initialInfo.id.toString() == initialRequestId)
        assertTrue(
            requireNotNull(workManager.getWorkInfoById(UUID.fromString(initialRequestId))
                .get(10, TimeUnit.SECONDS))
                .state
                .isFinished,
        )

        initialRelease.complete(Unit)
        replacementRelease.complete(Unit)
        awaitFinished(UUID.fromString(owner.requestId))
        withTimeout(20_000L) {
            while (LocalAddStorage.loadWorkOwner(context, sessionId)?.state != LocalAddStorage.OWNER_RETIRED) {
                kotlinx.coroutines.delay(25L)
            }
        }
    }

    @Test
    fun sameRestoreReplayReusesAcceptedLocalAddOwner(): Unit = runBlocking {
        val initialEntered = CompletableDeferred<Unit>()
        val replacementEntered = CompletableDeferred<Unit>()
        val initialRelease = CompletableDeferred<Unit>()
        val replacementRelease = CompletableDeferred<Unit>()
        var calls = 0
        LocalAddWorkerTestHooks.matchForTesting = { _, _ ->
            calls += 1
            when (calls) {
                1 -> {
                    initialEntered.complete(Unit)
                    initialRelease.await()
                }
                2 -> {
                    replacementEntered.complete(Unit)
                    replacementRelease.await()
                }
            }
            null
        }
        val (sessionId, _) = createLiveSession()
        withTimeout(20_000L) { initialEntered.await() }

        var interrupted = true
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = {
            if (interrupted) {
                interrupted = false
                error("F11 LocalAdd replay seam")
            }
        }
        val pending = RestoreTransactionCoordinator.begin(
            context,
            historyPlan("https://example.com/f11-local-add-replay"),
        )
        assertTrue(pending is RestoreOutcome.CommittedReconciliationPending)
        withTimeout(20_000L) { replacementEntered.await() }
        val firstOwner = requireNotNull(LocalAddStorage.loadWorkOwner(context, sessionId))
        assertEquals(LocalAddStorage.OWNER_ACCEPTED, firstOwner.state)
        assertTrue(
            requireNotNull(workManager.getWorkInfoById(UUID.fromString(firstOwner.requestId))
                .get(10, TimeUnit.SECONDS))
                .state
                .isFinished.not(),
        )
        assertEquals(RestorePhase.RECONCILING.name, RestoreOperationStore.load(context)?.journal?.phase)

        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = null
        val recovered = RestoreTransactionCoordinator.recover(context)
        assertTrue(recovered is RestoreOutcome.Completed)
        val replayedOwner = requireNotNull(LocalAddStorage.loadWorkOwner(context, sessionId))
        assertEquals(firstOwner.requestId, replayedOwner.requestId)
        assertEquals(
            1,
            workManager.getWorkInfosForUniqueWork(replayedOwner.uniqueWorkName)
                .get(10, TimeUnit.SECONDS)
                .count { !it.state.isFinished },
        )

        initialRelease.complete(Unit)
        replacementRelease.complete(Unit)
        awaitFinished(UUID.fromString(replayedOwner.requestId))
    }

    @Test
    fun cancelledSessionAndLegacyEntriesAreNeverRevived(): Unit = runBlocking {
        val cancelledSession = UUID.randomUUID().toString()
        sessions += cancelledSession
        val cancelledRequest = OneTimeWorkRequestBuilder<LocalAddWorker>()
            .setInputData(workDataOf(LocalAddWorker.KEY_SESSION_ID to cancelledSession))
            .addTag(LocalAddWorker.TAG)
            .build()
        LocalAddStorage.beginSession(
            context,
            cancelledSession,
            cancelledRequest.id.toString(),
            listOf(LocalAddEntryDto("content://provider/document/cancelled", null)),
        )
        LocalAddStorage.retireSession(context, cancelledSession)
        workManager.enqueueUniqueWork(
            LocalAddStorage.uniqueWorkName(cancelledSession),
            ExistingWorkPolicy.KEEP,
            cancelledRequest,
        ).result.get(10, TimeUnit.SECONDS)
        workManager.cancelUniqueWork(LocalAddStorage.uniqueWorkName(cancelledSession))
            .result
            .get(10, TimeUnit.SECONDS)

        val legacySession = UUID.randomUUID().toString()
        sessions += legacySession
        LocalAddStorage.saveEntries(
            context,
            legacySession,
            listOf(LocalAddEntryDto("content://provider/document/legacy", null)),
        )

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            historyPlan("https://example.com/f11-local-add-negative"),
        )
        assertTrue(outcome is RestoreOutcome.Completed)
        assertEquals(LocalAddStorage.OWNER_RETIRED, LocalAddStorage.loadWorkOwner(context, cancelledSession)?.state)
        assertNull(LocalAddStorage.loadWorkOwner(context, legacySession))
        assertTrue(
            workManager.getWorkInfosForUniqueWork(LocalAddStorage.uniqueWorkName(cancelledSession))
                .get(10, TimeUnit.SECONDS)
                .none { !it.state.isFinished },
        )
        assertTrue(
            workManager.getWorkInfosForUniqueWork(LocalAddStorage.uniqueWorkName(legacySession))
                .get(10, TimeUnit.SECONDS)
                .none { !it.state.isFinished },
        )
    }

    private suspend fun createLiveSession(): Pair<String, String> {
        val sessionId = UUID.randomUUID().toString()
        sessions += sessionId
        val request = OneTimeWorkRequestBuilder<LocalAddWorker>()
            .setInputData(workDataOf(LocalAddWorker.KEY_SESSION_ID to sessionId))
            .addTag(LocalAddWorker.TAG)
            .build()
        LocalAddStorage.beginSession(
            context,
            sessionId,
            request.id.toString(),
            listOf(LocalAddEntryDto("content://provider/document/$sessionId", null)),
        )
        workManager.enqueueUniqueWork(
            LocalAddStorage.uniqueWorkName(sessionId),
            ExistingWorkPolicy.KEEP,
            request,
        ).result.get(10, TimeUnit.SECONDS)
        return sessionId to request.id.toString()
    }

    private suspend fun awaitFinished(id: UUID): WorkInfo = withContext(Dispatchers.IO) {
        withTimeout(20_000L) {
            while (true) {
                val info = requireNotNull(workManager.getWorkInfoById(id).get(10, TimeUnit.SECONDS))
                if (info.state.isFinished) return@withTimeout info
                kotlinx.coroutines.delay(25L)
            }
            error("unreachable")
        }
    }

    private fun historyPlan(url: String): RestorePlan = BackupRestoreParser.fromTyped(
        RestoreAppDataItem(
            downloads = listOf(
                HistoryItem(
                    id = 0L,
                    url = url,
                    title = "F11 LocalAdd Reset",
                    author = "F11",
                    duration = "1:00",
                    durationSeconds = 60L,
                    thumb = "",
                    type = DownloadType.video,
                    time = System.currentTimeMillis() / 1000L,
                    downloadPath = listOf("/destination/$url"),
                    website = "F11",
                    format = Format(container = "mp4"),
                    filesize = 3L,
                    downloadId = 0L,
                ),
            ),
        ),
    )
}