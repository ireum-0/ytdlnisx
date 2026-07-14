package com.ireum.ytdl.util.download

object DownloadIssueClassifier {
    private const val MAX_CLASSIFIER_INPUT = 16_000

    data class Input(
        val stage: DownloadIssueStage,
        val exceptionClassName: String = "",
        val message: String = "",
        val output: String = "",
        val exitCode: Int? = null,
        val destinationWritable: Boolean? = null,
        val explicitCode: DownloadIssueCode? = null
    )

    fun classify(input: Input): List<DownloadIssue> {
        val details = listOf(input.message, input.output)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeLast(MAX_CLASSIFIER_INPUT)
        val probe = details.lowercase()

        input.explicitCode?.let { code ->
            return listOf(issueFor(code, input.stage, details, DownloadIssueSource.EXPLICIT_STATE))
        }

        val issues = linkedMapOf<DownloadIssueCode, DownloadIssue>()

        val typedTimeout = input.exceptionClassName.endsWith("SocketTimeoutException") ||
            input.exceptionClassName.endsWith("TimeoutCancellationException")
        val patternedTimeout = input.stage in setOf(DownloadIssueStage.EXTRACT, DownloadIssueStage.DOWNLOAD) &&
            NETWORK_TIMEOUT_PATTERNS.any(probe::contains)
        if (typedTimeout || patternedTimeout) {
            issues[DownloadIssueCode.NETWORK_TIMEOUT] = issueFor(
                DownloadIssueCode.NETWORK_TIMEOUT,
                input.stage,
                details,
                if (typedTimeout) DownloadIssueSource.TYPED_EXCEPTION else DownloadIssueSource.OUTPUT_PATTERN
            )
        }

        if (AUTH_PATTERNS.any(probe::contains) || HTTP_401.containsMatchIn(probe)) {
            issues[DownloadIssueCode.AUTH_REQUIRED] = issueFor(
                DownloadIssueCode.AUTH_REQUIRED,
                input.stage,
                details,
                DownloadIssueSource.OUTPUT_PATTERN
            )
        }

        if (FORMAT_PATTERNS.any(probe::contains)) {
            issues[DownloadIssueCode.FORMAT_UNAVAILABLE] = issueFor(
                DownloadIssueCode.FORMAT_UNAVAILABLE,
                input.stage,
                details,
                DownloadIssueSource.OUTPUT_PATTERN
            )
        }

        if (STORAGE_FULL_PATTERNS.any(probe::contains)) {
            issues[DownloadIssueCode.STORAGE_FULL] = issueFor(
                DownloadIssueCode.STORAGE_FULL,
                input.stage,
                details,
                DownloadIssueSource.OUTPUT_PATTERN
            )
        }

        val typedPermissionFailure = input.exceptionClassName.endsWith("SecurityException")
        val patternedDestinationFailure = input.stage in setOf(DownloadIssueStage.PREFLIGHT, DownloadIssueStage.MOVE) &&
            DESTINATION_PATTERNS.any(probe::contains)
        if (input.destinationWritable == false || typedPermissionFailure || patternedDestinationFailure) {
            issues[DownloadIssueCode.DESTINATION_NOT_WRITABLE] = issueFor(
                DownloadIssueCode.DESTINATION_NOT_WRITABLE,
                input.stage,
                details,
                when {
                    input.destinationWritable == false -> DownloadIssueSource.EXPLICIT_STATE
                    typedPermissionFailure -> DownloadIssueSource.TYPED_EXCEPTION
                    else -> DownloadIssueSource.OUTPUT_PATTERN
                }
            )
        }

        val ffmpegStage = input.stage in setOf(
            DownloadIssueStage.MERGE,
            DownloadIssueStage.SUBTITLE,
            DownloadIssueStage.HARD_SUB
        )
        val ffmpegExitFailure = ffmpegStage && input.exitCode != null && input.exitCode != 0
        val patternedFfmpegFailure = ffmpegStage && FFMPEG_FAILURE_PATTERNS.any(probe::contains)
        if (ffmpegExitFailure || patternedFfmpegFailure) {
            issues[DownloadIssueCode.FFMPEG_FAILED] = issueFor(
                DownloadIssueCode.FFMPEG_FAILED,
                input.stage,
                details,
                if (ffmpegExitFailure) DownloadIssueSource.EXIT_CODE else DownloadIssueSource.OUTPUT_PATTERN
            )
        }

        if (issues.isEmpty()) {
            issues[DownloadIssueCode.UNKNOWN] = issueFor(
                DownloadIssueCode.UNKNOWN,
                input.stage,
                details,
                DownloadIssueSource.UNKNOWN
            )
        }
        return issues.values.toList()
    }

    private fun issueFor(
        code: DownloadIssueCode,
        stage: DownloadIssueStage,
        details: String,
        source: DownloadIssueSource
    ): DownloadIssue {
        val retryable = code.supportsSameSettingsRetry()
        val actions = buildSet {
            add(DownloadSuggestedAction.VIEW_LOG)
            add(DownloadSuggestedAction.COPY_SUMMARY)
            if (retryable) add(DownloadSuggestedAction.RETRY)
            when (code) {
                DownloadIssueCode.AUTH_REQUIRED -> {
                    add(DownloadSuggestedAction.OPEN_AUTH_SETTINGS)
                    add(DownloadSuggestedAction.RECONFIGURE)
                }
                DownloadIssueCode.FORMAT_UNAVAILABLE,
                DownloadIssueCode.FFMPEG_FAILED,
                DownloadIssueCode.UNKNOWN -> add(DownloadSuggestedAction.RECONFIGURE)
                DownloadIssueCode.NETWORK_TIMEOUT -> add(DownloadSuggestedAction.OPEN_NETWORK_SETTINGS)
                DownloadIssueCode.STORAGE_FULL -> add(DownloadSuggestedAction.OPEN_STORAGE_SETTINGS)
                DownloadIssueCode.DESTINATION_NOT_WRITABLE -> add(DownloadSuggestedAction.RECONFIGURE)
                else -> Unit
            }
        }
        return DownloadIssue.create(
            stage = stage,
            code = code,
            retryable = retryable,
            suggestedActions = actions,
            details = details,
            source = source
        )
    }

    private val NETWORK_TIMEOUT_PATTERNS = listOf(
        "connection timed out",
        "read timed out",
        "network is unreachable",
        "temporary failure in name resolution"
    )
    private val AUTH_PATTERNS = listOf(
        "authentication required",
        "login required",
        "sign in to confirm",
        "cookies are required"
    )
    private val FORMAT_PATTERNS = listOf(
        "requested format is not available",
        "no video formats found",
        "requested format not available"
    )
    private val STORAGE_FULL_PATTERNS = listOf("no space left on device", "enospc")
    private val DESTINATION_PATTERNS = listOf("permission denied", "read-only file system")
    private val FFMPEG_FAILURE_PATTERNS = listOf("ffmpeg failed", "ffmpeg exited with")
    private val HTTP_401 = Regex("(?:http(?: error)?[ :]+401|status(?: code)?[ :=]+401)\\b")
}
