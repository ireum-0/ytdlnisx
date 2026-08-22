package com.ireum.ytdl.work

import com.ireum.ytdl.database.repository.HistoryReplacementAuthorization
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryReplacementMismatchKind
import com.ireum.ytdl.database.repository.HistoryReplacementTerminalAction
import com.ireum.ytdl.util.download.DownloadIssue
import java.io.IOException

/**
 * Typed refusal raised by a History authorization consumer.  It must cross
 * generic hard-sub/ytdlp catches unchanged so the worker's History terminal
 * state machine remains authoritative.
 */
internal class HistoryReplacementAuthorizationRefusalException(
    val authorization: HistoryReplacementAuthorization
) : IOException(
    when (authorization) {
        HistoryReplacementAuthorization.TargetMissing ->
            HistoryReplacementDiagnostic.targetDeletedIssue().redactedDetails
        HistoryReplacementAuthorization.SourceMismatch ->
            HistoryReplacementDiagnostic.details(HistoryReplacementMismatchKind.SOURCE)
        HistoryReplacementAuthorization.TypeMismatch ->
            HistoryReplacementDiagnostic.details(HistoryReplacementMismatchKind.TYPE)
        is HistoryReplacementAuthorization.Authorized ->
            "History replacement authorization unexpectedly succeeded"
    }
)

internal fun historyReplacementRefusalIssue(
    authorization: HistoryReplacementAuthorization,
): DownloadIssue = when (authorization) {
    HistoryReplacementAuthorization.TargetMissing ->
        HistoryReplacementDiagnostic.targetDeletedIssue()
    HistoryReplacementAuthorization.SourceMismatch ->
        HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.SOURCE)
    HistoryReplacementAuthorization.TypeMismatch ->
        HistoryReplacementDiagnostic.issue(HistoryReplacementMismatchKind.TYPE)
    is HistoryReplacementAuthorization.Authorized ->
        error("Authorized History result is not a refusal")
}

internal fun historyReplacementRefusalTerminalAction(
    authorization: HistoryReplacementAuthorization,
): HistoryReplacementTerminalAction = when (authorization) {
    HistoryReplacementAuthorization.TargetMissing -> HistoryReplacementTerminalAction.TARGET_DELETED
    HistoryReplacementAuthorization.SourceMismatch,
    HistoryReplacementAuthorization.TypeMismatch -> HistoryReplacementTerminalAction.PRESERVE_FAILED
    is HistoryReplacementAuthorization.Authorized ->
        error("Authorized History result is not a refusal")
}
