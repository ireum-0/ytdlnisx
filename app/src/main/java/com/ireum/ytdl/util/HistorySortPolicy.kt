package com.ireum.ytdl.util

import com.ireum.ytdl.database.DBManager.SORTING
import com.ireum.ytdl.database.repository.HistoryRepository.HistorySortType

enum class HistoryDateDisplayMode {
    DOWNLOAD_DATE,
    SOURCE_DATE,
    RECENT_ACTIVITY,
}

data class EffectiveHistorySort(
    val type: HistorySortType,
    val order: SORTING,
    val dateDisplayMode: HistoryDateDisplayMode,
    val controlsVisible: Boolean,
)

object HistorySortPolicy {
    fun resolve(
        selectedType: HistorySortType,
        selectedOrder: SORTING,
        isRecentMode: Boolean,
        isYoutuberMode: Boolean,
        isKeywordMode: Boolean,
    ): EffectiveHistorySort {
        if (isRecentMode) {
            return EffectiveHistorySort(
                type = HistorySortType.DATE,
                order = SORTING.DESC,
                dateDisplayMode = HistoryDateDisplayMode.RECENT_ACTIVITY,
                controlsVisible = false,
            )
        }

        val effectiveType = if (
            selectedType == HistorySortType.MEDIA_PUBLISHED_DATE &&
            (isYoutuberMode || isKeywordMode)
        ) {
            HistorySortType.DATE
        } else {
            selectedType
        }
        return EffectiveHistorySort(
            type = effectiveType,
            order = selectedOrder,
            dateDisplayMode = if (effectiveType == HistorySortType.MEDIA_PUBLISHED_DATE) {
                HistoryDateDisplayMode.SOURCE_DATE
            } else {
                HistoryDateDisplayMode.DOWNLOAD_DATE
            },
            controlsVisible = true,
        )
    }

    fun isSelectable(
        type: HistorySortType,
        isRecentMode: Boolean,
        isYoutuberMode: Boolean,
        isKeywordMode: Boolean,
    ): Boolean {
        if (isRecentMode) return false
        return type != HistorySortType.MEDIA_PUBLISHED_DATE ||
            (!isYoutuberMode && !isKeywordMode)
    }
}
