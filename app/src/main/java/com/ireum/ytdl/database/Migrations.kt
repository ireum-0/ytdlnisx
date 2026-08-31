package com.ireum.ytdl.database

import android.annotation.SuppressLint
import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ireum.ytdl.database.models.Format
import com.google.gson.Gson
import java.util.Locale


object Migrations {

    private fun parseDurationSeconds(value: String): Long {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return 0L
        if (trimmed.equals("LIVE", ignoreCase = true)) return 0L
        val parts = trimmed.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> 0L
        }
    }

    private fun repairMaterializedHistoryKeywords(database: SupportSQLiteDatabase) {
        val materializedByHistory = linkedMapOf<Long, LinkedHashMap<String, String>>()
        database.query(
            "SELECT historyItemId, normalizedKeyword, keyword FROM history_keyword_assignments " +
                "ORDER BY historyItemId, " +
                "CASE sourceType WHEN 'MANUAL' THEN 0 WHEN 'RULE' THEN 1 ELSE 2 END, " +
                "sourceId, position"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val historyId = cursor.getLong(0)
                val normalized = cursor.getString(1)
                val keyword = cursor.getString(2)
                materializedByHistory
                    .getOrPut(historyId) { linkedMapOf() }
                    .putIfAbsent(normalized, keyword)
            }
        }
        materializedByHistory.forEach { (historyId, keywords) ->
            database.execSQL(
                "UPDATE history SET keywords = ? WHERE id = ?",
                arrayOf<Any>(keywords.values.joinToString(", "), historyId)
            )
        }
    }

    @SuppressLint("Range")
    val migrationList = arrayOf(
        //Moving from one file path to multiple file paths of a history item
        Migration(13, 14){database ->
            database.query("SELECT * FROM history").use { cursor ->
                while(cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndex("id"))
                    val path = cursor.getString(cursor.getColumnIndex("downloadPath"))
                    val newPath = "[\"${path.replace("\"", "\\\"").replace("'", "''")}\"]"
                    database.execSQL("UPDATE history SET downloadPath = '${newPath}' WHERE id = $id")

                }
            }

            database.execSQL("CREATE TABLE IF NOT EXISTS `observeSources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `downloadItemTemplate` TEXT NOT NULL, `status` TEXT NOT NULL, `everyNr` INTEGER NOT NULL, `everyCategory` TEXT NOT NULL, `everyWeekDay` TEXT NOT NULL, `everyMonthDay` INTEGER NOT NULL, `everyTime` INTEGER NOT NULL, `startsTime` INTEGER NOT NULL, `startsMonth` TEXT NOT NULL, `endsDate` INTEGER NOT NULL DEFAULT 0, `endsAfterCount` INTEGER NOT NULL DEFAULT 0, `runCount` INTEGER NOT NULL DEFAULT 0, `retryMissingDownloads` INTEGER NOT NULL, `alreadyProcessedLinks` TEXT NOT NULL)")
        },

