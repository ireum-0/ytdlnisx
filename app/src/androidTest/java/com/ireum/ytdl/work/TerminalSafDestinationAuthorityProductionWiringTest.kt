package com.ireum.ytdl.work

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.TerminalItem
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.database.viewmodel.TerminalViewModel
import com.ireum.ytdl.util.terminal.TerminalCommandPlan
import com.ireum.ytdl.util.terminal.TerminalCommandPlanFactory
import com.ireum.ytdl.util.terminal.TerminalProviderDestinationOption
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production-wiring coverage for Terminal SAF destination authority.
 *
 * A persisted `content://` tree grant proves app/provider writability, never
 * native yt-dlp filesystem writability.  These cases drive the real
 * [TerminalCommandPlanFactory] over real preferences and the real durable
 * Terminal dispatch owner, so they observe the exact plan and the exact
 * durable identity that [TerminalDownloadWorker] later executes.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSafDestinationAuthorityProductionWiringTest {
    private lateinit var context: Application
    private lateinit var database: DBManager
    private lateinit var preferences: android.content.SharedPreferences
    private var previousCommandPath: String? = null
    private var hadCommandPath = false
    private var previousCacheDownloads: Boolean? = null

    private val providerTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ACommand"
    private val otherProviderTreeUri =
        "content://com.android.externalstorage.documents/tree/primary%3AOtherCommand"
    private val rawDestination = File(
        ApplicationProvider.getApplicationContext<Application>().filesDir,
        "terminal-raw-output",
    ).absolutePath

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, _ ->
            throw UnsupportedOperationException("enqueue must not be reached")
        }
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        hadCommandPath = preferences.contains("command_path")
        previousCommandPath = preferences.getString("command_path", null)
        previousCacheDownloads =
            if (preferences.contains("cache_downloads")) preferences.getBoolean("cache_downloads", true) else null
        File(rawDestination).mkdirs()
    }

    @After
    fun tearDown() {
        WorkManagerHandoffRecovery.clearForTesting()
        val editor = preferences.edit()
        if (hadCommandPath) editor.putString("command_path", previousCommandPath) else editor.remove("command_path")
        if (previousCacheDownloads == null) editor.remove("cache_downloads") else editor.putBoolean("cache_downloads", previousCacheDownloads!!)
        editor.commit()
        File(rawDestination).deleteRecursively()
        if (::database.isInitialized) database.close()
    }

    private fun plan(command: String, taskId: String = "task-1"): TerminalCommandPlan {
        val staging = File(context.cacheDir, "terminal-saf/$taskId")
        return TerminalCommandPlanFactory.create(
            context = context,
            preferences = preferences,
            command = command,
            taskId = taskId,
            cacheRoot = File(context.cacheDir, "terminal-saf").canonicalFile.apply { mkdirs() },
        ).also { staging.deleteRecursively() }
    }

    private fun setConfiguredProvider(uri: String) {
        preferences.edit().putString("command_path", uri).commit()
    }

    private fun assertNoReconstructedProviderPath(plan: TerminalCommandPlan) {
        val nativeValues = plan.requestOptions.map { it.value }
        assertFalse(
            "a reconstructed provider path must never reach native output: $nativeValues",
            nativeValues.any { it.contains("/storage/") || it.contains("emulated") },
        )
        assertFalse(plan.sanitizedConfig.contains("emulated"))
    }

    private fun assertStagesToAppOwnedRoot(plan: TerminalCommandPlan) {
        assertTrue("a provider destination must stage", plan.usesAppCache)
        val nativePath = plan.requestOptions.single { it.name == "-P" }.value
        assertTrue(
            "native output must be the app staging root, was $nativePath",
            nativePath.startsWith(File(context.cacheDir, "terminal-saf").canonicalFile.absolutePath),
        )
        // Output provenance stays active while output is staged.
        assertEquals(
            File(nativePath, ".ytdlnisx-terminal-output.txt").absolutePath,
            plan.outputAuthorityMarkerPath,
        )
    }

    /** A. Configured provider destination with caching enabled. */
    @Test
    fun configuredProviderDestinationWithCacheEnabledStagesAndKeepsExactUri() {
        setConfiguredProvider(providerTreeUri)
        preferences.edit().putBoolean("cache_downloads", true).commit()

        val plan = plan("https://example.com/video-a")

        assertStagesToAppOwnedRoot(plan)
        assertNoReconstructedProviderPath(plan)
        // The exact persisted URI stays the final publication destination.
        assertEquals(providerTreeUri, plan.downloadLocation)
    }

    /**
     * B. Configured provider destination with caching disabled.
     *
     * Provider writability must not enable direct native output, which is the
     * exact condition that previously emitted a reconstructed /storage path.
     */
    @Test
    fun configuredProviderDestinationWithCacheDisabledStillStages() {
        setConfiguredProvider(providerTreeUri)
        preferences.edit().putBoolean("cache_downloads", false).commit()

        val plan = plan("https://example.com/video-b")

        assertStagesToAppOwnedRoot(plan)
        assertNoReconstructedProviderPath(plan)
        assertEquals(providerTreeUri, plan.downloadLocation)
    }

    /** C. Folder-picked provider destination with caching enabled. */
    @Test
    fun folderPickedProviderDestinationWithCacheEnabledStages() {
        preferences.edit()
            .putString("command_path", rawDestination)
            .putBoolean("cache_downloads", true)
            .commit()

        val plan = plan(
            TerminalProviderDestinationOption.render(providerTreeUri) +
                " https://example.com/video-c",
        )

        assertStagesToAppOwnedRoot(plan)
        assertNoReconstructedProviderPath(plan)
        assertEquals(providerTreeUri, plan.downloadLocation)
        assertFalse(plan.sanitizedConfig.contains(TerminalProviderDestinationOption.OPTION))
    }

    /**
     * D. Folder-picked provider destination with caching disabled survives
     * reconstruction of the Terminal intent from its durable command.
     */
    @Test
    fun folderPickedProviderDestinationSurvivesIntentReconstruction() {
        preferences.edit()
            .putString("command_path", rawDestination)
            .putBoolean("cache_downloads", false)
            .commit()
        val durableCommand = TerminalProviderDestinationOption.render(providerTreeUri) +
            " https://example.com/video-d"

        // A reconstruction after process death replans from the stored command
        // only; no Fragment or process-local state is involved.
        val first = plan(durableCommand, taskId = "task-d")
        val reconstructed = plan(durableCommand, taskId = "task-d")

        assertStagesToAppOwnedRoot(first)
        assertStagesToAppOwnedRoot(reconstructed)
        assertNoReconstructedProviderPath(reconstructed)
        assertEquals(providerTreeUri, first.downloadLocation)
        assertEquals(providerTreeUri, reconstructed.downloadLocation)
    }

    /** E. An independently writable raw destination keeps direct native output. */
    @Test
    fun writableRawConfiguredDestinationKeepsDirectNativeOutput() {
        preferences.edit()
            .putString("command_path", rawDestination)
            .putBoolean("cache_downloads", false)
            .commit()

        val plan = plan("https://example.com/video-e")

        assertFalse("a valid raw path must not be routed through SAF", plan.usesAppCache)
        assertEquals(rawDestination, plan.requestOptions.single { it.name == "-P" }.value)
        assertEquals(rawDestination, plan.downloadLocation)
        assertEquals(null, plan.outputAuthorityMarkerPath)
    }

    /**
     * F. A manually authored native -P keeps the validated direct-native
     * contract and is not confused with Folder-picker provider metadata.
     */
    @Test
    fun manualAuthoredRawPathKeepsDirectNativeContract() {
        setConfiguredProvider(providerTreeUri)
        preferences.edit().putBoolean("cache_downloads", true).commit()
        val expected = File(rawDestination).canonicalFile

        val authored = plan("-P $rawDestination https://example.com/video-f")
        assertRawDestinationHonored(authored, expected)

        // An authored native path outranks both a configured provider default
        // and a Folder-picked provider selection, so the raw destination is
        // honored rather than silently redirected to the provider.
        val withSelection = plan(
            TerminalProviderDestinationOption.render(otherProviderTreeUri) +
                " -P $rawDestination https://example.com/video-f",
        )
        assertRawDestinationHonored(withSelection, expected)
        assertFalse(withSelection.sanitizedConfig.contains(TerminalProviderDestinationOption.OPTION))
    }

    /**
     * The authored native destination stays the direct output authority.  The
     * shared path parser reports the canonical spelling of that path, so the
     * assertion compares canonical files rather than the literal argument.
     */
    private fun assertRawDestinationHonored(plan: TerminalCommandPlan, expected: File) {
        assertFalse("an authored raw path must stay direct", plan.usesAppCache)
        assertTrue(plan.requestOptions.none { it.name == "-P" })
        assertFalse(
            "an authored raw path must not be redirected to a provider",
            plan.downloadLocation.startsWith("content://"),
        )
        assertEquals(expected, File(plan.downloadLocation).canonicalFile)
        assertNoReconstructedProviderPath(plan)
    }

    /**
     * G. The provider destination is part of the durable Terminal identity, so
     * a stale request cannot adopt a newer selection.
     */
    @Test
    fun providerDestinationIsDurablyBoundAndStaleRequestsCannotAdoptANewerSelection(): Unit =
        runBlocking {
            val viewModel = TerminalViewModel(context, database, true)
            val boundCommand = TerminalProviderDestinationOption.render(providerTreeUri) +
                " https://example.com/video-g"
            val terminalId = viewModel.insert(TerminalItem(command = boundCommand))
            val carrier = requireNotNull(
                database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                    WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                    terminalId.toString(),
                ),
            )

            // The exact selection is durably bound to this Terminal identity.
            assertEquals(boundCommand, carrier.confirmedUrl)
            assertEquals(
                terminalId,
                database.terminalDao.getTerminalById(terminalId)?.id,
            )
            assertTrue(carrier.confirmedUrl.contains(providerTreeUri))

            // The current owner is accepted for its own exact command.
            assertTrue(
                WorkManagerHandoffRecovery.isCurrentTerminalDispatchRequest(
                    context = context,
                    terminalId = terminalId,
                    command = boundCommand,
                    handoffId = carrier.handoffId,
                    requestId = carrier.requestId,
                    generationId = carrier.generationId,
                    boundary = carrier.boundary,
                    commandFingerprint = carrier.configFingerprint,
                    workRequestId = carrier.requestId,
                ),
            )

            // A request carrying a different provider selection for the same
            // Terminal is not the current owner and must not be adopted.
            val newerCommand = TerminalProviderDestinationOption.render(otherProviderTreeUri) +
                " https://example.com/video-g"
            assertFalse(
                WorkManagerHandoffRecovery.isCurrentTerminalDispatchRequest(
                    context = context,
                    terminalId = terminalId,
                    command = newerCommand,
                    handoffId = carrier.handoffId,
                    requestId = carrier.requestId,
                    generationId = carrier.generationId,
                    boundary = carrier.boundary,
                    commandFingerprint = carrier.configFingerprint,
                    workRequestId = carrier.requestId,
                ),
            )
            // A fabricated fingerprint for the newer selection is refused too.
            assertFalse(
                WorkManagerHandoffRecovery.isCurrentTerminalDispatchRequest(
                    context = context,
                    terminalId = terminalId,
                    command = newerCommand,
                    handoffId = carrier.handoffId,
                    requestId = carrier.requestId,
                    generationId = carrier.generationId,
                    boundary = carrier.boundary,
                    commandFingerprint = UUID.randomUUID().toString().replace("-", ""),
                    workRequestId = carrier.requestId,
                ),
            )
        }

    /**
     * H. Provider publication composition.
     *
     * A provider plan stages into the app-owned root and keeps the exact URI as
     * `TerminalDownloadWorker`'s `moveFile(destDir = ...)` destination, which is
     * the input that selects the provider-aware publication path.  The move
     * itself is exercised by the existing publication/recovery suites.
     */
    @Test
    fun providerPlanPublishesFromStagingToTheExactProviderUri() {
        setConfiguredProvider(providerTreeUri)
        preferences.edit().putBoolean("cache_downloads", false).commit()

        val plan = plan("https://example.com/video-h")

        assertStagesToAppOwnedRoot(plan)
        // The publication destination is exactly what the worker passes to the
        // provider-aware move, and it is the original grant, not a pathname.
        assertEquals(providerTreeUri, plan.downloadLocation)
        assertTrue(plan.downloadLocation.startsWith("content://"))
    }
}
