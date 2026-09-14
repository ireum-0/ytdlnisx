package com.ireum.ytdl.database

import android.content.Context
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.LocalHistoryAdmissionResult
import com.ireum.ytdl.util.LocalAddStorageIdentityPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAddAdmissionPersistenceTest {
    private lateinit var database: DBManager
    private lateinit var repository: HistoryKeywordAssignmentRepository

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            DBManager::class.java,
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
        repository = HistoryKeywordAssignmentRepository(database)
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun sameFilenameInDifferentDirectoriesRemainsDistinct() = runBlocking {
        val first = repository.insertLocalHistory(history("file:///storage/a/movie.mp4", "url:a"))
        val second = repository.insertLocalHistory(history("file:///storage/b/movie.mp4", "url:b"))

        assertTrue(first is LocalHistoryAdmissionResult.Inserted)
        assertTrue(second is LocalHistoryAdmissionResult.Inserted)
        assertEquals(2, database.historyDao.getAll().size)
    }

    @Test
    fun providerDocumentIdentityIncludesAuthority() = runBlocking {
        val first = repository.insertLocalHistory(
            history("content://provider-a/document/42", "url:a")
        )
        val second = repository.insertLocalHistory(
            history("content://provider-b/document/42", "url:b")
        )

        assertTrue(first is LocalHistoryAdmissionResult.Inserted)
        assertTrue(second is LocalHistoryAdmissionResult.Inserted)
        assertEquals(2, database.historyDao.getAll().size)
    }

    @Test
    fun exactProviderDocumentIdentityIsDeduplicated() = runBlocking {
        val first = repository.insertLocalHistory(
            history("content://provider/document/42", "url:a")
        )
        val second = repository.insertLocalHistory(
            history("content://provider/document/42", "url:b")
        )

        assertTrue(first is LocalHistoryAdmissionResult.Inserted)
        assertTrue(second is LocalHistoryAdmissionResult.AlreadyPresent)
        assertEquals(1, database.historyDao.getAll().size)
    }

    @Test
    fun sameTreeAndRelativePathIsDeduplicated() = runBlocking {
        val treeUri = DocumentsContract.buildTreeDocumentUri("provider", "tree").toString()
        val firstUri = DocumentsContract.buildDocumentUri("provider", "tree/child").toString()
        val secondUri = DocumentsContract.buildDocumentUri("provider", "tree/child").toString()

        val first = repository.insertLocalHistory(
            history(firstUri, "url:a", treeUri = treeUri, treePath = "child")
        )
        val second = repository.insertLocalHistory(
            history(secondUri, "url:b", treeUri = treeUri, treePath = "child")
        )

        assertTrue(first is LocalHistoryAdmissionResult.Inserted)
        assertTrue(second is LocalHistoryAdmissionResult.AlreadyPresent)
        assertEquals(1, database.historyDao.getAll().size)
    }

    @Test
    fun treeIdentityDoesNotCrossProviderAuthorities() = runBlocking {
        val treeUri = DocumentsContract.buildTreeDocumentUri("provider-a", "tree").toString()
        val firstUri = DocumentsContract.buildDocumentUri("provider-a", "tree/child").toString()
        val secondUri = DocumentsContract.buildDocumentUri("provider-b", "tree/child").toString()

        assertTrue(
            LocalAddStorageIdentityPolicy.hasSameProviderAuthority(
                android.net.Uri.parse(treeUri),
                android.net.Uri.parse(firstUri),
            )
        )
        assertTrue(
            !LocalAddStorageIdentityPolicy.hasSameProviderAuthority(
                android.net.Uri.parse(treeUri),
                android.net.Uri.parse(secondUri),
            )
        )

        val first = repository.insertLocalHistory(
            history(firstUri, "url:a", treeUri = treeUri, treePath = "child")
        )
        val second = repository.insertLocalHistory(
            history(secondUri, "url:b", treeUri = treeUri, treePath = "child")
        )

        assertTrue(first is LocalHistoryAdmissionResult.Inserted)
        assertTrue(second is LocalHistoryAdmissionResult.Inserted)
        assertEquals(2, database.historyDao.getAll().size)
    }

    @Test
    fun unprovenStorageIdentityFailsOpenForLocalAdd() = runBlocking {
        val first = repository.insertLocalHistory(history("", "unknown:a"))
        val second = repository.insertLocalHistory(history("", "unknown:b"))

        assertTrue(first is LocalHistoryAdmissionResult.Inserted)
        assertTrue(second is LocalHistoryAdmissionResult.Inserted)
        assertEquals(2, database.historyDao.getAll().size)
    }

    @Test
    fun existingUrlMatchRemainsAValidLocalAddDuplicate() = runBlocking {
        val first = repository.insertLocalHistory(history("file:///storage/a/movie.mp4", "same-url"))
        val second = repository.insertLocalHistory(history("file:///storage/b/other.mp4", "same-url"))

        assertTrue(first is LocalHistoryAdmissionResult.Inserted)
        assertTrue(second is LocalHistoryAdmissionResult.AlreadyPresent)
        assertEquals(1, database.historyDao.getAll().size)
    }

    @Test
    fun concurrentFinalAdmissionsInsertExactlyOneExactStorageObject() = runBlocking {
        val candidate = history("file:///storage/a/concurrent.mp4", "concurrent-url")
        val results = coroutineScope {
            (0 until 2).map {
                async {
                    repository.insertLocalHistory(candidate)
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it is LocalHistoryAdmissionResult.Inserted })
        assertEquals(1, results.count { it is LocalHistoryAdmissionResult.AlreadyPresent })
        assertEquals(1, database.historyDao.getAll().size)
    }

    @Test
    fun duplicateBatchUsesStrongIdentityInsteadOfFilename() = runBlocking {
        val candidates = listOf(
            history("file:///storage/a/batch.mp4", "batch:a"),
            history("file:///storage/a/batch.mp4", "batch:b"),
            history("file:///storage/b/batch.mp4", "batch:c"),
        )
        val results = candidates.map { repository.insertLocalHistory(it) }

        assertEquals(2, results.count { it is LocalHistoryAdmissionResult.Inserted })
        assertEquals(1, results.count { it is LocalHistoryAdmissionResult.AlreadyPresent })
        assertEquals(2, database.historyDao.getAll().size)
    }

    private fun history(
        path: String,
        url: String,
        treeUri: String = "",
        treePath: String = "",
    ) = HistoryItem(
        id = 0,
        url = url,
        title = "Movie",
        author = "Creator",
        duration = "00:01:00",
        thumb = "",
        type = DownloadType.video,
        time = System.currentTimeMillis() / 1000L,
        downloadPath = if (path.isBlank()) emptyList() else listOf(path),
        website = "local",
        format = Format(format_id = "local", container = "mp4"),
        downloadId = 0,
        localTreeUri = treeUri,
        localTreePath = treePath,
    )
}
