package com.ireum.ytdl.database

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
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
