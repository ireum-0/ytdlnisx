package com.ireum.ytdl.database

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.ireum.ytdl.database.models.AutomaticKeywordRule
import com.ireum.ytdl.database.models.AutomaticKeywordRuleKeyword
import com.ireum.ytdl.database.models.AutomaticKeywordRuleVideoMatch
import com.ireum.ytdl.database.models.AutomaticKeywordRuleTypes
import com.ireum.ytdl.database.models.BackupCompatibility
import com.ireum.ytdl.database.models.BackupCustomThumbItem
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.CookieItem
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.HistoryKeywordAssignment
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.database.models.KeywordGroup
import com.ireum.ytdl.database.models.KeywordGroupMember
import com.ireum.ytdl.database.models.Playlist
import com.ireum.ytdl.database.models.PlaylistGroup
import com.ireum.ytdl.database.models.PlaylistGroupMember
import com.ireum.ytdl.database.models.PlaylistItemCrossRef
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.RestorePlan
import com.ireum.ytdl.database.models.SearchHistoryItem
import com.ireum.ytdl.database.models.TemplateShortcut
import com.ireum.ytdl.database.models.YoutuberGroup
import com.ireum.ytdl.database.models.YoutuberGroupMember
import com.ireum.ytdl.database.models.YoutuberGroupRelation
import com.ireum.ytdl.database.models.YoutuberMeta
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.models.observeSources.ObservationPurposes
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import com.ireum.ytdl.util.BackupSettingsUtil
import java.util.Locale

/** The single schema and validation authority for backup restore input. */
internal object BackupRestoreParser {
    const val CURRENT_APP_MARKER = "YTDLnisX_backup"
    private const val INITIAL_APP_MARKER = "YTDLnisx_backup"
    private const val LEGACY_PLAYLIST_ITEMS_KEY = "playlist_items"

    data class PlaylistPayload(
        val playlists: List<Playlist>?,
        val playlistItemCrossRefs: List<PlaylistItemCrossRef>?,
        val playlistGroups: List<PlaylistGroup>?,
        val playlistGroupMembers: List<PlaylistGroupMember>?,
    )

    fun parse(raw: String, gson: Gson = Gson()): RestorePlan {
        val json = try {
            JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("Backup root must be a JSON object")
        } catch (error: Exception) {
            throw IllegalArgumentException("Malformed backup JSON", error)
        }
        return parse(json, gson)
    }

