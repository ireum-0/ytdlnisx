package com.ireum.ytdl.util.storage

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production-wiring coverage for configured download-archive storage
 * authority.  A persisted `content://` tree keeps provider authority; the
 * archive is read, merged and written through a narrow provider seam instead
 * of a reconstructed filesystem pathname.
 */
@RunWith(AndroidJUnit4::class)
class ConfiguredDownloadArchiveStoreProductionWiringTest {
    private lateinit var context: Application
    private lateinit var database: com.ireum.ytdl.database.DBManager
    private lateinit var preferences: android.content.SharedPreferences
    private var hadArchivePath = false
    private var previousArchivePath: String? = null
    private var previousDuplicateMode: String? = null
    private var hadDuplicateMode = false

    /** Deterministic in-memory stand-in for a persisted SAF tree grant. */
    private class FakeProvider : ConfiguredDownloadArchiveProvider {
        var contents: String? = null
        var readCount = 0
        var writeCount = 0
        var readFailure: Throwable? = null
        var writeFailure: Throwable? = null

        /** Models a provider that mutates partially and then fails. */
        var partialWriteThenFailure: String? = null

        override fun readText(context: Context, treeUri: Uri): String? {
            readCount++
            readFailure?.let { throw it }
            return contents
        }

        override fun replaceText(context: Context, treeUri: Uri, text: String) {
            writeCount++
            partialWriteThenFailure?.let { partial ->
                // The provider truncated to a readable partial state and only
                // then reported failure.
                contents = partial
                partialWriteThenFailure = null
                throw DownloadArchiveUnavailableException("provider write failed after partial mutation")
            }
            writeFailure?.let { throw it }
            contents = text
        }
    }

    private val provider = FakeProvider()
    private val treeUri = Uri.parse(
        "content://com.android.externalstorage.documents/tree/primary%3AYTDLnisx",
    )

    private companion object {
        const val MEMBER_ID = "dQw4w9WgXcQ"
        const val NON_MEMBER_ID = "oHg5SJYRHA0"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = androidx.room.Room
            .inMemoryDatabaseBuilder(context, com.ireum.ytdl.database.DBManager::class.java)
            .addTypeConverter(com.ireum.ytdl.database.Converters())
            .allowMainThreadQueries()
            .build()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        hadArchivePath = preferences.contains(ConfiguredDownloadArchiveStore.PREFERENCE_KEY)
        previousArchivePath = preferences.getString(ConfiguredDownloadArchiveStore.PREFERENCE_KEY, null)
        hadDuplicateMode = preferences.contains("prevent_duplicate_downloads")
        previousDuplicateMode = preferences.getString("prevent_duplicate_downloads", null)
        provider.contents = null
        provider.readCount = 0
        provider.writeCount = 0
        provider.readFailure = null
        provider.writeFailure = null
        provider.partialWriteThenFailure = null
        DownloadArchiveProviderFence.clearAllForTesting(context)
        ConfiguredDownloadArchiveStore.providerForTesting = provider
        preferences.edit()
            .putString("prevent_duplicate_downloads", "download_archive")
            .commit()
    }

    @After
    fun tearDown() {
        ConfiguredDownloadArchiveStore.providerForTesting = null
        DownloadArchiveProviderFence.clearAllForTesting(context)
        if (::database.isInitialized) database.close()
        val editor = preferences.edit()
        if (hadArchivePath) {
            editor.putString(ConfiguredDownloadArchiveStore.PREFERENCE_KEY, previousArchivePath)
        } else {
            editor.remove(ConfiguredDownloadArchiveStore.PREFERENCE_KEY)
        }
        if (hadDuplicateMode) editor.putString("prevent_duplicate_downloads", previousDuplicateMode)
        else editor.remove("prevent_duplicate_downloads")
        editor.commit()
    }

    private fun useSafTree() {
        preferences.edit()
            .putString(ConfiguredDownloadArchiveStore.PREFERENCE_KEY, treeUri.toString())
            .commit()
    }

