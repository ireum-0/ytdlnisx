package com.ireum.ytdl.database.repository

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.R
import com.ireum.ytdl.database.dao.ObserveSourcesDao
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.util.Extensions.calculateNextTimeForObserving
import com.ireum.ytdl.work.ObserveSourceWorker
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ObserveSourcesRepository(private val observeSourcesDao: ObserveSourcesDao, private val workManager: WorkManager, private val sharedPreferences: SharedPreferences) {
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


    suspend fun insert(item: ObserveSourcesItem) : Long{
        if (!observeSourcesDao.checkIfExistsWithSameURL(item.url)){
            return observeSourcesDao.insert(item)
        }
        return -1
    }

    suspend fun delete(item: ObserveSourcesItem): List<Long> {
        return observeSourcesDao.deleteAndCancelWaiting(item.id)
    }


    suspend fun deleteAll(): List<Long> {
        return observeSourcesDao.deleteAllAndCancelWaiting()
    }

    suspend fun update(item: ObserveSourcesItem): List<Long> {
        return if (item.status == SourceStatus.STOPPED) {
            observeSourcesDao.updateAndCancelWaiting(item)
        } else {
            observeSourcesDao.update(item)
            emptyList()
        }
    }

    fun cancelObservationTaskByID(id: Long){
        workManager.cancelUniqueWork("OBSERVE$id")
        workManager.cancelAllWorkByTag("observation_$id")
        workManager.cancelAllWorkByTag(id.toString())
    }

    fun observeTask(it: ObserveSourcesItem){
        cancelObservationTaskByID(it.id)

        Calendar.getInstance().apply {
            val nextRunAt = it.calculateNextTimeForObserving()
            val initialDelay = (nextRunAt - System.currentTimeMillis()).coerceAtLeast(0L)

            //schedule for next time
            val allowMeteredNetworks = sharedPreferences.getBoolean("metered_networks", true)

            val workConstraints = Constraints.Builder()
            if (!allowMeteredNetworks) workConstraints.setRequiredNetworkType(NetworkType.UNMETERED)
            else {
                workConstraints.setRequiredNetworkType(NetworkType.CONNECTED)
            }

            val workRequest = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
                .addTag("observeSources")
                .addTag(it.id.toString())
                .addTag("observation_${it.id}")
                .setConstraints(workConstraints.build())
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .setInputData(Data.Builder().putLong("id", it.id).build())

            workManager.enqueueUniqueWork(
                "OBSERVE${it.id}",
                ExistingWorkPolicy.REPLACE,
                workRequest.build()
            )
        }

    }

}
