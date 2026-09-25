package com.ireum.ytdl.work

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.util.storage.ConfiguredDownloadArchiveProvider
import com.ireum.ytdl.util.storage.ConfiguredDownloadArchiveStore
import com.ireum.ytdl.util.storage.DownloadArchiveProviderFence
import com.ireum.ytdl.util.storage.DownloadArchiveUnavailableException
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
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.receiver.ObserveRetryDecisionReceiver
import com.ireum.ytdl.util.LinkUtil
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import com.ireum.ytdl.util.SourceSnapshot
import com.ireum.ytdl.work.WorkManagerHandoffRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    private var previousArchivePath: String? = null
    private var hadArchivePath = false
    private var previousSchedulerMode = false
    private var hadSchedulerMode = false
    private val queuedItems = mutableListOf<DownloadItem>()
    private val cancelledMembershipNotifications = mutableListOf<Long>()

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            workManager = WorkManager.getInstance(context)
            workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
            WorkManagerHandoffRecovery.clearForTesting()
            database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
                .addTypeConverter(Converters())
                .allowMainThreadQueries()
                .build()
            WorkManagerHandoffRecovery.databaseForTesting = database
            preferences = PreferenceManager.getDefaultSharedPreferences(context)
            hadDuplicateMode = preferences.contains("prevent_duplicate_downloads")
            previousDuplicateMode = preferences.getString("prevent_duplicate_downloads", null)
            hadArchivePath = preferences.contains(ConfiguredDownloadArchiveStore.PREFERENCE_KEY)
            previousArchivePath =
                preferences.getString(ConfiguredDownloadArchiveStore.PREFERENCE_KEY, null)
            hadSchedulerMode = preferences.contains("use_scheduler")
            previousSchedulerMode = preferences.getBoolean("use_scheduler", false)
            preferences.edit()
                .putString("prevent_duplicate_downloads", "")
                .putBoolean("use_scheduler", false)
                .putBoolean("metered_networks", true)
                .commit()

            queuedItems.clear()
            cancelledMembershipNotifications.clear()
            ObserveSourceWorkerEffectTestHooks.dbManagerForTesting = database
            ObserveSourceWorkerEffectTestHooks.startDownloadWorkerForTesting = { items, _ ->
                queuedItems += items.map { it.copy() }
                Result.success("captured")
            }
            ObserveSourceWorkerEffectTestHooks.retryConfirmationAvailableForTesting = false
            ObserveSourceWorkerEffectTestHooks.membershipWaitingNotificationCancelledForTesting = {
                cancelledMembershipNotifications += it
            }
            ObserveSourcesRepository.beforeFinalEffectForTesting = null
            ObserveSourcesRepository.afterDurableStopBeforeCancellationForTesting = null
            ObserveSourcesRepository.observeRequestCreatedForTesting = null
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
            WorkManagerHandoffRecovery.clearForTesting()
        ObserveSourceWorkerEffectTestHooks.clearForTesting()
        ConfiguredDownloadArchiveStore.providerForTesting = null
        DownloadArchiveProviderFence.clearAllForTesting(context)
        ObserveSourcesRepository.beforeFinalEffectForTesting = null
        ObserveSourcesRepository.afterDurableStopBeforeCancellationForTesting = null
        ObserveSourcesRepository.observeRequestCreatedForTesting = null
            if (::database.isInitialized) database.close()
            val editor = preferences.edit()
            if (hadDuplicateMode) editor.putString("prevent_duplicate_downloads", previousDuplicateMode)
            else editor.remove("prevent_duplicate_downloads")
            if (hadArchivePath) {
                editor.putString(ConfiguredDownloadArchiveStore.PREFERENCE_KEY, previousArchivePath)
            } else {
                editor.remove(ConfiguredDownloadArchiveStore.PREFERENCE_KEY)
            }
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
        val recurrence = requireNotNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.OBSERVE_RECURRENCE,
                sourceId.toString(),
            ),
        )
        assertEquals(sourceId, recurrence.sourceId)
        assertEquals(persisted.configurationGeneration, recurrence.sourceConfigurationGeneration)
        assertEquals(WorkManagerHandoffCarrier.PENDING_ENQUEUE, recurrence.state)
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
        val initial = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(0, initial.runCount)
        val waitingDownloadId = database.downloadDao.insertRaw(
            downloadTemplate().copy(
                status = DownloadRepository.Status.WaitingForMembership.name,
                observeSourceId = sourceId,
                lastIssueCode = "MEMBERSHIP_REQUIRED",
                lastIssueStage = "DOWNLOAD",
            ),
        )

        runWorker(sourceId, SourceSnapshot.partial(listOf(result("https://youtu.be/threshold"))))

        val persisted = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(1, persisted.runCount)
        assertEquals(ObserveSourcesRepository.SourceStatus.STOPPED, persisted.status)
        assertEquals(
            listOf("https://youtu.be/template", "https://youtu.be/threshold"),
            queuedItems.map { it.url },
        )
        // Requeue removes the waiting prompt, then end-count revocation also
        // cancels the queued membership retry owned by the stopped source.
        assertEquals(
            listOf(waitingDownloadId, waitingDownloadId),
            cancelledMembershipNotifications,
        )
    }

    @Test
    fun legacyUnversionedRequestOnlySchedulesCurrentDurableGeneration() = runBlocking {
        val sourceId = insertSource()
        val current = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        val scheduledInput = CompletableDeferred<Data>()
        ObserveSourcesRepository.observeRequestCreatedForTesting = { id, data ->
            assertEquals(sourceId, id)
            scheduledInput.complete(data)
        }

        val legacyRequest = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
            .setInputData(Data.Builder().putLong(ObserveSourceWorker.INPUT_SOURCE_ID, sourceId).build())
            .build()
        workManager.enqueueUniqueWork(
            "OBSERVE$sourceId",
            ExistingWorkPolicy.REPLACE,
            legacyRequest,
        ).result.get(10, TimeUnit.SECONDS)

        val migratedInput = withTimeout(15_000L) { scheduledInput.await() }
        assertEquals(
            current.configurationGeneration,
            migratedInput.getLong(ObserveSourceWorker.INPUT_CONFIGURATION_GENERATION, -1L),
        )
        val currentWork = withTimeout(15_000L) {
            while (true) {
                val matching = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork("OBSERVE$sourceId")
                        .get(5, TimeUnit.SECONDS)
                        .firstOrNull { info ->
                            !info.state.isFinished &&
                                ObserveSourceWorker.configurationGenerationTag(
                                    current.configurationGeneration,
                                ) in info.tags
                        }
                }
                if (matching != null) return@withTimeout matching
                delay(25L)
            }
            error("unreachable")
        }
        assertEquals(WorkInfo.State.ENQUEUED, currentWork.state)
        assertTrue(queuedItems.isEmpty())
        assertEquals(current.runCount, database.observeSourcesDao.getByIDOrNull(sourceId)?.runCount)
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
    fun staleGenerationHeldBeforeDownloadAdmissionCannotInsertAfterEditWins() = runBlocking {
        val sourceId = insertSource()
        val old = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val url = "https://youtu.be/stale-download-admission"
        ObserveSourcesRepository.beforeFinalEffectForTesting = { effect, id, generation ->
            if (effect == ObserveSourcesRepository.FinalEffect.DOWNLOAD_ADMISSION &&
                id == sourceId && generation == old.configurationGeneration
            ) {
                entered.complete(Unit)
                release.await()
            }
        }
        val execution = async(Dispatchers.IO) {
            runWorker(
                sourceId,
                SourceSnapshot.partial(listOf(result(url)), "generation race"),
                cancelObservationWork = false,
            )
        }
        try {
            withTimeout(30_000L) { entered.await() }
            assertTrue(ObserveSourcesRepository(
                database.observeSourcesDao,
                workManager,
                preferences,
                context,
            ).reconfigure(old.copy(name = "edit wins before admission"), resetProcessedLinks = false))
        } finally {
            release.complete(Unit)
        }
        assertEquals(WorkInfo.State.SUCCEEDED, execution.await().state)
        assertTrue(database.downloadDao.getAllDownloadsList().none { it.url == url })
        assertTrue(queuedItems.isEmpty())
        assertEquals(2L, database.observeSourcesDao.getByIDOrNull(sourceId)?.configurationGeneration)
    }

    @Test
    fun staleGenerationHeldBeforeDestructiveSyncCannotDeleteHistoryAfterStopWins() = runBlocking {
        val oldUrl = "https://youtu.be/stale-destructive-sync"
        val historyId = database.historyDao.insertAndGetIdRaw(history(oldUrl))
        val sourceId = insertSource(syncWithSource = true, alreadyProcessedLinks = mutableListOf(oldUrl))
        val old = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        ObserveSourceWorkerEffectTestHooks.sourceSnapshotForTesting = { SourceSnapshot.authoritative(emptyList()) }
        ObserveSourcesRepository.beforeFinalEffectForTesting = { effect, id, generation ->
            if (effect == ObserveSourcesRepository.FinalEffect.DESTRUCTIVE_SYNC &&
                id == sourceId && generation == old.configurationGeneration
            ) {
                entered.complete(Unit)
                release.await()
            }
        }

        val execution = async(Dispatchers.IO) {
            runWorker(sourceId, SourceSnapshot.authoritative(emptyList()), cancelObservationWork = false)
        }
        try {
            withTimeout(30_000L) { entered.await() }
            assertNotNull(ObserveSourcesRepository(
                database.observeSourcesDao,
                workManager,
                preferences,
                context,
            ).stop(old))
        } finally {
            release.complete(Unit)
        }
        assertEquals(WorkInfo.State.SUCCEEDED, execution.await().state)
        assertNotNull(database.historyDao.getNullableItem(historyId))
        assertEquals(ObserveSourcesRepository.SourceStatus.STOPPED,
            database.observeSourcesDao.getByIDOrNull(sourceId)?.status)
    }

    @Test
    fun staleGenerationHeldBeforeSuccessorCannotPublishAfterEditWins() = runBlocking {
        val sourceId = insertSource(runCount = 4)
        val old = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var replacementInput: Data? = null
        ObserveSourceWorkerEffectTestHooks.sourceSnapshotForTesting = { SourceSnapshot.partial(emptyList()) }
        ObserveSourcesRepository.beforeFinalEffectForTesting = { effect, id, generation ->
            if (effect == ObserveSourcesRepository.FinalEffect.SUCCESSOR_PUBLICATION &&
                id == sourceId && generation == old.configurationGeneration
            ) {
                entered.complete(Unit)
                release.await()
            }
        }
        ObserveSourcesRepository.observeRequestCreatedForTesting = { id, data ->
            if (id == sourceId) replacementInput = data
        }

        val execution = async(Dispatchers.IO) {
            runWorker(sourceId, SourceSnapshot.partial(emptyList()), cancelObservationWork = false)
        }
        try {
            withTimeout(30_000L) { entered.await() }
            assertTrue(ObserveSourcesRepository(
                database.observeSourcesDao,
                workManager,
                preferences,
                context,
            ).reconfigure(old.copy(name = "new generation owns recurrence"), resetProcessedLinks = false))
        } finally {
            release.complete(Unit)
        }
        assertEquals(WorkInfo.State.SUCCEEDED, execution.await().state)
        val persisted = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(2L, persisted.configurationGeneration)
        assertEquals(4, persisted.runCount)
        val successor = workManager.getWorkInfosForUniqueWork("OBSERVE$sourceId")
            .get(10, TimeUnit.SECONDS).single()
        assertEquals(WorkInfo.State.ENQUEUED, successor.state)
        assertEquals(
            2L,
            requireNotNull(replacementInput).getLong(
                ObserveSourceWorker.INPUT_CONFIGURATION_GENERATION,
                -1L,
            ),
        )
    }

    @Test
    fun persistedOldRequestSelfRefusesAfterNewRepositoryObservesNewGeneration() = runBlocking {
        val sourceId = insertSource(runCount = 6)
        val old = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        val reconstructedRepository = ObserveSourcesRepository(
            database.observeSourcesDao,
            workManager,
            preferences,
            context,
        )
        assertTrue(reconstructedRepository.reconfigure(old.copy(name = "after restart"), false))
        var extractionCount = 0
        ObserveSourceWorkerEffectTestHooks.sourceSnapshotForTesting = {
            extractionCount++
            SourceSnapshot.authoritative(emptyList())
        }

        val info = runWorker(
            sourceId,
            SourceSnapshot.authoritative(emptyList()),
            expectedGeneration = old.configurationGeneration,
            cancelObservationWork = false,
            installSnapshotHook = false,
        )

        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
        assertEquals(0, extractionCount)
        val current = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        assertEquals(2L, current.configurationGeneration)
        assertEquals(6, current.runCount)
    }

    @Test
    fun confirmedRetryFingerprintStillRevokesChangedConfiguration() = runBlocking {
        val sourceId = insertSource(retryMissingDownloads = true)
        val source = requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId))
        val confirmedUrl = "https://youtu.be/confirmed-retry-fingerprint"
        val originalFingerprint = WorkManagerHandoffRecovery.observeConfigFingerprint(source)
        WorkManagerHandoffRecovery.databaseForTesting = database
        try {
            val handoffId = WorkManagerHandoffRecovery.prepareObserveRetryDownload(
                context = context,
                sourceId = sourceId,
                sourceConfigurationGeneration = source.configurationGeneration,
                confirmedUrl = confirmedUrl,
                configFingerprint = originalFingerprint,
            )
            val carrier = requireNotNull(database.workManagerHandoffCarrierDao.get(handoffId))

            // Isolate the specialized fingerprint fence from the ordinary
            // generation fence: this simulates a legacy/config payload change
            // whose generation is unchanged, which must still refuse the retry.
            database.openHelper.writableDatabase.execSQL(
                "UPDATE sources SET name = ? WHERE id = ?",
                arrayOf<Any>("changed without generation", sourceId),
            )
            assertEquals(
                source.configurationGeneration,
                database.observeSourcesDao.getByIDOrNull(sourceId)?.configurationGeneration,
            )
            ObserveSourceWorkerEffectTestHooks.sourceSnapshotForTesting = {
                error("stale confirmed retry must be refused before source extraction")
            }

            val info = runWorker(
                sourceId = sourceId,
                snapshot = SourceSnapshot.failed(IllegalStateException("must not extract")),
                expectedGeneration = source.configurationGeneration,
                confirmedRetryCarrier = carrier,
            )

            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            assertTrue(queuedItems.isEmpty())
            assertNull(database.workManagerHandoffCarrierDao.get(handoffId))
        } finally {
            WorkManagerHandoffRecovery.databaseForTesting = null
        }
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

    @Test
    fun providerBackedArchiveMembershipIsVisibleToQueueDuplicatePreflight() = runBlocking {
        useSafArchive(ArchiveProviderFake("youtube ${ArchiveProviderFake.MEMBER_ID}\n"))
        try {
            val sourceId = insertSource(
                getOnlyNewUploads = true,
                runCount = 0,
                syncWithSource = true,
                alreadyProcessedLinks = mutableListOf("https://youtu.be/old"),
            )
            val memberUrl = "https://youtu.be/${ArchiveProviderFake.MEMBER_ID}"
            val newUrl = "https://youtu.be/${ArchiveProviderFake.NON_MEMBER_ID}"

            runWorker(
                sourceId,
                SourceSnapshot.partial(listOf(result(memberUrl), result(newUrl)), "archive"),
            )

            // The provider-backed membership suppressed exactly the archived
            // item; the unknown item still reached the queue.
            assertEquals(1, queuedItems.size)
            assertEquals(newUrl, queuedItems.single().url)
            // The skipped item was never inserted as a runnable row.
            assertTrue(
                database.downloadDao.getAllDownloadsList().none { it.url == memberUrl },
            )
        } finally {
            ConfiguredDownloadArchiveStore.providerForTesting = null
        }
    }

    @Test
    fun unavailableProviderArchiveWithholdsQueueAdmissionInsteadOfTreatingItAsEmpty() =
        runBlocking {
            useSafArchive(
                ArchiveProviderFake(null).apply {
                    readFailure = DownloadArchiveUnavailableException("permission revoked")
                },
            )
            try {
                val sourceId = insertSource(
                    getOnlyNewUploads = true,
                    runCount = 0,
                    syncWithSource = true,
                    alreadyProcessedLinks = mutableListOf("https://youtu.be/old"),
                )
                val candidateUrl = "https://youtu.be/${ArchiveProviderFake.NON_MEMBER_ID}"

                runWorker(
                    sourceId,
                    SourceSnapshot.partial(listOf(result(candidateUrl)), "archive"),
                )

                // An unreadable archive is not an empty archive: the item is
                // withheld rather than admitted as a new download.
                assertTrue(queuedItems.isEmpty())
                assertTrue(
                    database.downloadDao.getAllDownloadsList()
                        .none { it.url == candidateUrl },
                )
            } finally {
                ConfiguredDownloadArchiveStore.providerForTesting = null
            }
        }

    @Test
    fun unresolvedProviderPromotionFenceWithholdsObserveQueueAdmission() = runBlocking {
        // A provider promotion is unresolved even though the provider document
        // itself is still readable.
        useSafArchive(ArchiveProviderFake("youtube ${ArchiveProviderFake.MEMBER_ID}\n"))
        val authority = ConfiguredDownloadArchiveStore.resolve(context)
        DownloadArchiveProviderFence.install(
            context = context,
            authority = authority,
            downloadId = 91L,
            executionId = "exec-observe-fence",
            generationKey = "fence-key",
        )
        try {
            val sourceId = insertSource(
                getOnlyNewUploads = true,
                runCount = 0,
                syncWithSource = true,
                alreadyProcessedLinks = mutableListOf("https://youtu.be/old"),
            )
            val memberUrl = "https://youtu.be/${ArchiveProviderFake.MEMBER_ID}"

            runWorker(
                sourceId,
                SourceSnapshot.partial(listOf(result(memberUrl)), "archive"),
            )

            // Unknown membership: nothing is queued and no row is inserted.
            assertTrue(queuedItems.isEmpty())
            assertTrue(
                database.downloadDao.getAllDownloadsList().none { it.url == memberUrl },
            )
        } finally {
            DownloadArchiveProviderFence.clear(context, authority)
            ConfiguredDownloadArchiveStore.providerForTesting = null
        }
    }

    private fun useSafArchive(provider: ConfiguredDownloadArchiveProvider) {
        preferences.edit()
            .putString("prevent_duplicate_downloads", "download_archive")
            .putString(
                ConfiguredDownloadArchiveStore.PREFERENCE_KEY,
                "content://com.android.externalstorage.documents/tree/primary%3AYTDLnisx",
            )
            .commit()
        ConfiguredDownloadArchiveStore.providerForTesting = provider
    }

    /** Deterministic stand-in for a persisted SAF tree grant. */
    private class ArchiveProviderFake(private val contents: String?) :
        ConfiguredDownloadArchiveProvider {
        var readFailure: Throwable? = null

        override fun readText(context: Context, treeUri: android.net.Uri): String? {
            readFailure?.let { throw it }
            return contents
        }

        override fun replaceText(
            context: Context,
            treeUri: android.net.Uri,
            text: String,
        ) = Unit

        companion object {
            const val MEMBER_ID = "dQw4w9WgXcQ"
            const val NON_MEMBER_ID = "oHg5SJYRHA0"
        }
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

    private suspend fun runWorker(
        sourceId: Long,
        snapshot: SourceSnapshot,
        expectedGeneration: Long? = null,
        cancelObservationWork: Boolean = true,
        installSnapshotHook: Boolean = true,
        confirmedRetryCarrier: WorkManagerHandoffCarrier? = null,
    ): WorkInfo {
        if (installSnapshotHook) ObserveSourceWorkerEffectTestHooks.sourceSnapshotForTesting = { snapshot }
        val generation = expectedGeneration
            ?: requireNotNull(database.observeSourcesDao.getByIDOrNull(sourceId)).configurationGeneration
        val request = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
            .addTag("observe-source-production-test")
            .setInputData(
                Data.Builder()
                    .putLong(ObserveSourceWorker.INPUT_SOURCE_ID, sourceId)
                    .putLong(ObserveSourceWorker.INPUT_CONFIGURATION_GENERATION, generation)
                    .apply {
                        confirmedRetryCarrier?.let { carrier ->
                            putString(ObserveSourceWorker.INPUT_CONFIRMED_URL, carrier.confirmedUrl)
                            putString(
                                ObserveSourceWorker.INPUT_CONFIRMATION_DECISION,
                                ObserveRetryDecisionReceiver.ACTION_DOWNLOAD,
                            )
                            putString(ObserveSourceWorker.INPUT_HANDOFF_ID, carrier.handoffId)
                            putString(ObserveSourceWorker.INPUT_HANDOFF_REQUEST_ID, carrier.requestId)
                            putString(ObserveSourceWorker.INPUT_CONFIG_FINGERPRINT, carrier.configFingerprint)
                        }
                    }
                    .build(),
            )
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
        if (cancelObservationWork) {
            workManager.cancelUniqueWork("OBSERVE$sourceId").result.get(10, TimeUnit.SECONDS)
        }
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
