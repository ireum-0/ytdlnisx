package com.ireum.ytdl.database.models

import com.google.gson.Gson

/**
 * The only restore representation that may cross the destructive Reset
 * boundary. Destination identities are deliberately absent; they belong to
 * one apply attempt and are rebuilt after every restart.
 */
class RestorePlan(
    val appMarker: String,
    val formatVersion: Int?,
    val compatibility: BackupCompatibility,
    capabilities: Set<String>,
    data: RestoreAppDataItem,
) {
    private val normalizedData: RestoreAppDataItem = Gson().fromJson(
        Gson().toJson(data),
        RestoreAppDataItem::class.java,
    )
    val capabilities: Set<String> = capabilities.toSet()

    init {
        require(appMarker.isNotBlank())
        require(capabilities.none { it.isBlank() })
    }

    /**
     * Return a detached snapshot. Callers cannot mutate the durable plan by
     * retaining and changing the legacy RestoreAppDataItem carrier.
     */
    val data: RestoreAppDataItem
        get() = Gson().fromJson(
            Gson().toJson(normalizedData),
            RestoreAppDataItem::class.java,
        )

    fun summaryCounts(): Map<String, Int> {
        val snapshot = data
        return linkedMapOf(
            "settings" to (snapshot.settings?.size ?: 0),
            "downloads" to (snapshot.downloads?.size ?: 0),
            "custom_thumbnails" to (snapshot.customThumbnails?.size ?: 0),
            "keyword_groups" to (snapshot.keywordGroups?.size ?: 0),
            "keyword_group_members" to (snapshot.keywordGroupMembers?.size ?: 0),
            "youtuber_groups" to (snapshot.youtuberGroups?.size ?: 0),
            "youtuber_group_members" to (snapshot.youtuberGroupMembers?.size ?: 0),
            "youtuber_group_relations" to (snapshot.youtuberGroupRelations?.size ?: 0),
            "queued" to (snapshot.queued?.size ?: 0),
            "paused" to (snapshot.paused?.size ?: 0),
            "scheduled" to (snapshot.scheduled?.size ?: 0),
            "cancelled" to (snapshot.cancelled?.size ?: 0),
            "errored" to (snapshot.errored?.size ?: 0),
            "saved" to (snapshot.saved?.size ?: 0),
            "cookies" to (snapshot.cookies?.size ?: 0),
            "templates" to (snapshot.templates?.size ?: 0),
            "shortcuts" to (snapshot.shortcuts?.size ?: 0),
            "search_history" to (snapshot.searchHistory?.size ?: 0),
            "observe_sources" to (snapshot.observeSources?.size ?: 0),
            "automatic_keyword_rules" to (snapshot.automaticKeywordRules?.size ?: 0),
            "history_keyword_assignments" to (snapshot.historyKeywordAssignments?.size ?: 0),
            "playlists" to (snapshot.playlists?.size ?: 0),
            "playlist_item_cross_refs" to (snapshot.playlistItemCrossRefs?.size ?: 0),
            "playlist_groups" to (snapshot.playlistGroups?.size ?: 0),
            "playlist_group_members" to (snapshot.playlistGroupMembers?.size ?: 0),
        )
    }
}

enum class BackupCompatibility {
    LEGACY_LOWERCASE_UNVERSIONED,
    LEGACY_UPPERCASE_UNVERSIONED,
    VERSION_3,
    VERSION_4,
    TYPED_PROGRAMMATIC,
}
