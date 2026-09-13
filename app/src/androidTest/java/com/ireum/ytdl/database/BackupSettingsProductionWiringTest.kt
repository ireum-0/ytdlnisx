package com.ireum.ytdl.database

import android.content.Context
import androidx.room.withTransaction
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.KeywordGroup
import com.ireum.ytdl.database.models.KeywordGroupMember
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import com.ireum.ytdl.util.FileUtil
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the selected-category capture and publication boundary. */
@RunWith(AndroidJUnit4::class)
class BackupSettingsProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var historyRepository: HistoryRepository
    private var publishedBackup: String? = null
    private var staleBackup: File? = null

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            database = DBManager.getInstance(context)
            historyRepository = HistoryRepository(database.historyDao, database.playlistDao)
            historyRepository.deleteAllRecords()
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .remove("cache_path")
                .remove("backup_path")
                .commit()
        }
    }

    @After
    fun tearDown() = runBlocking {
        SettingsViewModel.backupCaptureReadHookForTesting = null
        publishedBackup?.let { File(it).delete() }
        staleBackup?.delete()
        historyRepository.deleteAllRecords()
        database.keywordGroupDao.clearMembers()
        database.keywordGroupDao.clearGroups()
    }

    @Test
    fun emptySelectedDownloadsCaptureIsSuccessfulEmptyState() = runBlocking {
        val result = SettingsViewModel(context as android.app.Application)
            .backup(listOf("downloads"))

        assertTrue(result.isSuccess)
        publishedBackup = result.getOrNull()
    }

    @Test
    fun publicationUsesOnlyTheCurrentBackupArtifact() = runBlocking {
        val stagingDir = File(FileUtil.getCachePath(context), "Backups")
            .apply { mkdirs() }
        staleBackup = File(stagingDir, "stale-backup-${System.nanoTime()}.json")
            .apply { writeText("{\"stale\":true}") }

        val result = SettingsViewModel(context as android.app.Application)
            .backup(listOf("downloads"))

        assertTrue(result.isSuccess)
        val published = result.getOrNull()?.let(::File)
        assertTrue(published?.exists() == true)
        assertTrue(published?.readText().orEmpty().contains("YTDLnisX_backup"))
        assertTrue(staleBackup?.exists() == true)
        assertFalse(published?.readText().orEmpty().contains("stale"))
        publishedBackup = result.getOrNull()
    }

    @Test
    fun missingRequiredCustomThumbnailFailsBeforeArtifactPublication() = runBlocking {
        val missing = File(context.cacheDir, "missing-required-thumbnail-${System.nanoTime()}.jpg")
        database.historyDao.insertAndGetIdRaw(history(customThumb = missing.absolutePath))

        val result = SettingsViewModel(context as android.app.Application)
            .backup(listOf("downloads"))

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("thumbnail", ignoreCase = true))
        assertFalse(result.getOrNull()?.let(::File)?.exists() == true)
    }

    @Test
    fun readableCustomThumbnailRemainsAValidPayload() {
        runBlocking {
            val thumbnail = File(context.cacheDir, "valid-custom-thumbnail-${System.nanoTime()}.jpg")
            thumbnail.writeBytes(byteArrayOf(1, 2, 3, 4))
            database.historyDao.insertAndGetIdRaw(history(customThumb = thumbnail.absolutePath))

            val result = SettingsViewModel(context as android.app.Application)
                .backup(listOf("downloads"))

            assertTrue(result.isSuccess)
            publishedBackup = result.getOrNull()
            thumbnail.delete()
        }
    }

    @Test
    fun relatedKeywordSnapshotRemainsCoherentDuringConcurrentWriter() = runBlocking {
        val application = context as android.app.Application
        val beforeGroupId = database.keywordGroupDao.insertGroup(KeywordGroup(name = "before"))
        database.keywordGroupDao.insertMembers(
            listOf(KeywordGroupMember(beforeGroupId, "before-member"))
        )

        val groupsRead = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        SettingsViewModel.backupCaptureReadHookForTesting = { phase ->
            if (phase == "keyword_groups") groupsRead.countDown()
        }

        try {
            val backup = async(Dispatchers.IO) {
                SettingsViewModel(application).backup(listOf("keywordData"))
            }
            assertTrue(groupsRead.await(5, TimeUnit.SECONDS))

            val writer = async(Dispatchers.IO) {
                writerStarted.countDown()
                database.withTransaction {
                    database.keywordGroupDao.clearMembers()
                    database.keywordGroupDao.clearGroups()
                    val afterGroupId = database.keywordGroupDao.insertGroup(
                        KeywordGroup(name = "after")
                    )
                    database.keywordGroupDao.insertMembers(
                        listOf(KeywordGroupMember(afterGroupId, "after-member"))
                    )
                }
            }
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))

            val result = backup.await()
            writer.await()
            assertTrue(result.isSuccess)
            val published = result.getOrNull()
            assertTrue(published?.let(::File)?.isFile == true)
            publishedBackup = published

            val root = JsonParser.parseString(File(published!!).readText()).asJsonObject
            val groupNames = root["keyword_groups"].asJsonArray
                .map { it.asJsonObject["name"].asString }
                .toSet()
            val memberKeywords = root["keyword_group_members"].asJsonArray
                .map { it.asJsonObject["keyword"].asString }
                .toSet()

            // Room's transaction snapshot must represent one complete state,
            // never groups from one state with members from the other.
            assertTrue(groupNames == setOf("before") || groupNames == setOf("after"))
            val expectedMember = if (groupNames == setOf("before")) {
                "before-member"
            } else {
                "after-member"
            }
            assertEquals(setOf(expectedMember), memberKeywords)
        } finally {
            SettingsViewModel.backupCaptureReadHookForTesting = null
        }
    }

    @Test
    fun backupFailsWhenStagingDirectoryCannotBeUsed() = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val original = preferences.getString("cache_path", null)
        val isolatedRoot = File(
            context.getExternalFilesDir(null),
            "backup-write-failure-${System.nanoTime()}"
        )
        val stagingPath = File(isolatedRoot, "Backups")
        try {
            preferences.edit().putString("cache_path", isolatedRoot.absolutePath).commit()
            isolatedRoot.mkdirs()
            assertTrue(stagingPath.createNewFile())

            val result = SettingsViewModel(context as android.app.Application)
                .backup(listOf("downloads"))

            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull() != null)
            assertTrue(result.getOrNull().isNullOrBlank())
            assertTrue(stagingPath.isFile)
        } finally {
            if (original == null) {
                preferences.edit().remove("cache_path").commit()
            } else {
                preferences.edit().putString("cache_path", original).commit()
            }
            isolatedRoot.deleteRecursively()
        }
    }

    private fun history(customThumb: String = "") = HistoryItem(
        id = 0L,
        url = "https://example.com/video-${System.nanoTime()}",
        title = "Backup test",
        author = "Author",
        duration = "1:00",
        thumb = "",
        type = DownloadType.video,
        time = System.currentTimeMillis(),
        downloadPath = listOf("/tmp/backup-test-output"),
        website = "Test",
        format = Format(),
        downloadId = 0L,
        customThumb = customThumb,
    )
}
