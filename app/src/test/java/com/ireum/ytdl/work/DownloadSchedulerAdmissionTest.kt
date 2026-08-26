package com.ireum.ytdl.work

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.util.HistoryRedownloadMarker
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSchedulerAdmissionTest {
    @Test
    fun onlyLiveActiveRowsConsumeTheExistingHardSubAdmissionGate() {
        val active = hardSubItem(1L, DownloadRepository.Status.Active.name, "active-E1")
        val postProcessing = hardSubItem(
            2L,
            DownloadRepository.Status.PostProcessing.name,
            "post-E1",
        )
        DownloadWorkerExecutionOwners.claim(active.id, active.executionId)
        DownloadWorkerExecutionOwners.claim(postProcessing.id, postProcessing.executionId)

        try {
            val ownership = classifyDownloadSchedulerOwnership(listOf(active, postProcessing))

            assertEquals(setOf(active.id, postProcessing.id), ownership.liveExecutionIds)
            assertEquals(setOf(active.id), ownership.liveCapacityIds)
            // The scheduler historically used Active rows for hard-sub
            // exclusivity; PostProcessing has already left that gate.
            assertEquals(setOf(active.id), ownership.liveHardSubIds)
            assertEquals(emptySet<Long>(), ownership.recoveryOwnedIds)
        } finally {
            DownloadWorkerExecutionOwners.release(active.id, active.executionId)
            DownloadWorkerExecutionOwners.release(postProcessing.id, postProcessing.executionId)
        }
    }

    private fun hardSubItem(
        id: Long,
        status: String,
        executionId: String,
    ) = DownloadItem(
        id = id,
        url = "https://example.com/$id",
        title = "title",
        author = "author",
        thumb = "",
        duration = "1:00",
        type = DownloadType.video,
        format = Format(format_id = "1080p"),
        container = "mp4",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = "/downloads",
        website = "example.com",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(embedSubs = true),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = status,
        downloadStartTime = 0L,
        logID = null,
        playlistURL = HistoryRedownloadMarker.quality(id, 1080),
        executionId = executionId,
    )
}
