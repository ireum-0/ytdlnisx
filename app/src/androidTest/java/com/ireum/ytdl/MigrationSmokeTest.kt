package com.ireum.ytdl

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.Migrations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationSmokeTest {

    @get:Rule
    val helper = MigrationTestHelper( 
        InstrumentationRegistry.getInstrumentation(),
        DBManager::class.java
    )

    @After
    fun deleteDatabase() {
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .deleteDatabase(TEST_DB)
    }

    @Test
    fun migrateFromVersion49To50AddsObservedLinks() {
        helper.createDatabase(TEST_DB, 49).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            50,
            true,
            *Migrations.migrationList
        )

        db.use {
            val sourceDefaults = tableColumnDefaults(it, "sources")

            assertTrue(sourceDefaults.containsKey("observedLinks"))
            assertEquals("'[]'", sourceDefaults["observedLinks"])
        }
    }

    @Test
    fun migrateFromVersion30To50ValidatesCurrentManualMigrationChain() {
        helper.createDatabase(TEST_DB, 30).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            50,
            true,
            *Migrations.migrationList
        )

        db.use {
            val historyColumns = tableColumnDefaults(it, "history")
            val sourceDefaults = tableColumnDefaults(it, "sources")
            val downloadDefaults = tableColumnDefaults(it, "downloads")

            assertTrue(historyColumns.keys.containsAll(listOf("artist", "durationSeconds", "lastWatched")))
            assertTrue(historyColumns.keys.containsAll(listOf("hardSubScanRemoved", "hardSubDone")))
            assertTrue(sourceDefaults.keys.containsAll(listOf("runHistory", "runInProgress", "currentRunStatus")))
            assertTrue(sourceDefaults.keys.containsAll(listOf("autoAddKeyword", "retryPromptedLinks", "observedLinks")))
            assertTrue(downloadDefaults.containsKey("observeSourceId"))
        }
    }

    private fun tableColumnDefaults(
        db: SupportSQLiteDatabase,
        tableName: String
    ): Map<String, String?> {
        val columns = linkedMapOf<String, String?>()
        db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                columns[cursor.getString(nameIndex)] = cursor.getString(defaultIndex)
            }
        }
        return columns
    }

    private companion object {
        const val TEST_DB = "migration-smoke-test"
    }
}
