package com.ireum.ytdl.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.dao.TerminalDao
import com.ireum.ytdl.database.models.TerminalItem
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.work.TerminalExecutionRegistry
import com.ireum.ytdl.work.WorkManagerHandoffRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class TerminalViewModel private constructor(
    private val application: Application,
    databaseOverride: DBManager?,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, null)

    /** Test-only constructor that preserves the production object graph. */
    internal constructor(
        application: Application,
        database: DBManager,
        @Suppress("UNUSED_PARAMETER") testOnly: Boolean,
    ) : this(application, database)

    private val dbManager: DBManager = databaseOverride ?: DBManager.getInstance(application)
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

    suspend fun insert(item: TerminalItem) : Long = withContext(Dispatchers.IO) {
        RestoreMutationAdmission.withOrdinaryMutation(application) {
            dbManager.withTransaction {
                val terminalId = dao.insert(item)
                check(terminalId > 0L) { "Terminal insert did not return a durable id" }
                WorkManagerHandoffRecovery.stageTerminalDispatchWithinTransaction(
                    db = dbManager,
                    terminalId = terminalId,
                    command = item.command,
                )
                terminalId
            }
        }
    }

    suspend fun delete(id: Long) {
        if (!WorkManagerHandoffRecovery.cancelTerminalDispatch(application, id)) return
        if (!TerminalExecutionRegistry.cancel(application, id)) return
        deleteRow(id)
    }

    private suspend fun deleteRow(id: Long) {
        withContext(Dispatchers.IO) {
            dao.delete(id)
        }
    }

    fun startTerminalDownloadWorker(item: TerminalItem) {
        // The row and carrier are committed by insert(); publication is a
        // recoverable bridge owned by WorkManagerHandoffRecovery, not this
        // ViewModel's lifecycle.
        WorkManagerHandoffRecovery.dispatchTerminalDispatch(application, item.id)
    }

    fun cancelTerminalDownload(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        // Supersession is durable before WorkManager cancellation is awaited.
        val cancellationRequested = WorkManagerHandoffRecovery.cancelTerminalDispatch(
            application,
            id,
        )
        if (!cancellationRequested || !TerminalExecutionRegistry.cancel(application, id)) {
            notificationUtil.cancelTerminalDownloadNotification(id.toInt())
            return@launch
        }
        notificationUtil.cancelTerminalDownloadNotification(id.toInt())
        deleteRow(id)
    }


}

