package com.ireum.ytdl.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.HistoryDateFetchCounts
import com.ireum.ytdl.database.models.HistoryDateFetchOperation
import com.ireum.ytdl.database.repository.HistoryDateFetchRepository
import com.ireum.ytdl.work.HistoryDateFetchManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HistoryDateFetchUiState(
    val operation: HistoryDateFetchOperation? = null,
    val counts: HistoryDateFetchCounts? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryDateFetchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = HistoryDateFetchRepository(DBManager.getInstance(application))
    private val manager = HistoryDateFetchManager.get(application)

    val uiState: StateFlow<HistoryDateFetchUiState> = repository.currentOperation
        .flatMapLatest { operation ->
            if (operation == null) {
                flowOf(HistoryDateFetchUiState())
            } else {
                repository.observeCounts(operation.operationId).map { counts ->
                    HistoryDateFetchUiState(operation, counts)
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HistoryDateFetchUiState(),
        )

    fun startOrReconnect() = manager.startOrReconnect()

    fun cancel(operationId: String) = manager.cancel(operationId)
}
