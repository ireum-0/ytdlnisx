package com.ireum.ytdl.work

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Proves the accepted low-quality carrier reaches the real WorkManager. */
@RunWith(AndroidJUnit4::class)
class LowQualityRedownloadRealWorkManagerTest {
    private lateinit var context: Application
    private lateinit var database: DBManager
    private lateinit var repository: LowQualityRedownloadRepository
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        LowQualityRedownloadLedger.cancelAllEnqueueConvergenceJobsAndJoinForTesting()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        repository = LowQualityRedownloadRepository(database)
    }

    @After
    fun tearDown() = runBlocking {
        repository.getActiveOperation()?.operationId?.let { operationId ->
            workManager.cancelUniqueWork(
                LowQualityRedownloadWorker.uniqueWorkName(operationId)
            ).result.get(10, TimeUnit.SECONDS)
        }
        LowQualityRedownloadLedger.cancelAllEnqueueConvergenceJobsAndJoinForTesting()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun scanningPhaseReconcileReachesRealWorkManagerWithExactOperationIdentity() = runBlocking {
        val operation = repository.createOrReconnect(now = 100L)
        val manager = LowQualityRedownloadManager.createForTesting(context, database)

        manager.reconcile()

        val workInfo = awaitUniqueWork(operation.operationId)
        assertTrue(workInfo.tags.contains(LowQualityRedownloadWorker.GLOBAL_TAG))
        assertTrue(
            workInfo.tags.contains(
                LowQualityRedownloadWorker.operationTag(operation.operationId)
            )
        )
        assertEquals(
            LowQualityRedownloadPhase.SCANNING,
            operation.phaseValue,
        )
    }

    @Test
    fun reconstructedManagerUsesSameUniqueOperationCarrierWithoutPeerOperation() = runBlocking {
        val operation = repository.createOrReconnect(now = 100L)
        val firstManager = LowQualityRedownloadManager.createForTesting(context, database)
        firstManager.reconcile()
        val firstWork = awaitUniqueWork(operation.operationId)

        LowQualityRedownloadLedger.cancelAllEnqueueConvergenceJobsAndJoinForTesting()
        val reconstructedManager = LowQualityRedownloadManager.createForTesting(context, database)
        reconstructedManager.reconcile()

        val workInfos = awaitUniqueWorkInfos(operation.operationId)
        assertTrue(workInfos.isNotEmpty())
        assertTrue(workInfos.all {
            it.tags.contains(LowQualityRedownloadWorker.operationTag(operation.operationId))
        })
        assertTrue(workInfos.any { it.id == firstWork.id })
    }

    private suspend fun awaitUniqueWork(operationId: String): WorkInfo {
        var result: WorkInfo? = null
        withTimeout(10_000L) {
            while (result == null) {
                result = awaitUniqueWorkInfos(operationId).firstOrNull()
                if (result == null) delay(25L)
            }
        }
        return checkNotNull(result)
    }

    private fun awaitUniqueWorkInfos(operationId: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(
            LowQualityRedownloadWorker.uniqueWorkName(operationId)
        ).get(5, TimeUnit.SECONDS)
}