    /** A. The existing app-owned default keeps ordinary File behavior. */
    @Test
    fun defaultRawArchiveReadsSeedsAndPromotesIdempotently() {
        preferences.edit().remove(ConfiguredDownloadArchiveStore.PREFERENCE_KEY).commit()

        val authority = ConfiguredDownloadArchiveStore.resolve(context)
        val rawFile = assertRawFile(authority)
        rawFile.writeText("youtube EXISTING\n")

        val read = ConfiguredDownloadArchiveStore.read(context, authority)
        assertTrue(read is ConfiguredDownloadArchiveRead.Available)
        assertEquals(listOf("youtube EXISTING"), (read as ConfiguredDownloadArchiveRead.Available).lines)

        val generation = DownloadArchiveAuthority.prepare(context, 41L, "exec-raw")
        try {
            // Seeded from the configured raw archive, so the native duplicate
            // authority is not silently emptied.
            assertEquals(
                listOf("youtube EXISTING"),
                DownloadArchiveAuthority.readLines(generation.privateArchive),
            )
            generation.privateArchive.appendText("youtube ADDED\n")

            assertTrue(DownloadArchiveAuthority.promote(context, generation))
            assertEquals(
                listOf("youtube EXISTING", "youtube ADDED"),
                DownloadArchiveAuthority.readLines(rawFile),
            )
            assertFalse(generation.privateArchive.exists())

            // Repeated promotion adds nothing and still reports success.
            assertTrue(DownloadArchiveAuthority.promote(context, generation))
            assertEquals(
                listOf("youtube EXISTING", "youtube ADDED"),
                DownloadArchiveAuthority.readLines(rawFile),
            )
        } finally {
            generation.privateArchive.delete()
            rawFile.delete()
        }
    }

    /** B. A persisted tree URI resolves and reads through the provider. */
    @Test
    fun providerBackedArchiveExposesMembershipAndIsNeverAFile() {
        useSafTree()
        provider.contents = "youtube $MEMBER_ID\n"

        val authority = ConfiguredDownloadArchiveStore.resolve(context)
        assertTrue(authority is ConfiguredDownloadArchive.SafTree)
        assertEquals(treeUri, (authority as ConfiguredDownloadArchive.SafTree).treeUri)
        // Provider authority is never re-inferred as direct File authority.
        assertNull(authority.rawFileOrNull)

        val read = ConfiguredDownloadArchiveStore.read(context, authority)
        assertTrue(read is ConfiguredDownloadArchiveRead.Available)
        assertEquals(
            listOf("youtube $MEMBER_ID"),
            (read as ConfiguredDownloadArchiveRead.Available).lines,
        )
        // Both duplicate preflights consume this one read result.
        assertTrue(
            DownloadArchiveIdentity.matchesSource(
                "https://www.youtube.com/watch?v=$MEMBER_ID",
                read.lines,
            ),
        )
        assertFalse(
            DownloadArchiveIdentity.matchesSource(
                "https://www.youtube.com/watch?v=$NON_MEMBER_ID",
                read.lines,
            ),
        )
    }

    /**
     * C. A revoked/inaccessible provider is unavailable, never an empty
     * archive, and it has no native path.
     */
    @Test
    fun unavailableProviderIsNotAnEmptyArchiveAndHasNoNativePath() {
        useSafTree()
        provider.readFailure = DownloadArchiveUnavailableException("permission revoked")

        val authority = ConfiguredDownloadArchiveStore.resolve(context)
        val read = ConfiguredDownloadArchiveStore.read(context, authority)
        assertTrue(read is ConfiguredDownloadArchiveRead.Unavailable)
        assertNull(read.linesOrNull)
        assertFalse(read.isAvailable)
        assertNull(ConfiguredDownloadArchiveStore.nativeArchivePathOrNull(context))
    }

    /** C. A malformed persisted value is unavailable, not silently raw. */
    @Test
    fun malformedConfiguredLocationIsUnavailable() {
        preferences.edit()
            .putString(ConfiguredDownloadArchiveStore.PREFERENCE_KEY, "not-a-location")
            .commit()

        val authority = ConfiguredDownloadArchiveStore.resolve(context)
        assertTrue(authority is ConfiguredDownloadArchive.Unresolved)
        val read = ConfiguredDownloadArchiveStore.read(context, authority)
        assertTrue(read is ConfiguredDownloadArchiveRead.Unavailable)
        assertNull(ConfiguredDownloadArchiveStore.nativeArchivePathOrNull(context))
    }

    /**
     * D. A provider-backed configured archive with no trusted private archive
     * never yields a native `--download-archive` pathname.
     */
    @Test
    fun providerArchiveNeverProducesNativeArchivePathWithoutTrustedGeneration() {
        useSafTree()
        assertNull(ConfiguredDownloadArchiveStore.nativeArchivePathOrNull(context))

        val producedConfig = producedConfig(downloadArchivePath = null)
        assertFalse(
            "provider-backed archive must not be named to yt-dlp: $producedConfig",
            Regex("--download-archive[= ]").containsMatchIn(producedConfig),
        )
    }

