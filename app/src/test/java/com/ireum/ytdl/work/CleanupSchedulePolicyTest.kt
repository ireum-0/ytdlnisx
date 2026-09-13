package com.ireum.ytdl.work

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupSchedulePolicyTest {
    @Test
    fun dailyAndWeeklyAdvanceLocalCalendarDates() {
        val now = calendar("UTC", 2026, Calendar.MARCH, 8, 1, 30)

        val daily = CleanupSchedulePolicy.nextOccurrence(now, CleanupSchedulePolicy.DAILY)
        val weekly = CleanupSchedulePolicy.nextOccurrence(now, CleanupSchedulePolicy.WEEKLY)

        assertLocalDateTime(daily, 2026, Calendar.MARCH, 9, 1, 30)
        assertLocalDateTime(weekly, 2026, Calendar.MARCH, 15, 1, 30)
    }

    @Test
    fun monthlyAnchorsCoverAllMonthLengthsAndYearRollover() {
        assertLocalDate(
            CleanupSchedulePolicy.nextOccurrence(
                calendar("UTC", 2026, Calendar.JANUARY, 28, 9, 0),
                CleanupSchedulePolicy.MONTHLY,
                monthlyAnchorDay = 28,
            ),
            2026,
            Calendar.FEBRUARY,
            28,
        )
        assertLocalDate(
            CleanupSchedulePolicy.nextOccurrence(
                calendar("UTC", 2028, Calendar.JANUARY, 29, 9, 0),
                CleanupSchedulePolicy.MONTHLY,
                monthlyAnchorDay = 29,
            ),
            2028,
            Calendar.FEBRUARY,
            29,
        )
        assertLocalDate(
            CleanupSchedulePolicy.nextOccurrence(
                calendar("UTC", 2026, Calendar.JANUARY, 29, 9, 0),
                CleanupSchedulePolicy.MONTHLY,
                monthlyAnchorDay = 29,
            ),
            2026,
            Calendar.FEBRUARY,
            28,
        )
        assertLocalDate(
            CleanupSchedulePolicy.nextOccurrence(
                calendar("UTC", 2026, Calendar.JANUARY, 30, 9, 0),
                CleanupSchedulePolicy.MONTHLY,
                monthlyAnchorDay = 30,
            ),
            2026,
            Calendar.FEBRUARY,
            28,
        )
        assertLocalDate(
            CleanupSchedulePolicy.nextOccurrence(
                calendar("UTC", 2026, Calendar.JANUARY, 31, 9, 0),
                CleanupSchedulePolicy.MONTHLY,
                monthlyAnchorDay = 31,
            ),
            2026,
            Calendar.FEBRUARY,
            28,
        )

        val marchAfterClampedJanuary = CleanupSchedulePolicy.nextOccurrence(
            calendar("UTC", 2026, Calendar.FEBRUARY, 28, 9, 0),
            CleanupSchedulePolicy.MONTHLY,
            monthlyAnchorDay = 31,
        )
        assertLocalDate(marchAfterClampedJanuary, 2026, Calendar.MARCH, 31)

        assertLocalDate(
            CleanupSchedulePolicy.nextOccurrence(
                calendar("UTC", 2026, Calendar.DECEMBER, 31, 9, 0),
                CleanupSchedulePolicy.MONTHLY,
                monthlyAnchorDay = 31,
            ),
            2027,
            Calendar.JANUARY,
            31,
        )
    }

    @Test
    fun monthlyAnchorDoesNotDriftAfterAClampedMonth() {
        val january = calendar("UTC", 2026, Calendar.JANUARY, 31, 9, 0)
        val february = CleanupSchedulePolicy.nextOccurrence(
            january,
            CleanupSchedulePolicy.MONTHLY,
            monthlyAnchorDay = 31,
        )
        val march = CleanupSchedulePolicy.nextOccurrence(
            february,
            CleanupSchedulePolicy.MONTHLY,
            monthlyAnchorDay = 31,
        )

        assertLocalDate(february, 2026, Calendar.FEBRUARY, 28)
        assertLocalDate(march, 2026, Calendar.MARCH, 31)
        assertTrue(march.timeInMillis > february.timeInMillis)
    }

    @Test
    fun dailyAndWeeklyPreserveLocalTimeAcrossDstSpringAndFall() {
        val zone = "America/New_York"
        val spring = calendar(zone, 2026, Calendar.MARCH, 7, 1, 30)
        val springDaily = CleanupSchedulePolicy.nextOccurrence(
            spring,
            CleanupSchedulePolicy.DAILY,
        )
        assertLocalDateTime(springDaily, 2026, Calendar.MARCH, 8, 1, 30)

        val fall = calendar(zone, 2026, Calendar.OCTOBER, 31, 1, 30)
        val fallWeekly = CleanupSchedulePolicy.nextOccurrence(
            fall,
            CleanupSchedulePolicy.WEEKLY,
        )
        assertLocalDateTime(fallWeekly, 2026, Calendar.NOVEMBER, 7, 1, 30)

        // Calendar.DATE, rather than elapsed milliseconds, preserves the
        // intended local calendar occurrence through the offset transition.
        assertEquals(zone, springDaily.timeZone.id)
        assertEquals(zone, fallWeekly.timeZone.id)
    }

    @Test
    fun monthlyPreservesLocalCalendarDateAcrossDst() {
        val now = calendar("America/New_York", 2026, Calendar.OCTOBER, 31, 1, 30)
        val next = CleanupSchedulePolicy.nextOccurrence(
            now,
            CleanupSchedulePolicy.MONTHLY,
            monthlyAnchorDay = 31,
        )

        assertLocalDateTime(next, 2026, Calendar.NOVEMBER, 30, 1, 30)
        assertEquals("America/New_York", next.timeZone.id)
    }

    @Test
    fun unsupportedCadenceIsDisabled() {
        assertTrue(!CleanupSchedulePolicy.isEnabled(null))
        assertTrue(!CleanupSchedulePolicy.isEnabled("hourly"))
    }

    private fun assertLocalDate(
        actual: Calendar,
        year: Int,
        month: Int,
        day: Int,
    ) {
        assertEquals(year, actual.get(Calendar.YEAR))
        assertEquals(month, actual.get(Calendar.MONTH))
        assertEquals(day, actual.get(Calendar.DAY_OF_MONTH))
    }

    private fun assertLocalDateTime(
        actual: Calendar,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ) {
        assertLocalDate(actual, year, month, day)
        assertEquals(hour, actual.get(Calendar.HOUR_OF_DAY))
        assertEquals(minute, actual.get(Calendar.MINUTE))
    }

    private fun calendar(
        zoneId: String,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Calendar = GregorianCalendar(TimeZone.getTimeZone(zoneId)).apply {
        clear()
        set(year, month, day, hour, minute, 0)
    }
}
