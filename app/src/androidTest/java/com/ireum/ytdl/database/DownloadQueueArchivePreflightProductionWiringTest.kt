package com.ireum.ytdl.database

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.storage.ConfiguredDownloadArchive
import com.ireum.ytdl.util.storage.ConfiguredDownloadArchiveProvider
import com.ireum.ytdl.util.storage.ConfiguredDownloadArchiveStore
import com.ireum.ytdl.util.storage.DownloadArchiveAuthority
import com.ireum.ytdl.util.storage.DownloadArchiveProviderFence
import com.ireum.ytdl.util.storage.DownloadArchiveUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Queue-side duplicate preflight for a configured download archive.  An
 * unreadable configured archive is unknown membership, so admission fails
 * closed instead of treating the archive as empty.
 */
@RunWith(AndroidJUnit4::class)
class DownloadQueueArchivePreflightProductionWiringTest {
    private lateinit var context: Application
    private lateinit var database: DBManager
    private lateinit var preferences: android.content.SharedPreferences
    private var hadArchivePath = false
    private var previousArchivePath: String? = null
    private var previousDuplicateMode: String? = null
    private var hadDuplicateMode = false

    private class ArchiveProviderFake(private val contents: String?) :
        ConfiguredDownloadArchiveProvider {
        var readFailure: Throwable? = null

        override fun readText(context: Context, treeUri: Uri): String? {
            readFailure?.let { throw it }
            return contents
        }

        override fun replaceText(context: Context, treeUri: Uri, text: String) = Unit
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        hadArchivePath = preferences.contains(ConfiguredDownloadArchiveStore.PREFERENCE_KEY)
        previousArchivePath = preferences.getString(ConfiguredDownloadArchiveStore.PREFERENCE_KEY, null)
        hadDuplicateMode = preferences.contains("prevent_duplicate_downloads")
        previousDuplicateMode = preferences.getString("prevent_duplicate_downloads", null)
        preferences.edit()
            .putString("prevent_duplicate_downloads", "download_archive")
            .putString(
                ConfiguredDownloadArchiveStore.PREFERENCE_KEY,
                "content://com.android.externalstorage.documents/tree/primary%3AYTDLnisx",
            )
            .commit()
        ConfiguredDownloadArchiveStore.providerForTesting = ArchiveProviderFake(null)
        DownloadArchiveProviderFence.clearAllForTesting(context)
    }

    @After
    fun tearDown() {
        ConfiguredDownloadArchiveStore.providerForTesting = null
        DownloadArchiveProviderFence.clearAllForTesting(context)
        val editor = preferences.edit()
        if (hadArchivePath) {
            editor.putString(ConfiguredDownloadArchiveStore.PREFERENCE_KEY, previousArchivePath)
        } else {
            editor.remove(ConfiguredDownloadArchiveStore.PREFERENCE_KEY)
        }
        if (hadDuplicateMode) editor.putString("prevent_duplicate_downloads", previousDuplicateMode)
        else editor.remove("prevent_duplicate_downloads")
        editor.commit()
        database.close()
    }

    @Test
    fun providerBackedArchiveMembershipMarksQueueDuplicate() = runBlocking {
        val provider = ArchiveProviderFake("youtube $MEMBER_ID\n")
        ConfiguredDownloadArchiveStore.providerForTesting = provider
        val itemId = insertProcessingItem("https://www.youtube.com/watch?v=$MEMBER_ID")

        val duplicates = viewModel().checkProcessingDuplicates()

        // Provider membership is honoured: the item is a real duplicate.
        assertEquals(1, duplicates.size)
        assertEquals(itemId, duplicates.single().downloadItemID)
        val persisted = requireNotNull(database.downloadDao.getNullableDownloadById(itemId))
        assertEquals(DownloadRepository.Status.Duplicate.name, persisted.status)
    }

    @Test
    fun unavailableProviderArchiveFailsClosedInsteadOfAdmittingAsEmpty() = runBlocking {
        val provider = ArchiveProviderFake(null).apply {
            readFailure = DownloadArchiveUnavailableException("permission revoked")
        }
        ConfiguredDownloadArchiveStore.providerForTesting = provider
        val itemId = insertProcessingItem("https://www.youtube.com/watch?v=$NON_MEMBER_ID")

        val duplicates = viewModel().checkProcessingDuplicates()

        // Unknown membership is never reported as a duplicate and never
        // silently admitted: the item is withheld as a recoverable error.
        assertTrue(duplicates.isEmpty())
        val persisted = requireNotNull(database.downloadDao.getNullableDownloadById(itemId))
        assertEquals(DownloadRepository.Status.Error.name, persisted.status)
        assertEquals(
            DownloadIssueCode.ARCHIVE_UNAVAILABLE.name,
            persisted.lastIssueCode,
        )
    }

    @Test
    fun unresolvedProviderPromotionFenceWithholdsQueueAdmission() = runBlocking {
        // A provider promotion is unresolved even though the provider document
        // itself is still readable.
        val provider = ArchiveProviderFake("youtube $MEMBER_ID\n")
        ConfiguredDownloadArchiveStore.providerForTesting = provider
        val authority = ConfiguredDownloadArchiveStore.resolve(context)
        assertTrue(authority is ConfiguredDownloadArchive.SafTree)
        DownloadArchiveProviderFence.install(
            context = context,
            generationKey = DownloadArchiveAuthority.stableKey(90L, "exec-fence"),
            authority = authority,
            downloadId = 90L,
            executionId = "exec-fence",
        )
        try {
            val itemId = insertProcessingItem("https://www.youtube.com/watch?v=$MEMBER_ID")

            val duplicates = viewModel().checkProcessingDuplicates()

            // A fenced archive is unknown membership, so nothing is admitted
            // and nothing is reported as a duplicate.
            assertTrue(duplicates.isEmpty())
            val persisted = requireNotNull(database.downloadDao.getNullableDownloadById(itemId))
            assertEquals(DownloadRepository.Status.Error.name, persisted.status)
            assertEquals(
                DownloadIssueCode.ARCHIVE_UNAVAILABLE.name,
                persisted.lastIssueCode,
            )
        } finally {
            DownloadArchiveProviderFence.clearForGeneration(
                context,
                DownloadArchiveAuthority.stableKey(90L, "exec-fence"),
            )
        }
    }

    private fun viewModel(): DownloadViewModel = DownloadViewModel(context, database, true)

    private suspend fun insertProcessingItem(url: String): Long {
        val id = database.downloadDao.insert(
            DownloadItem(
                id = 0L,
                url = url,
                title = "queue archive preflight",
                author = "",
                thumb = "",
                duration = "",
                type = DownloadType.video,
                format = Format(container = "mp4"),
                container = "mp4",
                downloadSections = "",
                allFormats = mutableListOf(),
                downloadPath = context.filesDir.absolutePath,
                website = url,
                downloadSize = "",
                playlistTitle = "",
                audioPreferences = AudioPreferences(),
                videoPreferences = VideoPreferences(),
                extraCommands = "",
                customFileNameTemplate = "",
                SaveThumb = false,
                status = DownloadRepository.Status.Processing.name,
                downloadStartTime = 0L,
                logID = null,
            ),
        )
        return id
    }

    private companion object {
        const val MEMBER_ID = "dQw4w9WgXcQ"
        const val NON_MEMBER_ID = "oHg5SJYRHA0"
    }
}
