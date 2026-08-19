package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.models.HistoryKeywordAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordAssignmentMaterializerTest {
    @Test
    fun overlappingSourcesAreDeduplicatedWithoutLosingSourceRows() {
        val rows = listOf(
            row("Music", "music", "MANUAL", 0),
            row("music", "music", "RULE", 11),
            row("Live", "live", "RULE", 11),
            row("Live", "live", "RULE", 12)
        )
        assertEquals("Music, Live", KeywordAssignmentMaterializer.materialize(rows))
        assertEquals(4, rows.distinctBy {
            listOf(it.historyItemId, it.normalizedKeyword, it.sourceType, it.sourceId)
        }.size)
    }

    @Test
    fun sourceIdentityIsAlwaysNonNullAndStable() {
        val manual = row("Favorite", "favorite", "MANUAL", 0)
        val rule = row("Favorite", "favorite", "RULE", 42)
        assertEquals(0L, manual.sourceId)
        assertTrue(rule.sourceId > 0)
        assertTrue(manual.sourceType.isNotBlank())
        assertTrue(rule.sourceType.isNotBlank())
    }

    @Test
    fun repeatedSyncRowsProduceSameMaterializedValue() {
        val once = listOf(row("Music", "music", "RULE", 7))
        val retried = once + once
        assertEquals(
            KeywordAssignmentMaterializer.materialize(once),
            KeywordAssignmentMaterializer.materialize(retried)
        )
    }

    private fun row(keyword: String, normalized: String, sourceType: String, sourceId: Long) =
        HistoryKeywordAssignment(1, normalized, keyword, sourceType, sourceId, 0, 1)
}
