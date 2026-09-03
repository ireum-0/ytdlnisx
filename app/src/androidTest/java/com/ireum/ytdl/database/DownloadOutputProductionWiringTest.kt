package com.ireum.ytdl.database

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.DownloadOutputProvenance
import com.ireum.ytdl.work.DownloadWorker
import com.ireum.ytdl.work.DownloadWorkerEffectTestHooks
import com.ireum.ytdl.work.DownloadWorkerExecutionOwners
import com.ireum.ytdl.work.DownloadWorkerProcessOwners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val outputWiringDownloadIds = AtomicLong(
    System.currentTimeMillis().coerceAtLeast(10_000_000L),
)

@RunWith(AndroidJUnit4::class)
class DownloadOutputProductionWiringTest {
    private lateinit var db: DBManager
    private lateinit var testRoot: File

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        cancelStaleRealWorkerRequests(context)
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(context)
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        clearOutputHooks()
        testRoot = File(
            requireNotNull(context.getExternalFilesDir(null)),
            "bug-output-${UUID.randomUUID()}",
        )
        assertTrue(testRoot.mkdirs())
        db = Room.inMemoryDatabaseBuilder(
            context,
            DBManager::class.java,
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        cancelStaleRealWorkerRequests(ApplicationProvider.getApplicationContext())
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(ApplicationProvider.getApplicationContext())
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        clearOutputHooks()
        db.close()
        testRoot.deleteRecursively()
    }

