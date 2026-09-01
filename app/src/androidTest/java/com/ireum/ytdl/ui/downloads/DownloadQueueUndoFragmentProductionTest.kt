package com.ireum.ytdl.ui.downloads

import android.Manifest
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.R as MaterialR
import com.ireum.ytdl.MainActivity
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.PendingUndoCarrier
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.LowQualityRedownloadLedger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.allOf
import java.util.UUID

/**
 * Exercises the real Activity -> ViewPager2 -> child Fragment -> Snackbar
 * wiring.  Repository-only owner tests cannot detect a peer Fragment creating
 * a second activity-scoped owner or a real view generation being destroyed.
 */
@RunWith(AndroidJUnit4::class)
class DownloadQueueUndoFragmentProductionTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var preferences: android.content.SharedPreferences
    private var activityScenario: ActivityScenario<MainActivity>? = null
    private val createdDownloadIds = mutableListOf<Long>()
    private val testPrefix = "undo-ui-${UUID.randomUUID()}"
    private var previousSwipeGestures: Set<String>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = DBManager.getInstance(context)
        preferences = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
        previousSwipeGestures = preferences.getStringSet("swipe_gesture", null)
        val gestures = previousSwipeGestures?.toMutableSet()
            ?: context.resources.getStringArray(R.array.swipe_gestures_values).toMutableSet()
        gestures += "errored"
        gestures += "cancelled"
        gestures += "queued"
        check(preferences.edit().putStringSet("swipe_gesture", gestures).commit())

        grantRuntimePermissions()
        runBlocking {
            database.pendingUndoCarrierDao.getAll()
                .filter { it.snapshotJson.contains(testPrefix) }
                .forEach { database.pendingUndoCarrierDao.delete(it.token) }
            database.downloadDao.getAllDownloadsList()
                .filter { it.url.contains(testPrefix) }
                .forEach { database.downloadDao.delete(it.id) }
        }
        DownloadRepository.clearLivePendingRemovalTokensForTest()
    }

    @After
    fun tearDown() {
        runBlocking {
            activityScenario?.close()
            activityScenario = null
            LowQualityRedownloadLedger.cancelAllAbandonedUndoConvergenceJobsAndJoinForTesting()
            LowQualityRedownloadLedger.cancelAllCancellationConvergenceJobsAndJoinForTesting()
            LowQualityRedownloadLedger.cancelAllEnqueueConvergenceJobsAndJoinForTesting()
            DownloadExecutionRecovery.cancelAllRecoveryJobsAndJoinForTesting()
            DownloadRepository.clearLivePendingRemovalTokensForTest()
            database.pendingUndoCarrierDao.getAll()
                .filter { it.snapshotJson.contains(testPrefix) }
                .forEach { database.pendingUndoCarrierDao.delete(it.token) }
            database.downloadDao.getAllDownloadsList()
                .filter { it.url.contains(testPrefix) }
                .forEach { database.downloadDao.delete(it.id) }
            createdDownloadIds.forEach { database.downloadDao.delete(it) }
            previousSwipeGestures?.let {
                preferences.edit().putStringSet("swipe_gesture", it).commit()
            }
        }
    }

    @Test
    fun activityScopedPeerOwnerDoesNotInvalidatePublishedRestore() = runBlocking {
        val id = insertDownload(DownloadRepository.Status.Error)
        val scenario = launchQueueAt(4)

        awaitDownloadVisible(id)
        swipeFirstVisibleDownload(id)
        awaitDatabase { database.downloadDao.getNullableDownloadById(id) == null }
        val carrier = awaitRemovalCarrier(id)
        assertEquals(PendingUndoCarrier.PUBLISHED_PRESENTATION, carrier.presentationState)
        assertNull(database.downloadDao.getNullableDownloadById(id))

        // Page 5 is a real peer using the same activity-scoped DownloadViewModel.
        // Creating it must not rotate or invalidate the Error page's owner.
        selectQueuePage(scenario, 5)
        awaitFragment<SavedDownloadsFragment>(scenario)

        onView(
            allOf(
                withId(MaterialR.id.snackbar_action),
                withText(R.string.undo),
            )
        ).perform(androidx.test.espresso.action.ViewActions.click())

        awaitDatabase {
            database.downloadDao.getNullableDownloadById(id)?.status ==
                DownloadRepository.Status.Error.name &&
                database.pendingUndoCarrierDao.get(carrier.token) == null
        }
        assertEquals(DownloadRepository.Status.Error.name, database.downloadDao.getNullableDownloadById(id)?.status)
    }

    @Test
    fun destroyedPeerViewAbandonsOnlyItsOwnerAndKeepsSecondOwnerUsable() = runBlocking {
        val cancelledId = insertDownload(DownloadRepository.Status.Cancelled)
        val savedId = insertDownload(DownloadRepository.Status.Saved)
        val scenario = launchQueueAt(3)

        awaitDownloadVisible(cancelledId)
        swipeFirstVisibleDownload(cancelledId)
        awaitDatabase { database.downloadDao.getNullableDownloadById(cancelledId) == null }
        val abandonedCarrier = awaitRemovalCarrier(cancelledId)
        assertEquals(PendingUndoCarrier.PUBLISHED_PRESENTATION, abandonedCarrier.presentationState)

        // Move to Saved, then explicitly remove only the real Cancelled child
        // Fragment.  This invokes its production onDestroyView path while the
        // Saved peer remains the active presentation owner.
        selectQueuePage(scenario, 5)
        awaitFragment<SavedDownloadsFragment>(scenario)
        destroyFragmentForTesting<CancelledDownloadsFragment>(scenario)
        awaitDatabase {
            database.pendingUndoCarrierDao.get(abandonedCarrier.token)?.presentationState !=
                PendingUndoCarrier.PUBLISHED_PRESENTATION ||
                !DownloadRepository.isLivePendingRemovalToken(abandonedCarrier.token)
        }

        // The exact peer owner remains usable after page 3's owner is gone.
        awaitDownloadVisible(savedId)
        swipeFirstVisibleDownload(savedId)
        val savedCarrier = awaitRemovalCarrier(savedId)
        onView(
            allOf(
                withId(MaterialR.id.snackbar_action),
                withText(R.string.undo),
            )
        ).perform(androidx.test.espresso.action.ViewActions.click())
        awaitDatabase {
            database.downloadDao.getNullableDownloadById(savedId)?.status ==
                DownloadRepository.Status.Saved.name &&
                database.pendingUndoCarrierDao.get(savedCarrier.token) == null
        }

        // The original page's owner was abandoned/recovered; it did not
        // damage the second owner's exact carrier or restore another row.
        assertTrue(
            database.downloadDao.getNullableDownloadById(cancelledId) == null ||
                database.downloadDao.getNullableDownloadById(cancelledId)?.status ==
                    DownloadRepository.Status.Cancelled.name
        )
    }

    @Test
    fun recreatedDownloadQueueViewDoesNotReuseOldUndoPresentation() = runBlocking {
        val id = insertDownload(DownloadRepository.Status.Error)
        val scenario = launchQueueAt(4)
        awaitDownloadVisible(id)
        swipeFirstVisibleDownload(id)
        awaitDatabase { database.downloadDao.getNullableDownloadById(id) == null }
        val carrier = awaitRemovalCarrier(id)
        assertEquals(PendingUndoCarrier.PUBLISHED_PRESENTATION, carrier.presentationState)

        scenario.recreate()
        // MainActivity/DownloadQueueMainFragment intentionally does not save
        // the pager item; reselect the production page after recreation.
        selectQueuePage(scenario, 4)

        // The old view generation is no longer allowed to remain positive UI
        // authority.  Recovery may terminalize it, but it may not attach the
        // old owner to the recreated page or recreate the deleted Download.
        awaitDatabase {
            !DownloadRepository.isLivePendingRemovalToken(carrier.token) &&
                !DownloadRepository.isProcessLocalPendingUndoAuthority(carrier.token)
        }
        assertNull(database.downloadDao.getNullableDownloadById(id))
        assertFalse(DownloadRepository.isLivePendingRemovalToken(carrier.token))
    }

    @Test
    fun queuedLinkedCancellationUndoUsesRealFragmentOwner() = runBlocking {
        val operation = LowQualityRedownloadRepository(database).createOrReconnect(
            now = System.currentTimeMillis(),
        )
        val historyId = 900_000L + createdDownloadIds.size
        val id = insertDownload(
            DownloadRepository.Status.Queued,
            playlistUrl = HistoryRedownloadMarker.regular(historyId),
        )
        database.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = historyId,
                selected = true,
                itemState = LowQualityRedownloadItemState.CHECKING.name,
            )
        )
        assertEquals(
            1,
            database.lowQualityRedownloadDao.linkQueuedDownload(
                operation.operationId,
                historyId,
                id,
                System.currentTimeMillis(),
            )
        )
        database.downloadDao.setStatus(id, DownloadRepository.Status.Queued.name)

        launchQueueAt(1)
        awaitDownloadVisible(id)
        swipeFirstVisibleDownload(id)
        onView(withText(R.string.ok)).perform(androidx.test.espresso.action.ViewActions.click())

        val carrier = awaitCancellationCarrier(id)
        assertEquals(PendingUndoCarrier.PUBLISHED_PRESENTATION, carrier.presentationState)
        onView(
            allOf(
                withId(MaterialR.id.snackbar_action),
                withText(R.string.undo),
            )
        ).perform(androidx.test.espresso.action.ViewActions.click())

        awaitDatabase {
            database.pendingUndoCarrierDao.get(carrier.token) == null &&
                database.downloadDao.getNullableDownloadById(id)?.status ==
                    DownloadRepository.Status.Queued.name
        }
    }

    private fun insertDownload(
        status: DownloadRepository.Status,
        playlistUrl: String = "https://example.com/$testPrefix/${createdDownloadIds.size}",
    ): Long {
        val id = runBlocking {
            database.downloadDao.insert(
                DownloadItem(
                    id = 0L,
                    url = "https://example.com/$testPrefix/${createdDownloadIds.size}",
                    title = "$testPrefix-${status.name}",
                    author = "test-author",
                    thumb = "",
                    duration = "1:00",
                    type = DownloadType.video,
                    format = Format(
                        format_id = "18",
                        container = "mp4",
                        vcodec = "avc1",
                        acodec = "mp4a",
                    ),
                    container = "mp4",
                    downloadSections = "",
                    allFormats = arrayListOf(),
                    downloadPath = context.filesDir.resolve(testPrefix).absolutePath,
                    website = "example.com",
                    downloadSize = "",
                    playlistTitle = "",
                    audioPreferences = AudioPreferences(),
                    videoPreferences = com.ireum.ytdl.database.models.VideoPreferences(),
                    extraCommands = "",
                    customFileNameTemplate = "%(title)s",
                    SaveThumb = false,
                    status = status.name,
                    downloadStartTime = 0L,
                    logID = null,
                    playlistURL = playlistUrl,
                )
            )
        }
        createdDownloadIds += id
        return id
    }

    private fun launchQueueAt(page: Int): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        activityScenario = scenario
        scenario.onActivity { activity ->
            val bottomNavigation = activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                R.id.bottomNavigationView,
            )
            bottomNavigation.selectedItemId = R.id.historyFragment
        }
        awaitUi {
            var atHistory = false
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager.findFragmentById(R.id.frame_layout)
                    as androidx.navigation.fragment.NavHostFragment
                atHistory = navHost.navController.currentDestination?.id == R.id.historyFragment
            }
            atHistory
        }
        scenario.onActivity { activity ->
            val navHost = activity.supportFragmentManager.findFragmentById(R.id.frame_layout)
                as androidx.navigation.fragment.NavHostFragment
            navHost.navController.navigate(R.id.downloadQueueMainFragment)
        }
        selectQueuePage(scenario, page)
        return scenario
    }

    private fun selectQueuePage(
        scenario: ActivityScenario<MainActivity>,
        page: Int,
    ) {
        scenario.onActivity { activity ->
            activity.findViewById<ViewPager2>(R.id.download_viewpager)
                .setCurrentItem(page, false)
        }
        awaitQueuePage(scenario, page)
    }

    private fun awaitQueuePage(
        scenario: ActivityScenario<MainActivity>,
        page: Int,
    ) {
        awaitUi {
            var selected = false
            scenario.onActivity { activity ->
                val pager = activity.findViewById<ViewPager2>(R.id.download_viewpager)
                selected = pager.currentItem == page
            }
            selected
        }
        awaitUi {
            var ready = false
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager.findFragmentById(R.id.frame_layout)
                    as? androidx.navigation.fragment.NavHostFragment
                val queue = navHost?.childFragmentManager?.primaryNavigationFragment
                    as? DownloadQueueMainFragment
                ready = queue?.childFragmentManager?.fragments?.any {
                    it.view != null && when (page) {
                        1 -> it is QueuedDownloadsFragment
                        3 -> it is CancelledDownloadsFragment
                        4 -> it is ErroredDownloadsFragment
                        5 -> it is SavedDownloadsFragment
                        else -> true
                    }
                } == true
            }
            ready
        }
    }

    private inline fun <reified T : androidx.fragment.app.Fragment> awaitFragment(
        scenario: ActivityScenario<MainActivity>,
    ) {
        awaitUi {
            var found = false
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager.findFragmentById(R.id.frame_layout)
                    as? androidx.navigation.fragment.NavHostFragment
                val queue = navHost?.childFragmentManager?.primaryNavigationFragment
                    as? DownloadQueueMainFragment
                found = queue?.childFragmentManager?.fragments?.any { it is T && it.view != null } == true
            }
            found
        }
    }

    private inline fun <reified T : androidx.fragment.app.Fragment> destroyFragmentForTesting(
        scenario: ActivityScenario<MainActivity>,
    ) {
        var fragment: T? = null
        scenario.onActivity { activity ->
            val navHost = activity.supportFragmentManager.findFragmentById(R.id.frame_layout)
                as? androidx.navigation.fragment.NavHostFragment
            val queue = checkNotNull(
                navHost?.childFragmentManager?.primaryNavigationFragment
                    as? DownloadQueueMainFragment
            )
            val selected = checkNotNull(
                queue.childFragmentManager.fragments.firstOrNull { it is T } as? T
            ) { "${T::class.java.simpleName} was not created" }
            fragment = selected
            queue.childFragmentManager.beginTransaction()
                .remove(selected)
                .commitNow()
        }
        awaitUi { fragment?.view == null }
    }

    private fun swipeFirstVisibleDownload(id: Long) {
        val bounds = Rect()
        activityScenario?.onActivity { activity ->
            val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                R.id.download_recyclerview,
            )
            val holder = recycler.findViewHolderForAdapterPosition(0)
                ?: error("RecyclerView has no bound download card")
            check(holder.itemView.tag == id.toString()) {
                "Unexpected first card tag: ${holder.itemView.tag}"
            }
            check(holder.itemView.getGlobalVisibleRect(bounds)) {
                "Download card is not globally visible"
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        val y = bounds.centerY().toFloat()
        val startX = (bounds.right - 12).toFloat()
        val endX = (bounds.left + 12).toFloat()
        fun send(action: Int, eventTime: Long, x: Float) {
            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                x,
                y,
                0,
            )
            try {
                instrumentation.sendPointerSync(event)
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, downTime, startX)
        repeat(30) { step ->
            Thread.sleep(20L)
            val eventTime = SystemClock.uptimeMillis()
            val fraction = (step + 1) / 30f
            send(
                MotionEvent.ACTION_MOVE,
                eventTime,
                startX + (endX - startX) * fraction,
            )
        }
        Thread.sleep(20L)
        send(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), endX)
    }

    private fun awaitDownloadVisible(id: Long) {
        awaitUi {
            var visible = false
            activityScenario?.onActivity { activity ->
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.download_recyclerview)
                visible = recycler.findViewHolderForAdapterPosition(0)?.itemView?.tag == id.toString()
            }
            visible
        }
    }

    private fun awaitRemovalCarrier(id: Long): PendingUndoCarrier = awaitValue {
        database.pendingUndoCarrierDao.getAll().firstOrNull {
            it.kind == PendingUndoCarrier.REMOVAL_KIND &&
                it.snapshotJson.contains("$testPrefix") &&
                it.snapshotJson.contains(id.toString()) &&
                it.presentationState == PendingUndoCarrier.PUBLISHED_PRESENTATION
        }
    }

    private fun awaitCancellationCarrier(id: Long): PendingUndoCarrier {
        return awaitValue {
            val token = database.lowQualityRedownloadDao.getItemByDownloadId(id)?.reasonCode
            token?.let { database.pendingUndoCarrierDao.get(it) }
                ?.takeIf {
                    it.kind == PendingUndoCarrier.CANCELLATION_KIND &&
                        it.presentationState == PendingUndoCarrier.PUBLISHED_PRESENTATION
                }
        }
    }

    private fun <T> awaitValue(read: suspend () -> T?): T {
        var value: T? = null
        awaitUi {
            value = runBlocking { read() }
            value != null
        }
        return checkNotNull(value)
    }

    private fun awaitDatabase(condition: suspend () -> Boolean) {
        awaitUi {
            runBlocking { condition() }
        }
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return
            Thread.sleep(50L)
        }
        throw AssertionError("Timed out waiting for production UI/database state")
    }

    private fun grantRuntimePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.GRANT_RUNTIME_PERMISSIONS")
        try {
            listOfNotNull(
                Manifest.permission.READ_MEDIA_VIDEO.takeIf {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                },
                Manifest.permission.READ_MEDIA_AUDIO.takeIf {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                },
                Manifest.permission.POST_NOTIFICATIONS.takeIf {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                },
            ).forEach { permission ->
                runCatching {
                    automation.grantRuntimePermission(context.packageName, permission)
                }
            }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }
}
