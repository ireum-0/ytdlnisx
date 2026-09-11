package com.ireum.ytdl.work

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanupScheduleCoordinatorProductionWiringTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var preferences: android.content.SharedPreferences

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit()
            .remove("cleanup_leftover_downloads")
            .remove("cleanup_leftover_downloads_generation")
            .remove("cleanup_leftover_downloads_anchor_day")
            .commit()
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        preferences.edit()
            .remove("cleanup_leftover_downloads")
            .remove("cleanup_leftover_downloads_generation")
            .remove("cleanup_leftover_downloads_anchor_day")
            .commit()
    }

    @Test
    fun enableAndRapidCadenceChangesLeaveOneCurrentLogicalChain() {
        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        assertEquals(1, activeCleanupWork().size)

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.WEEKLY)
        val active = activeCleanupWork()
        assertEquals(1, active.size)
        assertTrue(active.single().tags.contains("${CleanupScheduleCoordinator.TAG}_cadence_weekly"))

        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.MONTHLY)
        val finalActive = activeCleanupWork()
        assertEquals(1, finalActive.size)
        assertTrue(finalActive.single().tags.contains("${CleanupScheduleCoordinator.TAG}_cadence_monthly"))
    }

    @Test
    fun disableRemovesFutureCleanupOwnerAndStaleSuccessorIsFenced() {
        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        val before = activeCleanupWork().single()
        val generation = preferences.getString("cleanup_leftover_downloads_generation", null)
            ?: error("missing generation")

        assertFalse(
            CleanupScheduleCoordinator.scheduleSuccessor(
                context,
                generation = "stale-generation",
                cadence = CleanupSchedulePolicy.DAILY,
                monthlyAnchorDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
            )
        )
        assertEquals(before.id, activeCleanupWork().single().id)

        CleanupScheduleCoordinator.configure(context, null)
        assertTrue(activeCleanupWork().isEmpty())
    }

    @Test
    fun startupReconciliationRestoresOneChainWhenCarrierIsMissing() {
        CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY)
        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)

        CleanupScheduleCoordinator.reconcile(context)

        assertEquals(1, activeCleanupWork().size)
    }

    private fun activeCleanupWork(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(CleanupScheduleCoordinator.WORK_NAME)
            .get(10, TimeUnit.SECONDS)
            .filter { info ->
                info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED
            }
}
