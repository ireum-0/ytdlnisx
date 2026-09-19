package com.ireum.ytdl.database.models

import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem

data class RestoreAppDataItem(
    val settings : List<BackupSettingsItem>? = null,
    val downloads: List<HistoryItem>? = null,
    val customThumbnails: List<BackupCustomThumbItem>? = null,
    val keywordGroups: List<KeywordGroup>? = null,
    val keywordGroupMembers: List<KeywordGroupMember>? = null,
    val youtuberGroups: List<YoutuberGroup>? = null,
    val youtuberGroupMembers: List<YoutuberGroupMember>? = null,
    val youtuberGroupRelations: List<YoutuberGroupRelation>? = null,
    val historyVisibleChildYoutuberGroups: Set<Long>? = null,
    val historyVisibleChildYoutubers: Set<String>? = null,
    val historyVisibleChildKeywords: Set<String>? = null,
    val youtuberMeta: List<YoutuberMeta>? = null,
    val queued: List<DownloadItem>? = null,
    val paused: List<DownloadItem>? = null,
    val scheduled: List<DownloadItem>? = null,
    val cancelled: List<DownloadItem>? = null,
    val errored: List<DownloadItem>? = null,
    val saved: List<DownloadItem>? = null,
    val cookies: List<CookieItem>? = null,
    val templates: List<CommandTemplate>? = null,
    val shortcuts: List<TemplateShortcut>? = null,
    val searchHistory: List<SearchHistoryItem>? = null,
    val observeSources: List<ObserveSourcesItem>? = null,
    val automaticKeywordRules: List<AutomaticKeywordRule>? = null,
    val automaticKeywordRuleKeywords: List<AutomaticKeywordRuleKeyword>? = null,
    val automaticKeywordRuleVideoMatches: List<AutomaticKeywordRuleVideoMatch>? = null,
    val historyKeywordAssignments: List<HistoryKeywordAssignment>? = null,
    val playlists: List<Playlist>? = null,
    val playlistItemCrossRefs: List<PlaylistItemCrossRef>? = null,
    val playlistGroups: List<PlaylistGroup>? = null,
    val playlistGroupMembers: List<PlaylistGroupMember>? = null,
)

data class BackupSettingsItem(
    var key: String,
    var value: String,
    var type: String?
)

data class BackupCustomThumbItem(
    var historyId: Long,
    var base64: String,
    var extension: String = "jpg"
)
