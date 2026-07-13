package com.ireum.ytdl.util.extractors.ytdlp

import java.io.File

object YtdlpArgumentPolicy {
    private const val FFMPEG_LOCATION_OPTION = "--ffmpeg-location"
    private val CONFIG_OPTIONS = setOf("--config", "--config-location", "--config-locations")
    private val PROCESS_SPAWNING_OPTIONS = setOf(
        "--exec",
        "--exec-before-download",
        "--exec-after-download",
        "--external-downloader",
        "--downloader",
        "--external-downloader-args",
        "--downloader-args",
        "--postprocessor-args",
        "--ppa",
        "--use-postprocessor"
    )
    private val BLOCKED_EXTERNAL_OPTIONS = CONFIG_OPTIONS + FFMPEG_LOCATION_OPTION + PROCESS_SPAWNING_OPTIONS

    fun sanitize(
        originalArgs: List<String>,
        allowedConfigFiles: Set<File>
    ): List<String> {
        val args = mutableListOf<String>()
        var i = 0
        while (i < originalArgs.size) {
            val arg = originalArgs[i]
            val normalizedArg = arg.unwrapMatchingQuotes()
            if (isBlockedExternalOption(normalizedArg, BLOCKED_EXTERNAL_OPTIONS)) {
                val inlineValue = normalizedArg.substringAfter("=", "")
                    .takeIf { normalizedArg.contains("=") }
                if (inlineValue != null && isBlockedExternalOption(normalizedArg, CONFIG_OPTIONS)) {
                    if (isAllowedAppGeneratedConfigPath(inlineValue, allowedConfigFiles)) {
                        args.add(arg)
                    }
                    i += 1
                    continue
                }

                val nextArg = originalArgs.getOrNull(i + 1)
                if (
                    isBlockedExternalOption(normalizedArg, CONFIG_OPTIONS) &&
                    nextArg != null &&
                    isAllowedAppGeneratedConfigPath(nextArg, allowedConfigFiles)
                ) {
                    args.add(arg)
                    args.add(nextArg)
                    i += 2
                    continue
                }
                i += if (nextArg != null && !nextArg.unwrapMatchingQuotes().startsWith("-")) 2 else 1
                continue
            }
            args.add(arg)
            i += 1
        }
        return args
    }

    fun stripExternalFfmpegLocationOptions(commandString: String): String {
        val lineSeparator = if (commandString.contains("\r\n")) "\r\n" else "\n"
        var skipNextValueLine = false
        return Regex("\\r?\\n")
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
                    val first = tokens.first().unwrapMatchingQuotes()
                    if (!first.startsWith("-")) {
                        skipNextValueLine = false
                        return@map null
                    }
                    skipNextValueLine = false
                }

                val sanitized = mutableListOf<String>()
                var changed = false
                var i = 0
                while (i < tokens.size) {
                    val token = tokens[i]
                    val normalizedToken = token.unwrapMatchingQuotes()
                    when {
                        isBlockedExternalOption(normalizedToken, BLOCKED_EXTERNAL_OPTIONS) -> {
                            changed = true
                            i += when {
                                normalizedToken.contains("=") -> 1
                                i + 1 < tokens.size && !tokens[i + 1].unwrapMatchingQuotes().startsWith("-") -> 2
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
}