    fun parse(json: JsonObject, gson: Gson = Gson()): RestorePlan {
        val marker = json.stringValue("app")
            ?: throw IllegalArgumentException("Backup app marker is missing")
        val compatibility = compatibilityFor(marker, json)
        val version = json.intValueOrNull("backup_format_version")
        val playlistPayload = parsePlaylistPayload(json, gson, compatibility)
        val rawHistory = json.objectArray("downloads", gson, HistoryItem::class.java)
        val data = normalize(
            RestoreAppDataItem(
                settings = json.objectArray("settings", gson, BackupSettingsItem::class.java),
                downloads = rawHistory,
                customThumbnails = json.objectArray("custom_thumbnails", gson, BackupCustomThumbItem::class.java),
                keywordGroups = json.objectArray("keyword_groups", gson, KeywordGroup::class.java),
                keywordGroupMembers = json.objectArray("keyword_group_members", gson, KeywordGroupMember::class.java),
                youtuberGroups = json.objectArray("youtuber_groups", gson, YoutuberGroup::class.java),
                youtuberGroupMembers = json.objectArray("youtuber_group_members", gson, YoutuberGroupMember::class.java),
                youtuberGroupRelations = json.objectArray("youtuber_group_relations", gson, YoutuberGroupRelation::class.java),
                historyVisibleChildYoutuberGroups = json.longStringSet("history_visible_child_youtuber_groups"),
                historyVisibleChildYoutubers = json.stringSet("history_visible_child_youtubers"),
                historyVisibleChildKeywords = json.stringSet("history_visible_child_keywords"),
                youtuberMeta = json.objectArray("youtuber_meta", gson, YoutuberMeta::class.java),
                queued = json.objectArray("queued", gson, DownloadItem::class.java),
                paused = json.objectArray("paused", gson, DownloadItem::class.java),
                scheduled = json.objectArray("scheduled", gson, DownloadItem::class.java),
                cancelled = json.objectArray("cancelled", gson, DownloadItem::class.java),
                errored = json.objectArray("errored", gson, DownloadItem::class.java),
                saved = json.objectArray("saved", gson, DownloadItem::class.java),
                cookies = json.objectArray("cookies", gson, CookieItem::class.java),
                templates = json.objectArray("templates", gson, com.ireum.ytdl.database.models.CommandTemplate::class.java),
                shortcuts = json.objectArray("shortcuts", gson, TemplateShortcut::class.java),
                searchHistory = json.objectArray("search_history", gson, SearchHistoryItem::class.java),
                observeSources = json.observeSourcesArray(gson),
                automaticKeywordRules = json.objectArray("automatic_keyword_rules", gson, AutomaticKeywordRule::class.java),
                automaticKeywordRuleKeywords = json.objectArray("automatic_keyword_rule_keywords", gson, AutomaticKeywordRuleKeyword::class.java),
                automaticKeywordRuleVideoMatches = json.objectArray("automatic_keyword_rule_video_matches", gson, AutomaticKeywordRuleVideoMatch::class.java),
                historyKeywordAssignments = json.objectArray("history_keyword_assignments", gson, HistoryKeywordAssignment::class.java),
                playlists = playlistPayload.playlists,
                playlistItemCrossRefs = playlistPayload.playlistItemCrossRefs,
                playlistGroups = playlistPayload.playlistGroups,
                playlistGroupMembers = playlistPayload.playlistGroupMembers,
            ),
            compatibility,
        )
        return RestorePlan(
            appMarker = marker,
            formatVersion = version,
            compatibility = compatibility,
            capabilities = typedCapabilities(data),
            data = data,
        )
    }

    /** Typed callers use this adapter instead of bypassing validation. */
    fun fromTyped(data: RestoreAppDataItem): RestorePlan {
        val normalized = normalize(data, BackupCompatibility.TYPED_PROGRAMMATIC)
        return RestorePlan(
            appMarker = CURRENT_APP_MARKER,
            formatVersion = 4,
            compatibility = BackupCompatibility.TYPED_PROGRAMMATIC,
            capabilities = typedCapabilities(normalized),
            data = normalized,
        )
    }

    /** Revalidates a plan before it can acquire destructive Reset authority. */
    fun validatePlan(plan: RestorePlan): RestorePlan {
        validatePlanMetadata(plan.appMarker, plan.formatVersion, plan.compatibility)
        val normalized = normalize(plan.data, plan.compatibility)
        require(plan.capabilities == typedCapabilities(normalized)) {
            "Restore capability declaration does not match the normalized payload"
        }
        return RestorePlan(
            appMarker = plan.appMarker,
            formatVersion = plan.formatVersion,
            compatibility = plan.compatibility,
            capabilities = typedCapabilities(normalized),
            data = normalized,
        )
    }

    /** Compatibility helper retained for existing playlist production tests. */
    fun parsePlaylistPayload(json: JsonObject, gson: Gson): PlaylistPayload =
        parsePlaylistPayload(
            json,
            gson,
            compatibilityFor(json.stringValue("app") ?: CURRENT_APP_MARKER, json),
        )

