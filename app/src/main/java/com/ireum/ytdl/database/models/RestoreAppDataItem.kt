package com.ireum.ytdl.database.models

import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.google.gson.JsonArray

data class RestoreAppDataItem(
    var settings : List<BackupSettingsItem>? = null,
    var downloads: List<HistoryItem>? = null,
    var customThumbnails: List<BackupCustomThumbItem>? = null,
    var keywordGroups: List<KeywordGroup>? = null,
    var keywordGroupMembers: List<KeywordGroupMember>? = null,
    var youtuberGroups: List<YoutuberGroup>? = null,
    var youtuberGroupMembers: List<YoutuberGroupMember>? = null,
    var youtuberGroupRelations: List<YoutuberGroupRelation>? = null,
    var historyVisibleChildYoutuberGroups: Set<Long>? = null,
    var historyVisibleChildKeywords: Set<String>? = null,
    var youtuberMeta: List<YoutuberMeta>? = null,
    var queued: List<DownloadItem>? = null,
    var scheduled: List<DownloadItem>? = null,
    var cancelled: List<DownloadItem>? = null,
    var errored: List<DownloadItem>? = null,
    var saved: List<DownloadItem>? = null,
    var cookies: List<CookieItem>? = null,
    var templates: List<CommandTemplate>? = null,
    var shortcuts: List<TemplateShortcut>? = null,
    var searchHistory: List<SearchHistoryItem>? = null,
    var observeSources: List<ObserveSourcesItem>? = null,
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
