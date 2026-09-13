package com.ireum.ytdl.database

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.AutomaticKeywordRuleKeyword
import com.ireum.ytdl.database.models.AutomaticKeywordRuleVideoMatch
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryKeywordAssignment
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.database.models.KeywordGroup
import com.ireum.ytdl.database.models.KeywordGroupMember
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.YoutuberGroup
import com.ireum.ytdl.database.models.YoutuberGroupMember
import com.ireum.ytdl.database.models.YoutuberGroupRelation
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises portable old-to-new identity maps through the restore boundary. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreIdentityProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private var originalVisibleChildYoutuberGroups: Set<String>? = null

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            database = DBManager.getInstance(context)
            database.downloadDao.deleteAll()
            database.observeSourcesDao.deleteAllRecords()
            database.keywordGroupDao.clearMembers()
            database.keywordGroupDao.clearGroups()
            database.youtuberGroupDao.clearMembers()
            database.youtuberGroupDao.clearRelations()
            database.youtuberGroupDao.clearGroups()
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            originalVisibleChildYoutuberGroups = preferences
                .getStringSet("history_visible_child_youtuber_groups", null)
                ?.toSet()
            preferences.edit().remove("history_visible_child_youtuber_groups").commit()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            database.downloadDao.deleteAll()
            database.observeSourcesDao.deleteAllRecords()
            database.keywordGroupDao.clearMembers()
            database.keywordGroupDao.clearGroups()
            database.youtuberGroupDao.clearMembers()
            database.youtuberGroupDao.clearRelations()
            database.youtuberGroupDao.clearGroups()
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val original = originalVisibleChildYoutuberGroups
            if (original == null) {
                preferences.edit().remove("history_visible_child_youtuber_groups").commit()
            } else {
                preferences.edit()
                    .putStringSet("history_visible_child_youtuber_groups", original)
                    .commit()
            }
        }
    }

    @Test
    fun missingObserveSourceDoesNotResolveThroughRawDestinationId() = runBlocking {
        val collidingSourceId = 41L
        database.observeSourcesDao.insert(source(collidingSourceId, "existing"))

        val success = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(
                saved = listOf(
                    download(
                        sourceId = collidingSourceId,
                        status = DownloadRepository.Status.WaitingForMembership.name,
                    )
                ),
            ),
            context,
        )

        assertTrue(success)
        val restored = database.downloadDao.getAllDownloadsList().single()
        assertEquals(0L, restored.observeSourceId)
        assertEquals(DownloadRepository.Status.Error.name, restored.status)
    }

    @Test
    fun importedObserveSourceAndTemplateUseExplicitRemappedIdentity() = runBlocking {
        val oldSourceId = 41L
        database.observeSourcesDao.insert(source(oldSourceId, "existing"))
        val backupSource = source(oldSourceId, "imported")

        val success = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(
                observeSources = listOf(backupSource),
                saved = listOf(download(sourceId = oldSourceId)),
            ),
            context,
        )

        assertTrue(success)
        val restoredSource = database.observeSourcesDao.getByURL(backupSource.url)
        assertNotEquals(oldSourceId, restoredSource.id)
        assertEquals(restoredSource.id, restoredSource.downloadItemTemplate.observeSourceId)
        val restored = database.downloadDao.getAllDownloadsList()
            .single { it.url == "https://youtu.be/portable-source" }
        assertEquals(restoredSource.id, restored.observeSourceId)
    }

    @Test
    fun keywordGroupMembersRequireAnImportedGroupMapping() = runBlocking {
        val oldGroupId = 7L
        database.keywordGroupDao.insertGroup(KeywordGroup(oldGroupId, "existing"))

        val success = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(
                keywordGroups = listOf(KeywordGroup(oldGroupId, "imported")),
                keywordGroupMembers = listOf(
                    KeywordGroupMember(oldGroupId, "mapped"),
                    KeywordGroupMember(999L, "unmapped"),
                ),
            ),
            context,
        )

        assertTrue(success)
        val importedGroup = database.keywordGroupDao.getGroupByName("imported")
        assertNotEquals(oldGroupId, importedGroup?.id)
        assertEquals(
            listOf(KeywordGroupMember(importedGroup!!.id, "mapped")),
            database.keywordGroupDao.getAllMembers(),
        )
    }

    @Test
    fun youtuberRelationsRequireExplicitMappingsForBothEndpoints() = runBlocking {
        val oldParentId = 12L
        database.youtuberGroupDao.insertGroup(YoutuberGroup(oldParentId, "existing"))

        val success = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(
                youtuberGroups = listOf(
                    YoutuberGroup(oldParentId, "imported-parent"),
                    YoutuberGroup(13L, "imported-child"),
                ),
                youtuberGroupMembers = listOf(
                    YoutuberGroupMember(oldParentId, "mapped-author"),
                    YoutuberGroupMember(999L, "unmapped-author"),
                ),
                youtuberGroupRelations = listOf(
                    YoutuberGroupRelation(oldParentId, 13L),
                    YoutuberGroupRelation(999L, 13L),
                ),
            ),
            context,
        )

        assertTrue(success)
        val parent = database.youtuberGroupDao.getGroupByName("imported-parent")
        val child = database.youtuberGroupDao.getGroupByName("imported-child")
        assertNotEquals(oldParentId, parent?.id)
        assertNotEquals(13L, child?.id)
        assertEquals(
            listOf(YoutuberGroupMember(parent!!.id, "mapped-author")),
            database.youtuberGroupDao.getAllMembers(),
        )
        assertEquals(
            listOf(YoutuberGroupRelation(parent.id, child!!.id)),
            database.youtuberGroupDao.getAllRelations(),
        )
    }

    @Test
    fun visibleChildYoutuberPreferenceUsesOnlyExplicitGroupMapping() = runBlocking {
        val collidingId = 17L
        database.youtuberGroupDao.insertGroup(YoutuberGroup(collidingId, "existing-collision"))

        val success = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(
                youtuberGroups = listOf(YoutuberGroup(collidingId, "imported-visible")),
                historyVisibleChildYoutuberGroups = setOf(collidingId, 999L),
            ),
            context,
            resetData = false,
        )

        assertTrue(success)
        val imported = database.youtuberGroupDao.getGroupByName("imported-visible")!!
        val visible = PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet("history_visible_child_youtuber_groups", emptySet())
            .orEmpty()
        assertEquals(setOf(imported.id.toString()), visible)
        assertNotEquals(collidingId.toString(), imported.id.toString())
    }

    @Test
    fun automaticKeywordRelationsUseExplicitRuleAndHistoryMappings() = runBlocking {
        val collidingRuleId = 70L
        val collidingHistoryId = 80L
        database.automaticKeywordRuleDao.insertRule(
            AutomaticKeywordRule(
                id = collidingRuleId,
                conditionValue = "https://www.youtube.com/playlist?list=EXISTING",
                conditionKey = "youtube:playlist:EXISTING",
                playlistName = "existing-rule",
            )
        )
        database.historyDao.insertAndGetIdRaw(history(collidingHistoryId, "https://youtu.be/existing"))

        val success = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(
                downloads = listOf(history(collidingHistoryId, "https://youtu.be/imported")),
                automaticKeywordRules = listOf(
                    AutomaticKeywordRule(
                        id = collidingRuleId,
                        conditionValue = "https://www.youtube.com/playlist?list=IMPORTED",
                        conditionKey = "youtube:playlist:IMPORTED",
                        playlistName = "imported-rule",
                    )
                ),
                automaticKeywordRuleKeywords = listOf(
                    AutomaticKeywordRuleKeyword(
                        ruleId = collidingRuleId,
                        normalizedKeyword = "ignored-normalized-value",
                        keyword = "Imported Keyword",
                        position = 0,
                    )
                ),
                automaticKeywordRuleVideoMatches = listOf(
                    AutomaticKeywordRuleVideoMatch(
                        ruleId = collidingRuleId,
                        videoKey = "ignored-video-key",
                        videoUrl = "https://youtu.be/imported",
                        eligibleForAssignment = true,
                        firstSeenAt = 1L,
                    ),
                    AutomaticKeywordRuleVideoMatch(
                        ruleId = 999L,
                        videoKey = "ignored-unmapped-key",
                        videoUrl = "https://youtu.be/unmapped",
                        eligibleForAssignment = true,
                        firstSeenAt = 2L,
                    ),
                ),
                historyKeywordAssignments = listOf(
                    HistoryKeywordAssignment(
                        historyItemId = collidingHistoryId,
                        normalizedKeyword = "ignored-normalized-value",
                        keyword = "Imported Keyword",
                        sourceType = HistoryKeywordAssignmentSources.RULE,
                        sourceId = collidingRuleId,
                        position = 0,
                        createdAt = 1L,
                    ),
                    HistoryKeywordAssignment(
                        historyItemId = collidingHistoryId,
                        normalizedKeyword = "unmapped-rule",
                        keyword = "Unmapped Rule",
                        sourceType = HistoryKeywordAssignmentSources.RULE,
                        sourceId = 999L,
                        position = 1,
                        createdAt = 2L,
                    ),
                    HistoryKeywordAssignment(
                        historyItemId = 999L,
                        normalizedKeyword = "unmapped-history",
                        keyword = "Unmapped History",
                        sourceType = HistoryKeywordAssignmentSources.RULE,
                        sourceId = collidingRuleId,
                        position = 2,
                        createdAt = 3L,
                    ),
                ),
            ),
            context,
            resetData = false,
        )

        assertTrue(success)
        val importedRule = database.automaticKeywordRuleDao.getAllRules()
            .single { it.conditionKey == "youtube:playlist:IMPORTED" }
        val importedHistory = database.historyDao.getAll()
            .single { it.url == "https://youtu.be/imported" }
        assertNotEquals(collidingRuleId, importedRule.id)
        assertNotEquals(collidingHistoryId, importedHistory.id)
        assertEquals(
            listOf("Imported Keyword"),
            database.automaticKeywordRuleDao.getRuleKeywords(importedRule.id)
                .map { it.keyword },
        )
        assertTrue(
            database.automaticKeywordRuleDao.getVideoMatch(
                importedRule.id,
                AutomaticKeywordNormalizer.videoKey("https://youtu.be/imported"),
            ) != null
        )
        assertTrue(
            database.automaticKeywordRuleDao.getVideoMatch(importedRule.id, "youtube:video:unmapped") == null
        )
        assertTrue(
            database.automaticKeywordRuleDao.getAllVideoMatches().none { it.ruleId == 999L }
        )
        val assignments = database.automaticKeywordRuleDao.getAssignmentsRaw(importedHistory.id)
        assertEquals(1, assignments.size)
        assertEquals(HistoryKeywordAssignmentSources.RULE, assignments.single().sourceType)
        assertEquals(importedRule.id, assignments.single().sourceId)
        assertEquals("Imported Keyword", assignments.single().keyword)
        assertEquals("Imported Keyword", importedHistory.keywords)
        assertTrue(database.automaticKeywordRuleDao.getAssignmentsRaw(collidingHistoryId).isEmpty())
    }

    private fun source(id: Long, suffix: String) = ObserveSourcesItem(
        id = id,
        name = "Source $suffix",
        url = "https://example.com/$suffix",
        downloadItemTemplate = download(sourceId = id),
        everyNr = 1,
        everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
        everyTime = 0L,
        weeklyConfig = null,
        monthlyConfig = null,
        status = ObserveSourcesRepository.SourceStatus.STOPPED,
        startsTime = 0L,
        endsDate = 0L,
        endsAfterCount = 0,
        runCount = 0,
        getOnlyNewUploads = false,
        retryMissingDownloads = false,
        ignoredLinks = mutableListOf(),
        alreadyProcessedLinks = mutableListOf(),
        syncWithSource = false,
    )

    private fun history(id: Long, url: String) = com.ireum.ytdl.database.models.HistoryItem(
        id = id,
        url = url,
        title = "Portable history $id",
        author = "Author",
        duration = "1:00",
        thumb = "",
        type = DownloadType.video,
        time = 1L,
        downloadPath = listOf("/tmp/portable-history-$id"),
        website = "YouTube",
        format = Format(),
        downloadId = 0L,
        keywords = "",
    )

    private fun download(
        sourceId: Long,
        status: String = DownloadRepository.Status.Saved.name,
    ) = DownloadItem(
        id = 0L,
        url = "https://youtu.be/portable-source",
        title = "Portable source",
        author = "Author",
        thumb = "",
        duration = "1:00",
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
        status = status,
        downloadStartTime = 0L,
        logID = null,
        observeSourceId = sourceId,
    )
}
