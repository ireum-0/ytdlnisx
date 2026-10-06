package com.ireum.ytdl.work

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreOperationStore
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScheduledDownloadWindowProductionWiringTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var database: DBManager
    private lateinit var workManager: WorkManager

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        runCatching { RestoreTransactionCoordinator.recover(context) }
        RestoreOperationStore.root(context).deleteRecursively()
        SchedulerSettingsTransitionCoordinator.clearForTesting(context)
        preferences.edit().remove("schedule_start").remove("schedule_end")
            .remove("use_scheduler").commit()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
        AlarmScheduler.exactAlarmPublicationForTesting = { _, _, _ -> }
    }

    @After
    fun tearDown(): Unit = runBlocking {
        AlarmScheduler.exactAlarmPublicationForTesting = null
        AlarmScheduler.schedulerTransitionStepForTesting = null
        SchedulerSettingsTransitionCoordinator.clearForTesting(context)
        WorkManagerHandoffRecovery.clearForTesting()
        if (::database.isInitialized) database.close()
        preferences.edit().remove("schedule_start").remove("schedule_end")
            .remove("use_scheduler").commit()
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        RestoreOperationStore.root(context).deleteRecursively()
    }

    @Test
    fun realSchedulerReadsPersistedOvernightWindowAcrossMidnight() {
        preferences.edit().putString("schedule_start", "22:00")
            .putString("schedule_end", "05:00").commit()
        var now = clock(21, 59)
        val scheduler = AlarmScheduler(context) { now.clone() as Calendar }
        assertFalse(scheduler.isDuringTheScheduledTime())
        for ((hour, minute) in listOf(22 to 0, 23 to 59, 0 to 0, 2 to 17, 5 to 0)) {
            now = clock(hour, minute, 59, 999)
            assertTrue("$hour:$minute", scheduler.isDuringTheScheduledTime())
        }
        now = clock(5, 1)
        assertFalse(scheduler.isDuringTheScheduledTime())
    }

    @Test
    fun realSchedulerUsesDefaultsAndRereadsEqualBoundaryConfiguration() {
        var now = clock(0, 0)
        val scheduler = AlarmScheduler(context) { now.clone() as Calendar }
        assertTrue(scheduler.isDuringTheScheduledTime())
        now = clock(5, 0, 59, 999)
        assertTrue(scheduler.isDuringTheScheduledTime())
        now = clock(5, 1)
        assertFalse(scheduler.isDuringTheScheduledTime())
        preferences.edit().putString("schedule_start", "09:15")
            .putString("schedule_end", "09:15").commit()
        now = clock(9, 14)
        assertFalse(scheduler.isDuringTheScheduledTime())
        now = clock(9, 15, 59, 999)
        assertTrue(scheduler.isDuringTheScheduledTime())
        now = clock(9, 16)
        assertFalse(scheduler.isDuringTheScheduledTime())
    }

    @Test
    fun realSchedulePublishesNormalizedStartAndEndWithMatchingDurableCarriers() = runBlocking {
        preferences.edit().putString("schedule_start", "22:00")
            .putString("schedule_end", "05:00").putBoolean("use_scheduler", true).commit()
        val now = clock(23, 59, 43, 987)
        val published = mutableListOf<Long>()
        AlarmScheduler.exactAlarmPublicationForTesting = { _, at, _ -> published += at }

        AlarmScheduler(context) { now.clone() as Calendar }.scheduleSuspending()

        val start = clock(22, 0).apply { add(Calendar.DATE, 1) }.timeInMillis
        val end = clock(5, 0).apply { add(Calendar.DATE, 1) }.timeInMillis
        assertEquals(listOf(start, end), published)
        val startCarrier = requireNotNull(database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
            WorkManagerHandoffCarrier.SCHEDULE_START, WorkManagerHandoffCarrier.START_BOUNDARY,
        ))
        val endCarrier = requireNotNull(database.workManagerHandoffCarrierDao.getOutstandingForBoundary(
            WorkManagerHandoffCarrier.SCHEDULE_END, WorkManagerHandoffCarrier.END_BOUNDARY,
        ))
        assertEquals(start, startCarrier.notBeforeAt)
        assertEquals(end, endCarrier.notBeforeAt)
        assertEquals(0L, start % 60_000L)
        assertEquals(0L, end % 60_000L)
        assertEquals(clock(23, 59, 43, 987).timeInMillis, now.timeInMillis)
    }

    private fun clock(hour: Int, minute: Int, second: Int = 0, millis: Int = 0): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 6, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
        }
}
