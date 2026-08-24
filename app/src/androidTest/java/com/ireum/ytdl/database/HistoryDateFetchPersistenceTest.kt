package com.ireum.ytdl.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryDateFetchItemState
import com.ireum.ytdl.database.models.HistoryDateFetchOperationState
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.repository.HistoryDateFetchRepository
import com.ireum.ytdl.util.HistoryDateLookupOrigin
import com.ireum.ytdl.util.HistoryDateLookupResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDateFetchPersistenceTest {
    private lateinit var database: DBManager
    private lateinit var repository: HistoryDateFetchRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        repository = HistoryDateFetchRepository(database)
    }

    @After
    fun closeDatabase() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun createOrReconnectSnapshotsCandidatesAndRepositoryRecreationKeepsIdentity() = runBlocking {
        insertHistory(1, "example.com/a")
        insertHistory(2, "https://example.com/a")

        val first = repository.createOrReconnect(now = 100)
        val reconstructed = HistoryDateFetchRepository(database)
        val second = reconstructed.createOrReconnect(now = 200)

        assertEquals(first.operationId, second.operationId)
        assertEquals(2, first.candidateCount)
        assertEquals(1, first.uniqueSourceCount)
        assertEquals(1, first.duplicateCoalesced)
        assertEquals(2, reconstructed.getPendingItems(first.operationId).size)
        assertEquals(1, rowCount("history_date_fetch_operations"))
        assertEquals(2, rowCount("history_date_fetch_items"))
    }

    @Test
    fun interruptedPendingItemsRecoverWhileCompletedItemsAreNotRepeated() = runBlocking {
        insertHistory(1, "https://example.com/a")
        insertHistory(2, "https://example.com/b")
        val operation = repository.createOrReconnect(now = 100)
        val pending = repository.getPendingItems(operation.operationId)

        repository.checkpointSourceGroup(
            operation.operationId,
            listOf(pending.first()),
            HistoryDateLookupResult(-123L, HistoryDateLookupOrigin.LOCAL),
            elapsedMs = 5,
            now = 200,
        )

        val recovered = HistoryDateFetchRepository(database).getPendingItems(operation.operationId)
        assertEquals(listOf(2L), recovered.map { it.historyId })
        assertEquals(-123L, database.historyDao.getItem(1).mediaPublishedAt)
    }

    @Test
    fun checkpointAtomicallyProtectsChangedSourceAndExistingDate() = runBlocking {
        insertHistory(1, "https://example.com/a")
        insertHistory(2, "https://example.com/b")
        val operation = repository.createOrReconnect(now = 100)
        val items = repository.getPendingItems(operation.operationId).associateBy { it.historyId }
        database.historyDao.updateRaw(database.historyDao.getItem(1).copy(url = "https://example.com/changed"))
        database.historyDao.updateRaw(database.historyDao.getItem(2).copy(mediaPublishedAt = 777L))

        repository.checkpointSourceGroup(
            operation.operationId,
            listOf(items.getValue(1)),
            HistoryDateLookupResult(999L, HistoryDateLookupOrigin.MINIMAL, extractorLaunches = 1),
            elapsedMs = 10,
            now = 200,
        )
        repository.checkpointSourceGroup(
            operation.operationId,
            listOf(items.getValue(2)),
            HistoryDateLookupResult(999L, HistoryDateLookupOrigin.MINIMAL, extractorLaunches = 1),
            elapsedMs = 10,
            now = 210,
        )

        val outcomes = database.historyDateFetchDao.getItems(operation.operationId)
            .associateBy { it.historyId }
        assertEquals(HistoryDateFetchItemState.SKIPPED, outcomes.getValue(1).stateValue)
        assertEquals(HistoryDateFetchRepository.REASON_SOURCE_CHANGED, outcomes.getValue(1).reasonCode)
        assertEquals(HistoryDateFetchItemState.SKIPPED, outcomes.getValue(2).stateValue)
        assertEquals(777L, database.historyDao.getItem(2).mediaPublishedAt)
        assertEquals(0L, database.historyDao.getItem(1).mediaPublishedAt)
    }

    @Test
    fun cancellationIsPersistedAndTerminalRowsAreExcludedFromRecovery() = runBlocking {
        insertHistory(1, "https://example.com/a")
        val operation = repository.createOrReconnect(now = 100)

        assertTrue(repository.requestCancellation(operation.operationId))
        assertTrue(repository.finishCancellation(operation.operationId))

        val terminal = repository.getOperation(operation.operationId)!!
        assertTrue(terminal.cancelRequested)
        assertEquals(HistoryDateFetchOperationState.CANCELLED, terminal.stateValue)
        assertEquals(
            HistoryDateFetchItemState.CANCELLED,
            database.historyDateFetchDao.getItems(operation.operationId).single().stateValue,
        )
        assertTrue(repository.getNonterminalOperations().isEmpty())
        assertNull(repository.getActiveOperation())

        val next = repository.createOrReconnect(now = 300)
        assertNotEquals(operation.operationId, next.operationId)
    }

    @Test
    fun workerFinalizerHonorsCancellationAfterLastPendingItemWasCheckpointed() = runBlocking {
        insertHistory(1, "https://example.com/a")
        val operation = repository.createOrReconnect(now = 100)
        val pending = repository.getPendingItems(operation.operationId).single()
        assertTrue(
            repository.checkpointSourceGroup(
                operation.operationId,
                listOf(pending),
                HistoryDateLookupResult(-123L, HistoryDateLookupOrigin.LOCAL),
                elapsedMs = 5,
                now = 200,
            )
        )
        assertTrue(repository.getPendingItems(operation.operationId).isEmpty())
        assertTrue(repository.requestCancellation(operation.operationId))
        assertEquals(
            HistoryDateFetchOperationState.RUNNING,
            repository.getOperation(operation.operationId)?.stateValue,
        )

        val reconstructed = HistoryDateFetchRepository(database)
        assertEquals(
            HistoryDateFetchOperationState.CANCELLED,
            reconstructed.finalizeWorkerRun(operation.operationId),
        )

        val child = database.historyDateFetchDao.getItems(operation.operationId).single()
        assertEquals(HistoryDateFetchItemState.UPDATED, child.stateValue)
        val terminal = reconstructed.getOperation(operation.operationId)!!
        assertEquals(HistoryDateFetchOperationState.CANCELLED, terminal.stateValue)
        assertEquals(HistoryDateFetchRepository.REASON_USER_CANCELLED, terminal.terminalReason)
        assertNull(reconstructed.getActiveOperation())

        val next = reconstructed.createOrReconnect(now = 300)
        assertNotEquals(operation.operationId, next.operationId)
    }

    @Test
    fun repeatedNoDateRunsStayBoundedPreserveHistoryAndKeepNewestSummary() = runBlocking {
        insertHistory(1, "https://example.com/a")
        val originalHistory = database.historyDao.getItem(1)
        val operationIds = mutableListOf<String>()

        repeat(4) { index ->
            val operation = repository.createOrReconnect(now = 100L + index * 100L)
            operationIds += operation.operationId
            assertEquals(1, operation.candidateCount)
            val pending = repository.getPendingItems(operation.operationId).single()
            assertTrue(
                repository.checkpointSourceGroup(
                    operation.operationId,
                    listOf(pending),
                    HistoryDateLookupResult(origin = HistoryDateLookupOrigin.NONE),
                    elapsedMs = 5,
                    now = 150L + index * 100L,
                )
            )
            assertTrue(repository.finishCompleted(operation.operationId))
            assertEquals(originalHistory, database.historyDao.getItem(1))
        }

        assertEquals(2, rowCount("history_date_fetch_operations"))
        assertEquals(2, rowCount("history_date_fetch_items"))
        assertNull(repository.getOperation(operationIds.first()))
        assertTrue(database.historyDateFetchDao.getItems(operationIds.first()).isEmpty())
        assertTrue(repository.getOperation(operationIds[2]) != null)

        val newest = repository.getOperation(operationIds.last())!!
        assertEquals(HistoryDateFetchOperationState.COMPLETED, newest.stateValue)
        assertEquals(operationIds.last(), repository.currentOperation.first()?.operationId)
        assertEquals(
            HistoryDateFetchItemState.NO_DATE,
            database.historyDateFetchDao.getItems(newest.operationId).single().stateValue,
        )
    }

    private fun rowCount(table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun insertHistory(id: Long, url: String) {
        database.historyDao.insertRaw(
            HistoryItem(
                id = id,
                url = url,
                title = "Item $id",
                author = "Creator",
                duration = "00:01:00",
                thumb = "",
                type = DownloadType.video,
                time = 1000 + id,
                downloadPath = emptyList(),
                website = "example",
                format = Format(format_id = "best"),
                downloadId = 0,
            )
        )
    }
}
