package com.ireum.ytdl.database

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Exercises paused Download capture/restore through SettingsViewModel. */
@RunWith(AndroidJUnit4::class)
class BackupPausedProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var workManager: WorkManager
    private var publishedBackup: String? = null

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            database = DBManager.getInstance(context)
            workManager = WorkManager.getInstance(context)
            workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
            database.downloadDao.deleteAll()
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove("cache_path")
                .remove("backup_path")
                .commit()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            publishedBackup?.let { File(it).delete() }
            workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
            database.downloadDao.deleteAll()
        }
    }

    @Test
    fun pausedBackupAndRestorePreserveStateWithoutStartingWork() = runBlocking {
        val expectedOrderPosition = 41L
        val source = pausedDownload(orderPosition = expectedOrderPosition)
        val sourceId = database.downloadDao.insert(source)
        database.downloadDao.updateOrderPosition(sourceId, expectedOrderPosition)
        assertEquals(
            expectedOrderPosition,
            database.downloadDao.getPausedDownloadsList().single().orderPosition,
        )

        val backupResult = SettingsViewModel(context as Application)
            .backup(listOf("paused"))
        assertTrue(backupResult.isSuccess)
        publishedBackup = backupResult.getOrThrow()
        val json = JsonParser.parseString(File(publishedBackup!!).readText()).asJsonObject
        val encoded = json["paused"].asJsonArray.single()
        val encodedItem = Gson().fromJson(encoded, DownloadItem::class.java)
        assertEquals(DownloadRepository.Status.Paused.name, encodedItem.status)
        assertEquals(expectedOrderPosition, encodedItem.orderPosition)

        database.downloadDao.deleteAll()
        assertTrue(database.downloadDao.getAllDownloadsList().isEmpty())
        assertTrue(
            SettingsViewModel(context as Application).restoreData(
                RestoreAppDataItem(paused = listOf(encodedItem)),
                context,
                resetData = true,
            ).isCompleted()
        )

        val restored = database.downloadDao.getAllDownloadsList().single()
        assertTrue(sourceId > 0L)
        assertEquals(DownloadRepository.Status.Paused.name, restored.status)
        assertEquals(source.url, restored.url)
        assertEquals(source.operationId, restored.operationId)
        assertEquals(source.retryAttempt, restored.retryAttempt)
        assertEquals(expectedOrderPosition, restored.orderPosition)
        assertEquals("", restored.executionId)
        assertTrue(
            workManager.getWorkInfosByTag("download").get(20, TimeUnit.SECONDS)
                .none { it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.BLOCKED }
        )
    }

    @Test
    fun allCategoryBackupIncludesPausedPayload() = runBlocking {
        database.downloadDao.insert(pausedDownload(orderPosition = 7L))

        val result = SettingsViewModel(context as Application).backup()
        assertTrue(result.isSuccess)
        publishedBackup = result.getOrThrow()
        val json = JsonParser.parseString(File(publishedBackup!!).readText()).asJsonObject
        assertTrue(json.has("paused"))
        assertEquals(1, json["paused"].asJsonArray.size())
        assertEquals(4, json["backup_format_version"].asInt)
    }

    @Test
    fun resetPausedCategoryDoesNotDeleteQueuedRows() = runBlocking {
        val queuedId = database.downloadDao.insert(
            pausedDownload(orderPosition = 1L).copy(
                id = 0L,
                status = DownloadRepository.Status.Queued.name,
            )
        )
        database.downloadDao.insert(pausedDownload(orderPosition = 2L))

        assertTrue(
            SettingsViewModel(context as Application).restoreData(
                RestoreAppDataItem(paused = listOf(pausedDownload(orderPosition = 9L))),
                context,
                resetData = true,
            ).isCompleted()
        )

        val rows = database.downloadDao.getAllDownloadsList()
        assertTrue(rows.any { it.id == queuedId && it.status == DownloadRepository.Status.Queued.name })
        assertEquals(1, rows.count { it.status == DownloadRepository.Status.Paused.name })
    }

    @Test
    fun repeatedMergeCreatesIndependentPausedRows() = runBlocking {
        val payload = RestoreAppDataItem(paused = listOf(pausedDownload(orderPosition = 12L)))
        assertTrue(SettingsViewModel(context as Application).restoreData(payload, context))
        assertTrue(SettingsViewModel(context as Application).restoreData(payload, context))

        val rows = database.downloadDao.getAllDownloadsList()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.status == DownloadRepository.Status.Paused.name })
        assertEquals(2, rows.map { it.id }.distinct().size)
    }

    private fun pausedDownload(orderPosition: Long) = DownloadItem(
        id = 0L,
        url = "https://youtu.be/paused-$orderPosition",
        title = "Paused $orderPosition",
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
        status = DownloadRepository.Status.Paused.name,
        downloadStartTime = 0L,
        logID = null,
        operationId = "paused-operation-$orderPosition",
        retryAttempt = 2,
        orderPosition = orderPosition,
    )
}