    /** D. The app-owned generation-private archive is the exact native path. */
    @Test
    fun trustedGenerationPrivateArchiveIsTheExactNativeArchivePath() {
        useSafTree()
        val privateArchive = File(context.filesDir, "trusted-generation-archive.txt")
        try {
            val values = archiveOptionValues(producedConfig(privateArchive.absolutePath))
            assertTrue(values.isNotEmpty())
            assertEquals(privateArchive.absolutePath, values.last())
        } finally {
            privateArchive.delete()
        }
    }

    /**
     * D. Authored extra commands cannot displace the trusted archive.  The
     * trusted option is re-asserted after authored arguments, so it remains
     * the final authority yt-dlp uses.
     */
    @Test
    fun trustedArchiveSurvivesAuthoredExtraCommands() {
        useSafTree()
        val privateArchive = File(context.filesDir, "trusted-generation-archive.txt")
        try {
            val archiveOptions = archiveOptionValues(
                producedConfig(
                    downloadArchivePath = privateArchive.absolutePath,
                    extraCommands = "--sleep-subtitles 5",
                ),
            )
            assertTrue(archiveOptions.isNotEmpty())
            assertEquals(privateArchive.absolutePath, archiveOptions.last())
        } finally {
            privateArchive.delete()
        }
    }

    /**
     * E. Provider promotion seeds from provider contents, preserves existing
     * lines, adds only the missing delta, and is idempotent.
     */
    @Test
    fun providerPromotionMergesDeltaIdempotentlyAndVerifiesBeforeRetiring() {
        useSafTree()
        provider.contents = "youtube EXISTING\n"

        val generation = DownloadArchiveAuthority.prepare(context, 42L, "exec-saf")
        try {
            assertEquals(
                listOf("youtube EXISTING"),
                DownloadArchiveAuthority.readLines(generation.privateArchive),
            )
            generation.privateArchive.appendText("youtube DELTA\n")

            val readsBeforePromotion = provider.readCount
            assertTrue(DownloadArchiveAuthority.promote(context, generation))
            // Promotion proves the result by re-reading the same authority.
            assertTrue(provider.readCount > readsBeforePromotion)
            assertEquals(
                listOf("youtube EXISTING", "youtube DELTA"),
                ConfiguredDownloadArchiveStore.parseLines(provider.contents.orEmpty()),
            )
            // Verified before the private generation is retired.
            assertFalse(generation.privateArchive.exists())

            val writesAfterFirstPromotion = provider.writeCount
            assertTrue(DownloadArchiveAuthority.promote(context, generation))
            // Idempotent: nothing new to merge, so no extra provider write.
            assertEquals(writesAfterFirstPromotion, provider.writeCount)
            assertEquals(
                listOf("youtube EXISTING", "youtube DELTA"),
                ConfiguredDownloadArchiveStore.parseLines(provider.contents.orEmpty()),
            )
        } finally {
            generation.privateArchive.delete()
        }
    }

    /**
     * F. A provider write failure reports failure, keeps the private
     * generation as recoverable evidence, and does not claim success.
     */
    @Test
    fun providerPromotionFailureRetainsPrivateGenerationAndReportsFailure() {
        useSafTree()
        provider.contents = "youtube EXISTING\n"

        val generation = DownloadArchiveAuthority.prepare(context, 43L, "exec-saf-failure")
        try {
            generation.privateArchive.appendText("youtube DELTA\n")
            provider.writeFailure = DownloadArchiveUnavailableException("provider write denied")

            val reported = runCatching { DownloadArchiveAuthority.promote(context, generation) }
            assertTrue(
                "provider promotion must not report success",
                reported.getOrNull() != true,
            )
            // Generation evidence stays recoverable for the existing
            // finalization-pending retry path.
            assertTrue(generation.privateArchive.exists())
            assertTrue(
                DownloadArchiveAuthority.readLines(generation.privateArchive)
                    .contains("youtube DELTA"),
            )
            // The configured archive was not advanced by the failed write.
            assertEquals(
                listOf("youtube EXISTING"),
                ConfiguredDownloadArchiveStore.parseLines(provider.contents.orEmpty()),
            )
        } finally {
            generation.privateArchive.delete()
        }
    }

