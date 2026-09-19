package com.ireum.ytdl.database

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.BackupCustomThumbItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises destination-owned custom-thumbnail restore through SettingsViewModel. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreThumbnailProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var historyRepository: HistoryRepository
    private lateinit var restoredThumbnailDirectory: File

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            database = DBManager.getInstance(context)
            historyRepository = HistoryRepository(database.historyDao, database.playlistDao)
            historyRepository.deleteAllRecords()
            restoredThumbnailDirectory = File(context.filesDir, "restored_custom_thumbnails")
            restoredThumbnailDirectory.deleteRecursively()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            historyRepository.deleteAllRecords()
            restoredThumbnailDirectory.deleteRecursively()
            File(context.filesDir, "restore-thumbnail-staging").deleteRecursively()
        }
    }

    @Test
    fun independentRestoresWithSameBackupHistoryIdKeepDestinationOwnedBytes() = runBlocking {
        val application = context as Application
        val oldHistoryId = 42L
        val firstBytes = byteArrayOf(1, 2, 3)
        val secondBytes = byteArrayOf(9, 8, 7, 6)
        val first = SettingsViewModel(application).restoreData(
            RestoreAppDataItem(
                downloads = listOf(history(oldHistoryId)),
                customThumbnails = listOf(thumbnail(oldHistoryId, firstBytes, "jpg")),
            ),
            context,
            resetData = true,
        )
        val second = SettingsViewModel(application).restoreData(
            RestoreAppDataItem(
                downloads = listOf(history(oldHistoryId)),
                customThumbnails = listOf(thumbnail(oldHistoryId, secondBytes, "png")),
            ),
            context,
            resetData = false,
        )

        assertTrue(first.isCompleted())
        assertTrue(second.isCompleted())
        val restored = database.historyDao.getAll().sortedBy { it.id }
        assertEquals(2, restored.size)
        val firstPath = File(restored[0].customThumb)
        val secondPath = File(restored[1].customThumb)
        assertTrue(firstPath.isFile)
        assertTrue(secondPath.isFile)
        assertNotEquals(firstPath.absolutePath, secondPath.absolutePath)
        assertEquals(firstBytes.toList(), firstPath.readBytes().toList())
        assertEquals(secondBytes.toList(), secondPath.readBytes().toList())
        assertTrue(firstPath.extension == "jpg")
        assertTrue(secondPath.extension == "png")
    }

    @Test
    fun historyWithoutThumbnailPayloadDoesNotRetainBackupLocalPath() = runBlocking {
        val legacyPath = File(context.cacheDir, "backup-local-thumbnail.jpg").absolutePath
        val success = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(downloads = listOf(history(77L, legacyPath))),
            context,
            resetData = true,
        )

        assertTrue(success.isCompleted())
        assertEquals("", database.historyDao.getAll().single().customThumb)
    }

    @Test
    fun invalidThumbnailPayloadFailsRestoreWithoutBindingHistory() = runBlocking {
        val success = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(
                downloads = listOf(history(88L)),
                customThumbnails = listOf(
                    BackupCustomThumbItem(
                        historyId = 88L,
                        base64 = Base64.encodeToString(byteArrayOf(4), Base64.NO_WRAP),
                        extension = "jpg/invalid",
                    )
                ),
            ),
            context,
            resetData = true,
        )

        assertFalse(success.isCompleted())
        assertEquals(0, database.historyDao.getCount())
        assertTrue(
            !restoredThumbnailDirectory.exists() ||
                restoredThumbnailDirectory.listFiles().orEmpty().isEmpty()
        )
    }

    @Test
    fun destinationThumbnailPublicationFailureDoesNotBindFailedPath() = runBlocking {
        val unrelated = history(901L)
        val unrelatedId = database.historyDao.insertAndGetIdRaw(unrelated)
        val sentinel = byteArrayOf(7, 6, 5)
        assertTrue(restoredThumbnailDirectory.createNewFile())
        restoredThumbnailDirectory.writeBytes(sentinel)

        val result = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(
                downloads = listOf(history(90L)),
                customThumbnails = listOf(thumbnail(90L, byteArrayOf(1, 2, 3), "jpg")),
            ),
            context,
            resetData = false,
        )

        assertFalse(result.isCompleted())
        val rows = database.historyDao.getAll()
        assertTrue(rows.any { it.id == unrelatedId })
        val failedRow = rows.single { it.url == "https://example.com/restore-90" }
        assertEquals("", failedRow.customThumb)
        assertEquals(sentinel.toList(), restoredThumbnailDirectory.readBytes().toList())
        assertTrue(
            !File(context.filesDir, "restore-thumbnail-staging").exists() ||
                File(context.filesDir, "restore-thumbnail-staging").listFiles().orEmpty().isEmpty()
        )
    }

    @Test
    fun historyInsertFailureAfterThumbnailStagingCleansRestoreArtifacts() = runBlocking {
        val unrelatedId = database.historyDao.insertAndGetIdRaw(history(902L))
        val triggerName = "f5_fail_history_insert"
        val writableDatabase = database.openHelper.writableDatabase
        writableDatabase.execSQL(
            "CREATE TRIGGER $triggerName BEFORE INSERT ON history " +
                "BEGIN SELECT RAISE(ABORT, 'forced F5 History insert failure'); END"
        )
        try {
            val result = SettingsViewModel(context as Application).restoreData(
                RestoreAppDataItem(
                    downloads = listOf(history(91L)),
                    customThumbnails = listOf(thumbnail(91L, byteArrayOf(8, 9), "png")),
                ),
                context,
                resetData = false,
            )

            assertFalse(result.isCompleted())
            assertEquals(listOf(unrelatedId), database.historyDao.getAll().map { it.id })
            assertTrue(
                !restoredThumbnailDirectory.exists() ||
                    restoredThumbnailDirectory.listFiles().orEmpty().isEmpty()
            )
            assertTrue(
                !File(context.filesDir, "restore-thumbnail-staging").exists() ||
                    File(context.filesDir, "restore-thumbnail-staging").listFiles().orEmpty().isEmpty()
            )
        } finally {
            writableDatabase.execSQL("DROP TRIGGER IF EXISTS $triggerName")
        }
    }

    private fun thumbnail(historyId: Long, bytes: ByteArray, extension: String) =
        BackupCustomThumbItem(
            historyId = historyId,
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            extension = extension,
        )

    private fun history(id: Long, customThumb: String = "") = HistoryItem(
        id = id,
        url = "https://example.com/restore-$id",
        title = "Restore test $id",
        author = "Author",
        duration = "1:00",
        thumb = "",
        type = DownloadType.video,
        time = System.currentTimeMillis(),
        downloadPath = listOf("/tmp/restore-test-output-$id"),
        website = "Test",
        format = Format(),
        downloadId = id,
        customThumb = customThumb,
    )
}
