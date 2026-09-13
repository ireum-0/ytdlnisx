package com.ireum.ytdl.util

import android.content.SharedPreferences
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.repository.CommandTemplateRepository
import com.ireum.ytdl.database.repository.CookieRepository
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.repository.SearchHistoryRepository
import com.ireum.ytdl.database.dao.KeywordGroupDao
import com.ireum.ytdl.database.dao.YoutuberGroupDao
import com.ireum.ytdl.database.dao.YoutuberMetaDao
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

object BackupSettingsUtil {
    private val nonPortablePreferenceKeys = setOf(
        "app_language",
        // Re-emitted by the youtuber-data payload where old group IDs can be
        // explicitly remapped to destination group IDs.
        "history_visible_child_youtuber_groups",
    )
    private const val PLAYBACK_POSITION_CACHE_PREFIX = "player_playback_position_"

    internal fun isPortablePreferenceKey(key: String): Boolean =
        key !in nonPortablePreferenceKeys &&
            !key.startsWith(PLAYBACK_POSITION_CACHE_PREFIX)

    /**
     * A successful empty array is meaningful backup state.  Capture failures
     * therefore remain a typed [Result.failure] instead of being collapsed to
     * that same empty value.
     */
    private fun <T> capture(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun <T> captureSuspend(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    internal fun <T> toJsonArray(items: Iterable<T>): JsonArray {
        val gson = Gson()
        return JsonArray().also { array ->
            items.forEach { item ->
                array.add(JsonParser.parseString(gson.toJson(item)).asJsonObject)
            }
        }
    }

    fun backupSettings(preferences: SharedPreferences): Result<JsonArray> = capture {
        val items = preferences.all
            .filterKeys(::isPortablePreferenceKey)
            .map { (key, value) ->
                val nonNullValue = requireNotNull(value) { "Preference $key has no value" }
                val encoded = when (nonNullValue) {
                    is String -> BackupSettingsItem(key, nonNullValue, "String")
                    is Boolean -> BackupSettingsItem(key, nonNullValue.toString(), "Boolean")
                    is Int -> BackupSettingsItem(key, nonNullValue.toString(), "Int")
                    is Long -> BackupSettingsItem(key, nonNullValue.toString(), "Long")
                    is Float -> BackupSettingsItem(key, nonNullValue.toString(), "Float")
                    is Set<*> -> {
                        require(nonNullValue.all { it is String }) {
                            "Preference $key contains a non-string set member"
                        }
                        BackupSettingsItem(
                            key = key,
                            value = Gson().toJson(nonNullValue.toList()),
                            type = "StringSet",
                        )
                    }
                    else -> throw IllegalArgumentException(
                        "Unsupported preference type for $key: ${nonNullValue::class.java.name}",
                    )
                }
                encoded
            }
        toJsonArray(items)
    }

    suspend fun backupHistory(historyRepository: HistoryRepository): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { historyRepository.getAll() })
    }

    suspend fun backupQueuedDownloads(downloadRepository: DownloadRepository): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { downloadRepository.getQueuedDownloadsForBackup() })
    }

    suspend fun backupPausedDownloads(downloadRepository: DownloadRepository): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { downloadRepository.getPausedDownloadsForBackup() })
    }

    suspend fun backupScheduledDownloads(downloadRepository: DownloadRepository): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { downloadRepository.getScheduledDownloadsForBackup() })
    }

    suspend fun backupCancelledDownloads(downloadRepository: DownloadRepository): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { downloadRepository.getCancelledDownloadsForBackup() })
    }

    suspend fun backupErroredDownloads(downloadRepository: DownloadRepository): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { downloadRepository.getErroredDownloadsForBackup() })
    }

    suspend fun backupSavedDownloads(downloadRepository: DownloadRepository): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { downloadRepository.getSavedDownloadsForBackup() })
    }

    suspend fun backupCookies(cookieRepository: CookieRepository): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { cookieRepository.getAll() })
    }

    suspend fun backupCommandTemplates(commandTemplateRepository: CommandTemplateRepository): Result<JsonArray> = captureSuspend {
        val items = withContext(Dispatchers.IO) { commandTemplateRepository.getAll() }
        items.forEach { it.useAsExtraCommand = false }
        toJsonArray(items)
    }

    suspend fun backupShortcuts(commandTemplateRepository: CommandTemplateRepository): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { commandTemplateRepository.getAllShortCuts() })
    }

    suspend fun backupSearchHistory(searchHistoryRepository: SearchHistoryRepository): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { searchHistoryRepository.getAll() })
    }

    suspend fun backupObserveSources(observeSourcesRepository: ObserveSourcesRepository): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { observeSourcesRepository.getAll() })
    }

    suspend fun backupKeywordGroups(keywordGroupDao: KeywordGroupDao): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { keywordGroupDao.getGroups() })
    }

    suspend fun backupKeywordGroupMembers(keywordGroupDao: KeywordGroupDao): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { keywordGroupDao.getAllMembers() })
    }

    suspend fun backupYoutuberGroups(youtuberGroupDao: YoutuberGroupDao): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { youtuberGroupDao.getGroups() })
    }

    suspend fun backupYoutuberGroupMembers(youtuberGroupDao: YoutuberGroupDao): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { youtuberGroupDao.getAllMembers() })
    }

    suspend fun backupYoutuberGroupRelations(youtuberGroupDao: YoutuberGroupDao): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { youtuberGroupDao.getAllRelations() })
    }

    suspend fun backupYoutuberMeta(youtuberMetaDao: YoutuberMetaDao): Result<JsonArray> = captureSuspend {
        toJsonArray(withContext(Dispatchers.IO) { youtuberMetaDao.getAll() })
    }

}
