package com.ireum.ytdl.util

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.App
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.*
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpRuntimeAuthority
import com.ireum.ytdl.work.*
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real supervised native fixture: only the yt-dlp program is local/test-owned. */
internal class RuntimeAuthorityNativeFixture(private val context: Context) {
    val root = File(context.noBackupFilesDir, "updater04-${UUID.randomUUID()}")
    val binary = File(root, "yt-dlp")
    private val previous = YoutubeDLCompat.runtimeLayoutOverrideForTesting
    private val real = YoutubeDLCompat.runtimeLayout(context)
    private val original = """
import os, sys, time
root = '${root.absolutePath}'
identity = os.environ.get('YTDLNISX_PROCESS_ID', '')
def await_release(path):
    deadline = time.monotonic() + 120
    while os.path.exists(path):
        if time.monotonic() > deadline:
            raise RuntimeError('fixture release did not arrive')
        time.sleep(0.02)
if '--version' in sys.argv:
    print('fixture-v1')
    sys.exit(0)
if '--update-to' in sys.argv:
    with open(os.path.join(root, 'update-ready'), 'w') as out:
        out.write(identity)
    await_release(os.path.join(root, 'update-hold'))
    with open(sys.argv[0]) as source:
        replacement = source.read().replace('fixture-v1', 'fixture-v2')
    with open(sys.argv[0], 'w') as target:
        target.write(replacement)
    print('Updated yt-dlp to fixture-v2')
    sys.exit(0)
parts = identity.split(':')
if len(parts) < 3 or parts[0] != 'download':
    raise RuntimeError('expected real Download process identity')
with open(os.path.join(root, 'ready-' + parts[1]), 'w') as out:
    out.write(identity + '\nfixture-v1')
print('[download] Native runtime fixture entered', flush=True)
await_release(os.path.join(root, 'hold-' + parts[1]))
print('ERROR: native fixture completed without media output', file=sys.stderr)
sys.exit(1)
""".trimIndent()

    fun install() {
        assertTrue(root.mkdirs())
        assertTrue("installed bundled Python is required", real.pythonBinary.isFile)
        assertTrue("installed bundled QuickJS is required", real.quickJsBinary.isFile)
        binary.writeText(original)
        YoutubeDLCompat.runtimeLayoutOverrideForTesting = real.copy(ytdlpBinary = binary)
    }

    fun promote() {
        assertTrue("only a real mutation owner may replace the fixture", YtdlpRuntimeAuthority.mutationOwnedByCurrentThread())
        binary.writeText(original.replace("fixture-v1", "fixture-v2"))
    }

    fun invalidatePublication() {
        assertTrue(YtdlpRuntimeAuthority.mutationOwnedByCurrentThread())
        binary.writeText("this is not valid Python (\n")
    }

    fun ready(id: Long) = File(root, "ready-$id")
    fun hold(id: Long) { File(root, "hold-$id").writeText("owned") }
    fun release(id: Long) { File(root, "hold-$id").delete() }
    fun releaseAll() {
        root.listFiles()?.filter { it.name.startsWith("hold-") || it.name == "update-hold" }
            ?.forEach { it.delete() }
    }
    fun close(preserveRoot: Boolean = false) {
        releaseAll()
        YoutubeDLCompat.runtimeLayoutOverrideForTesting = previous
        if (!preserveRoot) root.deleteRecursively()
    }
}

private val runtimeAuthorityDownloadIds = AtomicLong(System.currentTimeMillis())
private val runtimeAuthorityIsolationFailure = AtomicReference<Throwable?>()

@RunWith(AndroidJUnit4::class)
class YtdlpRuntimeAuthorityProductionWiringTest {
    private lateinit var context: Context
    private lateinit var db: DBManager
    private lateinit var native: RuntimeAuthorityNativeFixture
    private lateinit var preferences: android.content.SharedPreferences
    private var originalValues = emptyMap<String, Any?>()
    private var ownsPreferences = false
    private val requests = ConcurrentHashMap<Long, UUID>()
    private class AttemptCleanup {
        val execution = AtomicReference<String?>()
        val observed = CountDownLatch(1)
    }
    private val cleanups = ConcurrentHashMap<Long, AttemptCleanup>()
    private val liveExecutions = ConcurrentHashMap<Long, String>()
    private val producerReconciliations = ConcurrentHashMap.newKeySet<Long>()
    private val gates = mutableListOf<CountDownLatch>()
    private val mutationGenerations = mutableListOf<Pair<String, String>>()
    private val cancelledTransports = ConcurrentHashMap<Long, DownloadItem>()
    private val keys = listOf("ytdlp_source", "ytdlp_source_label", "ytdlp_source_generation",
        "ytdlp_committed_source_generation", "ytdlp_committed_source", "ytdlp_committed_result",
        "ytdlp_pending_source_generation", "ytdlp_pending_source", "use_scheduler",
        "concurrent_downloads", "cache_downloads", "use_cookies", "log_downloads")

