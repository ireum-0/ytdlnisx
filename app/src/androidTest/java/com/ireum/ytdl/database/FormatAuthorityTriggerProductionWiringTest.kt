package com.ireum.ytdl.database

import android.app.Application
import android.util.Log
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.ireum.ytdl.database.models.*
import com.ireum.ytdl.work.FormatAuthority
import com.ireum.ytdl.work.FormatRequestPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FormatAuthorityTriggerProductionWiringTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DBManager::class.java)
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val name = "format-trigger-v35-" + UUID.randomUUID()
    private var database: DBManager? = null

    @After fun cleanup() {
        database?.close()
        context.deleteDatabase(name) // Only this test's uniquely named fixture.
    }

    private fun open(): DBManager = DBManager.create(context, name).also { database = it }
    private fun sql(db: DBManager) = db.openHelper.writableDatabase
    private suspend fun stamp(db: DBManager, kind: String, target: String) =
        checkNotNull(db.formatAuthorityDao.get(kind, target))

    private suspend fun owned(db: DBManager, kind: String, target: String): FormatRowAuthority {
        val before = stamp(db, kind, target).copy(owner = "old-request",
            lineage = if (kind == "DOWNLOAD") "old-generation" else "",
            completedLineage = if (kind == "DOWNLOAD") "old-generation" else "")
        db.formatAuthorityDao.put(before)
        return before
    }

    private fun changed(before: FormatRowAuthority, after: FormatRowAuthority, recreate: Boolean = false,
                        deleted: Boolean = false) {
        assertTrue(after.revision > before.revision)
        if (recreate) assertTrue(after.incarnation > before.incarnation)
        else assertEquals(before.incarnation, after.incarnation)
        assertEquals("", after.owner)
        assertEquals("", after.completedLineage)
        assertEquals(if (deleted) "DELETED" else "CURRENT", after.state)
        assertEquals(if (before.kind == "DOWNLOAD" && !recreate && !deleted) before.lineage else "", after.lineage)
    }

    private suspend fun diagnosed(block: suspend () -> Unit) {
        try { block() } catch (failure: Throwable) {
            runCatching { database?.let { Log.e("FormatTriggerV35", snapshot(sql(it)).toString()) } }
            Log.e("FormatTriggerV35", "first failure before fixture cleanup", failure)
            throw failure
        }
    }

    @Test fun roomAbortUpsertAndEveryOuterConflictPolicyRevokeExistingOwner() = runBlocking(Dispatchers.IO) { diagnosed {
        val db = open()
        seedDownload(sql(db), 7)
        var before = owned(db, "DOWNLOAD", "7")
        var row = db.downloadDao.getDownloadById(7)
        db.downloadDao.updateWithoutUpsert(row.copy(title = "actual Room OR ABORT"))
        changed(before, stamp(db, "DOWNLOAD", "7"))
        assertEquals("actual Room OR ABORT", db.downloadDao.getDownloadById(7).title)
        before = owned(db, "DOWNLOAD", "7")
        row = db.downloadDao.getDownloadById(7)
        db.downloadDao.update(row.copy(title = "actual Room upsert existing"))
        changed(before, stamp(db, "DOWNLOAD", "7"))
        db.downloadDao.update(row.copy(id = 8, title = "actual Room upsert new"))
        assertEquals("CURRENT", stamp(db, "DOWNLOAD", "8").state)
        for (policy in listOf("", "OR ABORT", "OR FAIL", "OR IGNORE", "OR REPLACE", "OR ROLLBACK")) {
            before = owned(db, "DOWNLOAD", "7")
            sql(db).execSQL("UPDATE $policy downloads SET title=? WHERE id=7", arrayOf("writer $policy"))
            changed(before, stamp(db, "DOWNLOAD", "7"))
            assertEquals("writer $policy", db.downloadDao.getDownloadById(7).title)
        }
        assertEquals(1, rows(sql(db), "SELECT id FROM format_authority_clock").size)
    } }

    @Test fun deleteRecreateReplacementAndKeyMoveMintDistinctIncarnations() = runBlocking(Dispatchers.IO) { diagnosed {
        val db = open()
        seedDownload(sql(db), 7); seedDownload(sql(db), 8)
        val original = owned(db, "DOWNLOAD", "7")
        val victim = owned(db, "DOWNLOAD", "8")
        db.downloadDao.delete(8)
        changed(victim, stamp(db, "DOWNLOAD", "8"), deleted = true)
        // Move into an existing tombstone: neither numeric identity is the new incarnation.
        sql(db).execSQL("UPDATE OR ABORT downloads SET id=8 WHERE id=7")
        changed(original, stamp(db, "DOWNLOAD", "7"), deleted = true)
        val moved = stamp(db, "DOWNLOAD", "8")
        assertTrue(moved.incarnation > maxOf(original.incarnation, victim.incarnation))
        assertEquals("", moved.lineage); assertEquals("", moved.owner)
        val oldKeyTombstone = stamp(db, "DOWNLOAD", "7")
        seedDownload(sql(db), 7)
        changed(oldKeyTombstone, stamp(db, "DOWNLOAD", "7"), recreate = true)
        val current = owned(db, "DOWNLOAD", "7")
        db.downloadDao.insertRaw(db.downloadDao.getDownloadById(7).copy(title = "replacement"))
        changed(current, stamp(db, "DOWNLOAD", "7"), recreate = true)
        val live = owned(db, "DOWNLOAD", "7")
        db.downloadDao.delete(7)
        changed(live, stamp(db, "DOWNLOAD", "7"), deleted = true)
        assertNull(db.downloadDao.getNullableDownloadById(7))
    } }

    @Test fun resultUrlChangesIdMovesAndRepeatedWritesFenceBothUrls() = runBlocking(Dispatchers.IO) { diagnosed {
        val db = open()
        seedResult(sql(db), 9, "old"); seedResult(sql(db), 10, "new")
        val oldUrl = owned(db, "RESULT_URL", "old")
        val newUrl = owned(db, "RESULT_URL", "new")
        val row = owned(db, "RESULT_ROW", "9")
        db.resultDao.update(checkNotNull(db.resultDao.getResultByID(9)).copy(url = "new"))
        changed(row, stamp(db, "RESULT_ROW", "9"))
        changed(oldUrl, stamp(db, "RESULT_URL", "old"))
        changed(newUrl, stamp(db, "RESULT_URL", "new"))
        val deleted = owned(db, "RESULT_ROW", "10")
        db.resultDao.delete(10)
        changed(deleted, stamp(db, "RESULT_ROW", "10"), deleted = true)
        db.resultDao.updateID(9, 10)
        assertEquals("DELETED", stamp(db, "RESULT_ROW", "9").state)
        assertTrue(stamp(db, "RESULT_ROW", "10").incarnation > deleted.incarnation)
        val replaced = owned(db, "RESULT_ROW", "10")
        db.resultDao.insert(checkNotNull(db.resultDao.getResultByID(10)).copy(title = "same key replacement"))
        changed(replaced, stamp(db, "RESULT_ROW", "10"), recreate = true)
        val beforeDeleteUrl = owned(db, "RESULT_URL", "new")
        db.resultDao.delete(10)
        changed(beforeDeleteUrl, stamp(db, "RESULT_URL", "new"))
        val tombstone = stamp(db, "RESULT_ROW", "10")
        seedResult(sql(db), 10, "new")
        changed(tombstone, stamp(db, "RESULT_ROW", "10"), recreate = true)
    } }

    @Test fun actualForegroundLeasesCannotPublishAfterDownloadOrUrlMutation() = runBlocking(Dispatchers.IO) { diagnosed {
        val db = open()
        seedDownload(sql(db), 7); seedResult(sql(db), 9, "old")
        suspend fun capture() = db.downloadDao.getDownloadById(7).let { row ->
            FormatAuthority.captureForeground(context, db, listOf(row),
                mapOf(row.id to FormatRequestPolicy.capture(context, db, row.type, row.url)), true).single()
        }
        val lease = capture()
        val row = db.downloadDao.getDownloadById(7)
        db.downloadDao.updateWithoutUpsert(row.copy(title = "new writer"))
        assertFalse(FormatAuthority.publish(context, db, lease, listOf(Format(format_id = "stale"))))
        FormatAuthority.releaseForeground(context, db, lease.actor)
        val urlLease = capture()
        db.resultDao.update(checkNotNull(db.resultDao.getResultByID(9)).copy(url = "new"))
        assertFalse(FormatAuthority.publish(context, db, urlLease, listOf(Format(format_id = "stale-url"))))
        assertTrue(db.downloadDao.getDownloadById(7).allFormats.isEmpty())
        assertTrue(checkNotNull(db.resultDao.getResultByID(9)).formats.isEmpty())
        FormatAuthority.releaseForeground(context, db, urlLease.actor)
        assertTrue(db.formatAuthorityDao.foregroundClaims().isEmpty())
    } }

    @Test fun abortedWriterRollsBackRowsAuthoritiesAndClock() = runBlocking(Dispatchers.IO) { diagnosed {
        val db = open()
        seedDownload(sql(db), 7); seedDownload(sql(db), 8); seedResult(sql(db), 9, "old")
        owned(db, "DOWNLOAD", "7"); owned(db, "RESULT_URL", "old")
        val before = snapshot(sql(db))
        try {
            db.withTransaction {
                db.downloadDao.updateWithoutUpsertRaw(db.downloadDao.getDownloadById(7).copy(title = "rollback"))
                db.resultDao.update(checkNotNull(db.resultDao.getResultByID(9)).copy(url = "rollback-url"))
                assertNotEquals(before, snapshot(sql(db)))
                error("interrupted writer")
            }
        } catch (interrupted: IllegalStateException) { assertEquals("interrupted writer", interrupted.message) }
        assertEquals(before, snapshot(sql(db)))
        try {
            sql(db).execSQL("UPDATE OR ABORT downloads SET id=8 WHERE id=7")
            fail("A real source-row uniqueness conflict must still fail")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(before, snapshot(sql(db)))
    } }

    @Test fun oldRoom65ProductionOpenPreservesDurableStateAndRepairsBeforeFirstDaoWrite() =
        runBlocking(Dispatchers.IO) { diagnosed {
            val before: Map<String, List<List<String?>>>
            helper.createDatabase(name, 65).use { old ->
                seedOld65(old)
                before = snapshot(old)
                assertEquals(6, ownedTriggers(old).size)
                assertTrue(ownedTriggers(old).all { it.contains("INSERT OR REPLACE INTO format_row_authorities") })
            }
            var db = open()
            // A normal DAO is the first operation; no FormatAuthority transaction initializes this DB.
            assertEquals("old-download", db.downloadDao.getDownloadById(7).title)
            assertEquals(before, snapshot(sql(db)))
            val definitions = ownedTriggers(sql(db))
            assertEquals(6, definitions.size)
            assertTrue(definitions.none { it.contains("INSERT OR REPLACE INTO format_row_authorities") })
            val incumbent = stamp(db, "DOWNLOAD", "7")
            assertEquals("old-request", incumbent.owner)
            db.downloadDao.updateWithoutUpsert(db.downloadDao.getDownloadById(7).copy(title = "first DAO write"))
            changed(incumbent, stamp(db, "DOWNLOAD", "7"))
            val after = snapshot(sql(db))
            db.close(); db = open()
            assertEquals("first DAO write", db.downloadDao.getDownloadById(7).title)
            assertEquals(after, snapshot(sql(db)))
            assertEquals(definitions, ownedTriggers(sql(db)))
            assertEquals(listOf(listOf("CREATE TRIGGER unrelated_v35 AFTER UPDATE ON history BEGIN SELECT 1; END")),
                rows(sql(db), "SELECT sql FROM sqlite_master WHERE name='unrelated_v35'"))
        } }

    @Test fun interruptedOld65ReplacementRollsBackThenReopenRepairsAtomically() = runBlocking(Dispatchers.IO) { diagnosed {
        val before = helper.createDatabase(name, 65).use { old ->
            seedOld65(old)
            val snapshotBefore = snapshot(old)
            val definitions = ownedTriggers(old)
            old.beginTransaction()
            try {
                FormatAuthoritySchema.install(old)
                assertNotEquals(definitions, ownedTriggers(old))
                // Simulated interrupted startup: DDL and all backfill are rolled back together.
            } finally { old.endTransaction() }
            assertEquals(definitions, ownedTriggers(old))
            assertEquals(snapshotBefore, snapshot(old))
            snapshotBefore
        }
        var db = open()
        assertEquals("old-download", db.downloadDao.getDownloadById(7).title)
        assertEquals(before, snapshot(sql(db)))
        assertTrue(ownedTriggers(sql(db)).none { it.contains("INSERT OR REPLACE INTO format_row_authorities") })
        db.downloadDao.updateWithoutUpsert(db.downloadDao.getDownloadById(7).copy(title = "after interrupted open"))
        val after = snapshot(sql(db))
        db.close(); db = open()
        assertEquals("after interrupted open", db.downloadDao.getDownloadById(7).title)
        assertEquals(after, snapshot(sql(db)))
    } }

    private fun seedOld65(old: SupportSQLiteDatabase) {
        old.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
            assertTrue(it.moveToFirst()); assertEquals("37e48b53d28d0d9c6d209c7112e95e1c", it.getString(0))
        }
        old.enableWriteAheadLogging()
        PreFixRoom65FormatAuthoritySchema.install(old)
        seedDownload(old, 7); seedResult(old, 9, "old")
        old.execSQL("UPDATE format_row_authorities SET owner='old-request',lineage='old-generation',completedLineage='old-generation' WHERE kind='DOWNLOAD' AND target='7'")
        old.execSQL("INSERT INTO format_foreground_claims VALUES('old-request',1,'retained-foreground')")
        old.execSQL("INSERT INTO format_legacy_debts VALUES('legacy-request','[7]','retained-legacy')")
        old.execSQL("INSERT INTO format_request_cancellations VALUES('cancel-request','cancel-handoff','cancel-generation','PENDING')")
        old.execSQL("INSERT INTO format_recovery_issues VALUES('old-issue','issue-request','LEGACY','retained-issue','[7]','old-handoff','old-generation','OPEN',0)")
        old.execSQL("""INSERT INTO work_manager_handoff_carriers
            (handoffId,kind,generationId,requestId,uniqueWorkName,state,sourceId,confirmedUrl,
             decision,configFingerprint,boundary,notBeforeAt,attempt,createdAt,updatedAt)
            VALUES('old-handoff','FORMAT_BATCH','old-generation','old-request','old-unique','ACCEPTED',
                   0,'','','','old-boundary',0,0,1,1)""")
        old.execSQL("INSERT INTO format_batch_payloads(handoffId,capturedIds,selectedIds,otherIds,notificationId) VALUES('old-handoff','[7]','[7]','[]',17)")
        old.execSQL("CREATE TRIGGER unrelated_v35 AFTER UPDATE ON history BEGIN SELECT 1; END")
    }

    private fun seedDownload(db: SupportSQLiteDatabase, id: Long) {
        val gson = Gson()
        db.execSQL("""INSERT INTO downloads(id,url,title,author,thumb,duration,type,format,allFormats,
            downloadPath,website,downloadSize,playlistTitle,audioPreferences,videoPreferences,
            customFileNameTemplate,SaveThumb,rowNumber,status)
            VALUES(?,'old','old-download','author','','','video',?,'[]','','example.com','','',?,?,'',0,0,'Saved')""",
            arrayOf(id, gson.toJson(Format()), gson.toJson(AudioPreferences()), gson.toJson(VideoPreferences())))
    }

    private fun seedResult(db: SupportSQLiteDatabase, id: Long, url: String) {
        db.execSQL("""INSERT INTO results(id,url,title,author,duration,thumb,website,playlistTitle,formats,creationTime)
            VALUES(?,?,'old-result','author','','','example.com','','[]',1)""", arrayOf<Any>(id, url))
    }

    private fun ownedTriggers(db: SupportSQLiteDatabase): List<String> =
        rows(db, "SELECT sql FROM sqlite_master WHERE type='trigger' AND name IN " +
            "('format_authority_downloads_insert','format_authority_downloads_update','format_authority_downloads_delete'," +
            "'format_authority_results_insert','format_authority_results_update','format_authority_results_delete') ORDER BY name")
            .map { checkNotNull(it.single()) }

    private fun rows(db: SupportSQLiteDatabase, query: String): List<List<String?>> = buildList {
        db.query(query).use { cursor ->
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
        }
    }

    private fun snapshot(db: SupportSQLiteDatabase): Map<String, List<List<String?>>> =
        listOf("downloads", "results", "format_row_authorities", "format_authority_clock", "sqlite_sequence",
            "format_foreground_claims", "format_legacy_debts", "format_recovery_issues",
            "format_request_cancellations", "work_manager_handoff_carriers", "format_batch_payloads")
            .associateWith { rows(db, "SELECT * FROM $it ORDER BY rowid") }
}
