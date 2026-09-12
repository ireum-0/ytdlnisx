package com.ireum.ytdl.work

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.util.SourceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Regression for the exact Observe final-admission -> worker-claim -> Observe
 * continuation ordering. The real ObserveSourceWorker owns publication; the
 * hook only places the production DAO claim at the deterministic boundary
 * after the insert transaction has committed and before the producer resumes.
 */
@RunWith(AndroidJUnit4::class)
class ObserveSourcePostInsertClaimProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var workManager: WorkManager
    private lateinit var preferences: android.content.SharedPreferences
    private var previousDuplicateMode: String? = null
    private var hadDuplicateMode = false
    private var previousSchedulerMode = false
    private var hadSchedulerMode = false

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        hadDuplicateMode = preferences.contains("prevent_duplicate_downloads")
        previousDuplicateMode = preferences.getString("prevent_duplicate_downloads", null)
        hadSchedulerMode = preferences.contains("use_scheduler")
        previousSchedulerMode = preferences.getBoolean("use_scheduler", false)
        preferences.edit()
            .putString("prevent_duplicate_downloads", "")
            .putBoolean("use_scheduler", false)
            .putBoolean("metered_networks", true)
            .commit()

        ObserveSourceWorkerEffectTestHooks.dbManagerForTesting = database
        ObserveSourceWorkerEffectTestHooks.retryConfirmationAvailableForTesting = false
        ObserveSourceWorkerEffectTestHooks.startDownloadWorkerForTesting = { _, _ ->
            Result.success("captured")
        }
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        ObserveSourceWorkerEffectTestHooks.clearForTesting()
        if (::database.isInitialized) database.close()
        val editor = preferences.edit()
        if (hadDuplicateMode) editor.putString("prevent_duplicate_downloads", previousDuplicateMode)
        else editor.remove("prevent_duplicate_downloads")
        if (hadSchedulerMode) editor.putBoolean("use_scheduler", previousSchedulerMode)
        else editor.remove("use_scheduler")
        editor.commit()
        Unit
    }

    @Test
    fun workerClaimAfterFinalInsertSurvivesObserveContinuation() = runBlocking {
        val sourceId = insertSource()
        val observedUrl = "https://youtu.be/post-insert-claim"
        val executionId = "observe-post-insert-worker-claim"
        var claimedDownloadId = 0L

        ObserveSourceWorkerEffectTestHooks.afterFinalAdmissionInsertForTesting = { inserted ->
            claimedDownloadId = inserted.id
            val claimed = database.downloadDao.claimDownloadForWorkerAndRead(
                id = inserted.id,
                expectedOperationId = inserted.operationId,
                expectedRetryAttempt = inserted.retryAttempt,
                executionId = executionId,
            )
            assertNotNull("freshly admitted row must be claimable", claimed)
            assertEquals(DownloadRepository.Status.Active.name, claimed!!.status)
            assertEquals(executionId, claimed.executionId)
        }
        ObserveSourceWorkerEffectTestHooks.sourceSnapshotForTesting = {
            SourceSnapshot.partial(
                listOf(result(observedUrl)),
                "deterministic post-insert claim interleaving",
            )
        }

        val info = runWorker(sourceId)

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertTrue("claim hook must execute", claimedDownloadId > 0L)
        val persisted = requireNotNull(database.downloadDao.getNullableDownloadById(claimedDownloadId))
        assertEquals(DownloadRepository.Status.Active.name, persisted.status)
        assertEquals(executionId, persisted.executionId)
    }

    private suspend fun insertSource(): Long = database.observeSourcesDao.insert(
        ObserveSourcesItem(
            id = 0L,
            name = "post-insert claim source",
            url = "https://www.youtube.com/playlist?list=post-insert-claim",
            downloadItemTemplate = downloadTemplate(),
            everyNr = 1,
            everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
            everyTime = System.currentTimeMillis(),
            weeklyConfig = null,
            monthlyConfig = null,
            status = ObserveSourcesRepository.SourceStatus.ACTIVE,
            startsTime = System.currentTimeMillis(),
            endsDate = 0L,
            endsAfterCount = 0,
            runCount = 0,
            getOnlyNewUploads = false,
            retryMissingDownloads = false,
            ignoredLinks = mutableListOf(),
            alreadyProcessedLinks = mutableListOf(),
            syncWithSource = false,
        )
    )

    private suspend fun runWorker(sourceId: Long): WorkInfo {
        val request = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
            .addTag("observe-post-insert-claim-test")
            .setInputData(Data.Builder().putLong(ObserveSourceWorker.INPUT_SOURCE_ID, sourceId).build())
            .build()
        workManager.enqueue(request)
        val info = withTimeout(30_000L) {
            while (true) {
                val current = withContext(Dispatchers.IO) {
                    workManager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)
                }
                if (current?.state?.isFinished == true) return@withTimeout checkNotNull(current)
                delay(25L)
            }
            error("unreachable")
        }
        workManager.cancelUniqueWork("OBSERVE$sourceId").result.get(10, TimeUnit.SECONDS)
        return info
    }

    private fun result(url: String) = ResultItem(
        id = 0L,
        url = url,
        title = "Observed video",
        author = "Author",
        duration = "00:01",
        thumb = "",
        website = "YouTube",
        playlistTitle = "Post-insert claim playlist",
        urls = "",
        chapters = null,
        playlistURL = "https://www.youtube.com/playlist?list=post-insert-claim",
    )

    private fun downloadTemplate() = DownloadItem(
        id = 0L,
        url = "https://youtu.be/template",
        title = "Template",
        author = "",
        thumb = "",
        duration = "",
        type = DownloadType.video,
        format = Format(container = "mp4"),
        container = "mp4",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = context.filesDir.absolutePath,
        website = "YouTube",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = DownloadRepository.Status.Cancelled.name,
        downloadStartTime = 0L,
        logID = null,
    )
}
