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
    fun migrateFromVersion49To51AddsObservedLinks() {
        helper.createDatabase(TEST_DB, 49).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            51,
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
    fun migrateFromVersion49To51PreservesPopulatedSourceRows() {
        helper.createDatabase(TEST_DB, 49).apply {
            execSQL(
                """
                INSERT INTO sources (
                    id, name, url, downloadItemTemplate, everyNr, everyCategory, everyTime,
                    status, startsTime, retryMissingDownloads, alreadyProcessedLinks,
                    autoAddKeyword, retryPromptedLinks
                ) VALUES (
                    7, 'Source Name', 'https://example.com/feed', '{}', 3, 'Day', 12345,
                    'Active', 67890, 1, '["old"]', 'keyword', '["retry"]'
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            51,
            true,
            *Migrations.migrationList
        )

        db.use {
            it.query("SELECT name, url, autoAddKeyword, retryPromptedLinks, observedLinks FROM sources WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Source Name", cursor.getString(0))
                assertEquals("https://example.com/feed", cursor.getString(1))
                assertEquals("keyword", cursor.getString(2))
                assertEquals("[\"retry\"]", cursor.getString(3))
                assertEquals("[]", cursor.getString(4))
            }
        }
    }

    @Test
    fun migrateFromVersion30To51ValidatesCurrentManualMigrationChain() {
        helper.createDatabase(TEST_DB, 30).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            51,
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

    @Test
    fun migrateFromVersion37To52PreservesHistoryAndBackfillsManualKeywordAssignments() {
        helper.createDatabase(TEST_DB, 37).apply {
            execSQL(
                """
                INSERT INTO history (
                    id, url, title, author, artist, duration, durationSeconds, thumb,
                    type, time, downloadPath, website, format, filesize, downloadId,
                    command, playbackPositionMs, localTreeUri, localTreePath, keywords,
                    customThumb
                ) VALUES (
                    11, 'https://example.com/video', 'Saved title', 'Creator', 'Artist',
                    '00:02:03', 123, 'https://example.com/thumb.jpg', 'video', 1000,
                    '["/storage/emulated/0/Download/video.mp4"]', 'example.com', '{}',
                    4567, 99, '--format 137+140', 42000,
                    'content://example/tree/downloads', '/storage/emulated/0/Download',
                    ' One, one，Two ', '/storage/emulated/0/Download/custom.jpg'
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            52,
            true,
            *Migrations.migrationList
        )

        db.use {
            it.query(
                """
                SELECT title, artist, durationSeconds, playbackPositionMs, lastWatched, keywords,
                       hardSubScanRemoved, hardSubDone
                FROM history WHERE id = 11
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Saved title", cursor.getString(0))
                assertEquals("Artist", cursor.getString(1))
                assertEquals(123L, cursor.getLong(2))
                assertEquals(42000L, cursor.getLong(3))
                assertEquals(0L, cursor.getLong(4))
                assertEquals("One, Two", cursor.getString(5))
                assertEquals(0, cursor.getInt(6))
                assertEquals(0, cursor.getInt(7))
            }

            assertEquals(
                setOf(
                    "index_history_time",
                    "index_history_author",
                    "index_history_title",
                    "index_history_type",
                    "index_history_website",
                    "index_history_filesize",
                    "index_history_url"
                ),
                tableIndices(it, "history")
            )
            it.query(
                """
                SELECT normalizedKeyword, keyword, sourceType, sourceId
                FROM history_keyword_assignments
                WHERE historyItemId = 11
                ORDER BY position
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("one", cursor.getString(0))
                assertEquals("One", cursor.getString(1))
                assertEquals("MANUAL", cursor.getString(2))
                assertEquals(0L, cursor.getLong(3))
                assertTrue(cursor.moveToNext())
                assertEquals("two", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrateFromVersion51To52AddsRuleTablesAndManagedSourceColumns() {
        helper.createDatabase(TEST_DB, 51).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            52,
            true,
            *Migrations.migrationList
        )

        db.use {
            val sourceDefaults = tableColumnDefaults(it, "sources")
            assertEquals("'USER'", sourceDefaults["observationPurpose"])
            assertEquals("''", sourceDefaults["managedConditionKey"])
            val ruleDefaults = tableColumnDefaults(it, "automatic_keyword_rules")
            assertEquals("0", ruleDefaults["pendingApplyToExisting"])
            val tables = linkedSetOf<String>()
            it.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertTrue(tables.contains("automatic_keyword_rules"))
            assertTrue(tables.contains("automatic_keyword_rule_keywords"))
            assertTrue(tables.contains("automatic_keyword_rule_video_matches"))
            assertTrue(tables.contains("history_keyword_assignments"))
        }
    }

    @Test
    fun migrateFromVersion50To51PreservesDownloadAndAddsRetryMetadata() {
        helper.createDatabase(TEST_DB, 50).apply {
            execSQL(
                """
                INSERT INTO downloads (
                    id, url, title, author, thumb, duration, type, format, container,
                    downloadSections, allFormats, downloadPath, website, downloadSize,
                    playlistTitle, audioPreferences, videoPreferences, extraCommands,
                    customFileNameTemplate, SaveThumb, status, downloadStartTime, logID,
                    playlistURL, playlistIndex, incognito, availableSubtitles, rowNumber,
                    observeSourceId
                ) VALUES (
                    21, 'https://example.com/video', 'Retry me', 'Creator', '', '10',
                    'video', '{}', 'mp4', '', '[]', '/downloads', 'example.com', '', '',
                    '{}', '{}', '', '%(title)s', 0, 'Error', 0, 5, '', NULL, 0, '[]', 0, 7
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            51,
            true,
            *Migrations.migrationList
        )

        db.use {
            it.query(
                """
                SELECT title, status, observeSourceId, operationId, retryAttempt,
                       retryStrategy, lastIssueCode, lastIssueStage
                FROM downloads WHERE id = 21
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Retry me", cursor.getString(0))
                assertEquals("Error", cursor.getString(1))
                assertEquals(7L, cursor.getLong(2))
                assertEquals("", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals("ORIGINAL", cursor.getString(5))
                assertEquals("", cursor.getString(6))
                assertEquals("", cursor.getString(7))
            }
        }
    }

    @Test
    fun migrateFromVersion52To53AddsSourceDatesWithoutChangingExistingRows() {
        helper.createDatabase(TEST_DB, 52).apply {
            execSQL(
                """
                INSERT INTO history (
                    id, url, title, author, duration, thumb, type, time,
                    downloadPath, website, format, keywords
                ) VALUES (
                    61, 'https://example.com/history', 'History title', 'Creator',
                    '00:01:00', '', 'video', 1234, '["/downloads/history.mp4"]',
                    'example.com', '{}', 'stale projection'
                )
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO history_keyword_assignments " +
                    "(historyItemId, normalizedKeyword, keyword, sourceType, sourceId, position, createdAt) " +
                    "VALUES (61, 'manual', 'Manual', 'MANUAL', 0, 0, 1), " +
                    "(61, 'rule', 'Rule', 'RULE', 9, 0, 2), " +
                    "(61, 'manual', 'Ignored duplicate', 'RULE', 9, 1, 2)"
            )
            execSQL(
                """
                INSERT INTO results (
                    id, url, title, author, duration, thumb, website, playlistTitle,
                    formats, urls, chapters, playlistURL, playlistIndex, creationTime,
                    availableSubtitles
                ) VALUES (
                    62, 'https://example.com/result', 'Result title', 'Creator',
                    '00:01:00', '', 'example.com', '', '[]', '', NULL, '', NULL,
                    1234, '[]'
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO downloads (
                    id, url, title, author, thumb, duration, type, format, container,
                    downloadSections, allFormats, downloadPath, website, downloadSize,
                    playlistTitle, audioPreferences, videoPreferences, extraCommands,
                    customFileNameTemplate, SaveThumb, status, downloadStartTime, logID,
                    playlistURL, playlistIndex, incognito, availableSubtitles, rowNumber
                ) VALUES (
                    63, 'https://example.com/download', 'Download title', 'Creator',
                    '', '00:01:00', 'video', '{}', 'mp4', '', '[]', '/downloads',
                    'example.com', '', '', '{}', '{}', '', '%(title)s', 0, 'Queued',
                    0, NULL, '', NULL, 0, '[]', 0
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            53,
            true,
            *Migrations.migrationList
        )

        db.use {
            assertEquals("0", tableColumnDefaults(it, "history")["mediaPublishedAt"])
            assertEquals("0", tableColumnDefaults(it, "results")["mediaPublishedAt"])
            assertEquals("0", tableColumnDefaults(it, "downloads")["mediaPublishedAt"])

            it.query("SELECT title, mediaPublishedAt, keywords FROM history WHERE id = 61").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("History title", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals("Manual, Rule", cursor.getString(2))
            }
            it.query("SELECT title, mediaPublishedAt FROM results WHERE id = 62").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Result title", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
            }
            it.query("SELECT title, mediaPublishedAt FROM downloads WHERE id = 63").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Download title", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
            }
        }
    }

    @Test
    fun migrateFromVersion53To54AddsDurableLowQualityLedgerAndPreservesRows() {
        helper.createDatabase(TEST_DB, 53).apply {
            execSQL(
                """
                INSERT INTO history (
                    id, url, title, author, duration, thumb, type, time,
                    downloadPath, website, format, mediaPublishedAt
                ) VALUES (
                    71, 'https://example.com/history-54', 'Existing history', 'Creator',
                    '00:01:00', '', 'video', 1234, '["/downloads/history.mp4"]',
                    'example.com', '{}', 9876
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO downloads (
                    id, url, title, author, thumb, duration, type, format, container,
                    downloadSections, allFormats, downloadPath, website, downloadSize,
                    playlistTitle, audioPreferences, videoPreferences, extraCommands,
                    customFileNameTemplate, SaveThumb, status, downloadStartTime, logID,
                    playlistURL, playlistIndex, incognito, availableSubtitles, rowNumber,
                    mediaPublishedAt
                ) VALUES (
                    72, 'https://example.com/download-54', 'Existing download', 'Creator',
                    '', '00:01:00', 'video', '{}', 'mp4', '', '[]', '/downloads',
                    'example.com', '', '', '{}', '{}', '', '%(title)s', 0, 'Queued',
                    0, NULL, '', NULL, 0, '[]', 0, 9876
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            54,
            true,
            *Migrations.migrationList
        )

        db.use {
            it.query("SELECT title, mediaPublishedAt FROM history WHERE id = 71").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Existing history", cursor.getString(0))
                assertEquals(9876L, cursor.getLong(1))
            }
            it.query("SELECT title, status, mediaPublishedAt FROM downloads WHERE id = 72").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Existing download", cursor.getString(0))
                assertEquals("Queued", cursor.getString(1))
                assertEquals(9876L, cursor.getLong(2))
            }

            val operationDefaults = tableColumnDefaults(it, "low_quality_redownload_operations")
            assertEquals("'SCANNING'", operationDefaults["phase"])
            assertEquals("'RUNNING'", operationDefaults["state"])
            assertEquals("0", operationDefaults["cancelRequested"])
            val itemDefaults = tableColumnDefaults(it, "low_quality_redownload_items")
            assertEquals("0", itemDefaults["selected"])
            assertEquals("'PROVISIONAL'", itemDefaults["itemState"])
            val itemIndices = tableIndices(it, "low_quality_redownload_items")
            assertTrue(itemIndices.contains("index_low_quality_redownload_items_operationId"))
            assertTrue(itemIndices.contains("index_low_quality_redownload_items_downloadId"))
            it.query("PRAGMA foreign_key_list(`low_quality_redownload_items`)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("low_quality_redownload_operations", cursor.getString(cursor.getColumnIndexOrThrow("table")))
                assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            }
        }
    }

    @Test
    fun migrateFromVersion54To55PreservesStableIdsAndBackfillsQueueOrder() {
        helper.createDatabase(TEST_DB, 54).apply {
            execSQL(
                "INSERT INTO low_quality_redownload_operations " +
                    "(operationId, phase, state) VALUES ('operation-55', 'DOWNLOADING', 'RUNNING')"
            )
            execSQL(
                """
                INSERT INTO downloads (
                    id, url, title, author, thumb, duration, type, format, container,
                    downloadSections, allFormats, downloadPath, website, downloadSize,
                    playlistTitle, audioPreferences, videoPreferences, extraCommands,
                    customFileNameTemplate, SaveThumb, status, downloadStartTime, logID,
                    playlistURL, playlistIndex, incognito, availableSubtitles, rowNumber,
                    mediaPublishedAt
                ) VALUES (
                    72, 'https://example.com/download-55', 'Stable download', 'Creator',
                    '', '00:01:00', 'video', '{}', 'mp4', '', '[]', '/downloads',
                    'example.com', '', '', '{}', '{}', '', '%(title)s', 0, 'Queued',
                    0, NULL, '', NULL, 0, '[]', 0, 9876
                )
                """.trimIndent()
            )
            execSQL(
                "INSERT INTO low_quality_redownload_items " +
                    "(operationId, historyId, selected, itemState, downloadId) " +
                    "VALUES ('operation-55', 71, 1, 'QUEUED', 72)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            55,
            true,
            *Migrations.migrationList
        )

        db.use {
            it.query("SELECT id, orderPosition FROM downloads WHERE id = 72").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(72L, cursor.getLong(0))
                assertEquals(72L, cursor.getLong(1))
            }
            it.query(
                "SELECT downloadId FROM low_quality_redownload_items " +
                    "WHERE operationId = 'operation-55' AND historyId = 71"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(72L, cursor.getLong(0))
            }
            assertTrue(tableIndices(it, "downloads").contains("index_downloads_orderPosition"))
            it.query("PRAGMA foreign_key_list(`low_quality_redownload_items`)").use { cursor ->
                val referencedTables = linkedSetOf<String>()
                val tableIndex = cursor.getColumnIndexOrThrow("table")
                while (cursor.moveToNext()) referencedTables += cursor.getString(tableIndex)
                assertEquals(setOf("low_quality_redownload_operations"), referencedTables)
            }
        }
    }

    @Test
    fun migrateFromVersion55To56AddsDurableHistoryDateFetchLedgerAndPreservesHistory() {
        helper.createDatabase(TEST_DB, 55).apply {
            execSQL(
                """
                INSERT INTO history (
                    id, url, title, author, duration, thumb, type, time,
                    downloadPath, website, format, mediaPublishedAt
                ) VALUES (
                    81, 'https://example.com/history-56', 'Existing history', 'Creator',
                    '00:01:00', '', 'video', 1234, '[]', 'example.com', '{}', -315532800
                )
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            56,
            true,
            *Migrations.migrationList,
        )

        db.use {
            it.query("SELECT title, mediaPublishedAt FROM history WHERE id = 81").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Existing history", cursor.getString(0))
                assertEquals(-315_532_800L, cursor.getLong(1))
            }

            val operationDefaults = tableColumnDefaults(it, "history_date_fetch_operations")
            assertEquals("'RUNNING'", operationDefaults["state"])
            assertEquals("0", operationDefaults["cancelRequested"])
            assertEquals("0", operationDefaults["extractorLaunches"])
            assertEquals("0", operationDefaults["compatibilityFallbacks"])
            assertTrue(
                tableIndices(it, "history_date_fetch_operations").containsAll(
                    setOf(
                        "index_history_date_fetch_operations_state",
                        "index_history_date_fetch_operations_createdAt",
                    )
                )
            )

            val itemDefaults = tableColumnDefaults(it, "history_date_fetch_items")
            assertEquals("'PENDING'", itemDefaults["itemState"])
            assertTrue(
                tableIndices(it, "history_date_fetch_items").containsAll(
                    setOf(
                        "index_history_date_fetch_items_operationId",
                        "index_history_date_fetch_items_historyId",
                        "index_history_date_fetch_items_operationId_itemState",
                    )
                )
            )
            it.query("PRAGMA foreign_key_list(`history_date_fetch_items`)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "history_date_fetch_operations",
                    cursor.getString(cursor.getColumnIndexOrThrow("table")),
                )
                assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            }
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

    private fun tableIndices(
        db: SupportSQLiteDatabase,
        tableName: String
    ): Set<String> {
        val indices = linkedSetOf<String>()
        db.query("PRAGMA index_list(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                indices += cursor.getString(nameIndex)
            }
        }
        return indices
    }

    private companion object {
        const val TEST_DB = "migration-smoke-test"
    }
}
