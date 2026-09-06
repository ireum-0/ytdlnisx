package com.ireum.ytdl.util.terminal

import android.content.Context
import android.content.SharedPreferences
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.SensitiveTextRedactor
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpArgumentPolicy
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandPathParser
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandPathResolution
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

data class TerminalRequestOption(
    val name: String,
    val value: String
)

data class TerminalCommandEnvironment(
    val cookiePath: String?,
    val userAgentHeader: String?,
    val downloadLocation: String,
    val formattedDownloadLocation: String,
    val appCacheOutputPath: String,
    val cacheDownloads: Boolean,
    val destinationWritable: Boolean
)

data class TerminalCommandPlan(
    val sanitizedConfig: String,
    val removedOptions: List<String>,
    val requestOptions: List<TerminalRequestOption>,
    val downloadLocation: String,
    val usesAppCache: Boolean
) {
    fun createRequest(configFile: File): YoutubeDLRequest {
        val request = YoutubeDLRequest(emptyList())
        YoutubeDLCompat.allowAppGeneratedConfigFile(request, configFile)
        request.addOption("--config-locations", configFile.absolutePath)
        requestOptions.forEach { option ->
            request.addOption(option.name, option.value)
        }
        return request
    }
}

object TerminalCommandPlanner {
    fun normalizeInput(input: String): String = input.replaceFirst("yt-dlp", "")

    fun create(command: String, environment: TerminalCommandEnvironment): TerminalCommandPlan {
        val sanitized = YtdlpArgumentPolicy.stripExternalFfmpegLocationOptionsWithReport(command)
        val options = mutableListOf<TerminalRequestOption>()

        environment.cookiePath?.let { options += TerminalRequestOption("--cookies", it) }
        environment.userAgentHeader
            ?.takeIf(String::isNotBlank)
            ?.let { options += TerminalRequestOption("--add-header", "User-Agent:$it") }

        var writesDirectly = !environment.cacheDownloads && environment.destinationWritable
        val configDeclaresOutputPath = YtdlpCommandPathParser.resolve(sanitized.commandString) is
            YtdlpCommandPathResolution.Explicit
        if (configDeclaresOutputPath) {
            writesDirectly = true
        } else {
            val outputPath = if (writesDirectly) {
                environment.formattedDownloadLocation
            } else {
                environment.appCacheOutputPath
            }
            options += TerminalRequestOption("-P", outputPath)
        }

        return TerminalCommandPlan(
            sanitizedConfig = sanitized.commandString,
            removedOptions = sanitized.removedOptions,
            requestOptions = options,
            downloadLocation = environment.downloadLocation,
            usesAppCache = !writesDirectly
        )
    }
}

object TerminalCommandPlanFactory {
    fun create(
        context: Context,
        preferences: SharedPreferences,
        command: String,
        taskId: String
    ): TerminalCommandPlan {
        val downloadLocation = preferences.getString(
            "command_path",
            FileUtil.getDefaultCommandPath()
        ) ?: FileUtil.getDefaultCommandPath()
        val useCookies = preferences.getBoolean("use_cookies", false)
        var cookiePath: String? = null
        if (useCookies) {
            FileUtil.getCookieFile(context) { cookiePath = it }
        }
        val userAgentHeader = if (
            useCookies && preferences.getBoolean("use_header", false)
        ) {
            preferences.getString("useragent_header", "")?.takeIf(String::isNotBlank)
        } else {
            null
        }
        val appCacheOutputPath = File(
            FileUtil.getCachePath(context),
            "TERMINAL/$taskId"
        ).absolutePath

        return TerminalCommandPlanner.create(
            command = command,
            environment = TerminalCommandEnvironment(
                cookiePath = cookiePath,
                userAgentHeader = userAgentHeader,
                downloadLocation = downloadLocation,
                formattedDownloadLocation = FileUtil.formatPath(downloadLocation),
                appCacheOutputPath = appCacheOutputPath,
                cacheDownloads = preferences.getBoolean("cache_downloads", true),
                destinationWritable = FileUtil.canWriteToDestination(downloadLocation, context)
            )
        )
    }
}

object TerminalCommandPreviewFormatter {
    fun format(
        plan: TerminalCommandPlan,
        effectiveArguments: List<String>,
        privatePathPrefixes: Collection<String>,
        configHeading: String,
        argumentsHeading: String,
        removedHeading: String
    ): String {
        val redactedConfig = SensitiveTextRedactor.redactOutput(plan.sanitizedConfig)
        val redactedArguments = SensitiveTextRedactor.redactArguments(effectiveArguments)
            .joinToString(" ", transform = ::quoteArgument)
        return buildString {
            appendLine(configHeading)
            appendLine(redactedConfig)
            appendLine()
            appendLine(argumentsHeading)
            appendLine(redactedArguments)
            if (plan.removedOptions.isNotEmpty()) {
                appendLine()
                appendLine(removedHeading)
                append(plan.removedOptions.joinToString())
            }
        }.let { SensitiveTextRedactor.redactPrivatePaths(it, privatePathPrefixes) }
            .trim()
    }

    private fun quoteArgument(argument: String): String {
        return if (argument.any(Char::isWhitespace)) {
            "\"${argument.replace("\"", "\\\"")}\""
        } else {
            argument
        }
    }
}
