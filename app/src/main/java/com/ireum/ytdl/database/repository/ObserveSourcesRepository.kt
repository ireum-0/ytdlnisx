package com.ireum.ytdl.database.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import com.ireum.ytdl.R
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.RestoreTransactionCoordinator.RestoreReconciliationAuthority
import com.ireum.ytdl.database.dao.ObserveSourcesDao
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.util.Extensions.calculateNextTimeForObserving
import com.ireum.ytdl.work.ObserveSourceWorker
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

private const val RESTORE_SCHEDULER_ACCEPTANCE_TIMEOUT_MS = 5_000L

class ObserveSourcesRepository(
    private val observeSourcesDao: ObserveSourcesDao,
    private val workManager: WorkManager,
    private val sharedPreferences: SharedPreferences,
    private val context: Context? = null,
) {
    val items: Flow<List<ObserveSourcesItem>> = observeSourcesDao.getAllSourcesFlow()

    enum class SourceStatus {
        ACTIVE, STOPPED
    }

    enum class EveryCategory {
        HOUR, DAY, WEEK, MONTH
    }

    companion object {
        val everyCategoryName = mapOf(
            EveryCategory.HOUR to R.string.hour,
            EveryCategory.DAY to R.string.day,
            EveryCategory.WEEK to R.string.week,
            EveryCategory.MONTH to R.string.month,
        )
    }

    fun getAll(): List<ObserveSourcesItem> = observeSourcesDao.getAllSources()

    fun getByURL(url: String): ObserveSourcesItem = observeSourcesDao.getByURL(url)

    fun getByID(id: Long): ObserveSourcesItem = observeSourcesDao.getByID(id)

    fun getByIDOrNull(id: Long): ObserveSourcesItem? = observeSourcesDao.getByIDOrNull(id)

    suspend fun insert(item: ObserveSourcesItem): Long = withOrdinaryMutation {
        if (!observeSourcesDao.checkIfExistsWithSameURL(item.url)) {
            return@withOrdinaryMutation observeSourcesDao.insert(item)
        }
        return@withOrdinaryMutation -1
    }

    suspend fun delete(item: ObserveSourcesItem): List<Long> = withOrdinaryMutation {
        observeSourcesDao.deleteAndCancelWaiting(item.id)
    }

    suspend fun deleteAll(): List<Long> = withOrdinaryMutation {
        observeSourcesDao.deleteAllAndCancelWaiting()
    }

    suspend fun update(item: ObserveSourcesItem): List<Long> = withOrdinaryMutation {
        if (item.status == SourceStatus.STOPPED) {
            observeSourcesDao.updateAndCancelWaiting(item)
        } else {
            observeSourcesDao.update(item)
            emptyList()
        }
    }

    fun cancelObservationTaskByID(id: Long) {
        val appContext = context?.applicationContext ?: return
        RestoreMutationAdmission.withOrdinaryMutationBlocking(appContext) {
            cancelObservationTaskByIDInternal(id)
        }
    }

    fun observeTask(it: ObserveSourcesItem) {
        val appContext = context?.applicationContext
        if (appContext == null) {
            enqueueObservation(it, null)
        } else {
            RestoreMutationAdmission.withOrdinaryMutationBlocking(appContext) {
                enqueueObservation(it, null)
            }
        }
    }

    /** Ordinary callers receive only the enqueue acceptance boundary. */
    suspend fun observeTaskAndAwait(it: ObserveSourcesItem): Boolean =
        withOrdinaryMutation {
            val operation = enqueueObservation(it, null) ?: return@withOrdinaryMutation false
            operation.result.get(
                RESTORE_SCHEDULER_ACCEPTANCE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
            true
        }

    internal suspend fun cancelObservationTaskByIDForRestore(
        id: Long,
        authority: RestoreReconciliationAuthority,
    ) {
        RestoreMutationAdmission.withRestoreMutation {
            RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(
                requireNotNull(context).applicationContext,
                authority,
            )
            cancelObservationTaskByIDInternal(id)
        }
    }

    internal suspend fun observeTaskAndAwaitForRestore(
        it: ObserveSourcesItem,
        authority: RestoreReconciliationAuthority,
    ): Boolean = RestoreMutationAdmission.withRestoreMutation {
        val appContext = requireNotNull(context).applicationContext
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(appContext, authority)
        val operation = enqueueObservation(it, authority) ?: return@withRestoreMutation false
        operation.result.get(
            RESTORE_SCHEDULER_ACCEPTANCE_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
        true
    }

    private fun cancelObservationTaskByIDInternal(id: Long) {
        workManager.cancelUniqueWork("OBSERVE$id")
        workManager.cancelAllWorkByTag("observation_$id")
        workManager.cancelAllWorkByTag(id.toString())
    }

    private fun enqueueObservation(
        it: ObserveSourcesItem,
        authority: RestoreReconciliationAuthority?,
    ): Operation? {
        val appContext = context?.applicationContext
        if (authority == null) {
            if (appContext != null && RestoreGate.isRestoreInProgress(appContext)) return null
        } else {
            RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(
                requireNotNull(appContext),
                authority,
            )
        }
        cancelObservationTaskByIDInternal(it.id)

        val nextRunAt = it.calculateNextTimeForObserving()
        val initialDelay = (nextRunAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val allowMeteredNetworks = sharedPreferences.getBoolean("metered_networks", true)
        val workConstraints = Constraints.Builder()
        if (!allowMeteredNetworks) {
            workConstraints.setRequiredNetworkType(NetworkType.UNMETERED)
        } else {
            workConstraints.setRequiredNetworkType(NetworkType.CONNECTED)
        }

        val workRequest = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
            .addTag("observeSources")
            .addTag(it.id.toString())
            .addTag("observation_${it.id}")
            .setConstraints(workConstraints.build())
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong("id", it.id).build())
            .build()

        return workManager.enqueueUniqueWork(
            "OBSERVE${it.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest,
        )
    }

    private suspend fun <T> withOrdinaryMutation(block: suspend () -> T): T {
        val appContext = context?.applicationContext
        return if (appContext == null) block() else {
            RestoreMutationAdmission.withOrdinaryMutation(appContext, block)
        }
    }
}