package com.ireum.ytdl.database

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the selected-category capture and publication boundary. */
@RunWith(AndroidJUnit4::class)
class BackupSettingsProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var historyRepository: HistoryRepository
    private var publishedBackup: String? = null

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
        publishedBackup?.let { File(it).delete() }
        historyRepository.deleteAllRecords()
    }

    @Test
    fun emptySelectedDownloadsCaptureIsSuccessfulEmptyState() = runBlocking {
        val result = SettingsViewModel(context as android.app.Application)
            .backup(listOf("downloads"))

        assertTrue(result.isSuccess)
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
