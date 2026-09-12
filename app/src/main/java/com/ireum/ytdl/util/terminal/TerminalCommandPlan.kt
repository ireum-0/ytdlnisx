package com.ireum.ytdl.util.terminal

import android.content.Context
import android.content.SharedPreferences
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.SensitiveTextRedactor
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpArgumentPolicy
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandPathParser
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandPathResolution
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandOutputTemplateResolution
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandOutputTemplateParser
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandTokenizer
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpOptionOwnership
import com.ireum.ytdl.work.DownloadOutputProvenance
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

enum class TerminalOutputExpectation {
    FILES_REQUIRED,
    NO_FILES_EXPECTED,
}

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
    val appCacheOutputMarkerPath: String,
    val cacheDownloads: Boolean,
    val destinationWritable: Boolean
)

data class TerminalCommandPlan(
    val sanitizedConfig: String,
    val removedOptions: List<String>,
    val requestOptions: List<TerminalRequestOption>,
    val downloadLocation: String,
    val usesAppCache: Boolean,
    val outputAuthorityMarkerPath: String? = null,
    val outputExpectation: TerminalOutputExpectation = TerminalOutputExpectation.FILES_REQUIRED,
) {
    fun createRequest(configFile: File): YoutubeDLRequest {
        val request = YoutubeDLRequest(emptyList())
        YoutubeDLCompat.allowAppGeneratedConfigFile(request, configFile)
        request.addOption("--config-locations", configFile.absolutePath)
        requestOptions.forEach { option ->
            request.addOption(option.name, option.value)
        }
        outputAuthorityMarkerPath?.let { markerPath ->
            // This is an app-owned, post-config carrier.  yt-dlp writes one
            // exact current-output path per completed item even when authored
            // config requests quiet/no-progress output.
            request.addCommands(DownloadOutputProvenance.structuredOutputMarkerArguments(File(markerPath)))
        }
        return request
    }
}

object TerminalCommandPlanner {
    fun normalizeInput(input: String): String = input.replaceFirst("yt-dlp", "")

    fun create(command: String, environment: TerminalCommandEnvironment): TerminalCommandPlan {
        val sanitized = YtdlpArgumentPolicy.stripExternalFfmpegLocationOptionsWithReport(command)
        val outputTemplate = YtdlpCommandOutputTemplateParser.resolve(
            command = sanitized.commandString,
            // Terminal's documented cookie option is a read-only credential
            // input and is retained by the existing sanitizer. It must not
            // be mistaken for an output-authority writer while we reuse the
            // shared output-template confinement parser.
            allowedIndependentWriteOptions = setOf("--cookies", "--cookies-from-browser"),
        )
        if (outputTemplate is YtdlpCommandOutputTemplateResolution.Invalid) {
            throw IllegalArgumentException(
                "Unsupported Terminal output template: ${outputTemplate.reason}"
            )
        }
        val pathResolution = YtdlpCommandPathParser.resolve(sanitized.commandString)
        if (pathResolution is YtdlpCommandPathResolution.Invalid) {
            throw IllegalArgumentException(
                "Unsupported Terminal output path: ${pathResolution.reason}"
            )
        }
        val authoredPathMap = (pathResolution as? YtdlpCommandPathResolution.Explicit)?.pathMap
        if (authoredPathMap?.outputTypePaths?.isNotEmpty() == true) {
            throw IllegalArgumentException(
                "Output-specific --paths destinations cannot be used by Terminal execution"
            )
        }
        if (authoredPathMap != null && authoredPathMap.home == null) {
            // A temp-only map does not establish where yt-dlp's final output
            // will be written, and leaving it in the native config would let
            // the process use an unconfined working-directory destination.
            // Fail closed instead of treating the numeric path as a direct
            // publication contract.  A home+temp map remains supported below.
            throw IllegalArgumentException(
                "Terminal --paths must include an explicit home destination"
            )
        }
        val options = mutableListOf<TerminalRequestOption>()

        environment.cookiePath?.let { options += TerminalRequestOption("--cookies", it) }
        environment.userAgentHeader
            ?.takeIf(String::isNotBlank)
            ?.let { options += TerminalRequestOption("--add-header", "User-Agent:$it") }

        // Preserve the existing direct-write contract when the user disabled
        // caching and the configured destination is writable (or when an
        // authored -P explicitly selects its own destination).  Cached
        // execution, which is the mutable/retryable path, is always routed
        // through the UUID-scoped app staging root and published from the
        // exact current-attempt carrier afterwards.
        val authoredDestination = authoredPathMap?.home?.absolutePath
        val finalDestination = authoredDestination ?: environment.downloadLocation
        val configDeclaresOutputPath = pathResolution is YtdlpCommandPathResolution.Explicit
        val writesDirectly = configDeclaresOutputPath ||
            (!environment.cacheDownloads && environment.destinationWritable)
        if (configDeclaresOutputPath) {
            // Keep the authored -P as the native destination. The shared path
            // parser has already proved it is an absolute, supported path.
        } else {
            options += TerminalRequestOption(
                "-P",
                if (writesDirectly) environment.formattedDownloadLocation
                else environment.appCacheOutputPath,
            )
        }

        val outputExpectation = classifyOutputExpectation(sanitized.commandString)

        return TerminalCommandPlan(
            sanitizedConfig = sanitized.commandString,
            removedOptions = sanitized.removedOptions,
            requestOptions = options,
            downloadLocation = finalDestination,
            usesAppCache = !writesDirectly,
            outputAuthorityMarkerPath = environment.appCacheOutputMarkerPath.takeIf { !writesDirectly },
            outputExpectation = outputExpectation,
        )
    }

