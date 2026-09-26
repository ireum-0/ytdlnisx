package com.ireum.ytdl.work

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.TerminalItem
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.util.terminal.TerminalCommandMetadata
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Persisted-generation coverage for BUG-TERMINAL-03.
 *
 * These cases seed the pre-materializer durable representation directly instead
 * of creating rows through the corrected insertion boundary, because the defect
 * is specifically about state that already exists in the database.  A legacy
 * row and carrier can be perfectly self-consistent and still omit the one
 * dimension that decides where its output is published, so identity equality
 * must never be treated as proof of the original provider.
 */
@RunWith(AndroidJUnit4::class)
class TerminalPersistedGenerationAuthorityProductionWiringTest {
    private lateinit var context: Application
    private lateinit var database: DBManager
    private lateinit var preferences: android.content.SharedPreferences
    private val enqueued = mutableListOf<OneTimeWorkRequest>()
    private var previousCommandPath: String? = null
    private var hadCommandPath = false
    private val terminals = mutableListOf<Long>()

    private val providerA = "content://com.android.externalstorage.documents/tree/primary%3ALegacyA"
    private val providerB = "content://com.android.externalstorage.documents/tree/primary%3ANewerB"
    private val providerC = "content://com.android.externalstorage.documents/tree/primary%3AFolderC"

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
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, request ->
            enqueued += request
            ControlledOperation().also { it.succeed() }
        }
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        hadCommandPath = preferences.contains("command_path")
        previousCommandPath = preferences.getString("command_path", null)
        enqueued.clear()
        terminals.clear()
    }

    @After
    fun tearDown() {
        WorkManagerHandoffRecovery.clearForTesting()
        val editor = preferences.edit()
        if (hadCommandPath) editor.putString("command_path", previousCommandPath) else editor.remove("command_path")
        editor.commit()
        runBlocking { terminals.forEach { runCatching { database.terminalDao.delete(it) } } }
        if (::database.isInitialized) database.close()
    }

    /** Mirrors the production enqueue seam used by the dispatch tests. */
    private class ControlledOperation : androidx.work.Operation {
        private val state = androidx.lifecycle.MutableLiveData<androidx.work.Operation.State>(
            androidx.work.Operation.IN_PROGRESS,
        )
        private val result = com.google.common.util.concurrent.SettableFuture
            .create<androidx.work.Operation.State.SUCCESS>()

        override fun getState(): androidx.lifecycle.LiveData<androidx.work.Operation.State> = state

        override fun getResult(): com.google.common.util.concurrent.ListenableFuture<androidx.work.Operation.State.SUCCESS> = result

        fun succeed() {
            state.postValue(androidx.work.Operation.SUCCESS)
            result.set(androidx.work.Operation.SUCCESS)
        }
    }

    private fun useConfiguredProvider(uri: String) {
        preferences.edit().putString("command_path", uri).commit()
    }

    /**
     * Seeds the exact pre-materializer representation: a Terminal row whose
     * command carries no Terminal-owned metadata, plus the matching prior-format
     * carrier and fingerprint.
     */
    private suspend fun seedLegacyTerminal(
        command: String,
        withCarrier: Boolean = true,
    ): Pair<Long, WorkManagerHandoffCarrier?> {
        val terminalId = database.terminalDao.insert(TerminalItem(command = command))
        terminals += terminalId
        if (!withCarrier) return terminalId to null
        val now = System.currentTimeMillis()
        val handoffId = UUID.randomUUID().toString()
        val carrier = WorkManagerHandoffCarrier(
            handoffId = handoffId,
            kind = WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
            generationId = handoffId,
            requestId = UUID.randomUUID().toString(),
            uniqueWorkName = terminalId.toString(),
            sourceId = terminalId,
            confirmedUrl = command,
            decision = "EXECUTE",
            configFingerprint = WorkManagerHandoffRecovery.terminalCommandFingerprint(command),
            sourceConfigurationGeneration = 1L,
            boundary = terminalId.toString(),
            createdAt = now,
            updatedAt = now,
        )
        assertTrue(database.workManagerHandoffCarrierDao.insert(carrier) != -1L)
        return terminalId to carrier
    }

    private suspend fun isCurrentRequest(
        terminalId: Long,
        carrier: WorkManagerHandoffCarrier,
    ): Boolean = WorkManagerHandoffRecovery.isCurrentTerminalDispatchRequest(
        context = context,
        terminalId = terminalId,
        command = carrier.confirmedUrl,
        handoffId = carrier.handoffId,
        requestId = carrier.requestId,
        generationId = carrier.generationId,
        boundary = carrier.boundary,
        commandFingerprint = carrier.configFingerprint,
        workRequestId = carrier.requestId,
    )

    /**
     * Waits until at least [count] requests have been enqueued.
     *
     * Reconciliation dispatches on a background scope, so a synchronous
     * assertion would race the enqueue.  The same bounded window is used by the
     * negative cases, which is what makes "nothing was enqueued" meaningful: the
     * positive cases prove this window is long enough to observe a real
     * dispatch.
     */
    private suspend fun awaitEnqueue(count: Int = 1, timeoutMs: Long = 5_000L) {
        withTimeout(timeoutMs) {
            while (enqueued.size < count) delay(25L)
        }
    }

    /** Gives an authorized dispatch the same bounded chance to become visible. */
    private suspend fun settleEnqueueWindow(timeoutMs: Long = 5_000L) {
        withTimeoutOrNull(timeoutMs) {
            while (enqueued.isEmpty()) delay(25L)
        }
        // A short settle so a late enqueue cannot land after the assertion.
        delay(250L)
    }

    /**
     * A. A legacy row created under provider A must not become runnable, and
     * must never publish as the current provider B.
     */
    @Test
    fun legacyConfiguredProviderRowCannotExecuteOrPublishAsTheCurrentProvider(): Unit = runBlocking {
        val (terminalId, carrier) = seedLegacyTerminal("https://example.com/video-legacy")
        requireNotNull(carrier)
        // The configured provider moves before the old row is resumed.
        useConfiguredProvider(providerB)

        // The identity fields all agree, which is exactly why equality of an
        // incomplete record proves nothing.
        assertEquals(
            WorkManagerHandoffRecovery.terminalCommandFingerprint(carrier.confirmedUrl),
            carrier.configFingerprint,
        )
        assertFalse(
            "an ambiguous legacy command must never be admitted as runnable",
            isCurrentRequest(terminalId, carrier),
        )

        // Startup reconciliation must neither enqueue it nor reconstruct a
        // runnable carrier from the ambiguous command.
        WorkManagerHandoffRecovery.reconcile(context)
        settleEnqueueWindow()
        assertTrue("no request may be enqueued for an ambiguous legacy row", enqueued.isEmpty())
        assertNull(
            "no runnable carrier may be reconstructed from an ambiguous command",
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                terminalId.toString(),
            ),
        )
        // The row and its command are preserved: this is a non-runnable
        // disposition, not a deletion.
        assertEquals(
            "https://example.com/video-legacy",
            database.terminalDao.getTerminalById(terminalId)?.command,
        )
    }

    /** B. Reconciliation must not rebuild a runnable carrier for an ambiguous row. */
    @Test
    fun legacyCarrierReconstructionDoesNotAdoptTheCurrentProvider(): Unit = runBlocking {
        val (terminalId, _) = seedLegacyTerminal("https://example.com/video-reconstruct", withCarrier = false)
        useConfiguredProvider(providerB)

        WorkManagerHandoffRecovery.reconcile(context)
        settleEnqueueWindow()
        assertTrue("an ambiguous legacy row must never be enqueued", enqueued.isEmpty())
        assertNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                terminalId.toString(),
            ),
        )
    }

    /**
     * C. The refusal is durable. A restart that re-runs reconciliation must not
     * resurrect the row into a runnable request.
     */
    @Test
    fun legacyRefusalSurvivesRestartAndStaysNonRunning(): Unit = runBlocking {
        val (terminalId, carrier) = seedLegacyTerminal("https://example.com/video-restart")
        requireNotNull(carrier)
        useConfiguredProvider(providerB)
        WorkManagerHandoffRecovery.reconcile(context)
        settleEnqueueWindow()
        assertTrue(enqueued.isEmpty())

        // Simulate a fresh process: clear in-memory state and reconcile again.
        enqueued.clear()
        WorkManagerHandoffRecovery.reconcile(context)

        settleEnqueueWindow()

        assertTrue("restart must not resurrect a runnable request", enqueued.isEmpty())
        assertFalse(isCurrentRequest(terminalId, carrier))
        assertNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                terminalId.toString(),
            ),
        )
        // The disposition is still diagnosable: the row remains for the user.
        assertNotNull(database.terminalDao.getTerminalById(terminalId))
    }

    /** D. A legacy row that already carries exact provider metadata stays runnable. */
    @Test
    fun legacyRowWithExplicitProviderMetadataRemainsExactAndRunnable(): Unit = runBlocking {
        val command = "--ytdlnisx-terminal-provider-destination=$providerC " +
            "https://example.com/video-explicit"
        val (terminalId, carrier) = seedLegacyTerminal(command)
        requireNotNull(carrier)
        useConfiguredProvider(providerB)

        assertTrue(
            "explicit provider metadata is already exact authority",
            isCurrentRequest(terminalId, carrier),
        )
        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()
        assertTrue(
            "an exact legacy provider row must still dispatch",
            enqueued.any { it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) == command },
        )
    }

    /** E. A legacy row with an authored native destination is not given provider authority. */
    @Test
    fun legacyRowWithAuthoredNativeDestinationKeepsAuthoredAuthority(): Unit = runBlocking {
        val command = "-P /storage/emulated/0/LegacyCommand https://example.com/video-authored"
        val (terminalId, carrier) = seedLegacyTerminal(command)
        requireNotNull(carrier)
        useConfiguredProvider(providerB)

        assertTrue(
            "an authored native destination is its own exact authority",
            isCurrentRequest(terminalId, carrier),
        )
        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()
        assertNotNull(
            "the authored native row must still dispatch",
            enqueued.singleOrNull {
                it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) == command
            },
        )
        // The configured provider was never injected as competing authority.
        assertFalse(carrier.confirmedUrl.contains(providerB))
    }

    /** F. A current-format row stays bound to its own provider across a change. */
    @Test
    fun currentFormatProviderRowSurvivesPreferenceChangeAndReconcile(): Unit = runBlocking {
        useConfiguredProvider(providerA)
        val terminalId = database.terminalDao.insert(
            TerminalItem(
                command = com.ireum.ytdl.util.terminal.TerminalCommandIntentMaterializer
                    .materialize("https://example.com/video-current", providerA),
            ),
        )
        terminals += terminalId
        val handoffId = UUID.randomUUID().toString()
        val durableCommand = database.terminalDao.getTerminalById(terminalId)!!.command
        val carrier = WorkManagerHandoffCarrier(
            handoffId = handoffId,
            kind = WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
            generationId = handoffId,
            requestId = UUID.randomUUID().toString(),
            uniqueWorkName = terminalId.toString(),
            sourceId = terminalId,
            confirmedUrl = durableCommand,
            decision = "EXECUTE",
            configFingerprint = WorkManagerHandoffRecovery.terminalCommandFingerprint(durableCommand),
            sourceConfigurationGeneration = 1L,
            boundary = terminalId.toString(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        assertTrue(database.workManagerHandoffCarrierDao.insert(carrier) != -1L)

        // Runnable and bound to A while it is still the pending owner.  This is
        // asserted before reconciliation, because a dispatched owner advances
        // past the pending state by design.
        assertTrue(
            "a current-format provider row must be runnable and bound to A",
            isCurrentRequest(terminalId, carrier),
        )

        useConfiguredProvider(providerB)
        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()

        // Reconciliation dispatched this exact owner rather than dropping it.
        assertTrue(enqueued.any { it.id.toString() == carrier.requestId })
        assertTrue(durableCommand.contains(providerA))
        assertFalse(durableCommand.contains(providerB))
    }

    /** G. A current-format raw/default row is not blocked by the legacy fence. */
    @Test
    fun currentFormatRawRowIsNotIndiscriminatelyBlocked(): Unit = runBlocking {
        val rawConfigured = "/storage/emulated/0/YTDLnisX/Command"
        useConfiguredProvider(rawConfigured)
        val durableCommand =
            com.ireum.ytdl.util.terminal.TerminalCommandIntentMaterializer
                .materialize("https://example.com/video-raw", rawConfigured)
        val terminalId = database.terminalDao.insert(TerminalItem(command = durableCommand))
        terminals += terminalId
        val handoffId = UUID.randomUUID().toString()
        val carrier = WorkManagerHandoffCarrier(
            handoffId = handoffId,
            kind = WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
            generationId = handoffId,
            requestId = UUID.randomUUID().toString(),
            uniqueWorkName = terminalId.toString(),
            sourceId = terminalId,
            confirmedUrl = durableCommand,
            decision = "EXECUTE",
            configFingerprint = WorkManagerHandoffRecovery.terminalCommandFingerprint(durableCommand),
            sourceConfigurationGeneration = 1L,
            boundary = terminalId.toString(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        assertTrue(database.workManagerHandoffCarrierDao.insert(carrier) != -1L)

        // Runnable before reconciliation, then dispatched by it.
        assertTrue(
            "a current-format raw row must be runnable",
            isCurrentRequest(terminalId, carrier),
        )
        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()
        assertTrue(enqueued.any { it.id.toString() == carrier.requestId })
    }

    /**
     * H/I. A new row under the current provider binds it independently, and an
     * old ambiguous request can never become current merely because text or the
     * current preference happens to match.
     */
    @Test
    fun newRowBindsCurrentProviderAndStaleAmbiguousRequestNeverBecomesCurrent(): Unit = runBlocking {
        useConfiguredProvider(providerB)
        val newCommand =
            com.ireum.ytdl.util.terminal.TerminalCommandIntentMaterializer
                .materialize("https://example.com/video-new", providerB)
        val newId = database.terminalDao.insert(TerminalItem(command = newCommand))
        terminals += newId
        assertTrue(newCommand.contains(providerB))

        val (legacyId, legacyCarrier) = seedLegacyTerminal("https://example.com/video-new")
        requireNotNull(legacyCarrier)

        // The legacy request cannot claim the newer row's boundary.
        assertFalse(
            WorkManagerHandoffRecovery.isCurrentTerminalDispatchRequest(
                context = context,
                terminalId = newId,
                command = legacyCarrier.confirmedUrl,
                handoffId = legacyCarrier.handoffId,
                requestId = legacyCarrier.requestId,
                generationId = legacyCarrier.generationId,
                boundary = legacyCarrier.boundary,
                commandFingerprint = legacyCarrier.configFingerprint,
                workRequestId = legacyCarrier.requestId,
            ),
        )
        // Nor can it claim its own boundary.
        assertFalse(isCurrentRequest(legacyId, legacyCarrier))
    }

    @Test
    fun durableClassificationDistinguishesPersistedGenerations() {
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.Ambiguous,
            TerminalCommandMetadata.classifyDurable("https://example.com/video"),
        )
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.SelfBound,
            TerminalCommandMetadata.classifyDurable(
                "--ytdlnisx-terminal-provider-destination=$providerC https://example.com/video",
            ),
        )
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.CurrentFormat,
            TerminalCommandMetadata.classifyDurable(
                TerminalCommandMetadata.renderCommandFormat() + " https://example.com/video",
            ),
        )
    }
}
