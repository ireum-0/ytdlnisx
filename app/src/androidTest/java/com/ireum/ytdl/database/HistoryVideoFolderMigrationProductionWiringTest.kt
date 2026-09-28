package com.ireum.ytdl.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.util.storage.HistoryVideoFolderMigration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises folder migration against its real Room reference reconciliation path. */
@RunWith(AndroidJUnit4::class)
class HistoryVideoFolderMigrationProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var testRoot: File

    @Before
    fun openDatabase() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        testRoot = File(context.cacheDir, "history-video-migration-${System.nanoTime()}")
        assertTrue(testRoot.mkdirs())
    }

    @After
    fun closeDatabase() {
        database.close()
        testRoot.deleteRecursively()
    }

    @Test
    fun staleReferencePreservesSourceUntilRetryReconcilesCurrentHistory() = runBlocking {
        val sourceRoot = File(testRoot, "source").apply { assertTrue(mkdirs()) }
        val destinationRoot = File(testRoot, "destination").apply { assertTrue(mkdirs()) }
        val source = File(sourceRoot, "video.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val firstId = insertHistory(source.path)
        val secondId = insertHistory(source.path)
        val migration = HistoryVideoFolderMigration(context, database)
        migration.beforePathReconciliationForTesting = { snapshot ->
            assertEquals(2, snapshot.size)
            val second = requireNotNull(database.historyDao.getNullableItem(secondId))
            assertEquals(
                1,
                database.historyDao.updateDownloadPathById(
                    secondId,
                    second.downloadPath + File(testRoot, "newer-reference.mp4").path,
                ),
            )
        }

        val firstResult = migration.migrate(sourceRoot, destinationRoot.path) { _, _ -> Unit }

        val firstAfterConflict = requireNotNull(database.historyDao.getNullableItem(firstId))
        val secondAfterConflict = requireNotNull(database.historyDao.getNullableItem(secondId))
        assertEquals(1, firstResult.updatedCards)
        assertEquals(0, firstResult.movedFiles)
        assertEquals(1, firstResult.failedFiles)
        assertTrue("source must remain while any current History row still references it", source.exists())
        assertTrue(File(firstAfterConflict.downloadPath.single()).isFile)
        assertEquals(source.path, secondAfterConflict.downloadPath.first())
        assertTrue(File(secondAfterConflict.downloadPath.last()).path.endsWith("newer-reference.mp4"))

        migration.beforePathReconciliationForTesting = null
        val retryResult = migration.migrate(sourceRoot, destinationRoot.path) { _, _ -> Unit }

        val firstAfterRetry = requireNotNull(database.historyDao.getNullableItem(firstId))
        val secondAfterRetry = requireNotNull(database.historyDao.getNullableItem(secondId))
        assertEquals(1, retryResult.updatedCards)
        assertEquals(1, retryResult.movedFiles)
        assertEquals(0, retryResult.failedFiles)
        assertFalse("source retires only after every live reference has changed", source.exists())
        assertTrue(File(firstAfterRetry.downloadPath.single()).isFile)
        assertTrue(File(secondAfterRetry.downloadPath.first()).isFile)
        assertTrue(secondAfterRetry.downloadPath.last().endsWith("newer-reference.mp4"))
    }

    @Test
    fun interruptionAfterCopyLeavesRecordedSourceAvailableForRetry() = runBlocking {
        val sourceRoot = File(testRoot, "source").apply { assertTrue(mkdirs()) }
        val destinationRoot = File(testRoot, "destination").apply { assertTrue(mkdirs()) }
        val expectedBytes = byteArrayOf(9, 8, 7)
        val source = File(sourceRoot, "video.mp4").apply { writeBytes(expectedBytes) }
        val historyId = insertHistory(source.path)
        val migration = HistoryVideoFolderMigration(context, database)
        migration.afterDestinationCopyForTesting = { _, _ -> error("injected interruption after copy") }

        val failure = runCatching {
            migration.migrate(sourceRoot, destinationRoot.path) { _, _ -> Unit }
        }.exceptionOrNull()

        assertTrue("expected the injected interruption", failure != null)
        assertTrue("source remains the durable History target", source.isFile)
        assertEquals(source.path, requireNotNull(database.historyDao.getNullableItem(historyId)).downloadPath.single())

        migration.afterDestinationCopyForTesting = null
        val retryResult = migration.migrate(sourceRoot, destinationRoot.path) { _, _ -> Unit }

        val migratedPath = requireNotNull(database.historyDao.getNullableItem(historyId)).downloadPath.single()
        assertEquals(1, retryResult.updatedCards)
        assertEquals(1, retryResult.movedFiles)
        assertEquals(0, retryResult.failedFiles)
        assertFalse(source.exists())
        assertTrue(File(migratedPath).isFile)
        assertEquals(expectedBytes.toList(), File(migratedPath).readBytes().toList())
    }

    private fun insertHistory(path: String): Long = database.historyDao.insertAndGetIdRaw(
        HistoryItem(
            id = 0,
            url = "https://example.com/video",
            title = "Video",
            author = "Author",
            duration = "1:00",
            thumb = "",
            type = DownloadType.video,
            time = System.currentTimeMillis(),
            downloadPath = listOf(path),
            website = "Test",
            format = Format(),
            downloadId = 0,
        ),
    )
}
