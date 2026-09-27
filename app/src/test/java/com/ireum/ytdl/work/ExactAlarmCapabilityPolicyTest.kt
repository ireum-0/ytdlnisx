package com.ireum.ytdl.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactAlarmCapabilityPolicyTest {
    @Test
    fun api24WithAlarmManagerUsesExactAlarmCapability() {
        assertTrue(
            ExactAlarmCapabilityPolicy.canSchedule(
                apiLevel = 24,
                alarmManagerAvailable = true,
                canScheduleExactAlarms = { error("API 24 has no exact-alarm special-access query") },
            ),
        )
    }

    @Test
    fun api30WithAlarmManagerUsesExactAlarmCapability() {
        assertTrue(
            ExactAlarmCapabilityPolicy.canSchedule(
                apiLevel = 30,
                alarmManagerAvailable = true,
                canScheduleExactAlarms = { error("API 30 has no exact-alarm special-access query") },
            ),
        )
    }

    @Test
    fun api24WithoutAlarmManagerHasNoCapability() {
        assertFalse(
            ExactAlarmCapabilityPolicy.canSchedule(
                apiLevel = 24,
                alarmManagerAvailable = false,
                canScheduleExactAlarms = { error("must not query without AlarmManager") },
            ),
        )
    }

    @Test
    fun api30WithoutAlarmManagerHasNoCapability() {
        assertFalse(
            ExactAlarmCapabilityPolicy.canSchedule(
                apiLevel = 30,
                alarmManagerAvailable = false,
                canScheduleExactAlarms = { error("must not query without AlarmManager") },
            ),
        )
    }

    @Test
    fun api31RequiresGrantedExactAlarmAccess() {
        assertTrue(
            ExactAlarmCapabilityPolicy.canSchedule(
                apiLevel = 31,
                alarmManagerAvailable = true,
                canScheduleExactAlarms = { true },
            ),
        )
        assertFalse(
            ExactAlarmCapabilityPolicy.canSchedule(
                apiLevel = 31,
                alarmManagerAvailable = true,
                canScheduleExactAlarms = { false },
            ),
        )
    }

    @Test
    fun api31WithoutAlarmManagerHasNoCapability() {
        assertFalse(
            ExactAlarmCapabilityPolicy.canSchedule(
                apiLevel = 31,
                alarmManagerAvailable = false,
                canScheduleExactAlarms = { error("must not query without AlarmManager") },
            ),
        )
    }
}