    private fun parsePlaylistPayload(
        json: JsonObject,
        gson: Gson,
        compatibility: BackupCompatibility,
    ): PlaylistPayload {
        val currentKeys = listOf(
            "playlists",
            "playlist_item_cross_refs",
            "playlist_groups",
            "playlist_group_members",
        )
        val legacyKeys = currentKeys.map {
            if (it == "playlist_item_cross_refs") LEGACY_PLAYLIST_ITEMS_KEY else it
        }
        val currentPresent = currentKeys.filter(json::has)
        val legacyPresent = if (!json.has("playlist_item_cross_refs")) {
            legacyKeys.filter(json::has)
        } else {
            emptyList()
        }
        val present = if (legacyPresent.isNotEmpty()) legacyPresent else currentPresent
        if (present.isEmpty()) return PlaylistPayload(null, null, null, null)
        if (compatibility == BackupCompatibility.VERSION_3) {
            throw IllegalArgumentException("Format 3 does not support playlist payloads")
        }
        if (
            legacyPresent.isEmpty() &&
            compatibility in setOf(
                BackupCompatibility.LEGACY_LOWERCASE_UNVERSIONED,
                BackupCompatibility.LEGACY_UPPERCASE_UNVERSIONED,
            )
        ) {
            throw IllegalArgumentException("Unversioned backups require the historical playlist_items payload")
        }
        val expected = if (legacyPresent.isNotEmpty()) legacyKeys else currentKeys
        require(present.size == expected.size) {
            "Incomplete playlist relationship payload; present fields: ${present.joinToString()}"
        }
        if (legacyPresent.isNotEmpty() && compatibility == BackupCompatibility.VERSION_4) {
            throw IllegalArgumentException("Format 4 requires playlist_item_cross_refs")
        }
        val crossRefKey = if (legacyPresent.isNotEmpty()) {
            LEGACY_PLAYLIST_ITEMS_KEY
        } else {
            "playlist_item_cross_refs"
        }
        return PlaylistPayload(
            playlists = json.objectArray("playlists", gson, Playlist::class.java),
            playlistItemCrossRefs = json.objectArray(crossRefKey, gson, PlaylistItemCrossRef::class.java),
            playlistGroups = json.objectArray("playlist_groups", gson, PlaylistGroup::class.java),
            playlistGroupMembers = json.objectArray("playlist_group_members", gson, PlaylistGroupMember::class.java),
        )
    }

    private fun compatibilityFor(marker: String, json: JsonObject): BackupCompatibility {
        require(marker == CURRENT_APP_MARKER || marker == INITIAL_APP_MARKER) {
            "Unsupported backup app marker: $marker"
        }
        val versionElement = json["backup_format_version"]
        if (json.has("backup_format_version")) {
            val versionValue = versionElement
                ?: throw IllegalArgumentException("backup_format_version must be an integer")
            require(!versionValue.isJsonNull) {
                "backup_format_version must be an integer"
            }
            require(versionValue.isJsonPrimitive && versionValue.asJsonPrimitive.isNumber) {
                "backup_format_version must be an integer"
            }
            val version = versionValue.asString.toIntOrNull()
                ?: throw IllegalArgumentException("backup_format_version must be an integer")
            require(version == 3 || version == 4) {
                "Unsupported backup format version: $version"
            }
            require(marker == CURRENT_APP_MARKER) {
                "Versioned backups require the current app marker"
            }
            return if (version == 3) BackupCompatibility.VERSION_3 else BackupCompatibility.VERSION_4
        }
        return if (marker == INITIAL_APP_MARKER) {
            BackupCompatibility.LEGACY_LOWERCASE_UNVERSIONED
        } else {
            BackupCompatibility.LEGACY_UPPERCASE_UNVERSIONED
        }
    }

    private fun validatePlanMetadata(
        marker: String,
        version: Int?,
        compatibility: BackupCompatibility,
    ) {
        require(marker == CURRENT_APP_MARKER || marker == INITIAL_APP_MARKER) {
            "Unsupported backup app marker: $marker"
        }
        when (compatibility) {
            BackupCompatibility.LEGACY_LOWERCASE_UNVERSIONED ->
                require(marker == INITIAL_APP_MARKER && version == null)
            BackupCompatibility.LEGACY_UPPERCASE_UNVERSIONED ->
                require(marker == CURRENT_APP_MARKER && version == null)
            BackupCompatibility.VERSION_3 ->
                require(marker == CURRENT_APP_MARKER && version == 3)
            BackupCompatibility.VERSION_4,
            BackupCompatibility.TYPED_PROGRAMMATIC ->
                require(marker == CURRENT_APP_MARKER && version == 4)
        }
    }

