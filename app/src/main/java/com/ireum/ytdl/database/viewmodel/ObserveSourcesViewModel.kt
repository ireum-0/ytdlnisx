package com.ireum.ytdl.database.viewmodel

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.repository.AutomaticKeywordObservationCoverage
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.ObserveSourceWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class ObserveSourcesViewModel(private val application: Application) : AndroidViewModel(application) {
    private val repository: ObserveSourcesRepository
    val items: LiveData<List<ObserveSourcesItem>>
    private val workManager : WorkManager
    private val preferences : SharedPreferences
    private val notificationUtil = NotificationUtil(application)

    init {
        val dao = DBManager.getInstance(application).observeSourcesDao
        workManager = WorkManager.getInstance(application)
        preferences = PreferenceManager.getDefaultSharedPreferences(application)
        repository = ObserveSourcesRepository(dao, workManager, preferences, application)
        items = repository.items.asLiveData()
    }

    fun getAll(): List<ObserveSourcesItem> {
        return repository.getAll()
    }

    fun getByURL(url: String) : ObserveSourcesItem {
        return repository.getByURL(url)
    }

    fun getByID(id: Long) : ObserveSourcesItem {
        return repository.getByID(id)
    }

    suspend fun insertUpdate(item: ObserveSourcesItem, resetProcessedLinks: Boolean = false) : Long {
        if (RestoreGate.isRestoreInProgress(application)) return 0L
        if (item.id > 0) {
            if (!repository.reconfigure(item, resetProcessedLinks)) return 0L
            notificationUtil.cancelObserveRetryConfirmation(item.id)
            AutomaticKeywordObservationCoverage(application).reconcile()
            return item.id
        }

        val id = repository.insertAndSchedule(item)
        AutomaticKeywordObservationCoverage(application).reconcile()
        return id
    }

    suspend fun stopObserving(item: ObserveSourcesItem) {
        if (RestoreGate.isRestoreInProgress(application)) return
        val cancelledIds = repository.stop(item) ?: return
        notificationUtil.cancelObserveRetryConfirmation(item.id)
        cancelledIds.forEach(notificationUtil::cancelMembershipWaitingNotification)
        AutomaticKeywordObservationCoverage(application).reconcile()
    }

    fun delete(item: ObserveSourcesItem) = viewModelScope.launch(Dispatchers.IO) {
        if (RestoreGate.isRestoreInProgress(application)) return@launch
        val cancelledIds = repository.delete(item) ?: return@launch
        notificationUtil.cancelObserveRetryConfirmation(item.id)
        cancelledIds.forEach(notificationUtil::cancelMembershipWaitingNotification)
        AutomaticKeywordObservationCoverage(application).reconcile()
    }

    fun deleteAll() = viewModelScope.launch(Dispatchers.IO) {
        if (RestoreGate.isRestoreInProgress(application)) return@launch
        val beforeDelete = getAll()
        repository.deleteAll().forEach(notificationUtil::cancelMembershipWaitingNotification)
        beforeDelete.forEach { notificationUtil.cancelObserveRetryConfirmation(it.id) }
        AutomaticKeywordObservationCoverage(application).reconcile()
    }

    suspend fun update(item: ObserveSourcesItem) {
        if (RestoreGate.isRestoreInProgress(application)) return
        if (repository.reconfigure(item, resetProcessedLinks = false)) {
            notificationUtil.cancelObserveRetryConfirmation(item.id)
        }
        AutomaticKeywordObservationCoverage(application).reconcile()
    }

    suspend fun reactivate(item: ObserveSourcesItem): Boolean {
        if (RestoreGate.isRestoreInProgress(application)) return false
        val active = item.copy(status = ObserveSourcesRepository.SourceStatus.ACTIVE)
        val updated = repository.reconfigure(active, resetProcessedLinks = false, resetRunCount = true)
        if (updated) {
            notificationUtil.cancelObserveRetryConfirmation(item.id)
            AutomaticKeywordObservationCoverage(application).reconcile()
        }
        return updated
    }

    suspend fun searchNow(item: ObserveSourcesItem): Boolean = repository.observeTaskAndAwait(item)
}
