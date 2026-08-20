package com.ireum.ytdl.util.download

import android.content.res.Resources
import com.ireum.ytdl.R

object DownloadIssueText {
    fun summary(resources: Resources, code: DownloadIssueCode): String {
        return resources.getString(
            when (code) {
                DownloadIssueCode.NETWORK_TIMEOUT -> R.string.download_issue_network_timeout
                DownloadIssueCode.MEMBERSHIP_REQUIRED -> R.string.download_issue_membership_required
                DownloadIssueCode.AUTH_REQUIRED -> R.string.download_issue_auth_required
                DownloadIssueCode.FORMAT_UNAVAILABLE -> R.string.download_issue_format_unavailable
                DownloadIssueCode.STORAGE_FULL -> R.string.download_issue_storage_full
                DownloadIssueCode.DESTINATION_NOT_WRITABLE -> R.string.download_issue_destination_not_writable
                DownloadIssueCode.FFMPEG_FAILED -> R.string.download_issue_ffmpeg_failed
                DownloadIssueCode.HISTORY_WRITE_FAILED -> R.string.download_issue_history_failed
                DownloadIssueCode.HISTORY_TARGET_DELETED -> R.string.download_issue_history_failed
                DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED -> R.string.download_issue_history_failed
                DownloadIssueCode.NOTIFICATION_FAILED -> R.string.download_issue_notification_failed
                DownloadIssueCode.UNKNOWN -> R.string.download_issue_unknown
            }
        )
    }

    fun stage(resources: Resources, stage: DownloadIssueStage): String {
        return resources.getString(
            when (stage) {
                DownloadIssueStage.PREFLIGHT -> R.string.download_stage_preflight
                DownloadIssueStage.EXTRACT -> R.string.download_stage_extract
                DownloadIssueStage.DOWNLOAD -> R.string.download_stage_download
                DownloadIssueStage.MERGE -> R.string.download_stage_merge
                DownloadIssueStage.SUBTITLE -> R.string.download_stage_subtitle
                DownloadIssueStage.HARD_SUB -> R.string.download_stage_hard_sub
                DownloadIssueStage.MOVE -> R.string.download_stage_move
                DownloadIssueStage.HISTORY -> R.string.download_stage_history
                DownloadIssueStage.NOTIFICATION -> R.string.download_stage_notification
                DownloadIssueStage.CLEANUP -> R.string.download_stage_cleanup
            }
        )
    }

    fun formatted(resources: Resources, issue: DownloadIssue): String {
        return resources.getString(
            R.string.download_issue_summary,
            stage(resources, issue.stage),
            summary(resources, issue.code)
        )
    }
}