    private fun normalize(
        input: RestoreAppDataItem,
        compatibility: BackupCompatibility,
    ): RestoreAppDataItem {
        val settings = input.settings
            ?.filter { BackupSettingsUtil.isPortablePreferenceKey(it.key) }
            ?.also(::validateSettings)
        val history = input.downloads?.map { item ->
            require(item.id > 0L) { "History backup identity must be positive" }
            require(item.url.isNotBlank()) { "History URL must not be blank" }
            item.copy(customThumb = "")
        }
        require(history.orEmpty().map(HistoryItem::id).distinct().size == history.orEmpty().size) {
            "Duplicate History backup identity"
        }
        val historyIds = history?.map { it.id }?.toSet()
        val thumbs = input.customThumbnails?.map { item ->
            validateThumbnail(item)
            require(historyIds?.contains(item.historyId) == true) {
                "Custom thumbnail refers to a missing backup History identity"
            }
            item.copy(extension = canonicalExtension(item.extension))
        }?.also {
            require(it.map(BackupCustomThumbItem::historyId).distinct().size == it.size) {
                "Duplicate custom thumbnail backup History identity"
            }
        }

        val keywordGroups = input.keywordGroups?.map {
            require(it.id > 0L && it.name.isNotBlank()) { "Invalid keyword group" }
            it
        }
        val keywordGroupIds = keywordGroups?.map { it.id }?.toSet().orEmpty()
        val keywordMembers = input.keywordGroupMembers?.mapNotNull {
            require(it.groupId > 0L && it.keyword.isNotBlank()) { "Invalid keyword group member" }
            it.takeIf { member -> member.groupId in keywordGroupIds }
        }

        val youtuberGroups = input.youtuberGroups?.map {
            require(it.id > 0L && it.name.isNotBlank()) { "Invalid youtuber group" }
            it
        }
        val youtuberGroupIds = youtuberGroups?.map { it.id }?.toSet().orEmpty()
        val youtuberMembers = input.youtuberGroupMembers?.mapNotNull {
            require(it.groupId > 0L && it.author.isNotBlank()) { "Invalid youtuber group member" }
            it.takeIf { member -> member.groupId in youtuberGroupIds }
        }
        val youtuberRelations = input.youtuberGroupRelations?.mapNotNull {
            require(it.parentGroupId > 0L && it.childGroupId > 0L) { "Invalid youtuber group relation" }
            it.takeIf { relation ->
                relation.parentGroupId in youtuberGroupIds &&
                    relation.childGroupId in youtuberGroupIds &&
                    relation.parentGroupId != relation.childGroupId
            }
        }?.distinct()

        val sources = input.observeSources?.map { source ->
            require(source.id > 0L && source.url.isNotBlank()) { "Invalid ObserveSource" }
            require(source.status == ObserveSourcesRepository.SourceStatus.ACTIVE ||
                source.status == ObserveSourcesRepository.SourceStatus.STOPPED) {
                "Invalid ObserveSource status"
            }
            source.copy(
                // Retain the backup-local identity in the immutable plan;
                // the Reset apply allocates and owns the destination id map.
                id = source.id,
                observationPurpose = ObservationPurposes.USER,
                managedConditionKey = "",
                downloadItemTemplate = source.downloadItemTemplate.copy(
                    id = 0L,
                    observeSourceId = 0L,
                    executionId = "",
                ),
            )
        }
        require(
            sources.orEmpty().map(ObserveSourcesItem::id).distinct().size == sources.orEmpty().size
        ) { "Duplicate ObserveSource backup identity" }
        val sourceIds = input.observeSources?.map { it.id }?.toSet().orEmpty()

        fun normalizeDownloads(
            items: List<DownloadItem>?,
            forcePaused: Boolean = false,
        ): List<DownloadItem>? = items?.map { item ->
            require(item.url.isNotBlank()) { "Download URL must not be blank" }
            require(item.id >= 0L) { "Download identity must be non-negative" }
            require(item.observeSourceId >= 0L) { "Download ObserveSource identity must be non-negative" }
            require(DownloadRepository.Status.values().any { it.name == item.status }) {
                "Invalid Download status: ${item.status}"
            }
            val status = if (forcePaused) {
                DownloadRepository.Status.Paused.toString()
            } else item.status
            val sourceId = if (item.observeSourceId in sourceIds) item.observeSourceId else 0L
            val normalizedStatus = if (
                item.observeSourceId > 0L && sourceId == 0L &&
                item.status == DownloadRepository.Status.WaitingForMembership.toString()
            ) {
                DownloadRepository.Status.Error.toString()
            } else {
                status
            }
            item.copy(
                // Keep the backup-local id for explicit mapping.  The Reset
                // apply always inserts with id=0 and never trusts this value
                // as a destination identity.
                id = item.id,
                status = normalizedStatus,
                observeSourceId = sourceId,
                executionId = "",
            )
        }

        val rules = input.automaticKeywordRules?.mapNotNull { rule ->
            require(rule.id > 0L) { "Automatic keyword rule identity must be positive" }
            val value = AutomaticKeywordNormalizer.canonicalPlaylistUrl(rule.conditionValue)
                ?: return@mapNotNull null
            val key = AutomaticKeywordNormalizer.playlistConditionKey(value)
                ?: return@mapNotNull null
            rule.copy(
                conditionType = AutomaticKeywordRuleTypes.PLAYLIST,
                conditionValue = value,
                conditionKey = key,
                revision = 1L,
                manualSyncStatus = normalizeSyncStatus(rule.manualSyncStatus),
                discoveryStatus = normalizeSyncStatus(rule.discoveryStatus),
            )
        }
        require(rules.orEmpty().map(AutomaticKeywordRule::id).distinct().size == rules.orEmpty().size) {
            "Duplicate automatic keyword rule backup identity"
        }
        val ruleIds = rules?.map { it.id }?.toSet().orEmpty()
        val ruleKeywords = input.automaticKeywordRuleKeywords?.mapNotNull { keyword ->
            require(keyword.ruleId > 0L) { "Automatic keyword rule keyword identity must be positive" }
            if (keyword.ruleId !in ruleIds) return@mapNotNull null
            val display = keyword.keyword.trim().replace(Regex("\\s+"), " ")
            val normalized = AutomaticKeywordNormalizer.normalizeKeyword(display)
            if (normalized.isBlank()) null else keyword.copy(
                normalizedKeyword = normalized,
                keyword = display,
            )
        }?.distinctBy { it.ruleId to it.normalizedKeyword }
        val matches = input.automaticKeywordRuleVideoMatches?.mapNotNull { match ->
            require(match.ruleId > 0L) { "Automatic keyword match identity must be positive" }
            if (match.ruleId !in ruleIds) return@mapNotNull null
            val key = AutomaticKeywordNormalizer.videoKey(match.videoUrl)
            if (key.isBlank()) null else match.copy(videoKey = key)
        }
        val rulesWithKeywords = ruleKeywords.orEmpty().map { it.ruleId }.toSet()
        val retainedRules = rules?.filter { it.id in rulesWithKeywords }
        val retainedRuleIds = retainedRules?.map { it.id }?.toSet().orEmpty()
        val retainedKeywords = ruleKeywords?.filter { it.ruleId in retainedRuleIds }
        val retainedMatches = matches?.filter { it.ruleId in retainedRuleIds }

        val assignments = input.historyKeywordAssignments?.mapNotNull { assignment ->
            require(assignment.historyItemId > 0L) { "History keyword assignment identity must be positive" }
            if (historyIds == null || assignment.historyItemId !in historyIds) {
                return@mapNotNull null
            }
            val display = assignment.keyword.trim().replace(Regex("\\s+"), " ")
            val normalized = AutomaticKeywordNormalizer.normalizeKeyword(display)
            if (normalized.isBlank()) return@mapNotNull null
            when (assignment.sourceType) {
                HistoryKeywordAssignmentSources.MANUAL -> assignment.copy(
                    normalizedKeyword = normalized,
                    keyword = display,
                    sourceId = HistoryKeywordAssignmentSources.MANUAL_SOURCE_ID,
                )
                HistoryKeywordAssignmentSources.RULE ->
                    assignment.takeIf { it.sourceId in retainedRuleIds }?.copy(
                        normalizedKeyword = normalized,
                        keyword = display,
                    )
                HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE ->
                    assignment.takeIf { it.sourceId in sourceIds }?.copy(
                        normalizedKeyword = normalized,
                        keyword = display,
                    )
                else -> throw IllegalArgumentException("Unsupported History keyword assignment source")
            }
        }?.distinctBy { listOf(it.historyItemId, it.normalizedKeyword, it.sourceType, it.sourceId) }

        val playlistIds = input.playlists?.map {
            require(it.id > 0L && it.name.isNotBlank()) { "Invalid playlist" }
            it.id
        }?.toSet().orEmpty()
        val playlistGroupIds = input.playlistGroups?.map {
            require(it.id > 0L && it.name.isNotBlank()) { "Invalid playlist group" }
            it.id
        }?.toSet().orEmpty()
        val crossRefs = input.playlistItemCrossRefs?.mapNotNull { relation ->
            require(relation.playlistId > 0L && relation.historyItemId > 0L) { "Invalid playlist item relation" }
            relation.takeIf {
                it.playlistId in playlistIds && historyIds != null && it.historyItemId in historyIds
            }
        }?.distinct()
        val playlistGroupMembers = input.playlistGroupMembers?.mapNotNull { member ->
            require(member.groupId > 0L && member.playlistId > 0L) { "Invalid playlist group relation" }
            member.takeIf { it.groupId in playlistGroupIds && it.playlistId in playlistIds }
        }?.distinct()
        val typedPlaylistGraphPresent = compatibility == BackupCompatibility.TYPED_PROGRAMMATIC &&
            listOf(
                input.playlists,
                input.playlistItemCrossRefs,
                input.playlistGroups,
                input.playlistGroupMembers,
            ).any { it != null }

        val downloadIdentityValues = listOfNotNull(
            input.queued,
            input.paused,
            input.scheduled,
            input.cancelled,
            input.errored,
            input.saved,
        ).flatten().map { it.id }.filter { it > 0L }
        require(downloadIdentityValues.distinct().size == downloadIdentityValues.size) {
            "Duplicate Download backup identity"
        }

        return RestoreAppDataItem(
            settings = settings,
            downloads = history,
            customThumbnails = thumbs,
            keywordGroups = keywordGroups,
            keywordGroupMembers = keywordMembers,
            youtuberGroups = youtuberGroups,
            youtuberGroupMembers = youtuberMembers,
            youtuberGroupRelations = youtuberRelations,
            historyVisibleChildYoutuberGroups = input.historyVisibleChildYoutuberGroups
                ?.filter { it in youtuberGroupIds }?.toSet(),
            historyVisibleChildYoutubers = input.historyVisibleChildYoutubers?.toSet(),
            historyVisibleChildKeywords = input.historyVisibleChildKeywords?.toSet(),
            youtuberMeta = input.youtuberMeta?.map { meta ->
                require(meta.author.isNotBlank()) { "Invalid youtuber metadata" }
                meta
            },
            queued = normalizeDownloads(input.queued),
            paused = normalizeDownloads(
                input.paused,
                forcePaused = true,
            ),
            scheduled = normalizeDownloads(input.scheduled),
            cancelled = normalizeDownloads(input.cancelled),
            errored = normalizeDownloads(input.errored),
            saved = normalizeDownloads(input.saved),
            cookies = input.cookies?.map { it.copy(id = 0L) },
            templates = input.templates?.map { it.copy(id = 0L) },
            shortcuts = input.shortcuts?.map { it.copy(id = 0L) },
            searchHistory = input.searchHistory?.map { it.copy(id = 0L) },
            observeSources = sources,
            automaticKeywordRules = retainedRules,
            automaticKeywordRuleKeywords = retainedKeywords,
            automaticKeywordRuleVideoMatches = retainedMatches,
            historyKeywordAssignments = assignments,
            playlists = input.playlists?.toList() ?: if (typedPlaylistGraphPresent) emptyList() else null,
            playlistItemCrossRefs = crossRefs ?: if (typedPlaylistGraphPresent) emptyList() else null,
            playlistGroups = input.playlistGroups?.toList() ?: if (typedPlaylistGraphPresent) emptyList() else null,
            playlistGroupMembers = playlistGroupMembers ?: if (typedPlaylistGraphPresent) emptyList() else null,
        )
    }