    /** F. Seeding is refused when the configured archive cannot be read. */
    @Test
    fun prepareRefusesToSeedFromAnUnreadableConfiguredArchive() {
        useSafTree()
        provider.readFailure = DownloadArchiveUnavailableException("permission revoked")

        val failure = runCatching { DownloadArchiveAuthority.prepare(context, 44L, "exec-unreadable") }
        assertTrue(failure.isFailure)
        // No private generation may exist for an unreadable configured archive.
        val expected = File(
            File(context.filesDir, "download-archive-generations"),
            "${DownloadArchiveAuthority.stableKey(44L, "exec-unreadable")}.txt",
        )
        assertFalse(expected.exists())
    }

    /**
     * A provider that truncates to readable partial contents and then fails
     * must not be trusted by ordinary admission, must survive a simulated
     * process restart, and must converge through recovery.
     */
    @Test
    fun partialProviderWriteKeepsAdmissionClosedUntilRecoveryConverges() {
        useSafTree()
        provider.contents = "youtube A\nyoutube B\n"
        val authority = ConfiguredDownloadArchiveStore.resolve(context)
        assertTrue(authority is ConfiguredDownloadArchive.SafTree)

        val generation = DownloadArchiveAuthority.prepare(context, 51L, "exec-partial")
        try {
            generation.privateArchive.writeText("youtube A\nyoutube B\nyoutube C\n")
            // The provider truncates to a readable partial state and throws.
            provider.partialWriteThenFailure = "youtube A\n"

            val reported = runCatching { DownloadArchiveAuthority.promote(context, generation) }
            assertTrue(
                "partial provider write must not report success",
                reported.getOrNull() != true,
            )
            // The provider is readable again, but only holds partial contents.
            assertEquals("youtube A\n", provider.contents)
            // Generation evidence and the durable fence both survive.
            assertTrue(generation.privateArchive.exists())
            assertTrue(DownloadArchiveProviderFence.isUnresolved(context, authority))
            assertNotNull(DownloadArchiveProviderFence.read(context, authority))

            // Ordinary admission reads fail closed while the fence stands,
            // even though the provider document itself is readable.
            val fenced = ConfiguredDownloadArchiveStore.read(context, authority)
            assertTrue(fenced is ConfiguredDownloadArchiveRead.Unavailable)
            assertNull(fenced.linesOrNull)

            // A fresh store object over the same app-private files (simulated
            // process restart) still observes the fence.
            val afterRestart = ConfiguredDownloadArchiveStore.read(context, authority)
            assertTrue(afterRestart is ConfiguredDownloadArchiveRead.Unavailable)
            assertTrue(DownloadArchiveProviderFence.isUnresolved(context, authority))

            // Recovery converges the provider from preserved generation evidence.
            assertTrue(DownloadArchiveAuthority.promote(context, generation))
            assertEquals(
                listOf("youtube A", "youtube B", "youtube C"),
                ConfiguredDownloadArchiveStore.parseLines(provider.contents.orEmpty()),
            )
            // Fence and private generation retire only after verification.
            assertFalse(DownloadArchiveProviderFence.isUnresolved(context, authority))
            assertFalse(generation.privateArchive.exists())

            // Admission trusts the verified provider again.
            val recovered = ConfiguredDownloadArchiveStore.read(context, authority)
            assertTrue(recovered is ConfiguredDownloadArchiveRead.Available)
            assertEquals(
                listOf("youtube A", "youtube B", "youtube C"),
                (recovered as ConfiguredDownloadArchiveRead.Available).lines,
            )
        } finally {
            generation.privateArchive.delete()
        }
    }

    /** A fence left by a crash before any provider mutation still fails closed. */
    @Test
    fun fenceInstalledBeforeProviderMutationFailsClosedUntilConverged() {
        useSafTree()
        provider.contents = "youtube A\n"
        val authority = ConfiguredDownloadArchiveStore.resolve(context)
        assertTrue(authority is ConfiguredDownloadArchive.SafTree)

        DownloadArchiveProviderFence.install(
            context = context,
            authority = authority,
            downloadId = 52L,
            executionId = "exec-crash-before-write",
            generationKey = "fence-key",
        )
        try {
            val fenced = ConfiguredDownloadArchiveStore.read(context, authority)
            assertTrue(fenced is ConfiguredDownloadArchiveRead.Unavailable)

            // Seeding a new generation from a fenced authority is refused, so
            // no producer can start against an unresolved provider.
            val prepareFailure = runCatching {
                DownloadArchiveAuthority.prepare(context, 52L, "exec-crash-before-write")
            }
            assertTrue(prepareFailure.isFailure)
            val expectedPrivate = File(
                File(context.filesDir, "download-archive-generations"),
                "${DownloadArchiveAuthority.stableKey(52L, "exec-crash-before-write")}.txt",
            )
            assertFalse(expectedPrivate.exists())
        } finally {
            DownloadArchiveProviderFence.clear(context, authority)
        }
    }

