package com.ireum.ytdl.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.AutomaticKeywordRuleKeyword
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.HistoryKeywordAssignment
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.AutomaticKeywordRuleEngine
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryReplacementResult
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticKeywordRulePersistenceTest {
    private lateinit var db: DBManager

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DBManager::class.java
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun initialApplicationAndRepeatedSyncAreIdempotent() = runBlocking {
        val historyId = HistoryKeywordAssignmentRepository(db).insertHistory(history())
        val ruleId = rule("youtube:playlist:A", listOf("Music", "Favorite"))
        val video = result("https://youtu.be/video1")

        AutomaticKeywordRuleEngine(db).applyFullSync(ruleId, listOf(video))
        AutomaticKeywordRuleEngine(db).applyFullSync(ruleId, listOf(video))

        val assignments = db.automaticKeywordRuleDao.getAssignmentsRaw(historyId)
        assertEquals(2, assignments.size)
        assertEquals("Music, Favorite", db.historyDao.getItem(historyId).keywords)
    }

    @Test
    fun existingHistoryWithPlaylistParametersMatchesByVideoIdentity() = runBlocking {
        val historyId = HistoryKeywordAssignmentRepository(db).insertHistory(
            history(url = "https://www.youtube.com/watch?v=video1&list=A&index=2")
        )
        val ruleId = rule("youtube:playlist:A", listOf("Music"))

        AutomaticKeywordRuleEngine(db).applyFullSync(
            ruleId,
            listOf(result("https://youtu.be/video1"))
        )

        assertEquals("Music", db.historyDao.getItem(historyId).keywords)
    }

    @Test
    fun baselineExcludesExistingButFutureDiscoveryApplies() = runBlocking {
        val existingId = HistoryKeywordAssignmentRepository(db).insertHistory(
            history(url = "https://youtu.be/old")
        )
        val futureId = HistoryKeywordAssignmentRepository(db).insertHistory(
            history(url = "https://youtu.be/new")
        )
        val ruleId = rule("youtube:playlist:A", listOf("Live"))
        val engine = AutomaticKeywordRuleEngine(db)

        engine.recordBaseline(ruleId, listOf(result("https://youtu.be/old")))
        engine.recordDiscovery(
            "youtube:playlist:A",
            listOf(result("https://youtu.be/new"))
        )

        assertEquals("", db.historyDao.getItem(existingId).keywords)
        assertEquals("Live", db.historyDao.getItem(futureId).keywords)
    }

    @Test
    fun historyInsertedAfterKnownDiscoveryReceivesRuleKeywords() = runBlocking {
        rule("youtube:playlist:A", listOf("Live"))
        AutomaticKeywordRuleEngine(db).recordDiscovery(
            "youtube:playlist:A",
            listOf(result("https://youtu.be/new"))
        )

        val historyId = HistoryKeywordAssignmentRepository(db).insertHistory(
            history(url = "https://www.youtube.com/watch?v=new&list=A")
        )

        assertEquals("Live", db.historyDao.getItem(historyId).keywords)
        assertEquals(
            listOf(HistoryKeywordAssignmentSources.RULE),
            db.automaticKeywordRuleDao.getAssignmentsRaw(historyId).map { it.sourceType }
        )
    }

    @Test
    fun repeatedFullPlaylistDiscoveryPreservesBaselineExclusions() = runBlocking {
        val existingId = HistoryKeywordAssignmentRepository(db).insertHistory(
            history(url = "https://youtu.be/old")
        )
        val futureId = HistoryKeywordAssignmentRepository(db).insertHistory(
            history(url = "https://youtu.be/new")
        )
        val ruleId = rule("youtube:playlist:A", listOf("Live"))
        val engine = AutomaticKeywordRuleEngine(db)

        engine.recordBaseline(ruleId, listOf(result("https://youtu.be/old")))
        engine.recordDiscovery(
            "youtube:playlist:A",
            listOf(
                result("https://youtu.be/old"),
                result("https://youtu.be/new")
            )
        )

        assertEquals("", db.historyDao.getItem(existingId).keywords)
        assertEquals("Live", db.historyDao.getItem(futureId).keywords)
        assertFalse(
            db.automaticKeywordRuleDao
                .getVideoMatch(ruleId, "youtube:video:old")!!
                .eligibleForAssignment
        )
    }

    @Test
    fun editingAndDeletingRuleNeverRemovesManualKeyword() = runBlocking {
        val historyId = HistoryKeywordAssignmentRepository(db).insertHistory(
            history(keywords = "Favorite")
        )
        val ruleId = rule("youtube:playlist:A", listOf("Music", "Favorite"))
        val assignments = HistoryKeywordAssignmentRepository(db)
        AutomaticKeywordRuleEngine(db).applyFullSync(
            ruleId,
            listOf(result("https://youtu.be/video1"))
        )

        assignments.replaceSourceKeywords(
            historyId,
            HistoryKeywordAssignmentSources.RULE,
            ruleId,
            listOf("Music", "Live")
        )
        assertEquals("Favorite, Music, Live", db.historyDao.getItem(historyId).keywords)

        assignments.removeSourceAssignments(HistoryKeywordAssignmentSources.RULE, ruleId)
        db.automaticKeywordRuleDao.deleteRule(ruleId)
        assertEquals("Favorite", db.historyDao.getItem(historyId).keywords)
    }

    @Test
    fun deletionUndoRestoresAssignmentSourcesWithoutPromotingRuleKeywords() = runBlocking {
        val assignments = HistoryKeywordAssignmentRepository(db)
        val historyId = assignments.insertHistory(history(keywords = "Favorite"))
        val ruleId = rule("youtube:playlist:A", listOf("Music"))
        AutomaticKeywordRuleEngine(db).applyFullSync(
            ruleId,
            listOf(result("https://youtu.be/video1"))
        )
        val deletedItem = db.historyDao.getItem(historyId)
        val snapshot = assignments.snapshotAssignments(historyId)

        db.historyDao.delete(deletedItem)
        assignments.restoreHistory(deletedItem, snapshot)
        assignments.removeSourceAssignments(HistoryKeywordAssignmentSources.RULE, ruleId)

        assertEquals("Favorite", db.historyDao.getItem(historyId).keywords)
        assertEquals(
            listOf(HistoryKeywordAssignmentSources.MANUAL),
            db.automaticKeywordRuleDao.getAssignmentsRaw(historyId).map { it.sourceType }
        )
    }

    @Test
    fun legacyObserveSourceKeywordRemainsEditable() = runBlocking {
        val assignments = HistoryKeywordAssignmentRepository(db)
        val historyId = assignments.insertHistory(history())
        assignments.replaceSourceKeywords(
            historyId,
            HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE,
            42L,
            listOf("Observed")
        )

        val protected = assignments.updateManualFromMaterializedEditor(
            historyId,
            emptyList()
        )

        assertEquals(0, protected)
        assertEquals("", db.historyDao.getItem(historyId).keywords)
        assertTrue(db.automaticKeywordRuleDao.getAssignmentsRaw(historyId).isEmpty())
    }

    @Test
    fun changingHistoryVideoIdentityReconcilesRuleAssignments() = runBlocking {
        val assignments = HistoryKeywordAssignmentRepository(db)
        val historyId = assignments.insertHistory(
            history(url = "https://youtu.be/old")
        )
        val oldRule = rule("youtube:playlist:A", listOf("Old"))
        val newRule = rule("youtube:playlist:B", listOf("New"))
        val engine = AutomaticKeywordRuleEngine(db)
        engine.applyFullSync(oldRule, listOf(result("https://youtu.be/old")))
        engine.applyFullSync(newRule, listOf(result("https://youtu.be/new")))
        db.historyDao.updateRaw(
            db.historyDao.getItem(historyId).copy(url = "https://youtu.be/new")
        )

        engine.reconcileHistoryUrlChange(
            historyId,
            "https://youtu.be/old",
            "https://youtu.be/new"
        )

        assertEquals("New", db.historyDao.getItem(historyId).keywords)
        assertEquals(
            listOf(newRule),
            db.automaticKeywordRuleDao.getAssignmentsRaw(historyId).map { it.sourceId }
        )
    }

    @Test
    fun combinedUrlAndKeywordEditDoesNotPromoteOldRuleKeywordToManual() = runBlocking {
        val assignments = HistoryKeywordAssignmentRepository(db)
        val historyId = assignments.insertHistory(history(url = "https://youtu.be/old"))
        val oldRule = rule("youtube:playlist:A", listOf("Old"))
        val newRule = rule("youtube:playlist:B", listOf("New"))
        val engine = AutomaticKeywordRuleEngine(db)
        engine.applyFullSync(oldRule, listOf(result("https://youtu.be/old")))
        engine.applyFullSync(newRule, listOf(result("https://youtu.be/new")))
        db.historyDao.updateRaw(
            db.historyDao.getItem(historyId).copy(url = "https://youtu.be/new")
        )

        assignments.updateManualFromMaterializedEditor(historyId, listOf("Old", "Manual"))
        engine.reconcileHistoryUrlChange(
            historyId,
            "https://youtu.be/old",
            "https://youtu.be/new"
        )

        assertEquals("Manual, New", db.historyDao.getItem(historyId).keywords)
        val rows = db.automaticKeywordRuleDao.getAssignmentsRaw(historyId)
        assertFalse(rows.any { it.normalizedKeyword == "old" })
        assertTrue(
            rows.any {
                it.normalizedKeyword == "manual" &&
                    it.sourceType == HistoryKeywordAssignmentSources.MANUAL
            }
        )
    }

    @Test
    fun mergeRestorePreservesReceivingDeviceRuleAssignments() = runBlocking {
        val ruleId = rule("youtube:playlist:A", listOf("Live"))
        AutomaticKeywordRuleEngine(db).applyFullSync(
            ruleId,
            listOf(result("https://youtu.be/video1"))
        )
        val assignments = HistoryKeywordAssignmentRepository(db)
        val historyId = assignments.insertHistory(history(keywords = "Backup"))
        val restoredManual = HistoryKeywordAssignment(
            historyItemId = historyId,
            normalizedKeyword = "backup",
            keyword = "Backup",
            sourceType = HistoryKeywordAssignmentSources.MANUAL,
            sourceId = HistoryKeywordAssignmentSources.MANUAL_SOURCE_ID,
            position = 0,
            createdAt = 1L
        )

        assignments.restoreAssignments(
            historyId,
            listOf(restoredManual),
            preserveExistingRuleAssignments = true
        )

        assertEquals(setOf("Backup", "Live"), db.historyDao.getItem(historyId).keywords
            .split(", ")
            .toSet())
        assertEquals(
            setOf(HistoryKeywordAssignmentSources.MANUAL, HistoryKeywordAssignmentSources.RULE),
            db.automaticKeywordRuleDao.getAssignmentsRaw(historyId).map { it.sourceType }.toSet()
        )
    }

    @Test
    fun duplicateHistoryMergePreservesAssignmentSources() = runBlocking {
        val assignments = HistoryKeywordAssignmentRepository(db)
        val retainedId = assignments.insertHistory(
            history(url = "https://youtu.be/retained", keywords = "Retained")
        )
        val duplicateId = assignments.insertHistory(
            history(url = "https://youtu.be/duplicate", keywords = "Duplicate")
        )
        val ruleId = rule("youtube:playlist:A", listOf("Rule"))
        assignments.replaceSourceKeywords(
            duplicateId,
            HistoryKeywordAssignmentSources.RULE,
            ruleId,
            listOf("Rule")
        )

        assignments.mergeHistoryAssignments(duplicateId, retainedId)

        assertEquals(
            setOf("Retained", "Duplicate", "Rule"),
            db.historyDao.getItem(retainedId).keywords.split(", ").toSet()
        )
        assertEquals(
            setOf(HistoryKeywordAssignmentSources.MANUAL, HistoryKeywordAssignmentSources.RULE),
            db.automaticKeywordRuleDao.getAssignmentsRaw(retainedId).map { it.sourceType }.toSet()
        )
    }

    @Test
    fun overlappingRulesAndPlaylistsCanShareOneVideo() = runBlocking {
        val historyId = HistoryKeywordAssignmentRepository(db).insertHistory(history())
        val first = rule("youtube:playlist:A", listOf("Music", "Shared"))
        val second = rule("youtube:playlist:B", listOf("Live", "Shared"))
        val engine = AutomaticKeywordRuleEngine(db)
        val video = result("https://youtu.be/video1")

        engine.applyFullSync(first, listOf(video))
        engine.applyFullSync(second, listOf(video))

        assertEquals("Music, Shared, Live", db.historyDao.getItem(historyId).keywords)
        assertEquals(4, db.automaticKeywordRuleDao.getAssignmentsRaw(historyId).size)
    }

    @Test
    fun ruleSummaryPreservesConfiguredKeywordOrder() = runBlocking {
        val ruleId = rule("youtube:playlist:A", listOf("Zulu", "Alpha"))

        val summary = db.automaticKeywordRuleDao.observeRuleSummaries().first().single {
            it.id == ruleId
        }

        assertEquals("Zulu,Alpha", summary.keywordsCsv)
    }

    @Test
    fun mixedDiscoveryResultsStayScopedToTheirPlaylist() = runBlocking {
        val firstHistory = HistoryKeywordAssignmentRepository(db).insertHistory(
            history(url = "https://youtu.be/first")
        )
        val secondHistory = HistoryKeywordAssignmentRepository(db).insertHistory(
            history(url = "https://youtu.be/second")
        )
        val firstRule = rule("youtube:playlist:A", listOf("First"))
        val secondRule = rule("youtube:playlist:B", listOf("Second"))
        db.automaticKeywordRuleDao.updateRule(
            db.automaticKeywordRuleDao.getRule(firstRule)!!.copy(baselineComplete = true)
        )
        db.automaticKeywordRuleDao.updateRule(
            db.automaticKeywordRuleDao.getRule(secondRule)!!.copy(baselineComplete = true)
        )

        val result = AutomaticKeywordRuleEngine(db).recordDiscovery(
            mapOf(
                "youtube:playlist:A" to listOf(result("https://youtu.be/first")),
                "youtube:playlist:B" to listOf(result("https://youtu.be/second"))
            )
        )

        assertEquals("First", db.historyDao.getItem(firstHistory).keywords)
        assertEquals("Second", db.historyDao.getItem(secondHistory).keywords)
        assertEquals(
            mapOf(firstRule to 1, secondRule to 1),
            result.ruleResults.associate { it.ruleId to it.matched }
        )
        assertTrue(result.ruleResults.all { it.failed == 0 })
    }

    @Test
    fun newerManualSyncRequestCannotBeCompletedByOlderRevision() = runBlocking {
        val ruleId = rule("youtube:playlist:A", listOf("Music"))
        val oldRevision = db.automaticKeywordRuleDao.getRule(ruleId)!!.revision

        assertEquals(
            1,
            db.automaticKeywordRuleDao.requestApplyExistingSync(ruleId, 10L)
        )
        assertEquals(
            0,
            db.automaticKeywordRuleDao.completeScheduledSyncIfCurrent(ruleId, oldRevision)
        )

        val current = db.automaticKeywordRuleDao.getRule(ruleId)!!
        assertEquals(oldRevision + 1, current.revision)
        assertTrue(current.pendingApplyToExisting)
    }

    @Test
    fun olderDiscoveryRunCannotOverwriteANewerRuleRevision() = runBlocking {
        val ruleId = rule("youtube:playlist:A", listOf("Music"))
        val previous = db.automaticKeywordRuleDao.getRule(ruleId)!!
        db.automaticKeywordRuleDao.updateRule(previous.copy(revision = previous.revision + 1))

        val updated = db.automaticKeywordRuleDao.updateDiscoveryStatusIfRevision(
            ruleId = ruleId,
            revision = previous.revision,
            status = "FAILED",
            at = 10L,
            error = "UNKNOWN",
        )

        assertEquals(0, updated)
        assertEquals("NEVER", db.automaticKeywordRuleDao.getRule(ruleId)!!.discoveryStatus)
    }

    @Test
    fun missingPlaylistEntryKeepsPreviouslyAppliedKeywords() = runBlocking {
        val historyId = HistoryKeywordAssignmentRepository(db).insertHistory(history())
        val ruleId = rule("youtube:playlist:A", listOf("Music"))
        val engine = AutomaticKeywordRuleEngine(db)

        engine.applyFullSync(ruleId, listOf(result("https://youtu.be/video1")))
        engine.applyFullSync(ruleId, emptyList())

        assertEquals("Music", db.historyDao.getItem(historyId).keywords)
        assertEquals(
            1,
            db.automaticKeywordRuleDao.getAssignmentsRaw(historyId).size
        )
    }

    @Test
    fun disabledRuleDoesNotApplyDuringDiscovery() = runBlocking {
        val historyId = HistoryKeywordAssignmentRepository(db).insertHistory(
            history(url = "https://youtu.be/new")
        )
        val ruleId = rule("youtube:playlist:A", listOf("Live"))
        val saved = db.automaticKeywordRuleDao.getRule(ruleId)!!
        db.automaticKeywordRuleDao.updateRule(saved.copy(enabled = false, baselineComplete = true))

        AutomaticKeywordRuleEngine(db).recordDiscovery(
            "youtube:playlist:A",
            listOf(result("https://youtu.be/new"))
        )

        assertEquals("", db.historyDao.getItem(historyId).keywords)
        assertTrue(db.automaticKeywordRuleDao.getAssignmentsRaw(historyId).isEmpty())
    }

    @Test
    fun blankVideoIdentityNeverCreatesCrossItemRuleMatch() = runBlocking {
        val ruleId = rule("youtube:playlist:A", listOf("Live"))
        val engine = AutomaticKeywordRuleEngine(db)

        engine.applyFullSync(ruleId, listOf(result("")))
        val historyId = HistoryKeywordAssignmentRepository(db).insertHistory(history(url = ""))

        assertTrue(db.automaticKeywordRuleDao.getAllVideoMatches().isEmpty())
        assertEquals("", db.historyDao.getItem(historyId).keywords)
    }

    @Test
    fun membershipParkingCannotOverwriteAConcurrentCancellation() = runBlocking {
        val sourceId = db.observeSourcesDao.insert(observeSource())
        val downloadId = db.downloadDao.insert(
            download(sourceId, DownloadRepository.Status.Cancelled.toString())
        )

        val parked = db.observeSourcesDao.parkDownloadForMembership(
            downloadId = downloadId,
            sourceId = sourceId,
            expectedStatus = DownloadRepository.Status.Active.toString(),
            issueCode = "MEMBERSHIP_REQUIRED",
            issueStage = "DOWNLOAD",
        )

        assertEquals(0, parked)
        assertEquals(
            DownloadRepository.Status.Cancelled.toString(),
            db.downloadDao.getDownloadById(downloadId).status,
        )
    }

    @Test
    fun cancelledMembershipWaitCanBeExplicitlyRestored() = runBlocking {
        val sourceId = db.observeSourcesDao.insert(observeSource())
        val downloadId = db.downloadDao.insert(
            download(sourceId, DownloadRepository.Status.Cancelled.toString())
        )

        val restored = db.observeSourcesDao.parkDownloadForMembership(
            downloadId = downloadId,
            sourceId = sourceId,
            expectedStatus = DownloadRepository.Status.Cancelled.toString(),
            issueCode = "MEMBERSHIP_REQUIRED",
            issueStage = "DOWNLOAD",
        )

        assertEquals(1, restored)
        assertEquals(
            DownloadRepository.Status.WaitingForMembership.toString(),
            db.downloadDao.getDownloadById(downloadId).status,
        )
    }

    @Test
    fun compatibilityHistoryUpdateCannotDivergeFromAssignments() = runBlocking {
        val historyId = HistoryKeywordAssignmentRepository(db).insertHistory(
            history(keywords = "Manual")
        )
        val current = db.historyDao.getItem(historyId)
        db.historyDao.updateRaw(current.copy(title = "Renamed", keywords = "Diverged"))

        assertEquals("Renamed", db.historyDao.getItem(historyId).title)
        assertEquals("Manual", db.historyDao.getItem(historyId).keywords)
        assertEquals(
            listOf("Manual"),
            db.automaticKeywordRuleDao.getAssignmentsRaw(historyId).map { it.keyword }
        )
    }

    @Test
    fun compatibilityHistoryUpdateAfterConcurrentDeletionIsANoOp() = runBlocking {
        val historyId = HistoryKeywordAssignmentRepository(db).insertHistory(history())
        val stale = db.historyDao.getItem(historyId)
        db.historyDao.deleteById(historyId)

        db.historyDao.updateRaw(stale.copy(title = "Stale rename"))

        assertTrue(db.historyDao.getAll().isEmpty())
    }

    @Test
    fun replacementUpdatesExistingHistoryAndPreservesAssignments() = runBlocking {
        val repository = HistoryKeywordAssignmentRepository(db)
        val historyId = repository.insertHistory(history(keywords = "Manual"))
        repository.replaceSourceKeywords(
            historyId,
            HistoryKeywordAssignmentSources.RULE,
            9L,
            listOf("Rule")
        )
        val assignmentsBefore = db.automaticKeywordRuleDao.getAssignmentsRaw(historyId)
        val replacement = db.historyDao.getItem(historyId).copy(
            title = "Replacement",
            downloadPath = listOf("/tmp/replacement.mp4")
        )

        assertEquals(
            HistoryReplacementResult.UPDATED,
            repository.replaceHistoryPreservingAssignments(replacement)
        )

        assertEquals("Replacement", db.historyDao.getItem(historyId).title)
        assertEquals("Manual, Rule", db.historyDao.getItem(historyId).keywords)
        assertEquals(assignmentsBefore, db.automaticKeywordRuleDao.getAssignmentsRaw(historyId))
    }

    @Test
    fun missingReplacementTargetIsReportedWithoutCreatingHistoryOrAssignments() = runBlocking {
        val result = HistoryKeywordAssignmentRepository(db)
            .replaceHistoryPreservingAssignments(history().copy(id = 404L))

        assertEquals(HistoryReplacementResult.TARGET_MISSING, result)
        assertTrue(db.historyDao.getAll().isEmpty())
        assertTrue(db.automaticKeywordRuleDao.getAssignmentsRaw(404L).isEmpty())
    }

    @Test
    fun staleReplacementSnapshotCannotRecreateDeletedHistoryOrAssignments() = runBlocking {
        val repository = HistoryKeywordAssignmentRepository(db)
        val historyId = repository.insertHistory(history(keywords = "Manual"))
        val stale = db.historyDao.getItem(historyId).copy(title = "Stale replacement")
        db.historyDao.deleteById(historyId)

        assertEquals(
            HistoryReplacementResult.TARGET_MISSING,
            repository.replaceHistoryPreservingAssignments(stale)
        )
        assertTrue(db.historyDao.getAll().isEmpty())
        assertTrue(db.automaticKeywordRuleDao.getAssignmentsRaw(historyId).isEmpty())
    }

    @Test
    fun oneFailedAssignmentDoesNotRollBackEarlierSuccess() = runBlocking {
        val historyId = HistoryKeywordAssignmentRepository(db).insertHistory(history())
        val repository = HistoryKeywordAssignmentRepository(db)
        repository.replaceSourceKeywords(
            historyId,
            HistoryKeywordAssignmentSources.RULE,
            9,
            listOf("Music")
        )
        val failed = runCatching {
            repository.replaceSourceKeywords(
                Long.MAX_VALUE,
                HistoryKeywordAssignmentSources.RULE,
                9,
                listOf("Live")
            )
        }.isFailure

        assertTrue(failed)
        assertEquals("Music", db.historyDao.getItem(historyId).keywords)
    }

    @Test
    fun processRestartKeepsRulesMatchesAssignmentsAndMaterializedKeywords() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "automatic-keyword-restart-test"
        context.deleteDatabase(name)
        var persistent = Room.databaseBuilder(context, DBManager::class.java, name)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        val historyId = HistoryKeywordAssignmentRepository(persistent).insertHistory(history())
        val ruleId = insertRule(persistent, "youtube:playlist:A", listOf("Music"))
        AutomaticKeywordRuleEngine(persistent).applyFullSync(
            ruleId,
            listOf(result("https://youtu.be/video1"))
        )
        persistent.close()

        persistent = Room.databaseBuilder(context, DBManager::class.java, name)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        assertEquals("Music", persistent.historyDao.getItem(historyId).keywords)
        assertEquals(1, persistent.automaticKeywordRuleDao.getAllRules().size)
        assertFalse(persistent.automaticKeywordRuleDao.getAssignmentsRaw(historyId).isEmpty())
        persistent.close()
        context.deleteDatabase(name)
        db = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
    }

    private suspend fun rule(conditionKey: String, keywords: List<String>) =
        insertRule(db, conditionKey, keywords)

    private suspend fun insertRule(
        database: DBManager,
        conditionKey: String,
        keywords: List<String>
    ): Long {
        val id = database.automaticKeywordRuleDao.insertRule(
            AutomaticKeywordRule(
                conditionValue = "https://www.youtube.com/playlist?list=${conditionKey.substringAfterLast(':')}",
                conditionKey = conditionKey,
                playlistName = "Playlist"
            )
        )
        database.automaticKeywordRuleDao.insertRuleKeywords(
            keywords.mapIndexed { index, keyword ->
                AutomaticKeywordRuleKeyword(id, keyword.lowercase(), keyword, index)
            }
        )
        return id
    }

    private fun history(
        url: String = "https://youtu.be/video1",
        keywords: String = ""
    ) = HistoryItem(
        id = 0,
        url = url,
        title = "Video",
        author = "Author",
        duration = "1:00",
        thumb = "",
        type = DownloadType.video,
        time = 1,
        downloadPath = listOf("/tmp/video.mp4"),
        website = "YouTube",
        format = Format(),
        downloadId = 1,
        keywords = keywords
    )

    private fun result(url: String) = ResultItem(
        id = 0,
        url = url,
        title = "Video",
        author = "Author",
        duration = "1:00",
        thumb = "",
        website = "YouTube",
        playlistTitle = "Playlist",
        urls = "",
        chapters = null,
        playlistURL = "https://www.youtube.com/playlist?list=A"
    )

    private fun observeSource() = ObserveSourcesItem(
        id = 0,
        name = "Source",
        url = "https://www.youtube.com/playlist?list=A",
        downloadItemTemplate = download(0, DownloadRepository.Status.Cancelled.toString()),
        everyNr = 1,
        everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
        everyTime = 0,
        weeklyConfig = null,
        monthlyConfig = null,
        status = ObserveSourcesRepository.SourceStatus.ACTIVE,
        startsTime = 0,
        endsDate = 0,
        endsAfterCount = 0,
        runCount = 0,
        getOnlyNewUploads = false,
        retryMissingDownloads = false,
        ignoredLinks = mutableListOf(),
        alreadyProcessedLinks = mutableListOf(),
        syncWithSource = false,
    )

    private fun download(sourceId: Long, status: String) = DownloadItem(
        id = 0,
        url = "https://youtu.be/video1",
        title = "Video",
        author = "Author",
        thumb = "",
        duration = "1:00",
        type = DownloadType.video,
        format = Format(),
        container = "mp4",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = "/tmp",
        website = "YouTube",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = status,
        downloadStartTime = 0,
        logID = null,
        observeSourceId = sourceId,
    )
}
