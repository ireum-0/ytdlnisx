package com.ireum.ytdl.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.DuplicateAdmissionMode
import com.ireum.ytdl.database.repository.DuplicateAdmissionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exercises the same repository transaction used by Manual and Observe
 * producers.  The two callers may perform advisory checks first, but this
 * final Room boundary is the only authority that publishes a runnable row.
 */
@RunWith(AndroidJUnit4::class)
class DownloadDuplicateAdmissionProductionWiringTest {
    private lateinit var database: DBManager

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun concurrentEquivalentManualAndObserveAdmissionsPublishExactlyOneRunnableRow() = runBlocking {
        val results = concurrentAdmissions(
            DownloadRepository(database),
            DownloadRepository(database),
            download("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            download("https://youtu.be/dQw4w9WgXcQ"),
            DuplicateAdmissionMode.URL_TYPE,
        )

        assertEquals(1, results.count { it is DuplicateAdmissionResult.Inserted })
        assertEquals(1, results.count { it is DuplicateAdmissionResult.Duplicate })
        assertEquals(1, database.downloadDao.getActiveAndQueuedDownloadsList().size)
    }

    @Test
    fun configurationAdmissionUsesCanonicalSourceButPreservesMeaningfulDifferences() = runBlocking {
        val repository = DownloadRepository(database)
        val first = download("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val same = first.copy(id = 0L, url = "https://youtu.be/dQw4w9WgXcQ")
        val differentFormat = first.copy(
            id = 0L,
            format = first.format.copy(format_id = "18"),
        )
        val command = "yt-dlp https://youtu.be/dQw4w9WgXcQ --format best"

        assertTrue(
            repository.insertNewWithDuplicateAdmission(
                first,
                DuplicateAdmissionMode.CONFIG,
                command,
            ) is DuplicateAdmissionResult.Inserted
        )
        assertTrue(
            repository.insertNewWithDuplicateAdmission(
                same,
                DuplicateAdmissionMode.CONFIG,
                command,
            ) is DuplicateAdmissionResult.Duplicate
        )
        assertTrue(
            repository.insertNewWithDuplicateAdmission(
                differentFormat,
                DuplicateAdmissionMode.CONFIG,
                "yt-dlp https://youtu.be/dQw4w9WgXcQ --format 18",
            ) is DuplicateAdmissionResult.Inserted
        )
        assertEquals(2, database.downloadDao.getActiveAndQueuedDownloadsList().size)
    }

    @Test
    fun differentMediaAndTypeRemainAdmissible() = runBlocking {
        val repository = DownloadRepository(database)
        val first = repository.insertNewWithDuplicateAdmission(
            download("https://youtu.be/dQw4w9WgXcQ"),
            DuplicateAdmissionMode.URL_TYPE,
        )
        val differentMedia = repository.insertNewWithDuplicateAdmission(
            download("https://youtu.be/9bZkp7q19f0"),
            DuplicateAdmissionMode.URL_TYPE,
        )
        val incompatibleType = repository.insertNewWithDuplicateAdmission(
            download("https://youtu.be/dQw4w9WgXcQ", DownloadType.audio),
            DuplicateAdmissionMode.URL_TYPE,
        )

        assertTrue(first is DuplicateAdmissionResult.Inserted)
        assertTrue(differentMedia is DuplicateAdmissionResult.Inserted)
        assertTrue(incompatibleType is DuplicateAdmissionResult.Inserted)
        assertEquals(3, database.downloadDao.getActiveAndQueuedDownloadsList().size)
    }

    @Test
    fun disabledDuplicateModeRemainsAnExplicitBypass() = runBlocking {
        val repository = DownloadRepository(database)
        val first = repository.insertNewWithDuplicateAdmission(
            download("https://youtu.be/dQw4w9WgXcQ"),
            DuplicateAdmissionMode.DISABLED,
        )
        val second = repository.insertNewWithDuplicateAdmission(
            download("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            DuplicateAdmissionMode.DISABLED,
        )

        assertTrue(first is DuplicateAdmissionResult.Inserted)
        assertTrue(second is DuplicateAdmissionResult.Inserted)
        assertFalse(
            database.downloadDao.getActiveAndQueuedDownloadsList().isEmpty()
        )
        assertEquals(2, database.downloadDao.getActiveAndQueuedDownloadsList().size)
    }

    @Test
    fun insertionFailureLeavesNoReservationAndRetryCanMakeProgress() = runBlocking {
        val repository = DownloadRepository(database)
        val item = download("https://youtu.be/dQw4w9WgXcQ")
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER fail_duplicate_admission_insert " +
                "BEFORE INSERT ON downloads " +
                "BEGIN SELECT RAISE(ABORT, 'forced duplicate admission failure'); END"
        )

        val failure = try {
            runCatching {
                repository.insertNewWithDuplicateAdmission(
                    item,
                    DuplicateAdmissionMode.URL_TYPE,
                )
            }.exceptionOrNull()
        } finally {
            sqlite.execSQL("DROP TRIGGER fail_duplicate_admission_insert")
        }

        assertTrue("insertion failure must be surfaced", failure != null)
        assertTrue(database.downloadDao.getActiveAndQueuedDownloadsList().isEmpty())

        val retry = repository.insertNewWithDuplicateAdmission(
            item,
            DuplicateAdmissionMode.URL_TYPE,
        )
        assertTrue(retry is DuplicateAdmissionResult.Inserted)
        assertEquals(1, database.downloadDao.getActiveAndQueuedDownloadsList().size)
    }

    private suspend fun concurrentAdmissions(
        firstRepository: DownloadRepository,
        secondRepository: DownloadRepository,
        first: DownloadItem,
        second: DownloadItem,
        mode: DuplicateAdmissionMode,
    ): List<DuplicateAdmissionResult> = coroutineScope {
        val ready = CompletableDeferred<Unit>()
        val started = AtomicInteger(0)
        val firstResult = async(Dispatchers.IO) {
            started.incrementAndGet()
            ready.await()
            firstRepository.insertNewWithDuplicateAdmission(first, mode)
        }
        val secondResult = async(Dispatchers.IO) {
            started.incrementAndGet()
            ready.await()
            secondRepository.insertNewWithDuplicateAdmission(second, mode)
        }
        while (started.get() < 2) yield()
        ready.complete(Unit)
        awaitAll(firstResult, secondResult)
    }

    private fun download(
        url: String,
        type: DownloadType = DownloadType.video,
    ) = DownloadItem(
        id = 0L,
        url = url,
        title = "Title",
        author = "Author",
        thumb = "",
        duration = "1:00",
        type = type,
        format = Format(format_id = "best", container = "mp4"),
        container = "mp4",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = "/downloads",
        website = "youtube.com",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "%(title)s",
        SaveThumb = false,
        status = DownloadRepository.Status.Queued.name,
        downloadStartTime = 0L,
        logID = null,
    )
}
