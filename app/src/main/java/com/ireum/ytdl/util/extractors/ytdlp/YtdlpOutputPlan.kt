package com.ireum.ytdl.util.extractors.ytdlp

import java.io.File
import java.util.UUID

/**
 * The output contract shared by request construction and DownloadWorker.
 *
 * A direct/no-cache download still uses an app-created staging directory
 * inside the effective destination.  The final destination is published by
 * an exact move result, so a file that merely appears in the public folder
 * cannot become an output of this operation.
 */
data class YtdlpOutputPlan(
    val finalDestination: String,
    val ytdlpDirectory: File,
    val directNoCache: Boolean,
    val explicitCommandPath: Boolean,
    val directDestinationDirectory: File? = null,
    val ownershipMarker: File? = null,
) {
    val directStagingDirectory: File?
        get() = ytdlpDirectory.takeIf { directNoCache }
}

internal sealed interface YtdlpCommandPathResolution {
    data object None : YtdlpCommandPathResolution

    data class Explicit(
        val option: String,
        val rawValue: String,
        val directory: File,
    ) : YtdlpCommandPathResolution

    data class Invalid(val reason: String) : YtdlpCommandPathResolution
}

/**
 * Parses only the path options that the command worker can safely authorize.
 * Unqualified paths and the yt-dlp `home:` path are supported.  Other typed
 * paths or multiple competing path options remain explicitly ambiguous.
 */
internal object YtdlpCommandPathParser {
    fun resolve(command: String): YtdlpCommandPathResolution {
        val tokens = tokenize(command)
            ?: return YtdlpCommandPathResolution.Invalid("unbalanced shell quoting")
        var explicit: YtdlpCommandPathResolution.Explicit? = null
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token == "--") break

            val optionAndValue = when {
                token == "-P" || token == "--paths" -> {
                    val value = tokens.getOrNull(index + 1)
                        ?: return YtdlpCommandPathResolution.Invalid("$token is missing its path")
                    index += 1
                    token to value
                }
                token.startsWith("--paths=") -> "--paths" to token.substringAfter('=')
                token.startsWith("-P=") -> "-P" to token.substringAfter('=')
                token.startsWith("-P") && token.length > 2 -> "-P" to token.substring(2)
                else -> null
            }
            if (optionAndValue != null) {
                val (option, value) = optionAndValue
                val parsed = parsePath(value)
                    ?: return YtdlpCommandPathResolution.Invalid(
                        "unsupported or malformed $option path: $value"
                    )
                if (explicit != null) {
                    return YtdlpCommandPathResolution.Invalid(
                        "multiple direct output path options are ambiguous"
                    )
                }
                explicit = YtdlpCommandPathResolution.Explicit(
                    option = option,
                    rawValue = value,
                    directory = parsed,
                )
            }
            index += 1
        }
        return explicit ?: YtdlpCommandPathResolution.None
    }

    private fun parsePath(rawValue: String): File? {
        val value = rawValue.trim().trim('"', '\'')
        if (value.isBlank() || value.startsWith("content://", ignoreCase = true)) return null

        val path = when {
            value.startsWith("home:", ignoreCase = true) -> value.substringAfter(':')
            value.contains(':') && !isWindowsDrivePath(value) -> return null
            else -> value
        }.trim()
        if (path.isBlank() || path.startsWith("~") || path.startsWith("file://")) return null
        // A relative yt-dlp path is resolved against the native process
        // working directory, which is not an operation-owned destination.
        // Canonicalizing it here would turn an ambiguous command into false
        // provenance, so only an explicitly absolute path is authorized.
        if (!File(path).isAbsolute) return null

        return runCatching {
            val file = File(path).canonicalFile
            file.takeIf { it.isAbsolute }
        }.getOrNull()
    }

    private fun isWindowsDrivePath(value: String): Boolean =
        value.length > 2 && value[0].isLetter() && value[1] == ':' &&
            (value[2] == '/' || value[2] == '\\')

    private fun tokenize(command: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var tokenStarted = false

        fun finishToken() {
            if (tokenStarted) {
                tokens += current.toString()
                current.setLength(0)
                tokenStarted = false
            }
        }

        command.forEach { character ->
            if (escaped) {
                current.append(character)
                escaped = false
                tokenStarted = true
                return@forEach
            }
            if (quote != null) {
                if (character == quote) {
                    quote = null
                } else if (character == '\\' && quote == '"') {
                    escaped = true
                } else {
                    current.append(character)
                }
                tokenStarted = true
                return@forEach
            }
            when {
                character == '\'' || character == '"' -> {
                    quote = character
                    tokenStarted = true
                }
                character == '\\' -> {
                    escaped = true
                    tokenStarted = true
                }
                character.isWhitespace() -> finishToken()
                else -> {
                    current.append(character)
                    tokenStarted = true
                }
            }
        }
        if (quote != null || escaped) return null
        finishToken()
        return tokens
    }
}

internal object YtdlpOutputPlanToken {
    fun forDownload(downloadId: Long, executionId: String, operationId: String): String {
        val seed = "$downloadId:${executionId.ifBlank { operationId }}"
        return UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    }
}
