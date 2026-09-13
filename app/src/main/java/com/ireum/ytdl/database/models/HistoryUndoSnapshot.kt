package com.ireum.ytdl.database.models

/** Exact database state owned by one History removal and eligible for Undo. */
data class HistoryUndoSnapshot(
    val item: HistoryItem,
    val assignments: List<HistoryKeywordAssignment>,
    val playlistMemberships: List<PlaylistItemCrossRef>,
)
