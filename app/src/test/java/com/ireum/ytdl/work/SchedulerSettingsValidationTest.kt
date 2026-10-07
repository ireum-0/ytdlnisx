package com.ireum.ytdl.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SchedulerSettingsValidationTest {
    @Test fun acceptsEveryCanonicalMinute() {
        for (hour in 0..23) for (minute in 0..59) {
            val time = SchedulerSettingsValidation.formatTime(hour, minute)
            assertTrue(SchedulerSettingsValidation.isValidTime(time))
            for (key in listOf("schedule_start", "schedule_end"))
                SchedulerSettingsValidation.validatePortable(key, "String", time)
        }
    }

    @Test fun rejectsNoncanonicalAndOutOfRangeTimes() {
        for (time in listOf("", "5:00", "05:0", "0500", "xx:yy", "24:00", "23:60",
            "-1:00", "123:00", "00:000", " 05:00", "05:00 ", "05:00:00")) {
            assertFalse(time, SchedulerSettingsValidation.isValidTime(time))
            for (key in listOf("schedule_start", "schedule_end"))
                assertTrue(runCatching { SchedulerSettingsValidation.validatePortable(key, "String", time) }.isFailure)
        }
    }

    @Test fun rejectsWrongSchedulerTimeTypes() {
        for (key in listOf("schedule_start", "schedule_end")) for (type in listOf(null, "Int", "Boolean", "StringSet"))
            assertTrue(runCatching { SchedulerSettingsValidation.validatePortable(key, type, "05:00") }.isFailure)
    }

    @Test fun enabledValueRequiresExactBooleanTypeAndValue() {
        for (value in listOf("true", "false")) SchedulerSettingsValidation.validatePortable("use_scheduler", "Boolean", value)
        for ((type, value) in listOf(null to "true", "String" to "true", "Int" to "1", "Boolean" to "TRUE", "Boolean" to "1"))
            assertTrue(runCatching { SchedulerSettingsValidation.validatePortable("use_scheduler", type, value) }.isFailure)
    }

    @Test fun leavesUnrelatedPortableSettingsToTheirExistingValidator() {
        SchedulerSettingsValidation.validatePortable("theme", "String", "system")
    }

    @Test fun uiTimeFormattingRemainsCanonicalAcrossNumericLocales() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale("ar"), Locale("fa"), Locale.US)) {
                Locale.setDefault(locale)
                org.junit.Assert.assertEquals("05:00", SchedulerSettingsValidation.formatTime(5, 0))
                org.junit.Assert.assertEquals("23:59", SchedulerSettingsValidation.formatTime(23, 59))
                assertTrue(SchedulerSettingsValidation.isValidTime(SchedulerSettingsValidation.formatTime(5, 0)))
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
