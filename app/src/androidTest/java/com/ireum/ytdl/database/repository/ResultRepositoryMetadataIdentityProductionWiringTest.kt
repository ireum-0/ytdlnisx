package com.ireum.ytdl.database.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.util.ExtractorSourceIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultRepositoryMetadataIdentityProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var repository: ResultRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
    }

    @After
    fun tearDown() {
        ResultRepositoryMetadataTestHooks.clearForTesting()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun freshFirstRejectsMismatchedCandidateWithoutApplyingFields() = runBlocking {
        val source = "https://requested.example/video/1"
        ResultRepositoryMetadataTestHooks.cachedMetadataForTesting = { null }
        ResultRepositoryMetadataTestHooks.freshMetadataForTesting = {
            result(
                url = "https://other.example/video/2",
                title = "unrelated title",
                author = "unrelated author",
                identity = identity(canonical = "https://other.example/video/2"),
            )
        }

        val item = download(source)
        assertNull(repository.updateDownloadItem(item, ResultRepository.DownloadMetadataLookupOrder.FRESH_FIRST))
        assertBlankMetadata(item)
    }

    @Test
    fun freshFirstRejectsMissingProvenanceWithoutApplyingFields() = runBlocking {
        val source = "https://requested.example/video/1"
        ResultRepositoryMetadataTestHooks.cachedMetadataForTesting = { null }
        ResultRepositoryMetadataTestHooks.freshMetadataForTesting = {
            result(url = "", title = "unproven title")
        }

        val item = download(source)
        assertNull(repository.updateDownloadItem(item, ResultRepository.DownloadMetadataLookupOrder.FRESH_FIRST))
        assertBlankMetadata(item)
    }

    @Test
    fun freshFirstAcceptsEquivalentYoutubeSourceIdentity() = runBlocking {
        val source = "https://www.youtube.com/watch?v=AAA111BBB22"
        ResultRepositoryMetadataTestHooks.cachedMetadataForTesting = { null }
        ResultRepositoryMetadataTestHooks.freshMetadataForTesting = {
            result(
                url = "https://youtu.be/AAA111BBB22",
                title = "accepted title",
                identity = identity(
                    canonical = "https://youtu.be/AAA111BBB22",
                    stableMediaId = "AAA111BBB22",
                    extractor = "Youtube",
                ),
            )
        }

        val item = download(source)
        assertNotNull(repository.updateDownloadItem(item, ResultRepository.DownloadMetadataLookupOrder.FRESH_FIRST))
        assertEquals("accepted title", item.title)
    }

    @Test
    fun freshFirstAcceptsApprovedVimeoRedirectIdentity() = runBlocking {
        val source = "https://player.vimeo.com/video/123456"
        ResultRepositoryMetadataTestHooks.cachedMetadataForTesting = { null }
        ResultRepositoryMetadataTestHooks.freshMetadataForTesting = {
            result(
                url = "https://vimeo.com/123456",
                title = "accepted redirect",
                identity = identity(
                    canonical = "https://vimeo.com/123456",
                    stableMediaId = "123456",
                    extractor = "Vimeo",
                ),
            )
        }

        val item = download(source)
        assertNotNull(repository.updateDownloadItem(item, ResultRepository.DownloadMetadataLookupOrder.FRESH_FIRST))
        assertEquals("accepted redirect", item.title)
    }

    @Test
    fun cacheFirstPreservesUsableCacheWhenFreshCandidateIsUnsafe() = runBlocking {
        val source = "https://requested.example/video/1"
        ResultRepositoryMetadataTestHooks.cachedMetadataForTesting = {
            result(
                url = source,
                title = "cached title",
                author = "",
                identity = identity(canonical = source),
            )
        }
        ResultRepositoryMetadataTestHooks.freshMetadataForTesting = {
            result(
                url = "https://other.example/video/2",
                title = "unsafe title",
                author = "unsafe author",
                identity = identity(canonical = "https://other.example/video/2"),
            )
        }

        val item = download(source)
        assertNotNull(repository.updateDownloadItem(item, ResultRepository.DownloadMetadataLookupOrder.CACHE_FIRST))
        assertEquals("cached title", item.title)
        assertEquals("", item.author)
    }

    @Test
    fun cacheFirstRejectsMismatchedFreshFallbackOnCacheMiss() = runBlocking {
        val source = "https://requested.example/video/1"
        ResultRepositoryMetadataTestHooks.cachedMetadataForTesting = { null }
        ResultRepositoryMetadataTestHooks.freshMetadataForTesting = {
            result(
                url = "https://other.example/video/2",
                title = "unsafe title",
                identity = identity(canonical = "https://other.example/video/2"),
            )
        }

        val item = download(source)
        assertNull(repository.updateDownloadItem(item, ResultRepository.DownloadMetadataLookupOrder.CACHE_FIRST))
        assertBlankMetadata(item)
    }

    @Test
    fun cacheFirstAcceptsValidFreshFallbackOnCacheMiss() = runBlocking {
        val source = "https://requested.example/video/1"
        ResultRepositoryMetadataTestHooks.cachedMetadataForTesting = { null }
        ResultRepositoryMetadataTestHooks.freshMetadataForTesting = {
            result(
                url = source,
                title = "fresh title",
                identity = identity(canonical = source),
            )
        }

        val item = download(source)
        assertNotNull(repository.updateDownloadItem(item, ResultRepository.DownloadMetadataLookupOrder.CACHE_FIRST))
        assertEquals("fresh title", item.title)
    }

    @Test
    fun freshCancellationPropagatesWithoutApplyingMetadata() = runBlocking {
        val expected = CancellationException("cancelled")
        var cacheLookups = 0
        ResultRepositoryMetadataTestHooks.freshMetadataForTesting = { throw expected }
        ResultRepositoryMetadataTestHooks.cachedMetadataForTesting = {
            cacheLookups += 1
            result(url = "https://requested.example/video/1", title = "cached")
        }

        val item = download("https://requested.example/video/1")
        try {
            repository.updateDownloadItem(item, ResultRepository.DownloadMetadataLookupOrder.FRESH_FIRST)
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
        assertEquals(0, cacheLookups)
        assertBlankMetadata(item)
    }

    private fun assertBlankMetadata(item: DownloadItem) {
        assertEquals("", item.title)
        assertEquals("", item.author)
        assertEquals("", item.thumb)
        assertEquals("", item.duration)
        assertEquals(0L, item.mediaPublishedAt)
    }

    private fun identity(
        original: String = "",
        canonical: String = "",
        stableMediaId: String = "",
        extractor: String = "",
    ) = ExtractorSourceIdentity(
        originalUrl = original,
        canonicalUrl = canonical,
        stableMediaId = stableMediaId,
        extractor = extractor,
    )

    private fun result(
        url: String,
        title: String,
        author: String = "author",
        identity: ExtractorSourceIdentity? = null,
    ) = ResultItem(
        id = 0L,
        url = url,
        title = title,
        author = author,
        duration = "",
        thumb = "",
        website = "",
        playlistTitle = "",
        urls = "",
        chapters = null,
        mediaPublishedAt = 0L,
    ).also { it.sourceIdentity = identity }

    private fun download(source: String) = DownloadItem(
        id = 0L,
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
        customFileNameTemplate = "%(title)s",
        SaveThumb = false,
        status = DownloadRepository.Status.Saved.name,
        downloadStartTime = 0L,
        logID = null,
        mediaPublishedAt = 0L,
    )
}
