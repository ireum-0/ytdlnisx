package com.ireum.ytdl.util.extractors.ytdlp

import java.io.File

object YtdlpArgumentPolicy {
    private const val FFMPEG_LOCATION_OPTION = "--ffmpeg-location"
    private const val BUNDLED_ARIA2_DOWNLOADER = "libaria2c.so"
    private const val COPY_STREAM_POSTPROCESSOR = "FFmpegCopyStream"
    private const val COPY_STREAM_PPA = "CopyStream:-c copy -an"
    private const val THUMBNAIL_CROP_FILTER =
        "-vf crop=\\\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\\\""
    private val CONFIG_OPTIONS = setOf("--config", "--config-location", "--config-locations")
    private val PROCESS_SPAWNING_OPTIONS = setOf(
        "--exec",
        "--exec-before-download",
        "--exec-after-download",
        "--external-downloader",
        "--external-downloader-args",
        "--downloader-args",
        "--postprocessor-args",
        "--plugin-dirs",
        "--netrc-cmd"
    )
    private val RESTRICTED_VALUE_OPTIONS = setOf(
        "--downloader",
        "--ppa",
        "--use-postprocessor"
    )
    private val BLOCKED_EXTERNAL_OPTIONS = CONFIG_OPTIONS + FFMPEG_LOCATION_OPTION + PROCESS_SPAWNING_OPTIONS
    private val RESTRICTED_EXTERNAL_OPTIONS = BLOCKED_EXTERNAL_OPTIONS + RESTRICTED_VALUE_OPTIONS
    private val SAFE_PPA_VALUES = setOf(
        COPY_STREAM_PPA,
        "ThumbnailsConvertor:-qmin 1 -q:v 1",
        "ThumbnailsConvertor:-qmin 1 -q:v 1 $THUMBNAIL_CROP_FILTER",
        "ThumbnailsConvertor:$THUMBNAIL_CROP_FILTER"
    )

    data class CommandStringSanitizeResult(
        val commandString: String,
        val removedOptions: List<String>
    )

    fun sanitize(
        originalArgs: List<String>,
        allowedConfigFiles: Set<File>
    ): List<String> {
        val args = mutableListOf<String>()
        var i = 0
        while (i < originalArgs.size) {
            val arg = originalArgs[i]
            val normalizedArg = arg.unwrapMatchingQuotes()
            val blockedOption = blockedExternalOptionFor(normalizedArg, RESTRICTED_EXTERNAL_OPTIONS)
            if (blockedOption != null) {
                val inlineValue = inlineOptionValue(normalizedArg, blockedOption)
                if (inlineValue != null) {
                    if (canKeepRestrictedOption(blockedOption, inlineValue, allowedConfigFiles)) {
                        args.add(arg)
                    }
                    i += 1
                    continue
                }

                val nextArg = originalArgs.getOrNull(i + 1)
                if (
                    nextArg != null &&
                    canKeepRestrictedOption(blockedOption, nextArg, allowedConfigFiles)
                ) {
                    args.add(arg)
                    args.add(nextArg)
                    i += 2
                    continue
                }
                i += if (nextArg != null) 2 else 1
                continue
            }
            args.add(arg)
            i += 1
        }
        return args
    }

    fun stripExternalFfmpegLocationOptions(commandString: String): String {
        return stripExternalFfmpegLocationOptionsWithReport(commandString).commandString
    }

