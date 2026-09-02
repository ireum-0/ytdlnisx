package com.ireum.ytdl.ui.downloads

import android.Manifest
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
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
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
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
import org.hamcrest.Matchers.containsString
import java.util.UUID

private fun compactIdentity(value: Any?): String =
    value?.let { "${it.javaClass.simpleName}@${System.identityHashCode(it)}" } ?: "null"

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

    private data class QueueFragmentLifecycleEvent(
        val kind: String,
        val fragment: Fragment,
        val view: View?,
        val savedTokenLive: Boolean?,
        val savedTokenProcessLocal: Boolean?,
        val savedResolverInFlight: Boolean?,
        val savedSnackbarActionVisible: Boolean,
        val savedSnackbarMessage: String?,
    ) {
        companion object {
            const val SAVED_VIEW_CREATED = "Saved.onFragmentViewCreated"
            const val SAVED_VIEW_DESTROYED = "Saved.onFragmentViewDestroyed"
            const val CANCELLED_VIEW_CREATED = "Cancelled.onFragmentViewCreated"
            const val CANCELLED_VIEW_DESTROYED = "Cancelled.onFragmentViewDestroyed"
        }

        override fun toString(): String =
            "$kind:${compactIdentity(fragment)}:${compactIdentity(view)}" +
                ":savedAuthority=$savedTokenLive/$savedTokenProcessLocal/$savedResolverInFlight" +
                ":savedSnackbar=$savedSnackbarActionVisible/$savedSnackbarMessage"
    }

    private data class UndoPeerDiagnostic(
        val savedFragmentPresent: Boolean,
        val savedViewPresent: Boolean,
        val savedViewDestroyed: Boolean,
        val savedFragmentLifecycleState: String,
        val savedViewLifecycleState: String,
        val savedOwnerIdentity: String,
        val cancelledOwnerIdentity: String,
        val savedOwnerActive: Boolean,
        val savedTokenLive: Boolean,
        val savedTokenProcessLocal: Boolean,
        val savedResolverInFlight: Boolean,
        val savedAuthorityKnown: Boolean,
        val savedCarrierCommitted: Boolean,
        val savedCarrierPresentationState: String?,
        val savedCarrierResolutionIntent: String?,
        val savedSnackbarActionVisible: Boolean,
        val savedSnackbarMessage: String?,
        val abandonedTokenLive: Boolean,
        val abandonedTokenProcessLocal: Boolean,
        val cancelledOwnerActive: Boolean,
        val cancelledViewPresent: Boolean,
        val cancelledViewDestroyed: Boolean,
        val abandonedCarrierPresentationState: String?,
        val abandonedCarrierResolutionIntent: String?,
        val lifecycleEvents: String,
    ) {
        override fun toString(): String =
            "savedFragmentPresent=$savedFragmentPresent," +
                "savedViewPresent=$savedViewPresent," +
                "savedViewDestroyed=$savedViewDestroyed," +
            "savedFragmentState=$savedFragmentLifecycleState," +
                "savedViewState=$savedViewLifecycleState," +
                "savedOwner=$savedOwnerIdentity," +
                "cancelledOwner=$cancelledOwnerIdentity," +
                "savedOwnerActive=$savedOwnerActive," +
                "savedLive=$savedTokenLive," +
                "savedProcessLocal=$savedTokenProcessLocal," +
                "savedResolverInFlight=$savedResolverInFlight," +
                "savedAuthorityKnown=$savedAuthorityKnown," +
                "savedCarrierCommitted=$savedCarrierCommitted," +
                "savedCarrier=$savedCarrierPresentationState/$savedCarrierResolutionIntent," +
                "savedSnackbar=$savedSnackbarActionVisible/$savedSnackbarMessage," +
                "abandonedLive=$abandonedTokenLive," +
                "abandonedProcessLocal=$abandonedTokenProcessLocal," +
                "cancelledOwnerActive=$cancelledOwnerActive," +
                "cancelledViewPresent=$cancelledViewPresent," +
                "cancelledViewDestroyed=$cancelledViewDestroyed," +
                "abandonedCarrier=$abandonedCarrierPresentationState/$abandonedCarrierResolutionIntent," +
                "events=[$lifecycleEvents]"
    }

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
            val scenario = activityScenario
            var viewModel: DownloadViewModel? = null
            scenario?.onActivity { activity ->
                viewModel = androidx.lifecycle.ViewModelProvider(activity)[DownloadViewModel::class.java]
            }
            scenario?.close()
            activityScenario = null
            // ActivityScenario invokes ViewModel.onCleared() asynchronously
            // relative to this test thread.  Join the production ViewModel
            // scope before clearing process-local Undo state so a previous
            // test cannot finish a stale resolver during the next test.
            viewModel?.clearForTesting()
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
        val carrier = awaitRemovalCarrier(id, expectedPage = 4)
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
        val lifecycleEvents = mutableListOf<QueueFragmentLifecycleEvent>()
        var savedTokenForLifecycle: String? = null
        val lifecycleCallbacks = queueFragmentLifecycleCallbacks(
            events = lifecycleEvents,
            savedTokenProvider = { savedTokenForLifecycle },
        )
        var queueChildFragmentManager: FragmentManager? = null
        val scenario = launchQueueAt(
            page = 3,
            onQueueChildFragmentManagerReady = { fragmentManager ->
                queueChildFragmentManager = fragmentManager
                fragmentManager.registerFragmentLifecycleCallbacks(
                    lifecycleCallbacks,
                    false,
                )
            },
        )
        scenario.onActivity { activity ->
            // Keep all peer pages materialized while each page publishes its
            // own capability.  The peer is later evicted by ViewPager2 itself.
            activity.findViewById<ViewPager2>(R.id.download_viewpager)
                .offscreenPageLimit = 5
        }

        try {
            awaitDownloadVisible(cancelledId)
            swipeFirstVisibleDownload(cancelledId)
            awaitDatabase { database.downloadDao.getNullableDownloadById(cancelledId) == null }
            val abandonedCarrier = awaitRemovalCarrier(cancelledId, expectedPage = 3)
            assertEquals(PendingUndoCarrier.PUBLISHED_PRESENTATION, abandonedCarrier.presentationState)

            // Move to Saved while the peer pages remain materialized, then
            // publish the Saved capability under its independent owner.
            selectQueuePage(scenario, 5)
            awaitFragment<SavedDownloadsFragment>(scenario)
            awaitDownloadVisible(savedId)
            swipeFirstVisibleDownload(savedId)
            val savedCarrier = awaitRemovalCarrier(savedId, expectedPage = 5)
            savedTokenForLifecycle = savedCarrier.token

            val savedFragment = checkNotNull(findQueueFragment<SavedDownloadsFragment>(scenario)) {
                "SavedDownloadsFragment was not materialized before peer eviction"
            }
            val savedView = checkNotNull(savedFragment.view) {
                "SavedDownloadsFragment view was not materialized before peer eviction"
            }
            val cancelledFragment = checkNotNull(findQueueFragment<CancelledDownloadsFragment>(scenario)) {
                "CancelledDownloadsFragment was not retained while offscreen limit was high"
            }
            val cancelledView = checkNotNull(cancelledFragment.view) {
                "CancelledDownloadsFragment view was not retained while offscreen limit was high"
            }
            val savedOwner = checkNotNull(undoPresentationOwnerForTest(savedFragment)) {
                "Saved view did not expose its exact UndoPresentationOwner"
            }
            val cancelledOwner = checkNotNull(undoPresentationOwnerForTest(cancelledFragment)) {
                "Cancelled view did not expose its exact UndoPresentationOwner"
            }
            var activityViewModel: DownloadViewModel? = null
            scenario.onActivity { activity ->
                activityViewModel = androidx.lifecycle.ViewModelProvider(activity)[DownloadViewModel::class.java]
            }
            val viewModel = checkNotNull(activityViewModel)

            val savedCarrierBeforeEviction = database.pendingUndoCarrierDao.get(savedCarrier.token)
                ?: throw AssertionError(
                    "Saved carrier disappeared before peer eviction: " +
                        captureUndoPeerDiagnostic(
                            scenario = scenario,
                            savedFragment = savedFragment,
                            savedView = savedView,
                            savedOwner = savedOwner,
                            cancelledFragment = cancelledFragment,
                            cancelledView = cancelledView,
                            cancelledOwner = cancelledOwner,
                            viewModel = viewModel,
                            savedToken = savedCarrier.token,
                            abandonedToken = abandonedCarrier.token,
                            lifecycleEvents = lifecycleEvents,
                        )
                )
            assertEquals(
                PendingUndoCarrier.PUBLISHED_PRESENTATION,
                savedCarrierBeforeEviction.presentationState,
            )
            assertEquals(
                PendingUndoCarrier.UNRESOLVED_INTENT,
                savedCarrierBeforeEviction.resolutionIntent,
            )
            assertTrue(
                "Saved carrier was not process-local authority before peer eviction",
                DownloadRepository.isProcessLocalPendingUndoAuthority(savedCarrier.token),
            )
            assertTrue(
                "Saved carrier was not live UI authority before peer eviction",
                DownloadRepository.isLivePendingRemovalToken(savedCarrier.token),
            )
            assertTrue(
                "Saved exact owner was not active before peer eviction",
                viewModel.isUndoPresentationOwnerActive(savedOwner),
            )
            assertFalse(
                "Saved and Cancelled unexpectedly share an Undo owner: " +
                    "saved=${savedOwner.id}/${savedOwner.generation}, " +
                    "cancelled=${cancelledOwner.id}/${cancelledOwner.generation}",
                savedOwner == cancelledOwner,
            )
            val beforePeerEvictionDiagnostic = captureUndoPeerDiagnostic(
                scenario = scenario,
                savedFragment = savedFragment,
                savedView = savedView,
                savedOwner = savedOwner,
                cancelledFragment = cancelledFragment,
                cancelledView = cancelledView,
                cancelledOwner = cancelledOwner,
                viewModel = viewModel,
                savedToken = savedCarrier.token,
                abandonedToken = abandonedCarrier.token,
                lifecycleEvents = lifecycleEvents,
            )
            assertTrue(
                "Cancelled peer was not still authoritative before ViewPager2 eviction: " +
                    beforePeerEvictionDiagnostic,
                beforePeerEvictionDiagnostic.cancelledOwnerActive &&
                    beforePeerEvictionDiagnostic.cancelledViewPresent,
            )

            // Reducing the retention range while Saved is current is the real
            // FragmentStateAdapter/ViewPager2 path for destroying only the
            // distant Cancelled view.  The wait observes only that exact
            // lifecycle callback; it never polls Saved authority.
            scenario.onActivity { activity ->
                val pager = activity.findViewById<ViewPager2>(R.id.download_viewpager)
                // RecyclerView may keep an already detached
                // FragmentStateAdapter holder in its item cache.  That cache
                // delays onViewRecycled/onDestroyView even after the
                // offscreen range has shrunk.  Disable only this test's cache
                // so the real adapter eviction reaches the production view
                // lifecycle synchronously and observably.
                (pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
                    ?.setItemViewCacheSize(0)
                pager.offscreenPageLimit = 1
            }
            try {
                awaitUi {
                    lifecycleEvents.any {
                        it.kind == QueueFragmentLifecycleEvent.CANCELLED_VIEW_DESTROYED &&
                            it.fragment === cancelledFragment
                    }
                }
            } catch (failure: AssertionError) {
                val timeoutDiagnostic = captureUndoPeerDiagnostic(
                    scenario = scenario,
                    savedFragment = savedFragment,
                    savedView = savedView,
                    savedOwner = savedOwner,
                    cancelledFragment = cancelledFragment,
                    cancelledView = cancelledView,
                    cancelledOwner = cancelledOwner,
                    viewModel = viewModel,
                    savedToken = savedCarrier.token,
                    abandonedToken = abandonedCarrier.token,
                    lifecycleEvents = lifecycleEvents,
                )
                throw AssertionError(
                    "ViewPager2 did not destroy the exact Cancelled peer view: " +
                        timeoutDiagnostic,
                    failure,
                )
            }

            // This is a one-shot classification immediately after the real
            // Cancelled onDestroyView callback.  A later poll is deliberately
            // not allowed to hide a transient Saved-owner loss.
            val diagnostic = captureUndoPeerDiagnostic(
                scenario = scenario,
                savedFragment = savedFragment,
                savedView = savedView,
                savedOwner = savedOwner,
                cancelledFragment = cancelledFragment,
                cancelledView = cancelledView,
                cancelledOwner = cancelledOwner,
                viewModel = viewModel,
                savedToken = savedCarrier.token,
                abandonedToken = abandonedCarrier.token,
                lifecycleEvents = lifecycleEvents,
            )
            assertTrue("Undo peer lifecycle classification: $diagnostic", diagnostic.cancelledViewDestroyed)
            assertTrue("Undo peer lifecycle classification: $diagnostic", diagnostic.savedFragmentPresent)
            assertTrue("Undo peer lifecycle classification: $diagnostic", diagnostic.savedViewPresent)
            assertFalse("Undo peer lifecycle classification: $diagnostic", diagnostic.savedViewDestroyed)
            assertTrue("Undo peer lifecycle classification: $diagnostic", diagnostic.savedOwnerActive)
            assertTrue("Undo peer lifecycle classification: $diagnostic", diagnostic.savedTokenLive)
            assertTrue("Undo peer lifecycle classification: $diagnostic", diagnostic.savedTokenProcessLocal)
            assertFalse("Undo peer lifecycle classification: $diagnostic", diagnostic.abandonedTokenLive)
            assertFalse("Undo peer lifecycle classification: $diagnostic", diagnostic.abandonedTokenProcessLocal)
            assertFalse("Undo peer lifecycle classification: $diagnostic", diagnostic.cancelledOwnerActive)
            assertFalse("Undo peer lifecycle classification: $diagnostic", diagnostic.cancelledViewPresent)

            // The exact Saved Snackbar action must still resolve only its own
            // carrier after the Cancelled owner has been abandoned.
            onView(
                allOf(
                    withId(MaterialR.id.snackbar_action),
                    withText(R.string.undo),
                    isDescendantOfA(
                        hasDescendant(withText(containsString("$testPrefix-Saved")))
                    ),
                )
            ).perform(androidx.test.espresso.action.ViewActions.click())
            awaitDatabase {
                database.downloadDao.getNullableDownloadById(savedId)?.status ==
                    DownloadRepository.Status.Saved.name &&
                    database.pendingUndoCarrierDao.get(savedCarrier.token) == null
            }

            // The abandoned peer did not restore or corrupt the Saved row.
            assertTrue(
                database.downloadDao.getNullableDownloadById(cancelledId) == null ||
                    database.downloadDao.getNullableDownloadById(cancelledId)?.status ==
                        DownloadRepository.Status.Cancelled.name
            )
        } finally {
            queueChildFragmentManager?.unregisterFragmentLifecycleCallbacks(lifecycleCallbacks)
        }
    }

    @Test
    fun recreatedDownloadQueueViewDoesNotReuseOldUndoPresentation() = runBlocking {
        val id = insertDownload(DownloadRepository.Status.Error)
        val scenario = launchQueueAt(4)
        awaitDownloadVisible(id)
        swipeFirstVisibleDownload(id)
        awaitDatabase { database.downloadDao.getNullableDownloadById(id) == null }
        val carrier = awaitRemovalCarrier(id, expectedPage = 4)
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
        awaitUi {
            runCatching {
                onView(withText(R.string.ok)).check(matches(isDisplayed()))
            }.isSuccess
        }
        onView(withText(R.string.ok)).perform(androidx.test.espresso.action.ViewActions.click())

        val carrier = awaitCancellationCarrier(id, expectedPage = 1)
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

    private fun launchQueueAt(
        page: Int,
        onQueueChildFragmentManagerReady: ((FragmentManager) -> Unit)? = null,
    ): ActivityScenario<MainActivity> {
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
        if (onQueueChildFragmentManagerReady != null) {
            awaitUi {
                var ready = false
                scenario.onActivity { activity ->
                    val navHost = activity.supportFragmentManager.findFragmentById(R.id.frame_layout)
                        as? androidx.navigation.fragment.NavHostFragment
                    val queue = navHost?.childFragmentManager?.primaryNavigationFragment
                        as? DownloadQueueMainFragment
                    ready = queue?.view?.findViewById<ViewPager2>(R.id.download_viewpager) != null
                }
                ready
            }
            scenario.onActivity { activity ->
                val navHost = activity.supportFragmentManager.findFragmentById(R.id.frame_layout)
                    as? androidx.navigation.fragment.NavHostFragment
                val queue = checkNotNull(
                    navHost?.childFragmentManager?.primaryNavigationFragment
                        as? DownloadQueueMainFragment
                )
                onQueueChildFragmentManagerReady(queue.childFragmentManager)
            }
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

    private inline fun <reified T : Fragment> findQueueFragment(
        scenario: ActivityScenario<MainActivity>,
    ): T? {
        var found: T? = null
        scenario.onActivity { activity ->
            val navHost = activity.supportFragmentManager.findFragmentById(R.id.frame_layout)
                as? androidx.navigation.fragment.NavHostFragment
            val queue = navHost?.childFragmentManager?.primaryNavigationFragment
                as? DownloadQueueMainFragment
            found = queue?.childFragmentManager?.fragments
                ?.firstOrNull { it is T } as? T
        }
        return found
    }

    private fun queueFragmentLifecycleCallbacks(
        events: MutableList<QueueFragmentLifecycleEvent>,
        savedTokenProvider: () -> String?,
    ): FragmentManager.FragmentLifecycleCallbacks =
        object : FragmentManager.FragmentLifecycleCallbacks() {
            private fun isTracked(fragment: Fragment): Boolean =
                fragment is SavedDownloadsFragment || fragment is CancelledDownloadsFragment

            private fun event(
                kind: String,
                fragment: Fragment,
                view: View?,
            ): QueueFragmentLifecycleEvent {
                val savedToken = savedTokenProvider()
                val activity = fragment.activity
                val snackbarAction = activity?.window?.decorView
                    ?.findViewById<View>(MaterialR.id.snackbar_action)
                val snackbarMessage = activity?.window?.decorView
                    ?.findViewById<View>(MaterialR.id.snackbar_text)
                    ?.let { (it as? android.widget.TextView)?.text?.toString() }
                return QueueFragmentLifecycleEvent(
                    kind = kind,
                    fragment = fragment,
                    view = view,
                    savedTokenLive = savedToken?.let(DownloadRepository::isLivePendingRemovalToken),
                    savedTokenProcessLocal = savedToken?.let(
                        DownloadRepository::isProcessLocalPendingUndoAuthority
                    ),
                    savedResolverInFlight = savedToken?.let(DownloadRepository::isUndoResolverInFlight),
                    savedSnackbarActionVisible = snackbarAction?.isShown == true,
                    savedSnackbarMessage = snackbarMessage,
                )
            }

            override fun onFragmentViewCreated(
                fragmentManager: FragmentManager,
                fragment: Fragment,
                view: View,
                savedInstanceState: Bundle?,
            ) {
                if (!isTracked(fragment)) return
                val kind = when (fragment) {
                    is SavedDownloadsFragment -> QueueFragmentLifecycleEvent.SAVED_VIEW_CREATED
                    is CancelledDownloadsFragment -> QueueFragmentLifecycleEvent.CANCELLED_VIEW_CREATED
                    else -> return
                }
                events += event(kind, fragment, view)
            }

            override fun onFragmentViewDestroyed(
                fragmentManager: FragmentManager,
                fragment: Fragment,
            ) {
                if (!isTracked(fragment)) return
                val kind = when (fragment) {
                    is SavedDownloadsFragment -> QueueFragmentLifecycleEvent.SAVED_VIEW_DESTROYED
                    is CancelledDownloadsFragment -> QueueFragmentLifecycleEvent.CANCELLED_VIEW_DESTROYED
                    else -> return
                }
                events += event(kind, fragment, null)
            }
        }

    private fun undoPresentationOwnerForTest(
        fragment: Fragment,
    ): DownloadRepository.UndoPresentationOwner? {
        val field = fragment.javaClass.getDeclaredField("undoPresentationOwner")
        field.isAccessible = true
        return field.get(fragment) as? DownloadRepository.UndoPresentationOwner
    }

    private suspend fun captureUndoPeerDiagnostic(
        scenario: ActivityScenario<MainActivity>,
        savedFragment: SavedDownloadsFragment,
        savedView: View,
        savedOwner: DownloadRepository.UndoPresentationOwner,
        cancelledFragment: CancelledDownloadsFragment,
        cancelledView: View,
        cancelledOwner: DownloadRepository.UndoPresentationOwner,
        viewModel: DownloadViewModel,
        savedToken: String,
        abandonedToken: String,
        lifecycleEvents: List<QueueFragmentLifecycleEvent>,
    ): UndoPeerDiagnostic {
        var savedFragmentPresent = false
        var savedViewPresent = false
        var savedFragmentLifecycleState = "UNKNOWN"
        var savedViewLifecycleState = "UNKNOWN"
        var cancelledViewPresent = false
        var savedSnackbarActionVisible = false
        var savedSnackbarMessage: String? = null
        scenario.onActivity { activity ->
            val navHost = activity.supportFragmentManager.findFragmentById(R.id.frame_layout)
                as? androidx.navigation.fragment.NavHostFragment
            val queue = navHost?.childFragmentManager?.primaryNavigationFragment
                as? DownloadQueueMainFragment
            val currentSaved = queue?.childFragmentManager?.fragments
                ?.firstOrNull { it is SavedDownloadsFragment }
            savedFragmentPresent = currentSaved === savedFragment
            savedViewPresent = savedFragment.view === savedView
            savedFragmentLifecycleState = savedFragment.lifecycle.currentState.name
            savedViewLifecycleState = savedFragment.viewLifecycleOwnerLiveData.value
                ?.lifecycle
                ?.currentState
                ?.name
                ?: "NONE"
            cancelledViewPresent = cancelledFragment.view === cancelledView
            val snackbarAction = activity.window.decorView
                .findViewById<View>(MaterialR.id.snackbar_action)
            savedSnackbarActionVisible = snackbarAction?.isShown == true
            savedSnackbarMessage = activity.window.decorView
                .findViewById<View>(MaterialR.id.snackbar_text)
                ?.let { (it as? android.widget.TextView)?.text?.toString() }
        }

        val savedCarrier = database.pendingUndoCarrierDao.get(savedToken)
        val abandonedCarrier = database.pendingUndoCarrierDao.get(abandonedToken)
        return UndoPeerDiagnostic(
            savedFragmentPresent = savedFragmentPresent,
            savedViewPresent = savedViewPresent,
            savedViewDestroyed = lifecycleEvents.any {
                it.kind == QueueFragmentLifecycleEvent.SAVED_VIEW_DESTROYED &&
                    it.fragment === savedFragment
            },
            savedFragmentLifecycleState = savedFragmentLifecycleState,
            savedViewLifecycleState = savedViewLifecycleState,
            savedOwnerIdentity = "${savedOwner.id}/${savedOwner.generation}",
            cancelledOwnerIdentity = "${cancelledOwner.id}/${cancelledOwner.generation}",
            savedOwnerActive = viewModel.isUndoPresentationOwnerActive(savedOwner),
            savedTokenLive = DownloadRepository.isLivePendingRemovalToken(savedToken),
            savedTokenProcessLocal = DownloadRepository.isProcessLocalPendingUndoAuthority(savedToken),
            savedResolverInFlight = DownloadRepository.isUndoResolverInFlight(savedToken),
            savedAuthorityKnown = DownloadRepository.isUndoAuthorityKnown(savedToken),
            savedCarrierCommitted = DownloadRepository.isUndoCarrierCommitted(savedToken),
            savedCarrierPresentationState = savedCarrier?.presentationState,
            savedCarrierResolutionIntent = savedCarrier?.resolutionIntent,
            savedSnackbarActionVisible = savedSnackbarActionVisible,
            savedSnackbarMessage = savedSnackbarMessage,
            abandonedTokenLive = DownloadRepository.isLivePendingRemovalToken(abandonedToken),
            abandonedTokenProcessLocal = DownloadRepository.isProcessLocalPendingUndoAuthority(abandonedToken),
            cancelledOwnerActive = viewModel.isUndoPresentationOwnerActive(cancelledOwner),
            cancelledViewPresent = cancelledViewPresent,
            cancelledViewDestroyed = lifecycleEvents.any {
                it.kind == QueueFragmentLifecycleEvent.CANCELLED_VIEW_DESTROYED &&
                    it.fragment === cancelledFragment
            } && !cancelledViewPresent,
            abandonedCarrierPresentationState = abandonedCarrier?.presentationState,
            abandonedCarrierResolutionIntent = abandonedCarrier?.resolutionIntent,
            lifecycleEvents = lifecycleEvents.joinToString("|")
                .ifEmpty { "none" },
        )
    }

    private fun swipeFirstVisibleDownload(id: Long) {
        val bounds = Rect()
        activityScenario?.onActivity { activity ->
            val recycler = currentDownloadRecyclerView(activity)
                ?: error("Selected download page has no RecyclerView")
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
                visible = currentDownloadRecyclerView(activity)
                    ?.findViewHolderForAdapterPosition(0)
                    ?.itemView
                    ?.tag == id.toString()
            }
            visible
        }
    }

    private fun currentDownloadRecyclerView(
        activity: MainActivity,
    ): androidx.recyclerview.widget.RecyclerView? {
        val pager = activity.findViewById<ViewPager2>(R.id.download_viewpager)
        val navHost = activity.supportFragmentManager.findFragmentById(R.id.frame_layout)
            as? androidx.navigation.fragment.NavHostFragment
        val queue = navHost?.childFragmentManager?.primaryNavigationFragment
            as? DownloadQueueMainFragment
        val expectedType = expectedQueueFragmentType(pager.currentItem)
        val selected = queue?.childFragmentManager?.fragments?.firstOrNull { fragment ->
            fragment.view != null && (expectedType == null || expectedType.isInstance(fragment))
        }
        return selected?.view?.findViewById(R.id.download_recyclerview)
    }

    private fun awaitRemovalCarrier(
        id: Long,
        expectedPage: Int,
    ): PendingUndoCarrier = awaitValue {
        val expectedUrl = "https://example.com/$testPrefix/${createdDownloadIds.indexOf(id)}"
        database.pendingUndoCarrierDao.getAll().firstOrNull {
            it.kind == PendingUndoCarrier.REMOVAL_KIND &&
                it.snapshotJson.contains("$testPrefix") &&
                it.snapshotJson.contains("\"url\":\"$expectedUrl\"") &&
                it.presentationState == PendingUndoCarrier.PUBLISHED_PRESENTATION
        }?.takeIf {
            // Room publication precedes the process-local PREPARED_UNPUBLISHED
            // -> LIVE_UI transition.  Wait for both sides of that real
            // publication boundary, plus the still-attached view surface, so
            // this test never samples the legal intermediate state.
            DownloadRepository.isLivePendingRemovalToken(it.token) &&
                isQueuePageReady(expectedPage)
        }
    }

    private fun awaitCancellationCarrier(
        id: Long,
        expectedPage: Int,
    ): PendingUndoCarrier {
        return awaitValue {
            val token = database.lowQualityRedownloadDao.getItemByDownloadId(id)?.reasonCode
            token?.let { database.pendingUndoCarrierDao.get(it) }
                ?.takeIf {
                    it.kind == PendingUndoCarrier.CANCELLATION_KIND &&
                        it.presentationState == PendingUndoCarrier.PUBLISHED_PRESENTATION
                }
                ?.takeIf {
                    DownloadRepository.isLivePendingCancellationToken(it.token) &&
                        isQueuePageReady(expectedPage)
                }
        }
    }

    private fun isQueuePageReady(expectedPage: Int): Boolean {
        var ready = false
        activityScenario?.onActivity { activity ->
            val pager = activity.findViewById<ViewPager2>(R.id.download_viewpager)
            if (pager.currentItem != expectedPage) return@onActivity
            val navHost = activity.supportFragmentManager.findFragmentById(R.id.frame_layout)
                as? androidx.navigation.fragment.NavHostFragment
            val queue = navHost?.childFragmentManager?.primaryNavigationFragment
                as? DownloadQueueMainFragment
            val expectedType = expectedQueueFragmentType(expectedPage)
                ?: return@onActivity
            ready = queue?.childFragmentManager?.fragments?.any { fragment ->
                expectedType.isInstance(fragment) &&
                    fragment.view?.findViewById<androidx.recyclerview.widget.RecyclerView>(
                        R.id.download_recyclerview,
                    ) != null
            } == true
        }
        return ready
    }

    private fun expectedQueueFragmentType(
        page: Int,
    ): Class<out androidx.fragment.app.Fragment>? = when (page) {
        1 -> QueuedDownloadsFragment::class.java
        3 -> CancelledDownloadsFragment::class.java
        4 -> ErroredDownloadsFragment::class.java
        5 -> SavedDownloadsFragment::class.java
        else -> null
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
