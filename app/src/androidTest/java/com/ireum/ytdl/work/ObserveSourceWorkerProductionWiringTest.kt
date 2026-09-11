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
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.AutomaticKeywordRuleKeyword
import com.ireum.ytdl.database.models.AutomaticKeywordSyncStatus
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.util.LinkUtil
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import com.ireum.ytdl.util.SourceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Drives the actual ObserveSourceWorker through WorkManager and an in-memory
 * Room database.  Extraction and the second Download worker are injected only
 * at their external boundaries; run/baseline/filter/absence/persistence logic
 * is the production implementation under test.
 */
@RunWith(AndroidJUnit4::class)
class ObserveSourceWorkerProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var workManager: WorkManager
    private lateinit var preferences: android.content.SharedPreferences
    private var previousDuplicateMode: String? = null
    private var hadDuplicateMode = false
    private var previousSchedulerMode = false
    private var hadSchedulerMode = false
    private val queuedItems = mutableListOf<DownloadItem>()

    @Before
    fun setUp() {
        runBlocking {
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

            queuedItems.clear()
            ObserveSourceWorkerEffectTestHooks.dbManagerForTesting = database
            ObserveSourceWorkerEffectTestHooks.startDownloadWorkerForTesting = { items, _ ->
                queuedItems += items.map { it.copy() }
                Result.success("captured")
            }
            ObserveSourceWorkerEffectTestHooks.retryConfirmationAvailableForTesting = false
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
            ObserveSourceWorkerEffectTestHooks.clearForTesting()
            if (::database.isInitialized) database.close()
            val editor = preferences.edit()
            if (hadDuplicateMode) editor.putString("prevent_duplicate_downloads", previousDuplicateMode)
            else editor.remove("prevent_duplicate_downloads")
            if (hadSchedulerMode) editor.putBoolean("use_scheduler", previousSchedulerMode)
            else editor.remove("use_scheduler")
            editor.commit()
        }
    }

    @Test
    fun partialFirstRunUsesProductionWorkerWithoutBaselineOrAbsenceAndAdvancesRun() = runBlocking {
        val missingHistoryId = database.historyDao.insertAndGetIdRaw(history("https://youtu.be/old"))
        val sourceId = insertSource(
            getOnlyNewUploads = true,
            runCount = 0,
            syncWithSource = true,
            alreadyProcessedLinks = mutableListOf("https://youtu.be/old"),
        )
        val newUrl = "https://youtu.be/new"

        runWorker(sourceId, SourceSnapshot.partial(listOf(result(newUrl)), "conversion dropped"))

        val persisted = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(1, persisted.runCount)
        assertTrue(persisted.ignoredLinks.isEmpty())
        assertTrue(persisted.alreadyProcessedLinks.contains(LinkUtil.canonicalYoutubeVideoUrlOrSelf(newUrl)))
        assertEquals(1, queuedItems.size)
        assertEquals(newUrl, queuedItems.single().url)
        assertNotNull(database.historyDao.getNullableItem(missingHistoryId))
    }

    @Test
    fun laterPartialPositiveItemReachesProductionQueueWithoutAbsenceDeletion() = runBlocking {
        val missingHistoryId = database.historyDao.insertAndGetIdRaw(history("https://youtu.be/old"))
        val sourceId = insertSource(
            getOnlyNewUploads = true,
            runCount = 1,
            syncWithSource = true,
            alreadyProcessedLinks = mutableListOf("https://youtu.be/old"),
        )
        val newUrl = "https://youtu.be/later"

        runWorker(sourceId, SourceSnapshot.partial(listOf(result(newUrl)), "continuation incomplete"))

        val persisted = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(2, persisted.runCount)
        assertEquals(1, queuedItems.size)
        assertEquals(newUrl, queuedItems.single().url)
        assertNotNull(database.historyDao.getNullableItem(missingHistoryId))
    }

    @Test
    fun partialRunAdvancesAndStopsAtEndsAfterCountThroughProductionWorker() = runBlocking {
        val sourceId = insertSource(
            runCount = 0,
            endsAfterCount = 1,
        )

        runWorker(sourceId, SourceSnapshot.partial(listOf(result("https://youtu.be/threshold"))))

        val persisted = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(1, persisted.runCount)
        assertEquals(ObserveSourcesRepository.SourceStatus.STOPPED, persisted.status)
        assertEquals(1, queuedItems.size)
    }

    @Test
    fun failedSnapshotDoesNotAdvanceOrPublishPositiveWorkThroughProductionWorker() = runBlocking {
        val missingHistoryId = database.historyDao.insertAndGetIdRaw(history("https://youtu.be/failed"))
        val sourceId = insertSource(
            runCount = 3,
            syncWithSource = true,
            alreadyProcessedLinks = mutableListOf("https://youtu.be/failed"),
        )

        runWorker(sourceId, SourceSnapshot.failed(IllegalStateException("fetch failed")))

        val persisted = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(3, persisted.runCount)
        assertTrue(queuedItems.isEmpty())
        assertTrue(persisted.alreadyProcessedLinks.contains("https://youtu.be/failed"))
        assertNotNull(database.historyDao.getNullableItem(missingHistoryId))
    }

    @Test
    fun authoritativeFirstRunEstablishesCompleteBaselineAndAdvancesThroughProductionWorker() = runBlocking {
        val sourceId = insertSource(getOnlyNewUploads = true, runCount = 0)
        val existingUrl = "https://youtu.be/existing"

        runWorker(sourceId, SourceSnapshot.authoritative(listOf(result(existingUrl))))

        val persisted = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(1, persisted.runCount)
        assertTrue(persisted.ignoredLinks.contains(LinkUtil.canonicalYoutubeVideoUrlOrSelf(existingUrl)))
        assertTrue(queuedItems.isEmpty())
    }

    @Test
    fun authoritativeEmptySourceRemovesMissingHistoryThroughProductionWorker() = runBlocking {
        val oldUrl = "https://youtu.be/removed"
        val historyId = database.historyDao.insertAndGetIdRaw(history(oldUrl))
        val sourceId = insertSource(
            syncWithSource = true,
            alreadyProcessedLinks = mutableListOf(oldUrl),
        )

        runWorker(sourceId, SourceSnapshot.authoritative(emptyList()))

        assertEquals(null, database.historyDao.getNullableItem(historyId))
        assertTrue(queuedItems.isEmpty())
    }

    @Test
    fun retryConfirmationWaitingKeepsRunCountNonCountingAtProductionBoundary() = runBlocking {
        ObserveSourceWorkerEffectTestHooks.retryConfirmationAvailableForTesting = true
        val existingUrl = "https://youtu.be/retry"
        val sourceId = insertSource(
            runCount = 0,
            retryMissingDownloads = true,
            alreadyProcessedLinks = mutableListOf(existingUrl),
        )

        runWorker(sourceId, SourceSnapshot.partial(listOf(result(existingUrl)), "partial source"))

        val persisted = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(0, persisted.runCount)
        assertTrue(queuedItems.isEmpty())
    }

    @Test
    fun managedKeywordDiscoveryUsesSourceAuthorityThroughProductionWorker() = runBlocking {
        val historyId = database.historyDao.insertAndGetIdRaw(history("https://youtu.be/discovered"))
        val ruleId = database.automaticKeywordRuleDao.insertRule(
            AutomaticKeywordRule(
                conditionValue = "https://www.youtube.com/playlist?list=production",
                conditionKey = "youtube:playlist:production",
                playlistName = "Production playlist",
                baselineComplete = true,
            )
        )
        database.automaticKeywordRuleDao.insertRuleKeywords(
            listOf(AutomaticKeywordRuleKeyword(ruleId, "live", "Live", 0))
        )
        val sourceId = insertSource(
            alreadyProcessedLinks = mutableListOf("https://youtu.be/discovered"),
        )
        val video = result("https://youtu.be/discovered")

        runWorker(sourceId, SourceSnapshot.partial(listOf(video), "partial membership"))

        var rule = requireNotNull(database.automaticKeywordRuleDao.getRule(ruleId))
        assertEquals(AutomaticKeywordSyncStatus.PARTIAL, rule.discoveryStatus)
        assertNull(
            database.automaticKeywordRuleDao.getVideoMatch(
                ruleId,
                AutomaticKeywordNormalizer.videoKey(video.url),
            )
        )
        assertEquals("", database.historyDao.getItem(historyId).keywords)

        runWorker(sourceId, SourceSnapshot.authoritative(listOf(video)))

        rule = requireNotNull(database.automaticKeywordRuleDao.getRule(ruleId))
        assertEquals(AutomaticKeywordSyncStatus.SUCCESS, rule.discoveryStatus)
        assertTrue(
            requireNotNull(
                database.automaticKeywordRuleDao.getVideoMatch(
                    ruleId,
                    AutomaticKeywordNormalizer.videoKey(video.url),
                )
            ).eligibleForAssignment
        )
        assertEquals("Live", database.historyDao.getItem(historyId).keywords)
    }

    private suspend fun insertSource(
        getOnlyNewUploads: Boolean = false,
        runCount: Int = 0,
        endsAfterCount: Int = 0,
        retryMissingDownloads: Boolean = false,
        alreadyProcessedLinks: MutableList<String> = mutableListOf(),
        syncWithSource: Boolean = false,
    ): Long {
        return database.observeSourcesDao.insert(
            ObserveSourcesItem(
                id = 0L,
                name = "production observe source",
                url = "https://www.youtube.com/playlist?list=production",
                downloadItemTemplate = downloadTemplate(),
                everyNr = 1,
                everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
                everyTime = System.currentTimeMillis(),
                weeklyConfig = null,
                monthlyConfig = null,
                status = ObserveSourcesRepository.SourceStatus.ACTIVE,
                startsTime = System.currentTimeMillis(),
                endsDate = 0L,
                endsAfterCount = endsAfterCount,
                runCount = runCount,
                getOnlyNewUploads = getOnlyNewUploads,
                retryMissingDownloads = retryMissingDownloads,
                ignoredLinks = mutableListOf(),
                alreadyProcessedLinks = alreadyProcessedLinks,
                syncWithSource = syncWithSource,
            )
        )
    }

    private suspend fun runWorker(sourceId: Long, snapshot: SourceSnapshot): WorkInfo {
        ObserveSourceWorkerEffectTestHooks.sourceSnapshotForTesting = { snapshot }
        val request = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
            .addTag("observe-source-production-test")
            .setInputData(Data.Builder().putLong(ObserveSourceWorker.INPUT_SOURCE_ID, sourceId).build())
            .build()
        workManager.enqueue(request)
        val info: WorkInfo = withTimeout(30_000L) {
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
        playlistTitle = "Production playlist",
        urls = "",
        chapters = null,
        playlistURL = "https://www.youtube.com/playlist?list=production",
    )

    private fun history(url: String) = HistoryItem(
        id = 0L,
        url = url,
        title = "Old history",
        author = "Author",
        duration = "00:01",
        thumb = "",
        type = DownloadType.video,
        time = System.currentTimeMillis(),
        downloadPath = emptyList(),
        website = "YouTube",
        format = Format(container = "mp4"),
        downloadId = 0L,
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
