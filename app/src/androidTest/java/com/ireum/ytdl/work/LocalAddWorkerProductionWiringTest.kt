package com.ireum.ytdl.work

import android.content.Context
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.util.LocalAddEntryDto
import com.ireum.ytdl.util.LocalAddStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual LocalAddWorker admission path around the old LIKE precheck. */
@RunWith(AndroidJUnit4::class)
class LocalAddWorkerProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var testRoot: File
    private var previousOpenSession: String? = null
    private var previousPendingSessionIds: Set<String> = emptySet()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        previousOpenSession = preferences.getString("local_add_open_session", null)
        previousPendingSessionIds = LocalAddStorage.loadPendingSessionIds(context).toSet()
        database = Room.inMemoryDatabaseBuilder(
            context,
            DBManager::class.java,
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
        LocalAddWorkerTestHooks.databaseForTesting = database
        LocalAddWorkerTestHooks.beforePendingPublicationForTesting = null
        LocalAddWorkerTestHooks.matchForTesting = { _, _ -> null }
        LocalAddWorkerTestHooks.metadataForTesting = { uri ->
            if (uri.scheme == "content" && uri.pathSegments.firstOrNull() == "document") {
                LocalAddWorkerTestHooks.MetadataOverride(displayName = "candidate.mp4", size = 3L)
            } else {
                null
            }
        }
        testRoot = File(context.cacheDir, "local-add-worker-${UUID.randomUUID()}")
        assertTrue(testRoot.mkdirs())
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        LocalAddStorage.loadPendingSessionIds(context)
            .filterNot(previousPendingSessionIds::contains)
            .forEach { LocalAddStorage.clearPending(context, it) }
        LocalAddStorage.setOpenSession(context, previousOpenSession)
        LocalAddWorkerTestHooks.beforePendingPublicationForTesting = null
        LocalAddWorkerTestHooks.databaseForTesting = null
        LocalAddWorkerTestHooks.matchForTesting = null
        LocalAddWorkerTestHooks.metadataForTesting = null
        database.close()
        testRoot.deleteRecursively()
    }

    @Test
    fun workerDoesNotSuppressProviderDocumentPrefixCandidateWithLikePrecheck() = runBlocking {
        val existingPath = "content://provider/document/Achild"
        val candidatePath = "content://provider/document/A"
        database.historyDao.insertRaw(history(existingPath))

        val entries = Gson().toJson(listOf(LocalAddEntryDto(candidatePath, null)))
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<LocalAddWorker>()
            .setInputData(workDataOf(LocalAddWorker.KEY_ENTRIES_JSON to entries))
            .addTag("local-add-worker-production-test")
            .build()
        workManager.enqueue(request)
        val info = awaitFinished(workManager, request.id)

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        val sessionId = awaitNewPendingSession()
        val pending = LocalAddStorage.loadPending(context, sessionId)
        assertEquals(listOf(candidatePath), pending.map { it.uri })
        assertEquals(1, database.historyDao.getAll().size)
    }

    @Test
    fun workerKeepsWhitespaceDistinctOpaqueProviderDocumentsInBatch() = runBlocking {
        val exactPath = DocumentsContract.buildDocumentUri("provider", "A").toString()
        val whitespacePath = DocumentsContract.buildDocumentUri("provider", " A ").toString()
        val entries = Gson().toJson(
            listOf(
                LocalAddEntryDto(exactPath, null),
                LocalAddEntryDto(whitespacePath, null),
            )
        )
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<LocalAddWorker>()
            .setInputData(workDataOf(LocalAddWorker.KEY_ENTRIES_JSON to entries))
            .addTag("local-add-worker-production-test")
            .build()
        workManager.enqueue(request)
        val info = awaitFinished(workManager, request.id)

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        val sessionId = awaitNewPendingSession()
        val pending = LocalAddStorage.loadPending(context, sessionId)
        assertEquals(listOf(exactPath, whitespacePath), pending.map { it.uri })
    }

    @Test
    fun workerKeepsProviderAuthorityNamespaceCaseDistinctInBatch() = runBlocking {
        val firstPath = DocumentsContract.buildDocumentUri("Provider.Example", "A").toString()
        val secondPath = DocumentsContract.buildDocumentUri("provider.example", "A").toString()
        val entries = Gson().toJson(
            listOf(
                LocalAddEntryDto(firstPath, null),
                LocalAddEntryDto(secondPath, null),
            )
        )
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<LocalAddWorker>()
            .setInputData(workDataOf(LocalAddWorker.KEY_ENTRIES_JSON to entries))
            .addTag("local-add-worker-production-test")
            .build()
        workManager.enqueue(request)
        val info = awaitFinished(workManager, request.id)

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        val sessionId = awaitNewPendingSession()
        val pending = LocalAddStorage.loadPending(context, sessionId)
        assertEquals(listOf(firstPath, secondPath), pending.map { it.uri })
    }

    private suspend fun awaitFinished(workManager: WorkManager, id: UUID): WorkInfo =
        withContext(Dispatchers.IO) {
            repeat(240) {
                val info = runCatching {
                    workManager.getWorkInfoById(id).get(1, TimeUnit.SECONDS)
                }.getOrNull()
                if (info?.state?.isFinished == true) return@withContext info
                Thread.sleep(250L)
            }
            error("Timed out waiting for LocalAddWorker $id")
        }

    @Test
    fun openedOlderPendingSessionRemainsDiscoverableAfterNewWorkerPublishes() = runBlocking {
        val workManager = WorkManager.getInstance(context)
        suspend fun publish(path: String): String {
            val request = OneTimeWorkRequestBuilder<LocalAddWorker>()
                .setInputData(workDataOf(
                    LocalAddWorker.KEY_ENTRIES_JSON to Gson().toJson(listOf(LocalAddEntryDto(path, null))),
                ))
                .addTag("local-add-worker-pending-index-test")
                .build()
            workManager.enqueue(request)
            assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(workManager, request.id).state)
            return awaitNewPendingSession()
        }

        val firstId = publish("content://provider/document/open-before-second")
        assertEquals(1, LocalAddStorage.loadPending(context, firstId).size)
        val secondId = publish("content://provider/document/second-session")
        assertEquals(setOf(firstId, secondId),
            LocalAddStorage.loadPendingSessionIds(context).filterNot(previousPendingSessionIds::contains).toSet())

        LocalAddStorage.clearPending(context, firstId)
        assertEquals(listOf(secondId),
            LocalAddStorage.loadPendingSessionIds(context).filterNot(previousPendingSessionIds::contains))
    }

    @Test
    fun threeConcurrentRealWorkersPublishIndependentlyDiscoverableSessions() = runBlocking {
        val entered = CountDownLatch(3)
        val release = CountDownLatch(1)
        val sessionIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        LocalAddWorkerTestHooks.beforePendingPublicationForTesting = { sessionId ->
            sessionIds += sessionId
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "pending-session publication was not released" }
        }
        val workManager = WorkManager.getInstance(context)
        val requests = (0 until 3).map { index ->
            OneTimeWorkRequestBuilder<LocalAddWorker>()
                .setInputData(workDataOf(
                    LocalAddWorker.KEY_ENTRIES_JSON to Gson().toJson(
                        listOf(LocalAddEntryDto("content://provider/document/concurrent-$index", null)),
                    ),
                ))
                .addTag("local-add-worker-pending-index-test")
                .build()
                .also(workManager::enqueue)
        }
        try {
            assertTrue("all workers must reach the exact pending publication boundary",
                withContext(Dispatchers.IO) { entered.await(30, TimeUnit.SECONDS) })
        } finally {
            release.countDown()
        }
        requests.forEach { assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(workManager, it.id).state) }
        val published = LocalAddStorage.loadPendingSessionIds(context)
            .filterNot(previousPendingSessionIds::contains)
        assertEquals(3, published.size)
        assertEquals(3, sessionIds.size)
        assertTrue(published.containsAll(sessionIds))
        assertEquals(3, published.map(LocalAddStorage::pendingNotificationTag).toSet().size)
        published.forEach { assertEquals(1, LocalAddStorage.loadPending(context, it).size) }
    }

    @Test
    fun legacyOpenPointerIsAdoptedOnlyWhenItsExactPayloadExists() = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val validId = UUID.randomUUID().toString()
        val missingId = UUID.randomUUID().toString()
        LocalAddStorage.savePending(context, validId, listOf(
            com.ireum.ytdl.util.LocalAddCandidateDto(
                uri = "content://provider/document/legacy", treeUri = null, title = "legacy",
                ext = "mp4", size = 1L, durationSeconds = 0,
            ),
        ))
        LocalAddStorage.setOpenSession(context, validId)
        assertTrue(LocalAddStorage.loadPendingSessionIds(context).contains(validId))
        assertTrue(preferences.getString("local_add_open_session", null) != validId)
        LocalAddStorage.setOpenSession(context, missingId)
        assertFalse(LocalAddStorage.loadPendingSessionIds(context).contains(missingId))
    }

    private suspend fun awaitNewPendingSession(): String = withContext(Dispatchers.IO) {
        repeat(100) {
            val session = LocalAddStorage.loadPendingSessionIds(context)
                .firstOrNull { it !in previousPendingSessionIds }
            if (session != null) return@withContext session
            Thread.sleep(100L)
        }
        error("LocalAddWorker did not publish a pending session")
    }

    private fun history(path: String) = HistoryItem(
        id = 0,
        url = "url:existing",
        title = "Existing",
        author = "Creator",
        artist = "",
        duration = "00:01:00",
        durationSeconds = 60L,
        thumb = "",
        type = DownloadType.video,
        time = System.currentTimeMillis() / 1000L,
        downloadPath = listOf(path),
        website = "local",
        format = Format(format_id = "local", container = "mp4"),
        filesize = 3L,
        downloadId = 0,
    )
}