    @Test
    fun realWorkerDirectNoCachePublishesOnlyExactCurrentOutput() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "direct").apply { mkdirs() }
            val unrelated = File(destination, "unrelated.m4a").apply { writeBytes(byteArrayOf(7, 7, 7)) }
            var currentSource: File? = null
            var currentOutputDirectory: File? = null
            db.downloadDao.insertRaw(download(downloadId, destination.absolutePath))
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    currentOutputDirectory = outputDirectory
                    File(destination, "created-during-attempt.m4a").writeBytes(byteArrayOf(2, 2, 2))
                    currentSource = File(outputDirectory, "current.m4a").apply {
                        writeBytes(byteArrayOf(1, 2, 3))
                    }
                    "[download] Destination: '${requireNotNull(currentSource).absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext())

            val ownedOutputDirectory = requireNotNull(currentOutputDirectory)
            assertEquals(".ytdlnisx-output", ownedOutputDirectory.parentFile?.name)
            assertEquals(destination.canonicalFile, ownedOutputDirectory.parentFile?.parentFile?.canonicalFile)
            val history = db.historyDao.getItemByDownloadId(downloadId)
            assertNotNull(history)
            val persisted = requireNotNull(history)
            assertEquals(1, persisted.downloadPath.size)
            assertTrue(persisted.downloadPath.single().endsWith("current.m4a"))
            assertFalse(persisted.downloadPath.any { it.endsWith("unrelated.m4a") })
            assertTrue(unrelated.exists())
            assertTrue(File(destination, "created-during-attempt.m4a").exists())
            assertFalse(currentSource!!.exists())
        }
    }

    @Test
    fun realWorkerRejectsAmbientRecentAndSameNameFiles() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "ambient").apply { mkdirs() }
            val recent = File(destination, "recent.m4a").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val sameName = File(destination, "same-name.m4a").apply { writeBytes(byteArrayOf(8, 9, 0)) }
            db.downloadDao.insertRaw(download(downloadId, destination.absolutePath))
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, _ ->
                if (candidateId != downloadId) {
                    null
                } else {
                    "[download] Destination: '${sameName.absolutePath}'\n" +
                        "[download] Destination: '${recent.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext())

            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(recent.exists())
            assertTrue(sameName.exists())
        }
    }

    @Test
    fun realWorkerDirectBaselineFailureDoesNotPromoteAmbientOutput() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "baseline-failure").apply { mkdirs() }
            val current = File(destination, "ambient.m4a").apply { writeBytes(byteArrayOf(1, 1, 1)) }
            db.downloadDao.insertRaw(download(downloadId, destination.absolutePath))
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.outputBaselineReaderForTesting = { directory ->
                if (directory.parentFile?.name == ".ytdlnisx-output") {
                    DownloadOutputProvenance.BaselineSnapshot.Failed("injected direct baseline failure")
                } else {
                    DownloadOutputProvenance.BaselineSnapshot.Complete(emptySet())
                }
            }
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    val reported = File(outputDirectory, "reported.m4a").apply {
                        writeBytes(byteArrayOf(2, 2, 2))
                    }
                    "[download] Destination: '${reported.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext())

            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(current.exists())
        }
    }

    @Test
    fun realWorkerUsesExplicitCustomCommandPathAsFinalDestination() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val configuredDestination = File(testRoot, "configured").apply { mkdirs() }
            val effectiveDestination = File(testRoot, "effective").apply { mkdirs() }
            val commandPath = effectiveDestination.absolutePath.replace('\\', '/')
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = configuredDestination.absolutePath,
                    type = DownloadType.command,
                    formatNote = "-P \"$commandPath\" --no-playlist",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, outputDirectory ->
                if (candidateId != downloadId) {
                    null
                } else {
                    val reported = File(outputDirectory, "command-output.m4a").apply {
                        writeBytes(byteArrayOf(3, 3, 3))
                    }
                    "${DownloadOutputProvenance.PRINT_MARKER}'${reported.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext())

            val history = requireNotNull(db.historyDao.getItemByDownloadId(downloadId))
            assertTrue(history.downloadPath.single().startsWith(effectiveDestination.canonicalPath))
            assertTrue(history.downloadPath.single().endsWith("command-output.m4a"))
            assertTrue(configuredDestination.listFiles().orEmpty().none { it.name == "command-output.m4a" })
        }
    }

    @Test
    fun realWorkerRejectsCustomCommandOutputOutsideEffectiveDestination() = runBlocking {
        withCacheDownloads(true) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val configuredDestination = File(testRoot, "configured-outside").apply { mkdirs() }
            val effectiveDestination = File(testRoot, "effective-outside").apply { mkdirs() }
            val foreignDestination = File(testRoot, "foreign-outside").apply { mkdirs() }
            val commandPath = effectiveDestination.absolutePath.replace('\\', '/')
            val foreign = File(foreignDestination, "foreign-output.m4a").apply {
                writeBytes(byteArrayOf(4, 4, 4))
            }
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = configuredDestination.absolutePath,
                    type = DownloadType.command,
                    formatNote = "-P \"$commandPath\" --no-playlist",
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, _ ->
                if (candidateId == downloadId) {
                    "${DownloadOutputProvenance.PRINT_MARKER}'${foreign.absolutePath}'"
                } else {
                    null
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext())

            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(
                DownloadRepository.Status.Error.name,
                db.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertTrue(foreign.exists())
            assertTrue(effectiveDestination.listFiles().orEmpty().none { it.name == foreign.name })
        }
    }

    @Test
    fun unprovenOutputCannotReachHistoryReplacementOrOldMediaDeletion() = runBlocking {
        withCacheDownloads(false) {
            val downloadId = outputWiringDownloadIds.getAndIncrement()
            val destination = File(testRoot, "replacement").apply { mkdirs() }
            val oldMedia = File(destination, "old.m4a").apply { writeBytes(byteArrayOf(9, 9, 9)) }
            val ambient = File(destination, "ambient.m4a").apply { writeBytes(byteArrayOf(6, 6, 6)) }
            val historyId = db.historyDao.insertAndGetIdRaw(
                history(oldMedia.absolutePath).copy(downloadId = 0L)
            )
            db.downloadDao.insertRaw(
                download(
                    id = downloadId,
                    destination = destination.absolutePath,
                    playlistUrl = HistoryRedownloadMarker.regular(historyId),
                )
            )
            DownloadWorkerEffectTestHooks.dbManagerForTesting = db
            DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = { candidateId, _, _ ->
                if (candidateId != downloadId) {
                    null
                } else {
                    "[download] Destination: '${ambient.absolutePath}'"
                }
            }

            enqueueAndAwaitDownloadWorker(ApplicationProvider.getApplicationContext())

            assertNull(db.historyDao.getItemByDownloadId(downloadId))
            assertEquals(oldMedia.absolutePath, requireNotNull(db.historyDao.getNullableItem(historyId)).downloadPath.single())
            assertTrue(oldMedia.exists())
            assertTrue(ambient.exists())
        }
    }

    private fun clearOutputHooks() {
        DownloadWorkerEffectTestHooks.dbManagerForTesting = null
        DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        DownloadWorkerEffectTestHooks.ytdlpSuccessForTesting = null
        DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = null
        DownloadWorkerEffectTestHooks.outputBaselineReaderForTesting = null
        DownloadWorkerEffectTestHooks.beforeNoCacheMediaPublicationForTesting = null
        DownloadWorkerEffectTestHooks.beforeNoCacheMediaScanForTesting = null
    }

    private suspend fun <T> withCacheDownloads(enabled: Boolean, block: suspend () -> T): T {
        val preferences = PreferenceManager.getDefaultSharedPreferences(
            ApplicationProvider.getApplicationContext()
        )
        val hadSetting = preferences.contains("cache_downloads")
        val previous = preferences.getBoolean("cache_downloads", true)
        assertTrue(preferences.edit().putBoolean("cache_downloads", enabled).commit())
        return try {
            block()
        } finally {
            val editor = preferences.edit()
            if (hadSetting) editor.putBoolean("cache_downloads", previous) else editor.remove("cache_downloads")
            assertTrue(editor.commit())
        }
    }

    private fun download(
        id: Long,
        destination: String,
        type: DownloadType = DownloadType.audio,
        formatNote: String = "audio only",
        playlistUrl: String? = "",
    ) = DownloadItem(
        id = id,
        url = "https://example.com/$id",
        title = "output provenance $id",
        author = "author",
        thumb = "",
        duration = "00:01",
        type = type,
        format = Format(container = "m4a", format_note = formatNote),
        container = "m4a",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = destination,
        website = "example",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = DownloadRepository.Status.Queued.name,
        downloadStartTime = 0L,
        logID = null,
        playlistURL = playlistUrl,
        operationId = "bug-output-$id-${UUID.randomUUID()}",
    )

    private fun history(path: String) = HistoryItem(
        id = 0L,
        url = "https://example.com/replacement",
        title = "old",
        author = "author",
        artist = "",
        duration = "00:01",
        durationSeconds = 1L,
        thumb = "",
        type = DownloadType.audio,
        time = 1L,
        downloadPath = listOf(path),
        website = "example",
        format = Format(container = "m4a"),
        filesize = 3L,
        downloadId = 0L,
    )

    private fun cancelStaleRealWorkerRequests(context: Context) {
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
    }

    private suspend fun enqueueAndAwaitDownloadWorker(context: Context): WorkInfo {
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag("bug-output-real-worker")
            .build()
        workManager.enqueue(request)
        return withContext(Dispatchers.IO) {
            repeat(240) {
                val workInfo = runCatching {
                    workManager.getWorkInfoById(request.id).get(1, TimeUnit.SECONDS)
                }.getOrNull()
                if (workInfo?.state?.isFinished == true) return@withContext workInfo
                Thread.sleep(250L)
            }
            error("Timed out waiting for real DownloadWorker ${request.id}")
        }
    }
}
