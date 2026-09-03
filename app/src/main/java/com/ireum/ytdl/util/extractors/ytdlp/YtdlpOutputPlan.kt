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
    val directStagingParent: File? = null,
    val commandPathMap: YtdlpPathMap? = null,
    val ownershipMarker: File? = null,
) {
    val directStagingDirectory: File?
        get() = ytdlpDirectory.takeIf { directNoCache }
}

/**
 * The effective filesystem path map accepted by yt-dlp's --paths option.
 * A null entry means that yt-dlp's default for that key remains in effect.
 */
data class YtdlpPathMap(
    val home: File? = null,
    val temp: File? = null,
    val outputTypePaths: Map<String, File> = emptyMap(),
)

internal sealed interface YtdlpCommandPathResolution {
    data object None : YtdlpCommandPathResolution

    data class Explicit(
        val pathMap: YtdlpPathMap,
    ) : YtdlpCommandPathResolution

    data class Invalid(val reason: String) : YtdlpCommandPathResolution
}

/**
 * Parses the path-map syntax used by yt-dlp. The upstream option parser stores
 * one effective value per key, with later declarations replacing earlier
 * declarations for the same key. The worker can safely rewrite the home and
 * temp keys into its operation-owned staging root. Output-type-specific keys
 * are retained in the model but rejected by output-plan construction because
 * this worker cannot prove their publication lineage independently.
 */
internal object YtdlpCommandPathParser {
    private val outputTypeKeys = setOf(
        "chapter",
        "subtitle",
        "thumbnail",
        "description",
        "annotation",
        "infojson",
        "link",
        "pl_video",
        "pl_thumbnail",
        "pl_description",
        "pl_infojson",
    )

    fun resolve(command: String): YtdlpCommandPathResolution {
        val tokens = tokenize(command)
            ?: return YtdlpCommandPathResolution.Invalid("unbalanced shell quoting")
        val paths = linkedMapOf<String, File>()
        var foundPathOption = false
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
                val parsed = parsePathMapValue(value)
                    ?: return YtdlpCommandPathResolution.Invalid(
                        "unsupported or malformed $option path: $value"
                    )
                foundPathOption = true
                parsed.keys.forEach { key -> paths[key] = parsed.directory }
            }
            index += 1
        }
        if (!foundPathOption) return YtdlpCommandPathResolution.None
        return YtdlpCommandPathResolution.Explicit(
            pathMap = YtdlpPathMap(
                home = paths["home"],
                temp = paths["temp"],
                outputTypePaths = paths
                    .filterKeys { it in outputTypeKeys }
                    .toMap(),
            )
        )
    }

    private data class ParsedPathMapValue(
        val keys: List<String>,
        val directory: File,
    )

    private fun parsePathMapValue(rawValue: String): ParsedPathMapValue? {
        val value = rawValue.trim().trim('"', '\'')
        if (value.isBlank() || value.startsWith("content://", ignoreCase = true)) return null

        val colonIndex = value.indexOf(':')
        val candidateKeys = if (colonIndex > 0 && !isWindowsDrivePath(value)) {
            value.substring(0, colonIndex)
                .split(',')
                .map { it.trim().lowercase() }
        } else {
            emptyList()
        }
        val isTyped = candidateKeys.isNotEmpty() && candidateKeys.all { key ->
            key == "home" || key == "temp" || key in outputTypeKeys
        }
        val typedKeys = if (isTyped) candidateKeys else listOf("home")
        val path = (if (isTyped) value.substring(colonIndex + 1) else value).trim()
        if (path.isBlank() || path.startsWith("~") || path.startsWith("file://")) return null
        // A relative yt-dlp path is resolved against the native process
        // working directory, which is not an operation-owned destination.
        // Canonicalizing it here would turn an ambiguous command into false
        // provenance, so only an explicitly absolute path is authorized.
        if (!File(path).isAbsolute) return null

        return runCatching {
            val file = File(path).canonicalFile
            file.takeIf { it.isAbsolute }?.let { ParsedPathMapValue(typedKeys, it) }
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