    /**
     * Raw app-owned archives keep their atomic replacement boundary and are
     * never fenced.
     */
    @Test
    fun rawAuthorityIsNeverFenced() {
        preferences.edit().remove(ConfiguredDownloadArchiveStore.PREFERENCE_KEY).commit()
        val authority = ConfiguredDownloadArchiveStore.resolve(context)
        val rawFile = assertRawFile(authority)

        DownloadArchiveProviderFence.install(
            context = context,
            authority = authority,
            downloadId = 53L,
            executionId = "exec-raw",
            generationKey = "raw-key",
        )
        try {
            assertFalse(DownloadArchiveProviderFence.isUnresolved(context, authority))
            rawFile.writeText("youtube RAW\n")
            val read = ConfiguredDownloadArchiveStore.read(context, authority)
            assertTrue(read is ConfiguredDownloadArchiveRead.Available)
        } finally {
            rawFile.delete()
        }
    }

    /**
     * A legacy persisted raw value keeps its historical folder meaning, so a
     * folder is never reinterpreted as the archive file itself.
     */
    @Test
    fun legacyAbsolutePersistedValueRemainsAFolder() {
        val folder = File(context.filesDir, "legacy-raw-folder").apply { mkdirs() }
        preferences.edit()
            .putString(ConfiguredDownloadArchiveStore.PREFERENCE_KEY, folder.absolutePath)
            .commit()
        try {
            val authority = ConfiguredDownloadArchiveStore.resolve(context)
            val rawFile = assertRawFile(authority)
            assertEquals(folder.absolutePath, rawFile.parentFile?.absolutePath)
            assertEquals(ConfiguredDownloadArchiveStore.ARCHIVE_FILE_NAME, rawFile.name)
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun assertRawFile(authority: ConfiguredDownloadArchive): File {
        assertTrue(authority is ConfiguredDownloadArchive.RawFile)
        val file = (authority as ConfiguredDownloadArchive.RawFile).file
        assertNotNull(file)
        assertEquals(ConfiguredDownloadArchiveStore.ARCHIVE_FILE_NAME, file.name)
        return file
    }

    /**
     * Builds a real producer request and returns the effective yt-dlp
     * configuration the app would execute.  The archive option is part of
     * that generated configuration rather than the returned request object,
     * so the assertion observes exactly what native yt-dlp receives.
     */
    private fun producedConfig(
        downloadArchivePath: String?,
        extraCommands: String = "",
    ): String {
        val cacheRoot = File(FileUtil.getCachePath(context))
        val before = cacheRoot.listFiles()?.map { it.name }?.toSet().orEmpty()
        val ytdlpUtil = YTDLPUtil(context, database.commandTemplateDao)
        val item = downloadItem(extraCommands)
        ytdlpUtil.buildYoutubeDLRequest(
            item,
            ytdlpUtil.resolveInitialYoutubeMediaAccessProfile(item),
            downloadArchivePath = downloadArchivePath,
        )
        val produced = cacheRoot.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name !in before }
            .sortedBy { it.lastModified() }
        return produced.lastOrNull()?.readText().orEmpty()
    }

    private fun archiveOptionValues(config: String): List<String> =
        Regex("--download-archive[= ](\\S+)").findAll(config)
            .map { it.groupValues[1] }
            .toList()

    private fun downloadItem(extraCommands: String): DownloadItem = DownloadItem(
        id = 0L,
        url = "https://www.youtube.com/watch?v=ARCBOUND",
        title = "archive boundary",
        author = "author",
        thumb = "",
        duration = "",
        type = DownloadType.video,
        format = Format(),
        container = "mp4",
        downloadSections = "All",
        allFormats = mutableListOf(),
        downloadPath = File(context.filesDir, "archive-boundary").absolutePath,
        website = "https://www.youtube.com/watch?v=ARCBOUND",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = com.ireum.ytdl.database.models.AudioPreferences(),
        videoPreferences = com.ireum.ytdl.database.models.VideoPreferences(),
        extraCommands = extraCommands,
        customFileNameTemplate = "",
        SaveThumb = false,
        status = com.ireum.ytdl.database.repository.DownloadRepository.Status.Queued.name,
        downloadStartTime = 0L,
        logID = null,
    )
}
