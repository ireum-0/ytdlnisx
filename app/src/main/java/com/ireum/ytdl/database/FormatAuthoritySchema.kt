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

    private fun stamp(kind: String, target: String, replace: String, deleted: Boolean = false, condition: String = ""): String {
        val lookup = "kind='$kind' AND target=$target"
        val incarnation = "CASE WHEN $replace THEN $serial ELSE incarnation END"
        val lineage = if (kind == "DOWNLOAD" && !deleted)
            "CASE WHEN $replace THEN '' ELSE lineage END" else "''"
        val allocation = if (condition.isEmpty()) mint else "INSERT INTO format_authority_clock(id) SELECT NULL WHERE $condition;"
        val guard = if (condition.isEmpty()) "" else " AND ($condition)"
        val state = if (deleted) "DELETED" else "CURRENT"
        // The outer writer's OR ABORT/IGNORE/REPLACE overrides a trigger's conflict policy.
        // Never attempt a conflicting insert: both statements run in the writer's transaction.
        return allocation + "UPDATE format_row_authorities SET revision=$serial,incarnation=$incarnation," +
            "owner='',lineage=$lineage,completedLineage='',state='$state' WHERE $lookup$guard;" +
            "INSERT INTO format_row_authorities(kind,target,revision,incarnation,owner,lineage,completedLineage,state) " +
            "SELECT '$kind',$target,$serial,$serial,'','','','$state' WHERE NOT EXISTS " +
            "(SELECT 1 FROM format_row_authorities WHERE $lookup)$guard;" + trim
    }

    fun install(database: SupportSQLiteDatabase) {
        // Also used on current-version open: old Room65 already has these named triggers.
        // A nested failure marks the enclosing migration/authority transaction for rollback too.
        database.beginTransaction()
        try {
            installInTransaction(database)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun installInTransaction(database: SupportSQLiteDatabase) {
        listOf("downloads" to "DOWNLOAD", "results" to "RESULT_ROW").forEach { (table, kind) ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                val row = if (operation == "DELETE") "OLD" else "NEW"
                val replace = when (operation) {
                    "INSERT" -> "1"
                    "UPDATE" -> "OLD.id != NEW.id"
                    else -> "0"
                }
                var body = stamp(kind, "CAST($row.id AS TEXT)", replace, operation == "DELETE")
                if (table == "results") {
                    if (operation != "INSERT") body += stamp("RESULT_URL", "OLD.url", "0")
                    if (operation != "DELETE") body += stamp("RESULT_URL", "NEW.url", "0")
                }
                if (operation == "UPDATE") {
                    // Primary-key changes revoke the old identity as well; ID is not incarnation.
                    body += stamp(kind, "CAST(OLD.id AS TEXT)", "0", true, "OLD.id != NEW.id")
                }
                val name = "format_authority_${table}_${operation.lowercase()}"
                val sql = "CREATE TRIGGER $name AFTER $operation ON $table BEGIN $body END"
                val old = database.query("SELECT sql FROM sqlite_master WHERE type='trigger' AND name=?", arrayOf(name)).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (old != sql) {
                    database.execSQL("DROP TRIGGER IF EXISTS $name")
                    database.execSQL(sql)
                }
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
