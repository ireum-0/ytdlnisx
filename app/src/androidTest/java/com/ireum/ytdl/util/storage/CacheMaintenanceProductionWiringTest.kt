package com.ireum.ytdl.util.storage

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.work.DownloadWorkerExecutionOwners
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Exercises the production cache deletion boundary with a real Android
 * Context.  A live execution is skipped while the maintenance window is
 * held, then the same exact artifact becomes eligible after its owner exits.
 */
@RunWith(AndroidJUnit4::class)
class CacheMaintenanceProductionWiringTest {
    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private var hadCachePath = false
    private var previousCachePath: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        hadCachePath = preferences.contains("cache_path")
        previousCachePath = preferences.getString("cache_path", null)
    }

    @After
    fun tearDown() {
        val editor = preferences.edit()
        if (hadCachePath) editor.putString("cache_path", previousCachePath) else editor.remove("cache_path")
        assertTrue(editor.commit())
    }

    @Test
    fun liveDownloadArtifactIsSkippedByProductionDeleteUntilOwnerExits() = runBlocking {
        val externalFiles = requireNotNull(context.getExternalFilesDir(null))
        val cacheRoot = File(externalFiles, "maintenance-${UUID.randomUUID()}").apply { mkdirs() }
        val item = item()
        try {
            assertTrue(preferences.edit().putString("cache_path", cacheRoot.absolutePath).commit())
            val staging = File(cacheRoot, item.id.toString()).apply { mkdirs() }
            DownloadCacheOwnership.ensureMarker(cacheRoot, item)
            val output = File(staging, "video.mp4").apply { writeText("owned") }
            assertTrue(DownloadCacheOwnership.recordArtifacts(cacheRoot, item, listOf(output.absolutePath)))
            DownloadWorkerExecutionOwners.claim(item.id, item.executionId)

            val liveResult = AppCacheManager(context).delete(setOf(AppCacheCategory.DOWNLOAD_TEMP))

            assertTrue(output.exists())
            assertTrue(liveResult.failedEntries > 0)
            assertFalse(liveResult.isComplete)

            DownloadWorkerExecutionOwners.release(item.id, item.executionId)
            val recoveredResult = AppCacheManager(context).delete(setOf(AppCacheCategory.DOWNLOAD_TEMP))

            assertFalse(output.exists())
            assertTrue(recoveredResult.deletedFiles > 0)
        } finally {
            DownloadWorkerExecutionOwners.release(item.id, item.executionId)
            cacheRoot.deleteRecursively()
        }
    }

    private fun item() = DownloadItem(
        id = System.nanoTime(),
        url = "https://example.com/cache-maintenance",
        title = "cache-maintenance",
        author = "author",
        thumb = "",
        duration = "00:01",
        type = DownloadType.video,
        format = Format(format_id = "best"),
        container = "mp4",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = "/downloads",
        website = "example.com",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = "Active",
        downloadStartTime = 1L,
        logID = null,
        operationId = "operation-${UUID.randomUUID()}",
        executionId = "execution-${UUID.randomUUID()}",
    )
}
