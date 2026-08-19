package com.ireum.ytdl.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.models.LowQualityRedownloadItem
import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.repository.LowQualityRedownloadRepository
import com.ireum.ytdl.util.LowQualityRedownloadProgress
import com.ireum.ytdl.work.LowQualityRedownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class LowQualityRedownloadCandidateUi(
    val item: LowQualityRedownloadItem,
    val title: String,
    val url: String
)

data class LowQualityRedownloadUiState(
    val operation: LowQualityRedownloadOperation? = null,
    val candidates: List<LowQualityRedownloadCandidateUi> = emptyList(),
    val progress: LowQualityRedownloadProgress? = null
)

class LowQualityRedownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val database = DBManager.getInstance(application)
    private val repository = LowQualityRedownloadRepository(database)
    private val manager = LowQualityRedownloadManager.get(application)

    val uiState: StateFlow<LowQualityRedownloadUiState> = repository.currentOperation
        .flatMapLatest { operation ->
            if (operation == null) {
                flowOf(LowQualityRedownloadUiState())
            } else {
                combine(
                    repository.observeItems(operation.operationId),
                    repository.observeLiveCounts(operation.operationId)
                ) { items, liveCounts ->
                    val historyById = if (items.isEmpty()) {
                        emptyMap()
                    } else {
                        database.historyDao.getItemsFromIDs(items.map(LowQualityRedownloadItem::historyId))
                            .associateBy { it.id }
                    }
                    LowQualityRedownloadUiState(
                        operation = operation,
                        candidates = items.map { item ->
                            val history = historyById[item.historyId]
                            LowQualityRedownloadCandidateUi(
                                item = item,
                                title = history?.title.orEmpty(),
                                url = history?.url.orEmpty()
                            )
                        },
                        progress = LowQualityRedownloadProgress.from(operation, items, liveCounts)
                    )
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            LowQualityRedownloadUiState()
        )

    fun startOrReconnect() = manager.startOrReconnect()

    fun setSelected(operationId: String, historyId: Long, selected: Boolean) =
        manager.setSelected(operationId, historyId, selected)

    fun confirm(operationId: String) = manager.confirm(operationId)

    fun cancel(operationId: String) = manager.cancel(operationId)
}