    fun stripExternalFfmpegLocationOptionsWithReport(commandString: String): CommandStringSanitizeResult {
        val lineSeparator = if (commandString.contains("\r\n")) "\r\n" else "\n"
        var skipNextValueLine = false
        val removedOptions = linkedSetOf<String>()
        val sanitizedCommand = Regex("\\r?\\n")
            .toPattern()
            .split(commandString, -1)
            .asList()
            .map { line ->
                if (line.isBlank() || line.trimStart().startsWith("#")) {
                    return@map line
                }

                val tokens = tokenizeCommandString(line)
                if (tokens.isEmpty()) {
                    return@map line
                }

                if (skipNextValueLine) {
                    skipNextValueLine = false
                    return@map null
                }

                val sanitized = mutableListOf<String>()
                var changed = false
                var i = 0
                while (i < tokens.size) {
                    val token = tokens[i]
                    val normalizedToken = token.unwrapMatchingQuotes()
                    val blockedOption = blockedExternalOptionFor(normalizedToken, RESTRICTED_EXTERNAL_OPTIONS)
                    when {
                        blockedOption != null -> {
                            val inlineValue = inlineOptionValue(normalizedToken, blockedOption)
                            if (inlineValue != null && canKeepRestrictedOption(blockedOption, inlineValue)) {
                                sanitized.add(token)
                                i += 1
                                continue
                            }

                            val nextToken = tokens.getOrNull(i + 1)
                            if (
                                nextToken != null &&
                                canKeepRestrictedOption(blockedOption, nextToken)
                            ) {
                                sanitized.add(token)
                                sanitized.add(nextToken)
                                i += 2
                                continue
                            }

                            changed = true
                            removedOptions += blockedOption
                            i += when {
                                inlineValue != null -> 1
                                nextToken != null -> 2
                                else -> {
                                    skipNextValueLine = true
                                    1
                                }
                            }
                        }
                        else -> {
                            sanitized.add(token)
                            i += 1
                        }
                    }
                }

                if (!changed) line else sanitized.joinToString(" ").takeIf { it.isNotBlank() }
            }
            .filterNotNull()
            .joinToString(lineSeparator)
        return CommandStringSanitizeResult(
            commandString = sanitizedCommand,
            removedOptions = removedOptions.toList()
        )
    }

    fun containsOptionWithValue(args: List<String>, option: String): Boolean {
        val index = args.indexOf(option)
        return index >= 0 && index + 1 < args.size && !args[index + 1].startsWith("-")
    }

    private fun tokenizeCommandString(commandString: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < commandString.length) {
            val c = commandString[i]
            if (c == '\\' && i + 1 < commandString.length) {
                current.append(c)
                current.append(commandString[i + 1])
                i += 2
                continue
            }
            if ((c == '"' || c == '\'') && (quote == null || quote == c)) {
                quote = if (quote == c) null else c
                current.append(c)
                i += 1
                continue
            }
            if (quote == null && c.isWhitespace()) {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.setLength(0)
                }
                i += 1
                continue
            }
            current.append(c)
            i += 1
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }

    private fun isBlockedExternalOption(arg: String, option: String): Boolean {
        return arg == option || arg.startsWith("$option=")
    }

    private fun isBlockedExternalOption(arg: String, options: Set<String>): Boolean {
        return options.any { isBlockedExternalOption(arg, it) }
    }

    private fun blockedExternalOptionFor(arg: String, options: Set<String>): String? {
        return options.firstOrNull { isBlockedExternalOption(arg, it) }
    }

    private fun inlineOptionValue(arg: String, option: String): String? {
        return arg.substringAfter("$option=", "").takeIf { arg.startsWith("$option=") }
    }

    private fun String.unwrapMatchingQuotes(): String {
        val trimmed = trim()
        if (trimmed.length < 2) return trimmed
        val first = trimmed.first()
        val last = trimmed.last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun isAllowedAppGeneratedConfigPath(path: String, allowedConfigFiles: Set<File>): Boolean {
        return runCatching {
            val candidate = File(path.unwrapMatchingQuotes()).canonicalFile
            candidate.isFile && allowedConfigFiles.any { it.canonicalFile == candidate }
        }.getOrDefault(false)
    }

    private fun canKeepRestrictedOption(
        option: String,
        rawValue: String,
        allowedConfigFiles: Set<File> = emptySet()
    ): Boolean {
        val value = rawValue.unwrapMatchingQuotes()
        return when (option) {
            "--config", "--config-location", "--config-locations" ->
                isAllowedAppGeneratedConfigPath(value, allowedConfigFiles)
            "--downloader" -> value == BUNDLED_ARIA2_DOWNLOADER
            "--use-postprocessor" -> value == COPY_STREAM_POSTPROCESSOR
            "--ppa" -> value in SAFE_PPA_VALUES
            else -> false
        }
    }
}
