package com.ireum.ytdl.database

import android.content.Context
import android.util.Log
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.ireum.ytdl.database.models.ChapterItem
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.repository.ResultMetadataMergePolicy
import com.ireum.ytdl.database.repository.ResultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ResultChapterConverterProductionWiringTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DBManager::class.java)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private var database: DBManager? = null
    private val ordered = listOf(ChapterItem(0, 9, "intro"), ChapterItem(10, 25, "second"))

    @After fun cleanup() {
        database?.close()
        names.forEach { context.deleteDatabase(it) } // Only UUID-named databases created by this test.
    }

    @Test fun currentDaoPreservesNullEmptyOrderedChaptersAndRepositoryConsumers() = runBlocking(Dispatchers.IO) { diagnosed {
        val name = newName()
        var db = open(name)
        val inputs = listOf(result(1, null), result(2, emptyList()), result(3, ordered))
        val repository = ResultRepository(db.resultDao, db.commandTemplateDao, context)
        inputs.forEach { repository.insert(it) }
        assertEquals("null", rawChapters(db, 1))
        assertEquals("[]", rawChapters(db, 2))
        inputs.forEach { assertEquals(it, repository.getItemByID(it.id)) }
        val observed = withTimeout(5_000) { repository.allResults.first() }.sortedBy { it.id }
        assertEquals(inputs, observed)
        assertNull(observed[0].chapters)
        assertEquals(emptyList<ChapterItem>(), observed[1].chapters)
        assertEquals(ordered, observed[2].chapters)
        assertEquals(ordered, ResultMetadataMergePolicy.merge(observed[0], observed[2]).chapters)
        assertNull(ResultMetadataMergePolicy.merge(observed[0], observed[0]).chapters)
        val changed = observed[0].copy(chapters = ordered)
        repository.update(changed)
        assertEquals(changed, repository.getItemByURL(changed.url))
        repository.update(changed.copy(chapters = null))
        assertNull(repository.getItemByID(changed.id)!!.chapters)
        db.close(); db = open(name)
        inputs.forEach { assertEquals(it, db.resultDao.getResultByID(it.id)) }
    } }

    @Test fun sqlNullAndJsonNullRemainNullAndDistinctFromEmptyAfterReopen() = runBlocking(Dispatchers.IO) { diagnosed {
        val name = newName()
        var db = open(name)
        (1L..4L).forEach { db.resultDao.insert(result(it, null)) }
        db.openHelper.writableDatabase.execSQL("UPDATE results SET chapters=NULL WHERE id=1")
        db.openHelper.writableDatabase.execSQL("UPDATE results SET chapters=' null ' WHERE id=3")
        db.openHelper.writableDatabase.execSQL("UPDATE results SET chapters='[]' WHERE id=4")
        repeat(2) {
            assertNull(rawChapters(db, 1))
            assertEquals("null", rawChapters(db, 2))
            assertEquals(" null ", rawChapters(db, 3))
            for (id in 1L..3L) assertNull(db.resultDao.getResultByID(id)!!.chapters)
            assertEquals(emptyList<ChapterItem>(), db.resultDao.getResultByID(4)!!.chapters)
            if (it == 0) { db.close(); db = open(name) }
        }
    } }

    @Test fun corruptChapterCarriersFailDaoReadWithoutBeingRewritten() = runBlocking(Dispatchers.IO) { diagnosed {
        val db = open(newName())
        db.resultDao.insert(result(1, null))
        for (corrupt in listOf("", " ", "[{", "{}", "\"null\"", "null trailing", "[1]")) {
            db.openHelper.writableDatabase.execSQL("UPDATE results SET chapters=? WHERE id=1", arrayOf(corrupt))
            val error = try {
                db.resultDao.getResultByID(1)
                null
            } catch (error: Exception) { error }
            assertNotNull("Corrupt chapter JSON must remain observable: '$corrupt'", error)
            assertEquals(corrupt, rawChapters(db, 1))
        }
        db.openHelper.writableDatabase.execSQL("UPDATE results SET chapters='null' WHERE id=1")
        assertNull(db.resultDao.getResultByID(1)!!.chapters)
    } }

    @Test fun oldRoom61PreChangeChapterRowsSurviveUpgradeAndReopen() = oldRows(61)
    @Test fun oldRoom63PreChangeChapterRowsSurviveUpgradeAndReopen() = oldRows(63)
    @Test fun oldRoom64PreChangeChapterRowsSurviveUpgradeAndReopen() = oldRows(64)
    @Test fun oldRoom65PreChangeChapterRowsSurviveUpgradeAndReopen() = oldRows(65)

    private fun oldRows(version: Int) = runBlocking(Dispatchers.IO) { diagnosed {
        val name = newName()
        // Historical schema and pre-change Gson writer, never the current Room writer.
        val carriers = listOf(null, Gson().toJson(null as List<ChapterItem>?),
            Gson().toJson(emptyList<ChapterItem>()), Gson().toJson(ordered))
        helper.createDatabase(name, version).use { old ->
            if (version == 65) PreFixRoom65FormatAuthoritySchema.install(old)
            carriers.forEachIndexed { index, carrier -> seedOldResult(old, index + 1L, carrier) }
        }
        var db = open(name)
        repeat(2) { pass ->
            carriers.forEachIndexed { index, carrier ->
                val id = index + 1L
                assertEquals(carrier, rawChapters(db, id))
                val expected = when (id) { 1L, 2L -> null; 3L -> emptyList(); else -> ordered }
                assertEquals(result(id, expected), db.resultDao.getResultByID(id))
            }
            if (pass == 0) { db.close(); db = open(name) }
        }
        Log.i("ChapterConverterV39", "old schema $version SQL NULL/JSON null/empty/ordered DAO roundtrip and reopen passed")
    } }

    private fun seedOldResult(old: SupportSQLiteDatabase, id: Long, chapters: String?) {
        old.execSQL("""INSERT INTO results(id,url,title,author,duration,thumb,website,playlistTitle,
            formats,urls,chapters,creationTime) VALUES(?,?,'cached','author','','','example.com','','[]','',?,1)""",
            arrayOf<Any?>(id, "https://example.com/chapter/$id", chapters))
    }

    private fun result(id: Long, chapters: List<ChapterItem>?) = ResultItem(
        id = id, url = "https://example.com/chapter/$id", title = "cached", author = "author",
        duration = "", thumb = "", website = "example.com", playlistTitle = "", formats = emptyList(),
        urls = "", chapters = chapters, creationTime = 1,
    )

    private fun newName(): String = "chapter-converter-v39-${UUID.randomUUID()}".also { names.add(it) }

    private fun open(name: String): DBManager = DBManager.create(context, name).also { database = it }

    private fun rawChapters(db: DBManager, id: Long): String? =
        db.openHelper.readableDatabase.query("SELECT chapters FROM results WHERE id=?", arrayOf(id)).use {
            assertTrue(it.moveToFirst())
            if (it.isNull(0)) null else it.getString(0)
        }

    private suspend fun diagnosed(block: suspend () -> Unit) {
        try { block() } catch (error: Throwable) {
            Log.e("ChapterConverterV39", "Preserved first failure before teardown", error)
            throw error
        }
    }
}
