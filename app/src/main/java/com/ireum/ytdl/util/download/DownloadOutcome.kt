package com.ireum.ytdl.util.download

import com.ireum.ytdl.util.SensitiveTextRedactor

enum class DownloadOutcomeStatus {
    SUCCESS,
    SUCCESS_WITH_WARNINGS,
    WAITING_FOR_ACCESS,
    RETRYABLE_FAILURE,
    FINAL_FAILURE,
    CANCELED
}

enum class DownloadIssueStage {
    PREFLIGHT,
    EXTRACT,
    DOWNLOAD,
    MERGE,
    SUBTITLE,
    HARD_SUB,
    MOVE,
    HISTORY,
    NOTIFICATION,
    CLEANUP
}

enum class DownloadIssueCode {
    NETWORK_TIMEOUT,
    MEMBERSHIP_REQUIRED,
    AUTH_REQUIRED,
    FORMAT_UNAVAILABLE,
    STORAGE_FULL,
    DESTINATION_NOT_WRITABLE,
    FFMPEG_FAILED,
    HISTORY_WRITE_FAILED,
    NOTIFICATION_FAILED,
    UNKNOWN
}

fun DownloadIssueCode.supportsSameSettingsRetry(): Boolean {
    return this == DownloadIssueCode.NETWORK_TIMEOUT ||
        this == DownloadIssueCode.STORAGE_FULL ||
        this == DownloadIssueCode.DESTINATION_NOT_WRITABLE
}

enum class DownloadIssueSeverity {
    INFO,
    WARNING,
    ERROR
}

enum class DownloadIssueSource {
    TYPED_EXCEPTION,
    EXIT_CODE,
    EXPLICIT_STATE,
    OUTPUT_PATTERN,
    UNKNOWN
}

enum class DownloadSuggestedAction {
    VIEW_LOG,
    COPY_SUMMARY,
    RETRY,
    RECONFIGURE,
    OPEN_AUTH_SETTINGS,
    OPEN_NETWORK_SETTINGS,
    OPEN_STORAGE_SETTINGS
}

class DownloadIssue private constructor(
    val stage: DownloadIssueStage,
    val code: DownloadIssueCode,
    val severity: DownloadIssueSeverity,
    val retryable: Boolean,
    val suggestedActions: Set<DownloadSuggestedAction>,
    val redactedDetails: String,
    val source: DownloadIssueSource
) {
    companion object {
        private const val MAX_DETAILS_LENGTH = 8_000

        fun create(
            stage: DownloadIssueStage,
            code: DownloadIssueCode,
            severity: DownloadIssueSeverity = DownloadIssueSeverity.ERROR,
            retryable: Boolean = false,
            suggestedActions: Set<DownloadSuggestedAction> = setOf(
                DownloadSuggestedAction.VIEW_LOG,
                DownloadSuggestedAction.COPY_SUMMARY
            ),
            details: String = "",
            source: DownloadIssueSource = DownloadIssueSource.UNKNOWN
        ): DownloadIssue {
            val safeDetails = SensitiveTextRedactor.redactOutput(details)
                .takeLast(MAX_DETAILS_LENGTH)
            return DownloadIssue(
                stage = stage,
                code = code,
                severity = severity,
                retryable = retryable,
                suggestedActions = suggestedActions,
                redactedDetails = safeDetails,
                source = source
            )
        }
    }
}

data class DownloadOutcome(
    val status: DownloadOutcomeStatus,
    val issues: List<DownloadIssue> = emptyList(),
    val createdFileCount: Int = 0
) {
    init {
        require(createdFileCount >= 0)
        require(status != DownloadOutcomeStatus.SUCCESS_WITH_WARNINGS || issues.isNotEmpty())
    }

    companion object {
        fun completed(createdFileCount: Int, issues: List<DownloadIssue> = emptyList()): DownloadOutcome {
            return DownloadOutcome(
                status = if (issues.isEmpty()) {
                    DownloadOutcomeStatus.SUCCESS
                } else {
                    DownloadOutcomeStatus.SUCCESS_WITH_WARNINGS
                },
                issues = issues,
                createdFileCount = createdFileCount
            )
        }

        fun failed(issue: DownloadIssue): DownloadOutcome {
            return DownloadOutcome(
                status = if (issue.retryable) {
                    DownloadOutcomeStatus.RETRYABLE_FAILURE
                } else {
                    DownloadOutcomeStatus.FINAL_FAILURE
                },
                issues = listOf(issue)
            )
        }

        fun canceled(): DownloadOutcome = DownloadOutcome(DownloadOutcomeStatus.CANCELED)
    }
}
