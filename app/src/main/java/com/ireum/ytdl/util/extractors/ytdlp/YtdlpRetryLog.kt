package com.ireum.ytdl.util.extractors.ytdlp

import com.ireum.ytdl.util.SensitiveTextRedactor

internal object YtdlpRetryLog {
    fun append(existingDetails: String, entry: String): String = existingDetails + entry

    fun format(
        notice: String,
        errorLabel: String,
        errorMessage: String,
        command: String,
        diagnostics: String,
    ): String {
        return SensitiveTextRedactor.redactOutput(
            "\nRetry:\n" +
                "Reason: $notice\n" +
                "$errorLabel:\n${errorMessage.takeLast(4000)}\n" +
                "Command:\n${SensitiveTextRedactor.redactCommand(command)} \n" +
                "$diagnostics\n"
        )
    }
}
