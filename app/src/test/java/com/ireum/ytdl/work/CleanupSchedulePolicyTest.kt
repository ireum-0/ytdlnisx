package com.ireum.ytdl.work

import java.util.Calendar
import java.util.GregorianCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupSchedulePolicyTest {
    @Test
    fun dailyAndWeeklyKeepLocalCalendarDays() {
        val now = calendar(2026, Calendar.MARCH, 8, 1, 30)

        val daily = CleanupSchedulePolicy.nextOccurrence(now, CleanupSchedulePolicy.DAILY)
        val weekly = CleanupSchedulePolicy.nextOccurrence(now, CleanupSchedulePolicy.WEEKLY)

        assertEquals(9, daily.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MARCH, daily.get(Calendar.MONTH))
        assertEquals(15, weekly.get(Calendar.DAY_OF_MONTH))
        assertEquals(1, daily.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, daily.get(Calendar.MINUTE))
    }

    @Test
    fun monthlyAnchorSurvivesShortMonthAndLeapYear() {
        val jan31 = calendar(2028, Calendar.JANUARY, 31, 9, 0)
        val feb = CleanupSchedulePolicy.nextOccurrence(jan31, CleanupSchedulePolicy.MONTHLY, 31)
        val mar = CleanupSchedulePolicy.nextOccurrence(feb, CleanupSchedulePolicy.MONTHLY, 31)

        assertEquals(Calendar.FEBRUARY, feb.get(Calendar.MONTH))
        assertEquals(29, feb.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.MARCH, mar.get(Calendar.MONTH))
        assertEquals(31, mar.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun monthlyAnchorClampsShortMonthConservatively() {
        val jan31 = calendar(2026, Calendar.JANUARY, 31, 9, 0)
        val feb = CleanupSchedulePolicy.nextOccurrence(jan31, CleanupSchedulePolicy.MONTHLY, 31)

        assertEquals(Calendar.FEBRUARY, feb.get(Calendar.MONTH))
        assertEquals(28, feb.get(Calendar.DAY_OF_MONTH))
        assertTrue(feb.timeInMillis > jan31.timeInMillis)
    }

    @Test
    fun unsupportedCadenceIsNotEnabled() {
        assertTrue(!CleanupSchedulePolicy.isEnabled(null))
        assertTrue(!CleanupSchedulePolicy.isEnabled("hourly"))
    }

    private fun calendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        GregorianCalendar().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }
}
