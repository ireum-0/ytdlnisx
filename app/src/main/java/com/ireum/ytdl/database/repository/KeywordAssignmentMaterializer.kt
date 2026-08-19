package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.models.HistoryKeywordAssignment

object KeywordAssignmentMaterializer {
    fun materialize(orderedAssignments: List<HistoryKeywordAssignment>): String {
        val seen = linkedSetOf<String>()
        return orderedAssignments.mapNotNull {
            if (seen.add(it.normalizedKeyword)) it.keyword else null
        }.joinToString(", ")
    }
}
