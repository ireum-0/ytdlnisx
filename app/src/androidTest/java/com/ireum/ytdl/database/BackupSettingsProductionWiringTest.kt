package com.ireum.ytdl.database

import android.content.Context
import androidx.room.withTransaction
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.KeywordGroup
import com.ireum.ytdl.database.models.KeywordGroupMember
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.viewmodel.SettingsViewModel
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.BackupSettingsUtil
import com.ireum.ytdl.App
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.RestorePlan
import com.google.gson.JsonParser
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the selected-category capture and publication boundary. */
@RunWith(AndroidJUnit4::class)
class BackupSettingsProductionWiringTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var historyRepository: HistoryRepository
    private var publishedBackup: String? = null
    private var staleBackup: File? = null

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            database = DBManager.getInstance(context)
            historyRepository = HistoryRepository(database.historyDao, database.playlistDao)
            historyRepository.deleteAllRecords()
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .remove("cache_path")
                .remove("backup_path")
                .commit()
        }
    }

    @After
    fun tearDown() = runBlocking {
        SettingsViewModel.backupCaptureReadHookForTesting = null
        SettingsViewModel.backupStagingWriteHookForTesting = null
        publishedBackup?.let { BackupPublicationTestSupport.delete(context, it) }
        staleBackup?.delete()
        historyRepository.deleteAllRecords()
        database.keywordGroupDao.clearMembers()
        database.keywordGroupDao.clearGroups()
    }

    @Test
    fun emptySelectedDownloadsCaptureIsSuccessfulEmptyState() = runBlocking {
        val result = SettingsViewModel(context as android.app.Application)
            .backup(listOf("downloads"))

        assertTrue(result.isSuccess)
        publishedBackup = result.getOrNull()
    }

    @Test
    fun observeSourceBackupOmitsDestinationGenerationAndLegacyPayloadGetsLocalGeneration() = runBlocking {
        val source = observeSource().copy(configurationGeneration = 91L)
        val sourceId = database.observeSourcesDao.insert(source)
        try {
            val repository = com.ireum.ytdl.database.repository.ObserveSourcesRepository(
                database.observeSourcesDao,
                WorkManager.getInstance(context),
                PreferenceManager.getDefaultSharedPreferences(context),
                context,
            )
            val backup = BackupSettingsUtil.backupObserveSources(repository).getOrThrow()
            assertTrue(backup.size() > 0)
            backup.forEach { assertFalse(it.asJsonObject.has("configurationGeneration")) }

            val destinationSource = source.copy(id = sourceId)
            val legacySourceJson = JsonParser.parseString(Gson().toJson(destinationSource)).asJsonObject
            legacySourceJson.remove("configurationGeneration")
            val sources = JsonArray().apply { add(legacySourceJson) }
            val root = JsonObject().apply {
                addProperty("app", BackupRestoreParser.CURRENT_APP_MARKER)
                addProperty("backup_format_version", 4)
                add("observe_sources", sources)
            }
            val parsed = BackupRestoreParser.parse(root)
            assertEquals(1L, parsed.data.observeSources!!.single().configurationGeneration)

            val forgedSource = destinationSource.copy(configurationGeneration = Long.MAX_VALUE - 4)
            val typed = BackupRestoreParser.fromTyped(
                com.ireum.ytdl.database.models.RestoreAppDataItem(observeSources = listOf(forgedSource)),
            )
            assertEquals(1L, typed.data.observeSources!!.single().configurationGeneration)
        } finally {
            if (sourceId > 0L) database.observeSourcesDao.deleteRecord(sourceId)
        }
    }

    @Test
    fun publicationUsesOnlyTheCurrentBackupArtifact() = runBlocking {
        val stagingDir = File(FileUtil.getCachePath(context), "Backups")
            .apply { mkdirs() }
        staleBackup = File(stagingDir, "stale-backup-${System.nanoTime()}.json")
            .apply { writeText("{\"stale\":true}") }

        val result = SettingsViewModel(context as android.app.Application)
            .backup(listOf("downloads"))

        assertTrue(result.isSuccess)
        val published = result.getOrNull()
        assertTrue(published?.let { BackupPublicationTestSupport.exists(context, it) } == true)
        assertTrue(
            published?.let { BackupPublicationTestSupport.readText(context, it) }
                .orEmpty().contains("YTDLnisX_backup")
        )
        assertTrue(staleBackup?.exists() == true)
        assertFalse(
            published?.let { BackupPublicationTestSupport.readText(context, it) }
                .orEmpty().contains("stale")
        )
        publishedBackup = result.getOrNull()
    }

    @Test
    fun overlappingBackupsKeepOperationLocalStagingArtifacts() = runBlocking {
        val selectedPaths = Collections.synchronizedSet(mutableSetOf<String>())
        val bothSelected = CountDownLatch(2)
        val releaseWrites = CountDownLatch(1)
        SettingsViewModel.backupStagingWriteHookForTesting = { file ->
            selectedPaths += file.absolutePath
            bothSelected.countDown()
            check(bothSelected.await(5, TimeUnit.SECONDS)) {
                "overlapping backups did not both select a staging artifact"
            }
            check(releaseWrites.await(5, TimeUnit.SECONDS)) {
                "overlapping backup writes were not released"
            }
        }

        var downloadsPath: String? = null
        var keywordPath: String? = null
        try {
            val downloads = async(Dispatchers.IO) {
                SettingsViewModel(context as android.app.Application)
                    .backup(listOf("downloads"))
            }
            val keywords = async(Dispatchers.IO) {
                SettingsViewModel(context as android.app.Application)
                    .backup(listOf("keywordData"))
            }

            assertTrue(bothSelected.await(5, TimeUnit.SECONDS))
            assertEquals(2, selectedPaths.size)
            releaseWrites.countDown()

            val downloadsResult = downloads.await()
            val keywordResult = keywords.await()
            assertTrue(downloadsResult.isSuccess)
            assertTrue(keywordResult.isSuccess)
            downloadsPath = downloadsResult.getOrThrow()
            keywordPath = keywordResult.getOrThrow()
            assertTrue(downloadsPath != keywordPath)

            val downloadsJson = JsonParser.parseString(
                BackupPublicationTestSupport.readText(context, downloadsPath!!)
            ).asJsonObject
            val keywordJson = JsonParser.parseString(
                BackupPublicationTestSupport.readText(context, keywordPath!!)
            ).asJsonObject
            assertTrue(downloadsJson.has("downloads"))
            assertFalse(downloadsJson.has("keyword_groups"))
            assertTrue(keywordJson.has("keyword_groups"))
            assertFalse(keywordJson.has("downloads"))
        } finally {
            SettingsViewModel.backupStagingWriteHookForTesting = null
            releaseWrites.countDown()
            downloadsPath?.let { BackupPublicationTestSupport.delete(context, it) }
            keywordPath?.let { BackupPublicationTestSupport.delete(context, it) }
        }
    }

    @Test
    fun missingRequiredCustomThumbnailFailsBeforeArtifactPublication() = runBlocking {
        val missing = File(context.cacheDir, "missing-required-thumbnail-${System.nanoTime()}.jpg")
        database.historyDao.insertAndGetIdRaw(history(customThumb = missing.absolutePath))

        val result = SettingsViewModel(context as android.app.Application)
            .backup(listOf("downloads"))

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("thumbnail", ignoreCase = true))
        assertFalse(
            result.getOrNull()?.let { BackupPublicationTestSupport.exists(context, it) } == true
        )
    }

    @Test
    fun readableCustomThumbnailRemainsAValidPayload() {
        runBlocking {
            val thumbnail = File(context.cacheDir, "valid-custom-thumbnail-${System.nanoTime()}.jpg")
            thumbnail.writeBytes(byteArrayOf(1, 2, 3, 4))
            database.historyDao.insertAndGetIdRaw(history(customThumb = thumbnail.absolutePath))

            val result = SettingsViewModel(context as android.app.Application)
                .backup(listOf("downloads"))

            assertTrue(result.isSuccess)
            publishedBackup = result.getOrNull()
            thumbnail.delete()
        }
    }

    @Test
    fun relatedKeywordSnapshotRemainsCoherentDuringConcurrentWriter() = runBlocking {
        val application = context as android.app.Application
        val beforeGroupId = database.keywordGroupDao.insertGroup(KeywordGroup(name = "before"))
        database.keywordGroupDao.insertMembers(
            listOf(KeywordGroupMember(beforeGroupId, "before-member"))
        )

        val groupsRead = CountDownLatch(1)
        val writerRelease = CountDownLatch(1)
        val writerAttempted = CountDownLatch(1)
        SettingsViewModel.backupCaptureReadHookForTesting = { phase ->
            if (phase == "keyword_groups") {
                groupsRead.countDown()
                check(writerRelease.await(5, TimeUnit.SECONDS)) {
                    "concurrent writer was not released while backup transaction was held"
                }
                check(writerAttempted.await(5, TimeUnit.SECONDS)) {
                    "concurrent writer did not reach its transaction attempt"
                }
            }
        }

        try {
            val writer = async(Dispatchers.IO) {
                writerRelease.await()
                writerAttempted.countDown()
                database.withTransaction {
                    database.keywordGroupDao.clearMembers()
                    database.keywordGroupDao.clearGroups()
                    val afterGroupId = database.keywordGroupDao.insertGroup(
                        KeywordGroup(name = "after")
                    )
                    database.keywordGroupDao.insertMembers(
                        listOf(KeywordGroupMember(afterGroupId, "after-member"))
                    )
                }
            }
            val backup = async(Dispatchers.IO) {
                SettingsViewModel(application).backup(listOf("keywordData"))
            }
            assertTrue(groupsRead.await(5, TimeUnit.SECONDS))
            writerRelease.countDown()

            val result = backup.await()
            writer.await()
            assertTrue(result.isSuccess)
            val published = result.getOrNull()
            assertTrue(
                published?.let { BackupPublicationTestSupport.exists(context, it) } == true
            )
            publishedBackup = published

            val root = JsonParser.parseString(
                BackupPublicationTestSupport.readText(context, published!!)
            ).asJsonObject
            val groupNames = root["keyword_groups"].asJsonArray
                .map { it.asJsonObject["name"].asString }
                .toSet()
            val memberKeywords = root["keyword_group_members"].asJsonArray
                .map { it.asJsonObject["keyword"].asString }
                .toSet()

            // Room's transaction snapshot must represent one complete state,
            // never groups from one state with members from the other.
            assertTrue(groupNames == setOf("before") || groupNames == setOf("after"))
            val expectedMember = if (groupNames == setOf("before")) {
                "before-member"
            } else {
                "after-member"
            }
            assertEquals(setOf(expectedMember), memberKeywords)
        } finally {
            SettingsViewModel.backupCaptureReadHookForTesting = null
        }
    }

    @Test
    fun backupFailsWhenStagingDirectoryCannotBeUsed() = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val original = preferences.getString("cache_path", null)
        val isolatedRoot = File(
            context.getExternalFilesDir(null),
            "backup-write-failure-${System.nanoTime()}"
        )
        val stagingPath = File(isolatedRoot, "Backups")
        try {
            preferences.edit().putString("cache_path", isolatedRoot.absolutePath).commit()
            isolatedRoot.mkdirs()
            assertTrue(stagingPath.createNewFile())

            val result = SettingsViewModel(context as android.app.Application)
                .backup(listOf("downloads"))

            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull() != null)
            assertTrue(result.getOrNull().isNullOrBlank())
            assertTrue(stagingPath.isFile)
        } finally {
            if (original == null) {
                preferences.edit().remove("cache_path").commit()
            } else {
                preferences.edit().putString("cache_path", original).commit()
            }
            isolatedRoot.deleteRecursively()
        }
    }

    @Test
    fun backupFailsWhenCurrentStagingFileWriteFails() = runBlocking {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val original = preferences.getString("cache_path", null)
        val isolatedRoot = File(
            context.getExternalFilesDir(null),
            "backup-staging-write-failure-${System.nanoTime()}"
        )
        val stagingDir = File(isolatedRoot, "Backups")
        try {
            preferences.edit().putString("cache_path", isolatedRoot.absolutePath).commit()
            SettingsViewModel.backupStagingWriteHookForTesting = {
                throw IOException("forced backup staging write failure")
            }

            val result = SettingsViewModel(context as android.app.Application)
                .backup(listOf("downloads"))

            assertFalse(result.isSuccess)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("forced"))
            assertTrue(result.getOrNull().isNullOrBlank())
            assertTrue(stagingDir.listFiles().orEmpty().isNotEmpty())
        } finally {
            SettingsViewModel.backupStagingWriteHookForTesting = null
            if (original == null) {
                preferences.edit().remove("cache_path").commit()
            } else {
                preferences.edit().putString("cache_path", original).commit()
            }
            isolatedRoot.deleteRecursively()
        }
    }

    @Test
    fun malformedUpdaterPlansRejectMergeAndResetBeforeAnyMutationOrOwnership() = runBlocking {
        App.instance.startupYtdlpUpdater.stop()
        database.historyDao.insertAndGetIdRaw(history())
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val snapshot = preferences.all.toMap()
        val originalHistory = historyRepository.getAll()
        val store = RestoreOperationStore.root(context)
        val originalFiles = store.walkTopDown().filter { it.isFile }.map { it.relativeTo(store).path to it.readBytes().toList() }.toMap()
        val valid = BackupRestoreParser.fromTyped(RestoreAppDataItem(settings = listOf(BackupSettingsItem("ytdlp_source", "stable", "String"))))
        val malformed = listOf(
            BackupSettingsItem("ytdlp_source", "", "String"),
            BackupSettingsItem("ytdlp_source", " \t\n", "String"),
            BackupSettingsItem("ytdlp_source", "1", "Int"),
            BackupSettingsItem("ytdlp_source", "true", "Boolean"),
            BackupSettingsItem("auto_update_ytdlp", "false", "String"),
            BackupSettingsItem("ytdlp_source_label", "1", "Int"),
        )
        malformed.forEach { item ->
            val plan = RestorePlan(valid.appMarker, valid.formatVersion, valid.compatibility, valid.capabilities,
                RestoreAppDataItem(settings = listOf(item)))
            listOf(false, true).forEach { reset ->
                val outcome = SettingsViewModel(context as android.app.Application).restorePlan(plan, context, reset)
                assertTrue("$item / reset=$reset", outcome is RestoreOutcome.RejectedBeforeOwnership)
                assertEquals(snapshot, preferences.all)
                assertEquals(originalHistory, historyRepository.getAll())
                assertFalse(RestoreGate.isRestoreInProgress(context))
                assertEquals(originalFiles, store.walkTopDown().filter { it.isFile }
                    .map { it.relativeTo(store).path to it.readBytes().toList() }.toMap())
            }
        }
    }

    @Test
    fun oldestLegacyAndCurrentRepresentationsShareUpdaterKeyAdmission() {
        val malformed = listOf(
            BackupSettingsItem("ytdlp_source", "", "String"), BackupSettingsItem("ytdlp_source", " \t\n", "String"),
            BackupSettingsItem("ytdlp_source", "1", "Int"), BackupSettingsItem("ytdlp_source", "false", "Boolean"),
            BackupSettingsItem("auto_update_ytdlp", "1", "Int"), BackupSettingsItem("ytdlp_source_label", "1", "Int"),
        )
        listOf("YTDLnisx_backup" to null, "YTDLnisX_backup" to null, "YTDLnisX_backup" to 3, "YTDLnisX_backup" to 4).forEach { (marker, version) ->
            malformed.forEach { item ->
                val root = JsonObject().apply {
                    addProperty("app", marker)
                    version?.let { addProperty("backup_format_version", it) }
                    add("settings", Gson().toJsonTree(listOf(item)))
                }
                assertTrue(root.toString(), runCatching { BackupRestoreParser.parse(root) }.exceptionOrNull() is IllegalArgumentException)
            }
        }
    }

    @Test
    fun validUpdaterMergeValuesRemainExactlyConsumableAndAbsenceIsAccepted() = runBlocking {
        App.instance.startupYtdlpUpdater.stop()
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val original = preferences.all.toMap()
        val local = com.ireum.ytdl.util.UpdateUtil.destinationProvenancePreferences(context)
        val originalLocal = local.all.toMap()
        try {
            listOf<String?>(null, "stable", "nightly", "master", "  owner/custom branch  ").forEach { source ->
                listOf(false, true).forEach { automatic ->
                    val settings = mutableListOf(BackupSettingsItem("auto_update_ytdlp", automatic.toString(), "Boolean"),
                        BackupSettingsItem("ytdlp_source_label", "Display label", "String"), BackupSettingsItem("updater03_unrelated", "1", "Int"))
                    source?.let { settings += BackupSettingsItem("ytdlp_source", it, "String") }
                    val plan = BackupRestoreParser.fromTyped(RestoreAppDataItem(settings = settings))
                    assertEquals(settings, plan.data.settings)
                    assertTrue(SettingsViewModel(context as android.app.Application).restorePlan(plan, context) is RestoreOutcome.Completed)
                    source?.let { assertEquals(it, preferences.getString("ytdlp_source", null)) }
                    assertEquals(automatic, preferences.getBoolean("auto_update_ytdlp", !automatic))
                    assertEquals("Display label", preferences.getString("ytdlp_source_label", null))
                    assertEquals(1, preferences.getInt("updater03_unrelated", -1))
                }
            }
        } finally {
            val editor = preferences.edit()
            (preferences.all.keys + original.keys).filter { it.startsWith("ytdlp_") || it in setOf("auto_update_ytdlp", "updater03_unrelated") }.forEach { key ->
                editor.remove(key)
                putUpdaterTestValue(editor, key, original[key])
            }
            assertTrue(editor.commit())
            val localEditor = local.edit().clear()
            originalLocal.forEach { (key, value) -> putUpdaterTestValue(localEditor, key, value) }
            assertTrue(localEditor.commit())
        }
    }

    private fun putUpdaterTestValue(editor: android.content.SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> Unit
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> error("Unsupported test snapshot type")
        }
    }

    private fun history(customThumb: String = "") = HistoryItem(
        id = 0L,
        url = "https://example.com/video-${System.nanoTime()}",
        title = "Backup test",
        author = "Author",
        duration = "1:00",
        thumb = "",
        type = DownloadType.video,
        time = System.currentTimeMillis(),
        downloadPath = listOf("/tmp/backup-test-output"),
        website = "Test",
        format = Format(),
        downloadId = 0L,
        customThumb = customThumb,
    )

    private fun observeSource() = ObserveSourcesItem(
        id = 0L,
        name = "Backup source",
        url = "https://example.com/backup-source",
        downloadItemTemplate = DownloadItem(
            id = 0L,
            url = "https://example.com/backup-source",
            title = "",
            author = "",
            thumb = "",
            duration = "",
            type = DownloadType.video,
            format = Format(),
            container = "",
            downloadSections = "",
            allFormats = mutableListOf(),
            downloadPath = "",
            website = "",
            downloadSize = "",
            playlistTitle = "",
            audioPreferences = AudioPreferences(),
            videoPreferences = VideoPreferences(),
            extraCommands = "",
            customFileNameTemplate = "",
            SaveThumb = false,
            status = "Queued",
            downloadStartTime = 0L,
            logID = null,
        ),
        everyNr = 1,
        everyCategory = ObserveSourcesRepository.EveryCategory.DAY,
        everyTime = System.currentTimeMillis(),
        weeklyConfig = null,
        monthlyConfig = null,
        status = ObserveSourcesRepository.SourceStatus.ACTIVE,
        startsTime = System.currentTimeMillis(),
        endsDate = 0L,
        endsAfterCount = 0,
        runCount = 3,
        getOnlyNewUploads = false,
        retryMissingDownloads = false,
        ignoredLinks = mutableListOf(),
        alreadyProcessedLinks = mutableListOf(),
        syncWithSource = false,
    )
}
