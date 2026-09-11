package com.ireum.ytdl.database

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesMonthlyConfig
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesWeeklyConfig
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObserveSourcesConfigurationOwnershipTest {
    private lateinit var database: DBManager
    private lateinit var repository: ObserveSourcesRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        repository = ObserveSourcesRepository(
            database.observeSourcesDao,
            WorkManager.getInstance(context),
            PreferenceManager.getDefaultSharedPreferences(context),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun ordinaryEditPreservesWorkerRuntimeAndMembershipState() = runBlocking {
        val id = database.observeSourcesDao.insert(
            source(
                runCount = 4,
                endsAfterCount = 5,
                runHistory = mutableListOf("run-one", "run-two"),
                runInProgress = true,
                currentRunStatus = "FETCHING",
                alreadyProcessedLinks = mutableListOf("processed"),
                ignoredLinks = mutableListOf("ignored"),
                retryPromptedLinks = mutableListOf("prompted"),
                observedLinks = mutableListOf("observed"),
            )
        )
        val edit = database.observeSourcesDao.getByID(id).copy(
            name = "edited source",
            url = "https://example.com/edited",
            everyNr = 2,
            endsAfterCount = 5,
            runCount = 0,
            runHistory = mutableListOf(),
            runInProgress = false,
            currentRunStatus = "",
            alreadyProcessedLinks = mutableListOf(),
            ignoredLinks = mutableListOf(),
            retryPromptedLinks = mutableListOf(),
            observedLinks = mutableListOf(),
            status = ObserveSourcesRepository.SourceStatus.STOPPED,
        )

        repository.updateConfiguration(edit, resetProcessedLinks = false)

        val persisted = database.observeSourcesDao.getByID(id)
        assertEquals("edited source", persisted.name)
        assertEquals("https://example.com/edited", persisted.url)
        assertEquals(2, persisted.everyNr)
        assertEquals(4, persisted.runCount)
        assertEquals(5, persisted.endsAfterCount)
        assertEquals(mutableListOf("run-one", "run-two"), persisted.runHistory)
        assertTrue(persisted.runInProgress)
        assertEquals("FETCHING", persisted.currentRunStatus)
        assertEquals(ObserveSourcesRepository.SourceStatus.ACTIVE, persisted.status)
        assertEquals(mutableListOf("processed"), persisted.alreadyProcessedLinks)
        assertEquals(mutableListOf("ignored"), persisted.ignoredLinks)
        assertEquals(mutableListOf("prompted"), persisted.retryPromptedLinks)
        assertEquals(mutableListOf("observed"), persisted.observedLinks)
    }

    @Test
    fun explicitProcessedResetClearsOnlyMembershipOwnedByResetAction() = runBlocking {
        val id = database.observeSourcesDao.insert(
            source(
                runCount = 3,
                runHistory = mutableListOf("run"),
                runInProgress = true,
                currentRunStatus = "QUEUED",
                alreadyProcessedLinks = mutableListOf("processed"),
                ignoredLinks = mutableListOf("ignored"),
                retryPromptedLinks = mutableListOf("prompted"),
                observedLinks = mutableListOf("observed"),
            )
        )
        val edit = database.observeSourcesDao.getByID(id).copy(
            name = "reset edit",
            runCount = 0,
            runHistory = mutableListOf(),
            runInProgress = false,
            currentRunStatus = "",
            alreadyProcessedLinks = mutableListOf(),
            ignoredLinks = mutableListOf(),
            retryPromptedLinks = mutableListOf(),
            observedLinks = mutableListOf(),
        )

        repository.updateConfiguration(edit, resetProcessedLinks = true)

        val persisted = database.observeSourcesDao.getByID(id)
        assertEquals("reset edit", persisted.name)
        assertEquals(3, persisted.runCount)
        assertEquals(mutableListOf("run"), persisted.runHistory)
        assertTrue(persisted.runInProgress)
        assertEquals("QUEUED", persisted.currentRunStatus)
        assertTrue(persisted.alreadyProcessedLinks.isEmpty())
        assertTrue(persisted.ignoredLinks.isEmpty())
        assertTrue(persisted.retryPromptedLinks.isEmpty())
        assertTrue(persisted.observedLinks.isEmpty())
    }

    @Test
    fun explicitStartLifecycleUpdateStillResetsRunCount() = runBlocking {
        val id = database.observeSourcesDao.insert(
            source(
                status = ObserveSourcesRepository.SourceStatus.STOPPED,
                runCount = 7,
                runHistory = mutableListOf("prior"),
                runInProgress = false,
                currentRunStatus = "",
            )
        )
        val start = database.observeSourcesDao.getByID(id).copy(
            status = ObserveSourcesRepository.SourceStatus.ACTIVE,
            runCount = 0,
        )

        repository.update(start)

        val persisted = database.observeSourcesDao.getByID(id)
        assertEquals(ObserveSourcesRepository.SourceStatus.ACTIVE, persisted.status)
        assertEquals(0, persisted.runCount)
    }

    private fun source(
        status: ObserveSourcesRepository.SourceStatus = ObserveSourcesRepository.SourceStatus.ACTIVE,
        runCount: Int = 0,
        endsAfterCount: Int = 0,
        runHistory: MutableList<String> = mutableListOf(),
        runInProgress: Boolean = false,
        currentRunStatus: String = "",
        alreadyProcessedLinks: MutableList<String> = mutableListOf(),
        ignoredLinks: MutableList<String> = mutableListOf(),
        retryPromptedLinks: MutableList<String> = mutableListOf(),
        observedLinks: MutableList<String> = mutableListOf(),
    ) = ObserveSourcesItem(
        id = 0L,
        name = "source",
        url = "https://example.com/source",
        downloadItemTemplate = template(),
        everyNr = 1,
        everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
        everyTime = 0L,
        weeklyConfig = ObserveSourcesWeeklyConfig(listOf(1)),
        monthlyConfig = ObserveSourcesMonthlyConfig(1, 0),
        status = status,
        startsTime = 0L,
        endsDate = 0L,
        endsAfterCount = endsAfterCount,
        runCount = runCount,
        getOnlyNewUploads = true,
        retryMissingDownloads = true,
        ignoredLinks = ignoredLinks,
        alreadyProcessedLinks = alreadyProcessedLinks,
        syncWithSource = true,
        excludeShorts = true,
        runHistory = runHistory,
        runInProgress = runInProgress,
        currentRunStatus = currentRunStatus,
        autoAddKeyword = "tag",
        retryPromptedLinks = retryPromptedLinks,
        observedLinks = observedLinks,
    )

    private fun template() = DownloadItem(
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
        downloadPath = "",
        website = "YouTube",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = "Cancelled",
        downloadStartTime = 0L,
        logID = null,
    )
}
