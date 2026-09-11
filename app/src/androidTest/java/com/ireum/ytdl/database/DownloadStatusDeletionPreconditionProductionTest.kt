package com.ireum.ytdl.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryReplacementBarrier
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.download.DownloadIssueCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadStatusDeletionPreconditionProductionTest {
    private lateinit var database: DBManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        DownloadRepository.beforeStatusScopedDeletionTransactionForTesting = null
    }

    @After
    fun tearDown() {
        DownloadRepository.beforeStatusScopedDeletionTransactionForTesting = null
        database.close()
    }

    @Test
    fun staleErroredSnapshotCannotDeleteRequeuedGenerationOrLinkedState() = runBlocking {
        val operationId = "delete-snapshot-error-operation"
        val id = insertDownload(
            status = DownloadRepository.Status.Error,
            operationId = operationId,
            retryAttempt = 1,
        )
        insertLinkedChild(operationId, id)
        val barrier = HistoryReplacementBarrier(
            downloadId = id,
            operationId = operationId,
            historyId = 7001L,
            expectedSourceUrl = "https://example.com/7001",
            expectedType = DownloadType.video.name,
            issueCode = DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH.name,
            issueStage = "HISTORY",
            createdAt = 1L,
        )
        database.historyReplacementBarrierDao.insertIfAbsent(barrier)

        DownloadRepository.beforeStatusScopedDeletionTransactionForTesting = { snapshots ->
            val stale = snapshots.single()
            database.downloadDao.updateRaw(
                stale.copy(
                    status = DownloadRepository.Status.Queued.name,
                    operationId = "delete-snapshot-error-successor",
                    retryAttempt = stale.retryAttempt + 1,
                    executionId = "E2",
                )
            )
            database.lowQualityRedownloadDao.upsertItem(
                database.lowQualityRedownloadDao.getItemByDownloadId(id)!!.copy(
                    itemState = LowQualityRedownloadItemState.ACTIVE.name,
                    updatedAt = 2L,
                )
            )
        }

        assertTrue(DownloadRepository(database).deleteErrored().isEmpty())
        val current = database.downloadDao.getNullableDownloadById(id)
        assertNotNull(current)
        assertEquals(DownloadRepository.Status.Queued.name, current!!.status)
        assertEquals("delete-snapshot-error-successor", current.operationId)
        assertEquals("E2", current.executionId)
        assertEquals(barrier, database.historyReplacementBarrierDao.getByDownloadId(id))
        assertEquals(
            LowQualityRedownloadItemState.ACTIVE,
            database.lowQualityRedownloadDao.getItemByDownloadId(id)!!.stateValue,
        )
    }

    @Test
    fun staleCancelledSnapshotCannotDeleteNewActiveExecution() = runBlocking {
        val id = insertDownload(
            status = DownloadRepository.Status.Cancelled,
            operationId = "cancelled-E1",
        )

        DownloadRepository.beforeStatusScopedDeletionTransactionForTesting = { snapshots ->
            val stale = snapshots.single()
            database.downloadDao.updateRaw(
                stale.copy(
                    status = DownloadRepository.Status.Active.name,
                    operationId = "cancelled-E2",
                    retryAttempt = stale.retryAttempt + 1,
                    executionId = "active-E2",
                )
            )
        }

        DownloadRepository(database).deleteCancelled()
        val current = database.downloadDao.getNullableDownloadById(id)
        assertNotNull(current)
        assertEquals(DownloadRepository.Status.Active.name, current!!.status)
        assertEquals("active-E2", current.executionId)
    }

    @Test
    fun staleQueuedAndScheduledSnapshotsCannotDeleteClaimedRows() = runBlocking {
        val queuedId = insertDownload(
            status = DownloadRepository.Status.Queued,
            operationId = "queued-E1",
        )
        DownloadRepository.beforeStatusScopedDeletionTransactionForTesting = { snapshots ->
            val stale = snapshots.single()
            database.downloadDao.updateRaw(
                stale.copy(
                    status = DownloadRepository.Status.Active.name,
                    operationId = "queued-E2",
                    retryAttempt = stale.retryAttempt + 1,
                    executionId = "queued-E2",
                )
            )
        }
        DownloadRepository(database).deleteQueued()
        assertNotNull(database.downloadDao.getNullableDownloadById(queuedId))

        val scheduledId = insertDownload(
            status = DownloadRepository.Status.Scheduled,
            operationId = "scheduled-E0",
        )
        DownloadRepository.beforeStatusScopedDeletionTransactionForTesting = { snapshots ->
            val stale = snapshots.single()
            database.downloadDao.updateRaw(
                stale.copy(
                    status = DownloadRepository.Status.Scheduled.name,
                    operationId = "scheduled-E1",
                    downloadStartTime = 1234L,
                    executionId = "",
                )
            )
        }
        DownloadRepository(database).deleteScheduled()
        assertNotNull(database.downloadDao.getNullableDownloadById(queuedId))
        assertEquals(
            DownloadRepository.Status.Scheduled.name,
            database.downloadDao.getDownloadById(scheduledId).status,
        )
    }

    @Test
    fun mixedBatchDeletesOnlyCandidatesThatStillMatch() = runBlocking {
        val validId = insertDownload(
            status = DownloadRepository.Status.Error,
            operationId = "batch-valid",
        )
        val staleId = insertDownload(
            status = DownloadRepository.Status.Error,
            operationId = "batch-stale",
        )
        DownloadRepository.beforeStatusScopedDeletionTransactionForTesting = { snapshots ->
            val stale = snapshots.single { it.id == staleId }
            database.downloadDao.updateRaw(
                stale.copy(
                    status = DownloadRepository.Status.Queued.name,
                    operationId = "batch-successor",
                    retryAttempt = stale.retryAttempt + 1,
                    executionId = "batch-E2",
                )
            )
        }

        DownloadRepository(database).deleteErrored()
        assertNull(database.downloadDao.getNullableDownloadById(validId))
        assertNotNull(database.downloadDao.getNullableDownloadById(staleId))
        assertEquals(
            DownloadRepository.Status.Queued.name,
            database.downloadDao.getDownloadById(staleId).status,
        )
    }

    @Test
    fun rowDeletedBeforeTransactionIsSafeNoOp() = runBlocking {
        val id = insertDownload(
            status = DownloadRepository.Status.Error,
            operationId = "deleted-before-boundary",
        )
        DownloadRepository.beforeStatusScopedDeletionTransactionForTesting = { snapshots ->
            database.downloadDao.delete(snapshots.single().id)
        }

        DownloadRepository(database).deleteErrored()

        assertNull(database.downloadDao.getNullableDownloadById(id))
    }

    @Test
    fun cancellationBeforeDestructiveBoundaryDoesNotDeleteLater() = runBlocking {
        val id = insertDownload(
            status = DownloadRepository.Status.Error,
            operationId = "cancelled-before-boundary",
        )
        DownloadRepository.beforeStatusScopedDeletionTransactionForTesting = {
            throw CancellationException("cancelled while preparing cleanup")
        }

        var cancelled = false
        try {
            DownloadRepository(database).deleteErrored()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertNotNull(database.downloadDao.getNullableDownloadById(id))
    }

    @Test
    fun matchingSnapshotDeletesRowAndLinkedStateAtomically() = runBlocking {
        val operationId = "delete-valid-operation"
        val id = insertDownload(
            status = DownloadRepository.Status.Error,
            operationId = operationId,
        )
        insertLinkedChild(operationId, id)
        database.historyReplacementBarrierDao.insertIfAbsent(
            HistoryReplacementBarrier(
                downloadId = id,
                operationId = operationId,
                historyId = 7002L,
                expectedSourceUrl = "https://example.com/7002",
                expectedType = DownloadType.video.name,
                issueCode = DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH.name,
                issueStage = "HISTORY",
                createdAt = 1L,
            )
        )

        DownloadRepository(database).deleteErrored()
        assertNull(database.downloadDao.getNullableDownloadById(id))
        assertNull(database.historyReplacementBarrierDao.getByDownloadId(id))
        assertEquals(
            LowQualityRedownloadItemState.CANCELLED,
            database.lowQualityRedownloadDao.getItemByDownloadId(id)!!.stateValue,
        )
    }

    @Test
    fun explicitDeleteByIdStillDeletesCurrentRowRegardlessOfStatus() = runBlocking {
        val id = insertDownload(
            status = DownloadRepository.Status.Queued,
            operationId = "explicit-current-delete",
        )

        DownloadRepository(database).deleteAllWithIDs(listOf(id))
        assertNull(database.downloadDao.getNullableDownloadById(id))
    }

    private suspend fun insertDownload(
        status: DownloadRepository.Status,
        operationId: String,
        retryAttempt: Int = 0,
    ): Long = database.downloadDao.insert(
        DownloadItem(
            id = 0L,
            url = "https://example.com/$operationId",
            title = "Download $operationId",
            author = "author",
            thumb = "",
            duration = "1:00",
            type = DownloadType.video,
            format = Format(format_id = "best", container = "mp4"),
            container = "mp4",
            downloadSections = "",
            allFormats = mutableListOf(),
            downloadPath = "/downloads/$operationId.mp4",
            website = "example.com",
            downloadSize = "",
            playlistTitle = "",
            audioPreferences = AudioPreferences(),
            videoPreferences = VideoPreferences(),
            extraCommands = "",
            customFileNameTemplate = "%(title)s",
            SaveThumb = false,
            status = status.name,
            downloadStartTime = 0L,
            logID = null,
            operationId = operationId,
            retryAttempt = retryAttempt,
        )
    )

    private suspend fun insertLinkedChild(operationId: String, downloadId: Long) {
        database.lowQualityRedownloadDao.insertOperation(
            LowQualityRedownloadOperation(
                operationId = operationId,
                createdAt = 1L,
                updatedAt = 1L,
            )
        )
        database.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operationId,
                historyId = downloadId,
                intendedSourceUrl = "https://example.com/$downloadId",
                intendedType = DownloadType.video.name,
                itemState = LowQualityRedownloadItemState.ACTIVE.name,
                downloadId = downloadId,
                updatedAt = 1L,
            )
        )
    }
}
