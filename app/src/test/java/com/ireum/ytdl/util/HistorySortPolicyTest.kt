package com.ireum.ytdl.util

import com.ireum.ytdl.database.DBManager.SORTING
import com.ireum.ytdl.database.repository.HistoryRepository.HistorySortType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySortPolicyTest {
    @Test
    fun enteringRecentWithUploadDateSelectedUsesFixedRecentActivitySort() {
        val effective = recent(HistorySortType.MEDIA_PUBLISHED_DATE, SORTING.ASC)

        assertEquals(HistorySortType.DATE, effective.type)
        assertEquals(SORTING.DESC, effective.order)
        assertEquals(HistoryDateDisplayMode.RECENT_ACTIVITY, effective.dateDisplayMode)
        assertFalse(effective.controlsVisible)
    }

    @Test
    fun enteringRecentWithAnyStoredSortHasTheSameEffectiveState() {
        HistorySortType.values().forEach { selected ->
            assertEquals(recent(HistorySortType.MEDIA_PUBLISHED_DATE), recent(selected))
        }
    }

    @Test
    fun noSortOptionCanBeSelectedWhileRecentIsActive() {
        HistorySortType.values().forEach { type ->
            assertFalse(
                HistorySortPolicy.isSelectable(
                    type = type,
                    isRecentMode = true,
                    isYoutuberMode = false,
                    isKeywordMode = false,
                )
            )
        }
    }

    @Test
    fun leavingRecentRestoresTheSelectedNonRecentSort() {
        val effective = HistorySortPolicy.resolve(
            selectedType = HistorySortType.MEDIA_PUBLISHED_DATE,
            selectedOrder = SORTING.ASC,
            isRecentMode = false,
            isYoutuberMode = false,
            isKeywordMode = false,
        )

        assertEquals(HistorySortType.MEDIA_PUBLISHED_DATE, effective.type)
        assertEquals(SORTING.ASC, effective.order)
        assertEquals(HistoryDateDisplayMode.SOURCE_DATE, effective.dateDisplayMode)
        assertTrue(effective.controlsVisible)
    }

    @Test
    fun groupedModesKeepExistingSortsButFallbackFromSourceDate() {
        val groupedSource = HistorySortPolicy.resolve(
            selectedType = HistorySortType.MEDIA_PUBLISHED_DATE,
            selectedOrder = SORTING.ASC,
            isRecentMode = false,
            isYoutuberMode = true,
            isKeywordMode = false,
        )
        val groupedTitle = HistorySortPolicy.resolve(
            selectedType = HistorySortType.TITLE,
            selectedOrder = SORTING.ASC,
            isRecentMode = false,
            isYoutuberMode = false,
            isKeywordMode = true,
        )

        assertEquals(HistorySortType.DATE, groupedSource.type)
        assertEquals(HistoryDateDisplayMode.DOWNLOAD_DATE, groupedSource.dateDisplayMode)
        assertEquals(HistorySortType.TITLE, groupedTitle.type)
        assertFalse(
            HistorySortPolicy.isSelectable(
                HistorySortType.MEDIA_PUBLISHED_DATE,
                isRecentMode = false,
                isYoutuberMode = true,
                isKeywordMode = false,
            )
        )
    }

    @Test
    fun normalHistorySortsAreUnaffected() {
        HistorySortType.values().forEach { selected ->
            val effective = HistorySortPolicy.resolve(
                selectedType = selected,
                selectedOrder = SORTING.ASC,
                isRecentMode = false,
                isYoutuberMode = false,
                isKeywordMode = false,
            )
            assertEquals(selected, effective.type)
            assertEquals(SORTING.ASC, effective.order)
        }
    }

    private fun recent(
        selectedType: HistorySortType,
        selectedOrder: SORTING = SORTING.DESC,
    ): EffectiveHistorySort {
        return HistorySortPolicy.resolve(
            selectedType = selectedType,
            selectedOrder = selectedOrder,
            isRecentMode = true,
            isYoutuberMode = false,
            isKeywordMode = false,
        )
    }
}