    private fun validateSettings(settings: List<BackupSettingsItem>) {
        settings.forEach { item ->
            require(item.key.isNotBlank()) { "Preference key must not be blank" }
            when (val type = item.type) {
                "String" -> Unit
                "Boolean" -> require(item.value == "true" || item.value == "false") {
                    "Invalid Boolean preference value for ${item.key}"
                }
                "Int" -> require(item.value.toIntOrNull() != null) {
                    "Invalid Int preference value for ${item.key}"
                }
                "Long" -> require(item.value.toLongOrNull() != null) {
                    "Invalid Long preference value for ${item.key}"
                }
                "Float" -> require(item.value.toFloatOrNull()?.isFinite() == true) {
                    "Invalid Float preference value for ${item.key}"
                }
                "StringSet", "Set", "HashSet", "LinkedHashSet", "ArraySet" -> {
                    val value = JsonParser.parseString(item.value)
                    require(value.isJsonArray && value.asJsonArray.all {
                        it.isJsonPrimitive && it.asJsonPrimitive.isString
                    }) { "Invalid StringSet preference value for ${item.key}" }
                }
                else -> throw IllegalArgumentException("Unsupported preference type for ${item.key}: $type")
            }
        }
    }

    private fun validateThumbnail(item: BackupCustomThumbItem) {
        require(item.historyId > 0L) { "Custom thumbnail History identity must be positive" }
        canonicalExtension(item.extension)
        require(item.base64.isNotBlank()) { "Custom thumbnail payload must not be blank" }
        try {
            require(Base64.decode(item.base64, Base64.DEFAULT).isNotEmpty()) {
                "Custom thumbnail payload must not be empty"
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("Malformed custom thumbnail Base64", error)
        }
    }

    private fun canonicalExtension(extension: String): String {
        val normalized = extension.lowercase(Locale.ROOT).ifBlank { "jpg" }
        require(normalized.matches(Regex("[a-z0-9]{1,10}"))) {
            "Invalid custom thumbnail extension"
        }
        return normalized
    }

    private fun normalizeSyncStatus(status: String): String =
        if (status == "QUEUED" || status == "RUNNING") "NEVER" else status

    private fun typedCapabilities(data: RestoreAppDataItem): Set<String> = buildSet {
        if (data.settings != null) add("settings")
        if (data.downloads != null) add("downloads")
        if (data.customThumbnails != null) add("custom_thumbnails")
        if (data.keywordGroups != null || data.keywordGroupMembers != null) add("keyword_data")
        if (
            data.youtuberGroups != null ||
            data.youtuberGroupMembers != null ||
            data.youtuberGroupRelations != null ||
            data.youtuberMeta != null
        ) add("youtuber_data")
        if (data.queued != null) add("queued")
        if (data.paused != null) add("paused")
        if (data.scheduled != null) add("scheduled")
        if (data.cancelled != null) add("cancelled")
        if (data.errored != null) add("errored")
        if (data.saved != null) add("saved")
        if (data.cookies != null) add("cookies")
        if (data.templates != null) add("templates")
        if (data.shortcuts != null) add("shortcuts")
        if (data.searchHistory != null) add("search_history")
        if (
            data.historyVisibleChildYoutuberGroups != null ||
            data.historyVisibleChildYoutubers != null ||
            data.historyVisibleChildKeywords != null
        ) add("history_visibility")
        if (data.observeSources != null) add("observe_sources")
        if (
            data.automaticKeywordRules != null ||
            data.automaticKeywordRuleKeywords != null ||
            data.automaticKeywordRuleVideoMatches != null
        ) add("automatic_keyword_rules")
        if (data.historyKeywordAssignments != null) add("history_keyword_assignments")
        if (
            data.playlists != null ||
            data.playlistItemCrossRefs != null ||
            data.playlistGroups != null ||
            data.playlistGroupMembers != null
        ) add("playlist_data")
    }

    private fun JsonObject.stringValue(key: String): String? = get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.intValueOrNull(key: String): Int? = get(key)?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "$key must be numeric" }
        it.asString.toIntOrNull() ?: throw IllegalArgumentException("$key must be an integer")
    }

    private fun JsonObject.stringSet(key: String): Set<String>? = get(key)?.let { element ->
        require(element.isJsonArray) { "$key must be an array" }
        element.asJsonArray.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "$key must contain strings" }
            it.asString
        }.toSet()
    }

    private fun JsonObject.longStringSet(key: String): Set<Long>? = stringSet(key)?.map {
        it.toLongOrNull() ?: throw IllegalArgumentException("$key contains a non-numeric identity")
    }?.toSet()

    /**
     * Historical unversioned/v3 source payloads predate the later runtime
     * bookkeeping fields. This is the exact normalization the former
     * production fragment applied before Gson materialization.
     */
    private fun JsonObject.observeSourcesArray(gson: Gson): List<ObserveSourcesItem>? =
        get("observe_sources")?.let { element ->
            require(element.isJsonArray) { "observe_sources must be an array" }
            element.asJsonArray.mapIndexed { index, value ->
                require(value.isJsonObject) { "observe_sources[$index] must be an object" }
                val sourceJson = value.asJsonObject.deepCopy()
                listOf("ignoredLinks", "runHistory", "retryPromptedLinks", "observedLinks").forEach { key ->
                    if (!sourceJson.has(key) || sourceJson.get(key).isJsonNull) {
                        sourceJson.add(key, com.google.gson.JsonArray())
                    }
                }
                listOf("currentRunStatus", "autoAddKeyword").forEach { key ->
                    if (!sourceJson.has(key) || sourceJson.get(key).isJsonNull) {
                        sourceJson.addProperty(key, "")
                    }
                }
                sourceJson.addProperty("observationPurpose", ObservationPurposes.USER)
                sourceJson.addProperty("managedConditionKey", "")
                try {
                    gson.fromJson(sourceJson, ObserveSourcesItem::class.java)
                } catch (error: JsonParseException) {
                    throw IllegalArgumentException("Malformed observe_sources[$index]", error)
                }
            }
        }
    private fun <T> JsonObject.objectArray(
        key: String,
        gson: Gson,
        type: Class<T>,
    ): List<T>? = get(key)?.let { element ->
        require(element.isJsonArray) { "$key must be an array" }
        element.asJsonArray.mapIndexed { index, value ->
            require(value.isJsonObject) { "$key[$index] must be an object" }
            try {
                gson.fromJson(value, type)
            } catch (error: JsonParseException) {
                throw IllegalArgumentException("Malformed $key[$index]", error)
            }
        }
    }
}
