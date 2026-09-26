package com.ireum.ytdl.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.dao.TerminalDao
import com.ireum.ytdl.database.models.TerminalItem
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.terminal.TerminalCommandIntentMaterializer
import com.ireum.ytdl.util.terminal.TerminalCommandPlanFactory
import com.ireum.ytdl.work.TerminalCancellationCoordinator
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
        // Materialize the configured provider authority into the exact durable
        // command BEFORE the row and its dispatch carrier are staged.  Planning
        // resolves the destination much later, after durable admission, so a
        // configured provider that was never part of the command would let a
        // later command_path change redirect this already-durable Terminal.
        val materialized = TerminalCommandIntentMaterializer.materialize(
            command = item.command,
            configuredCommandPath = TerminalCommandPlanFactory.configuredDestination(
                PreferenceManager.getDefaultSharedPreferences(application),
            ),
        )
        val durableItem =
            if (materialized == item.command) item else item.copy(command = materialized)
        RestoreMutationAdmission.withOrdinaryMutation(application) {
            dbManager.withTransaction {
                val terminalId = dao.insert(durableItem)
                check(terminalId > 0L) { "Terminal insert did not return a durable id" }
                WorkManagerHandoffRecovery.stageTerminalDispatchWithinTransaction(
                    db = dbManager,
                    terminalId = terminalId,
                    command = durableItem.command,
                )
                terminalId
            }
        }
    }

    suspend fun delete(id: Long) {
        val result = TerminalCancellationCoordinator.cancel(application, id)
        if (!result.rowDeletionAuthorized) return
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
        val result = TerminalCancellationCoordinator.cancel(application, id)
        notificationUtil.cancelTerminalDownloadNotification(id.toInt())
        if (!result.rowDeletionAuthorized) return@launch
        deleteRow(id)
    }


}

