package com.ireum.ytdl.work

import android.content.Context
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Exercises the actual LocalAddWorker admission path around the old LIKE precheck. */
@RunWith(AndroidJUnit4::class)
class LocalAddWorkerProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var testRoot: File
    private var previousOpenSession: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        previousOpenSession = preferences.getString("local_add_open_session", null)
        database = Room.inMemoryDatabaseBuilder(
            context,
            DBManager::class.java,
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
        LocalAddWorkerTestHooks.databaseForTesting = database
        LocalAddWorkerTestHooks.matchForTesting = { _, _ -> null }
        LocalAddWorkerTestHooks.metadataForTesting = { uri ->
            if (uri.toString().startsWith("content://provider/document/")) {
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
        val currentSession = LocalAddStorage.consumeOpenSession(context)
        if (!currentSession.isNullOrBlank() && currentSession != previousOpenSession) {
            LocalAddStorage.clearPending(context, currentSession)
        }
        LocalAddStorage.setOpenSession(context, previousOpenSession)
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
        val sessionId = awaitOpenSession()
        val pending = LocalAddStorage.loadPending(context, sessionId)
        assertEquals(listOf(candidatePath), pending.map { it.uri })
        assertEquals(1, database.historyDao.getAll().size)
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

    private suspend fun awaitOpenSession(): String = withContext(Dispatchers.IO) {
        repeat(100) {
            val session = PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString("local_add_open_session", null)
            if (!session.isNullOrBlank()) return@withContext session
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
