package com.ireum.ytdl.util

object SensitiveTextRedactor {
    private const val REDACTED = "***"

    private val sensitiveOptions = setOf(
        "--cookies",
        "--cookies-from-browser",
        "--add-header",
        "--username",
        "--password",
        "--ap-username",
        "--ap-password",
        "--video-password",
        "--client-certificate",
        "--client-certificate-key",
        "--client-certificate-password",
        "--netrc-location",
        "--netrc-cmd",
        "--proxy"
    )

    private val sensitiveShortOptions = setOf("-u", "-p")

    private val sensitiveHeaderPatterns = listOf(
        Regex("(?i)(Authorization\\s*:\\s*)\\S+[^\\r\\n]*"),
        Regex("(?i)(Cookie\\s*:\\s*)[^\\r\\n]*"),
        Regex("(?i)(Set-Cookie\\s*:\\s*)[^\\r\\n]*"),
        Regex("(?i)(X-Auth-[A-Za-z0-9_-]*\\s*:\\s*)[^\\r\\n]*"),
        Regex("(?i)(Bearer\\s+)\\S+")
    )

    private val sensitiveQueryPattern =
        Regex("(?i)([?&](?:access_token|refresh_token|token|api_key|key|password)=)[^\\s&#]+")

    private val sensitiveAssignmentPattern =
        Regex("(?i)\\b(access_token|refresh_token|token|api_key|password)\\s*=\\s*[^\\s&]+")

    private val sensitivePathPattern =
        Regex("(?i)(?:[A-Za-z]:)?[\\\\/][^\\s\"']*(?:cookies\\.txt|config-TERMINAL\\[[^\\s\"']*\\.txt)")

    fun redactCommand(command: String): String {
        if (command.isBlank()) return command

        val tokens = tokenizeCommand(command)
        if (tokens.isEmpty()) return redactOutput(command)

        val redactedTokens = mutableListOf<String>()
        var skipNext = false
        for (token in tokens) {
            if (skipNext) {
                redactedTokens += REDACTED
                skipNext = false
                continue
            }

            val rawOptionName = token.substringBefore("=")
            val optionName = if (rawOptionName.startsWith("--")) {
                rawOptionName.lowercase()
            } else {
                rawOptionName
            }
            val attachedShortOption = sensitiveShortOptions.firstOrNull { shortOption ->
                token.length > shortOption.length &&
                        token.startsWith(shortOption)
            }
            when {
                attachedShortOption != null -> {
                    redactedTokens += "$attachedShortOption$REDACTED"
                }
                optionName in sensitiveOptions || optionName in sensitiveShortOptions -> {
                    redactedTokens += if (token.contains("=")) {
                        "${token.substringBefore("=")}=$REDACTED"
                    } else {
                        token
                    }
                    skipNext = !token.contains("=")
                }
                else -> redactedTokens += token
            }
        }

        return redactOutput(redactedTokens.joinToString(" ") { quoteIfNeeded(it) })
    }

    fun redactOutput(text: String): String {
        if (text.isBlank()) return text

        var redacted = text
        sensitiveHeaderPatterns.forEach { pattern ->
            redacted = pattern.replace(redacted) { matchResult ->
                matchResult.groupValues[1] + REDACTED
            }
        }
        redacted = sensitiveQueryPattern.replace(redacted) { matchResult ->
            matchResult.groupValues[1] + REDACTED
        }
        redacted = sensitiveAssignmentPattern.replace(redacted) { matchResult ->
            "${matchResult.groupValues[1]}=$REDACTED"
        }
        redacted = sensitivePathPattern.replace(redacted, REDACTED)
        return redacted
    }

    fun safeNotificationTitle(text: String, fallback: String = "Terminal Task"): String {
        val redacted = redactCommand(text).trim()
        return redacted.ifBlank { fallback }.take(65)
    }

    private fun tokenizeCommand(command: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null

        var index = 0
        while (index < command.length) {
            val char = command[index]
            when {
                quote != null -> {
                    if (char == '\\' && index + 1 < command.length) {
                        current.append(command[index + 1])
                        index += 2
                        continue
                    } else if (char == quote) {
                        quote = null
                    } else {
                        current.append(char)
                    }
                }
                char == '\\' && index + 1 < command.length -> {
                    current.append(command[index + 1])
                    index += 2
                    continue
                }
                char == '\'' || char == '"' -> quote = char
                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
            index += 1
        }

        if (current.isNotEmpty()) {
            tokens += current.toString()
        }
        return tokens
    }

    private fun quoteIfNeeded(token: String): String {
        return if (token.any { it.isWhitespace() }) {
            "\"${token.replace("\"", "\\\"")}\""
        } else {
            token
        }
    }
}
