package com.ireum.ytdl.database

import androidx.sqlite.db.SupportSQLiteDatabase

/** SQL revocation covers direct DAO queries, full-row writers and Restore replacement alike. */
internal object FormatAuthoritySchema {
    fun create(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS format_authority_clock (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        database.execSQL("CREATE TABLE IF NOT EXISTS format_row_authorities (kind TEXT NOT NULL, target TEXT NOT NULL, revision INTEGER NOT NULL, incarnation INTEGER NOT NULL, owner TEXT NOT NULL, lineage TEXT NOT NULL, completedLineage TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(kind,target))")
        database.execSQL("CREATE TABLE IF NOT EXISTS format_foreground_claims (id TEXT NOT NULL PRIMARY KEY, session INTEGER NOT NULL, payload TEXT NOT NULL)")
        database.execSQL("CREATE TABLE IF NOT EXISTS format_legacy_debts (requestId TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, reason TEXT NOT NULL)")
        database.execSQL("CREATE TABLE IF NOT EXISTS format_recovery_issues (issueId TEXT NOT NULL PRIMARY KEY, requestId TEXT NOT NULL, origin TEXT NOT NULL, reason TEXT NOT NULL, payload TEXT NOT NULL, handoffId TEXT NOT NULL, generationId TEXT NOT NULL, state TEXT NOT NULL, acknowledgedAt INTEGER NOT NULL)")
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_format_recovery_issues_requestId_handoffId_generationId_origin ON format_recovery_issues(requestId,handoffId,generationId,origin)")
        database.execSQL("CREATE TABLE IF NOT EXISTS format_request_cancellations (requestId TEXT NOT NULL PRIMARY KEY, handoffId TEXT NOT NULL, generationId TEXT NOT NULL, state TEXT NOT NULL)")
        install(database)
    }

    private const val serial = "(SELECT max(id) FROM format_authority_clock)"
    private const val mint = "INSERT INTO format_authority_clock(id) VALUES(NULL);"
    private const val trim = "DELETE FROM format_authority_clock WHERE id < (SELECT max(id) FROM format_authority_clock);"

    private fun stamp(kind: String, target: String, replace: Boolean, deleted: Boolean = false, condition: String = ""): String {
        val lookup = "kind='$kind' AND target=$target"
        val incarnation = if (replace) serial else "COALESCE((SELECT incarnation FROM format_row_authorities WHERE $lookup),$serial)"
        val lineage = if (kind == "DOWNLOAD" && !replace && !deleted)
            "COALESCE((SELECT lineage FROM format_row_authorities WHERE $lookup),'')" else "''"
        val allocation = if (condition.isEmpty()) mint else "INSERT INTO format_authority_clock(id) SELECT NULL WHERE $condition;"
        val where = if (condition.isEmpty()) "" else " WHERE $condition"
        return allocation + "INSERT OR REPLACE INTO format_row_authorities(kind,target,revision,incarnation,owner,lineage,completedLineage,state) " +
            "SELECT '$kind',$target,$serial,$incarnation,'',$lineage,'','${if (deleted) "DELETED" else "CURRENT"}'$where;" + trim
    }

    fun install(database: SupportSQLiteDatabase) {
        listOf("downloads" to "DOWNLOAD", "results" to "RESULT_ROW").forEach { (table, kind) ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                val row = if (operation == "DELETE") "OLD" else "NEW"
                var body = stamp(kind, "CAST($row.id AS TEXT)", operation == "INSERT", operation == "DELETE")
                if (table == "results") {
                    if (operation != "INSERT") body += stamp("RESULT_URL", "OLD.url", false)
                    if (operation != "DELETE") body += stamp("RESULT_URL", "NEW.url", false)
                }
                if (operation == "UPDATE") {
                    // Primary-key changes revoke the old identity as well; ID is not incarnation.
                    body += stamp(kind, "CAST(OLD.id AS TEXT)", false, true, "OLD.id != NEW.id")
                }
                database.execSQL("CREATE TRIGGER IF NOT EXISTS format_authority_${table}_${operation.lowercase()} AFTER $operation ON $table BEGIN $body END")
            }
        }
        // Existing rows acquire a durable incarnation, never a fabricated format owner.
        listOf("downloads" to "DOWNLOAD", "results" to "RESULT_ROW").forEach { (table, kind) ->
            database.query("SELECT CAST(id AS TEXT) FROM $table WHERE CAST(id AS TEXT) NOT IN (SELECT target FROM format_row_authorities WHERE kind='$kind')").use { cursor ->
                while (cursor.moveToNext()) {
                    database.execSQL("INSERT INTO format_authority_clock(id) VALUES(NULL)")
                    database.execSQL("INSERT INTO format_row_authorities SELECT ?,?,$serial,$serial,'','','','CURRENT'", arrayOf(kind, cursor.getString(0)))
                }
            }
        }
        database.query("SELECT DISTINCT url FROM results WHERE url NOT IN (SELECT target FROM format_row_authorities WHERE kind='RESULT_URL')").use { cursor ->
            while (cursor.moveToNext()) {
                database.execSQL("INSERT INTO format_authority_clock(id) VALUES(NULL)")
                database.execSQL("INSERT INTO format_row_authorities SELECT 'RESULT_URL',?,$serial,$serial,'','','','CURRENT'", arrayOf(cursor.getString(0)))
            }
        }
        database.execSQL(trim.removeSuffix(";"))
    }
}
