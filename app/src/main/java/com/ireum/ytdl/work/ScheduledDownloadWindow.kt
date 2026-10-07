package com.ireum.ytdl.work

import java.util.Calendar

/**
 * The scheduler's inclusive minute interval on a circular local day.
 * Equal boundaries select that single minute, not a full day. Seconds and
 * milliseconds do not change membership in the configured boundary minute.
 */
internal class ScheduledDownloadWindow(start: String, end: String) {
    private val startMinute = minuteOfDay(start)
    private val endMinute = minuteOfDay(end)

    fun contains(now: Calendar): Boolean {
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (startMinute <= endMinute) {
            minute in startMinute..endMinute
        } else {
            minute >= startMinute || minute <= endMinute
        }
    }

    fun nextStart(now: Calendar): Calendar = nextBoundary(startMinute, now)

    fun nextEnd(now: Calendar): Calendar = nextBoundary(endMinute, now).apply {
        // Membership includes the whole configured end minute. Select its
        // calendar day first, then cross that minute (including 23:59 rollover).
        add(Calendar.MINUTE, 1)
        if (timeInMillis < now.timeInMillis) add(Calendar.DATE, 1)
    }

    private fun nextBoundary(minute: Int, now: Calendar): Calendar =
        (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // Preserve the existing minute-based next-day selection, including
            // publication in today's boundary minute when it is already current.
            if (minute < now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)) {
                add(Calendar.DATE, 1)
            }
        }

    private fun minuteOfDay(time: String): Int {
        val parts = time.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }
}
