package com.ireum.ytdl.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.dao.TerminalDao
import com.ireum.ytdl.database.models.TerminalItem
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.TerminalDownloadWorker
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay


class TerminalViewModel(private val application: Application) : AndroidViewModel(application) {
    private val dbManager: DBManager = DBManager.getInstance(application)
    private val dao: TerminalDao = dbManager.terminalDao
    private val notificationUtil = NotificationUtil(application)
    fun getCount() : Int{
        return dao.getActiveTerminalsCount()
    }

    fun getTerminals() : Flow<List<TerminalItem>> {
        return dao.getActiveTerminalDownloadsFlow()
    }

    fun getTerminal(id: Long) : Flow<TerminalItem?> {
        return dao.getActiveTerminalFlow(id)
    }

    suspend fun insert(item: TerminalItem) : Long {
        return dao.insert(item)
    }

    suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) {
            dao.delete(id)
        }
    }

    fun startTerminalDownloadWorker(item: TerminalItem) = viewModelScope.launch(Dispatchers.IO) {
        val workRequest = OneTimeWorkRequestBuilder<TerminalDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putInt("id", item.id.toInt())
                    .putString("command", item.command)
                    .build()
            )
            .addTag("terminal")
            .addTag(item.id.toString())
            .build()

        WorkManager.getInstance(application).beginUniqueWork(
            item.id.toString(),
            ExistingWorkPolicy.KEEP,
            workRequest
        ).enqueue()
    }

    fun cancelTerminalDownload(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        YoutubeDL.getInstance().destroyProcessById(id.toString())
        WorkManager.getInstance(application).cancelUniqueWork(id.toString())
        delay(200)
        notificationUtil.cancelDownloadNotification(id.toInt())
        delete(id)
    }


}

