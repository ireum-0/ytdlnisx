package com.ireum.ytdl.work

import android.content.Context
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
import com.ireum.ytdl.database.models.AutomaticKeywordRuleVideoMatch
import com.ireum.ytdl.database.models.AutomaticKeywordSyncError
import com.ireum.ytdl.database.models.AutomaticKeywordSyncStatus
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import com.ireum.ytdl.util.SourceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Drives AutomaticKeywordRuleSyncWorker through WorkManager and an in-memory
 * Room database.  Only source extraction and the database instance are
 * injected; the worker's authority gate, rule reread, engine selection, and
 * durable status/match/assignment writes are the production path.
 */
@RunWith(AndroidJUnit4::class)
class AutomaticKeywordRuleSyncWorkerProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        AutomaticKeywordRuleSyncWorkerTestHooks.dbManagerForTesting = database
        AutomaticKeywordRuleSyncWorkerTestHooks.sourceSnapshotForTesting = null
        AutomaticKeywordRuleSyncWorkerTestHooks.afterFetchForTesting = null
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        AutomaticKeywordRuleSyncWorkerTestHooks.clearForTesting()
        database.close()
    }

    @Test
    fun partialEmptyInitialBaselineRemainsIncompleteThroughProductionWorker() = runBlocking {
        val existingHistoryId = insertHistory("https://youtu.be/existing")
        val ruleId = insertRule(baselineComplete = false)

        val info = runWorker(
            ruleId,
            SourceSnapshot.partial(emptyList(), "conversion dropped"),
        )

        val rule = requireNotNull(database.automaticKeywordRuleDao.getRule(ruleId))
        assertEquals(AutomaticKeywordSyncStatus.PARTIAL, rule.manualSyncStatus)
        assertFalse(rule.baselineComplete)
        assertTrue(database.automaticKeywordRuleDao.getAllVideoMatches().isEmpty())
        assertTrue(database.automaticKeywordRuleDao.getAssignmentsRaw(existingHistoryId).isEmpty())
        assertEquals("", database.historyDao.getItem(existingHistoryId).keywords)
        assertEquals(0, info.runAttemptCount)
        assertTrue(info.state == WorkInfo.State.ENQUEUED || info.state.isFinished)
    }

    @Test
    fun partialApplyExistingDoesNotConsumePendingSyncThroughProductionWorker() = runBlocking {
        val existingHistoryId = insertHistory("https://youtu.be/existing")
        val ruleId = insertRule(
            baselineComplete = true,
            pendingApplyToExisting = true,
        )

        val info = runWorker(
            ruleId,
            SourceSnapshot.partial(
                listOf(result("https://youtu.be/existing")),
                "pagination incomplete",
            ),
        )

        val rule = requireNotNull(database.automaticKeywordRuleDao.getRule(ruleId))
        assertEquals(AutomaticKeywordSyncStatus.PARTIAL, rule.manualSyncStatus)
        assertTrue(rule.pendingApplyToExisting)
        assertTrue(database.automaticKeywordRuleDao.getAllVideoMatches().isEmpty())
        assertTrue(database.automaticKeywordRuleDao.getAssignmentsRaw(existingHistoryId).isEmpty())
        assertEquals("", database.historyDao.getItem(existingHistoryId).keywords)
        assertEquals(0, info.runAttemptCount)
        assertTrue(info.state == WorkInfo.State.ENQUEUED || info.state.isFinished)
    }

    @Test
    fun failedSnapshotPreservesBaselineMatchesAndAssignmentsThroughProductionWorker() = runBlocking {
        val historyId = insertHistory("https://youtu.be/existing")
        val ruleId = insertRule(baselineComplete = true)
        database.automaticKeywordRuleDao.insertVideoMatch(
            AutomaticKeywordRuleVideoMatch(
                ruleId = ruleId,
                videoKey = AutomaticKeywordNormalizer.videoKey("https://youtu.be/existing"),
                videoUrl = "https://youtu.be/existing",
                eligibleForAssignment = true,
                firstSeenAt = 1L,
            )
        )
        HistoryKeywordAssignmentRepository(database).replaceSourceKeywords(
            historyId,
            HistoryKeywordAssignmentSources.RULE,
            ruleId,
            listOf("Live"),
        )
        val beforeAssignments = database.automaticKeywordRuleDao.getAssignmentsRaw(historyId)
        val beforeMatch = requireNotNull(
            database.automaticKeywordRuleDao.getVideoMatch(
                ruleId,
                AutomaticKeywordNormalizer.videoKey("https://youtu.be/existing"),
            )
        )

        val info = runWorker(
            ruleId,
            SourceSnapshot.failed(IllegalStateException("fetch failed"), "source unavailable"),
        )

        val rule = requireNotNull(database.automaticKeywordRuleDao.getRule(ruleId))
        assertEquals(AutomaticKeywordSyncStatus.FAILED, rule.manualSyncStatus)
        assertTrue(rule.baselineComplete)
        assertEquals(beforeAssignments, database.automaticKeywordRuleDao.getAssignmentsRaw(historyId))
        assertEquals(
            beforeMatch,
            database.automaticKeywordRuleDao.getVideoMatch(
                ruleId,
                AutomaticKeywordNormalizer.videoKey("https://youtu.be/existing"),
            )
        )
        assertEquals("Live", database.historyDao.getItem(historyId).keywords)
        assertEquals(0, info.runAttemptCount)
        assertTrue(info.state == WorkInfo.State.ENQUEUED || info.state.isFinished)
    }

    @Test
    fun authoritativeEmptyCompletesLegitimateEmptyBaselineThroughProductionWorker() = runBlocking {
        val ruleId = insertRule(baselineComplete = false)

        runWorker(ruleId, SourceSnapshot.authoritative(emptyList()))

        val rule = requireNotNull(database.automaticKeywordRuleDao.getRule(ruleId))
        assertEquals(AutomaticKeywordSyncStatus.SUCCESS, rule.manualSyncStatus)
        assertTrue(rule.baselineComplete)
        assertTrue(database.automaticKeywordRuleDao.getAllVideoMatches().isEmpty())
        assertTrue(database.automaticKeywordRuleDao.getAllAssignmentsRaw().isEmpty())
    }

    @Test
    fun partialThenAuthoritativeThenNewOnlyNewVideoReceivesRuleThroughProductionWorker() = runBlocking {
        val oldOne = insertHistory("https://youtu.be/old-one")
        val oldTwo = insertHistory("https://youtu.be/old-two")
        val newHistory = insertHistory("https://youtu.be/new")
        val ruleId = insertRule(baselineComplete = false)

        runWorker(ruleId, SourceSnapshot.partial(emptyList(), "empty converted page"))
        var rule = requireNotNull(database.automaticKeywordRuleDao.getRule(ruleId))
        assertFalse(rule.baselineComplete)
        assertEquals("", database.historyDao.getItem(oldOne).keywords)
        assertEquals("", database.historyDao.getItem(oldTwo).keywords)

        runWorker(
            ruleId,
            SourceSnapshot.authoritative(
                listOf(
                    result("https://youtu.be/old-one"),
                    result("https://youtu.be/old-two"),
                )
            ),
        )
        rule = requireNotNull(database.automaticKeywordRuleDao.getRule(ruleId))
        assertTrue(rule.baselineComplete)
        assertEquals("", database.historyDao.getItem(oldOne).keywords)
        assertEquals("", database.historyDao.getItem(oldTwo).keywords)
        assertFalse(
            requireNotNull(
                database.automaticKeywordRuleDao.getVideoMatch(
                    ruleId,
                    AutomaticKeywordNormalizer.videoKey("https://youtu.be/old-one"),
                )
            ).eligibleForAssignment
        )

        runWorker(
            ruleId,
            SourceSnapshot.authoritative(
                listOf(
                    result("https://youtu.be/old-one"),
                    result("https://youtu.be/old-two"),
                    result("https://youtu.be/new"),
                )
            ),
        )

        assertEquals("", database.historyDao.getItem(oldOne).keywords)
        assertEquals("", database.historyDao.getItem(oldTwo).keywords)
        assertEquals("Live", database.historyDao.getItem(newHistory).keywords)
        assertTrue(
            requireNotNull(
                database.automaticKeywordRuleDao.getVideoMatch(
                    ruleId,
                    AutomaticKeywordNormalizer.videoKey("https://youtu.be/new"),
                )
            ).eligibleForAssignment
        )
    }

    @Test
    fun ruleRevisionChangeAfterFetchBlocksStaleKeywordMutationThroughProductionWorker() = runBlocking {
        val historyId = insertHistory("https://youtu.be/stale")
        val ruleId = insertRule(baselineComplete = false)
        AutomaticKeywordRuleSyncWorkerTestHooks.afterFetchForTesting = { db, fetchedRule ->
            db.automaticKeywordRuleDao.updateRule(
                fetchedRule.copy(
                    revision = fetchedRule.revision + 1,
                    manualSyncStatus = AutomaticKeywordSyncStatus.NEVER,
                )
            )
        }

        runWorker(ruleId, SourceSnapshot.authoritative(listOf(result("https://youtu.be/stale"))))

        val rule = requireNotNull(database.automaticKeywordRuleDao.getRule(ruleId))
        assertEquals(2L, rule.revision)
        assertFalse(rule.baselineComplete)
        assertEquals(AutomaticKeywordSyncStatus.NEVER, rule.manualSyncStatus)
        assertTrue(database.automaticKeywordRuleDao.getAllVideoMatches().isEmpty())
        assertTrue(database.automaticKeywordRuleDao.getAssignmentsRaw(historyId).isEmpty())
        assertEquals("", database.historyDao.getItem(historyId).keywords)
    }

    private suspend fun runWorker(
        ruleId: Long,
        snapshot: SourceSnapshot,
    ): WorkInfo {
        // A previous retry may have left a terminal status in the row. Reset
        // only that test-run marker so this invocation must cross the real
        // worker's RUNNING -> terminal status boundary before the helper
        // returns; production does not perform this reset.
        val currentRule = requireNotNull(database.automaticKeywordRuleDao.getRule(ruleId))
        database.automaticKeywordRuleDao.updateRule(
            currentRule.copy(
                manualSyncStatus = AutomaticKeywordSyncStatus.NEVER,
                manualSyncAt = 0L,
                manualSyncError = AutomaticKeywordSyncError.NONE,
            )
        )
        AutomaticKeywordRuleSyncWorkerTestHooks.sourceSnapshotForTesting = { snapshot }
        val request = OneTimeWorkRequestBuilder<AutomaticKeywordRuleSyncWorker>()
            .addTag("automatic-keyword-production-test")
            .setInputData(
                Data.Builder()
                    .putLong(AutomaticKeywordRuleSyncWorker.INPUT_RULE_ID, ruleId)
                    .putString(
                        AutomaticKeywordRuleSyncWorker.INPUT_MODE,
                        "BASELINE_ONLY",
                    )
                    .build()
            )
            .build()
        workManager.enqueue(request)
        val observed = withTimeout(30_000L) {
            while (true) {
                val info = withContext(Dispatchers.IO) {
                    workManager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)
                }
                val current = database.automaticKeywordRuleDao.getRule(ruleId)
                if (
                    current?.manualSyncStatus in setOf(
                        AutomaticKeywordSyncStatus.SUCCESS,
                        AutomaticKeywordSyncStatus.PARTIAL,
                        AutomaticKeywordSyncStatus.FAILED,
                    ) || info?.state?.isFinished == true
                ) {
                    if (info?.state == WorkInfo.State.ENQUEUED || info?.state?.isFinished == true) {
                        return@withTimeout requireNotNull(info)
                    }
                }
                delay(25L)
            }
            error("unreachable")
        }
        workManager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS)
        return observed
    }

    private suspend fun insertRule(
        baselineComplete: Boolean,
        pendingApplyToExisting: Boolean = false,
    ): Long {
        val id = database.automaticKeywordRuleDao.insertRule(
            AutomaticKeywordRule(
                conditionValue = "https://www.youtube.com/playlist?list=A",
                conditionKey = "youtube:playlist:A",
                playlistName = "Playlist A",
                baselineComplete = baselineComplete,
                pendingApplyToExisting = pendingApplyToExisting,
            )
        )
        database.automaticKeywordRuleDao.insertRuleKeywords(
            listOf(AutomaticKeywordRuleKeyword(id, "live", "Live", 0))
        )
        return id
    }

    private suspend fun insertHistory(url: String): Long =
        HistoryKeywordAssignmentRepository(database).insertHistory(history(url))

    private fun history(url: String) = HistoryItem(
        id = 0L,
        url = url,
        title = "Video",
        author = "Author",
        duration = "00:01",
        thumb = "",
        type = DownloadType.video,
        time = 1L,
        downloadPath = emptyList(),
        website = "YouTube",
        format = Format(container = "mp4"),
        downloadId = 1L,
    )

    private fun result(url: String) = ResultItem(
        id = 0L,
        url = url,
        title = "Video",
        author = "Author",
        duration = "00:01",
        thumb = "",
        website = "YouTube",
        playlistTitle = "Playlist A",
        urls = "",
        chapters = null,
        playlistURL = "https://www.youtube.com/playlist?list=A",
    )
}
