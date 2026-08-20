package com.ireum.ytdl.database.repository

import com.ireum.ytdl.util.download.DownloadIssue
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueSeverity
import com.ireum.ytdl.util.download.DownloadIssueSource
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.ireum.ytdl.util.download.DownloadSuggestedAction

enum class HistoryReplacementMismatchKind {
    SOURCE,
    TYPE,
}

object HistoryReplacementDiagnostic {
    fun mismatchKind(outcome: HistoryReplacementOutcome): HistoryReplacementMismatchKind? =
        when (outcome) {
            HistoryReplacementOutcome.SourceMismatch -> HistoryReplacementMismatchKind.SOURCE
            HistoryReplacementOutcome.TypeMismatch -> HistoryReplacementMismatchKind.TYPE
            is HistoryReplacementOutcome.Updated,
            HistoryReplacementOutcome.TargetMissing -> null
        }

    fun issueCode(kind: HistoryReplacementMismatchKind): DownloadIssueCode = when (kind) {
        HistoryReplacementMismatchKind.SOURCE -> DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH
        HistoryReplacementMismatchKind.TYPE -> DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH
    }

    fun details(kind: HistoryReplacementMismatchKind): String = when (kind) {
        HistoryReplacementMismatchKind.SOURCE ->
            "History target source no longer matches the replacement download"
        HistoryReplacementMismatchKind.TYPE ->
            "History target media type no longer matches the replacement download"
    }

    fun issue(kind: HistoryReplacementMismatchKind): DownloadIssue = DownloadIssue.create(
        stage = DownloadIssueStage.HISTORY,
        code = issueCode(kind),
        severity = DownloadIssueSeverity.ERROR,
        retryable = false,
        suggestedActions = setOf(
            DownloadSuggestedAction.VIEW_LOG,
            DownloadSuggestedAction.COPY_SUMMARY,
        ),
        details = details(kind),
        source = DownloadIssueSource.EXPLICIT_STATE,
    )
}
