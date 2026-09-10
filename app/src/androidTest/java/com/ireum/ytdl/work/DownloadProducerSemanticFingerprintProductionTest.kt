package com.ireum.ytdl.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpOutputPlan
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpProducerSemanticSnapshot
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadProducerSemanticFingerprintProductionTest {
    private lateinit var context: Context
    private lateinit var database: DBManager
    private lateinit var testRoot: File
    private lateinit var ytdlp: YTDLPUtil

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        testRoot = File(
            requireNotNull(context.getExternalFilesDir(null)),
            "p2b-fingerprint-${UUID.randomUUID()}",
        ).apply { mkdirs() }
        ytdlp = YTDLPUtil(context, database.commandTemplateDao)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        testRoot.deleteRecursively()
    }

    @Test
    fun generatedConfigSemanticsReachTheProductionFingerprintBoundary() {
        val source = "https://youtube.com/watch?v=dQw4w9WgXcQ"
        val first = fingerprintFor(
            download(
                url = source,
                format = Format(format_id = "137", container = "mp4"),
                allFormats = mutableListOf(Format(format_id = "137")),
            ),
        )
        val changed = fingerprintFor(
            download(
                url = source,
                format = Format(format_id = "136", container = "mp4"),
                allFormats = mutableListOf(Format(format_id = "136")),
            ),
        )

        assertNotEquals(first.fingerprint, changed.fingerprint)
        assertNotEquals(
            first.request.getArguments("--config-locations")?.singleOrNull(),
            changed.request.getArguments("--config-locations")?.singleOrNull(),
        )
    }

    @Test
    fun generatedConfigFilenameIsRuntimeOnlyForIdenticalEffectiveSemantics() {
        val item = download(
            url = "https://youtube.com/watch?v=dQw4w9WgXcQ",
            format = Format(format_id = "137", container = "mp4"),
            allFormats = mutableListOf(Format(format_id = "137")),
        )
        val first = fingerprintFor(item)
        val second = fingerprintFor(item.copy(id = item.id + 1L))

        assertEquals(first.fingerprint, second.fingerprint)
        assertNotEquals(
            first.request.getArguments("--config-locations")?.singleOrNull(),
            second.request.getArguments("--config-locations")?.singleOrNull(),
        )
    }

    @Test
    fun generatedSubtitleSemanticsReachTheProductionFingerprintBoundary() {
        val source = "https://youtube.com/watch?v=dQw4w9WgXcQ"
        val common = download(
            url = source,
            format = Format(format_id = "137", container = "mp4"),
            allFormats = mutableListOf(Format(format_id = "137")),
            videoPreferences = VideoPreferences(embedSubs = false, writeSubs = false),
        )
        val withSubtitles = common.copy(
            videoPreferences = common.videoPreferences.copy(writeSubs = true),
        )

        val without = fingerprintFor(common)
        val with = fingerprintFor(withSubtitles)

        assertNotEquals(without.fingerprint, with.fingerprint)
    }

    private fun fingerprintFor(item: DownloadItem): FingerprintedRequest {
        val plan = YtdlpOutputPlan(
            finalDestination = File(testRoot, "published").absolutePath,
            ytdlpDirectory = File(testRoot, "stage-${UUID.randomUUID()}"),
            directNoCache = false,
            explicitCommandPath = false,
        )
        val request = ytdlp.buildYoutubeDLRequest(
            downloadItem = item,
            mediaAccessProfile = ytdlp.resolveInitialYoutubeMediaAccessProfile(item),
            outputPlan = plan,
        )
        val snapshot = requireNotNull(YtdlpProducerSemanticSnapshot.forRequest(request))
        val command = ytdlp.parseYTDLRequestString(request)
        val fingerprint = DownloadProducerSemanticFingerprint.fingerprint(
            command = command,
            outputPlan = plan,
            effectiveProducerSemantics = snapshot.configContents,
            runtimePaths = snapshot.runtimePaths,
        )
        request.getArguments("--config-locations")
            ?.filterNotNull()
            ?.forEach { File(it).delete() }
        return FingerprintedRequest(request, fingerprint)
    }

    private data class FingerprintedRequest(
        val request: com.yausername.youtubedl_android.YoutubeDLRequest,
        val fingerprint: String,
    )

    private fun download(
        url: String,
        format: Format,
        allFormats: MutableList<Format>,
        videoPreferences: VideoPreferences = VideoPreferences(),
    ) = DownloadItem(
        id = 100_000L,
        url = url,
        title = "fingerprint",
        author = "author",
        thumb = "",
        duration = "01:00",
        type = DownloadType.video,
        format = format,
        container = "mp4",
        downloadSections = "",
        allFormats = allFormats,
        downloadPath = File(testRoot, "published").absolutePath,
        website = "youtube",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = videoPreferences,
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = DownloadRepository.Status.Queued.name,
        downloadStartTime = 0L,
        logID = null,
        playlistURL = "",
        operationId = "fingerprint-${UUID.randomUUID()}",
    )
}
