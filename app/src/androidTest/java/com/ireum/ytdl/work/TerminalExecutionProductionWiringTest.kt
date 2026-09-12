package com.ireum.ytdl.work

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.TerminalItem
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.storage.TerminalCacheOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Production WorkManager wiring for the durable Terminal execution witness.
 * The native response seam only avoids network/native-runtime dependence; the
 * worker still creates its command plan, admits the item, advances the durable
 * witness, and performs its ordinary no-output terminal commit.
 */
@RunWith(AndroidJUnit4::class)
class TerminalExecutionProductionWiringTest {
    private lateinit var context: Context
    private lateinit var db: DBManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = DBManager.getInstance(context)
        TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterAdmissionForTesting = null
    }

    @After
    fun tearDown() {
        TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = null
        TerminalDownloadWorkerEffectTestHooks.afterAdmissionForTesting = null
    }

    @Test
    fun durableWitnessExistsBeforeNoCacheNativeBoundary() = runBlocking {
        // An authored absolute -P is an explicit direct/no-cache route. The
        // simulation keeps this production-boundary test independent of
        // network/native output while still exercising the no-cache path.
        val command = "--simulate -P /storage/emulated/0/Download https://example.com/terminal-witness"
        val itemId = db.terminalDao.insert(TerminalItem(command = command))
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadCachePreference = preferences.contains("cache_downloads")
        val previousCache = preferences.getBoolean("cache_downloads", true)
        val hadCommandPath = preferences.contains("command_path")
        val previousCommandPath = preferences.getString("command_path", null)
        val witnessedBeforeNative = AtomicBoolean(false)
        var request: OneTimeWorkRequest? = null
        try {
            check(
                preferences.edit()
                    .putBoolean("cache_downloads", false)
                    .putString("command_path", context.filesDir.absolutePath)
                    .commit(),
            )
            TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { observedId, _ ->
                assertEquals(itemId.toInt(), observedId)
                assertEquals(
                    TerminalExecutionRecovery.Phase.NATIVE_STARTED,
                    TerminalExecutionRecovery.read(context, itemId)?.phase,
                )
                witnessedBeforeNative.set(true)
            }
            TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = { observedId, outputDirectory ->
                assertEquals(itemId.toInt(), observedId)
                assertNull(outputDirectory)
                ""
            }
            request = OneTimeWorkRequestBuilder<TerminalDownloadWorker>()
                .setInputData(
                    workDataOf(
                        "id" to itemId.toInt(),
                        "command" to command,
                    ),
                )
                .addTag("terminal-execution-recovery")
                .build()
            WorkManager.getInstance(context).enqueue(checkNotNull(request))
            val info = awaitFinished(checkNotNull(request))
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            assertEquals(true, witnessedBeforeNative.get())
            assertNull(db.terminalDao.getTerminalById(itemId))
            assertEquals(
                TerminalExecutionRecovery.Phase.COMMITTED,
                TerminalExecutionRecovery.read(context, itemId)?.phase,
            )
        } finally {
            request?.let {
                WorkManager.getInstance(context).cancelWorkById(it.id).result.get(10, TimeUnit.SECONDS)
            }
            db.terminalDao.delete(itemId)
            TerminalExecutionRecovery.clearForTesting(
                File(context.filesDir, "terminal-execution-recovery"),
                itemId,
            )
            preferences.edit().apply {
                if (hadCachePreference) putBoolean("cache_downloads", previousCache)
                else remove("cache_downloads")
                if (hadCommandPath) putString("command_path", previousCommandPath)
                else remove("command_path")
            }.commit()
        }
    }

    @Test
    fun admittedTerminalKeepsItsCacheRootWhenPreferenceChangesBeforePlanning() = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val hadCachePath = preferences.contains("cache_path")
        val previousCachePath = preferences.getString("cache_path", null)
        val hadCacheDownloads = preferences.contains("cache_downloads")
        val previousCacheDownloads = preferences.getBoolean("cache_downloads", true)
        val externalFiles = requireNotNull(context.getExternalFilesDir(null))
        val admittedRoot = File(externalFiles, "terminal-bound-${System.nanoTime()}").canonicalFile
        val command = "--simulate https://example.com/terminal-bound-root"
        val itemId = db.terminalDao.insert(TerminalItem(command = command))
        val observedStagingRoot = AtomicReference<File?>(null)
        val observedFallbackRoot = AtomicReference<File?>(null)
        var request: OneTimeWorkRequest? = null
        try {
            assertTrue(
                preferences.edit()
                    .putString("cache_path", admittedRoot.absolutePath)
                    .putBoolean("cache_downloads", true)
                    .commit(),
            )
            TerminalDownloadWorkerEffectTestHooks.afterAdmissionForTesting = { observedId, boundRoot ->
                assertEquals(itemId.toInt(), observedId)
                assertEquals(admittedRoot, boundRoot.canonicalFile)
                assertTrue(
                    preferences.edit().putString("cache_path", "").commit(),
                )

                // Model a carrier in the fallback namespace that admission
                // did not inspect.  A later plan/staging step must still use
                // the already admitted root rather than silently switching.
                val fallbackRoot = File(FileUtil.getCachePath(context)).canonicalFile
                val carrierRoot = File(fallbackRoot, "TERMINAL/stale-$itemId").apply { mkdirs() }
                val staleToken = "$itemId-stale"
                TerminalCacheOwnership.ensureMarker(carrierRoot, staleToken)
                val remainder = File(carrierRoot, "remainder.bin").apply { writeText("stale") }
                assertTrue(TerminalCacheOwnership.recordArtifacts(carrierRoot, listOf(remainder.absolutePath)))
                assertTrue(
                    TerminalCacheOwnership.recordRecoveryCarrier(
                        directory = carrierRoot,
                        taskToken = staleToken,
                        subjectId = itemId.toString(),
                    ),
                )
                assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(carrierRoot, staleToken))
                observedFallbackRoot.set(fallbackRoot)
            }
            TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = { observedId, output ->
                assertEquals(itemId.toInt(), observedId)
                observedStagingRoot.set(output?.canonicalFile)
            }
            TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = { observedId, _ ->
                assertEquals(itemId.toInt(), observedId)
                ""
            }
            request = OneTimeWorkRequestBuilder<TerminalDownloadWorker>()
                .setInputData(workDataOf("id" to itemId.toInt(), "command" to command))
                .addTag("terminal-bound-cache-root")
                .build()
            WorkManager.getInstance(context).enqueue(checkNotNull(request))
            val info = awaitFinished(checkNotNull(request))

            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            val staging = checkNotNull(observedStagingRoot.get())
            assertEquals(
                File(admittedRoot, "TERMINAL").canonicalFile,
                staging.parentFile?.canonicalFile,
            )
            val fallbackRoot = checkNotNull(observedFallbackRoot.get())
            assertTrue(File(fallbackRoot, "TERMINAL/stale-$itemId").isDirectory)
            assertFalse(staging.path.startsWith(fallbackRoot.path))
            assertNull(db.terminalDao.getTerminalById(itemId))
        } finally {
            request?.let {
                WorkManager.getInstance(context).cancelWorkById(it.id).result.get(10, TimeUnit.SECONDS)
            }
            db.terminalDao.delete(itemId)
            TerminalExecutionRecovery.clearForTesting(
                File(context.filesDir, "terminal-execution-recovery"),
                itemId,
            )
            observedFallbackRoot.get()?.let { fallbackRoot ->
                File(fallbackRoot, "TERMINAL/stale-$itemId").deleteRecursively()
            }
            admittedRoot.deleteRecursively()
            preferences.edit().apply {
                if (hadCachePath) putString("cache_path", previousCachePath) else remove("cache_path")
                if (hadCacheDownloads) putBoolean("cache_downloads", previousCacheDownloads)
                else remove("cache_downloads")
            }.commit()
        }
    }

    private suspend fun awaitFinished(request: OneTimeWorkRequest): WorkInfo = withContext(Dispatchers.IO) {
        repeat(240) {
            val info = runCatching {
                WorkManager.getInstance(context).getWorkInfoById(request.id).get(1, TimeUnit.SECONDS)
            }.getOrNull()
            if (info?.state?.isFinished == true) return@withContext checkNotNull(info)
            Thread.sleep(250L)
        }
        error("Timed out waiting for TerminalDownloadWorker ${request.id}")
    }
}