//        Migration(17, 18 ){ database ->
//            database.execSQL("ALTER TABLE `sources` ADD COLUMN `syncWithSource` INTEGER NOT NULL DEFAULT 0")
//        }

        //add filesizes to history
        Migration(20, 21) { database ->
            database.query("SELECT * FROM history").use { cursor ->
                while(cursor.moveToNext()) {
                    kotlin.runCatching {
                        val id = cursor.getLong(cursor.getColumnIndex("id"))
                        val format = cursor.getString(cursor.getColumnIndex("format"))
                        val parsed = Gson().fromJson(format, Format::class.java)
                        database.execSQL("UPDATE history SET filesize = ${parsed.filesize} WHERE id = $id")
                    }
                }
            }
        },

        //add preferred command template and url regexes
        Migration(21, 22) { database ->
            // Add the `preferredCommandTemplate` column as INTEGER (since SQLite does not support BOOLEAN)
            database.execSQL("ALTER TABLE commandTemplates ADD COLUMN preferredCommandTemplate INTEGER NOT NULL DEFAULT 0")

            // Add `urlRegex` as a JSON string (since lists are not supported in SQLite)
            database.execSQL("ALTER TABLE commandTemplates ADD COLUMN urlRegex TEXT NOT NULL DEFAULT '[]'")
        },

        //add available subtitles list in result and download item
        Migration(22, 23) { database ->
            //add available subtitles for result item
            database.execSQL("ALTER TABLE results ADD COLUMN availableSubtitles TEXT NOT NULL DEFAULT '[]'")

            //add available subtitles for download item
            database.execSQL("ALTER TABLE downloads ADD COLUMN availableSubtitles TEXT NOT NULL DEFAULT '[]'")
        },

        //add row number to download item, use to set autonumber metadata
        Migration(23, 24) { database ->
            //add available subtitles for download item
            database.execSQL("ALTER TABLE downloads ADD COLUMN rowNumber INTEGER NOT NULL DEFAULT 0")
        },

        //add enabled to cookies
        Migration(24, 25) { database ->
            database.execSQL("ALTER TABLE cookies ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
        },

        //add description to cookies
        Migration(25, 26) { database ->
            database.execSQL("ALTER TABLE cookies ADD COLUMN description TEXT NOT NULL DEFAULT ''")
        },
        Migration(29, 30) { database ->
            database.execSQL("CREATE INDEX IF NOT EXISTS index_history_time ON history(time)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_history_author ON history(author)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_history_title ON history(title)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_history_type ON history(type)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_history_website ON history(website)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_history_filesize ON history(filesize)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_history_url ON history(url)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_status ON downloads(status)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_downloadStartTime ON downloads(downloadStartTime)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_item_playlistId ON PlaylistItemCrossRef(playlistId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_item_historyItemId ON PlaylistItemCrossRef(historyItemId)")
        },
        Migration(30, 31) { database ->
            database.query("SELECT id, author FROM history").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndex("id"))
                    val author = cursor.getString(cursor.getColumnIndex("author")) ?: ""
                    val normalized = author.split(Regex("[,/]"))
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                    if (normalized != author) {
                        val safe = normalized.replace("'", "''")
                        database.execSQL("UPDATE history SET author = '$safe' WHERE id = $id")
                    }
                }
            }
        },
        Migration(31, 32) { database ->
            database.execSQL("ALTER TABLE history ADD COLUMN localTreeUri TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE history ADD COLUMN localTreePath TEXT NOT NULL DEFAULT ''")
        },
        Migration(32, 33) { database ->
            database.execSQL("ALTER TABLE history ADD COLUMN keywords TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE history ADD COLUMN customThumb TEXT NOT NULL DEFAULT ''")
        },
        Migration(33, 34) { database ->
            database.execSQL("CREATE TABLE IF NOT EXISTS `youtuber_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_youtuber_groups_name` ON `youtuber_groups` (`name`)")
            database.execSQL("CREATE TABLE IF NOT EXISTS `youtuber_group_members` (`groupId` INTEGER NOT NULL, `author` TEXT NOT NULL, PRIMARY KEY(`groupId`, `author`))")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_youtuber_group_members_author` ON `youtuber_group_members` (`author`)")
        },
        Migration(34, 35) { database ->
            database.execSQL("ALTER TABLE history ADD COLUMN durationSeconds INTEGER NOT NULL DEFAULT 0")
            database.query("SELECT id, duration FROM history").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndex("id"))
                    val duration = cursor.getString(cursor.getColumnIndex("duration")) ?: ""
                    val seconds = parseDurationSeconds(duration)
                    database.execSQL("UPDATE history SET durationSeconds = $seconds WHERE id = $id")
                }
            }
        },
        Migration(35, 36) { database ->
            database.execSQL("CREATE TABLE IF NOT EXISTS `youtuber_meta` (`author` TEXT NOT NULL, `channelUrl` TEXT NOT NULL, `iconUrl` TEXT NOT NULL, PRIMARY KEY(`author`))")
        },
        Migration(36, 37) { database ->
            database.execSQL("ALTER TABLE history ADD COLUMN artist TEXT NOT NULL DEFAULT ''")
        },
        Migration(37, 38) { database ->
            database.execSQL("ALTER TABLE history ADD COLUMN lastWatched INTEGER NOT NULL DEFAULT 0")
        },
        Migration(38, 39) { database ->
            database.execSQL("CREATE TABLE IF NOT EXISTS `playlist_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_groups_name` ON `playlist_groups` (`name`)")
            database.execSQL("CREATE TABLE IF NOT EXISTS `playlist_group_members` (`groupId` INTEGER NOT NULL, `playlistId` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `playlistId`))")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_group_members_playlistId` ON `playlist_group_members` (`playlistId`)")
        },
        Migration(39, 40) { database ->
            database.execSQL("CREATE TABLE IF NOT EXISTS `keyword_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_keyword_groups_name` ON `keyword_groups` (`name`)")
            database.execSQL("CREATE TABLE IF NOT EXISTS `keyword_group_members` (`groupId` INTEGER NOT NULL, `keyword` TEXT NOT NULL, PRIMARY KEY(`groupId`, `keyword`))")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_keyword_group_members_keyword` ON `keyword_group_members` (`keyword`)")
        },
        Migration(40, 41) { database ->
            database.execSQL("CREATE TABLE IF NOT EXISTS `youtuber_group_relations` (`parentGroupId` INTEGER NOT NULL, `childGroupId` INTEGER NOT NULL, PRIMARY KEY(`parentGroupId`, `childGroupId`))")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_youtuber_group_relations_childGroupId` ON `youtuber_group_relations` (`childGroupId`)")
        },
        Migration(41, 42) { database ->
            database.execSQL("ALTER TABLE sources ADD COLUMN excludeShorts INTEGER NOT NULL DEFAULT 0")
        },
        Migration(42, 43) { database ->
            database.execSQL("ALTER TABLE sources ADD COLUMN runHistory TEXT NOT NULL DEFAULT '[]'")
        },
        Migration(43, 44) { database ->
            database.execSQL("ALTER TABLE sources ADD COLUMN runInProgress INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE sources ADD COLUMN currentRunStatus TEXT NOT NULL DEFAULT ''")
        },
        Migration(44, 45) { database ->
            database.execSQL("ALTER TABLE history ADD COLUMN hardSubScanRemoved INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE history ADD COLUMN hardSubDone INTEGER NOT NULL DEFAULT 0")
        },
        Migration(45, 46) { _ ->
            // Reserved schema version step to preserve upgrade/downgrade compatibility.
        },
        Migration(46, 47) { _ ->
            // Reserved schema version step to preserve upgrade/downgrade compatibility.
        },
        Migration(47, 48) { database ->
            database.execSQL("ALTER TABLE downloads ADD COLUMN observeSourceId INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE sources ADD COLUMN autoAddKeyword TEXT NOT NULL DEFAULT ''")
        },
        Migration(48, 49) { database ->
            database.execSQL("ALTER TABLE sources ADD COLUMN retryPromptedLinks TEXT NOT NULL DEFAULT '[]'")
        },
        Migration(49, 50) { database ->
            database.execSQL("ALTER TABLE sources ADD COLUMN observedLinks TEXT NOT NULL DEFAULT '[]'")
        },
        Migration(50, 51) { database ->
            database.execSQL("ALTER TABLE downloads ADD COLUMN operationId TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE downloads ADD COLUMN retryAttempt INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE downloads ADD COLUMN retryStrategy TEXT NOT NULL DEFAULT 'ORIGINAL'")
            database.execSQL("ALTER TABLE downloads ADD COLUMN lastIssueCode TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE downloads ADD COLUMN lastIssueStage TEXT NOT NULL DEFAULT ''")
        },
        Migration(51, 52) { database ->
            database.execSQL("ALTER TABLE sources ADD COLUMN observationPurpose TEXT NOT NULL DEFAULT 'USER'")
            database.execSQL("ALTER TABLE sources ADD COLUMN managedConditionKey TEXT NOT NULL DEFAULT ''")

            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `automatic_keyword_rules` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`conditionType` TEXT NOT NULL, `conditionValue` TEXT NOT NULL, " +
                    "`conditionKey` TEXT NOT NULL, `playlistName` TEXT NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, `revision` INTEGER NOT NULL, " +
                    "`baselineComplete` INTEGER NOT NULL, " +
                    "`pendingApplyToExisting` INTEGER NOT NULL DEFAULT 0, " +
                    "`manualSyncStatus` TEXT NOT NULL, " +
                    "`manualSyncAt` INTEGER NOT NULL, `manualSyncError` TEXT NOT NULL, " +
                    "`discoveryStatus` TEXT NOT NULL, `discoveryAt` INTEGER NOT NULL, " +
                    "`discoveryError` TEXT NOT NULL)"
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_automatic_keyword_rules_conditionType_conditionKey` ON `automatic_keyword_rules` (`conditionType`, `conditionKey`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_automatic_keyword_rules_enabled` ON `automatic_keyword_rules` (`enabled`)")

            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `automatic_keyword_rule_keywords` (" +
                    "`ruleId` INTEGER NOT NULL, `normalizedKeyword` TEXT NOT NULL, " +
                    "`keyword` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`ruleId`, `normalizedKeyword`), " +
                    "FOREIGN KEY(`ruleId`) REFERENCES `automatic_keyword_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_automatic_keyword_rule_keywords_ruleId` ON `automatic_keyword_rule_keywords` (`ruleId`)")

            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `automatic_keyword_rule_video_matches` (" +
                    "`ruleId` INTEGER NOT NULL, `videoKey` TEXT NOT NULL, `videoUrl` TEXT NOT NULL, " +
                    "`eligibleForAssignment` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`ruleId`, `videoKey`), " +
                    "FOREIGN KEY(`ruleId`) REFERENCES `automatic_keyword_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_automatic_keyword_rule_video_matches_ruleId` ON `automatic_keyword_rule_video_matches` (`ruleId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_automatic_keyword_rule_video_matches_videoKey` ON `automatic_keyword_rule_video_matches` (`videoKey`)")

            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `history_keyword_assignments` (" +
                    "`historyItemId` INTEGER NOT NULL, `normalizedKeyword` TEXT NOT NULL, " +
                    "`keyword` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceId` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`historyItemId`, `normalizedKeyword`, `sourceType`, `sourceId`), " +
                    "FOREIGN KEY(`historyItemId`) REFERENCES `history`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_history_keyword_assignments_historyItemId` ON `history_keyword_assignments` (`historyItemId`)")
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_history_keyword_assignments_sourceType_sourceId` ON `history_keyword_assignments` (`sourceType`, `sourceId`)")

            val now = System.currentTimeMillis()
            database.query("SELECT id, keywords FROM history WHERE keywords != ''").use { cursor ->
                val idColumn = cursor.getColumnIndex("id")
                val keywordColumn = cursor.getColumnIndex("keywords")
                while (cursor.moveToNext()) {
                    val historyId = cursor.getLong(idColumn)
                    val seen = linkedSetOf<String>()
                    val materializedKeywords = mutableListOf<String>()
                    cursor.getString(keywordColumn).orEmpty()
                        .split(',', '\n', '，')
                        .map { it.trim().replace(Regex("\\s+"), " ") }
                        .filter(String::isNotBlank)
                        .forEachIndexed { position, keyword ->
                            val normalized = keyword.lowercase(Locale.ROOT)
                            if (seen.add(normalized)) {
                                materializedKeywords += keyword
                                database.execSQL(
                                    "INSERT OR IGNORE INTO history_keyword_assignments " +
                                        "(historyItemId, normalizedKeyword, keyword, sourceType, sourceId, position, createdAt) " +
                                        "VALUES (?, ?, ?, 'MANUAL', 0, ?, ?)",
                                    arrayOf<Any>(historyId, normalized, keyword, position, now)
                                )
                            }
                        }
                    database.execSQL(
                        "UPDATE history SET keywords = ? WHERE id = ?",
                        arrayOf<Any>(materializedKeywords.joinToString(", "), historyId)
                    )
                }
            }
        },
        Migration(52, 53) { database ->
            database.execSQL("ALTER TABLE results ADD COLUMN mediaPublishedAt INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE downloads ADD COLUMN mediaPublishedAt INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE history ADD COLUMN mediaPublishedAt INTEGER NOT NULL DEFAULT 0")
            repairMaterializedHistoryKeywords(database)
        },
        Migration(53, 54) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `low_quality_redownload_operations` (" +
                    "`operationId` TEXT NOT NULL, `phase` TEXT NOT NULL DEFAULT 'SCANNING', " +
                    "`state` TEXT NOT NULL DEFAULT 'RUNNING', " +
                    "`scanUpperBoundHistoryId` INTEGER NOT NULL DEFAULT 0, " +
                    "`scanCursorHistoryId` INTEGER NOT NULL DEFAULT 0, " +
                    "`scanTotal` INTEGER NOT NULL DEFAULT 0, `scanProcessed` INTEGER NOT NULL DEFAULT 0, " +
                    "`scanFailures` INTEGER NOT NULL DEFAULT 0, `cancelRequested` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`completedAt` INTEGER NOT NULL DEFAULT 0, `terminalReason` TEXT NOT NULL DEFAULT '', " +
                    "PRIMARY KEY(`operationId`))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `low_quality_redownload_items` (" +
                    "`operationId` TEXT NOT NULL, `historyId` INTEGER NOT NULL, " +
                    "`candidateReason` TEXT NOT NULL DEFAULT '', `mediaState` TEXT NOT NULL DEFAULT '', " +
                    "`actualHeight` INTEGER NOT NULL DEFAULT 0, `requestedHeight` INTEGER NOT NULL DEFAULT 0, " +
                    "`expectedHeight` INTEGER NOT NULL DEFAULT 0, `sourceMaxHeight` INTEGER NOT NULL DEFAULT 0, " +
                    "`selected` INTEGER NOT NULL DEFAULT 0, `itemState` TEXT NOT NULL DEFAULT 'PROVISIONAL', " +
                    "`reasonCode` TEXT NOT NULL DEFAULT '', `downloadId` INTEGER, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`operationId`, `historyId`), " +
                    "FOREIGN KEY(`operationId`) REFERENCES `low_quality_redownload_operations`(`operationId`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_low_quality_redownload_items_operationId` " +
                    "ON `low_quality_redownload_items` (`operationId`)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_low_quality_redownload_items_downloadId` " +
                    "ON `low_quality_redownload_items` (`downloadId`)"
            )
        },
        Migration(54, 55) { database ->
            database.execSQL(
                "ALTER TABLE downloads ADD COLUMN orderPosition INTEGER NOT NULL DEFAULT 0"
            )
            database.execSQL("UPDATE downloads SET orderPosition = id")
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_downloads_orderPosition " +
                    "ON downloads(orderPosition)"
            )
        },
        Migration(55, 56) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `history_date_fetch_operations` (" +
                    "`operationId` TEXT NOT NULL, `state` TEXT NOT NULL DEFAULT 'RUNNING', " +
                    "`cancelRequested` INTEGER NOT NULL DEFAULT 0, " +
                    "`candidateCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`uniqueSourceCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`processedSourceCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`localHits` INTEGER NOT NULL DEFAULT 0, `cacheHits` INTEGER NOT NULL DEFAULT 0, " +
                    "`duplicateCoalesced` INTEGER NOT NULL DEFAULT 0, " +
                    "`extractorLaunches` INTEGER NOT NULL DEFAULT 0, " +
                    "`compatibilityFallbacks` INTEGER NOT NULL DEFAULT 0, " +
                    "`elapsedMs` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, `completedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`terminalReason` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`operationId`))"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_history_date_fetch_operations_state` " +
                    "ON `history_date_fetch_operations` (`state`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_history_date_fetch_operations_createdAt` " +
                    "ON `history_date_fetch_operations` (`createdAt`)"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `history_date_fetch_items` (" +
                    "`operationId` TEXT NOT NULL, `historyId` INTEGER NOT NULL, " +
                    "`sourceUrlSnapshot` TEXT NOT NULL, `sourceGroupKey` TEXT NOT NULL DEFAULT '', " +
                    "`itemState` TEXT NOT NULL DEFAULT 'PENDING', `reasonCode` TEXT NOT NULL DEFAULT '', " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`operationId`, `historyId`), " +
                    "FOREIGN KEY(`operationId`) REFERENCES `history_date_fetch_operations`(`operationId`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_history_date_fetch_items_operationId` " +
                    "ON `history_date_fetch_items` (`operationId`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_history_date_fetch_items_historyId` " +
                    "ON `history_date_fetch_items` (`historyId`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_history_date_fetch_items_operationId_itemState` " +
                    "ON `history_date_fetch_items` (`operationId`, `itemState`)"
            )
        },
        Migration(56, 57) { database ->
            database.execSQL(
                "ALTER TABLE downloads ADD COLUMN executionId TEXT NOT NULL DEFAULT ''"
            )
            // v56 mismatch rows retain lastIssueCode/lastIssueStage as the
            // fail-closed carrier.  v56 has no trustworthy replacement source
            // snapshot, so inventing history_replacement_barriers rows here
            // would create false destructive authority.
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `history_replacement_barriers` (" +
                    "`downloadId` INTEGER NOT NULL, " +
                    "`operationId` TEXT NOT NULL, " +
                    "`historyId` INTEGER NOT NULL, " +
                    "`expectedSourceUrl` TEXT NOT NULL, " +
                    "`expectedType` TEXT NOT NULL, " +
                    "`issueCode` TEXT NOT NULL, " +
                    "`issueStage` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`downloadId`))"
            )
        },
        Migration(57, 58) { database ->
            database.execSQL(
                "ALTER TABLE low_quality_redownload_items " +
                    "ADD COLUMN intendedSourceUrl TEXT NOT NULL DEFAULT ''"
            )
            database.execSQL(
                "ALTER TABLE low_quality_redownload_items " +
                    "ADD COLUMN intendedType TEXT NOT NULL DEFAULT ''"
            )
        },
        Migration(58, 59) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_undo_carriers` (" +
                    "`token` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, " +
                    "`ownerId` TEXT NOT NULL DEFAULT '', " +
                    "`authorityGeneration` INTEGER NOT NULL DEFAULT 0, " +
                    "`resolutionIntent` TEXT NOT NULL DEFAULT '', " +
                    "`resolverGeneration` INTEGER NOT NULL DEFAULT 0, " +
                    "`snapshotJson` TEXT NOT NULL DEFAULT '', " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`token`))"
            )
        },
        Migration(59, 60) { database ->
            database.execSQL(
                "ALTER TABLE pending_undo_carriers " +
                    "ADD COLUMN presentationState TEXT NOT NULL DEFAULT 'UNPUBLISHED'"
            )
        },
        Migration(60, 61) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `work_manager_handoff_carriers` (" +
                    "`handoffId` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, " +
                    "`generationId` TEXT NOT NULL, " +
                    "`requestId` TEXT NOT NULL, " +
                    "`uniqueWorkName` TEXT NOT NULL, " +
                    "`state` TEXT NOT NULL DEFAULT 'PENDING_ENQUEUE', " +
                    "`sourceId` INTEGER NOT NULL DEFAULT 0, " +
                    "`confirmedUrl` TEXT NOT NULL DEFAULT '', " +
                    "`decision` TEXT NOT NULL DEFAULT '', " +
                    "`configFingerprint` TEXT NOT NULL DEFAULT '', " +
                    "`boundary` TEXT NOT NULL DEFAULT '', " +
                    "`notBeforeAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`attempt` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`handoffId`))"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_work_manager_handoff_carriers_kind_boundary_state` " +
                    "ON `work_manager_handoff_carriers` (`kind`, `boundary`, `state`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_work_manager_handoff_carriers_sourceId_confirmedUrl_configFingerprint_decision_kind` " +
                    "ON `work_manager_handoff_carriers` (`sourceId`, `confirmedUrl`, `configFingerprint`, `decision`, `kind`)"
            )
        }
    )

    @DeleteTable.Entries(
        DeleteTable(
            tableName = "observeSources"
        )
    )
    class resetObserveSources : AutoMigrationSpec {
        @Override
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            // Invoked once auto migration is done
        }
    }


}
