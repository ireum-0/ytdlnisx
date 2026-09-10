package com.ireum.ytdl.work

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpOutputPlan
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpProducerSemanticSnapshot
import com.ireum.ytdl.util.storage.DownloadCacheOwnership
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real startup recovery and DownloadWorker admission seam for a
 * completed producer generation.  The durable COMPLETE record is arranged as
 * it would be immediately before process death; the successor must adopt its
 * exact output without crossing the native producer boundary.
 */
@RunWith(AndroidJUnit4::class)
class DownloadProducerRecoveryProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var destination: File
    private var downloadId: Long = 0L
    private var operationId: String = ""
    private var executionId: String = ""
    private var stagingRoot: File? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(context)
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        DownloadWorkerEffectTestHooks.dbManagerForTesting = null
        DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        DownloadWorkerEffectTestHooks.ytdlpSuccessForTesting = null
        DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = null
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        destination = File(
            requireNotNull(context.getExternalFilesDir(null)),
            "p2b-complete-${UUID.randomUUID()}",
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()
        DownloadExecutionRecovery.clearForTesting(context)
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        DownloadWorkerEffectTestHooks.dbManagerForTesting = null
        DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        DownloadWorkerEffectTestHooks.ytdlpSuccessForTesting = null
        DownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = null
        if (::database.isInitialized) database.close()
        destination.deleteRecursively()
        stagingRoot?.let { root ->
            val cacheRoot = FileUtil.getCachePath(context).let(::File).canonicalFile
            if (root.parentFile?.canonicalFile == cacheRoot && root.name == downloadId.toString()) {
                root.deleteRecursively()
                DownloadCacheOwnership.markerFile(cacheRoot, downloadId).delete()
            }
        }
    }

    @Test
    fun completeProducerSurvivesStartupRecoveryAndIsAdoptedWithoutNativeReplay() = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadCacheSetting = preferences.contains("cache_downloads")
        val previousCacheSetting = preferences.getBoolean("cache_downloads", true)
        assertTrue(preferences.edit().putBoolean("cache_downloads", true).commit())
        try {
            downloadId = System.currentTimeMillis().coerceAtLeast(20_000_000L)
            operationId = "p2b-complete-$downloadId-${UUID.randomUUID()}"
            executionId = "e1-${UUID.randomUUID()}"
            val e1 = download().copy(
                id = downloadId,
                status = DownloadRepository.Status.Active.name,
                executionId = executionId,
                operationId = operationId,
                incognito = true,
            )
            database.downloadDao.insertRaw(e1)

            val ytdlp = YTDLPUtil(context, database.commandTemplateDao)
            val outputPlan = ytdlp.resolveOutputPlan(e1)
            stagingRoot = outputPlan.ytdlpDirectory.canonicalFile
            val request = ytdlp.buildYoutubeDLRequest(
                downloadItem = e1,
                mediaAccessProfile = ytdlp.resolveInitialYoutubeMediaAccessProfile(e1),
                outputPlan = outputPlan,
            )
            val snapshot = requireNotNull(YtdlpProducerSemanticSnapshot.forRequest(request))
            val fingerprint = DownloadProducerSemanticFingerprint.fingerprint(
                command = ytdlp.parseYTDLRequestString(request),
                outputPlan = outputPlan,
                effectiveProducerSemantics = snapshot.configContents,
                runtimePaths = snapshot.runtimePaths,
                publicationSemantics = mapOf(
                    "incognito" to "true",
                    "redownload" to "none",
                ),
            )
            request.getArguments("--config-locations")
                ?.filterNotNull()
                ?.forEach { File(it).delete() }

            val cacheRoot = File(FileUtil.getCachePath(context)).canonicalFile
            assertTrue(DownloadCacheOwnership.ensureMarker(cacheRoot, e1).isFile)
            val output = File(requireNotNull(stagingRoot), "adopted.m4a").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            assertTrue(DownloadCacheOwnership.recordArtifacts(cacheRoot, e1, listOf(output.absolutePath)))
            val prepared = requireNotNull(
                DownloadProducerRecovery.prepare(
                    context = context,
                    downloadId = downloadId,
                    operationId = operationId,
                    executionId = executionId,
                    semanticFingerprint = fingerprint,
                    outputRoot = requireNotNull(stagingRoot),
                )
            )
            assertTrue(DownloadProducerRecovery.markRunning(context, prepared))
            val complete = requireNotNull(
                DownloadProducerRecovery.markComplete(
                    context = context,
                    record = prepared,
                    outputPaths = listOf(output.absolutePath),
                )
            )
            assertEquals(DownloadProducerRecovery.Phase.COMPLETE, complete.phase)

            // These are the durable artifacts visible after E1 process death.
            repeat(2) {
                DownloadExecutionRecovery.reconcile(context, database)
                assertTrue(output.isFile)
                val retained = DownloadProducerRecovery.discover(context)
                assertTrue(
                    retained is DownloadProducerRecovery.DiscoveryResult.Healthy &&
                        retained.records.any {
                            it.downloadId == downloadId &&
                                it.executionId == executionId &&
                                it.phase == DownloadProducerRecovery.Phase.COMPLETE
                        },
                )
            }
            assertEquals(
                DownloadRepository.Status.Queued.name,
                database.downloadDao.getNullableDownloadById(downloadId)?.status,
            )
            assertEquals("", database.downloadDao.getNullableDownloadById(downloadId)?.executionId)
            DownloadExecutionRecovery.cancelAllRecoveryJobsForTesting()

            val nativeCalls = AtomicInteger(0)
            DownloadWorkerEffectTestHooks.dbManagerForTesting = database
            DownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { candidateId ->
                if (candidateId == downloadId) {
                    nativeCalls.incrementAndGet()
                    error("A compatible COMPLETE predecessor must be adopted before native execution")
                }
            }
            val workInfo = enqueueAndAwaitWorker()

            assertTrue(workInfo.state.isFinished)
            assertEquals(0, nativeCalls.get())
            assertTrue(File(destination, "adopted.m4a").isFile)
            assertFalse(database.downloadDao.getNullableDownloadById(downloadId) != null)
            val remaining = (DownloadProducerRecovery.discover(context) as? DownloadProducerRecovery.DiscoveryResult.Healthy)
                ?.records
                .orEmpty()
            assertTrue(remaining.none { it.downloadId == downloadId })
        } finally {
            val editor = preferences.edit()
            if (hadCacheSetting) editor.putBoolean("cache_downloads", previousCacheSetting)
            else editor.remove("cache_downloads")
            assertTrue(editor.commit())
        }
    }

    private suspend fun enqueueAndAwaitWorker(): WorkInfo {
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag("p2b-complete-production")
            .build()
        workManager.enqueue(request)
        return withContext(Dispatchers.IO) {
            repeat(240) {
                val info = runCatching {
                    workManager.getWorkInfoById(request.id).get(1, TimeUnit.SECONDS)
                }.getOrNull()
                if (info?.state?.isFinished == true) return@withContext info
                Thread.sleep(250L)
            }
            error("Timed out waiting for completed-producer recovery worker")
        }
    }

    private fun download() = DownloadItem(
        id = 0L,
        url = "https://example.com/p2b-complete",
        title = "p2b complete",
        author = "author",
        thumb = "",
        duration = "00:01",
        type = DownloadType.audio,
        format = Format(container = "m4a", format_note = "audio only"),
        container = "m4a",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = destination.absolutePath,
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
        playlistURL = "",
    )
}
