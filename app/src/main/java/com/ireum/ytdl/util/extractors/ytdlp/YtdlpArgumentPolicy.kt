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
        val normalizedArgs = originalArgs.map { it.unwrapMatchingQuotes() }
        var i = 0
        while (i < originalArgs.size) {
            val arg = originalArgs[i]
            val normalizedArg = normalizedArgs[i]
            val ownership = YtdlpOptionOwnership.inspect(normalizedArgs, i)
            val blockedOption = ownership.canonicalName
                ?.let { canonical ->
                    RESTRICTED_EXTERNAL_OPTIONS.firstOrNull { option -> option == canonical }
                }
                ?: blockedExternalOptionFor(normalizedArg, RESTRICTED_EXTERNAL_OPTIONS)
            if (blockedOption != null) {
                val inlineValue = ownership.inlineValue ?: inlineOptionValue(normalizedArg, blockedOption)
                val sharedSpan = ownership.recognizedOption || ownership.ambiguousOption
                val followingCount = if (sharedSpan) {
                    ownership.consumedFollowingTokenCount
                } else if (inlineValue == null && originalArgs.getOrNull(i + 1) != null) {
                    1
                } else {
                    0
                }
                val nextIndex = (i + 1 + followingCount).coerceAtMost(originalArgs.size)
                val separatedValue = originalArgs.getOrNull(i + 1)
                if (inlineValue != null) {
                    if (canKeepRestrictedOption(blockedOption, inlineValue, allowedConfigFiles)) {
                        args.add(arg)
                        if (sharedSpan) {
                            args.addAll(originalArgs.subList(i + 1, nextIndex))
                        }
                    }
                    i = nextIndex.coerceAtLeast(i + 1)
                    continue
                }

                if (
                    separatedValue != null &&
                    canKeepRestrictedOption(blockedOption, separatedValue, allowedConfigFiles)
                ) {
                    args.add(arg)
                    args.addAll(originalArgs.subList(i + 1, nextIndex))
                    i = nextIndex.coerceAtLeast(i + 1)
                    continue
                }
                i = nextIndex.coerceAtLeast(i + 1)
                continue
            }
            val span = if (ownership.recognizedOption || ownership.ambiguousOption) {
                ownership.nextIndexDelta
            } else {
                1
            }
            val nextIndex = (i + span).coerceAtMost(originalArgs.size)
            args.addAll(originalArgs.subList(i, nextIndex))
            i = nextIndex
        }
        return args
    }

    fun stripExternalFfmpegLocationOptions(commandString: String): String {
        return stripExternalFfmpegLocationOptionsWithReport(commandString).commandString
    }

    fun stripExternalFfmpegLocationOptionsWithReport(commandString: String): CommandStringSanitizeResult {
        val tokens = YtdlpCommandTokenizer.tokenize(commandString)
            ?: return CommandStringSanitizeResult(
                commandString = commandString,
                removedOptions = emptyList(),
            )
        val removedOptions = linkedSetOf<String>()
        val sanitized = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val ownership = YtdlpOptionOwnership.inspect(tokens, i)
            if (ownership.optionTerminator) {
                sanitized.addAll(tokens.subList(i, tokens.size))
                break
            }

            val token = tokens[i]
            val blockedOption = ownership.canonicalName
                ?.let { canonical ->
                    RESTRICTED_EXTERNAL_OPTIONS.firstOrNull { option -> option == canonical }
                }
                ?: blockedExternalOptionFor(token, RESTRICTED_EXTERNAL_OPTIONS)
            if (blockedOption != null) {
                val inlineValue = ownership.inlineValue ?: inlineOptionValue(token, blockedOption)
                val span = if (ownership.recognizedOption || ownership.ambiguousOption) {
                    ownership.nextIndexDelta
                } else if (inlineValue == null && i + 1 < tokens.size) {
                    2
                } else {
                    1
                }
                val end = (i + span).coerceAtMost(tokens.size)
                val separatedValue = tokens.getOrNull(i + 1)
                val canKeep = if (inlineValue != null) {
                    canKeepRestrictedOption(blockedOption, inlineValue)
                } else {
                    separatedValue != null &&
                        canKeepRestrictedOption(blockedOption, separatedValue)
                }
                if (canKeep) {
                    sanitized.addAll(tokens.subList(i, end))
                } else {
                    removedOptions += blockedOption
                }
                i = end
                continue
            }

            val span = if (ownership.recognizedOption || ownership.ambiguousOption) {
                ownership.nextIndexDelta
            } else {
                1
            }
            val end = (i + span).coerceAtMost(tokens.size)
            sanitized.addAll(tokens.subList(i, end))
            i = end
        }
        val sanitizedCommand = YtdlpCommandTokenizer.render(sanitized)
        return CommandStringSanitizeResult(
            commandString = sanitizedCommand,
            removedOptions = removedOptions.toList()
        )
    }

    fun containsOptionWithValue(args: List<String>, option: String): Boolean {
        val index = args.indexOf(option)
        return index >= 0 && index + 1 < args.size && !args[index + 1].startsWith("-")
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
