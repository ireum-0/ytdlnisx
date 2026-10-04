package com.ireum.ytdl.util

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.App
import androidx.room.Room
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.yausername.aria2c.Aria2c
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class UpdateUtilProductionWiringTest {
    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var originalValues: Map<String, Any?>
    private lateinit var native: RuntimeAuthorityNativeFixture
    private val startupOwners = mutableListOf<StartupYtdlpUpdateOwner>()
    private val completionListeners = mutableListOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()

    private val ownedPreferenceKeys = listOf(
        "ytdlp_source",
        "ytdlp_source_label",
        "ytdlp_source_generation",
        "ytdlp_committed_source_generation",
        "ytdlp_committed_source",
        "ytdlp_committed_result",
        "ytdlp_pending_source_generation",
        "ytdlp_pending_source",
        "auto_update_ytdlp",
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        App.instance.startupYtdlpUpdater.stop()
        awaitRuntimeReadiness()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalValues = ownedPreferenceKeys.associateWith { key ->
            if (!preferences.contains(key)) null else if (key == "auto_update_ytdlp") {
                preferences.getBoolean(key, false)
            } else if (key.endsWith("_generation")) {
                preferences.getLong(key, 0)
            } else preferences.getString(key, null)
        }
        val editor = preferences.edit()
        ownedPreferenceKeys.forEach(editor::remove)
        assertTrue("test preference reset must persist", editor.commit())
        UpdateUtil.updaterForTesting = null
        UpdateUtil.updateRequestAdmittedForTesting = null
        native = RuntimeAuthorityNativeFixture(context).also { it.install() }
    }

    @After
    fun tearDown() {
        if (!::originalValues.isInitialized) return
        runBlocking { startupOwners.forEach { it.stop() } }
        completionListeners.forEach(preferences::unregisterOnSharedPreferenceChangeListener)
        if (::native.isInitialized) native.close()
        UpdateUtil.updaterForTesting = null
        UpdateUtil.updateRequestAdmittedForTesting = null
        val editor = preferences.edit()
        ownedPreferenceKeys.forEach(editor::remove)
        originalValues.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                null -> Unit
            }
        }
        assertTrue("test preferences must be restored", editor.commit())
    }

    private suspend fun awaitRuntimeReadiness() {
        var startup: Job? = null
        try {
            withTimeout(30_000) {
                // Observe the same real App job tree as the focused native fixture.
                val scope = App::class.java.getDeclaredField("applicationScope").let { field ->
                    field.isAccessible = true
                    field.get(null) as CoroutineScope
                }
                val owner = requireNotNull(scope.coroutineContext[Job])
                startup = owner
                while (true) {
                    val children = owner.children.toList()
                    if (children.isEmpty()) break
                    children.joinAll()
                }
                check(!owner.isCancelled) { "App startup scope was cancelled" }
                // In the resolved 0.18.1 dependencies these flags are set only
                // after payload initialization returns; joining startup publishes them.
                check(dependencyInitialized(YoutubeDL::class.java)) { "YoutubeDL initialization did not complete" }
                check(dependencyInitialized(Aria2c::class.java)) { "Aria2c initialization did not complete" }
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Runtime readiness precondition failed within 30000ms: ${readinessDiagnostic(startup)}",
                failure,
            )
        }
    }

    private fun dependencyInitialized(type: Class<*>): Boolean =
        type.getDeclaredField("initialized").let { field ->
            field.isAccessible = true
            field.getBoolean(null)
        }

    private fun readinessDiagnostic(startup: Job?): String {
        fun state(type: Class<*>) = runCatching { dependencyInitialized(type).toString() }
            .getOrElse { "unavailable:${it.javaClass.simpleName}:${it.message}" }
        val libraries = runCatching {
            val runtime = YoutubeDLCompat.runtimeLayout(context)
            listOf(
                runtime.pythonBinary,
                runtime.quickJsBinary,
                java.io.File(runtime.pythonLibraryDir, "libandroid-support.so"),
                java.io.File(runtime.pythonLibraryDir, "libpython3.12.so.1.0"),
            ).joinToString { "${it.absolutePath}:isFile=${it.isFile}:bytes=${it.length()}" }
        }.getOrElse { "unavailable:${it.javaClass.simpleName}:${it.message}" }
        return "startup=$startup children=${startup?.children?.toList()} " +
            "YoutubeDL.initialized=${state(YoutubeDL::class.java)} " +
            "Aria2c.initialized=${state(Aria2c::class.java)} libraries=[$libraries]"
    }

    @Test
    fun sourceBSelectedDuringAUpdateRunsAfterAAndOwnsCommittedProvenance() = runBlocking {
        val update = UpdateUtil(context)
        val sourceAEnteredNativeBoundary = CountDownLatch(1)
        val allowSourceAToFinish = CountDownLatch(1)
        val invocations = CopyOnWriteArrayList<String>()
        UpdateUtil.updaterForTesting = { _, source ->
            invocations += source
            assertEquals(source, preferences.getString("ytdlp_pending_source", null))
            assertEquals(
                preferences.getLong("ytdlp_source_generation", -1L),
                preferences.getLong("ytdlp_pending_source_generation", -2L),
            )
            if (source == "stable") {
                sourceAEnteredNativeBoundary.countDown()
                check(allowSourceAToFinish.await(10, TimeUnit.SECONDS)) {
                    "source A test latch was not released"
                }
                UpdateUtil.YTDLPUpdateResponse(UpdateUtil.YTDLPUpdateStatus.DONE, "A-installed")
            } else {
                UpdateUtil.YTDLPUpdateResponse(UpdateUtil.YTDLPUpdateStatus.DONE, "B-installed")
            }
        }

        val generationA = update.selectSource("stable", "Stable")
        val requestA = async(Dispatchers.IO) { update.updateYoutubeDL(generationA) }
        assertTrue("source A must reach the updater boundary", sourceAEnteredNativeBoundary.await(10, TimeUnit.SECONDS))

        val generationB = update.selectSource("nightly", "Nightly")
        assertTrue("a source change must advance its generation", generationB > generationA)
        assertEquals(UpdateUtil.DesiredSource("nightly", generationB), update.desiredSource())
        assertEquals("source B must persist before A is released", listOf("stable"), invocations.toList())
        assertFalse("A must not be recorded as B's committed runtime", preferences.contains("ytdlp_committed_source"))

        val requestB = async(Dispatchers.IO) { update.updateYoutubeDL(generationB) }
        val staleRequestA = async(Dispatchers.IO) { update.updateYoutubeDL(generationA) }
        assertEquals(
            UpdateUtil.YTDLPUpdateStatus.SUPERSEDED,
            withTimeout(5_000) { staleRequestA.await() }.status,
        )

        allowSourceAToFinish.countDown()
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, withTimeout(10_000) { requestA.await() }.status)
        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, withTimeout(10_000) { requestB.await() }.status)
        assertEquals(listOf("stable", "nightly"), invocations.toList())
        assertEquals(generationB, preferences.getLong("ytdlp_source_generation", -1L))
        assertEquals("nightly", preferences.getString("ytdlp_source", null))
        assertEquals("Nightly", preferences.getString("ytdlp_source_label", null))
        assertEquals(generationB, preferences.getLong("ytdlp_committed_source_generation", -1L))
        assertEquals("nightly", preferences.getString("ytdlp_committed_source", null))
        assertEquals("DONE:B-installed", preferences.getString("ytdlp_committed_result", null))
        assertFalse(preferences.contains("ytdlp_pending_source_generation"))
        assertFalse(preferences.contains("ytdlp_pending_source"))
    }

    @Test
    fun startupReconcilesPersistedDesiredGenerationAfterCoordinatorRecreation() = runBlocking {
        assertTrue(
            preferences.edit()
                .putString("ytdlp_source", "nightly")
                .putLong("ytdlp_source_generation", 8L)
                .putLong("ytdlp_committed_source_generation", 7L)
                .putString("ytdlp_committed_source", "stable")
                .putString("ytdlp_committed_result", "DONE:stable@old")
                .commit(),
        )
        val calls = AtomicInteger()
        UpdateUtil.updaterForTesting = { _, source ->
            assertEquals("nightly", source)
            calls.incrementAndGet()
            UpdateUtil.YTDLPUpdateResponse(UpdateUtil.YTDLPUpdateStatus.DONE, "nightly@recovered")
        }

        val result = UpdateUtil(context).updateOnStartup(automaticUpdatesEnabled = false)

        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, result.status)
        assertEquals(1, calls.get())
        assertEquals(8L, preferences.getLong("ytdlp_committed_source_generation", -1L))
        assertEquals("nightly", preferences.getString("ytdlp_committed_source", null))
        assertEquals("DONE:nightly@recovered", preferences.getString("ytdlp_committed_result", null))
    }

    @Test
    fun nativeFailureReleasesMutationOwnerForLaterRequest() = runBlocking {
        val update = UpdateUtil(context)
        val generation = update.selectSource("stable", "Stable")
        val calls = AtomicInteger()
        UpdateUtil.updaterForTesting = { _, _ ->
            if (calls.incrementAndGet() == 1) error("first native failure")
            UpdateUtil.YTDLPUpdateResponse(UpdateUtil.YTDLPUpdateStatus.DONE, "stable@recovered")
        }

        val failure = runCatching { update.updateYoutubeDL(generation) }.exceptionOrNull()
        assertEquals("first native failure", failure?.message)
        assertEquals("stable", preferences.getString("ytdlp_pending_source", null))

        assertEquals(
            UpdateUtil.YTDLPUpdateStatus.DONE,
            update.updateOnStartup(automaticUpdatesEnabled = false).status,
        )
        assertEquals(2, calls.get())
        assertEquals(generation, preferences.getLong("ytdlp_committed_source_generation", -1L))
        assertFalse(preferences.contains("ytdlp_pending_source_generation"))
    }

    @Test
    fun overlappingSameGenerationRequestsShareOneNativeUpdate() = runBlocking {
        val update = UpdateUtil(context)
        val generation = update.selectSource("stable", "Stable")
        val enteredNativeBoundary = CountDownLatch(1)
        val releaseNativeUpdate = CountDownLatch(1)
        val duplicateRequestAdmitted = CountDownLatch(1)
        val calls = AtomicInteger()
        val requests = AtomicInteger()
        UpdateUtil.updateRequestAdmittedForTesting = {
            if (requests.incrementAndGet() == 2) duplicateRequestAdmitted.countDown()
        }
        UpdateUtil.updaterForTesting = { _, _ ->
            calls.incrementAndGet()
            enteredNativeBoundary.countDown()
            check(releaseNativeUpdate.await(10, TimeUnit.SECONDS)) {
                "same-generation test latch was not released"
            }
            UpdateUtil.YTDLPUpdateResponse(UpdateUtil.YTDLPUpdateStatus.DONE, "stable@1")
        }

        val first = async(Dispatchers.IO) { update.updateYoutubeDL(generation) }
        assertTrue(enteredNativeBoundary.await(10, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { update.updateYoutubeDL(generation) }
        assertTrue("second request must reach coordinator admission", duplicateRequestAdmitted.await(10, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
        releaseNativeUpdate.countDown()

        assertEquals(UpdateUtil.YTDLPUpdateStatus.DONE, withTimeout(10_000) { first.await() }.status)
        assertEquals(
            UpdateUtil.YTDLPUpdateStatus.ALREADY_UP_TO_DATE,
            withTimeout(10_000) { second.await() }.status,
        )
        assertEquals(1, calls.get())
    }


    @Test
    fun startupOwnerWaitsForReadinessAfterGenerationAdmission() = runBlocking {
        val generation = UpdateUtil(context).selectSource("nightly", "Nightly")
        val ready = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<UpdateUtil.DesiredSource>()
        val committed = committedGeneration("nightly", generation)
        val calls = AtomicInteger()
        UpdateUtil.updaterForTesting = { _, _ ->
            calls.incrementAndGet()
            UpdateUtil.YTDLPUpdateResponse(UpdateUtil.YTDLPUpdateStatus.DONE, "ready")
        }
        val owner = startupOwner(
            observed = { observed.complete(it) },
            prerequisites = { ready.await() },
        )
        assertEquals(UpdateUtil.DesiredSource("nightly", generation), withTimeout(10_000) { observed.await() })
        assertEquals(0, calls.get())
        assertFalse(preferences.contains("ytdlp_pending_source_generation"))

        ready.complete(Unit)
        withTimeout(30_000) { committed.await() }
        owner.stop()
        assertEquals(1, calls.get())
    }

    @Test
    fun startupOwnerSurvivesStaleActiveRecoveryAndQueuedSnapshotInProcess() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters()).allowMainThreadQueries().build()
        val id = System.currentTimeMillis()
        val activeId = db.downloadDao.insertRaw(staleDownload(id, "Active", "startup-stale-E1"))
        val queuedId = db.downloadDao.insertRaw(staleDownload(id + 1, "Queued", ""))
        val generation = UpdateUtil(context).selectSource("nightly", "Nightly")
        val recoveryAllowed = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Unit>()
        val committed = committedGeneration("nightly", generation)
        val calls = AtomicInteger()
        UpdateUtil.updaterForTesting = { _, _ ->
            assertTrue(db.downloadDao.getNullableDownloadById(activeId)?.status != "Active")
            // A queued row is not native mutation authority or a permanent veto.
            assertEquals("Queued", db.downloadDao.getNullableDownloadById(queuedId)?.status)
            calls.incrementAndGet()
            UpdateUtil.YTDLPUpdateResponse(UpdateUtil.YTDLPUpdateStatus.DONE, "recovered")
        }
        val owner = startupOwner(
            observed = { observed.complete(Unit) },
            prerequisites = {
                recoveryAllowed.await()
                assertTrue(DownloadExecutionRecovery.reconcile(context, db).completedCleanly)
            },
        )
        try {
            withTimeout(10_000) { observed.await() }
            assertEquals(2, db.downloadDao.getDownloadsCountByStatus(listOf("Active", "Queued")))
            assertEquals(0, calls.get())
            recoveryAllowed.complete(Unit)
            withTimeout(30_000) { committed.await() }
            owner.stop()
            assertEquals(1, calls.get())
            assertEquals(1, db.downloadDao.getDownloadsCountByStatus(listOf("Active", "Queued")))
        } finally {
            recoveryAllowed.complete(Unit)
            owner.stop()
            db.close()
        }
    }

    @Test
    fun startupOwnerCoalescesSameGenerationWakeupsWithoutDuplicateNativeUpdate() = runBlocking {
        assertTrue(preferences.edit().putBoolean("auto_update_ytdlp", true).commit())
        val generation = UpdateUtil(context).selectSource("stable", "Stable")
        val committed = committedGeneration("stable", generation)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val duplicateDrained = CompletableDeferred<Unit>()
        val observations = AtomicInteger()
        val calls = AtomicInteger()
        UpdateUtil.updaterForTesting = { _, _ ->
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            UpdateUtil.YTDLPUpdateResponse(UpdateUtil.YTDLPUpdateStatus.DONE, "once")
        }
        val owner = startupOwner(observed = {
            if (observations.incrementAndGet() >= 2) duplicateDrained.complete(Unit)
        })
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            repeat(20) { owner.wake() }
            release.countDown()
            withTimeout(30_000) { committed.await() }
            withTimeout(10_000) { duplicateDrained.await() }
            owner.stop()
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            owner.stop()
        }
    }

    @Test
    fun startupOwnerFollowsRestoredGenerationAfterOlderPendingIntentIsRetired() = runBlocking {
        val generationA = UpdateUtil(context).selectSource("stable", "Stable")
        val enteredA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val responses = CopyOnWriteArrayList<Pair<UpdateUtil.DesiredSource, UpdateUtil.YTDLPUpdateStatus>>()
        val calls = CopyOnWriteArrayList<String>()
        val generationB = generationA + 1
        val committedB = committedGeneration("nightly", generationB)
        UpdateUtil.updaterForTesting = { _, source ->
            calls += source
            if (source == "stable") {
                enteredA.countDown()
                check(releaseA.await(10, TimeUnit.SECONDS))
            } else {
                assertFalse("A must not prove restored B", preferences.contains("ytdlp_committed_source"))
                assertEquals(generationB, preferences.getLong("ytdlp_pending_source_generation", -1))
            }
            UpdateUtil.YTDLPUpdateResponse(UpdateUtil.YTDLPUpdateStatus.DONE, source)
        }
        val owner = startupOwner(completed = { desired, result -> responses += desired to result.status })
        try {
            assertTrue(enteredA.await(10, TimeUnit.SECONDS))
            assertEquals(generationA, preferences.getLong("ytdlp_pending_source_generation", -1))
            // Publish the same durable preference replacement through Restore's
            // production admission boundary, without a fake direct updater claim.
            RestoreMutationAdmission.withRestorePublication {
                assertTrue(preferences.edit()
                    .putString("ytdlp_source", "nightly")
                    .putString("ytdlp_source_label", "Restored Nightly")
                    .putLong("ytdlp_source_generation", generationB)
                    .remove("ytdlp_pending_source_generation")
                    .remove("ytdlp_pending_source")
                    .commit())
            }
            releaseA.countDown()
            withTimeout(30_000) { committedB.await() }
            owner.stop()
            assertEquals(listOf("stable", "nightly"), calls.toList())
            assertTrue(responses.contains(
                UpdateUtil.DesiredSource("stable", generationA) to UpdateUtil.YTDLPUpdateStatus.SUPERSEDED))
            assertEquals("DONE:nightly", preferences.getString("ytdlp_committed_result", null))
            assertFalse(preferences.contains("ytdlp_pending_source_generation"))
        } finally {
            releaseA.countDown()
            owner.stop()
        }
    }

    @Test
    fun startupOwnerRechecksClearedPortableGraphWithoutPreferenceWakeup() = runBlocking {
        val generation = UpdateUtil(context).selectSource("nightly", "Nightly")
        val committedA = committedGeneration("nightly", generation)
        val committedB = committedGeneration("stable", 0L)
        val idleEntered = Channel<Unit>(Channel.UNLIMITED)
        val idleTicks = Channel<Unit>(Channel.RENDEZVOUS)
        val calls = CopyOnWriteArrayList<String>()
        var snapshot: Map<String, Any?>? = null
        UpdateUtil.updaterForTesting = { _, source ->
            calls += source
            if (source == "stable") {
                assertEquals(UpdateUtil.DesiredSource("stable", 0L), UpdateUtil(context).desiredSource())
                assertFalse(preferences.contains("ytdlp_committed_source"))
            }
            UpdateUtil.YTDLPUpdateResponse(UpdateUtil.YTDLPUpdateStatus.DONE, source)
        }
        val owner = startupOwner(idleWakeup = {
            // Model the idle deadline deterministically, including an Android
            // version with no clear notification. This controls waiting only;
            // admission, native exclusion and durable proof remain production.
            idleEntered.send(Unit)
            idleTicks.receive()
        })
        try {
            withTimeout(10_000) { idleEntered.receive() }
            withTimeout(10_000) { idleTicks.send(Unit) }
            withTimeout(30_000) { committedA.await() }
            withTimeout(10_000) { idleEntered.receive() }
            val beforeClear = preferences.all.toMap()
            snapshot = beforeClear
            RestoreMutationAdmission.withRestorePublication {
                val editor = preferences.edit().clear()
                // The real Reset publisher keeps destination-local keys but
                // can replace portable settings with an empty settings array.
                beforeClear.filterKeys { !BackupSettingsUtil.isPortablePreferenceKey(it) }
                    .forEach { (key, value) -> putPreferenceValue(editor, key, value) }
                assertTrue(editor.commit())
            }
            assertEquals(UpdateUtil.DesiredSource("stable", 0L), UpdateUtil(context).desiredSource())
            assertEquals(listOf("nightly"), calls.toList())
            // No callback is allowed to release the controlled idle wait.
            withTimeout(10_000) { idleTicks.send(Unit) }
            withTimeout(30_000) { committedB.await() }
            withTimeout(10_000) { idleEntered.receive() }
            repeat(2) {
                withTimeout(10_000) { idleTicks.send(Unit) }
                withTimeout(10_000) { idleEntered.receive() }
            }
            owner.stop()
            assertEquals(listOf("nightly", "stable"), calls.toList())
            assertEquals("DONE:stable", preferences.getString("ytdlp_committed_result", null))
            assertFalse(preferences.contains("ytdlp_pending_source_generation"))
            assertFalse(preferences.contains("ytdlp_pending_source"))
        } finally {
            owner.stop()
            idleTicks.close()
            idleEntered.close()
            snapshot?.let { saved ->
                RestoreMutationAdmission.withRestorePublication {
                    val editor = preferences.edit().clear()
                    saved.forEach { (key, value) -> putPreferenceValue(editor, key, value) }
                    assertTrue("full preference graph must be restored", editor.commit())
                }
            }
        }
    }

    private fun putPreferenceValue(
        editor: android.content.SharedPreferences.Editor,
        key: String,
        value: Any?,
    ) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            null -> editor.remove(key)
            else -> error("Unsupported preference type for $key")
        }
    }

    private fun startupOwner(
        observed: ((UpdateUtil.DesiredSource) -> Unit)? = null,
        completed: ((UpdateUtil.DesiredSource, UpdateUtil.YTDLPUpdateResponse) -> Unit)? = null,
        idleWakeup: (suspend () -> Unit)? = null,
        prerequisites: suspend () -> Unit = {},
    ) = StartupYtdlpUpdateOwner(
        context,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        generationObserved = observed,
        attemptCompleted = completed,
        awaitIdleWakeupForTesting = idleWakeup,
        awaitPrerequisites = prerequisites,
    ).also(startupOwners::add)

    private fun committedGeneration(source: String, generation: Long): CompletableDeferred<Unit> {
        val committed = CompletableDeferred<Unit>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (preferences.getLong("ytdlp_committed_source_generation", -1) == generation &&
                preferences.getString("ytdlp_committed_source", null) == source &&
                !preferences.contains("ytdlp_pending_source_generation") &&
                !preferences.contains("ytdlp_pending_source")
            ) committed.complete(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        completionListeners += listener
        return committed
    }

    private fun staleDownload(id: Long, status: String, execution: String) = DownloadItem(
        id = id, url = "https://example.invalid/startup", title = "startup", author = "",
        thumb = "", duration = "", type = DownloadType.video, format = Format(),
        container = "Default", downloadSections = "", allFormats = mutableListOf(),
        downloadPath = context.cacheDir.absolutePath, website = "", downloadSize = "",
        playlistTitle = "", audioPreferences = AudioPreferences(), videoPreferences = VideoPreferences(),
        extraCommands = "", customFileNameTemplate = "", SaveThumb = false,
        status = status, downloadStartTime = 0, logID = null, executionId = execution,
    )
    @Test
    fun customUpdaterErrorOutputRemainsAnErrorResponse() {
        val update = UpdateUtil(context)

        val error = update.customUpdateResponse("downloading\nERROR: source rejected\n")
        val current = update.customUpdateResponse("up to date\nyt-dlp is up to date")

        assertEquals(UpdateUtil.YTDLPUpdateStatus.ERROR, error.status)
        assertEquals("ERROR: source rejected", error.message)
        assertEquals(UpdateUtil.YTDLPUpdateStatus.ALREADY_UP_TO_DATE, current.status)
        assertEquals("yt-dlp is up to date", current.message)
        assertTrue(runCatching { update.customUpdateResponse("\n") }.exceptionOrNull() is NoSuchElementException)
    }
}
