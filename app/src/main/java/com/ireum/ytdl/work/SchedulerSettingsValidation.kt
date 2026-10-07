package com.ireum.ytdl.work

import java.util.Locale

/** Shared domain contract for UI transitions, portable Restore and replay. */
internal object SchedulerSettingsValidation {
    fun formatTime(hour: Int, minute: Int): String {
        require(hour in 0..23 && minute in 0..59)
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    }

    fun isValidTime(value: String): Boolean =
        value.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))

    fun requireTime(value: String) {
        require(isValidTime(value)) { "Scheduler time must be HH:mm in 00:00..23:59" }
    }

    fun validatePortable(key: String, type: String?, value: String) {
        when (key) {
            "schedule_start", "schedule_end" -> {
                require(type == "String") { "Scheduler time must be String" }
                requireTime(value)
            }
            "use_scheduler" -> require(type == "Boolean" && value in setOf("true", "false")) {
                "Scheduler enabled preference must be Boolean"
            }
        }
    }
}
