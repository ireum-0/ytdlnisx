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
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ObserveSourceGenerationOwnershipProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var workManager: WorkManager
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var repository: ObserveSourcesRepository
    private var oldMetered: Boolean? = null

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
        oldMetered = preferences.takeIf { it.contains("metered_networks") }
            ?.getBoolean("metered_networks", true)
        preferences.edit().putBoolean("metered_networks", true).commit()
        repository = ObserveSourcesRepository(
            database.observeSourcesDao,
            workManager,
            preferences,
            context,
        )
        clearRepositoryHooks()
    }

    @After
    fun tearDown() = runBlocking {
        clearRepositoryHooks()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        if (::database.isInitialized) database.close()
        preferences.edit().apply {
            if (oldMetered == null) remove("metered_networks")
            else putBoolean("metered_networks", oldMetered!!)
        }.commit()
        Unit
    }

    @Test
    fun scheduledRequestCarriesExactDurableGeneration() = runBlocking {
        var capturedInput: androidx.work.Data? = null
        ObserveSourcesRepository.observeRequestCreatedForTesting = { _, data -> capturedInput = data }
        val sourceId = repository.insertAndSchedule(source("scheduled"))
        assertTrue(sourceId > 0L)
        val persisted = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(1L, persisted.configurationGeneration)

        val work = workManager.getWorkInfosForUniqueWork("OBSERVE$sourceId")
            .get(10, TimeUnit.SECONDS)
            .single()
        assertEquals(
            persisted.configurationGeneration,
            requireNotNull(capturedInput).getLong(
                com.ireum.ytdl.work.ObserveSourceWorker.INPUT_CONFIGURATION_GENERATION,
                -1L,
            ),
        )
        assertEquals(
            sourceId,
            requireNotNull(capturedInput).getLong(
                com.ireum.ytdl.work.ObserveSourceWorker.INPUT_SOURCE_ID,
                -1L,
            ),
        )
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, work.state)
    }

    @Test
    fun legacyReconciliationDoesNotReplaceAlreadyQueuedCurrentGeneration() = runBlocking {
        val sourceId = repository.insertAndSchedule(source("legacy-current"))
        assertTrue(sourceId > 0L)
        val before = workManager.getWorkInfosForUniqueWork("OBSERVE$sourceId")
            .get(10, TimeUnit.SECONDS)
            .single()
        val current = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))

        assertTrue(repository.reconcileLegacyRequest(sourceId, "legacy-request-id"))

        val after = workManager.getWorkInfosForUniqueWork("OBSERVE$sourceId")
            .get(10, TimeUnit.SECONDS)
            .single()
        assertEquals(before.id, after.id)
        assertTrue(
            com.ireum.ytdl.work.ObserveSourceWorker.configurationGenerationTag(
                current.configurationGeneration,
            ) in after.tags,
        )
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, after.state)
    }

    @Test
    fun configurationEditPreservesWorkerStateUnlessProcessedLinkResetIsExplicit() = runBlocking {
        val sourceId = repository.insert(source("ownership"))
        val staleUi = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
            .copy(name = "edited configuration")
        val initial = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        val workerState = initial.copy(
            runCount = 8,
            runHistory = mutableListOf("run-a", "run-b"),
            runInProgress = true,
            currentRunStatus = "extracting",
            ignoredLinks = mutableListOf("ignored"),
            alreadyProcessedLinks = mutableListOf("processed"),
            retryPromptedLinks = mutableListOf("prompted"),
            observedLinks = mutableListOf("observed"),
        )
        assertEquals(1, database.observeSourcesDao.updateRuntimeIfGeneration(
            id = sourceId,
            expectedGeneration = initial.configurationGeneration,
            runCount = workerState.runCount,
            ignoredLinks = workerState.ignoredLinks,
            alreadyProcessedLinks = workerState.alreadyProcessedLinks,
            runHistory = workerState.runHistory,
            runInProgress = workerState.runInProgress,
            currentRunStatus = workerState.currentRunStatus,
            retryPromptedLinks = workerState.retryPromptedLinks,
            observedLinks = workerState.observedLinks,
        ))

        assertTrue(repository.reconfigure(staleUi, resetProcessedLinks = false))
        val afterEdit = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(2L, afterEdit.configurationGeneration)
        assertEquals("edited configuration", afterEdit.name)
        assertRuntimeEquals(workerState, afterEdit)

        assertTrue(repository.reconfigure(afterEdit.copy(name = "explicit reset"), resetProcessedLinks = true))
        val afterReset = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(3L, afterReset.configurationGeneration)
        assertEquals(8, afterReset.runCount)
        assertEquals(listOf("run-a", "run-b"), afterReset.runHistory)
        assertTrue(afterReset.runInProgress)
        assertEquals("extracting", afterReset.currentRunStatus)
        assertTrue(afterReset.ignoredLinks.isEmpty())
        assertTrue(afterReset.alreadyProcessedLinks.isEmpty())
        assertTrue(afterReset.retryPromptedLinks.isEmpty())
        assertTrue(afterReset.observedLinks.isEmpty())
    }

    @Test
    fun stopRevokesDurableAuthorityBeforeWorkCancellation() = runBlocking {
        val sourceId = repository.insertAndSchedule(source("stop-order"))
        val old = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        ObserveSourcesRepository.afterDurableStopBeforeCancellationForTesting = { id, newGeneration ->
            assertEquals(sourceId, id)
            assertEquals(old.configurationGeneration + 1L, newGeneration)
            entered.complete(Unit)
            release.await()
        }

        val stop = async(Dispatchers.IO) { repository.stop(old) }
        try {
            withTimeout(10_000L) { entered.await() }
            val revoked = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
            assertEquals(ObserveSourcesRepository.SourceStatus.STOPPED, revoked.status)
            assertEquals(old.configurationGeneration + 1L, revoked.configurationGeneration)
            assertEquals(
                androidx.work.WorkInfo.State.ENQUEUED,
                workManager.getWorkInfosForUniqueWork("OBSERVE$sourceId")
                    .get(10, TimeUnit.SECONDS).single().state,
            )
        } finally {
            release.complete(Unit)
        }
        assertNotNull(stop.await())
        assertEquals(
            androidx.work.WorkInfo.State.CANCELLED,
            awaitUniqueState(sourceId, androidx.work.WorkInfo.State.CANCELLED),
        )
        assertTrue(repository.withActiveGeneration(sourceId, old.configurationGeneration) { "revived" } is
            ObserveSourcesRepository.GenerationResult.Stale)
    }

    @Test
    fun abaEditUsesDistinctGenerationsAndDeleteMakesOldAuthorityAbsent() = runBlocking {
        val sourceId = repository.insert(source("aba-A"))
        val firstA = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertTrue(repository.reconfigure(firstA.copy(name = "B"), resetProcessedLinks = false))
        val b = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertTrue(repository.reconfigure(b.copy(name = "aba-A"), resetProcessedLinks = false))
        val secondA = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(firstA.name, secondA.name)
        assertTrue(secondA.configurationGeneration > firstA.configurationGeneration)
        assertTrue(repository.withActiveGeneration(sourceId, firstA.configurationGeneration) { "old A" } is
            ObserveSourcesRepository.GenerationResult.Stale)

        assertNotNull(repository.delete(secondA))
        assertNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertTrue(repository.withActiveGeneration(sourceId, secondA.configurationGeneration) { "deleted" } is
            ObserveSourcesRepository.GenerationResult.Stale)
    }

    @Test
    fun everyFinalEffectLosesWhenEditStopOrDeleteWinsAdmission() = runBlocking {
        val effects = listOf(
            ObserveSourcesRepository.FinalEffect.DOWNLOAD_ADMISSION,
            ObserveSourcesRepository.FinalEffect.DESTRUCTIVE_SYNC,
            ObserveSourcesRepository.FinalEffect.SUCCESSOR_PUBLICATION,
        )
        val mutations = listOf("edit", "stop", "delete")
        for (effect in effects) {
            for (mutation in mutations) {
                val sourceId = repository.insert(source("${effect.name}-$mutation"))
                val old = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var finalEffectRan = false
                ObserveSourcesRepository.beforeFinalEffectForTesting = { actual, id, generation ->
                    if (actual == effect && id == sourceId && generation == old.configurationGeneration) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                val stalePublication = async(Dispatchers.IO) {
                    if (effect == ObserveSourcesRepository.FinalEffect.SUCCESSOR_PUBLICATION) {
                        repository.finishRunAndSchedule(old.copy(runCount = old.runCount + 1), revoke = false).committed
                    } else {
                        repository.withActiveGeneration(sourceId, old.configurationGeneration, effect) {
                            finalEffectRan = true
                        } is ObserveSourcesRepository.GenerationResult.Current
                    }
                }
                try {
                    withTimeout(10_000L) { entered.await() }
                    when (mutation) {
                        "edit" -> assertTrue(repository.reconfigure(old.copy(name = "won-edit"), false))
                        "stop" -> assertNotNull(repository.stop(old))
                        "delete" -> assertNotNull(repository.delete(old))
                    }
                } finally {
                    release.complete(Unit)
                }
                assertFalse(stalePublication.await())
                assertFalse(finalEffectRan)
            }
        }
    }

    private suspend fun awaitUniqueState(sourceId: Long, target: androidx.work.WorkInfo.State) =
        withTimeout(10_000L) {
            while (true) {
                val states = workManager.getWorkInfosForUniqueWork("OBSERVE$sourceId")
                    .get(5, TimeUnit.SECONDS).map { it.state }
                if (states.any { it == target }) return@withTimeout target
                kotlinx.coroutines.yield()
            }
            error("unreachable")
        }

    private fun assertRuntimeEquals(expected: ObserveSourcesItem, actual: ObserveSourcesItem) {
        assertEquals(expected.runCount, actual.runCount)
        assertEquals(expected.runHistory, actual.runHistory)
        assertEquals(expected.runInProgress, actual.runInProgress)
        assertEquals(expected.currentRunStatus, actual.currentRunStatus)
        assertEquals(expected.ignoredLinks, actual.ignoredLinks)
        assertEquals(expected.alreadyProcessedLinks, actual.alreadyProcessedLinks)
        assertEquals(expected.retryPromptedLinks, actual.retryPromptedLinks)
        assertEquals(expected.observedLinks, actual.observedLinks)
    }

    private suspend fun clearRepositoryHooks() {
        ObserveSourcesRepository.beforeFinalEffectForTesting = null
        ObserveSourcesRepository.afterDurableStopBeforeCancellationForTesting = null
        ObserveSourcesRepository.observeRequestCreatedForTesting = null
    }

    private suspend fun source(name: String) = ObserveSourcesItem(
        id = 0L,
        name = name,
        url = "https://www.youtube.com/playlist?list=$name",
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
        status = "Cancelled",
        downloadStartTime = 0L,
        logID = null,
    )
}
