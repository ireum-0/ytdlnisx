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
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.database.repository.ResultRepositoryMetadataTestHooks
import com.ireum.ytdl.util.ExtractorSourceIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val metadataWiringIds = AtomicLong(System.currentTimeMillis().coerceAtLeast(20_000_000L))

@RunWith(AndroidJUnit4::class)
class DownloadMetadataPublicationProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var repository: ResultRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cancelWork()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        repository = ResultRepository(
            database.resultDao,
            database.commandTemplateDao,
            context,
        )
        ResultRepositoryMetadataTestHooks.clearForTesting()
        UpdateMultipleDownloadsDataWorkerTestHooks.dbManagerForTesting = database
    }

    @After
    fun tearDown() {
        ResultRepositoryMetadataTestHooks.clearForTesting()
        UpdateMultipleDownloadsDataWorkerTestHooks.clearForTesting()
        cancelWork()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun batchWorkerPublishesMetadataWithoutOverwritingConcurrentNonMetadataState() = runBlocking {
        val source = sourceUrl("batch-positive")
        val id = insert(download(id = metadataWiringIds.getAndIncrement(), source = source))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        installFresh(source, entered, release)

        val work = enqueueBatch(id)
        withTimeout(20_000L) { entered.await() }
        val current = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        database.downloadDao.updateRaw(
            current.copy(
                status = "Paused",
                orderPosition = 77L,
                downloadPath = "/newer-path",
                retryAttempt = 9,
            )
        )
        release.complete(Unit)
        awaitFinished(work)

        val persisted = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        assertEquals("fresh title", persisted.title)
        assertEquals("Paused", persisted.status)
        assertEquals(77L, persisted.orderPosition)
        assertEquals("/newer-path", persisted.downloadPath)
        assertEquals(9, persisted.retryAttempt)
    }

    @Test
    fun batchWorkerRejectsMetadataWhenSourceChangesDuringLookup() = runBlocking {
        val sourceA = sourceUrl("batch-source-a")
        val sourceB = sourceUrl("batch-source-b")
        val id = insert(download(id = metadataWiringIds.getAndIncrement(), source = sourceA))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        installFresh(sourceA, entered, release)

        val work = enqueueBatch(id)
        withTimeout(20_000L) { entered.await() }
        val current = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        database.downloadDao.updateRaw(current.copy(url = sourceB))
        release.complete(Unit)
        awaitFinished(work)

        val persisted = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        assertEquals(sourceB, persisted.url)
        assertEquals("", persisted.title)
        assertEquals("", persisted.author)
    }

    @Test
    fun batchWorkerDoesNotResurrectDeletedDownload() = runBlocking {
        val source = sourceUrl("batch-deleted")
        val id = insert(download(id = metadataWiringIds.getAndIncrement(), source = source))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        installFresh(source, entered, release)

        val work = enqueueBatch(id)
        withTimeout(20_000L) { entered.await() }
        database.downloadDao.delete(id)
        release.complete(Unit)
        awaitFinished(work)

        assertNull(database.downloadDao.getNullableDownloadById(id))
    }

    @Test
    fun downloadWorkerPublicationIsNarrowAndSourceGuarded() = runBlocking {
        val source = sourceUrl("download-positive")
        val id = metadataWiringIds.getAndIncrement()
        val initial = download(id = id, source = source).copy(
            status = "Active",
            executionId = "E1",
        )
        database.downloadDao.insertRaw(initial)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        installFresh(source, entered, release)

        val publication = async {
            persistDownloadMetadataNarrowly(repository, database.downloadDao, initial)
        }
        withTimeout(20_000L) { entered.await() }
        database.downloadDao.updateRaw(
            initial.copy(
                orderPosition = 91L,
                downloadPath = "/download-newer-path",
                retryAttempt = 4,
            )
        )
        release.complete(Unit)

        assertTrue(publication.await())
        val persisted = requireNotNull(database.downloadDao.getNullableDownloadById(id))
        assertEquals("fresh title", persisted.title)
        assertEquals("Active", persisted.status)
        assertEquals("E1", persisted.executionId)
        assertEquals(91L, persisted.orderPosition)
        assertEquals("/download-newer-path", persisted.downloadPath)
        assertEquals(4, persisted.retryAttempt)
    }

    @Test
    fun downloadWorkerRejectsStaleSourceAndDeletedRow() = runBlocking {
        val sourceA = sourceUrl("download-source-a")
        val sourceB = sourceUrl("download-source-b")
        val id = metadataWiringIds.getAndIncrement()
        val initial = download(id = id, source = sourceA).copy(
            status = "Active",
            executionId = "E2",
        )
        database.downloadDao.insertRaw(initial)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        installFresh(sourceA, entered, release)

        val publication = async {
            persistDownloadMetadataNarrowly(repository, database.downloadDao, initial)
        }
        withTimeout(20_000L) { entered.await() }
        database.downloadDao.updateRaw(initial.copy(url = sourceB))
        release.complete(Unit)
        assertFalse(publication.await())
        assertEquals("", requireNotNull(database.downloadDao.getNullableDownloadById(id)).title)

        val deletedId = metadataWiringIds.getAndIncrement()
        val deleted = download(id = deletedId, source = sourceA).copy(
            status = "Active",
            executionId = "E3",
        )
        database.downloadDao.insertRaw(deleted)
        val deletedEntered = CompletableDeferred<Unit>()
        val deletedRelease = CompletableDeferred<Unit>()
        installFresh(sourceA, deletedEntered, deletedRelease)
        val deletedPublication = async {
            persistDownloadMetadataNarrowly(repository, database.downloadDao, deleted)
        }
        withTimeout(20_000L) { deletedEntered.await() }
        database.downloadDao.delete(deletedId)
        deletedRelease.complete(Unit)
        assertFalse(deletedPublication.await())
        assertNull(database.downloadDao.getNullableDownloadById(deletedId))
    }

    @Test
    fun downloadWorkerCancellationDoesNotPublishPartialMetadata() = runBlocking {
        val source = sourceUrl("download-cancel")
        val item = download(id = metadataWiringIds.getAndIncrement(), source = source).copy(
            status = "Active",
            executionId = "E4",
        )
        database.downloadDao.insertRaw(item)
        val expected = CancellationException("metadata cancelled")
        ResultRepositoryMetadataTestHooks.cachedMetadataForTesting = { null }
        ResultRepositoryMetadataTestHooks.freshMetadataForTesting = { throw expected }

        try {
            persistDownloadMetadataNarrowly(repository, database.downloadDao, item)
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(expected, actual)
        }
        assertEquals("", requireNotNull(database.downloadDao.getNullableDownloadById(item.id)).title)
    }

    private fun installFresh(
        source: String,
        entered: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ) {
        ResultRepositoryMetadataTestHooks.cachedMetadataForTesting = { null }
        ResultRepositoryMetadataTestHooks.freshMetadataForTesting = {
            entered.complete(Unit)
            release.await()
            ResultItem(
                id = 0L,
                url = source,
                title = "fresh title",
                author = "fresh author",
                duration = "00:10",
                thumb = "fresh-thumb",
                website = "fresh-site",
                playlistTitle = "",
                urls = "",
                chapters = null,
                mediaPublishedAt = 123L,
            ).also {
                it.sourceIdentity = ExtractorSourceIdentity(canonicalUrl = source)
            }
        }
    }

    private suspend fun insert(item: DownloadItem): Long {
        database.downloadDao.insertRaw(item)
        return item.id
    }

    private fun enqueueBatch(id: Long): java.util.UUID {
        val request = OneTimeWorkRequestBuilder<UpdateMultipleDownloadsDataWorker>()
            .setInputData(Data.Builder().putLongArray("ids", longArrayOf(id)).build())
            .addTag("bug-metadata-f14")
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }

    private suspend fun awaitFinished(id: java.util.UUID): WorkInfo {
        return withContext(Dispatchers.IO) {
            repeat(240) {
                val info = runCatching {
                    WorkManager.getInstance(context).getWorkInfoById(id).get(1, TimeUnit.SECONDS)
                }.getOrNull()
                if (info?.state?.isFinished == true) return@withContext info
                Thread.sleep(250L)
            }
            error("Timed out waiting for metadata worker $id")
        }
    }

    private fun cancelWork() {
        runCatching {
            WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        }
    }

    private fun sourceUrl(suffix: String) = "https://metadata.example/$suffix"

    private fun download(id: Long, source: String) = DownloadItem(
        id = id,
        url = source,
        title = "",
        author = "",
        thumb = "",
        duration = "",
        type = DownloadType.video,
        format = Format(format_id = "best"),
        container = "mp4",
        downloadSections = "",
        allFormats = arrayListOf(),
        downloadPath = "/downloads",
        website = "",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = "Queued",
        downloadStartTime = 0L,
        logID = null,
    )
}
