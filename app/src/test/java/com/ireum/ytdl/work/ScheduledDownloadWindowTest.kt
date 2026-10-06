package com.ireum.ytdl.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduledDownloadWindowTest {
    @Test
    fun sameDayWindowIncludesOnlyConfiguredMinutes() {
        val window = ScheduledDownloadWindow("09:15", "17:30")
        assertFalse(window.contains(clock(9, 14)))
        assertTrue(window.contains(clock(12, 0)))
        assertFalse(window.contains(clock(17, 31)))
    }

    @Test
    fun exactStartAndEndMinutesAreInclusive() {
        val window = ScheduledDownloadWindow("09:15", "17:30")
        assertTrue(window.contains(clock(9, 15)))
        assertTrue(window.contains(clock(17, 30)))
    }

    @Test
    fun sameHourWindowChecksBothMinuteBounds() {
        val window = ScheduledDownloadWindow("09:15", "09:30")
        assertFalse(window.contains(clock(9, 14)))
        assertTrue(window.contains(clock(9, 15)))
        assertTrue(window.contains(clock(9, 30)))
        assertFalse(window.contains(clock(9, 31)))
    }

    @Test
    fun overnightWindowIncludesEveningThrough2359() {
        val window = ScheduledDownloadWindow("22:00", "05:00")
        assertFalse(window.contains(clock(21, 59)))
        assertTrue(window.contains(clock(22, 0)))
        assertTrue(window.contains(clock(23, 59)))
    }

    @Test
    fun overnightWindowIncludesMidnightThroughExactEndMinute() {
        val window = ScheduledDownloadWindow("22:00", "05:00")
        assertTrue(window.contains(clock(0, 0)))
        assertTrue(window.contains(clock(2, 17)))
        assertTrue(window.contains(clock(5, 0)))
        assertFalse(window.contains(clock(5, 1)))
        assertFalse(window.contains(clock(12, 0)))
    }

    @Test
    fun overnightEndCanBeInTheAfternoon() {
        val window = ScheduledDownloadWindow("22:00", "15:30")
        assertTrue(window.contains(clock(0, 0)))
        assertTrue(window.contains(clock(15, 30)))
        assertFalse(window.contains(clock(15, 31)))
    }

    @Test
    fun midnightAnd2359CanBeExactBoundaries() {
        val wholeDay = ScheduledDownloadWindow("00:00", "23:59")
        val twoMinutes = ScheduledDownloadWindow("23:59", "00:00")
        assertTrue(wholeDay.contains(clock(0, 0)))
        assertTrue(wholeDay.contains(clock(23, 59)))
        assertTrue(twoMinutes.contains(clock(23, 59)))
        assertTrue(twoMinutes.contains(clock(0, 0)))
        assertFalse(twoMinutes.contains(clock(0, 1)))
        assertFalse(twoMinutes.contains(clock(23, 58)))
    }

    @Test
    fun equalStartAndEndSelectOnlyThatMinute() {
        for (time in listOf("00:00", "09:15", "23:59")) {
            val parts = time.split(":").map(String::toInt)
            val minute = parts[0] * 60 + parts[1]
            val window = ScheduledDownloadWindow(time, time)
            assertTrue(window.contains(clock(parts[0], parts[1])))
            val before = (minute + 1439) % 1440
            val after = (minute + 1) % 1440
            assertFalse(window.contains(clock(before / 60, before % 60)))
            assertFalse(window.contains(clock(after / 60, after % 60)))
        }
    }

    @Test
    fun membershipIgnoresSecondsAndMillisInsideBoundaryMinutes() {
        val window = ScheduledDownloadWindow("22:00", "05:00")
        assertTrue(window.contains(clock(22, 0, 59, 999)))
        assertTrue(window.contains(clock(5, 0, 59, 999)))
        assertFalse(window.contains(clock(5, 1, 0, 0)))
    }

    @Test
    fun bothPublishedBoundaryCalendarsNormalizeSecondsAndMillis() {
        val now = clock(2, 17, 43, 987)
        val window = ScheduledDownloadWindow("22:00", "05:00")
        assertEquals(clock(22, 0).timeInMillis, window.nextStart(now).timeInMillis)
        assertEquals(clock(5, 0).timeInMillis, window.nextEnd(now).timeInMillis)
        assertEquals(clock(2, 17, 43, 987).timeInMillis, now.timeInMillis)
    }

    @Test
    fun earlierBoundaryMinutesRollForwardOneCalendarDay() {
        val now = clock(23, 59, 43, 987)
        val window = ScheduledDownloadWindow("22:00", "05:00")
        for ((actual, expected) in listOf(
            window.nextStart(now) to clock(22, 0),
            window.nextEnd(now) to clock(5, 0),
        )) {
            expected.add(Calendar.DATE, 1)
            assertEquals(expected.timeInMillis, actual.timeInMillis)
        }
    }

    @Test
    fun currentBoundaryMinuteKeepsExistingSameDaySelection() {
        val now = clock(5, 0, 43, 987)
        assertEquals(
            clock(5, 0).timeInMillis,
            ScheduledDownloadWindow("22:00", "05:00").nextEnd(now).timeInMillis,
        )
    }

    @Test
    fun boundaryCalendarRetainsLocalZoneAndCalendarDate() {
        val now = clock(2, 17).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
        val boundary = ScheduledDownloadWindow("22:00", "15:30").nextEnd(now)
        assertEquals(now.timeZone, boundary.timeZone)
        assertEquals(now.get(Calendar.YEAR), boundary.get(Calendar.YEAR))
        assertEquals(now.get(Calendar.DAY_OF_YEAR), boundary.get(Calendar.DAY_OF_YEAR))
        assertEquals(15, boundary.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, boundary.get(Calendar.MINUTE))
    }

    @Test
    fun allMinutesAgreeWithCircularDistanceForRepresentativeWindows() {
        for ((start, end) in listOf(555 to 1050, 1320 to 300, 1320 to 930,
            555 to 570, 1439 to 0, 0 to 1439, 0 to 0, 555 to 555, 1439 to 1439)) {
            val window = ScheduledDownloadWindow(time(start), time(end))
            val width = (end - start + 1440) % 1440
            for (minute in 0 until 1440) {
                assertEquals(
                    "start=$start end=$end current=$minute",
                    (minute - start + 1440) % 1440 <= width,
                    window.contains(clock(minute / 60, minute % 60)),
                )
            }
        }
    }

    private fun time(minute: Int) = "${minute / 60}:${minute % 60}"

    private fun clock(hour: Int, minute: Int, second: Int = 0, millis: Int = 0): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 6, hour, minute, second)
            set(Calendar.MILLISECOND, millis)
        }
}
