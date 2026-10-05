package com.ireum.ytdl.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ireum.ytdl.database.BackupRestoreParser
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.RestorePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterPreferenceRestoreSchemaTest {
    @Test
    fun everySupportedRepresentationRejectsDestinationInvalidUpdaterSettings() {
        val malformed = listOf(
            BackupSettingsItem("ytdlp_source", "", "String"),
            BackupSettingsItem("ytdlp_source", " \t\n", "String"),
            BackupSettingsItem("ytdlp_source", "1", "Int"),
            BackupSettingsItem("ytdlp_source", "true", "Boolean"),
            BackupSettingsItem("auto_update_ytdlp", "false", "String"),
            BackupSettingsItem("auto_update_ytdlp", "1", "Boolean"),
            BackupSettingsItem("ytdlp_source_label", "1", "Int"),
        )
        malformed.forEach { item ->
            assertTrue(item.toString(), runCatching {
                BackupRestoreParser.fromTyped(RestoreAppDataItem(settings = listOf(item)))
            }.exceptionOrNull() is IllegalArgumentException)
            serializedRepresentations(listOf(item)).forEach { raw ->
                assertTrue(raw, runCatching { BackupRestoreParser.parse(raw) }.exceptionOrNull() is IllegalArgumentException)
            }
        }
    }

    @Test
    fun allRepresentationsPreserveAbsencePresetsCustomTextBooleanLabelAndGenericSettings() {
        listOf<String?>(null, "stable", "nightly", "master", "  owner/custom branch  ").forEach { source ->
            listOf(false, true).forEach { automatic ->
                val settings = mutableListOf(
                    BackupSettingsItem("auto_update_ytdlp", automatic.toString(), "Boolean"),
                    BackupSettingsItem("ytdlp_source_label", "  Display label  ", "String"),
                    BackupSettingsItem("updater03_unrelated", "1", "Int"),
                )
                source?.let { settings += BackupSettingsItem("ytdlp_source", it, "String") }
                assertEquals(settings, BackupRestoreParser.fromTyped(RestoreAppDataItem(settings = settings)).data.settings)
                serializedRepresentations(settings).forEach { raw ->
                    assertEquals(settings, BackupRestoreParser.parse(raw).data.settings)
                }
            }
        }
    }

    @Test
    fun ownedRecoverySanitizesOnlyHistoricallyGenericValidUpdaterViolations() {
        val malformed = listOf(
            BackupSettingsItem("ytdlp_source", "1", "Int"),
            BackupSettingsItem("ytdlp_source", "true", "Boolean"),
            BackupSettingsItem("ytdlp_source", " \t\n", "String"),
            BackupSettingsItem("auto_update_ytdlp", "false", "String"),
            BackupSettingsItem("ytdlp_source_label", "1", "Int"),
        )
        malformed.forEach { item ->
            val settings = listOf(
                BackupSettingsItem("ytdlp_source", "  custom/source  ", "String"),
                BackupSettingsItem("ytdlp_source_label", "Exact label", "String"),
                BackupSettingsItem("auto_update_ytdlp", "false", "Boolean"),
                BackupSettingsItem("updater03_unrelated", "23", "Int"),
            ).filterNot { it.key == item.key } + item
            val strict = BackupRestoreParser.fromTyped(RestoreAppDataItem(settings = emptyList()))
            val raw = RestorePlan(strict.appMarker, strict.formatVersion, strict.compatibility,
                strict.capabilities, RestoreAppDataItem(settings = settings))
            assertTrue(runCatching { BackupRestoreParser.validatePlan(raw) }.isFailure)
            val recovery = BackupRestoreParser.recoverOwnedResetPlan(raw)
            val removed = if (item.key == "ytdlp_source") setOf(item.key, "ytdlp_source_label") else setOf(item.key)
            assertEquals(settings.filterNot { it.key in removed }, recovery.plan.data.settings)
            assertEquals(item.key == "ytdlp_source", recovery.updaterRepair.sourceInvalid)
            assertEquals(item.key == "auto_update_ytdlp", recovery.updaterRepair.automaticInvalid)
            assertEquals(item.key == "ytdlp_source_label", recovery.updaterRepair.labelInvalid)
            assertEquals(settings, raw.data.settings)
        }
    }

    @Test
    fun ownedRecoveryRetainsGenericValueMetadataAndCapabilityValidation() {
        val strict = BackupRestoreParser.fromTyped(RestoreAppDataItem(settings = emptyList()))
        listOf(
            BackupSettingsItem("auto_update_ytdlp", "1", "Boolean"),
            BackupSettingsItem("ytdlp_source", "not-an-int", "Int"),
            BackupSettingsItem("updater03_unrelated", "not-an-int", "Int"),
            BackupSettingsItem("updater03_unrelated", "value", "Unknown"),
        ).forEach { item ->
            val raw = RestorePlan(strict.appMarker, strict.formatVersion, strict.compatibility,
                strict.capabilities, RestoreAppDataItem(settings = listOf(item)))
            assertTrue(item.toString(), runCatching { BackupRestoreParser.recoverOwnedResetPlan(raw) }.isFailure)
        }
        listOf(
            RestorePlan("unrelated", strict.formatVersion, strict.compatibility, strict.capabilities, strict.data),
            RestorePlan(strict.appMarker, strict.formatVersion, strict.compatibility, emptySet(), strict.data),
        ).forEach { raw ->
            assertTrue(runCatching { BackupRestoreParser.recoverOwnedResetPlan(raw) }.isFailure)
        }
    }

    @Test
    fun ownedRecoveryPreservesHistoricalLastWriterIntentForDuplicateUpdaterKeys() {
        val strict = BackupRestoreParser.fromTyped(RestoreAppDataItem(settings = emptyList()))
        val valid = BackupSettingsItem("ytdlp_source", "  custom/exact  ", "String")
        val invalid = BackupSettingsItem("ytdlp_source", "1", "Int")
        fun recover(items: List<BackupSettingsItem>) = BackupRestoreParser.recoverOwnedResetPlan(
            RestorePlan(strict.appMarker, strict.formatVersion, strict.compatibility,
                strict.capabilities, RestoreAppDataItem(settings = items)),
        )
        assertEquals(listOf(valid), recover(listOf(invalid, valid)).plan.data.settings)
        assertTrue(!recover(listOf(invalid, valid)).updaterRepair.sourceInvalid)
        assertEquals(emptyList<BackupSettingsItem>(), recover(listOf(valid, invalid)).plan.data.settings)
        assertTrue(recover(listOf(valid, invalid)).updaterRepair.sourceInvalid)
    }

    private fun serializedRepresentations(settings: List<BackupSettingsItem>): List<String> =
        listOf("YTDLnisx_backup" to null, "YTDLnisX_backup" to null,
            "YTDLnisX_backup" to 3, "YTDLnisX_backup" to 4).map { (marker, version) ->
            JsonObject().apply {
                addProperty("app", marker)
                version?.let { addProperty("backup_format_version", it) }
                add("settings", Gson().toJsonTree(settings))
            }.toString()
        }
}
