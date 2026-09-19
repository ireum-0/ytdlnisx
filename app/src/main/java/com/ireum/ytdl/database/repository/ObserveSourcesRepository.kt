package com.ireum.ytdl.database.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import com.ireum.ytdl.R
import com.ireum.ytdl.database.RestoreGate
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
    val items : Flow<List<ObserveSourcesItem>> = observeSourcesDao.getAllSourcesFlow()
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
            EveryCategory.MONTH to R.string.month
        )
    }


    fun getAll() : List<ObserveSourcesItem> {
        return observeSourcesDao.getAllSources()
    }

    fun getByURL(url: String) : ObserveSourcesItem {
        return observeSourcesDao.getByURL(url)
    }

    fun getByID(id: Long) : ObserveSourcesItem {
        return observeSourcesDao.getByID(id)
    }

    fun getByIDOrNull(id: Long): ObserveSourcesItem? {
        return observeSourcesDao.getByIDOrNull(id)
    }


    suspend fun insert(item: ObserveSourcesItem) : Long {
        checkRestoreAdmission()
        if (!observeSourcesDao.checkIfExistsWithSameURL(item.url)){
            return observeSourcesDao.insert(item)
        }
        return -1
    }

    suspend fun delete(item: ObserveSourcesItem): List<Long> {
        checkRestoreAdmission()
        return observeSourcesDao.deleteAndCancelWaiting(item.id)
    }


    suspend fun deleteAll(): List<Long> {
        checkRestoreAdmission()
        return observeSourcesDao.deleteAllAndCancelWaiting()
    }

    suspend fun update(item: ObserveSourcesItem): List<Long> {
        checkRestoreAdmission()
        return if (item.status == SourceStatus.STOPPED) {
            observeSourcesDao.updateAndCancelWaiting(item)
        } else {
            observeSourcesDao.update(item)
            emptyList()
        }
    }

    fun cancelObservationTaskByID(id: Long, allowDuringRestore: Boolean = false) {
        if (!allowDuringRestore && context != null && RestoreGate.isRestoreInProgress(context)) return
        workManager.cancelUniqueWork("OBSERVE$id")
        workManager.cancelAllWorkByTag("observation_$id")
        workManager.cancelAllWorkByTag(id.toString())
    }

    fun observeTask(it: ObserveSourcesItem, allowDuringRestore: Boolean = false) {
        enqueueObservation(it, allowDuringRestore)
    }

    /**
     * Post-commit Reset reconciliation uses the WorkManager operation result
     * as its finite acceptance boundary. Ordinary callers retain the
     * fire-and-return API above.
     */
    suspend fun observeTaskAndAwait(
        it: ObserveSourcesItem,
        allowDuringRestore: Boolean = false,
    ): Boolean {
        val operation = enqueueObservation(it, allowDuringRestore) ?: return false
        operation.result.get(RESTORE_SCHEDULER_ACCEPTANCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return true
    }

    private fun enqueueObservation(
        it: ObserveSourcesItem,
        allowDuringRestore: Boolean,
    ): Operation? {
        if (!allowDuringRestore && context != null && RestoreGate.isRestoreInProgress(context)) {
            return null
        }
        cancelObservationTaskByID(it.id, allowDuringRestore)

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

    private fun checkRestoreAdmission() {
        if (context != null) {
            check(!RestoreGate.isRestoreInProgress(context)) {
                "Restore transaction is active"
            }
        }
    }

}
