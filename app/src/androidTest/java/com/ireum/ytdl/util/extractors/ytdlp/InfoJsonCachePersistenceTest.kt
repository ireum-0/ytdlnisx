package com.ireum.ytdl.util.extractors.ytdlp

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
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.util.FileUtil
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InfoJsonCachePersistenceTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var cacheRoot: File
    private lateinit var ytdlp: YTDLPUtil
    private val fixtures = mutableSetOf<File>()
    private val fixturePrefixes = mutableSetOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        cacheRoot = File(FileUtil.getCachePath(context), "infojsons").apply { mkdirs() }
        ytdlp = YTDLPUtil(context, database.commandTemplateDao)
    }

    @After
    fun tearDown() {
        fixtures.forEach(File::delete)
        cacheRoot.listFiles().orEmpty()
            .filter { file -> fixturePrefixes.any(file.name::startsWith) }
            .forEach(File::delete)
        if (::database.isInitialized) database.close()
    }

    @Test
    fun schemeLessAuthoritativeWriteIsFoundThroughNormalizedLookup() {
        val schemeLess = "cache-key-a.example/media/1"
        fixture(schemeLess, title = "scheme-less authoritative")

        val cached = ytdlp.getCachedInfoJsonResultOrThrow("https://$schemeLess")

        assertEquals("scheme-less authoritative", cached?.title)
    }

    @Test
    fun normalizedWriteIsFoundThroughSchemeLessLookup() {
        val schemeLess = "cache-key-b.example/media/2"
        fixture("https://$schemeLess", title = "normalized authoritative")

        val cached = ytdlp.getCachedInfoJsonResultOrThrow(schemeLess)

        assertEquals("normalized authoritative", cached?.title)
    }

    @Test
    fun validatedLegacyEntryIsUsedAfterMissAndMigrated() {
        val source = "cache-key-c.example/media/3"
        val key = InfoJsonCacheKeyPolicy.resolve(source)
        val legacy = fixture(source, title = "legacy", legacy = true)

        val cached = ytdlp.getCachedInfoJsonResultOrThrow(source)

        assertEquals("legacy", cached?.title)
        assertFalse(legacy.exists())
        assertTrue(
            cacheRoot.listFiles().orEmpty().any {
                it.name.startsWith(key.authoritativePrefix) && it.name.endsWith(".info.json")
            }
        )
    }

    @Test
    fun authoritativeEntryWinsWithoutTouchingLegacyEntry() {
        val source = "cache-key-precedence.example/media/3"
        val legacy = fixture(source, title = "legacy", legacy = true)
        fixture(source, title = "authoritative")

        val cached = ytdlp.getCachedInfoJsonResultOrThrow(source)

        assertEquals("authoritative", cached?.title)
        assertTrue(legacy.exists())
    }

    @Test
    fun invalidAndExpiredLegacyEntriesAreRejectedWithoutMigration() {
        val invalidSource = "cache-key-d.example/media/4"
        val invalid = fixture(
            invalidSource,
            title = "invalid",
            legacy = true,
            provenance = "https://different.example/media/4",
        )
        val expiredSource = "cache-key-e.example/media/5"
        val expired = fixture(
            expiredSource,
            title = "expired",
            legacy = true,
            ageMillis = 6L * 60L * 60L * 1000L,
        )

        assertNull(ytdlp.getCachedInfoJsonResultOrThrow(invalidSource))
        assertNull(ytdlp.getCachedInfoJsonResultOrThrow(expiredSource))
        assertTrue(invalid.exists())
        assertTrue(expired.exists())
        assertFalse(hasAuthoritativeFile(invalidSource))
        assertFalse(hasAuthoritativeFile(expiredSource))
    }

    @Test
    fun explicitHttpAndHttpsCacheIdentitiesRemainDistinct() {
        val http = "http://cache-key-f.example/media/6"
        val https = "https://cache-key-f.example/media/6"
        fixture(http, title = "http")
        fixture(https, title = "https")

        assertEquals("http", ytdlp.getCachedInfoJsonResultOrThrow(http)?.title)
        assertEquals("https", ytdlp.getCachedInfoJsonResultOrThrow(https)?.title)
    }

    @Test
    fun dateFetchMultiResultLookupReusesAuthoritativeCache() {
        val source = "cache-key-g.example/media/7"
        fixture(source, title = "older", uploadDate = "20240101", timestamp = 1_001L)
        fixture(source, title = "newer", uploadDate = "20240202", timestamp = 1_002L)

        val results = ytdlp.getCachedInfoJsonResultsOrThrow(source)

        assertEquals(setOf("older", "newer"), results.map { it.title }.toSet())
        assertEquals(2, results.map { it.mediaPublishedAt }.distinct().size)
    }

    @Test
    fun cacheFirstEnrichmentCompletesWithoutSourceRequest() = runBlocking {
        val source = "cache-key-h.example/media/8"
        fixture(source, title = "cached title", uploadDate = "20240303")
        val item = download(source)
        val resultRepository = ResultRepository(
            database.resultDao,
            database.commandTemplateDao,
            context,
        )

        val updated = resultRepository.updateDownloadItem(
            item,
            ResultRepository.DownloadMetadataLookupOrder.CACHE_FIRST,
        )

        assertNotNull(updated)
        assertEquals("cached title", item.title)
        assertEquals("Cache Author", item.author)
        assertEquals("https://images.example/thumb.jpg", item.thumb)
        assertTrue(item.mediaPublishedAt != 0L)
    }

    private fun fixture(
        source: String,
        title: String,
        legacy: Boolean = false,
        provenance: String = InfoJsonCacheKeyPolicy.resolve(source).dispatchSource,
        uploadDate: String = "20240101",
        timestamp: Long = System.nanoTime(),
        ageMillis: Long = 0L,
    ): File {
        val key = InfoJsonCacheKeyPolicy.resolve(source)
        fixturePrefixes += key.authoritativePrefix
        val legacyPrefix = InfoJsonCacheKeyPolicy.legacySchemeLessPrefix(source, key)
        legacyPrefix?.let(fixturePrefixes::add)
        val prefix = if (legacy) legacyPrefix!! else key.authoritativePrefix
        val file = File(cacheRoot, "${prefix}${timestamp}video.info.json")
        file.writeText(
            """{
                "id":"cache-item",
                "title":"$title",
                "uploader":"Cache Author",
                "thumbnail":"https://images.example/thumb.jpg",
                "duration":60,
                "extractor":"Generic",
                "webpage_url_domain":"cache.example",
                "original_url":"$provenance",
                "webpage_url":"$provenance",
                "url":"$provenance",
                "_type":"video",
                "upload_date":"$uploadDate",
                "formats":[]
            }""".trimIndent()
        )
        file.setLastModified(System.currentTimeMillis() - ageMillis)
        fixtures += file
        return file
    }

    private fun hasAuthoritativeFile(source: String): Boolean {
        val prefix = InfoJsonCacheKeyPolicy.resolve(source).authoritativePrefix
        return cacheRoot.listFiles().orEmpty().any { it.name.startsWith(prefix) }
    }

    private fun download(source: String) = DownloadItem(
        id = 0,
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
        downloadStartTime = 0,
        logID = null,
        mediaPublishedAt = 0,
    )
}
