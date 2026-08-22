package com.ireum.ytdl.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.HistoryReplacementAuthorization
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryReplacementQualityAuthorityLostException
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.database.viewmodel.HistoryRedownloadRestorePolicy
import com.ireum.ytdl.util.BackupSettingsUtil
import com.ireum.ytdl.util.HistoryRedownloadMarker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryReplacementBarrierPersistenceTest {
    private lateinit var db: DBManager

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DBManager::class.java,
        ).addTypeConverter(Converters()).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun sourceMismatchBarrierSurvivesPauseAndMutableRetryFields() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        val before = db.historyDao.getItem(historyId)

        assertEquals(
            HistoryReplacementAuthorization.SourceMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = OTHER_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = item.operationId,
            )
        )
        val barrier = db.historyReplacementBarrierDao.getByDownloadId(item.id)
        assertNotNull(barrier)
        assertEquals(
            HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE).code.name,
            barrier!!.issueCode,
        )

        db.downloadDao.setStatus(item.id, DownloadRepository.Status.Paused.name)
        db.downloadDao.update(item.copy(type = DownloadType.audio, status = DownloadRepository.Status.Queued.name))

        val afterStaleWrite = db.downloadDao.getDownloadById(item.id)
        assertEquals(DownloadRepository.Status.Paused.name, afterStaleWrite.status)
        assertEquals(item.playlistURL, afterStaleWrite.playlistURL)
        // The authoritative mismatch is carried by the separate durable
        // barrier.  A Paused row is allowed to retain its user-visible state;
        // stale full-row writers must not erase the barrier or project a
        // misleading terminal Download issue into the row.
        assertEquals("", afterStaleWrite.lastIssueCode)
        assertEquals("", afterStaleWrite.lastIssueStage)
        assertEquals(barrier!!.issueCode, db.historyReplacementBarrierDao.getByDownloadId(item.id)?.issueCode)
        assertEquals(barrier!!.issueStage, db.historyReplacementBarrierDao.getByDownloadId(item.id)?.issueStage)

        assertEquals(
            HistoryReplacementAuthorization.SourceMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = item.operationId,
            )
        )
        assertEquals(before.downloadPath, db.historyDao.getItem(historyId).downloadPath)
        assertEquals(before.url, db.historyDao.getItem(historyId).url)
    }

    @Test
    fun typeMismatchBarrierCannotBeReauthorizedAfterTypeChange() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.audio)

        assertEquals(
            HistoryReplacementAuthorization.TypeMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.audio,
                replacementDownloadId = item.id,
                replacementOperationId = item.operationId,
            )
        )
        db.downloadDao.update(item.copy(type = DownloadType.video))
        assertEquals(DownloadType.audio, db.downloadDao.getDownloadById(item.id).type)

        assertEquals(
            HistoryReplacementAuthorization.TypeMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = item.operationId,
            )
        )
        assertEquals(listOf("/tmp/previous.mp4"), db.historyDao.getItem(historyId).downloadPath)
    }

    @Test
    fun targetMissingBarrierSurvivesHistoryRecreationWithTheSameId() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        db.historyDao.deleteById(historyId)

        assertEquals(
            HistoryReplacementAuthorization.TargetMissing,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = item.operationId,
            )
        )
        assertEquals(
            "HISTORY_TARGET_DELETED",
            db.historyReplacementBarrierDao.getByDownloadId(item.id)?.issueCode,
        )

        db.historyDao.insertRaw(history().copy(id = historyId))

        assertEquals(
            HistoryReplacementAuthorization.TargetMissing,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = item.operationId,
            )
        )
        assertEquals(
            listOf("/tmp/previous.mp4"),
            db.historyDao.getItem(historyId).downloadPath,
        )
    }

    @Test
    fun qualityMarkerBackupWithoutLedgerIsRevokedBeforeRestore() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        db.downloadDao.update(
            item.copy(playlistURL = HistoryRedownloadMarker.quality(historyId, 720))
        )

        val backupItem = DownloadRepository(db).getQueuedDownloadsForBackup().single()
        assertTrue(DownloadRepository(db).getQueuedDownloadsList().isEmpty())
        assertEquals(0, db.downloadDao.reQueueDownloadItems(listOf(item.id)))
        assertEquals(0, db.downloadDao.updateItemsToProcessing(listOf(item.id)))
        assertEquals(0, db.downloadDao.rescheduleQueuedOrScheduled(item.id, 1L))
        db.downloadDao.update(item.copy(playlistURL = HistoryRedownloadMarker.regular(historyId)))
        assertEquals(1, db.downloadDao.reQueueDownloadItems(listOf(item.id)))
        val revoked = HistoryRedownloadRestorePolicy.revokeOrphanQualityMarker(
            item = backupItem.copy(id = 0L),
            hasPersistedRefusal = false,
        )

        assertEquals("", revoked?.playlistURL)
        assertEquals(DownloadRepository.Status.Error.name, revoked?.status)
        assertEquals(
            com.ireum.ytdl.util.download.DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED.name,
            revoked?.lastIssueCode,
        )
        val restoredId = DownloadRepository(db).insertRestoredDownload(revoked!!, null)
        assertEquals("", db.downloadDao.getDownloadById(restoredId).playlistURL)

        val regular = backupItem.copy(
            id = 0L,
            playlistURL = HistoryRedownloadMarker.regular(historyId),
        )
        assertEquals(
            null,
            HistoryRedownloadRestorePolicy.revokeOrphanQualityMarker(
                regular,
                hasPersistedRefusal = false,
            )
        )
    }

    @Test
    fun qualityHistoryReplacementRequiresLiveImmutableLedgerAuthority() = runBlocking {
        val historyId = insertHistory()
        val operation = LowQualityRedownloadRepository(db).createOrReconnect(now = 10L)
        val downloadOperationId = "quality-download-operation-$historyId"
        val item = insertDownload(DownloadType.video)
        db.downloadDao.update(
            item.copy(
                playlistURL = HistoryRedownloadMarker.quality(historyId, 720),
                operationId = downloadOperationId,
            )
        )
        assertNotEquals(operation.operationId, downloadOperationId)
        db.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operation.operationId,
                historyId = historyId,
                intendedSourceUrl = HISTORY_URL,
                intendedType = DownloadType.video.name,
                selected = true,
                itemState = LowQualityRedownloadItemState.QUEUED.name,
                downloadId = item.id,
            )
        )

        assertEquals(
            1,
            db.downloadDao.claimDownloadForWorker(
                id = item.id,
                expectedOperationId = downloadOperationId,
                expectedRetryAttempt = item.retryAttempt,
                executionId = "quality-authority-worker",
            ),
        )

        assertEquals(
            HistoryReplacementAuthorization.Authorized::class.java,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = downloadOperationId,
                expectedExecutionId = "quality-authority-worker",
            )::class.java,
        )

        db.lowQualityRedownloadDao.finishOperation(
            operation.operationId,
            com.ireum.ytdl.database.models.LowQualityRedownloadOperationState.COMPLETED.name,
            "done",
            11L,
        )
        assertEquals(0, db.downloadDao.reQueueDownloadItems(listOf(item.id)))
        var rejected = false
        try {
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = downloadOperationId,
                expectedExecutionId = "quality-authority-worker",
            )
        } catch (_: HistoryReplacementQualityAuthorityLostException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun qualityAuthorityRejectsWrongDownloadLineageWithoutHistoryMutation() = runBlocking {
        val historyId = insertHistory()
        val parentOperation = LowQualityRedownloadRepository(db).createOrReconnect(now = 20L)
        val downloadOperationId = "quality-download-lineage-$historyId"
        val item = insertDownload(DownloadType.video)
        db.downloadDao.update(
            item.copy(
                playlistURL = HistoryRedownloadMarker.quality(historyId, 720),
                operationId = downloadOperationId,
            )
        )
        db.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = parentOperation.operationId,
                historyId = historyId,
                intendedSourceUrl = HISTORY_URL,
                intendedType = DownloadType.video.name,
                selected = true,
                itemState = LowQualityRedownloadItemState.QUEUED.name,
                downloadId = item.id,
            )
        )
        assertEquals(
            1,
            db.downloadDao.claimDownloadForWorker(
                id = item.id,
                expectedOperationId = downloadOperationId,
                expectedRetryAttempt = item.retryAttempt,
                executionId = "quality-lineage-worker",
            ),
        )
        val before = db.historyDao.getItem(historyId)
        var rejected = false
        try {
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = "wrong-download-lineage",
                expectedExecutionId = "quality-lineage-worker",
            )
        } catch (_: com.ireum.ytdl.database.repository.HistoryReplacementExecutionOwnershipLostException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(before, db.historyDao.getItem(historyId))
    }

    @Test
    fun qualityAuthorityRejectsMissingAndTerminalLedgerAuthority() = runBlocking {
        val missingHistoryId = insertHistory()
        val missingDownloadOperationId = "quality-missing-download-$missingHistoryId"
        val missingItem = insertDownload(DownloadType.video)
        db.downloadDao.update(
            missingItem.copy(
                playlistURL = HistoryRedownloadMarker.quality(missingHistoryId, 720),
                operationId = missingDownloadOperationId,
            )
        )
        assertEquals(
            0,
            db.downloadDao.claimDownloadForWorker(
                id = missingItem.id,
                expectedOperationId = missingDownloadOperationId,
                expectedRetryAttempt = missingItem.retryAttempt,
                executionId = "quality-missing-worker",
            ),
        )
        // Bypass runnable admission only to exercise the destructive-boundary
        // defense-in-depth check below.
        db.downloadDao.update(
            missingItem.copy(
                playlistURL = HistoryRedownloadMarker.quality(missingHistoryId, 720),
                operationId = missingDownloadOperationId,
                status = DownloadRepository.Status.Active.name,
                executionId = "quality-missing-worker",
            )
        )
        var missingRejected = false
        try {
            repository().authorizeHistoryReplacement(
                historyId = missingHistoryId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = missingItem.id,
                replacementOperationId = missingDownloadOperationId,
                expectedExecutionId = "quality-missing-worker",
            )
        } catch (_: HistoryReplacementQualityAuthorityLostException) {
            missingRejected = true
        }
        assertTrue(missingRejected)

        val terminalHistoryId = insertHistory()
        val parentOperation = LowQualityRedownloadRepository(db).createOrReconnect(now = 30L)
        val terminalDownloadOperationId = "quality-terminal-download-$terminalHistoryId"
        val terminalItem = insertDownload(DownloadType.video)
        db.downloadDao.update(
            terminalItem.copy(
                playlistURL = HistoryRedownloadMarker.quality(terminalHistoryId, 720),
                operationId = terminalDownloadOperationId,
            )
        )
        db.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = parentOperation.operationId,
                historyId = terminalHistoryId,
                intendedSourceUrl = HISTORY_URL,
                intendedType = DownloadType.video.name,
                selected = true,
                itemState = LowQualityRedownloadItemState.QUEUED.name,
                downloadId = terminalItem.id,
            )
        )
        assertEquals(
            1,
            db.downloadDao.claimDownloadForWorker(
                id = terminalItem.id,
                expectedOperationId = terminalDownloadOperationId,
                expectedRetryAttempt = terminalItem.retryAttempt,
                executionId = "quality-terminal-worker",
            ),
        )
        assertEquals(
            1,
            db.lowQualityRedownloadDao.setItemStateByDownloadId(
                terminalItem.id,
                LowQualityRedownloadItemState.FAILED.name,
                "terminal-child",
                31L,
            ),
        )
        var terminalRejected = false
        try {
            repository().authorizeHistoryReplacement(
                historyId = terminalHistoryId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = terminalItem.id,
                replacementOperationId = terminalDownloadOperationId,
                expectedExecutionId = "quality-terminal-worker",
            )
        } catch (_: HistoryReplacementQualityAuthorityLostException) {
            terminalRejected = true
        }
        assertTrue(terminalRejected)
    }

    @Test
    fun qualityAuthorityRejectsImmutableSourceAndTypeMismatch() = runBlocking {
        val sourceHistoryId = insertHistory()
        val sourceOperation = LowQualityRedownloadRepository(db).createOrReconnect(now = 40L)
        val sourceDownloadOperationId = "quality-source-download-$sourceHistoryId"
        val sourceItem = insertDownload(DownloadType.video)
        db.downloadDao.update(
            sourceItem.copy(
                playlistURL = HistoryRedownloadMarker.quality(sourceHistoryId, 720),
                operationId = sourceDownloadOperationId,
            )
        )
        db.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = sourceOperation.operationId,
                historyId = sourceHistoryId,
                intendedSourceUrl = OTHER_URL,
                intendedType = DownloadType.video.name,
                selected = true,
                itemState = LowQualityRedownloadItemState.QUEUED.name,
                downloadId = sourceItem.id,
            )
        )
        assertEquals(
            1,
            db.downloadDao.claimDownloadForWorker(
                id = sourceItem.id,
                expectedOperationId = sourceDownloadOperationId,
                expectedRetryAttempt = sourceItem.retryAttempt,
                executionId = "quality-source-worker",
            ),
        )
        var sourceRejected = false
        try {
            repository().authorizeHistoryReplacement(
                historyId = sourceHistoryId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = sourceItem.id,
                replacementOperationId = sourceDownloadOperationId,
                expectedExecutionId = "quality-source-worker",
            )
        } catch (_: HistoryReplacementQualityAuthorityLostException) {
            sourceRejected = true
        }
        assertTrue(sourceRejected)

        val typeHistoryId = insertHistory()
        val typeOperation = LowQualityRedownloadRepository(db).createOrReconnect(now = 50L)
        val typeDownloadOperationId = "quality-type-download-$typeHistoryId"
        val typeItem = insertDownload(DownloadType.video)
        db.downloadDao.update(
            typeItem.copy(
                playlistURL = HistoryRedownloadMarker.quality(typeHistoryId, 720),
                operationId = typeDownloadOperationId,
            )
        )
        db.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = typeOperation.operationId,
                historyId = typeHistoryId,
                intendedSourceUrl = HISTORY_URL,
                intendedType = DownloadType.audio.name,
                selected = true,
                itemState = LowQualityRedownloadItemState.QUEUED.name,
                downloadId = typeItem.id,
            )
        )
        assertEquals(
            1,
            db.downloadDao.claimDownloadForWorker(
                id = typeItem.id,
                expectedOperationId = typeDownloadOperationId,
                expectedRetryAttempt = typeItem.retryAttempt,
                executionId = "quality-type-worker",
            ),
        )
        var typeRejected = false
        try {
            repository().authorizeHistoryReplacement(
                historyId = typeHistoryId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = typeItem.id,
                replacementOperationId = typeDownloadOperationId,
                expectedExecutionId = "quality-type-worker",
            )
        } catch (_: HistoryReplacementQualityAuthorityLostException) {
            typeRejected = true
        }
        assertTrue(typeRejected)
    }

    @Test
    fun regularHistoryMarkerWithoutLowQualityLedgerRemainsAuthorized() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        db.downloadDao.update(
            item.copy(playlistURL = HistoryRedownloadMarker.regular(historyId))
        )

        assertTrue(
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = item.id,
                replacementOperationId = item.operationId,
            ) is HistoryReplacementAuthorization.Authorized
        )
    }

    @Test
    fun staleSecondWorkerClaimCannotRunAfterFirstWorkerRecordsMismatch() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        val firstExecution = "worker-one"
        val secondExecution = "worker-two"

        assertEquals(
            1,
            db.downloadDao.claimDownloadForWorker(
                id = item.id,
                expectedOperationId = item.operationId,
                expectedRetryAttempt = item.retryAttempt,
                executionId = firstExecution,
            )
        )
        repository().authorizeHistoryReplacement(
            historyId = historyId,
            expectedSourceUrl = OTHER_URL,
            expectedType = DownloadType.video,
            replacementDownloadId = item.id,
            replacementOperationId = item.operationId,
        )

        assertEquals(
            0,
            db.downloadDao.claimDownloadForWorker(
                id = item.id,
                expectedOperationId = item.operationId,
                expectedRetryAttempt = item.retryAttempt,
                executionId = secondExecution,
            )
        )
        assertTrue(
            db.historyReplacementBarrierDao.getByDownloadId(item.id)?.issueCode ==
                HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE).code.name
        )
    }

    @Test
    fun pausedRowCanCarryMismatchWithoutBeingForcedBackToError() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        val executionId = "paused-mismatch-attempt"

        assertEquals(
            1,
            db.downloadDao.claimDownloadForWorker(
                id = item.id,
                expectedOperationId = item.operationId,
                expectedRetryAttempt = item.retryAttempt,
                executionId = executionId,
            )
        )
        repository().authorizeHistoryReplacement(
            historyId = historyId,
            expectedSourceUrl = OTHER_URL,
            expectedType = DownloadType.video,
            replacementDownloadId = item.id,
            replacementOperationId = item.operationId,
        )
        assertEquals(1, db.downloadDao.pauseIfExecutionOwned(item.id, executionId))

        val issue = HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)
        assertEquals(
            1,
            db.downloadDao.persistMismatchIssueForExecution(
                id = item.id,
                executionId = executionId,
                issueCode = issue.code.name,
                issueStage = issue.stage.name,
            )
        )
        val current = db.downloadDao.getDownloadById(item.id)
        assertEquals(DownloadRepository.Status.Paused.name, current.status)
        assertEquals(issue.code.name, current.lastIssueCode)
        assertEquals(issue.stage.name, current.lastIssueStage)
        assertEquals(0, db.downloadDao.reQueueDownloadItems(listOf(item.id)))
        assertEquals(DownloadRepository.Status.Paused.name, db.downloadDao.getDownloadById(item.id).status)
    }

    @Test
    fun barrierOnlyRefusalIsProjectedThroughBackupAndRestoredWithNewDownloadId() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        val repository = DownloadRepository(db)
        repository().authorizeHistoryReplacement(
            historyId = historyId,
            expectedSourceUrl = OTHER_URL,
            expectedType = DownloadType.video,
            replacementDownloadId = item.id,
            replacementOperationId = item.operationId,
        )

        val barrier = db.historyReplacementBarrierDao.getByDownloadId(item.id)!!
        assertEquals("", db.downloadDao.getDownloadById(item.id).lastIssueCode)
        val backupItem = repository.getQueuedDownloadsForBackup().single { it.id == item.id }
        assertEquals(barrier.issueCode, backupItem.lastIssueCode)
        assertEquals(barrier.issueStage, backupItem.lastIssueStage)

        val restoredId = repository.insertRestoredDownload(
            backupItem.copy(id = 0L),
            barrier.copy(downloadId = 0L),
        )
        assertTrue(restoredId != item.id)
        assertEquals(
            barrier.issueCode,
            db.historyReplacementBarrierDao.getByDownloadId(restoredId)?.issueCode,
        )
        assertEquals(
            HistoryReplacementAuthorization.SourceMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = restoredId,
                replacementOperationId = item.operationId,
            )
        )
    }

    @Test
    fun barrierIsRestoredByRepositoryUndoPrimitive() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        val repository = DownloadRepository(db)
        repository().authorizeHistoryReplacement(
            historyId = historyId,
            expectedSourceUrl = OTHER_URL,
            expectedType = DownloadType.video,
            replacementDownloadId = item.id,
            replacementOperationId = item.operationId,
        )
        val barrier = db.historyReplacementBarrierDao.getByDownloadId(item.id)!!

        val undoHandle = repository.deleteForUndo(item.id)!!
        assertEquals(null, db.downloadDao.getNullableDownloadById(item.id))
        assertEquals(null, db.historyReplacementBarrierDao.getByDownloadId(item.id))

        assertEquals(null, repository.restoreUndo(DownloadRepository.DownloadUndoToken("not-ready")))
        val restoredId = repository.restoreUndo(undoHandle.token)!!
        assertTrue(restoredId != item.id)
        val restoredBarrier = db.historyReplacementBarrierDao.getByDownloadId(restoredId)
        assertEquals(barrier.issueCode, restoredBarrier?.issueCode)
        assertEquals(barrier.issueStage, restoredBarrier?.issueStage)
        assertEquals(DownloadRepository.Status.Error.name, db.downloadDao.getDownloadById(restoredId).status)
        assertEquals(
            HistoryReplacementAuthorization.SourceMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = restoredId,
                replacementOperationId = item.operationId,
            )
        )
    }

    @Test
    fun typeAndTargetDeletedRefusalsSurviveRepositoryUndo() = runBlocking {
        val typeHistoryId = insertHistory()
        val typeItem = insertDownload(DownloadType.audio)
        assertEquals(
            HistoryReplacementAuthorization.TypeMismatch,
            repository().authorizeHistoryReplacement(
                historyId = typeHistoryId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.audio,
                replacementDownloadId = typeItem.id,
                replacementOperationId = typeItem.operationId,
            ),
        )
        val typeRestoredId = DownloadRepository(db)
            .restoreUndo(DownloadRepository(db).deleteForUndo(typeItem.id)!!.token)!!
        assertEquals(
            HistoryReplacementAuthorization.TypeMismatch,
            repository().authorizeHistoryReplacement(
                historyId = typeHistoryId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = typeRestoredId,
                replacementOperationId = typeItem.operationId,
            ),
        )
        assertEquals(
            HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.TYPE).code.name,
            db.historyReplacementBarrierDao.getByDownloadId(typeRestoredId)?.issueCode,
        )

        val targetHistoryId = insertHistory()
        val targetItem = insertDownload(DownloadType.video)
        db.historyDao.deleteById(targetHistoryId)
        assertEquals(
            HistoryReplacementAuthorization.TargetMissing,
            repository().authorizeHistoryReplacement(
                historyId = targetHistoryId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = targetItem.id,
                replacementOperationId = targetItem.operationId,
            ),
        )
        val targetRepository = DownloadRepository(db)
        val targetHandle = targetRepository.deleteForUndo(targetItem.id)!!
        val targetRestoredId = targetRepository.restoreUndo(targetHandle.token)!!
        assertEquals(
            HistoryReplacementAuthorization.TargetMissing,
            repository().authorizeHistoryReplacement(
                historyId = targetHistoryId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = targetRestoredId,
                replacementOperationId = targetItem.operationId,
            ),
        )
        assertEquals(
            "HISTORY_TARGET_DELETED",
            db.historyReplacementBarrierDao.getByDownloadId(targetRestoredId)?.issueCode,
        )
    }

    @Test
    fun typeMismatchBarrierOnlyStateIsProjectedAndRestored() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.audio)
        val repository = DownloadRepository(db)
        repository().authorizeHistoryReplacement(
            historyId = historyId,
            expectedSourceUrl = HISTORY_URL,
            expectedType = DownloadType.audio,
            replacementDownloadId = item.id,
            replacementOperationId = item.operationId,
        )
        val barrier = db.historyReplacementBarrierDao.getByDownloadId(item.id)!!
        val backupItem = repository.getQueuedDownloadsForBackup().single { it.id == item.id }
        assertEquals(barrier.issueCode, backupItem.lastIssueCode)

        val restoredId = repository.insertRestoredDownload(
            backupItem.copy(id = 0L),
            barrier.copy(downloadId = 0L),
        )
        assertEquals(
            HistoryReplacementAuthorization.TypeMismatch,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = restoredId,
                replacementOperationId = item.operationId,
            )
        )
    }

    @Test
    fun targetDeletedBarrierOnlyStateIsProjectedAndRestored() = runBlocking {
        val historyId = insertHistory()
        val item = insertDownload(DownloadType.video)
        db.historyDao.deleteById(historyId)
        repository().authorizeHistoryReplacement(
            historyId = historyId,
            expectedSourceUrl = HISTORY_URL,
            expectedType = DownloadType.video,
            replacementDownloadId = item.id,
            replacementOperationId = item.operationId,
        )
        val barrier = db.historyReplacementBarrierDao.getByDownloadId(item.id)!!
        val backupItem = DownloadRepository(db)
            .getQueuedDownloadsForBackup()
            .single { it.id == item.id }
        assertEquals("HISTORY_TARGET_DELETED", backupItem.lastIssueCode)

        val restoredId = DownloadRepository(db).insertRestoredDownload(
            backupItem.copy(id = 0L),
            barrier.copy(downloadId = 0L),
        )
        db.historyDao.insertRaw(history().copy(id = historyId))
        assertEquals(
            HistoryReplacementAuthorization.TargetMissing,
            repository().authorizeHistoryReplacement(
                historyId = historyId,
                expectedSourceUrl = HISTORY_URL,
                expectedType = DownloadType.video,
                replacementDownloadId = restoredId,
                replacementOperationId = item.operationId,
            )
        )
    }

    private fun repository() = HistoryKeywordAssignmentRepository(db)

    private suspend fun insertHistory(): Long = repository().insertHistory(
        HistoryItem(
            id = 0L,
            url = HISTORY_URL,
            title = "Previous",
            author = "Author",
            duration = "1:00",
            thumb = "",
            type = DownloadType.video,
            time = 1L,
            downloadPath = listOf("/tmp/previous.mp4"),
            website = "YouTube",
            format = Format(),
            downloadId = 0L,
        )
    )

    private suspend fun insertDownload(type: DownloadType): DownloadItem {
        val operationId = "history-replacement-test-${type.name}"
        val item = DownloadItem(
            id = 0L,
            url = HISTORY_URL,
            title = "Replacement",
            author = "Author",
            thumb = "",
            duration = "1:00",
            type = type,
            format = Format(format_id = "best"),
            container = "mp4",
            downloadSections = "",
            allFormats = arrayListOf(),
            downloadPath = "/tmp",
            website = "YouTube",
            downloadSize = "",
            playlistTitle = "",
            audioPreferences = AudioPreferences(),
            videoPreferences = VideoPreferences(),
            extraCommands = "",
            customFileNameTemplate = "%(title)s",
            SaveThumb = false,
            status = DownloadRepository.Status.Queued.name,
            downloadStartTime = 0L,
            logID = null,
            playlistURL = "history-redownload:1",
            operationId = operationId,
        )
        val id = db.downloadDao.insert(item)
        return item.copy(id = id)
    }

    companion object {
        private const val HISTORY_URL = "https://youtu.be/dQw4w9WgXcQ"
        private const val OTHER_URL = "https://youtu.be/9bZkp7q19f0"
    }
}
