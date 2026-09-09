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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    }

    @After
    fun tearDown() {
        TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpResponseForTesting = null
        TerminalDownloadWorkerEffectTestHooks.ytdlpSuccessWithOutputDirectoryForTesting = null
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