    // Run teardown outside JUnit's @After aggregation so its timeout cannot replace
    // the original native/authority failure. An incomplete isolation blocks later cases.
    @get:Rule
    val isolateRuntime = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                var primaryFailure: Throwable? = null
                try {
                    base.evaluate()
                } catch (failure: Throwable) {
                    primaryFailure = failure
                    throw failure
                } finally {
                    try {
                        tearDown()
                    } catch (cleanupFailure: Throwable) {
                        runtimeAuthorityIsolationFailure.compareAndSet(null, cleanupFailure)
                        if (primaryFailure == null) throw cleanupFailure
                        primaryFailure.addSuppressed(cleanupFailure)
                    }
                }
            }
        }
    }

    @Before
    fun setUp() = runBlocking {
        runtimeAuthorityIsolationFailure.get()?.let {
            throw AssertionError("Previous real-worker isolation did not complete; shared state is retained", it)
        }
        context = ApplicationProvider.getApplicationContext()
        awaitAppStartup()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalValues = keys.associateWith { key ->
            if (!preferences.contains(key)) null else when (key) {
                "use_scheduler", "cache_downloads", "use_cookies", "log_downloads" -> preferences.getBoolean(key, false)
                "concurrent_downloads" -> preferences.getInt(key, 1)
                "ytdlp_source_generation", "ytdlp_committed_source_generation", "ytdlp_pending_source_generation" -> preferences.getLong(key, 0)
                else -> preferences.getString(key, null)
            }
        }
        ownsPreferences = true
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        assertTrue(editor.putBoolean("use_scheduler", false).putInt("concurrent_downloads", 2)
            .putBoolean("cache_downloads", false).putBoolean("use_cookies", false)
            .putBoolean("log_downloads", true).commit())
        resetHooks()
        DownloadWorkerExecutionOwners.clearForTesting()
        DownloadWorkerProcessOwners.clearForTesting()
        db = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters()).allowMainThreadQueries().build()
        DownloadWorkerEffectTestHooks.dbManagerForTesting = db
        DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting = { id, execution ->
            cleanups[id]?.let { cleanup ->
                if (execution.isNotBlank()) {
                    cleanup.execution.compareAndSet(null, execution)
                    cleanup.observed.countDown()
                }
            }
        }
        native = RuntimeAuthorityNativeFixture(context)
        native.install()
    }

    private fun tearDown() = runBlocking {
        if (!ownsPreferences) return@runBlocking
        if (::db.isInitialized) capture("BEFORE_TEARDOWN")
        gates.forEach { it.countDown() }
        if (::native.isInitialized) native.releaseAll()
        // Only this test's exact generations may be retired. Preserve the
        // primary failure and fixture if real recovery/validation cannot converge.
        YoutubeDLCompat.processStarterOverrideForTesting = null
        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
        mutationGenerations.forEach { (identity, token) ->
            assertTrue("Exact mutation cleanup $identity/$token",
                YtdlpNativeProcessBarrier.recoverGeneration(identity, token))
        }
        if (!YtdlpNativeProcessBarrier.runtimeMutationDebtIsAbsent(context)) {
            withContext(Dispatchers.IO) { YtdlpRuntimeAuthority.withMutation(context) { } }
        }
        // Error/Queued and WorkManager cancellation acknowledgement are not cleanup.
        // Observe every exact attempt before canceling transports or retiring Room/hooks.
        requests.keys.forEach {
            if (cancelledTransports.containsKey(it)) awaitCancelledTransportIsolation(it) else awaitAttemptCleanup(it)
        }
        requests.keys.filterNot(cancelledTransports::containsKey).forEach { convergeProducerRecovery(it) }
        requests.values.forEach { request ->
            WorkManager.getInstance(context).cancelWorkById(request).result.get(10, TimeUnit.SECONDS)
            waitFor { WorkManager.getInstance(context).getWorkInfoById(request)
                .get(2, TimeUnit.SECONDS)?.state?.isFinished == true }
        }
        requests.keys.forEach {
            if (cancelledTransports.containsKey(it)) awaitCancelledTransportIsolation(it)
            else { convergeProducerRecovery(it); assertAttemptIsolated(it) }
        }
        if (::db.isInitialized) capture("ISOLATION_ESTABLISHED")
        val preserveFixture = if (requests.isEmpty()) false else {
            val producers = DownloadProducerRecovery.discover(context)
            assertTrue("Producer discovery must remain healthy before fixture retirement: $producers",
                producers is DownloadProducerRecovery.DiscoveryResult.Healthy)
            producers.records.any {
                requests.containsKey(it.downloadId) && it.phase == DownloadProducerRecovery.Phase.COMPLETE
            }
        }
        if (preserveFixture) emitDiagnostic("Updater04 phase=PRESERVE_COMPLETE_PRODUCER_FIXTURE root=${native.root}")
        resetHooks()
        if (::native.isInitialized) native.close(preserveRoot = preserveFixture)
        if (::db.isInitialized) db.close()
        if (!::preferences.isInitialized) return@runBlocking
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        originalValues.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            null -> Unit
        } }
        assertTrue("owned preferences must restore", editor.commit())
    }

    @Test
    fun liveRealDownloadExcludesManualMutationUntilExactQuiescence() = authorityTest {
        val id = enqueueConsumer()
        requireLive(id, "fixture-v1")
        val requested = latch()
        val entered = latch()
        val promotions = AtomicInteger()
        YtdlpRuntimeAuthority.mutationRequestedForTesting = { requested.countDown() }
        UpdateUtil.updaterForTesting = { _, _ -> entered.countDown(); promotions.incrementAndGet(); native.promote(); done() }
        val update = async(Dispatchers.IO) { UpdateUtil(context).updateYoutubeDL() }
        await(requested)
        assertFalse("live consumer must exclude destructive mutation", entered.await(300, TimeUnit.MILLISECONDS))
        requireLive(id, "fixture-v1")
        native.release(id)
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, withTimeout(30_000) { update.await() }.status)
        requireTerminal(id)
        assertEquals(1, promotions.get())
    }

    @Test
    fun startupIdleObservationCannotAuthorizeMutationPastLateRealDownload() = authorityTest {
        assertEquals(0, db.downloadDao.getDownloadsCountByStatus(listOf("Active", "Queued")))
        val preAdmission = latch()
        val allowAdmission = latch()
        val entered = latch()
        YtdlpRuntimeAuthority.mutationRequestedForTesting = { preAdmission.countDown(); await(allowAdmission) }
        UpdateUtil.updaterForTesting = { _, _ -> entered.countDown(); native.promote(); done() }
        val update = async(Dispatchers.IO) { UpdateUtil(context).updateOnStartup(true) }
        await(preAdmission)
        val id = enqueueConsumer()
        requireLive(id, "fixture-v1")
        allowAdmission.countDown()
        assertFalse(entered.await(300, TimeUnit.MILLISECONDS))
        native.release(id)
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, withTimeout(30_000) { update.await() }.status)
        requireTerminal(id)
    }

    @Test
    fun promotionExcludesRealDownloadLaunchUntilPromotedRuntimeIsUsable() = authorityTest {
        val mutationOwned = latch()
        val allowPromotion = latch()
        val consumerAttempted = latch()
        UpdateUtil.updaterForTesting = { _, _ ->
            assertTrue(YtdlpRuntimeAuthority.mutationOwnedByCurrentThread())
            native.binary.writeText("")
            mutationOwned.countDown(); await(allowPromotion)
            native.promote(); done()
        }
        val update = async(Dispatchers.IO) { UpdateUtil(context).updateYoutubeDL() }
        await(mutationOwned)
        YtdlpRuntimeAuthority.consumerRequestedForTesting = { identity ->
            if (identity.startsWith("download:")) consumerAttempted.countDown()
        }
        val id = enqueueConsumer()
        await(consumerAttempted)
        assertFalse("no native program may observe partial publication", native.ready(id).exists())
        allowPromotion.countDown()
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, withTimeout(30_000) { update.await() }.status)
        requireLive(id, "fixture-v2")
        native.release(id)
        requireTerminal(id)
    }

    @Test
    fun ownedUpdaterFailureAndCancellationReleaseForLaterConsumersAndRetry() = authorityTest {
        val released = AtomicInteger()
        val testedMutationOwner = ThreadLocal<Boolean>()
        val cancelOwned = latch()
        val cancelHold = latch()
        val attempts = AtomicInteger()
        // The hook runs on the acquiring thread. Count only an acquisition that
        // actually entered this test's updater, after the real startup jobs joined.
        YtdlpRuntimeAuthority.mutationRequestedForTesting = { testedMutationOwner.remove() }
        YtdlpRuntimeAuthority.mutationReleasedForTesting = {
            if (testedMutationOwner.get() == true) {
                released.incrementAndGet()
            }
        }
        UpdateUtil.updaterForTesting = { _, _ ->
            assertTrue(YtdlpRuntimeAuthority.mutationOwnedByCurrentThread())
            testedMutationOwner.set(true)
            when (attempts.getAndIncrement()) {
                0 -> throw IllegalStateException("owned updater failure")
                1 -> { cancelOwned.countDown(); await(cancelHold); error("cancelled updater must not return success") }
                else -> { native.promote(); done() }
            }
        }
        val failure = runCatching { withContext(Dispatchers.IO) { UpdateUtil(context).updateYoutubeDL() } }
        assertTrue(failure.exceptionOrNull()?.message?.contains("owned updater failure") == true)
        assertEquals(1, released.get())
        val first = enqueueConsumer(); requireLive(first, "fixture-v1"); native.release(first); requireTerminal(first)
        val cancelled = async(Dispatchers.IO) { UpdateUtil(context).updateYoutubeDL() }
        await(cancelOwned)
        cancelled.cancelAndJoin()
        assertEquals(2, released.get())
        val second = enqueueConsumer(); requireLive(second, "fixture-v1"); native.release(second); requireTerminal(second)
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, withContext(Dispatchers.IO) { UpdateUtil(context).updateYoutubeDL() }.status)
        assertEquals(3, released.get())
    }

    @Test
    fun realCustomSelfUpdateOwnsMutationWithoutRecursiveConsumerAdmission() = authorityTest {
        val updateUtil = UpdateUtil(context)
        updateUtil.selectSource("updater04/native-fixture", "fixture")
        File(native.root, "update-hold").writeText("owned")
        val admittedReaders = AtomicInteger()
        YtdlpRuntimeAuthority.consumerAdmittedForTesting = { admittedReaders.incrementAndGet() }
        val update = async(Dispatchers.IO) { updateUtil.updateYoutubeDL() }
        waitFor {
            if (update.isCompleted) {
                val result = update.await()
                error("Custom native updater returned ${result.status} before its admission proof")
            }
            File(native.root, "update-ready").isFile
        }
        assertTrue(File(native.root, "update-ready").readText().startsWith("mutation:"))
        assertEquals("custom update must not reenter a reader", 0, admittedReaders.get())
        val attempted = latch()
        YtdlpRuntimeAuthority.consumerRequestedForTesting = { if (it.startsWith("download:")) attempted.countDown() }
        val id = enqueueConsumer()
        await(attempted)
        assertFalse(native.ready(id).exists())
        File(native.root, "update-hold").delete()
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, withTimeout(30_000) { update.await() }.status)
        requireLive(id, "fixture-v2")
        assertEquals(1, admittedReaders.get())
        native.release(id); requireTerminal(id)
    }

    @Test
    fun overlappingUpdaterRequestsKeepCoalescingAndDesiredGenerationOrdering() = authorityTest {
        val entered = latch(); val release = latch(); val secondAdmitted = latch()
        val count = AtomicInteger(); val admitted = AtomicInteger()
        UpdateUtil.updateRequestAdmittedForTesting = { if (admitted.incrementAndGet() == 2) secondAdmitted.countDown() }
        UpdateUtil.updaterForTesting = { _, _ ->
            count.incrementAndGet(); entered.countDown(); await(release); native.promote(); done()
        }
        val util = UpdateUtil(context)
        val first = async(Dispatchers.IO) { util.updateYoutubeDL() }
        await(entered)
        val same = async(Dispatchers.IO) { util.updateYoutubeDL() }
        await(secondAdmitted); release.countDown()
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, withTimeout(30_000) { first.await() }.status)
        assertEquals(UpdateUtil.YTDLPUpdateStatus.ALREADY_UP_TO_DATE, withTimeout(30_000) { same.await() }.status)
        assertEquals(1, count.get())
        val generation = util.selectSource("master", "master")
        assertEquals(UpdateUtil.YTDLPUpdateStatus.SUPERSEDED, util.updateYoutubeDL(generation - 1).status)
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, util.updateYoutubeDL(generation).status)
        assertEquals(2, count.get())
        assertEquals(generation, preferences.getLong("ytdlp_committed_source_generation", -1))
    }

    @Test
    fun independentRealDownloadNativeGenerationsRemainIsolatedOnException() = authorityTest {
        val first = enqueueConsumer(); val second = enqueueConsumer()
        val firstExecution = requireLive(first, "fixture-v1")
        val secondExecution = requireLive(second, "fixture-v1")
        assertNotEquals(firstExecution, secondExecution)
        val firstToken = YtdlpNativeProcessBarrier.generationTokenFor("download:$first:$firstExecution")
        val secondToken = YtdlpNativeProcessBarrier.generationTokenFor("download:$second:$secondExecution")
        assertNotNull(firstToken); assertNotNull(secondToken); assertNotEquals(firstToken, secondToken)
        native.release(first); requireTerminal(first)
        assertEquals(secondExecution, requireLive(second, "fixture-v1"))
        val entered = latch()
        UpdateUtil.updaterForTesting = { _, _ -> entered.countDown(); native.promote(); done() }
        val update = async(Dispatchers.IO) { UpdateUtil(context).updateYoutubeDL() }
        assertFalse(entered.await(300, TimeUnit.MILLISECONDS))
        native.release(second); requireTerminal(second)
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, withTimeout(30_000) { update.await() }.status)
    }


    private data class UnresolvedMutation(val identity: String, val token: String, val process: Process)

    private suspend fun establishUnresolvedMutation(): UnresolvedMutation {
        val util = UpdateUtil(context)
        util.selectSource("updater04/native-fixture", "fixture")
        File(native.root, "update-hold").writeText("owned")
        val captured = AtomicReference<UnresolvedMutation>()
        YoutubeDLCompat.processStarterOverrideForTesting = { command, environment, redirect ->
            check(command.contains("--update-to"))
            val process = ProcessBuilder(command).redirectErrorStream(redirect).apply {
                environment().putAll(environment)
            }.start()
            val identity = requireNotNull(environment["YTDLNISX_PROCESS_ID"])
            val token = requireNotNull(environment["YTDLNISX_NATIVE_GENERATION"])
            mutationGenerations += identity to token
            captured.set(UnresolvedMutation(identity, token, process))
            val ready = File(native.root, "update-ready")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!ready.isFile && process.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
            check(ready.isFile && ready.readText() == identity) { "Real custom updater did not reach its native body" }
            // A real native generation has crossed launch. Simulate loss of
            // readable finalization evidence; never substitute a helper claim.
            YtdlpNativeProcessBarrier.markerReadFailurePathForTesting =
                YtdlpNativeProcessBarrier.markerFor(identity).absolutePath
            throw IOException("Lost Java launch acknowledgement after real native entry")
        }
        val failure = try {
            withContext(Dispatchers.IO) { runCatching { util.updateYoutubeDL() }.exceptionOrNull() }
        } finally {
            YoutubeDLCompat.processStarterOverrideForTesting = null
        }
        assertTrue("Must retain unresolved native failure: $failure", failure is YoutubeDLCompat.NativeExecutionFailure)
        val exact = requireNotNull(captured.get())
        assertTrue(exact.identity.startsWith("mutation:"))
        assertTrue(exact.process.isAlive)
        assertFalse(YtdlpNativeProcessBarrier.proveGenerationAbsent(exact.token))
        assertFalse(YtdlpRuntimeAuthority.mutationOwnedByCurrentThread())
        val publication = YtdlpNativeProcessBarrier.markerFor(YtdlpNativeProcessBarrier.RUNTIME_MUTATION_PROCESS_ID)
        assertTrue(publication.isFile)
        emitDiagnostic("Updater04 debt=UNRESOLVED_NATIVE identity=${exact.identity} token=${exact.token} " +
            "alive=${exact.process.isAlive} publication=${publication.readText().replace('\n', '|')}")
        return exact
    }

    private suspend fun requireReaderBlocked(exact: UnresolvedMutation? = null) {
        val ordinaryLaunches = AtomicInteger()
        YoutubeDLCompat.processStarterOverrideForTesting = { command, environment, redirect ->
            if (environment["YTDLNISX_PROCESS_ID"]?.startsWith("consumer:") == true) ordinaryLaunches.incrementAndGet()
            ProcessBuilder(command).redirectErrorStream(redirect).apply { environment().putAll(environment) }.start()
        }
        val failure = try {
            withContext(Dispatchers.IO) {
                runCatching { YoutubeDLCompat.executeLibraryRequest(context,
                    YoutubeDLRequest(emptyList()).apply { addOption("--version") }) }.exceptionOrNull()
            }
        } finally { YoutubeDLCompat.processStarterOverrideForTesting = null }
        assertNotNull("Reader must fail closed, not launch against unresolved publication", failure)
        assertEquals("No ordinary native launch may cross the fence", 0, ordinaryLaunches.get())
        exact?.let {
            assertTrue("Unresolved proof must include a genuinely live mutation", it.process.isAlive)
            assertFalse(YtdlpNativeProcessBarrier.proveGenerationAbsent(it.token))
        }
        assertFalse(YtdlpNativeProcessBarrier.runtimeMutationDebtIsAbsent(context))
        emitDiagnostic("Updater04 debt=READER_BLOCKED launches=${ordinaryLaunches.get()} failure=${failure?.javaClass?.name}")
    }

    private suspend fun recoverExactMutation(exact: UnresolvedMutation) {
        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
        assertTrue(YtdlpNativeProcessBarrier.recoverGeneration(exact.identity, exact.token))
        assertTrue(YtdlpNativeProcessBarrier.proveGenerationAbsent(exact.token))
        withContext(Dispatchers.IO) { YtdlpRuntimeAuthority.withMutation(context) { } }
        assertTrue(YtdlpNativeProcessBarrier.runtimeMutationDebtIsAbsent(context))
        emitDiagnostic("Updater04 debt=EXACT_RECOVERY identity=${exact.identity} token=${exact.token} absent=true")
    }

    private suspend fun requireVersion(version: String) {
        val response = withContext(Dispatchers.IO) {
            YoutubeDLCompat.executeLibraryRequest(context,
                YoutubeDLRequest(emptyList()).apply { addOption("--version") })
        }
        assertEquals(version, response.out.trim())
    }

    @Test
    fun unresolvedMutationBlocksLateReaderUntilExactRecovery() = authorityTest {
        val exact = establishUnresolvedMutation()
        requireReaderBlocked(exact)
        recoverExactMutation(exact)
        requireVersion("fixture-v1")
    }

    @Test
    fun restartPreservesMutationDebtFence() = authorityTest {
        val exact = establishUnresolvedMutation()
        val marker = YtdlpNativeProcessBarrier.markerFor(YtdlpNativeProcessBarrier.RUNTIME_MUTATION_PROCESS_ID)
        val before = marker.readText()
        YtdlpRuntimeAuthority.resetProcessLocalAuthorityForTesting()
        assertEquals("Restart analogue must retain durable evidence", before, marker.readText())
        requireReaderBlocked(exact)
        recoverExactMutation(exact)
        requireVersion("fixture-v1")
    }

    @Test
    fun failedPublicationOrValidationDoesNotAdmitReader() = authorityTest {
        UpdateUtil.updaterForTesting = { _, _ -> native.invalidatePublication(); done() }
        val failure = withContext(Dispatchers.IO) { runCatching { UpdateUtil(context).updateYoutubeDL() }.exceptionOrNull() }
        assertNotNull("Failure must follow material fixture publication", failure)
        assertEquals("this is not valid Python (\n", native.binary.readText())
        requireReaderBlocked()
        UpdateUtil.updaterForTesting = { _, _ -> native.promote(); done() }
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE,
            withContext(Dispatchers.IO) { UpdateUtil(context).updateYoutubeDL() }.status)
        assertTrue(YtdlpNativeProcessBarrier.runtimeMutationDebtIsAbsent(context))
        requireVersion("fixture-v2")
    }

    @Test
    fun laterUpdaterProgressAfterExactMutationRecovery() = authorityTest {
        val exact = establishUnresolvedMutation()
        requireReaderBlocked(exact)
        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting = null
        assertTrue(YtdlpNativeProcessBarrier.recoverGeneration(exact.identity, exact.token))
        assertTrue(YtdlpNativeProcessBarrier.proveGenerationAbsent(exact.token))
        UpdateUtil.updaterForTesting = { _, _ -> native.promote(); done() }
        val util = UpdateUtil(context)
        util.selectSource("stable", "stable")
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE,
            withContext(Dispatchers.IO) { util.updateYoutubeDL() }.status)
        assertTrue(YtdlpNativeProcessBarrier.runtimeMutationDebtIsAbsent(context))
        requireVersion("fixture-v2")
    }

    @Test
    fun preRegistrationCancellationRevokesWaitingRealDownloadLaunch() = authorityTest {
        val writerOwned = latch(); val releaseWriter = latch(); val readerRequested = latch(); val revoked = latch()
        val waitingIdentity = AtomicReference<String>()
        val launches = AtomicInteger()
        UpdateUtil.updaterForTesting = { _, _ -> writerOwned.countDown(); await(releaseWriter); done() }
        val update = async(Dispatchers.IO) { UpdateUtil(context).updateYoutubeDL() }
        await(writerOwned)
        YtdlpRuntimeAuthority.consumerRequestedForTesting = { identity ->
            if (identity.startsWith("download:")) { waitingIdentity.set(identity); readerRequested.countDown() }
        }
        YoutubeDLCompat.runtimeAdmissionRevokedForTesting = { identity ->
            if (identity == waitingIdentity.get()) revoked.countDown()
        }
        YoutubeDLCompat.processStarterOverrideForTesting = { command, environment, redirect ->
            if (environment["YTDLNISX_PROCESS_ID"]?.startsWith("download:") == true) launches.incrementAndGet()
            ProcessBuilder(command).redirectErrorStream(redirect).apply { environment().putAll(environment) }.start()
        }
        val id = enqueueConsumer()
        await(readerRequested)
        val row = requireNotNull(db.downloadDao.getNullableDownloadById(id))
        assertEquals("Active", row.status)
        assertTrue(row.executionId.isNotBlank())
        assertEquals("download:$id:${row.executionId}", waitingIdentity.get())
        assertEquals(row.executionId, DownloadWorkerExecutionOwners.ownerOf(id))
        assertFalse("An unbound/stale native token cannot revoke this waiting launch",
            YoutubeDLCompat.destroyProcessByIdForGeneration(waitingIdentity.get(), UUID.randomUUID().toString()))
        assertEquals(1L, revoked.count)
        cancelledTransports[id] = row
        WorkManager.getInstance(context).cancelWorkById(requireNotNull(requests[id])).result.get(10, TimeUnit.SECONDS)
        await(revoked)
        assertEquals(0, launches.get())
        assertFalse(native.ready(id).exists())
        releaseWriter.countDown()
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, withTimeout(30_000) { update.await() }.status)
        awaitCancelledTransportIsolation(id)
        assertEquals("Revoked attempt must never launch after writer release", 0, launches.get())
        assertFalse(native.ready(id).exists())
        assertNull(db.historyDao.getItemByDownloadId(id))
        emitDiagnostic("Updater04 debt=CANCELLED_PRE_REGISTRATION identity=${waitingIdentity.get()} launches=0")
    }



    private suspend fun awaitCancelledTransportIsolation(id: Long) {
        val admitted = requireNotNull(cancelledTransports[id])
        val cleanup = requireNotNull(cleanups[id])
        waitFor { cleanup.observed.count == 0L }
        assertEquals("Cleanup must belong to the exact admitted attempt", admitted.executionId, cleanup.execution.get())
        val row = requireNotNull(db.downloadDao.getNullableDownloadById(id))
        // Generic WorkManager transport cancellation uses the existing exact
        // requeue CAS; it deliberately revokes Room's execution token.
        assertEquals("Queued", row.status)
        assertEquals("", row.executionId)
        assertEquals(admitted.operationId, row.operationId)
        assertEquals(admitted.retryAttempt, row.retryAttempt)
        assertNull(DownloadWorkerExecutionOwners.ownerOf(id))
        assertNull(DownloadWorkerProcessOwners.ownerOf(id))
        assertFalse(DownloadWorker.hasRegisteredNativeProcess(id, admitted.executionId))
        assertFalse(YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(id, admitted.executionId))
        assertFalse(YtdlpNativeProcessBarrier.hasUnresolved("download:$id:${admitted.executionId}"))
        if (DownloadProducerRecovery.hasBlockingForAdmission(context, id) && producerReconciliations.add(id)) {
            DownloadExecutionRecovery.reconcile(context, db)
        }
        waitFor { !DownloadProducerRecovery.hasBlockingForAdmission(context, id) }
        val discovery = DownloadProducerRecovery.discover(context)
        assertTrue(discovery is DownloadProducerRecovery.DiscoveryResult.Healthy)
        assertTrue(discovery.records.filter { it.downloadId == id }.all { it.executionId == admitted.executionId })
        assertNull(DownloadExecutionRecovery.pendingDispositionForExecution(context, id))
        assertFalse(id in DownloadExecutionRecovery.pendingDownloadIds(context))
        assertFalse(DownloadExecutionRecovery.isRecoveryJobActiveForTesting(id))
        assertNull(db.historyDao.getItemByDownloadId(id))
        assertEquals(androidx.work.WorkInfo.State.CANCELLED,
            requireNotNull(WorkManager.getInstance(context).getWorkInfoById(requireNotNull(requests[id]))
                .get(2, TimeUnit.SECONDS)).state)
        emitDiagnostic("Updater04 debt=CANCELLED_TRANSPORT_ISOLATED download=$id " +
            "cleanup=${admitted.executionId} row=Queued/ native=false owners=null work=CANCELLED")
    }


    private fun latch() = CountDownLatch(1).also { gates += it }
    private fun await(latch: CountDownLatch) { assertTrue("finite authority ordering wait", latch.await(20, TimeUnit.SECONDS)) }
    private fun done() = UpdateUtil.YTDLPUpdateResponse(UpdateUtil.YTDLPUpdateStatus.DONE, "fixture promoted")
    private suspend fun waitFor(predicate: suspend () -> Boolean) = withTimeout(30_000) { while (!predicate()) delay(20) }

    private fun authorityTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        try {
            block()
        } finally {
            // Release synchronous authority latches before runBlocking joins its
            // children, including when real native admission has already failed.
            gates.forEach { it.countDown() }
            if (::native.isInitialized) native.releaseAll()
        }
    }

    private suspend fun awaitAppStartup() {
        // Observe the real App.onCreate job tree; no synthetic readiness marker
        // and no new production seam. App creates these children synchronously.
        val scope = App::class.java.getDeclaredField("applicationScope").let { field ->
            field.isAccessible = true
            field.get(null) as CoroutineScope
        }
        val startup = requireNotNull(scope.coroutineContext[Job])
        withTimeout(30_000) {
            while (true) {
                val children = startup.children.toList()
                if (children.isEmpty()) break
                children.joinAll()
            }
        }
        assertFalse("App startup must finish before mutation baselining", startup.children.any { it.isActive })
    }

    private suspend fun enqueueConsumer(): Long {
        val id = runtimeAuthorityDownloadIds.getAndIncrement()
        cleanups[id] = AttemptCleanup()
        val destination = File(native.root, "output-$id").apply { mkdirs() }
        val item = DownloadItem(id=id, url="https://example.com/$id", title="runtime authority", author="author",
            thumb="", duration="00:01", type=DownloadType.audio, format=Format(container="m4a", format_note="audio only"),
            container="m4a", downloadSections="", allFormats=mutableListOf(), downloadPath=destination.absolutePath,
            website="example", downloadSize="", playlistTitle="", audioPreferences=AudioPreferences(),
            videoPreferences=VideoPreferences(), extraCommands="", customFileNameTemplate="", SaveThumb=false,
            status=DownloadRepository.Status.Queued.name, downloadStartTime=0, logID=null, playlistURL="",
            operationId="updater04-$id-${UUID.randomUUID()}")
        native.hold(id)
        db.downloadDao.insertRaw(item)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().addTag("updater04-real-worker").build()
        requests[id] = request.id
        WorkManager.getInstance(context).enqueue(request).result.get(10, TimeUnit.SECONDS)
        return id
    }

    private suspend fun requireLive(id: Long, version: String): String {
        try {
            waitFor {
                val row = db.downloadDao.getNullableDownloadById(id)
                if (row == null || row.status !in setOf("Queued", "Active", "PostProcessing") ||
                    cleanups[id]?.observed?.count == 0L
                ) {
                    val log = row?.logID?.let { runCatching { db.logDao.getByID(it).content }.getOrNull() }.orEmpty()
                    throw AssertionError("Real native admission failed before its ready proof: download=$id " +
                        "row=${row?.status}/${row?.executionId} issue=${row?.lastIssueCode}/${row?.lastIssueStage}\n$log")
                }
                native.ready(id).isFile && native.ready(id).readLines().size == 2
            }
        } catch (failure: Throwable) { captureFailure("NATIVE_ADMISSION_FAILURE", failure); throw failure }
        capture("LIVE_NATIVE_PROOF")
        val row = requireNotNull(db.downloadDao.getNullableDownloadById(id))
        assertEquals(DownloadRepository.Status.Active.name, row.status)
        assertTrue(row.executionId.isNotBlank())
        assertEquals(row.executionId, DownloadWorkerExecutionOwners.ownerOf(id))
        assertEquals(row.executionId, DownloadWorkerProcessOwners.ownerOf(id))
        assertEquals(listOf("download:$id:${row.executionId}", version), native.ready(id).readLines())
        assertNotNull(YtdlpNativeProcessBarrier.generationTokenFor("download:$id:${row.executionId}"))
        assertTrue(YtdlpNativeProcessBarrier.hasUnresolved("download:$id:${row.executionId}"))
        val previous = liveExecutions.putIfAbsent(id, row.executionId)
        if (previous != null) assertEquals("The live execution must not silently change", previous, row.executionId)
        return row.executionId
    }

    private suspend fun requireTerminal(id: Long) {
        awaitAttemptCleanup(id)
        convergeProducerRecovery(id)
        capture("EXACT_NATIVE_TERMINAL_PROOF")
        val row = requireNotNull(db.downloadDao.getNullableDownloadById(id))
        assertEquals(DownloadRepository.Status.Error.name, row.status)
        assertNull(db.historyDao.getItemByDownloadId(id))
        assertAttemptIsolated(id)
    }

    private suspend fun awaitAttemptCleanup(id: Long) {
        val cleanup = requireNotNull(cleanups[id])
        try { waitFor { cleanup.observed.count == 0L } }
        catch (failure: Throwable) { captureFailure("EXACT_CLEANUP_OBSERVATION_FAILURE", failure); throw failure }
        val execution = requireNotNull(cleanup.execution.get())
        assertTrue("Cleanup must carry an exact nonblank execution", execution.isNotBlank())
        liveExecutions[id]?.let { assertEquals("Cleanup must belong to the admitted execution", it, execution) }
        assertEquals("Cleanup must belong to the current row execution", execution,
            requireNotNull(db.downloadDao.getNullableDownloadById(id)).executionId)
    }

    private data class ProducerObservation(
        val blocking: Boolean,
        val converged: Boolean,
        val diagnostics: String,
    )

    private fun observeProducerRecovery(id: Long, execution: String): ProducerObservation {
        val discovery = DownloadProducerRecovery.discover(context)
        val namespace = when (discovery) {
            is DownloadProducerRecovery.DiscoveryResult.Healthy -> "Healthy"
            is DownloadProducerRecovery.DiscoveryResult.Unavailable -> "Unavailable:${discovery.reason}"
            is DownloadProducerRecovery.DiscoveryResult.Opaque -> "Opaque:${discovery.opaqueFiles}"
        }
        val records = discovery.records.filter { it.downloadId == id }.map { record ->
            // This phase classification is diagnostic only. The convergence gate
            // uses the actual production hasBlockingForAdmission predicate below.
            val phaseBlocks = record.phase !in setOf(
                DownloadProducerRecovery.Phase.FINALIZED, DownloadProducerRecovery.Phase.COMPLETE,
            )
            "operation=${record.operationId}/execution=${record.executionId}/generation=${record.generationId}" +
                "/phase=${record.phase}/admissionBlocking=$phaseBlocks" +
                "/exactExecution=${record.executionId == execution}/root=${record.outputRoot}"
        }
        val blocking = DownloadProducerRecovery.hasBlockingForAdmission(context, id)
        val row = db.downloadDao.getNullableDownloadById(id)
        val executionOwner = DownloadWorkerExecutionOwners.ownerOf(id)
        val processOwner = DownloadWorkerProcessOwners.ownerOf(id)
        val registered = DownloadWorker.hasRegisteredNativeProcess(id, execution)
        val markerDebt = YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(id, execution)
        val unresolved = YtdlpNativeProcessBarrier.hasUnresolved("download:$id:$execution")
        val recoveryActive = DownloadExecutionRecovery.isRecoveryJobActiveForTesting(id)
        val disposition = DownloadExecutionRecovery.pendingDispositionForExecution(context, id)
        val recoveryPhase = DownloadExecutionRecovery.pendingPhaseForTesting(context, id)
        val genericPending = id in DownloadExecutionRecovery.pendingDownloadIds(context)
        val diagnostics = "download=$id cleanupExecution=$execution discovery=$namespace records=$records " +
            "producerBlocking=$blocking row=${row?.status}/${row?.executionId} operation=${row?.operationId} " +
            "executionOwner=$executionOwner processOwner=$processOwner nativeRegistered=$registered " +
            "markerDebt=$markerDebt unresolved=$unresolved recoveryActive=$recoveryActive " +
            "recovery=$disposition/$recoveryPhase genericPending=$genericPending"
        if (discovery !is DownloadProducerRecovery.DiscoveryResult.Healthy) {
            emitDiagnostic("Updater04 phase=PRODUCER_DISCOVERY_FAILURE $diagnostics")
            throw AssertionError("Producer namespace must remain readable: $diagnostics")
        }
        // COMPLETE finality is valid and non-blocking; record absence is not required.
        val converged = !blocking && row != null && row.executionId == execution &&
            row.status !in setOf("Active", "PostProcessing") &&
            executionOwner == null && processOwner == null && !registered && !markerDebt && !unresolved &&
            !recoveryActive && disposition == null && !genericPending
        return ProducerObservation(blocking, converged, diagnostics)
    }

    private suspend fun convergeProducerRecovery(id: Long) {
        val cleanup = requireNotNull(cleanups[id])
        assertEquals("Producer reconciliation must follow exact attempt cleanup", 0L, cleanup.observed.count)
        val execution = requireNotNull(cleanup.execution.get())
        var lastDiagnostics = "Producer observation not yet available for $id/$execution"
        fun observe(phase: String): ProducerObservation {
            val current = observeProducerRecovery(id, execution)
            if (current.diagnostics != lastDiagnostics) {
                emitDiagnostic("Updater04 phase=$phase ${current.diagnostics}")
            }
            lastDiagnostics = current.diagnostics
            return current
        }
        try {
            val before = observe("BEFORE_PRODUCER_DRAIN")
            withTimeout(30_000) {
                // Drive the real recovery protocol once per consumer. Deferred
                // recovery must finish naturally; teardown must not retry or cancel it.
                if (before.blocking && producerReconciliations.add(id)) {
                    val result = DownloadExecutionRecovery.reconcile(context, db)
                    val failure = result.failuresByDownload[id]
                    emitDiagnostic("Updater04 phase=PRODUCER_RECONCILE_RETURN download=$id " +
                        "execution=$execution deferred=${id in result.deferredDownloadIds} " +
                        "recoveryActive=${DownloadExecutionRecovery.isRecoveryJobActiveForTesting(id)} " +
                        "failure=${failure?.javaClass?.simpleName}/${failure?.message}")
                }
                while (!observe("PRODUCER_CONVERGENCE_WAIT").converged) delay(20)
            }
            emitDiagnostic("Updater04 phase=PRODUCER_CONVERGED ${observe("AFTER_PRODUCER_DRAIN").diagnostics}")
        } catch (failure: Throwable) {
            try { observe("PRODUCER_CONVERGENCE_FAILURE") }
            catch (observationFailure: Throwable) { failure.addSuppressed(observationFailure) }
            captureFailure("PRODUCER_CONVERGENCE_FAILURE", failure)
            throw AssertionError("Exact producer recovery did not converge: $lastDiagnostics; failure=${failure.message}", failure)
        }
    }

    private fun assertAttemptIsolated(id: Long) {
        val execution = requireNotNull(cleanups[id]?.execution?.get())
        val row = requireNotNull(db.downloadDao.getNullableDownloadById(id))
        assertEquals(execution, row.executionId)
        assertFalse("Cleanup must leave the row outside live execution states",
            row.status in setOf("Active", "PostProcessing"))
        assertNull(DownloadWorkerExecutionOwners.ownerOf(id))
        assertNull(DownloadWorkerProcessOwners.ownerOf(id))
        assertFalse(YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(id, row.executionId))
        assertFalse(YtdlpNativeProcessBarrier.hasUnresolved("download:$id:$execution"))
        assertFalse(DownloadWorker.hasRegisteredNativeProcess(id, row.executionId))
        assertNull(DownloadExecutionRecovery.pendingDispositionForExecution(context, id))
        assertFalse(id in DownloadExecutionRecovery.pendingDownloadIds(context))
        assertFalse(DownloadExecutionRecovery.isRecoveryJobActiveForTesting(id))
        val producer = observeProducerRecovery(id, execution)
        assertFalse("Producer isolation must follow real recovery: ${producer.diagnostics}", producer.blocking)
    }

    private fun captureFailure(phase: String, primaryFailure: Throwable) {
        try { capture(phase) } catch (diagnosticFailure: Throwable) { primaryFailure.addSuppressed(diagnosticFailure) }
    }

    private fun capture(phase: String) {
        requests.forEach { (id, request) ->
            val row = db.downloadDao.getNullableDownloadById(id)
            val info = runCatching { WorkManager.getInstance(context).getWorkInfoById(request).get(2, TimeUnit.SECONDS) }.getOrNull()
            val execution = row?.executionId.orEmpty()
            val text = "Updater04 phase=$phase download=$id request=$request row=${row?.status}/$execution " +
                "executionOwner=${DownloadWorkerExecutionOwners.ownerOf(id)} processOwner=${DownloadWorkerProcessOwners.ownerOf(id)} " +
                "generation=${YtdlpNativeProcessBarrier.generationTokenFor("download:$id:$execution")} " +
                "nativeDebt=${YtdlpNativeProcessBarrier.hasDownloadMarkerDebt(id, execution)} " +
                "cleanup=${cleanups[id]?.execution?.get()} work=${info?.id}/${info?.state}/${info?.runAttemptCount} " +
                "recovery=${DownloadExecutionRecovery.pendingDispositionForExecution(context,id)}/${DownloadExecutionRecovery.pendingPhaseForTesting(context,id)}"
            emitDiagnostic(text)
        }
    }

    private fun emitDiagnostic(text: String) {
        android.util.Log.i("Updater04NativeProof", text)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,
            android.os.Bundle().apply { putString("stream", "\n$text\n") })
    }

    private fun resetHooks() {
        YoutubeDLCompat.processStarterOverrideForTesting=null
        YoutubeDLCompat.runtimeAdmissionRevokedForTesting=null
        YtdlpNativeProcessBarrier.markerReadFailurePathForTesting=null
        UpdateUtil.updaterForTesting=null; UpdateUtil.updateRequestAdmittedForTesting=null
        YtdlpRuntimeAuthority.consumerAdmittedForTesting=null; YtdlpRuntimeAuthority.consumerRequestedForTesting=null
        YtdlpRuntimeAuthority.mutationRequestedForTesting=null; YtdlpRuntimeAuthority.mutationAdmittedForTesting=null
        YtdlpRuntimeAuthority.mutationReleasedForTesting=null
        DownloadWorkerEffectTestHooks.afterAttemptCleanupForTesting=null; DownloadWorkerEffectTestHooks.dbManagerForTesting=null
    }
}
