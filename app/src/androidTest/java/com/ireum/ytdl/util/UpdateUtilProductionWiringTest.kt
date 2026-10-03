package com.ireum.ytdl.util

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.App
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.yausername.aria2c.Aria2c
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val ownedPreferenceKeys = listOf(
        "ytdlp_source",
        "ytdlp_source_label",
        "ytdlp_source_generation",
        "ytdlp_committed_source_generation",
        "ytdlp_committed_source",
        "ytdlp_committed_result",
        "ytdlp_pending_source_generation",
        "ytdlp_pending_source",
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        awaitRuntimeReadiness()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalValues = ownedPreferenceKeys.associateWith { key ->
            if (!preferences.contains(key)) null else if (key.endsWith("_generation")) {
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
