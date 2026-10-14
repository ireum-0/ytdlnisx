package com.ireum.ytdl.work

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.Operation
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.utils.futures.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.Gson
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.Migrations
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.FormatRecoveryIssue
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FormatBatchMigrationProductionWiringTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DBManager::class.java)
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private var database: DBManager? = null
    private val name = "format-batch-old-writer-migration"

    @After fun cleanup() {
        WorkManagerHandoffRecovery.clearForTesting()
        database?.close()
        context.deleteDatabase(name) // Only this newly-created migration fixture.
    }

    @Test fun version63UpgradePreservesOldPendingCarrierAndReplaysItsExactRequest() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        seedOldWriter(63, requestId, "HARD_SUB_SCAN", "PENDING_ENQUEUE", "")
        upgrade()
        val db = database!!
        val old = db.workManagerHandoffCarrierDao.get("old-owner")!!
        assertEquals("old-generation", old.generationId)
        assertEquals(requestId, old.requestId)
        assertNull(db.formatBatchPayloadDao.get("old-owner"))
        val published = Channel<Pair<String, Operation>>(1)
        WorkManagerHandoffRecovery.databaseForTesting = db
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, request ->
            ImmediateOperation().also { check(published.trySend(request.id.toString() to it).isSuccess) }
        }
        WorkManagerHandoffRecovery.reconcile(context)
        assertEquals(requestId, withTimeout(5_000) { published.receive() }.first)
        WorkManagerHandoffRecovery.enqueueAndAwait(context, "old-owner").await()
        assertNull(db.workManagerHandoffCarrierDao.get("old-owner")) // Existing hard-sub acceptance contract.
    }

    @Test fun oldestCarrierSchema61AcceptedSchedulerStillRetainsMissingWorkInfo() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        seedOldWriter(61, requestId, "SCHEDULE_START", "ACCEPTED", "START")
        upgrade()
        val db = database!!
        WorkManagerHandoffRecovery.databaseForTesting = db
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, _ -> error("accepted old carrier must not duplicate") }
        repeat(2) { WorkManagerHandoffRecovery.reconcile(context) }
        val old = db.workManagerHandoffCarrierDao.get("old-owner")!!
        assertEquals(requestId, old.requestId)
        assertEquals("old-generation", old.generationId)
        assertEquals(0L, old.sourceConfigurationGeneration)
        assertEquals(WorkManagerHandoffCarrier.ACCEPTED, old.state)
        assertNull(db.formatBatchPayloadDao.get("old-owner"))
    }

    @Test fun oldWriter61AcceptedTerminalSchedulerUsesPreservedRetryContract() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        seedOldWriter(61, requestId, "SCHEDULE_END", "ACCEPTED", "END")
        upgrade()
        val db = database!!
        val published = Channel<String>(1)
        WorkManagerHandoffRecovery.databaseForTesting = db
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { id ->
            if (id == requestId) WorkInfo(UUID.fromString(id), WorkInfo.State.FAILED, emptySet(), Data.EMPTY, Data.EMPTY, 0, 0) else null
        }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, request ->
            ImmediateOperation().also { check(published.trySend(request.id.toString()).isSuccess) }
        }
        WorkManagerHandoffRecovery.reconcile(context)
        val retryId = withTimeout(5_000) { published.receive() }
        WorkManagerHandoffRecovery.enqueueAndAwait(context, "old-owner").await()
        val retry = db.workManagerHandoffCarrierDao.get("old-owner")!!
        assertNotEquals(requestId, retryId)
        assertEquals(retryId, retry.requestId)
        assertEquals("old-generation", retry.generationId)
        assertEquals(1, retry.attempt)
        assertEquals(WorkManagerHandoffCarrier.ACCEPTED, retry.state)
    }

    @Test fun formatIssueUpgradePreservesOriginal64AndUnownedRows() = runBlocking {
        val request = UUID.randomUUID().toString()
        helper.createDatabase(name, 64).use { old ->
            old.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                assertTrue(it.moveToFirst())
                assertEquals("d1f2c58653c654b391c371527e3f0da5", it.getString(0))
            }
            seedHistoricalRows(old)
            old.execSQL("""
                INSERT INTO work_manager_handoff_carriers
                    (handoffId,kind,generationId,requestId,uniqueWorkName,state,sourceId,confirmedUrl,
                     decision,configFingerprint,boundary,notBeforeAt,attempt,createdAt,updatedAt)
                VALUES ('old-format','FORMAT_BATCH','old-generation',?,'format_batch_old-format','ACCEPTED',
                        0,'','','','old-format',0,0,1,1)
            """.trimIndent(), arrayOf(request))
            old.execSQL("INSERT INTO format_batch_payloads(handoffId,capturedIds,selectedIds,otherIds,notificationId) VALUES('old-format','[7]','[7]','[]',17)")
            old.query("SELECT count(*) FROM sqlite_master WHERE name='format_recovery_issues'").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
        upgrade()
        val db = database!!
        assertEquals("[]", db.formatBatchPayloadDao.get("old-format")?.authority)
        assertEquals("", db.formatAuthorityDao.get("DOWNLOAD", "7")?.owner)
        val before = historicalRows(db.openHelper.readableDatabase)
        WorkManagerHandoffRecovery.databaseForTesting = db
        WorkManagerHandoffRecovery.workInfoOverrideForTesting = { null }
        WorkManagerHandoffRecovery.enqueueOverrideForTesting = { _, _, _ -> error("Unproven original64 members cannot enqueue") }
        WorkManagerHandoffRecovery.reconcile(context)
        val issue = db.formatAuthorityDao.recoveryIssues().single()
        assertEquals(request, issue.requestId)
        assertEquals("old-format", issue.handoffId)
        assertEquals(FormatRecoveryIssue.OPEN, issue.state)
        assertEquals(before, historicalRows(db.openHelper.readableDatabase))
        assertTrue(FormatRecoveryIssues.acknowledge(context, db, issue))
        assertEquals(WorkManagerHandoffCarrier.RESOLVED, db.workManagerHandoffCarrierDao.get("old-format")?.state)
        assertEquals(before, historicalRows(db.openHelper.readableDatabase))
    }

    @Test fun historical61And63NumericInputsNeverAcquireCurrentMemberAuthority() = runBlocking {
        for (version in listOf(61, 63)) {
            val oldRequest = OneTimeWorkRequestBuilder<UpdateMultipleDownloadsFormatsWorker>()
                .setInputData(Data.Builder().putLongArray("ids", longArrayOf(7)).build()).build()
            helper.createDatabase(name, version).use { old -> seedHistoricalRows(old) }
            upgrade()
            val db = database!!
            WorkManagerHandoffRecovery.databaseForTesting = db
            val before = historicalRows(db.openHelper.readableDatabase)
            val manager = WorkManager.getInstance(context)
            withContext(Dispatchers.IO) { manager.enqueue(oldRequest).result.get(5, TimeUnit.SECONDS) }
            val finished = withTimeout(15_000) {
                var info: WorkInfo? = null
                while (info?.state?.isFinished != true) {
                    info = withContext(Dispatchers.IO) { manager.getWorkInfoById(oldRequest.id).get(5, TimeUnit.SECONDS) }
                    if (info?.state?.isFinished != true) delay(10)
                }
                checkNotNull(info)
            }
            assertEquals(WorkInfo.State.FAILED, finished.state)
            val issue = FormatRecoveryIssues.discoverRetained(context, db).single()
            assertEquals(oldRequest.id.toString(), issue.requestId)
            assertEquals("", issue.handoffId)
            assertEquals("", db.formatAuthorityDao.get("DOWNLOAD", "7")?.owner)
            assertEquals(before, historicalRows(db.openHelper.readableDatabase))
            assertTrue(FormatRecoveryIssues.acknowledge(context, db, issue))
            assertEquals(before, historicalRows(db.openHelper.readableDatabase))
            WorkManagerHandoffRecovery.clearForTesting()
            db.close(); database = null
            context.deleteDatabase(name) // Only this method's old-schema fixture.
        }
    }

    @Test fun rowlessIssueSurvivesDatabaseReopen() = runBlocking {
        helper.createDatabase(name, 63).use { old -> seedHistoricalRows(old) }
        upgrade()
        val request = UUID.randomUUID().toString()
        val issue = FormatAuthority.retainLegacy(context, database!!, request, listOf(7), "LEGACY_IDS_REQUIRE_CURRENT_PRODUCER")
        database!!.close()
        database = Room.databaseBuilder(context, DBManager::class.java, name)
            .addTypeConverter(Converters()).addMigrations(*Migrations.migrationList).allowMainThreadQueries().build()
        assertEquals(listOf(issue), FormatRecoveryIssues.discoverRetained(context, database!!))
        assertTrue(FormatRecoveryIssues.acknowledge(context, database!!, issue))
        database!!.close()
        database = Room.databaseBuilder(context, DBManager::class.java, name)
            .addTypeConverter(Converters()).addMigrations(*Migrations.migrationList).allowMainThreadQueries().build()
        assertTrue(FormatRecoveryIssues.discoverRetained(context, database!!).isEmpty())
        assertEquals(FormatRecoveryIssue.ACKNOWLEDGED, database!!.formatAuthorityDao.recoveryIssue(issue.issueId)?.state)
        assertEquals("", database!!.formatAuthorityDao.get("DOWNLOAD", "7")?.owner)
    }

    /** Explicit old61/63/original64 columns; no new authority/debt/issue writer seeds the old state. */
    private fun seedHistoricalRows(old: SupportSQLiteDatabase) {
        val gson = Gson()
        old.execSQL("""
            INSERT INTO downloads(id,url,title,author,thumb,duration,type,format,allFormats,
                downloadPath,website,downloadSize,playlistTitle,audioPreferences,videoPreferences,
                customFileNameTemplate,SaveThumb,rowNumber,status)
            VALUES(7,?,'old-download','old-author','','','video',?,'[]','','example.com','','',?,?,'',0,0,'Saved')
        """.trimIndent(), arrayOf("https://example.com/old", gson.toJson(Format()),
            gson.toJson(AudioPreferences()), gson.toJson(VideoPreferences())))
        old.execSQL("""
            INSERT INTO results(id,url,title,author,duration,thumb,website,playlistTitle,formats,creationTime)
            VALUES(9,'https://example.com/old','old-cache','old-author','','','example.com','','[]',1)
        """.trimIndent())
    }

    private fun historicalRows(db: SupportSQLiteDatabase): List<String> = buildList {
        db.query("SELECT url,status,format,allFormats FROM downloads WHERE id=7").use {
            check(it.moveToFirst()); repeat(it.columnCount) { index -> add(it.getString(index)) }
        }
        db.query("SELECT url,formats FROM results WHERE id=9").use {
            check(it.moveToFirst()); repeat(it.columnCount) { index -> add(it.getString(index)) }
        }
    }

    private fun seedOldWriter(version: Int, requestId: String, kind: String, state: String, boundary: String) {
        WorkManagerHandoffRecovery.clearForTesting()
        helper.createDatabase(name, version).use { db ->
            // Original 61 writer columns: intentionally no later origin/configuration or format fields.
            db.execSQL("""
                INSERT INTO work_manager_handoff_carriers (
                    handoffId, kind, generationId, requestId, uniqueWorkName, state,
                    sourceId, confirmedUrl, decision, configFingerprint, boundary,
                    notBeforeAt, attempt, createdAt, updatedAt
                ) VALUES (?, ?, 'old-generation', ?, 'old-unique-name', ?, 0, '', '', '', ?, 0, 0, 1, 1)
            """.trimIndent(), arrayOf("old-owner", kind, requestId, state, boundary))
        }
    }
    private fun upgrade() {
        helper.runMigrationsAndValidate(name, 65, true, *Migrations.migrationList).close()
        database = Room.databaseBuilder(context, DBManager::class.java, name)
            .addTypeConverter(Converters()).addMigrations(*Migrations.migrationList).allowMainThreadQueries().build()
    }
    private class ImmediateOperation : Operation {
        private val future = SettableFuture.create<Operation.State.SUCCESS>().also { it.set(Operation.SUCCESS) }
        override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = future
        override fun getState(): LiveData<Operation.State> = MutableLiveData(Operation.SUCCESS)
    }
}
