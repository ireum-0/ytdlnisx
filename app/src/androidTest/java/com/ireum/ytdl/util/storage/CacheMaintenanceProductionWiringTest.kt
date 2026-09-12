package com.ireum.ytdl.util.storage

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.work.DownloadWorkerExecutionOwners
import com.ireum.ytdl.work.DownloadWorkerProcessOwners
import com.ireum.ytdl.work.claimDownloadThroughProductionAdmission
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Exercises the real AppCacheManager deletion boundary with an Android
 * Context.  A live execution is retained while the maintenance window is
 * held, then the same exact owned material becomes eligible after release.
 */
@RunWith(AndroidJUnit4::class)
class CacheMaintenanceProductionWiringTest {
    private lateinit var context: Context
    private lateinit var db: DBManager
    private lateinit var preferences: android.content.SharedPreferences
    private var hadCachePath = false
    private var previousCachePath: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = DBManager.getInstance(context)
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        hadCachePath = preferences.contains("cache_path")
        previousCachePath = preferences.getString("cache_path", null)
    }

    @After
    fun tearDown() {
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
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

    @Test
    fun staleMarkerCannotRaceAnewDownloadOwnerAtDeleteOrImportBoundary() = runBlocking {
        val externalFiles = requireNotNull(context.getExternalFilesDir(null))
        val cacheRoot = File(externalFiles, "maintenance-stale-${UUID.randomUUID()}").apply { mkdirs() }
        val queued = item().copy(
            status = DownloadRepository.Status.Queued.name,
            downloadStartTime = 0L,
            operationId = "operation-current-${UUID.randomUUID()}",
            executionId = "",
        )
        val downloadId = db.downloadDao.insertRaw(queued)
        val persistedQueued = requireNotNull(db.downloadDao.getNullableDownloadById(downloadId))
        val prior = persistedQueued.copy(
            operationId = "operation-prior-${UUID.randomUUID()}",
            executionId = "execution-prior-${UUID.randomUUID()}",
        )
        try {
            assertTrue(preferences.edit().putString("cache_path", cacheRoot.absolutePath).commit())
            val staging = File(cacheRoot, prior.id.toString()).apply { mkdirs() }
            DownloadCacheOwnership.ensureMarker(cacheRoot, prior)

            // Use the production claim/CAS boundary.  It publishes E2's
            // process-local owner while the stale E1 marker is still present;
            // maintenance must not infer a negative from that older marker.
            val claimed = requireNotNull(
                claimDownloadThroughProductionAdmission(
                    context = context,
                    dbManager = db,
                    candidate = persistedQueued,
                    concurrentDownloadLimit = Int.MAX_VALUE,
                )
            )
            assertEquals(downloadId, claimed.id)
            assertTrue(claimed.executionId.isNotBlank())
            assertTrue(claimed.executionId != prior.executionId)

            val liveDelete = AppCacheManager(context).delete(setOf(AppCacheCategory.DOWNLOAD_TEMP))
            assertTrue(staging.exists())
            assertTrue(liveDelete.failedEntries > 0)
            assertFalse(liveDelete.isComplete)
            assertTrue(CacheImportPlanner.collect(cacheRoot).isEmpty())

            // The real worker rotates the stale marker only after claim.  The
            // exact current owner remains protected both before and after
            // that filesystem publication boundary.
            val prepared = DownloadCacheOwnership.prepareAttempt(cacheRoot, claimed)
            assertEquals(staging.canonicalFile, prepared.canonicalFile)
            val output = File(prepared, "video.mp4").apply { writeText("current") }
            assertTrue(DownloadCacheOwnership.recordArtifacts(cacheRoot, claimed, listOf(output.absolutePath)))
            val liveCurrentDelete = AppCacheManager(context).delete(setOf(AppCacheCategory.DOWNLOAD_TEMP))
            assertTrue(output.exists())
            assertTrue(liveCurrentDelete.failedEntries > 0)
            assertFalse(liveCurrentDelete.isComplete)
            assertTrue(CacheImportPlanner.collect(cacheRoot).isEmpty())

            DownloadWorkerExecutionOwners.release(claimed.id, claimed.executionId)
            val importManifest = CacheImportPlanner.collect(cacheRoot)
            assertEquals(listOf(output.canonicalPath), importManifest.map { it.source.canonicalPath })
            val cleanup = AppCacheManager(context).delete(setOf(AppCacheCategory.DOWNLOAD_TEMP))
            assertFalse(output.exists())
            assertTrue(cleanup.deletedFiles > 0)
        } finally {
            DownloadWorkerExecutionOwners.ownerOf(downloadId)?.let { executionId ->
                DownloadWorkerExecutionOwners.release(downloadId, executionId)
            }
            db.downloadDao.delete(downloadId)
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
