package com.ireum.ytdl.database

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.preference.PreferenceManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.BackupCustomThumbItem
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.models.observeSources.ObservationPurposes
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import com.ireum.ytdl.work.CleanUpLeftoverDownloads
import com.ireum.ytdl.work.CleanupScheduleCoordinator
import com.ireum.ytdl.work.CleanupSchedulePolicy
import com.ireum.ytdl.work.LowQualityRedownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Production-wiring coverage for the F11 file-backed Reset protocol. */
@RunWith(AndroidJUnit4::class)
class BackupResetTransactionProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var preferences: android.content.SharedPreferences
    private var originalAlarmPreferencePresent = false
    private var originalAlarmPreference = false

    @Before
    fun setUp() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalAlarmPreferencePresent = preferences.contains("use_alarm_for_scheduling")
        originalAlarmPreference = preferences.getBoolean("use_alarm_for_scheduling", false)
        preferences.edit().putBoolean("use_alarm_for_scheduling", false).commit()
        WorkManager.getInstance(context).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        clearHooks()
        runCatching { RestoreTransactionCoordinator.recover(context) }
        RestoreOperationStore.root(context).deleteRecursively()
        database = DBManager.getInstance(context)
        database.historyDao.nuke()
        database.downloadDao.deleteAll()
        database.openHelper.writableDatabase.execSQL("DELETE FROM low_quality_redownload_items")
        database.openHelper.writableDatabase.execSQL("DELETE FROM low_quality_redownload_operations")
        database.openHelper.writableDatabase.execSQL("DELETE FROM sources")
        preferences.edit().remove("f11_reset_marker").commit()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        clearHooks()
        runCatching { RestoreTransactionCoordinator.recover(context) }
        WorkManager.getInstance(context).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        database.historyDao.nuke()
        database.downloadDao.deleteAll()
        database.openHelper.writableDatabase.execSQL("DELETE FROM low_quality_redownload_items")
        database.openHelper.writableDatabase.execSQL("DELETE FROM low_quality_redownload_operations")
        database.openHelper.writableDatabase.execSQL("DELETE FROM sources")
        preferences.edit().remove("f11_reset_marker").apply {
            if (originalAlarmPreferencePresent) {
                putBoolean("use_alarm_for_scheduling", originalAlarmPreference)
            } else {
                remove("use_alarm_for_scheduling")
            }
        }.commit()
        RestoreOperationStore.root(context).deleteRecursively()
    }

    @Test
    fun malformedInputIsRejectedBeforeAnyResetOwnerExists() = runBlocking {
        val beforeHistoryCount = database.historyDao.getCount()
        assertThrows {
            BackupRestoreParser.parse(
                """{"app":"unrelated-app","backup_format_version":4}""",
            )
        }
        assertThrows {
            BackupRestoreParser.parse(
                """{"app":"YTDLnisX_backup","backup_format_version":4,"downloads":{}}""",
            )
        }
        assertThrows {
            BackupRestoreParser.parse(
                """{"app":"YTDLnisX_backup","backup_format_version":4,"playlists":[]}""",
            )
        }
        assertThrows {
            BackupRestoreParser.parse(
                """{"app":"YTDLnisX_backup","backup_format_version":4,"downloads":[{"id":1,"url":"https://example.com/a"}],"custom_thumbnails":[{"historyId":1,"base64":"@@@","extension":"jpg"}]}""",
            )
        }

        val outcome = SettingsViewModel(context as Application).restoreData(
            RestoreAppDataItem(
                settings = listOf(BackupSettingsItem("f11_reset_marker", "not-an-int", "Int")),
            ),
            context,
            resetData = true,
        )
        assertTrue(outcome is RestoreOutcome.RejectedBeforeOwnership)
        assertEquals(beforeHistoryCount, database.historyDao.getCount())
        assertFalse(RestoreGate.isRestoreInProgress(context))
    }

    @Test
    fun historicalMarkersAndVersionsUseOnlyProvenCompatibility() {
        val lowercaseLegacy = BackupRestoreParser.parse(
            """{"app":"YTDLnisx_backup","downloads":[]}""",
        )
        assertEquals(
            com.ireum.ytdl.database.models.BackupCompatibility.LEGACY_LOWERCASE_UNVERSIONED,
            lowercaseLegacy.compatibility,
        )
        val uppercaseLegacy = BackupRestoreParser.parse(
            """{"app":"YTDLnisX_backup","downloads":[]}""",
        )
        assertEquals(
            com.ireum.ytdl.database.models.BackupCompatibility.LEGACY_UPPERCASE_UNVERSIONED,
            uppercaseLegacy.compatibility,
        )
        val version3 = BackupRestoreParser.parse(
            """{"app":"YTDLnisX_backup","backup_format_version":3,"downloads":[]}""",
        )
        assertEquals(
            com.ireum.ytdl.database.models.BackupCompatibility.VERSION_3,
            version3.compatibility,
        )
        assertThrows {
            BackupRestoreParser.parse(
                """{"app":"YTDLnisX_backup","backup_format_version":3,"playlists":[],"playlist_item_cross_refs":[],"playlist_groups":[],"playlist_group_members":[]}""",
            )
        }
        assertThrows {
            BackupRestoreParser.parse(
                """{"app":"YTDLnisx_backup","backup_format_version":3,"downloads":[]}""",
            )
        }
        assertThrows {
            BackupRestoreParser.parse(
                """{"app":"YTDLnisX_backup","backup_format_version":5,"downloads":[]}""",
            )
        }
    }

    @Test
    fun malformedActiveCarrierFailsClosedAndRemainsRecoverable() = runBlocking {
        val root = RestoreOperationStore.root(context)
        check(root.mkdirs() || root.isDirectory)
        File(root, "active.json").writeText("{malformed")

        assertTrue(RestoreGate.isRestoreInProgress(context))
        val pending = RestoreTransactionCoordinator.recover(context)
        assertTrue(pending is RestoreOutcome.RecoveryPending)
        assertTrue(RestoreGate.isRestoreInProgress(context))
    }

    @Test
    fun failureBeforeActivePublicationLeavesLiveStateUntouched() = runBlocking {
        val existing = history(7L, "https://example.com/existing")
        database.historyDao.insertAndGetIdRaw(existing)
        RestoreTransactionCoordinator.beforeActivePublicationForTesting = {
            error("F11 pre-publication fault")
        }

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            plan(history(8L, "https://example.com/imported")),
        )

        assertTrue(outcome is RestoreOutcome.RejectedBeforeOwnership)
        assertEquals(listOf(existing.url), database.historyDao.getAll().map { it.url })
        assertFalse(RestoreGate.isRestoreInProgress(context))
        val carrierFiles = RestoreOperationStore.root(context).listFiles().orEmpty()
        assertFalse(carrierFiles.any { it.isDirectory })
        assertFalse(File(RestoreOperationStore.root(context), "active.json").exists())
    }

    @Test
    fun preparedAndFilesReadyRestartBoundariesRollForward() = runBlocking {
        val preparedThrowOnce = AtomicBoolean(true)
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            if (preparedThrowOnce.compareAndSet(true, false)) {
                error("F11 simulated restart after PREPARED")
            }
        }

        val preparedPending = RestoreTransactionCoordinator.begin(
            context,
            plan(history(8L, "https://example.com/prepared")),
        )
        assertTrue(preparedPending is RestoreOutcome.RecoveryPending)
        assertEquals(RestorePhase.PREPARED, (preparedPending as RestoreOutcome.RecoveryPending).phase)
        assertEquals(0, database.historyDao.getCount())
        assertTrue(RestoreGate.isRestoreInProgress(context))

        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = null
        val filesThrowOnce = AtomicBoolean(true)
        RestoreTransactionCoordinator.afterFilesReadyBeforeApplyForTesting = {
            if (filesThrowOnce.compareAndSet(true, false)) {
                error("F11 simulated restart after FILES_READY")
            }
        }
        val filesPending = RestoreTransactionCoordinator.recover(context)
        assertTrue(filesPending is RestoreOutcome.RecoveryPending)
        assertEquals(RestorePhase.FILES_READY, (filesPending as RestoreOutcome.RecoveryPending).phase)
        assertEquals(0, database.historyDao.getCount())

        RestoreTransactionCoordinator.afterFilesReadyBeforeApplyForTesting = null
        RestoreTransactionCoordinator.stagingFailureForTesting = null
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        assertEquals(listOf("https://example.com/prepared"), database.historyDao.getAll().map { it.url })
        assertFalse(RestoreGate.isRestoreInProgress(context))
    }

    @Test
    fun secondResetCannotReplaceAnActiveOwner() = runBlocking {
        val throwOnce = AtomicBoolean(true)
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            if (throwOnce.compareAndSet(true, false)) error("F11 active-owner boundary")
        }

        val first = RestoreTransactionCoordinator.begin(
            context,
            plan(history(17L, "https://example.com/active-owner-first")),
        )
        assertTrue(first is RestoreOutcome.RecoveryPending)
        assertTrue(RestoreGate.isRestoreInProgress(context))

        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = null
        val second = RestoreTransactionCoordinator.begin(
            context,
            plan(history(18L, "https://example.com/active-owner-second")),
        )
        assertTrue(second is RestoreOutcome.RejectedBeforeOwnership)
        assertEquals(
            listOf("https://example.com/active-owner-first"),
            database.historyDao.getAll().map { it.url },
        )
        assertFalse(RestoreGate.isRestoreInProgress(context))
    }

    @Test
    fun stagingFailureBeforeActivePublicationLeavesLiveStateUntouched() = runBlocking {
        val existing = history(5L, "https://example.com/staging-existing")
        database.historyDao.insertAndGetIdRaw(existing)
        RestoreTransactionCoordinator.stagingFailureForTesting = {
            error("F11 staging fault for History $it")
        }

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            plan(
                history(16L, "https://example.com/staging-imported"),
                BackupCustomThumbItem(
                    historyId = 16L,
                    base64 = Base64.encodeToString(byteArrayOf(1, 2), Base64.NO_WRAP),
                    extension = "jpg",
                ),
            ),
        )

        assertTrue(outcome is RestoreOutcome.RejectedBeforeOwnership)
        assertEquals(listOf(existing.url), database.historyDao.getAll().map { it.url })
        assertFalse(RestoreGate.isRestoreInProgress(context))
        assertFalse(File(RestoreOperationStore.root(context), "active.json").exists())
    }
    @Test
    fun activeConflictingWorkerMustQuiesceBeforeResetApplies() = runBlocking {
        val existing = history(6L, "https://example.com/quiescence-existing")
        database.historyDao.insertAndGetIdRaw(existing)
        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = {}
        CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting = {
            admissionEntered.countDown()
            check(releaseAdmission.await(10, TimeUnit.SECONDS)) {
                "cleanup worker admission did not release"
            }
        }
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = 0L
        assertTrue(CleanupScheduleCoordinator.configure(context, CleanupSchedulePolicy.DAILY))
        assertTrue(admissionEntered.await(10, TimeUnit.SECONDS))

        val reset = async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(
                context,
                plan(
                    history(15L, "https://example.com/quiescence-imported"),
                    settings = listOf(BackupSettingsItem("f11_reset_marker", "quiesced", "String")),
                ),
            )
        }
        try {
            val deadline = System.currentTimeMillis() + 5_000L
            while (!RestoreGate.isRestoreInProgress(context) && System.currentTimeMillis() < deadline) {
                delay(10L)
            }
            assertTrue(RestoreGate.isRestoreInProgress(context))
            assertEquals(listOf(existing.url), database.historyDao.getAll().map { it.url })
            assertTrue(
                DownloadRepository(database)
                    .startDownloadWorker(emptyList(), context)
                    .isFailure,
            )
        } finally {
            releaseAdmission.countDown()
        }

        assertTrue(reset.await() is RestoreOutcome.Completed)
        assertEquals(
            listOf("https://example.com/quiescence-imported"),
            database.historyDao.getAll().map { it.url },
        )
    }
    @Test
    fun roomFailureRollsBackAndFreshRecoveryCompletesTheSamePlan() = runBlocking {
        val existing = history(9L, "https://example.com/existing")
        database.historyDao.insertAndGetIdRaw(existing)
        RestoreTransactionCoordinator.roomApplyFailureForTesting = {
            error("F11 Room apply fault")
        }

        val pending = RestoreTransactionCoordinator.begin(
            context,
            plan(history(10L, "https://example.com/imported")),
        )
        assertTrue(pending is RestoreOutcome.RecoveryPending)
        assertEquals(listOf(existing.url), database.historyDao.getAll().map { it.url })
        assertTrue(RestoreGate.isRestoreInProgress(context))
        assertTrue(
            DownloadRepository(database)
                .startDownloadWorker(emptyList(), context)
                .isFailure,
        )
        assertNotNull(RestoreOperationStore.load(context))

        RestoreTransactionCoordinator.roomApplyFailureForTesting = null
        val completed = RestoreTransactionCoordinator.recover(context)
        assertTrue(completed is RestoreOutcome.Completed)
        assertEquals(listOf("https://example.com/imported"), database.historyDao.getAll().map { it.url })
        assertFalse(RestoreGate.isRestoreInProgress(context))
    }

    @Test
    fun roomCommitThenJournalCrashIsSafelyReappliedAfterRestart() = runBlocking {
        val throwOnce = AtomicBoolean(true)
        RestoreTransactionCoordinator.afterRoomCommitBeforeJournalForTesting = {
            if (throwOnce.compareAndSet(true, false)) {
                error("F11 simulated process death after Room commit")
            }
        }

        val pending = RestoreTransactionCoordinator.begin(
            context,
            plan(history(11L, "https://example.com/ambiguous")),
        )
        assertTrue(pending is RestoreOutcome.RecoveryPending)
        assertEquals(listOf("https://example.com/ambiguous"), database.historyDao.getAll().map { it.url })

        RestoreTransactionCoordinator.afterRoomCommitBeforeJournalForTesting = null
        val completed = RestoreTransactionCoordinator.recover(context)
        assertTrue(completed is RestoreOutcome.Completed)
        assertEquals(1, database.historyDao.getCount())
        assertEquals("https://example.com/ambiguous", database.historyDao.getAll().single().url)
        assertFalse(RestoreGate.isRestoreInProgress(context))
        assertTrue(
            RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed,
        )
        assertEquals(1, database.historyDao.getCount())
    }

    @Test
    fun requiredFilesReuseDeterministicPublicationAcrossAmbiguousRestart() = runBlocking {
        val bytes = byteArrayOf(1, 3, 5, 7)
        val throwOnce = AtomicBoolean(true)
        RestoreTransactionCoordinator.afterRoomCommitBeforeJournalForTesting = {
            if (throwOnce.compareAndSet(true, false)) error("F11 file restart boundary")
        }

        val pending = RestoreTransactionCoordinator.begin(
            context,
            plan(
                history(12L, "https://example.com/thumbnail"),
                BackupCustomThumbItem(
                    historyId = 12L,
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    extension = "png",
                ),
            ),
        )
        assertTrue(pending is RestoreOutcome.RecoveryPending)
        val first = database.historyDao.getAll().single()
        val firstFile = File(first.customThumb)
        assertTrue(firstFile.isFile)
        assertEquals(bytes.toList(), firstFile.readBytes().toList())

        RestoreTransactionCoordinator.afterRoomCommitBeforeJournalForTesting = null
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        val second = database.historyDao.getAll().single()
        assertEquals(first.customThumb, second.customThumb)
        assertEquals(bytes.toList(), File(second.customThumb).readBytes().toList())
    }

    @Test
    fun finalFilePublicationFailureKeepsResetRecoverableBeforeRoomApply() = runBlocking {
        RestoreTransactionCoordinator.finalFilePublicationFailureForTesting = {
            error("F11 final publication fault")
        }
        val pending = RestoreTransactionCoordinator.begin(
            context,
            plan(
                history(13L, "https://example.com/file-failure"),
                BackupCustomThumbItem(
                    historyId = 13L,
                    base64 = Base64.encodeToString(byteArrayOf(8, 6), Base64.NO_WRAP),
                    extension = "jpg",
                ),
            ),
        )
        assertTrue(pending is RestoreOutcome.RecoveryPending)
        assertEquals(0, database.historyDao.getCount())
        assertTrue(RestoreGate.isRestoreInProgress(context))

        RestoreTransactionCoordinator.finalFilePublicationFailureForTesting = null
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        assertEquals(1, database.historyDao.getCount())
    }

    @Test
    fun preferenceCommitFailureCompensatesButRecoveryReplaysDesiredImage() = runBlocking {
        val original = preferences.getString("f11_reset_marker", null)
        RestoreTransactionCoordinator.preferenceCommitOverrideForTesting = { false }

        val pending = RestoreTransactionCoordinator.begin(
            context,
            plan(settings = listOf(BackupSettingsItem("f11_reset_marker", "imported", "String"))),
        )
        assertTrue(pending is RestoreOutcome.RecoveryPending)
        assertEquals(original, preferences.getString("f11_reset_marker", null))
        assertTrue(RestoreGate.isRestoreInProgress(context))

        RestoreTransactionCoordinator.preferenceCommitOverrideForTesting = null
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        assertEquals("imported", preferences.getString("f11_reset_marker", null))
    }

    @Test
    fun categorySpecificPostCommitFailuresRemainReconciliable() = runBlocking {
        val installers: List<() -> Unit> = listOf(
            {
                RestoreTransactionCoordinator.downloadSchedulingFailureForTesting = {
                    error("F11 download scheduling fault")
                }
            },
            {
                RestoreTransactionCoordinator.observeSchedulingFailureForTesting = {
                    error("F11 ObserveSource scheduling fault")
                }
            },
            {
                RestoreTransactionCoordinator.automaticKeywordReconciliationFailureForTesting = {
                    error("F11 automatic-keyword reconciliation fault")
                }
            },
        )

        installers.forEachIndexed { index, install ->
            clearHooks()
            install()
            val pending = RestoreTransactionCoordinator.begin(
                context,
                if (index == 0) {
                    plan(
                        history(20L + index, "https://example.com/category-$index"),
                        queued = listOf(queuedDownload(index.toLong())),
                    )
                } else {
                    plan(history(20L + index, "https://example.com/category-$index"))
                },
            )
            assertTrue(pending is RestoreOutcome.CommittedReconciliationPending)
            assertEquals(1, database.historyDao.getCount())

            clearHooks()
            assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
            assertFalse(RestoreGate.isRestoreInProgress(context))
        }
    }

    @Test
    fun postCommitReconciliationFailureRemainsDurableDebt() = runBlocking {
        RestoreTransactionCoordinator.reconciliationFailureForTesting = {
            error("F11 reconciliation fault")
        }

        val pending = RestoreTransactionCoordinator.begin(
            context,
            plan(history(14L, "https://example.com/reconcile")),
        )
        assertTrue(pending is RestoreOutcome.CommittedReconciliationPending)
        assertEquals(1, database.historyDao.getCount())
        assertTrue(RestoreGate.isRestoreInProgress(context))

        RestoreTransactionCoordinator.reconciliationFailureForTesting = null
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        assertFalse(RestoreGate.isRestoreInProgress(context))
        assertEquals(1, database.historyDao.getCount())
    }

    @Test
    fun quiescedRestartRollsForwardFromDurableBoundary() = runBlocking {
        RestoreTransactionCoordinator.afterQuiescedBeforeFilesReadyForTesting = {
            error("F11 simulated restart after QUIESCED")
        }

        val pending = RestoreTransactionCoordinator.begin(
            context,
            plan(history(101L, "https://example.com/quiesced-restart")),
        )
        assertTrue(pending is RestoreOutcome.RecoveryPending)
        assertEquals(RestorePhase.QUIESCED, (pending as RestoreOutcome.RecoveryPending).phase)
        assertEquals(
            RestorePhase.QUIESCED.name,
            requireNotNull(RestoreOperationStore.load(context)).journal.phase,
        )
        assertTrue(database.historyDao.getCount() == 0)

        RestoreTransactionCoordinator.afterQuiescedBeforeFilesReadyForTesting = null
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        assertEquals(
            listOf("https://example.com/quiesced-restart"),
            database.historyDao.getAll().map { it.url },
        )
        assertFalse(RestoreGate.isRestoreInProgress(context))
    }

    @Test
    fun dataCommittedRestartResumesReconciliationWithoutReapplyingData() = runBlocking {
        RestoreTransactionCoordinator.afterDataCommittedBeforeReconciliationForTesting = {
            error("F11 simulated restart after DATA_COMMITTED")
        }

        val pending = RestoreTransactionCoordinator.begin(
            context,
            plan(history(102L, "https://example.com/data-committed-restart")),
        )
        assertTrue(pending is RestoreOutcome.CommittedReconciliationPending)
        assertEquals(
            RestorePhase.DATA_COMMITTED.name,
            requireNotNull(RestoreOperationStore.load(context)).journal.phase,
        )
        assertEquals(
            listOf("https://example.com/data-committed-restart"),
            database.historyDao.getAll().map { it.url },
        )

        RestoreTransactionCoordinator.afterDataCommittedBeforeReconciliationForTesting = null
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        assertEquals(1, database.historyDao.getCount())
        assertEquals(
            listOf("https://example.com/data-committed-restart"),
            database.historyDao.getAll().map { it.url },
        )
        assertFalse(RestoreGate.isRestoreInProgress(context))
    }

    @Test
    fun completeBeforeRetirementRestartOnlyRetiresCompletedOperation() = runBlocking {
        RestoreTransactionCoordinator.afterCompleteBeforeRetirementForTesting = {
            error("F11 simulated restart after COMPLETE")
        }

        val pending = RestoreTransactionCoordinator.begin(
            context,
            plan(history(103L, "https://example.com/complete-restart")),
        )
        assertTrue(pending is RestoreOutcome.RecoveryPending)
        assertEquals(
            RestorePhase.COMPLETE,
            (pending as RestoreOutcome.RecoveryPending).phase,
        )
        assertNotNull(RestoreOperationStore.load(context))
        assertEquals(1, database.historyDao.getCount())

        RestoreTransactionCoordinator.afterCompleteBeforeRetirementForTesting = null
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        assertNull(RestoreOperationStore.load(context))
        assertFalse(RestoreGate.isRestoreInProgress(context))
        assertEquals(1, database.historyDao.getCount())
    }

    @Test
    fun acceptedRestoreSchedulingReplaysWithOneCurrentOwner() = runBlocking {
        val startAt = System.currentTimeMillis() + 300_000L
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = {
            error("F11 simulated restart after accepted scheduling")
        }

        val pending = RestoreTransactionCoordinator.begin(
            context,
            plan(scheduled = listOf(scheduledDownload(104L, startAt))),
        )
        assertTrue(pending is RestoreOutcome.CommittedReconciliationPending)
        val operationId = (pending as RestoreOutcome.CommittedReconciliationPending).operationId
        val workName = "scheduledDownload-restore-$operationId-$startAt"
        assertEquals(1, unfinishedWork(workName).size)

        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = null
        assertTrue(RestoreTransactionCoordinator.recover(context) is RestoreOutcome.Completed)
        assertEquals(1, unfinishedWork(workName).size)
        assertFalse(RestoreGate.isRestoreInProgress(context))
    }

    @Test
    fun pausedOnlyResetPreservesQueuedStateAndReconstructsDownloadOwner() = runBlocking {
        val startAt = System.currentTimeMillis() + 300_000L
        val insertedId = database.downloadDao.insert(queuedDownload(105L).copy(downloadStartTime = startAt))
        val existing = database.downloadDao.getQueuedDownloadsList().single { it.id == insertedId }
        assertTrue(
            DownloadRepository(database)
                .startDownloadWorker(listOf(existing), context, awaitAcceptance = true)
                .isSuccess,
        )

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            plan(
                paused = listOf(
                    queuedDownload(106L).copy(
                        status = DownloadRepository.Status.Paused.name,
                        downloadStartTime = 0L,
                    ),
                ),
            ),
        )
        assertTrue(outcome is RestoreOutcome.Completed)
        assertEquals(1, database.downloadDao.getQueuedDownloadsList().count { it.id == insertedId })
        val operationId = (outcome as RestoreOutcome.Completed).operationId
        assertEquals(1, unfinishedWork("scheduledDownload-restore-$operationId-$startAt").size)
    }

    @Test
    fun historyOnlyResetPreservesQueuedStateAndReconstructsDownloadOwner() = runBlocking {
        val startAt = System.currentTimeMillis() + 300_000L
        val insertedId = database.downloadDao.insert(queuedDownload(107L).copy(downloadStartTime = startAt))
        val existing = database.downloadDao.getQueuedDownloadsList().single { it.id == insertedId }
        assertTrue(
            DownloadRepository(database)
                .startDownloadWorker(listOf(existing), context, awaitAcceptance = true)
                .isSuccess,
        )

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            plan(history(108L, "https://example.com/history-only")),
        )
        assertTrue(outcome is RestoreOutcome.Completed)
        assertEquals(1, database.downloadDao.getQueuedDownloadsList().count { it.id == insertedId })
        val operationId = (outcome as RestoreOutcome.Completed).operationId
        assertEquals(1, unfinishedWork("scheduledDownload-restore-$operationId-$startAt").size)
    }

    @Test
    fun persistentOnlyResetDoesNotManufactureDownloadWork() = runBlocking {
        val outcome = RestoreTransactionCoordinator.begin(
            context,
            plan(settings = listOf(BackupSettingsItem("f11_reset_marker", "persistent", "String"))),
        )
        assertTrue(outcome is RestoreOutcome.Completed)
        assertTrue(
            WorkManager.getInstance(context)
                .getWorkInfosByTag("download")
                .get(20, TimeUnit.SECONDS)
                .none { !it.state.isFinished },
        )
    }

    @Test
    fun managedKeywordSourceRegainsOwnerAfterDownloadQuiescence() = runBlocking {
        val conditionKey = "f11-managed-109"
        database.automaticKeywordRuleDao.insertRule(
            AutomaticKeywordRule(
                conditionValue = "https://example.com/managed-109",
                conditionKey = conditionKey,
                playlistName = "F11 managed",
            ),
        )
        val sourceId = database.observeSourcesDao.insert(
            managedKeywordSource(109L, conditionKey),
        )
        assertTrue(sourceId > 0L)

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            plan(
                paused = listOf(
                    queuedDownload(110L).copy(
                        status = DownloadRepository.Status.Paused.name,
                    ),
                ),
            ),
        )
        assertTrue(outcome is RestoreOutcome.Completed)
        assertEquals(
            1,
            unfinishedWork("OBSERVE$sourceId").size,
        )
    }

    @Test
    fun survivingLowQualityOperationRegainsRecoveryOwnerOrTerminates() = runBlocking {
        val operationId = "f11-low-quality-survivor"
        database.lowQualityRedownloadDao.insertOperation(
            LowQualityRedownloadOperation(
                operationId = operationId,
                phase = LowQualityRedownloadPhase.SCANNING.name,
                state = "RUNNING",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        database.lowQualityRedownloadDao.upsertItem(
            LowQualityRedownloadItem(
                operationId = operationId,
                historyId = 111L,
                intendedSourceUrl = "https://example.com/low-quality",
                intendedType = DownloadType.video.name,
                selected = true,
                itemState = LowQualityRedownloadItemState.PENDING.name,
            ),
        )

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            plan(
                paused = listOf(
                    queuedDownload(112L).copy(
                        status = DownloadRepository.Status.Paused.name,
                    ),
                ),
            ),
        )
        assertTrue(outcome is RestoreOutcome.Completed)

        withTimeout(20_000L) {
            while (true) {
                val operation = database.lowQualityRedownloadDao.getOperation(operationId)
                val work = unfinishedWork(LowQualityRedownloadWorker.uniqueWorkName(operationId))
                if (operation?.stateValue?.isTerminal == true || work.isNotEmpty()) {
                    return@withTimeout
                }
                delay(25L)
            }
        }
        val finalOperation = requireNotNull(database.lowQualityRedownloadDao.getOperation(operationId))
        val finalWork = unfinishedWork(LowQualityRedownloadWorker.uniqueWorkName(operationId))
        assertTrue(finalOperation.stateValue.isTerminal || finalWork.isNotEmpty())
    }

    @Test
    fun ordinaryMutationAuthorityWinsBeforeRestorePublication() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) {
                    "ordinary mutation authority did not release"
                }
            }
        }

        val writer = async(Dispatchers.IO) {
            DownloadRepository(database).insert(queuedDownload(113L))
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val reset = async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(
                context,
                plan(settings = listOf(BackupSettingsItem("f11_reset_marker", "writer-won", "String"))),
            )
        }
        assertFalse(reset.isCompleted)
        release.countDown()
        assertTrue(writer.await() > 0L)
        assertTrue(reset.await() is RestoreOutcome.Completed)
        assertEquals(
            1,
            database.downloadDao.getQueuedDownloadsList().count {
                it.url == "https://example.com/queued-113"
            },
        )
    }

    @Test
    fun restoreAuthorityRejectsDownloadObserveAndPreferenceMutation() = runBlocking {
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val preparedEntered = CountDownLatch(1)
        val releasePrepared = CountDownLatch(1)
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = {
            publicationEntered.countDown()
            check(releasePublication.await(10, TimeUnit.SECONDS)) {
                "restore publication authority did not release"
            }
        }
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            preparedEntered.countDown()
            check(releasePrepared.await(10, TimeUnit.SECONDS)) {
                "prepared restore boundary did not release"
            }
        }

        val reset = async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(
                context,
                plan(settings = listOf(BackupSettingsItem("f11_reset_marker", "restore-won", "String"))),
            )
        }
        assertTrue(publicationEntered.await(10, TimeUnit.SECONDS))
        releasePublication.countDown()
        assertTrue(preparedEntered.await(10, TimeUnit.SECONDS))

        val downloadFailure = runCatching {
            DownloadRepository(database).insert(queuedDownload(114L))
        }.exceptionOrNull()
        assertTrue(downloadFailure is IllegalStateException)
        val sourceFailure = runCatching {
            ObserveSourcesRepository(
                database.observeSourcesDao,
                WorkManager.getInstance(context),
                preferences,
                context,
            ).insert(managedKeywordSource(115L))
        }.exceptionOrNull()
        assertTrue(sourceFailure is IllegalStateException)
        val preferenceFailure = runCatching {
            RestoreMutationAdmission.applyOrdinaryPreferences(
                context,
                preferences.edit().putString("f11_reset_marker", "ordinary-writer"),
            )
        }.exceptionOrNull()
        assertTrue(preferenceFailure is IllegalStateException)
        assertNull(preferences.getString("f11_reset_marker", null))

        releasePrepared.countDown()
        assertTrue(reset.await() is RestoreOutcome.Completed)
        assertEquals("restore-won", preferences.getString("f11_reset_marker", null))
        assertTrue(database.downloadDao.getQueuedDownloadsList().none { it.url.endsWith("114") })
    }
    private fun plan(
        history: HistoryItem? = null,
        thumbnail: BackupCustomThumbItem? = null,
        settings: List<BackupSettingsItem>? = null,
        queued: List<DownloadItem>? = null,
        paused: List<DownloadItem>? = null,
        scheduled: List<DownloadItem>? = null,
        observeSources: List<ObserveSourcesItem>? = null,
    ) = com.ireum.ytdl.database.BackupRestoreParser.fromTyped(
        RestoreAppDataItem(
            downloads = history?.let(::listOf),
            customThumbnails = thumbnail?.let(::listOf),
            settings = settings,
            queued = queued,
            paused = paused,
            scheduled = scheduled,
            observeSources = observeSources,
        ),
    )

    private fun queuedDownload(index: Long) = DownloadItem(
        id = 0L,
        url = "https://example.com/queued-$index",
        title = "Queued $index",
        author = "F11",
        thumb = "",
        duration = "1:00",
        type = DownloadType.video,
        format = Format(container = "mp4"),
        container = "mp4",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = context.filesDir.absolutePath,
        website = "Test",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = com.ireum.ytdl.database.models.VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = DownloadRepository.Status.Queued.name,
        downloadStartTime = 0L,
        logID = null,
        operationId = "f11-queued-$index",
    )

    private fun history(id: Long, url: String) = HistoryItem(
        id = id,
        url = url,
        title = "F11 $id",
        author = "F11",
        duration = "1:00",
        thumb = "",
        type = DownloadType.video,
        time = System.currentTimeMillis(),
        downloadPath = listOf("/destination-only/$id"),
        website = "Test",
        format = Format(),
        downloadId = id,
        customThumb = "",
    )

    private fun scheduledDownload(index: Long, startAt: Long) =
        queuedDownload(index).copy(
            status = DownloadRepository.Status.Scheduled.name,
            downloadStartTime = startAt,
        )

    private fun managedKeywordSource(index: Long, conditionKey: String = "f11-managed-$index"): ObserveSourcesItem {
        val startsAt = System.currentTimeMillis() + 300_000L
        return ObserveSourcesItem(
            id = 0L,
            name = "F11 managed $index",
            url = "https://example.com/managed-$index",
            downloadItemTemplate = queuedDownload(index),
            everyNr = 1,
            everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
            everyTime = startsAt,
            weeklyConfig = null,
            monthlyConfig = null,
            status = ObserveSourcesRepository.SourceStatus.ACTIVE,
            startsTime = startsAt,
            endsDate = 0L,
            endsAfterCount = 0,
            runCount = 0,
            getOnlyNewUploads = true,
            retryMissingDownloads = false,
            ignoredLinks = mutableListOf(),
            alreadyProcessedLinks = mutableListOf(),
            syncWithSource = false,
            observationPurpose = ObservationPurposes.KEYWORD_DISCOVERY,
            managedConditionKey = conditionKey,
        )
    }

    private fun unfinishedWork(name: String): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(name)
            .get(20, TimeUnit.SECONDS)
            .filter { !it.state.isFinished }
    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            return
        }
        throw AssertionError("Expected restore validation to throw")
    }

    private fun clearHooks() {
        RestoreTransactionCoordinator.beforeActivePublicationForTesting = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = null
        RestoreTransactionCoordinator.afterFilesReadyBeforeApplyForTesting = null
        RestoreTransactionCoordinator.stagingFailureForTesting = null
        RestoreTransactionCoordinator.afterRoomCommitBeforeJournalForTesting = null
        RestoreTransactionCoordinator.preferenceCommitOverrideForTesting = null
        RestoreTransactionCoordinator.reconciliationFailureForTesting = null
        RestoreTransactionCoordinator.observeSchedulingFailureForTesting = null
        RestoreTransactionCoordinator.automaticKeywordReconciliationFailureForTesting = null
        RestoreTransactionCoordinator.downloadSchedulingFailureForTesting = null
        RestoreTransactionCoordinator.finalFilePublicationFailureForTesting = null
        RestoreTransactionCoordinator.roomApplyFailureForTesting = null
        RestoreTransactionCoordinator.afterQuiescedBeforeFilesReadyForTesting = null
        RestoreTransactionCoordinator.afterDataCommittedBeforeReconciliationForTesting = null
        RestoreTransactionCoordinator.afterCompleteBeforeRetirementForTesting = null
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = null
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = null
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = null
        CleanUpLeftoverDownloads.cleanupOverrideForTesting = null
        CleanUpLeftoverDownloads.beforeCleanupAdmissionForTesting = null
        CleanupScheduleCoordinator.initialDelayOverrideForTesting = null
        CleanupScheduleCoordinator.successorDelayOverrideForTesting = null
        CleanupScheduleCoordinator.replayInitialDelayOverrideForTesting = null
        CleanupScheduleCoordinator.nowProviderForTesting = null
    }
}
