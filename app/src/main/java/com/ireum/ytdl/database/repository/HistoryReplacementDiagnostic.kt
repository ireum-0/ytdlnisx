package com.ireum.ytdl.database.repository

import com.ireum.ytdl.util.download.DownloadIssue
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueSeverity
import com.ireum.ytdl.util.download.DownloadIssueSource
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.ireum.ytdl.util.download.DownloadOutcome
import com.ireum.ytdl.util.download.DownloadSuggestedAction
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState

enum class HistoryReplacementMismatchKind {
    SOURCE,
    TYPE,
}

data class HistoryReplacementRefusalLedgerDisposition(
    val itemState: LowQualityRedownloadItemState,
    val reasonCode: String,
)

/**
 * The only issues that may cross the durable History-replacement refusal
 * boundary.  Other worker-authoritative issues use their own terminal
 * diagnostic carrier and must never be serialized as a History barrier.
 */
data class HistoryReplacementRefusal internal constructor(
    val issue: DownloadIssue,
) {
    val code: DownloadIssueCode
        get() = issue.code

    val stage: DownloadIssueStage
        get() = issue.stage

    companion object {
        fun from(issue: DownloadIssue): HistoryReplacementRefusal? = when (issue.code) {
            DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH,
            DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH,
            DownloadIssueCode.HISTORY_TARGET_DELETED ->
                HistoryReplacementRefusal(issue).takeIf {
                    issue.stage == DownloadIssueStage.HISTORY
                }
            else -> null
        }
    }
}

object HistoryReplacementDiagnostic {
    fun persistedHistoryReplacementIssue(issueCode: String): DownloadIssue? = when (issueCode) {
        DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH.name ->
            issue(HistoryReplacementMismatchKind.SOURCE)
        DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH.name ->
            issue(HistoryReplacementMismatchKind.TYPE)
        DownloadIssueCode.HISTORY_TARGET_DELETED.name ->
            targetDeletedIssue()
        else -> null
    }

    fun persistedMismatchIssue(issueCode: String): DownloadIssue? =
        persistedHistoryReplacementIssue(issueCode)?.takeIf {
            it.code != DownloadIssueCode.HISTORY_TARGET_DELETED
        }

    fun isPersistedHistoryReplacementRefusal(issueCode: String): Boolean =
        persistedHistoryReplacementIssue(issueCode) != null

    fun isPersistedMismatch(issueCode: String): Boolean =
        persistedMismatchIssue(issueCode) != null

    fun refusalLedgerDisposition(issueCode: String): HistoryReplacementRefusalLedgerDisposition? =
        when (issueCode) {
            DownloadIssueCode.HISTORY_TARGET_DELETED.name ->
                HistoryReplacementRefusalLedgerDisposition(
                    itemState = LowQualityRedownloadItemState.SKIPPED,
                    reasonCode = DownloadIssueCode.HISTORY_TARGET_DELETED.name,
                )
            DownloadIssueCode.HISTORY_REPLACEMENT_SOURCE_MISMATCH.name,
            DownloadIssueCode.HISTORY_REPLACEMENT_TYPE_MISMATCH.name ->
                HistoryReplacementRefusalLedgerDisposition(
                    itemState = LowQualityRedownloadItemState.FAILED,
                    reasonCode = issueCode,
                )
            else -> null
        }

    fun mismatchKind(outcome: HistoryReplacementOutcome): HistoryReplacementMismatchKind? =
        when (outcome) {
            HistoryReplacementOutcome.SourceMismatch -> HistoryReplacementMismatchKind.SOURCE
            HistoryReplacementOutcome.TypeMismatch -> HistoryReplacementMismatchKind.TYPE
            is HistoryReplacementOutcome.Updated,
            HistoryReplacementOutcome.TargetMissing -> null
        }

    fun mismatchKind(authorization: HistoryReplacementAuthorization): HistoryReplacementMismatchKind? =
        when (authorization) {
            HistoryReplacementAuthorization.SourceMismatch -> HistoryReplacementMismatchKind.SOURCE
            HistoryReplacementAuthorization.TypeMismatch -> HistoryReplacementMismatchKind.TYPE
            is HistoryReplacementAuthorization.Authorized,
            HistoryReplacementAuthorization.TargetMissing -> null
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

    fun targetDeletedIssue(): DownloadIssue = DownloadIssue.create(
        stage = DownloadIssueStage.HISTORY,
        code = DownloadIssueCode.HISTORY_TARGET_DELETED,
        severity = DownloadIssueSeverity.WARNING,
        suggestedActions = setOf(
            DownloadSuggestedAction.VIEW_LOG,
            DownloadSuggestedAction.COPY_SUMMARY,
        ),
        details = "History target was deleted before replacement persistence",
        source = DownloadIssueSource.EXPLICIT_STATE,
    )

    fun qualityAuthorityLostIssue(): DownloadIssue = DownloadIssue.create(
        stage = DownloadIssueStage.HISTORY,
        code = DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED,
        severity = DownloadIssueSeverity.ERROR,
        retryable = false,
        suggestedActions = setOf(
            DownloadSuggestedAction.VIEW_LOG,
            DownloadSuggestedAction.COPY_SUMMARY,
        ),
        details = "Low-quality History replacement authority is missing, terminal, or incoherent",
        source = DownloadIssueSource.EXPLICIT_STATE,
    )

    fun qualityAuthorityLossOutcome(cancellationOrigin: Boolean): DownloadOutcome =
        if (cancellationOrigin) {
            DownloadOutcome.canceled()
        } else {
            DownloadOutcome.failed(qualityAuthorityLostIssue())
        }
}
