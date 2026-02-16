package com.ireum.ytdl.database

import android.annotation.SuppressLint
import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ireum.ytdl.database.models.Format
import com.google.gson.Gson


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

    @SuppressLint("Range")
    val migrationList = arrayOf(
        //Moving from one file path to multiple file paths of a history item
        Migration(13, 14){database ->
            val cursor = database.query("SELECT * FROM history")
            while(cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndex("id"))
                val path = cursor.getString(cursor.getColumnIndex("downloadPath"))
                val newPath = "[\"${path.replace("\"", "\\\"").replace("'", "''")}\"]"
                database.execSQL("UPDATE history SET downloadPath = '${newPath}' WHERE id = $id")

            }

            database.execSQL("CREATE TABLE IF NOT EXISTS `observeSources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `downloadItemTemplate` TEXT NOT NULL, `status` TEXT NOT NULL, `everyNr` INTEGER NOT NULL, `everyCategory` TEXT NOT NULL, `everyWeekDay` TEXT NOT NULL, `everyMonthDay` INTEGER NOT NULL, `everyTime` INTEGER NOT NULL, `startsTime` INTEGER NOT NULL, `startsMonth` TEXT NOT NULL, `endsDate` INTEGER NOT NULL DEFAULT 0, `endsAfterCount` INTEGER NOT NULL DEFAULT 0, `runCount` INTEGER NOT NULL DEFAULT 0, `retryMissingDownloads` INTEGER NOT NULL, `alreadyProcessedLinks` TEXT NOT NULL)")
        },

//        Migration(17, 18 ){ database ->
//            database.execSQL("ALTER TABLE `sources` ADD COLUMN `syncWithSource` INTEGER NOT NULL DEFAULT 0")
//        }

        //add filesizes to history
        Migration(20, 21) { database ->
            val cursor = database.query("SELECT * FROM history")
            while(cursor.moveToNext()) {
                kotlin.runCatching {
                    val id = cursor.getLong(cursor.getColumnIndex("id"))
                    val format = cursor.getString(cursor.getColumnIndex("format"))
                    val parsed = Gson().fromJson(format, Format::class.java)
                    database.execSQL("UPDATE history SET filesize = ${parsed.filesize} WHERE id = $id")
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
            val cursor = database.query("SELECT id, author FROM history")
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
            cursor.close()
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
            val cursor = database.query("SELECT id, duration FROM history")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndex("id"))
                val duration = cursor.getString(cursor.getColumnIndex("duration")) ?: ""
                val seconds = parseDurationSeconds(duration)
                database.execSQL("UPDATE history SET durationSeconds = $seconds WHERE id = $id")
            }
            cursor.close()
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
