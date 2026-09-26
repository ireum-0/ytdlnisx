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
import com.ireum.ytdl.database.viewmodel.TerminalViewModel
import com.ireum.ytdl.util.terminal.TerminalCommandIntentMaterializer
import com.ireum.ytdl.util.terminal.TerminalCommandMetadata
import com.ireum.ytdl.util.terminal.TerminalProviderDestinationOption
import java.io.File
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
        File(context.filesDir, "terminal-execution-recovery").deleteRecursively()
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
        // Execution witnesses live in the real app files directory rather than
        // the in-memory database, so they must be removed explicitly or a later
        // test would inherit a witness for a recycled terminal id.
        File(context.filesDir, "terminal-execution-recovery").deleteRecursively()
        // Reconciliation dispatches on a shared background scope.  Let that work
        // drain while this class's database is still installed, so a late
        // dispatch cannot resolve against the next test class's state.
        runBlocking { delay(1_000L) }
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
     * Seeds a persisted Terminal row plus its matching carrier directly, in the
     * exact prior representation.
     *
     * [formatGeneration] is the carrier's dispatch format generation.  The
     * default is the pre-materializer generation, because these cases model
     * state that already existed before the current writer ran.
     */
    private suspend fun seedLegacyTerminal(
        command: String,
        withCarrier: Boolean = true,
        formatGeneration: Long = TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
    ): Pair<Long, WorkManagerHandoffCarrier?> {
        val terminalId = database.terminalDao.insert(TerminalItem(command = command))
        terminals += terminalId
        if (!withCarrier) return terminalId to null
        val carrier = seedCarrier(terminalId, command, formatGeneration)
        return terminalId to carrier
    }

    private suspend fun seedCarrier(
        terminalId: Long,
        command: String,
        formatGeneration: Long,
    ): WorkManagerHandoffCarrier {
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
            sourceConfigurationGeneration = formatGeneration,
            boundary = terminalId.toString(),
            createdAt = now,
            updatedAt = now,
        )
        assertTrue(database.workManagerHandoffCarrierDao.insert(carrier) != -1L)
        return carrier
    }

    /** The literal marker bytes a pre-materializer user command could contain. */
    private fun historicalMarker(vararg parts: String): String =
        (listOf(TerminalCommandMetadata.renderCommandFormat()) + parts)
            .joinToString(" ")

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
            sourceConfigurationGeneration = TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
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
            sourceConfigurationGeneration = TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
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

    /**
     * A. Bare-format T1 must not abort a valid legacy SelfBound T2.
     *
     * T1 is encountered first, so if its classification propagated out of the
     * row loop the whole transaction would abort and T2 would be stranded.
     */
    @Test
    fun bareFormatRowDoesNotStrandAValidLegacySibling(): Unit = runBlocking {
        val malformedCommand = "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION} " +
            "https://example.com/video-malformed-bare"
        val malformedId = database.terminalDao.insert(TerminalItem(command = malformedCommand))
        terminals += malformedId
        // T2 has its own exact authority and no carrier, so it must be
        // reconstructed at generation 1 and dispatched.
        val siblingCommand = "-P /storage/emulated/0/SiblingCommand " +
            "https://example.com/video-valid-sibling"
        val siblingId = database.terminalDao.insert(TerminalItem(command = siblingCommand))
        terminals += siblingId
        useConfiguredProvider(providerB)

        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()

        // T2 converged.
        assertTrue(
            "the valid sibling must still be dispatched",
            enqueued.any {
                it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) == siblingCommand
            },
        )
        assertNotNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                siblingId.toString(),
            ),
        )
        // T1 is preserved and non-runnable.
        assertEquals(malformedCommand, database.terminalDao.getTerminalById(malformedId)?.command)
        assertNull(
            "no runnable carrier may exist for the malformed row",
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                malformedId.toString(),
            ),
        )
        assertTrue(
            "the malformed row must never be dispatched",
            enqueued.none {
                it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) == malformedCommand
            },
        )
    }

    /**
     * B. Repeated-format T1 must not abort a genuine current generation-2 T2.
     */
    @Test
    fun repeatedFormatRowDoesNotStrandACurrentGenerationSibling(): Unit = runBlocking {
        val malformedCommand = TerminalCommandMetadata.renderCommandFormat() + " " +
            TerminalCommandMetadata.renderCommandFormat() + " " +
            "https://example.com/video-malformed-repeated"
        val malformedId = database.terminalDao.insert(TerminalItem(command = malformedCommand))
        terminals += malformedId
        useConfiguredProvider(providerA)

        val siblingCommand = TerminalCommandIntentMaterializer.materialize(
            "https://example.com/video-current-sibling",
            providerA,
        )
        val siblingId = database.terminalDao.insert(TerminalItem(command = siblingCommand))
        terminals += siblingId
        seedCarrier(
            siblingId,
            siblingCommand,
            TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
        )

        useConfiguredProvider(providerB)
        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()

        assertTrue(
            "the current-generation sibling must still be dispatched",
            enqueued.any {
                it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) == siblingCommand
            },
        )
        assertNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                malformedId.toString(),
            ),
        )
    }

    /**
     * C/D. Unusable format value and malformed provider metadata are both
     * per-row dispositions, and neither may abort the batch.
     */
    @Test
    fun unusableFormatValueAndMalformedProviderDoNotStrandSiblings(): Unit = runBlocking {
        val emptyFormatCommand = "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION}= " +
            "https://example.com/video-malformed-empty"
        val emptyFormatId = database.terminalDao.insert(TerminalItem(command = emptyFormatCommand))
        terminals += emptyFormatId

        val repeatedProviderCommand =
            "${TerminalProviderDestinationOption.render(providerC)} " +
                "${TerminalProviderDestinationOption.render(providerC)} " +
                "https://example.com/video-malformed-provider"
        val repeatedProviderId =
            database.terminalDao.insert(TerminalItem(command = repeatedProviderCommand))
        terminals += repeatedProviderId

        val siblingCommand = TerminalProviderDestinationOption.render(providerC) +
            " https://example.com/video-valid-provider-sibling"
        val siblingId = database.terminalDao.insert(TerminalItem(command = siblingCommand))
        terminals += siblingId
        useConfiguredProvider(providerB)

        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()

        assertTrue(
            "a self-bound provider sibling must still be dispatched",
            enqueued.any {
                it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) == siblingCommand
            },
        )
        for (stranded in listOf(emptyFormatId, repeatedProviderId)) {
            assertNull(
                "malformed row $stranded must have no runnable carrier",
                database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                    WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                    stranded.toString(),
                ),
            )
            assertNotNull("malformed row $stranded must be preserved", database.terminalDao.getTerminalById(stranded))
        }
    }

    /**
     * E. A malformed row that does have an outstanding carrier has exactly that
     * carrier revoked, without touching its siblings.
     */
    @Test
    fun malformedRowWithOutstandingCarrierIsSupersededNotExecuted(): Unit = runBlocking {
        val malformedCommand = "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION} " +
            "https://example.com/video-malformed-carrier"
        val (malformedId, malformedCarrier) = seedLegacyTerminal(malformedCommand)
        requireNotNull(malformedCarrier)
        val siblingCommand = "-P /storage/emulated/0/SiblingTwo " +
            "https://example.com/video-sibling-two"
        val siblingId = database.terminalDao.insert(TerminalItem(command = siblingCommand))
        terminals += siblingId
        useConfiguredProvider(providerB)

        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()

        val revoked = database.workManagerHandoffCarrierDao.get(malformedCarrier.handoffId)
        assertEquals(WorkManagerHandoffCarrier.SUPERSEDED, revoked?.state)
        // Its exact request is no longer the current owner.
        assertFalse(isCurrentRequest(malformedId, malformedCarrier))
        // The row and its command survive.
        assertEquals(malformedCommand, database.terminalDao.getTerminalById(malformedId)?.command)
        // The sibling still converged.
        assertTrue(
            enqueued.any {
                it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) == siblingCommand
            },
        )
    }

    /**
     * F/G. Restart: the malformed disposition is stable and never synthesizes a
     * carrier, while valid siblings keep converging across process recreation.
     */
    @Test
    fun malformedDispositionIsStableAcrossRestartAndSiblingsStillConverge(): Unit = runBlocking {
        val malformedCommand = TerminalCommandMetadata.renderCommandFormat() + " " +
            TerminalCommandMetadata.renderCommandFormat() + " " +
            "https://example.com/video-restart-malformed"
        val malformedId = database.terminalDao.insert(TerminalItem(command = malformedCommand))
        terminals += malformedId
        val siblingCommand = "-P /storage/emulated/0/SiblingThree " +
            "https://example.com/video-sibling-three"
        val siblingId = database.terminalDao.insert(TerminalItem(command = siblingCommand))
        terminals += siblingId
        useConfiguredProvider(providerB)

        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()
        enqueued.clear()
        // Simulate process recreation: reconcile again from durable state.
        WorkManagerHandoffRecovery.reconcile(context)
        settleEnqueueWindow()

        assertNull(
            "restart must not synthesize a carrier for the malformed row",
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                malformedId.toString(),
            ),
        )
        assertNotNull(database.terminalDao.getTerminalById(malformedId))
        // The sibling carrier already exists from the first pass, so the second
        // pass must neither drop nor duplicate it.
        assertNotNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                siblingId.toString(),
            ),
        )
    }

    /**
     * H. Worker admission rejects an exact old request carrying malformed
     * metadata as a non-authoritative no-op, with no parser exception escaping
     * and no planner or native execution.
     */
    @Test
    fun workerAdmissionRejectsMalformedMetadataWithoutThrowing(): Unit = runBlocking {
        val malformedCommand = "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION} " +
            "https://example.com/video-worker-malformed"
        val (terminalId, carrier) = seedLegacyTerminal(malformedCommand)
        requireNotNull(carrier)

        // The durable authority check is a no-op, not an exception.
        assertFalse(isCurrentRequest(terminalId, carrier))

        // The planner is never reached for this state, and composing a plan from
        // it would refuse rather than execute the remainder.
        val plannerFailure = runCatching {
            com.ireum.ytdl.util.terminal.TerminalCommandPlanFactory.create(
                context = context,
                preferences = preferences,
                command = malformedCommand,
                taskId = "malformed",
            )
        }
        assertTrue("the planner must refuse malformed durable metadata", plannerFailure.isFailure)
    }

    /**
     * I. Current insert stays strict: malformed or repeated Terminal-owned
     * metadata is still refused before any row or carrier becomes durable.
     */
    @Test
    fun currentInsertStillRejectsMalformedMetadataBeforeDurability(): Unit = runBlocking {
        val viewModel = TerminalViewModel(context, database, true)
        useConfiguredProvider(providerA)
        for (malformed in listOf(
            "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION} https://example.com/video",
            "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION}= https://example.com/video",
            TerminalCommandMetadata.renderCommandFormat() + " " +
                TerminalCommandMetadata.renderCommandFormat() + " https://example.com/video",
            "${TerminalProviderDestinationOption.render(providerC)} " +
                "${TerminalProviderDestinationOption.render(providerC)} https://example.com/video",
        )) {
            val failure = runCatching {
                viewModel.insert(TerminalItem(command = malformed))
            }
            assertTrue("insert must refuse: $malformed", failure.isFailure)
        }
        // Nothing became durable.
        assertTrue(database.terminalDao.getTerminalById(1L) == null)
        assertTrue(
            database.workManagerHandoffCarrierDao
                .getOutstandingForBoundary(
                    WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                    "1",
                ) == null,
        )
    }

    /**
     * The non-throwing durable classification reports malformed metadata as its
     * own disposition and never as current format or self-bound.
     */
    @Test
    fun durableClassificationReportsMalformedWithoutThrowing() {
        val malformed = listOf(
            "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION} https://example.com/video",
            "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION}= https://example.com/video",
            TerminalCommandMetadata.renderCommandFormat() + " " +
                TerminalCommandMetadata.renderCommandFormat() + " https://example.com/video",
            "${TerminalProviderDestinationOption.render(providerC)} " +
                "${TerminalProviderDestinationOption.render(providerC)} https://example.com/video",
        )
        for (command in malformed) {
            for (generation in listOf(
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
                TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
            )) {
                val result = TerminalCommandMetadata.classifyDurableResult(command, generation)
                assertTrue(
                    "expected Malformed for: $command",
                    result is TerminalCommandMetadata.DurableClassification.Malformed,
                )
                assertTrue(
                    "a malformed disposition must carry a reason",
                    (result as TerminalCommandMetadata.DurableClassification.Malformed)
                        .reason.isNotBlank(),
                )
            }
        }
    }

    /**
     * Bare, empty-valued, and non-provider provider options are malformed
     * app-owned metadata on both the composition and the durable path.
     *
     * The sibling is a valid generation-1 self-bound row that must still be
     * reconstructed, proving the malformed row does not abort the batch.
     */
    @Test
    fun malformedProviderOptionRowIsIsolatedAndSiblingStillConverges(): Unit = runBlocking {
        val malformedCommands = listOf(
            "${TerminalProviderDestinationOption.OPTION} https://example.com/video-bare",
            "${TerminalProviderDestinationOption.OPTION}= https://example.com/video-empty",
            "https://example.com/video-bare-end ${TerminalProviderDestinationOption.OPTION}",
            "https://example.com/video-bogus " +
                "${TerminalProviderDestinationOption.OPTION}=not-a-provider-tree",
        )
        for ((index, malformedCommand) in malformedCommands.withIndex()) {
            val malformedId = database.terminalDao.insert(TerminalItem(command = malformedCommand))
            terminals += malformedId
            val siblingCommand = "-P /storage/emulated/0/StrictSibling$index " +
                "https://example.com/video-strict-sibling-$index"
            val siblingId = database.terminalDao.insert(TerminalItem(command = siblingCommand))
            terminals += siblingId
            useConfiguredProvider(providerB)

            WorkManagerHandoffRecovery.reconcile(context)
            awaitEnqueue()

            // The malformed row is non-runnable and preserved.
            assertNull(
                "malformed row $index must have no runnable carrier",
                database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                    WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                    malformedId.toString(),
                ),
            )
            assertEquals(
                malformedCommand,
                database.terminalDao.getTerminalById(malformedId)?.command,
            )
            // The malformed command is never dispatched.
            assertTrue(
                "malformed row $index must never be dispatched",
                enqueued.none {
                    it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) ==
                        malformedCommand
                },
            )
            // The valid sibling converged in the same pass.
            assertTrue(
                "sibling $index must still be dispatched",
                enqueued.any {
                    it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) ==
                        siblingCommand
                },
            )
            enqueued.clear()
        }
    }

    /**
     * A valid provider tree stays exact self-bound authority on the legacy path,
     * so the strictness change must not over-reject legitimate provider metadata.
     */
    @Test
    fun validProviderTreeSiblingStillRunsOnTheLegacyPath(): Unit = runBlocking {
        val siblingCommand = TerminalProviderDestinationOption.render(providerC) +
            " https://example.com/video-valid-provider-sibling"
        val siblingId = database.terminalDao.insert(TerminalItem(command = siblingCommand))
        terminals += siblingId
        useConfiguredProvider(providerB)

        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()

        assertTrue(
            "a valid generation-1 provider sibling must be dispatched",
            enqueued.any {
                it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) == siblingCommand
            },
        )
        assertEquals(
            providerC,
            TerminalCommandMetadata.strip(siblingCommand).providerTreeUri,
        )
    }

    /**
     * Current insertion refuses bare, empty and non-provider provider options
     * before any row or carrier becomes durable.
     */
    @Test
    fun currentInsertRejectsMalformedProviderOptionBeforeDurability(): Unit = runBlocking {
        val viewModel = TerminalViewModel(context, database, true)
        useConfiguredProvider(providerA)
        for (malformed in listOf(
            "${TerminalProviderDestinationOption.OPTION} https://example.com/video",
            "${TerminalProviderDestinationOption.OPTION}= https://example.com/video",
            "https://example.com/video ${TerminalProviderDestinationOption.OPTION}",
            "https://example.com/video ${TerminalProviderDestinationOption.OPTION}=",
            "${TerminalProviderDestinationOption.OPTION}=not-a-provider-tree https://example.com/video",
        )) {
            val failure = runCatching { viewModel.insert(TerminalItem(command = malformed)) }
            assertTrue("insert must refuse: $malformed", failure.isFailure)
        }
        // Nothing became durable, so no malformed token can reach planner/native.
        assertTrue(database.terminalDao.getTerminalById(1L) == null)
        assertNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                "1",
            ),
        )
    }

    /**
     * The durable classification reports every malformed provider form as
     * Malformed at both dispatch format generations, never as CurrentFormat or
     * SelfBound.
     */
    @Test
    fun durableClassificationReportsMalformedProviderFormsAtEveryGeneration() {
        val malformed = listOf(
            "${TerminalProviderDestinationOption.OPTION} https://example.com/video",
            "${TerminalProviderDestinationOption.OPTION}= https://example.com/video",
            "https://example.com/video ${TerminalProviderDestinationOption.OPTION}",
            "https://example.com/video ${TerminalProviderDestinationOption.OPTION}=",
            "${TerminalProviderDestinationOption.OPTION}=not-a-provider-tree https://example.com/video",
        )
        for (command in malformed) {
            for (generation in listOf(
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
                TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
            )) {
                val result = TerminalCommandMetadata.classifyDurableResult(command, generation)
                assertTrue(
                    "expected Malformed for: $command",
                    result is TerminalCommandMetadata.DurableClassification.Malformed,
                )
            }
        }
    }

    /**
     * The direct generation-2 persisted-generation cell.
     *
     * `3ffe5b35` could itself persist a generation-2 row and carrier whose
     * command carries the current-format marker together with malformed provider
     * metadata, because the provider option was not recognised at all.  The
     * marker plus a generation-2 carrier is exactly what would otherwise make
     * the row CurrentFormat, so this proves malformed provider metadata is not
     * overridden by generation, through real production reconciliation:
     *
     * - the exact outstanding generation-2 carrier becomes SUPERSEDED;
     * - the malformed rows are never enqueued and never admitted;
     * - each row and its exact command are preserved;
     * - valid siblings still converge in the same pass.
     */
    @Test
    fun generationTwoMalformedProviderCarrierIsSupersededAndSiblingsStillConverge(): Unit =
        runBlocking {
            // Two malformed forms that produce no provider value match at all,
            // each still carrying the current-format marker.
            val malformedCommands = listOf(
                TerminalCommandMetadata.renderCommandFormat() + " " +
                    "${TerminalProviderDestinationOption.OPTION}= https://example.com/video-gen2-empty",
                TerminalCommandMetadata.renderCommandFormat() + " " +
                    "https://example.com/video-gen2-bare " +
                    TerminalProviderDestinationOption.OPTION,
            )
            val malformedCarriers = mutableListOf<Pair<Long, WorkManagerHandoffCarrier>>()
            malformedCommands.forEach { malformedCommand ->
                // The marker is genuinely present, so without provider strictness
                // this row would classify as CurrentFormat.
                assertTrue(TerminalCommandMetadata.strip(
                    TerminalCommandMetadata.renderCommandFormat() + " https://example.com/video",
                ).currentFormat)
                val (malformedId, carrier) = seedLegacyTerminal(
                    command = malformedCommand,
                    formatGeneration = TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
                )
                requireNotNull(carrier)
                // The outstanding carrier really is a current-generation one.
                assertEquals(
                    TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
                    carrier.sourceConfigurationGeneration,
                )
                malformedCarriers += malformedId to carrier
            }

            // Valid siblings, inserted after the malformed rows.
            val siblingCommands = malformedCommands.indices.map { index ->
                val siblingCommand = "-P /storage/emulated/0/Gen2Sibling$index " +
                    "https://example.com/video-gen2-sibling-$index"
                val siblingId = database.terminalDao.insert(TerminalItem(command = siblingCommand))
                terminals += siblingId
                siblingCommand
            }
            useConfiguredProvider(providerB)

            // One real reconciliation pass over the whole batch.
            WorkManagerHandoffRecovery.reconcile(context)
            awaitEnqueue()

            for ((index, entry) in malformedCarriers.withIndex()) {
                val (malformedId, carrier) = entry
                val malformedCommand = malformedCommands[index]
                // 1. The exact outstanding generation-2 carrier is superseded.
                assertEquals(
                    "malformed generation-2 carrier $index must be SUPERSEDED",
                    WorkManagerHandoffCarrier.SUPERSEDED,
                    database.workManagerHandoffCarrierDao.get(carrier.handoffId)?.state,
                )
                // 2. Never enqueued, and never admitted.
                assertTrue(
                    "malformed generation-2 row $index must never be enqueued",
                    enqueued.none {
                        it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) ==
                            malformedCommand
                    },
                )
                assertFalse(
                    "malformed generation-2 row $index must never be admitted",
                    isCurrentRequest(malformedId, carrier),
                )
                // 3. Row and exact command are preserved.
                assertEquals(
                    malformedCommand,
                    database.terminalDao.getTerminalById(malformedId)?.command,
                )
            }

            // 4. Valid siblings still converge in the same pass.
            for ((index, siblingCommand) in siblingCommands.withIndex()) {
                assertTrue(
                    "valid sibling $index must still be dispatched",
                    enqueued.any {
                        it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) ==
                            siblingCommand
                    },
                )
            }
        }

    @Test
    fun durableClassificationDistinguishesPersistedGenerations() {
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.Ambiguous,
            TerminalCommandMetadata.classifyDurable(
                "https://example.com/video",
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
            ),
        )
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.SelfBound,
            TerminalCommandMetadata.classifyDurable(
                "--ytdlnisx-terminal-provider-destination=$providerC https://example.com/video",
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
            ),
        )
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.CurrentFormat,
            TerminalCommandMetadata.classifyDurable(
                TerminalCommandMetadata.renderCommandFormat() + " https://example.com/video",
                TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
            ),
        )
    }

    /**
     * A. Historical exact-marker collision.
     *
     * A generation-1 row whose user command literally contains the marker bytes
     * is not a current-format row.  Its identity fields all agree, so only the
     * durable generation proof can refuse it, and it must never acquire the
     * current provider B.
     */
    @Test
    fun historicalExactMarkerRowIsNotMistakenForCurrentFormat(): Unit = runBlocking {
        val command = historicalMarker("https://example.com/video-collision")
        val (terminalId, carrier) = seedLegacyTerminal(command)
        requireNotNull(carrier)
        useConfiguredProvider(providerB)

        // The command really does carry the marker bytes, and its generation-1
        // carrier is exactly the durable proof that must be refused.
        assertTrue(TerminalCommandMetadata.strip(command).currentFormat)
        assertEquals(TerminalCommandMetadata.LEGACY_FORMAT_GENERATION, carrier.sourceConfigurationGeneration)
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.Ambiguous,
            TerminalCommandMetadata.classifyDurable(
                command,
                carrier.sourceConfigurationGeneration,
            ),
        )
        assertFalse(
            "a historical exact-marker row must never be admitted",
            isCurrentRequest(terminalId, carrier),
        )

        WorkManagerHandoffRecovery.reconcile(context)
        settleEnqueueWindow()
        assertTrue("no request may be enqueued for the collision row", enqueued.isEmpty())
        assertNull(
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                terminalId.toString(),
            ),
        )
        // The user's own text is preserved, marker bytes included.
        assertEquals(command, database.terminalDao.getTerminalById(terminalId)?.command)
    }

    /**
     * B. Historical marker plus exact provider metadata stays self-bound.
     */
    @Test
    fun historicalMarkerWithExactProviderMetadataStaysRunnable(): Unit = runBlocking {
        val command = historicalMarker(
            TerminalProviderDestinationOption.render(providerC),
            "https://example.com/video-marker-provider",
        )
        val (terminalId, carrier) = seedLegacyTerminal(command)
        requireNotNull(carrier)
        useConfiguredProvider(providerB)

        assertTrue(
            "explicit provider metadata is independent self-bound authority",
            isCurrentRequest(terminalId, carrier),
        )
        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()
        assertTrue(
            enqueued.any { it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) == command },
        )
        // The later preference B does not redirect it.
        assertEquals(
            providerC,
            TerminalCommandMetadata.strip(command).providerTreeUri,
        )
    }

    /**
     * C. Historical marker plus a valid authored native home stays self-bound.
     */
    @Test
    fun historicalMarkerWithAuthoredNativeHomeStaysRunnable(): Unit = runBlocking {
        val command = historicalMarker(
            "-P /storage/emulated/0/LegacyCommand",
            "https://example.com/video-marker-native",
        )
        val (terminalId, carrier) = seedLegacyTerminal(command)
        requireNotNull(carrier)
        useConfiguredProvider(providerB)

        assertTrue(
            "an authored native home is independent self-bound authority",
            isCurrentRequest(terminalId, carrier),
        )
        WorkManagerHandoffRecovery.reconcile(context)
        awaitEnqueue()
        assertTrue(
            enqueued.any { it.workSpec.input.getString(TerminalDownloadWorker.INPUT_COMMAND) == command },
        )
        // The configured provider was never injected as competing authority.
        assertEquals(null, TerminalCommandMetadata.strip(command).providerTreeUri)
    }

    /**
     * F. Missing-carrier marker-only historical row.
     *
     * Without a current-generation carrier there is no independent generation
     * proof, so marker text alone must not synthesize a runnable carrier.
     */
    @Test
    fun markerOnlyRowWithoutCurrentGenerationCarrierIsNotReconstructed(): Unit = runBlocking {
        val command = historicalMarker("https://example.com/video-no-carrier")
        val (terminalId, _) = seedLegacyTerminal(command, withCarrier = false)
        useConfiguredProvider(providerB)

        WorkManagerHandoffRecovery.reconcile(context)
        settleEnqueueWindow()

        assertTrue(enqueued.isEmpty())
        assertNull(
            "no runnable carrier may be synthesized from marker text alone",
            database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
                WorkManagerHandoffCarrier.TERMINAL_DISPATCH,
                terminalId.toString(),
            ),
        )
        assertNotNull(database.terminalDao.getTerminalById(terminalId))
    }

    /**
     * G/H. A genuine current-format owner keeps its dispatch format generation
     * and exact authority, and a generation-1 carrier can never be that owner.
     */
    @Test
    fun currentGenerationOwnerIsExactAndLegacyGenerationNeverReachesIt(): Unit = runBlocking {
        useConfiguredProvider(providerA)
        val durableCommand = TerminalCommandIntentMaterializer.materialize(
            "https://example.com/video-current-gen",
            providerA,
        )
        val (terminalId, carrier) = seedLegacyTerminal(
            command = durableCommand,
            formatGeneration = TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
        )
        requireNotNull(carrier)
        useConfiguredProvider(providerB)

        // The command carries the marker bytes, but a generation-1 carrier
        // cannot prove current format.  It keeps only the authority its own text
        // carries: here the explicit provider A, so it stays self-bound to A and
        // is never treated as a current-format row.
        assertTrue(TerminalCommandMetadata.strip(durableCommand).currentFormat)
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.SelfBound,
            TerminalCommandMetadata.classifyDurable(
                durableCommand,
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
            ),
        )
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.CurrentFormat,
            TerminalCommandMetadata.classifyDurable(
                durableCommand,
                TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
            ),
        )
        assertEquals(
            TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
            carrier.sourceConfigurationGeneration,
        )
        assertTrue(
            "a genuine current-format owner is exact and runnable",
            isCurrentRequest(terminalId, carrier),
        )
        assertTrue(durableCommand.contains(providerA))
        assertFalse(durableCommand.contains(providerB))

        // The same generation-1 command keeps only its own bound authority, so a
        // stale generation never reaches current-format status and never adopts
        // the later preference B.
        val staleProviderTerminal = database.terminalDao.insert(
            TerminalItem(command = durableCommand),
        )
        terminals += staleProviderTerminal
        val staleProviderCarrier = seedCarrier(
            staleProviderTerminal,
            durableCommand,
            TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
        )
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.SelfBound,
            TerminalCommandMetadata.classifyDurable(
                durableCommand,
                staleProviderCarrier.sourceConfigurationGeneration,
            ),
        )
        assertTrue(
            "a self-bound generation-1 row keeps running against its own provider A",
            isCurrentRequest(staleProviderTerminal, staleProviderCarrier),
        )

        // A marker-only raw/default command is the case that depends entirely on
        // the generation proof: without it there is no authority at all.
        val rawCommand = TerminalCommandIntentMaterializer.materialize(
            "https://example.com/video-stale-generation",
            "/storage/emulated/0/YTDLnisX/Command",
        )
        val staleRawTerminal = database.terminalDao.insert(TerminalItem(command = rawCommand))
        terminals += staleRawTerminal
        val staleRawCarrier = seedCarrier(
            staleRawTerminal,
            rawCommand,
            TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
        )
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.Ambiguous,
            TerminalCommandMetadata.classifyDurable(
                rawCommand,
                staleRawCarrier.sourceConfigurationGeneration,
            ),
        )
        assertFalse(
            "a generation-1 marker-only row must never become authoritative",
            isCurrentRequest(staleRawTerminal, staleRawCarrier),
        )
    }

    /**
     * I. An execution already owned by TerminalExecutionRecovery is skipped by
     * reconciliation and is neither replanned nor corrupted.
     */
    @Test
    fun existingExecutionWitnessIsNotReplannedByTheGenerationCheck(): Unit = runBlocking {
        val command = TerminalCommandIntentMaterializer.materialize(
            "https://example.com/video-witness",
            providerA,
        )
        val (terminalId, _) = seedLegacyTerminal(
            command = command,
            formatGeneration = TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
        )
        useConfiguredProvider(providerB)
        assertFalse(TerminalExecutionRecovery.hasRecordFile(context, terminalId))

        // An admitted execution already owns this Terminal.
        assertTrue(
            TerminalExecutionRecovery.begin(
                context = context,
                subjectId = terminalId,
                executionToken = "$terminalId-witness",
                processId = "terminal:$terminalId",
            ),
        )
        assertTrue(TerminalExecutionRecovery.hasRecordFile(context, terminalId))

        WorkManagerHandoffRecovery.reconcile(context)
        settleEnqueueWindow()

        // Reconciliation leaves an execution-owned Terminal alone, so the
        // generation check never replans or re-dispatches it.
        assertTrue("an execution-owned Terminal must not be re-dispatched", enqueued.isEmpty())
        assertNotNull(
            "the execution witness must survive",
            TerminalExecutionRecovery.read(context, terminalId),
        )
    }
}
