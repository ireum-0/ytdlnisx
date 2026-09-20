package com.ireum.ytdl.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.ireum.ytdl.database.BackupRestoreParser
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreOperationStore
import com.ireum.ytdl.database.RestoreOutcome
import com.ireum.ytdl.database.models.RestorePlan
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Production-wiring proof for ordinary handoff-carrier final mutations. */
@RunWith(AndroidJUnit4::class)
class F11HandoffCarrierMutationAdmissionProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        WorkManager.getInstance(context).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        runCatching { RestoreTransactionCoordinator.recover(context) }
        RestoreOperationStore.root(context).deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        WorkManagerHandoffRecovery.clearForTesting()
        WorkManagerHandoffRecovery.databaseForTesting = database
    }

    @After
    fun tearDown(): Unit = runBlocking {
        clearHooks()
        WorkManagerHandoffRecovery.clearForTesting()
        if (::database.isInitialized) database.close()
        WorkManager.getInstance(context).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        RestoreOperationStore.root(context).deleteRecursively()
    }

    @Test
    fun hardSubOrdinaryCarrierMutationCompletesBeforeRestorePublication(): Unit = runBlocking {
        ordinaryWriterWins { WorkManagerHandoffRecovery.prepareHardSub(context) }
    }

    @Test
    fun schedulerOrdinaryCarrierMutationCompletesBeforeRestorePublication(): Unit = runBlocking {
        ordinaryWriterWins {
            WorkManagerHandoffRecovery.prepareSchedulerBoundary(
                context,
                WorkManagerHandoffCarrier.START_BOUNDARY,
                System.currentTimeMillis() + 60_000L,
            )
        }
    }

    @Test
    fun observeRetryOrdinaryCarrierMutationCompletesBeforeRestorePublication(): Unit = runBlocking {
        ordinaryWriterWins {
            WorkManagerHandoffRecovery.prepareObserveRetryDownload(
                context,
                sourceId = 701L,
                confirmedUrl = "https://example.com/f11-observe-retry",
                configFingerprint = "f11-fingerprint",
            )
        }
    }

    @Test
    fun hardSubResetWinsBeforeOrdinaryCarrierMutation(): Unit = runBlocking {
        resetWins { WorkManagerHandoffRecovery.prepareHardSub(context) }
    }

    @Test
    fun schedulerResetWinsBeforeOrdinaryCarrierMutation(): Unit = runBlocking {
        resetWins {
            WorkManagerHandoffRecovery.prepareSchedulerBoundary(
                context,
                WorkManagerHandoffCarrier.START_BOUNDARY,
                System.currentTimeMillis() + 60_000L,
            )
        }
    }

    @Test
    fun observeRetryResetWinsBeforeOrdinaryCarrierMutation(): Unit = runBlocking {
        resetWins {
            WorkManagerHandoffRecovery.prepareObserveRetryDownload(
                context,
                sourceId = 702L,
                confirmedUrl = "https://example.com/f11-observe-retry-reset-wins",
                configFingerprint = "f11-reset-fingerprint",
            )
        }
    }

    private suspend fun ordinaryWriterWins(create: () -> String) {
        val scope = CoroutineScope(currentCoroutineContext())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val events = AtomicInteger(0)
        var handoffId: String? = null
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                assertTrue(events.compareAndSet(0, 1))
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = {
            assertEquals(2, events.get())
            events.compareAndSet(2, 3)
        }

        val writer = scope.async(Dispatchers.IO) {
            handoffId = create()
            assertTrue(events.compareAndSet(1, 2))
        }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val reset = scope.async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(context, plan())
        }
        assertFalse(reset.isCompleted)
        release.countDown()
        writer.await()
        assertTrue(reset.await() is RestoreOutcome.Completed)
        assertEquals(3, events.get())
        val persistedId = requireNotNull(handoffId)
        assertTrue(database.workManagerHandoffCarrierDao.get(persistedId) != null)
    }

    private suspend fun resetWins(create: () -> String) {
        val publicationEntered = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val ordinaryEntered = CountDownLatch(1)
        val releaseOrdinary = CountDownLatch(1)
        val first = AtomicBoolean(true)
        lateinit var writer: Deferred<Result<String>>
        val writerScope = CoroutineScope(currentCoroutineContext())
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                ordinaryEntered.countDown()
                check(releaseOrdinary.await(20, TimeUnit.SECONDS))
            }
        }
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = {
            publicationEntered.countDown()
            writer = writerScope.async(Dispatchers.IO) {
                writerStarted.countDown()
                runCatching { create() }
            }
        }

        val reset = writerScope.async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(context, plan())
        }
        assertTrue(publicationEntered.await(20, TimeUnit.SECONDS))
        assertTrue(writerStarted.await(20, TimeUnit.SECONDS))
        assertTrue(ordinaryEntered.await(20, TimeUnit.SECONDS))
        releaseOrdinary.countDown()
        val failure = writer.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(reset.await() is RestoreOutcome.Completed)
        assertTrue(database.workManagerHandoffCarrierDao.getOutstanding().isEmpty())
    }

    private fun plan(): RestorePlan = BackupRestoreParser.fromTyped(
        RestoreAppDataItem(
            settings = listOf(
                BackupSettingsItem(
                    key = "f11_handoff_admission_marker",
                    value = "restore-won",
                    type = "String",
                ),
            ),
        ),
    )

    private fun clearHooks() {
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = null
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = null
        RestoreTransactionCoordinator.afterQuiescedBeforeFilesReadyForTesting = null
        RestoreTransactionCoordinator.afterFilesReadyBeforeApplyForTesting = null
        RestoreTransactionCoordinator.afterDataCommittedBeforeReconciliationForTesting = null
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = null
        RestoreTransactionCoordinator.afterCompleteBeforeRetirementForTesting = null
    }
}