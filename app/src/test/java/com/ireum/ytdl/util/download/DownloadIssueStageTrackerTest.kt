package com.ireum.ytdl.util.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIssueStageTrackerTest {
    @Test
    fun recognizesMergeAndSubtitleOutput() {
        assertEquals(
            DownloadIssueStage.MERGE,
            DownloadIssueStageTracker.update(
                DownloadIssueStage.DOWNLOAD,
                "[Merger] Merging formats into output.mkv"
            )
        )
        assertEquals(
            DownloadIssueStage.SUBTITLE,
            DownloadIssueStageTracker.update(
                DownloadIssueStage.DOWNLOAD,
                "[EmbedSubtitle] Embedding subtitles in output.mkv"
            )
        )
    }

    @Test
    fun postprocessingFfmpegFailureIsClassifiedAsFfmpeg() {
        val output = "ERROR: Postprocessing: ffmpeg exited with code 1"
        val stage = DownloadIssueStageTracker.update(DownloadIssueStage.DOWNLOAD, output)
        val issues = DownloadIssueClassifier.classify(
            DownloadIssueClassifier.Input(stage = stage, output = output)
        )

        assertEquals(DownloadIssueStage.MERGE, stage)
        assertTrue(issues.any { it.code == DownloadIssueCode.FFMPEG_FAILED })
    }

    @Test
    fun ordinaryOutputDoesNotChangeStageAndHardSubIsPreserved() {
        assertEquals(
            DownloadIssueStage.DOWNLOAD,
            DownloadIssueStageTracker.update(
                DownloadIssueStage.DOWNLOAD,
                "[download] 50% of 10 MiB"
            )
        )
        assertEquals(
            DownloadIssueStage.HARD_SUB,
            DownloadIssueStageTracker.update(
                DownloadIssueStage.HARD_SUB,
                "[Merger] Merging formats"
            )
        )
    }

    @Test
    fun mediaDownloadResumesDownloadStageAfterSubtitles() {
        assertEquals(
            DownloadIssueStage.DOWNLOAD,
            DownloadIssueStageTracker.update(
                DownloadIssueStage.SUBTITLE,
                "[download] 50% of 10 MiB"
            )
        )
        assertEquals(
            DownloadIssueStage.DOWNLOAD,
            DownloadIssueStageTracker.update(
                DownloadIssueStage.EXTRACT,
                "[download] Destination: output.mp4"
            )
        )
    }
}
