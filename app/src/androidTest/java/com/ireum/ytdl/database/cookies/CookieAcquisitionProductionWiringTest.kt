package com.ireum.ytdl.database.cookies

import android.content.Context
import android.content.ContextWrapper
import android.app.Application
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.repository.CookieRepository
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.viewmodel.CookieViewModel
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeMediaAccessProfile
import com.ireum.ytdl.util.terminal.TerminalCommandPlanFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Room-to-runtime cookie acquisition and consumer readiness on the production coordinator. */
@RunWith(AndroidJUnit4::class)
class CookieAcquisitionProductionWiringTest {
    private lateinit var baseContext: Context
    private lateinit var context: Context
    private lateinit var isolatedCache: File
    private lateinit var database: DBManager
    private lateinit var cookies: CookieRepository
    private lateinit var cookieViewModel: CookieViewModel
    private lateinit var preferences: android.content.SharedPreferences
    private var hadUseCookies = false
    private var previousUseCookies = false

    @Before
    fun setUp() {
        baseContext = ApplicationProvider.getApplicationContext()
        val application = ApplicationProvider.getApplicationContext<Application>()
        isolatedCache = File(baseContext.cacheDir, "cookie-acquisition-${UUID.randomUUID()}")
        assertTrue(isolatedCache.mkdirs())
        context = object : ContextWrapper(baseContext) {
            override fun getCacheDir(): File = isolatedCache
        }
        database = Room.inMemoryDatabaseBuilder(baseContext, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        cookies = CookieRepository(database.cookieDao)
        cookieViewModel = CookieViewModel(application, database.cookieDao, context)
        preferences = PreferenceManager.getDefaultSharedPreferences(baseContext)
        hadUseCookies = preferences.contains("use_cookies")
        previousUseCookies = preferences.getBoolean("use_cookies", false)
    }

    @After
    fun tearDown() {
        CookieProjectionCoordinator.beforeProjectionForTesting = null
        CookieProjectionCoordinator.failProjectionForTesting = false
        if (::database.isInitialized) database.close()
        val parent = isolatedCache.parentFile?.canonicalFile
        if (parent == baseContext.cacheDir.canonicalFile) isolatedCache.deleteRecursively()
        val editor = preferences.edit()
        if (hadUseCookies) editor.putBoolean("use_cookies", previousUseCookies)
        else editor.remove("use_cookies")
        assertTrue(editor.commit())
    }

    @Test
    fun roomUpsertAndExactRuntimeProjectionReturnTheSameRequestGeneration() = runBlocking {
        val requestId = "cookie-request-${UUID.randomUUID()}"
        val content = "# request cookie data\n" +
            cookieLine("SID", "fresh-session") + "\n" + httpOnlyCookieLine("HTTP", "http-only-session") + "\n"

        val result = acquire("https://example.com/", "account", content, requestId)

        assertTrue(result is CookieProjectionCoordinator.AcquisitionOutcome.Ready)
        val receipt = result as CookieProjectionCoordinator.AcquisitionOutcome.Ready
        assertEquals(requestId, receipt.requestId)
        assertTrue(receipt.projectionGeneration.isNotBlank())
        val row = requireNotNull(cookies.getByURLDescription("https://example.com/", "account"))
        assertEquals(receipt.roomId, row.id)
        assertEquals(content, row.content)
        assertTrue(row.enabled)
        val file = CookieProjectionCoordinator.requireUsableFile(context)
        assertEquals(
            "$COOKIE_HEADER\n${cookieLine("SID", "fresh-session")}\n${httpOnlyCookieLine("HTTP", "http-only-session")}\n",
            file.readText(),
        )
        assertTrue(CookieAcquisitionHandoff.matches(requestId, receipt.requestId, receipt.projectionGeneration))
    }

    @Test
    fun concurrentAcquisitionsSerializeRoomRowsAndRuntimeProjection() = runBlocking {
        val firstProjectionEntered = CountDownLatch(1)
        val releaseFirstProjection = CountDownLatch(1)
        val secondCallStarted = CountDownLatch(1)
        val secondProjectionEntered = CountDownLatch(1)
        val firstId = "first-${UUID.randomUUID()}"
        val secondId = "second-${UUID.randomUUID()}"
        CookieProjectionCoordinator.beforeProjectionForTesting = { requestId ->
            if (requestId == firstId) {
                firstProjectionEntered.countDown()
                check(releaseFirstProjection.await(5, TimeUnit.SECONDS))
            } else if (requestId == secondId) {
                secondProjectionEntered.countDown()
            }
        }

        try {
            coroutineScope {
                val first = async(Dispatchers.IO) {
                    acquire("https://example.com/one", "one", cookieContent("ONE", "1"), firstId)
                }
                assertTrue(firstProjectionEntered.await(5, TimeUnit.SECONDS))
                val second = async(Dispatchers.IO) {
                    secondCallStarted.countDown()
                    acquire("https://example.com/two", "two", cookieContent("TWO", "2"), secondId)
                }
                assertTrue(secondCallStarted.await(5, TimeUnit.SECONDS))
                assertFalse("second acquisition must wait behind the first projection", secondProjectionEntered.await(250, TimeUnit.MILLISECONDS))
                assertFalse(second.isCompleted)
                releaseFirstProjection.countDown()
                assertTrue(withTimeout(5_000) { first.await() } is CookieProjectionCoordinator.AcquisitionOutcome.Ready)
                assertTrue(secondProjectionEntered.await(5, TimeUnit.SECONDS))
                assertTrue(withTimeout(5_000) { second.await() } is CookieProjectionCoordinator.AcquisitionOutcome.Ready)
            }
        } finally {
            releaseFirstProjection.countDown()
        }

        assertEquals(2, cookies.getAllEnabled().size)
        val projected = CookieProjectionCoordinator.requireUsableFile(context).readText()
        assertTrue(projected.contains(cookieLine("ONE", "1")))
        assertTrue(projected.contains(cookieLine("TWO", "2")))
    }

    @Test
    fun projectionFailureRetiresStaleRuntimeFileAndDoesNotIssueSuccessReceipt() = runBlocking {
        val file = File(context.cacheDir, "cookies.txt")
        file.writeText("$COOKIE_HEADER\n${cookieLine("OLD", "stale")}\n")
        assertTrue(CookieProjectionCoordinator.isUsableCookieFile(file))
        CookieProjectionCoordinator.failProjectionForTesting = true

        val result = acquire(
            "https://example.com/current",
            "current",
            cookieContent("CURRENT", "new"),
            "current-${UUID.randomUUID()}",
        )

        assertEquals(
            CookieProjectionCoordinator.AcquisitionOutcome.Failed(CookieProjectionCoordinator.Failure.WRITE_FAILED),
            result,
        )
        assertNotNull(cookies.getByURLDescription("https://example.com/current", "current"))
        assertFalse("failed projection must not leave a usable stale file", file.exists())
        assertFalse(CookieAcquisitionHandoff.matches("expected", "current", "generation"))
        assertTrue(runCatching { CookieProjectionCoordinator.requireUsableFile(context) }.isFailure)
    }

    @Test
    fun emptyExtractionCannotProduceSuccessOrLeaveAnOlderRuntimeProjection() = runBlocking {
        val file = File(context.cacheDir, "cookies.txt")
        file.writeText("$COOKIE_HEADER\n${cookieLine("OLD", "stale")}\n")

        val result = acquire(
            "https://example.com/empty",
            "empty",
            "# extraction returned no cookie records\n",
            "empty-${UUID.randomUUID()}",
        )

        assertEquals(
            CookieProjectionCoordinator.AcquisitionOutcome.Failed(CookieProjectionCoordinator.Failure.NO_COOKIE_DATA),
            result,
        )
        assertNotNull(cookies.getByURLDescription("https://example.com/empty", "empty"))
        assertFalse(file.exists())
        assertTrue(runCatching { CookieProjectionCoordinator.requireUsableFile(context) }.isFailure)
    }

    @Test
    fun staleOrUnusableCookieProjectionFailsClosedAtTerminalRequestBoundary() {
        assertTrue(preferences.edit().putBoolean("use_cookies", true).commit())
        val cookieFile = File(context.cacheDir, "cookies.txt")
        val cacheRoot = File(isolatedCache, "terminal-cache").apply { mkdirs() }

        fun makePlan() = TerminalCommandPlanFactory.create(
            context = context,
            preferences = preferences,
            command = "https://example.com/terminal-cookie-check",
            taskId = "cookie-check",
            cacheRoot = cacheRoot,
        )

        assertTrue("missing cookie projection must refuse request planning", runCatching(::makePlan).isFailure)
        cookieFile.writeText(COOKIE_HEADER)
        assertTrue("header-only projection must refuse request planning", runCatching(::makePlan).isFailure)
        cookieFile.writeText("$COOKIE_HEADER\n\t\t\t\t\t\t\t\n")
        assertTrue("malformed Netscape records must refuse request planning", runCatching(::makePlan).isFailure)

        cookieFile.writeText("$COOKIE_HEADER\n${cookieLine("SID", "ready")}\n")
        val plan = makePlan()
        val cookieOption = plan.requestOptions.singleOrNull { it.name == "--cookies" }
        assertNotNull(cookieOption)
        assertEquals(cookieFile.absolutePath, cookieOption?.value)
    }

    @Test
    fun downloadRequestCannotSilentlyOmitConfiguredCookies() {
        assertTrue(preferences.edit().putBoolean("use_cookies", true).commit())
        val cookieFile = File(context.cacheDir, "cookies.txt")
        val cacheRoot = File(isolatedCache, "download-cache")
        val item = DownloadItem(
            id = 701L,
            url = "https://example.com/cookie-request",
            title = "cookie request",
            author = "author",
            thumb = "",
            duration = "00:01",
            type = DownloadType.audio,
            format = Format(container = "m4a", format_note = "audio only"),
            container = "m4a",
            downloadSections = "",
            allFormats = mutableListOf(),
            downloadPath = isolatedCache.absolutePath,
            website = "example",
            downloadSize = "",
            playlistTitle = "",
            audioPreferences = AudioPreferences(),
            videoPreferences = VideoPreferences(),
            extraCommands = "",
            customFileNameTemplate = "",
            SaveThumb = false,
            status = "Queued",
            downloadStartTime = 0L,
            logID = null,
            playlistURL = "",
        )
        val ytdlp = YTDLPUtil(context, database.commandTemplateDao)

        val missing = runCatching {
            ytdlp.buildYoutubeDLRequest(
                downloadItem = item,
                mediaAccessProfile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
                useCachedInfoJson = false,
                selectionOnly = true,
                cacheRoot = cacheRoot,
            )
        }
        assertTrue("missing enabled cookie file must stop request construction", missing.isFailure)

        cookieFile.writeText("$COOKIE_HEADER\n${cookieLine("SID", "ready")}\n")
        val request = ytdlp.buildYoutubeDLRequest(
            downloadItem = item,
            mediaAccessProfile = YoutubeMediaAccessProfile.PUBLIC_DEFAULT,
            useCachedInfoJson = false,
            selectionOnly = true,
            cacheRoot = cacheRoot,
        )
        try {
            assertEquals(
                cookieFile.absolutePath,
                request.getArguments("--cookies")?.filterNotNull()?.singleOrNull(),
            )
        } finally {
            request.getArguments("--config-locations")
                ?.filterNotNull()
                ?.forEach { File(it).delete() }
        }
    }

    @Test
    fun handoffRejectsMismatchedRequestAndMissingProjectionGeneration() {
        assertTrue(CookieAcquisitionHandoff.matches("request-1", "request-1", "generation-1"))
        assertFalse(CookieAcquisitionHandoff.matches("request-1", "request-2", "generation-1"))
        assertFalse(CookieAcquisitionHandoff.matches("request-1", "request-1", null))
        assertFalse(CookieAcquisitionHandoff.matches(null, "request-1", "generation-1"))
    }

    private suspend fun acquire(
        url: String,
        description: String,
        content: String,
        requestId: String,
    ): CookieProjectionCoordinator.AcquisitionOutcome = cookieViewModel.acquireAndProject(
        url = url,
        description = description,
        content = content,
        requestId = requestId,
    )

    private fun cookieContent(name: String, value: String): String =
        "# request cookie data\n${cookieLine(name, value)}\n"

    private fun cookieLine(name: String, value: String): String =
        ".example.com\tTRUE\t/\tFALSE\t0\t$name\t$value"

    private fun httpOnlyCookieLine(name: String, value: String): String =
        "#HttpOnly_.example.com\tTRUE\t/\tTRUE\t0\t$name\t$value"

    private companion object {
        const val COOKIE_HEADER = "# Netscape HTTP Cookie File\n# WebView Generated by YTDLnisx\n# This is a generated file! Do not edit."
    }
}