    private fun classifyOutputExpectation(command: String): TerminalOutputExpectation {
        val tokens = YtdlpCommandTokenizer.tokenize(command)
            ?: return TerminalOutputExpectation.FILES_REQUIRED
        val noFileOptions = setOf(
            "--dump-pages",
            "--dump-json",
            "--dump-single-json",
            "--dump-user-agent",
            "--extractor-descriptions",
            "--get-comments",
            "--get-description",
            "--get-duration",
            "--get-filename",
            "--get-format",
            "--get-id",
            "--get-thumbnail",
            "--get-title",
            "--get-url",
            "--list-extractors",
            "--list-formats",
            "--list-formats-as-table",
            "--list-formats-old",
            "--list-impersonate-targets",
            "--list-subs",
            "--list-thumbnails",
            "--print-json",
            "--print",
            "--version",
            "--help",
            "-F",
            "-h",
            "-j",
            "-J",
            "-O",
            "-e",
            "-g",
        )
        val noFileStateChangingOptions = setOf("--no-simulate")
        // These options intentionally create a file (or a file-backed
        // side-artifact) even when a preceding --simulate/--no-download made
        // the command look observation-only.  Keep the conservative carrier
        // requirement in that case; only a command whose effective options
        // are known to produce no filesystem output may bypass it.
        val fileProducingOptions = setOf(
            "--convert-sub",
            "--convert-subs",
            "--convert-subtitles",
            "--convert-thumbnails",
            "--download-archive",
            "--print-to-file",
            "--split-chapters",
            "--write-all-thumbnails",
            "--write-description",
            "--write-desktop-link",
            "--write-info-json",
            "--write-link",
            "--write-pages",
            "--write-playlist-metafiles",
            "--write-srt",
            "--write-subs",
            "--write-auto-subs",
            "--write-automatic-subs",
            "--write-thumbnail",
            "--write-url-link",
            "--write-webloc-link",
        )
        var hasFileProducingOption = false
        var simulationImplied = false
        var skipDownload = false
        var hasObservationOnlyPrint = false
        var hasLatePrint = false
        var explicitSimulation: Boolean? = null
        var index = 0
        while (index < tokens.size) {
            val ownership = YtdlpOptionOwnership.inspect(tokens, index)
            if (ownership.optionTerminator) break
            if (ownership.recognizedOption) {
                when {
                    ownership.canonicalName in noFileOptions -> {
                        val printTemplate = ownership.firstValue
                        if (ownership.canonicalName == "--print" || ownership.canonicalName == "-O") {
                            // yt-dlp's --print is observation-only only for
                            // the default/early stages.  A later-stage print
                            // (before_dl, post_process, after_move, ...)
                            // implies a real download and therefore a file
                            // carrier unless an explicit --simulate follows.
                            val whenPrefix = printTemplate
                                ?.substringBefore(':', "")
                                ?.lowercase()
                            val observationOnly = whenPrefix.isNullOrBlank() ||
                                whenPrefix in setOf("pre_process", "after_filter", "video")
                            if (observationOnly) {
                                hasObservationOnlyPrint = true
                            } else {
                                hasLatePrint = true
                            }
                        } else {
                            // Every remaining member of noFileOptions either
                            // exits before download processing or enables an
                            // implicit simulation mode in yt-dlp.  Mark it
                            // once here so short aliases (-j/-J/-e/-g/-O)
                            // and their long forms stay equivalent.
                            simulationImplied = true
                        }
                    }
                    ownership.canonicalName == "--no-download" ||
                        ownership.canonicalName == "--skip-download" -> {
                        // yt-dlp's --skip-download/--no-download still writes
                        // requested side artifacts, but with no side-artifact
                        // option it intentionally produces no file. Keep the
                        // distinction explicit so --write-description,
                        // --write-subs, etc. remain FILES_REQUIRED.
                        skipDownload = true
                    }
                    ownership.canonicalName == "--simulate" || ownership.canonicalName == "-s" -> {
                        explicitSimulation = true
                    }
                    ownership.canonicalName in noFileStateChangingOptions -> {
                        explicitSimulation = false
                    }
                    ownership.canonicalName in fileProducingOptions -> {
                        hasFileProducingOption = true
                    }
                }
            }
            index += when {
                ownership.recognizedOption || ownership.ambiguousOption ->
                    ownership.nextIndexDelta.coerceAtLeast(1)
                else -> 1
            }
        }
        // yt-dlp derives its implicit simulation mode from the complete
        // force-print map, not from whichever --print token happened to be
        // visited last. A command containing both an early/default print and
        // a later-stage print is a real download. Observation-only print
        // forms and metadata/listing options otherwise short-circuit before
        // filesystem output unless --no-simulate explicitly overrides them.
        val implicitSimulation = simulationImplied ||
            (hasObservationOnlyPrint && !hasLatePrint)
        val effectiveNoFiles = when (explicitSimulation) {
            // Explicit simulation returns before yt-dlp writes descriptions,
            // subtitles, thumbnails, or metadata.  A file-producing option
            // therefore does not override an explicit --simulate.
            true -> true
            // Explicit --no-simulate enables the ordinary download path even
            // when an observation-only option appeared first.
            false -> skipDownload && !hasFileProducingOption
            null -> implicitSimulation || (skipDownload && !hasFileProducingOption)
        }
        return if (effectiveNoFiles) {
            TerminalOutputExpectation.NO_FILES_EXPECTED
        } else {
            TerminalOutputExpectation.FILES_REQUIRED
        }
    }
}

object TerminalCommandPlanFactory {
    fun create(
        context: Context,
        preferences: SharedPreferences,
        command: String,
        taskId: String,
        /** Optional execution-bound raw cache authority. */
        cacheRoot: File? = null,
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
        val effectiveCacheRoot = (cacheRoot ?: File(FileUtil.getCachePath(context))).canonicalFile
        val appCacheOutputPath = File(
            effectiveCacheRoot,
            "TERMINAL/$taskId"
        ).absolutePath
        val appCacheOutputMarkerPath = File(
            appCacheOutputPath,
            ".ytdlnisx-terminal-output.txt"
        ).absolutePath

        return TerminalCommandPlanner.create(
            command = command,
            environment = TerminalCommandEnvironment(
                cookiePath = cookiePath,
                userAgentHeader = userAgentHeader,
                downloadLocation = downloadLocation,
                formattedDownloadLocation = FileUtil.formatPath(downloadLocation),
                appCacheOutputPath = appCacheOutputPath,
                appCacheOutputMarkerPath = appCacheOutputMarkerPath,
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
