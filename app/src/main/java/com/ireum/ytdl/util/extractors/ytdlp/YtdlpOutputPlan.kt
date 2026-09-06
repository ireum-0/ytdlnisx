package com.ireum.ytdl.util.extractors.ytdlp

import java.io.File
import java.util.UUID

internal val YTDLP_OUTPUT_TYPE_KEYS = setOf(
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

/**
 * The output contract shared by request construction and DownloadWorker.
 *
 * A direct/no-cache download still uses an app-created staging directory
 * inside the effective destination. The final destination is published by
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

internal sealed interface YtdlpCommandOutputTemplateResolution {
    data object None : YtdlpCommandOutputTemplateResolution

    /**
     * Effective yt-dlp output-template map after last-value-wins processing.
     * A null value is an explicit typed-empty suppression such as
     * `thumbnail:` and produces no destination for that type.
     */
    data class Explicit(
        val templates: Map<String, String?>,
    ) : YtdlpCommandOutputTemplateResolution

    data class Invalid(val reason: String) : YtdlpCommandOutputTemplateResolution
}

/** Shared tokenizer for the app's persisted yt-dlp command/config syntax. */
internal object YtdlpCommandTokenizer {
    fun tokenize(command: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var inComment = false
        var tokenStarted = false

        fun finishToken() {
            if (tokenStarted) {
                tokens += current.toString()
                current.setLength(0)
                tokenStarted = false
            }
        }

        command.forEach { character ->
            if (inComment) {
                if (character == '\n' || character == '\r') inComment = false
                return@forEach
            }
            if (escaped) {
                // Python shlex only treats backslash as an escape for `"` and
                // `\\` while inside double quotes.  A backslash before any
                // other character is literal data and must survive tokenization
                // so it cannot become a new option (for example `"\\-P"`).
                if (quote == '"' && character != '"' && character != '\\') {
                    current.append('\\')
                }
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
                character == '#' -> {
                    // yt-dlp Config.read_file uses shlex.split(contents, comments=true).
                    // Outside quotes an unescaped # comments out the rest of that line.
                    finishToken()
                    inComment = true
                }
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

    /**
     * Renders tokens back into the config syntax consumed by yt-dlp's
     * `shlex.split(contents, comments=True)` parser.
     *
     * Every token is either emitted using a conservative unquoted alphabet or
     * double-quoted with backslashes and quotes escaped.  The corresponding
     * tokenizer therefore preserves literal backslashes, empty tokens,
     * whitespace, comments, and embedded quotes on a sanitizer round-trip.
     */
    fun render(tokens: List<String>): String =
        tokens.joinToString(" ", transform = ::renderToken)

    fun renderToken(token: String): String {
        if (token.isNotEmpty() && token.all { character ->
                character.isLetterOrDigit() || character in "-_./:=+,%@"
            }) {
            return token
        }
        return "\"${token.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
}

internal enum class YtdlpOptionTarget {
    PATH,
    OUTPUT,
}

/**
 * The portion of yt-dlp's optparse surface that can own following tokens.
 *
 * This is deliberately shared by output/path planning and config sanitization.
 * A boolean "consumes the next token" flag is not sufficient for options such
 * as --replace-in-metadata (nargs=3) or --print-to-file (nargs=2).
 */
internal data class YtdlpOptionTokenOwnership(
    val canonicalName: String?,
    val target: YtdlpOptionTarget?,
    val requiredValueCount: Int,
    val inlineValue: String?,
    val values: List<String>,
    val consumedFollowingTokenCount: Int,
    val recognizedOption: Boolean,
    val ambiguousOption: Boolean = false,
    val optionTerminator: Boolean = false,
) {
    val nextIndexDelta: Int
        get() = if (optionTerminator) 1 else 1 + consumedFollowingTokenCount

    val missingValueCount: Int
        get() = (requiredValueCount - values.size).coerceAtLeast(0)

    val firstValue: String?
        get() = values.firstOrNull()
}

/**
 * Shared arity-aware static model of bundled yt-dlp 2025.11.12 options.
 *
 * The bundled parser is Python optparse. Long options use exact names or an
 * unambiguous prefix; an inline =value satisfies only the first required
 * argument. Unknown/ambiguous options are never granted destination
 * authority, but an ambiguous prefix that could own a value is conservatively
 * skipped so a native parse error cannot turn a later token into authority.
 */
internal object YtdlpOptionOwnership {
    private val SINGLE_VALUE_LONG_OPTIONS = setOf(
        "--add-headers",
        "--age-limit",
        "--ap-mso",
        "--ap-password",
        "--ap-username",
        "--audio-format",
        "--audio-quality",
        "--autonumber-size",
        "--autonumber-start",
        "--batch-file",
        "--break-match-filters",
        "--buffer-size",
        "--cache-dir",
        "--client-certificate",
        "--client-certificate-key",
        "--client-certificate-password",
        "--cn-verification-proxy",
        "--color",
        "--compat-options",
        "--concat-playlist",
        "--concurrent-fragments",
        "--config-locations",
        "--convert-sub",
        "--convert-subs",
        "--convert-subtitles",
        "--convert-thumbnails",
        "--cookies",
        "--cookies-from-browser",
        "--date",
        "--dateafter",
        "--datebefore",
        "--default-search",
        "--download-archive",
        "--download-sections",
        "--downloader",
        "--downloader-args",
        "--encoding",
        "--exec",
        "--exec-before-download",
        "--external-downloader",
        "--external-downloader-args",
        "--extractor-args",
        "--extractor-retries",
        "--ffmpeg-location",
        "--file-access-retries",
        "--fixup",
        "--format",
        "--format-sort",
        "--fragment-retries",
        "--geo-bypass-country",
        "--geo-bypass-ip-block",
        "--geo-verification-proxy",
        "--http-chunk-size",
        "--ies",
        "--impersonate",
        "--js-runtimes",
        "--limit-rate",
        "--load-info-json",
        "--match-filters",
        "--match-title",
        "--max-downloads",
        "--max-filesize",
        "--max-sleep-interval",
        "--max-views",
        "--merge-output-format",
        "--metadata-from-title",
        "--min-filesize",
        "--min-sleep-interval",
        "--min-views",
        "--netrc-cmd",
        "--netrc-location",
        "--output-na-placeholder",
        "--parse-metadata",
        "--password",
        "--playlist-end",
        "--playlist-items",
        "--playlist-start",
        "--plugin-dirs",
        "--postprocessor-args",
        "--ppa",
        "--preset-alias",
        "--progress-delta",
        "--progress-template",
        "--proxy",
        "--rate-limit",
        "--recode-video",
        "--referer",
        "--reject-title",
        "--remote-components",
        "--remove-chapters",
        "--remux-video",
        "--retries",
        "--retry-sleep",
        "--skip-playlist-after-errors",
        "--sleep-interval",
        "--sleep-requests",
        "--sleep-subtitles",
        "--socket-timeout",
        "--source-address",
        "--sponsorblock-api",
        "--sponsorblock-chapter-title",
        "--sponsorblock-mark",
        "--sponsorblock-remove",
        "--sponskrub-args",
        "--sponskrub-location",
        "--srt-langs",
        "--sub-format",
        "--sub-langs",
        "--throttled-rate",
        "--trim-file-names",
        "--trim-filenames",
        "--twofactor",
        "--update-to",
        "--use-extractors",
        "--use-postprocessor",
        "--user-agent",
        "--username",
        "--video-password",
        "--wait-for-video",
        "--xff",
    )

    /** Fixed required-argument counts from the bundled parser. */
    private val LONG_OPTION_ARITIES: Map<String, Int> = buildMap {
        SINGLE_VALUE_LONG_OPTIONS.forEach { put(it, 1) }
        put("--alias", 2)
        put("--print", 1)
        put("--print-to-file", 2)
        put("--paths", 1)
        put("--output", 1)
        put("--replace-in-metadata", 3)
    }

    // Exact no-value options that are prefixes of value-taking options, plus
    // no-value options that must be recognized as real policy-sensitive flags.
    private val LONG_NO_VALUE_OPTIONS = setOf(
        "--geo-bypass",
        "--netrc",
        "--no-config",
        "--no-config-locations",
        "--no-plugin-dirs",
        "--no-js-runtimes",
        "--no-remote-components",
        "--no-update",
        "--print-traffic",
        "--progress",
        "--sponskrub",
        "--update",
        "--write-thumbnail",
        "--write-pages",
    )

    private val LONG_OPTION_NAMES = LONG_OPTION_ARITIES.keys + LONG_NO_VALUE_OPTIONS

    fun inspect(tokens: List<String>, index: Int): YtdlpOptionTokenOwnership {
        val token = tokens.getOrNull(index)
            ?: return YtdlpOptionTokenOwnership(
                canonicalName = null,
                target = null,
                requiredValueCount = 0,
                inlineValue = null,
                values = emptyList(),
                consumedFollowingTokenCount = 0,
                recognizedOption = false,
            )
        if (token == "--") {
            return YtdlpOptionTokenOwnership(
                canonicalName = null,
                target = null,
                requiredValueCount = 0,
                inlineValue = null,
                values = emptyList(),
                consumedFollowingTokenCount = 0,
                recognizedOption = true,
                optionTerminator = true,
            )
        }

        if (token.startsWith("--") && token.length > 2) {
            val optionName = token.substringBefore('=')
            val canonical = resolveLongOption(optionName)
            if (canonical != null) {
                val required = LONG_OPTION_ARITIES[canonical] ?: 0
                val hasInlineValue = token.contains('=') && required > 0
                val inline = token.substringAfter('=', "").takeIf { hasInlineValue }
                val inlineCount = if (hasInlineValue) 1 else 0
                val neededFollowing = (required - inlineCount).coerceAtLeast(0)
                val followingCount = minOf(neededFollowing, tokens.size - index - 1)
                val values = buildList {
                    if (inline != null) add(inline)
                    repeat(followingCount) { add(tokens[index + 1 + it]) }
                }
                return YtdlpOptionTokenOwnership(
                    canonicalName = canonical,
                    target = canonical.targetOrNull(),
                    requiredValueCount = required,
                    inlineValue = inline,
                    values = values,
                    consumedFollowingTokenCount = followingCount,
                    recognizedOption = true,
                )
            }

            val candidates = longOptionCandidates(optionName)
            val conservativeFollowingCount = if (candidates.any { LONG_OPTION_ARITIES[it] ?: 0 > 0 }) {
                minOf(1, tokens.size - index - 1)
            } else {
                0
            }
            return YtdlpOptionTokenOwnership(
                canonicalName = null,
                target = null,
                requiredValueCount = 0,
                inlineValue = null,
                values = if (conservativeFollowingCount == 1) {
                    listOf(tokens[index + 1])
                } else {
                    emptyList()
                },
                consumedFollowingTokenCount = conservativeFollowingCount,
                recognizedOption = false,
                ambiguousOption = candidates.size > 1,
            )
        }

        val short = YtdlpShortOptionClusterParser.inspect(
            token = token,
            followingToken = tokens.getOrNull(index + 1),
        ) ?: return YtdlpOptionTokenOwnership(
            canonicalName = null,
            target = null,
            requiredValueCount = 0,
            inlineValue = null,
            values = emptyList(),
            consumedFollowingTokenCount = 0,
            recognizedOption = false,
        )
        return YtdlpOptionTokenOwnership(
            canonicalName = short.canonicalName,
            target = short.canonicalName.targetOrNull(),
            requiredValueCount = short.requiredValueCount,
            inlineValue = short.inlineValue,
            values = short.values,
            consumedFollowingTokenCount = short.consumedFollowingTokenCount,
            recognizedOption = short.recognizedOption,
        )
    }

    fun resolveLongOption(optionName: String): String? {
        if (!optionName.startsWith("--") || optionName.length <= 2) return null
        if (optionName in LONG_OPTION_NAMES) return optionName
        return longOptionCandidates(optionName).singleOrNull()
    }

    fun longOptionCandidates(optionName: String): List<String> {
        if (!optionName.startsWith("--") || optionName.length <= 2) return emptyList()
        return LONG_OPTION_NAMES
            .filter { canonical -> canonical.startsWith(optionName) }
            .sorted()
    }

    private fun String.targetOrNull(): YtdlpOptionTarget? = when (this) {
        "-P", "--paths" -> YtdlpOptionTarget.PATH
        "-o", "--output" -> YtdlpOptionTarget.OUTPUT
        else -> null
    }
}

/**
 * Models the short-option cluster handling used by Python optparse. A
 * value-taking option consumes the remainder of its token, or the following
 * token when it is the final character in a cluster. This matters for yt-dlp
 * because `-qP` and `-qo` are not unknown options: they are `-q` followed by
 * the value-taking `-P`/`-o` option.
 */
internal object YtdlpShortOptionClusterParser {
    data class Match(
        val value: String?,
        val consumesFollowingToken: Boolean,
    )

    data class Ownership(
        val canonicalName: String,
        val requiredValueCount: Int,
        val inlineValue: String?,
        val values: List<String>,
        val consumedFollowingTokenCount: Int,
        val recognizedOption: Boolean,
    )

    // These are the value-taking short options exposed by bundled yt-dlp
    // 2025.11.12. If one appears before the target, the remainder of the
    // token is its value and cannot contain another option.
    private val VALUE_TAKING_OPTIONS = setOf(
        '2', 'I', 'N', 'O', 'P', 'R', 'S', 'a', 'f', 'o', 'p', 'r', 't', 'u',
    )

    // The no-value options that may legally precede -o/-P in a cluster. Keep
    // this explicit instead of treating arbitrary letters as flags: an
    // unknown short option must not be reinterpreted as a destination option.
    private val NO_VALUE_OPTIONS = setOf(
        '4', '6', 'C', 'F', 'J', 'U', 'X', 'c', 'e', 'g', 'h', 'i', 'j', 'k',
        'n', 'q', 's', 'v', 'w', 'x',
    )

    fun match(token: String, followingToken: String?, target: Char): Match? {
        if (!token.startsWith('-') || token.startsWith("--") || token.length < 2) {
            return null
        }
        val cluster = token.substring(1)
        var index = 0
        while (index < cluster.length) {
            val option = cluster[index]
            if (option == target) {
                val attached = cluster.substring(index + 1)
                return if (attached.isNotEmpty()) {
                    Match(value = attached, consumesFollowingToken = false)
                } else {
                    Match(value = followingToken, consumesFollowingToken = true)
                }
            }
            if (option in VALUE_TAKING_OPTIONS || option !in NO_VALUE_OPTIONS) {
                // A preceding value-taking or unknown option owns the rest of
                // this token; the target is not an effective option here.
                return null
            }
            index += 1
        }
        return null
    }

    fun inspect(token: String, followingToken: String?): Ownership? {
        if (!token.startsWith('-') || token.startsWith("--") || token.length < 2) {
            return null
        }
        val cluster = token.substring(1)
        var index = 0
        while (index < cluster.length) {
            val option = cluster[index]
            if (option in VALUE_TAKING_OPTIONS) {
                val attached = cluster.substring(index + 1)
                val consumesFollowing = attached.isEmpty()
                val values = if (attached.isNotEmpty()) {
                    listOf(attached)
                } else {
                    followingToken?.let(::listOf).orEmpty()
                }
                return Ownership(
                    canonicalName = "-$option",
                    requiredValueCount = 1,
                    inlineValue = attached.takeIf { it.isNotEmpty() },
                    values = values,
                    consumedFollowingTokenCount = if (consumesFollowing && followingToken != null) 1 else 0,
                    recognizedOption = true,
                )
            }
            if (option !in NO_VALUE_OPTIONS) {
                return Ownership(
                    canonicalName = "-$option",
                    requiredValueCount = 0,
                    inlineValue = null,
                    values = emptyList(),
                    consumedFollowingTokenCount = 0,
                    recognizedOption = false,
                )
            }
            index += 1
        }
        return Ownership(
            canonicalName = "-${cluster.first()}",
            requiredValueCount = 0,
            inlineValue = null,
            values = emptyList(),
            consumedFollowingTokenCount = 0,
            recognizedOption = true,
        )
    }

    fun consumesFollowingToken(token: String): Boolean {
        return inspect(token, followingToken = "").let { ownership ->
            ownership?.requiredValueCount == 1 && ownership.inlineValue == null
        }
    }
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
    /**
     * Python's POSIX ``expandvars`` recognizes ``$name`` and ``${name}``
     * forms (its bundled regex is ASCII ``\$(\w+|\{[^}]*\})``). The native
     * process environment is supplied by the worker and cannot be reproduced
     * safely while planning publication. Reject only those syntactic forms so
     * an authored path cannot receive authority from an expansion we have not
     * established; punctuation-only dollar text remains ordinary data.
     */
    private val environmentVariablePattern =
        Regex("""\$(?:[A-Za-z0-9_]+|\{[^}]*\})""")

    fun resolve(command: String): YtdlpCommandPathResolution {
        val tokens = YtdlpCommandTokenizer.tokenize(command)
            ?: return YtdlpCommandPathResolution.Invalid("unbalanced shell quoting")
        val paths = linkedMapOf<String, File>()
        var foundPathOption = false
        var index = 0
        while (index < tokens.size) {
            val ownership = YtdlpOptionOwnership.inspect(tokens, index)
            if (ownership.optionTerminator) break

            if (ownership.target == YtdlpOptionTarget.PATH) {
                if (ownership.missingValueCount > 0 || ownership.firstValue == null) {
                    return YtdlpCommandPathResolution.Invalid(
                        "${ownership.canonicalName} is missing its path"
                    )
                }
                val value = ownership.firstValue
                    ?: return YtdlpCommandPathResolution.Invalid(
                        "${ownership.canonicalName} is missing its path"
                    )
                val parsed = parsePathMapValue(value)
                    ?: return YtdlpCommandPathResolution.Invalid(
                        "unsupported or malformed ${ownership.canonicalName} path: $value"
                    )
                foundPathOption = true
                parsed.keys.forEach { key -> paths[key] = parsed.directory }
                index += ownership.nextIndexDelta
                continue
            }

            // A token owned by any recognized fixed-arity option is data, even
            // when it literally looks like -P/--paths. Ambiguous options are
            // skipped conservatively because yt-dlp will reject them before
            // execution rather than granting a later token authority.
            if (ownership.recognizedOption || ownership.ambiguousOption) {
                if (ownership.recognizedOption && ownership.missingValueCount > 0) {
                    return YtdlpCommandPathResolution.Invalid(
                        "${ownership.canonicalName} is missing its value"
                    )
                }
                index += ownership.nextIndexDelta
                continue
            }

            index += 1
        }
        if (!foundPathOption) return YtdlpCommandPathResolution.None
        return YtdlpCommandPathResolution.Explicit(
            pathMap = YtdlpPathMap(
                home = paths["home"],
                temp = paths["temp"],
                outputTypePaths = paths
                    .filterKeys { it in YTDLP_OUTPUT_TYPE_KEYS }
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
            key == "home" || key == "temp" || key in YTDLP_OUTPUT_TYPE_KEYS
        }
        val typedKeys = if (isTyped) candidateKeys else listOf("home")
        val path = (if (isTyped) value.substring(colonIndex + 1) else value).trim()
        if (path.isBlank() || path.startsWith("~") || path.startsWith("file://")) return null
        // yt-dlp expands environment variables in authored path-map values
        // immediately before native output. The worker cannot safely grant
        // publication authority to a value whose effective destination is
        // dependent on that runtime environment, so fail closed here.
        if (environmentVariablePattern.containsMatchIn(path)) return null
        // A relative yt-dlp path is resolved against the native process
        // working directory, which is not an operation-owned destination.
        // Canonicalizing it here would turn an ambiguous command into false
        // provenance, so only an explicitly absolute path is authorized.
        // Android uses Unix-rooted paths. The JVM used by these policy tests
        // may be Windows-hosted, where File("/storage/...").isAbsolute can be
        // false even though the bundled runtime will treat it as absolute.
        if (!File(path).isAbsolute && !path.startsWith('/') && !isWindowsDrivePath(path)) {
            return null
        }

        return runCatching {
            val file = File(path).canonicalFile
            file.takeIf { it.isAbsolute }?.let { ParsedPathMapValue(typedKeys, it) }
        }.getOrNull()
    }

    private fun isWindowsDrivePath(value: String): Boolean =
        value.length > 2 && value[0].isLetter() && value[1] == ':' &&
            (value[2] == '/' || value[2] == '\\')
}

/**
 * Models yt-dlp's effective `-o/--output` map before native execution.
 *
 * yt-dlp resolves one last value per output type and ignores `--paths` when
 * the evaluated output is absolute. It also expands `~` and environment
 * variables before template substitution. Metadata substitutions are filename
 * sanitized, but a dynamic directory component can still evaluate to `..`.
 * Therefore this policy accepts ordinary relative filenames and static nested
 * directories, while failing closed for output forms whose confinement cannot
 * be established before execution.
 */
internal object YtdlpCommandOutputTemplateParser {
    private const val DYNAMIC_FIELD = '\u0001'
    private val conversionCharacters = "diouxXeEfFgGcrsaljhqBUDS".toSet()
    private val conversionFlags = "#0-+ ".toSet()
    private val lengthModifiers = "hlL".toSet()
    private val authoredIndependentWriteOptions = setOf(
        "--cache-dir",
        "--cookies",
        "--download-archive",
        "--print-to-file",
        "--write-pages",
    )
    private val authoredExecutionAuthorityOptions = setOf(
        "--js-runtimes",
    )
    private val authoredOutputSemanticOptions = setOf(
        "--output-na-placeholder",
    )
    // The bundled yt-dlp parser accepts unambiguous long-option prefixes. An
    // abbreviated form of any option below could otherwise evade this parser
    // or the later argument sanitizer and recover filesystem/process authority.
    private val exactSafeLongOptionsThatPrefixSensitiveOptions = setOf(
        "--netrc",
        "--print",
    )
    private val abbreviationSensitiveOptions =
        authoredIndependentWriteOptions + authoredExecutionAuthorityOptions +
            authoredOutputSemanticOptions + setOf(
        "--alias",
        "--config",
        "--config-location",
        "--config-locations",
        "--downloader",
        "--downloader-args",
        "--exec",
        "--exec-after-download",
        "--exec-before-download",
        "--external-downloader",
        "--external-downloader-args",
        "--ffmpeg-location",
        "--netrc-cmd",
        "--output",
        "--paths",
        "--plugin-dirs",
        "--postprocessor-args",
        "--ppa",
        "--use-postprocessor",
    )

    fun resolve(
        command: String,
        confinementOptionsFollow: Boolean = true,
    ): YtdlpCommandOutputTemplateResolution {
        val tokens = YtdlpCommandTokenizer.tokenize(command)
            ?: return YtdlpCommandOutputTemplateResolution.Invalid("unbalanced shell quoting")
        val templates = linkedMapOf<String, String?>()
        var foundOutputOption = false
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            val ownership = YtdlpOptionOwnership.inspect(tokens, index)

            // The app appends operation-owned -P home/temp after persisted
            // authored options. If authored `--` terminates option parsing,
            // those confinement options become positional arguments instead.
            if (ownership.optionTerminator) {
                if (confinementOptionsFollow) {
                    return YtdlpCommandOutputTemplateResolution.Invalid(
                        "option terminator -- disables app-owned output confinement"
                    )
                }
                break
            }
            val longOptionName = token.takeIf { it.startsWith("--") }?.substringBefore('=')
            if (longOptionName != null &&
                longOptionName !in abbreviationSensitiveOptions &&
                longOptionName !in exactSafeLongOptionsThatPrefixSensitiveOptions &&
                abbreviationSensitiveOptions.any { canonical -> canonical.startsWith(longOptionName) }
            ) {
                return YtdlpCommandOutputTemplateResolution.Invalid(
                    "abbreviated destination-sensitive option is unsupported: $longOptionName"
                )
            }

            if (ownership.recognizedOption) {
                // yt-dlp aliases can expand to -o/-P after this static policy
                // has run, so user-defined aliases are an unmodelled
                // destination route. This check is deliberately made only on
                // an actual option token; an identical string owned by a
                // fixed-arity option is data and was skipped above.
                if (ownership.canonicalName == "--alias") {
                    return YtdlpCommandOutputTemplateResolution.Invalid(
                        "--alias may inject an unmodelled output destination"
                    )
                }
                authoredIndependentWriteOptions.firstOrNull { option ->
                    ownership.canonicalName == option
                }?.let { option ->
                    return YtdlpCommandOutputTemplateResolution.Invalid(
                        "$option writes outside the operation-owned output contract"
                    )
                }
                authoredExecutionAuthorityOptions.firstOrNull { option ->
                    ownership.canonicalName == option
                }?.let { option ->
                    return YtdlpCommandOutputTemplateResolution.Invalid(
                        "$option may execute an untrusted external runtime"
                    )
                }
                authoredOutputSemanticOptions.firstOrNull { option ->
                    ownership.canonicalName == option
                }?.let { option ->
                    return YtdlpCommandOutputTemplateResolution.Invalid(
                        "$option can invalidate pre-execution output confinement"
                    )
                }
            }

            // Python optparse accepts short-option clusters. In particular,
            // -qoVALUE, -qo VALUE, and -vqoVALUE all select -o, while
            // -qP VALUE and -qPVALUE select -P. The shared ownership model
            // has already accounted for any preceding value-taking option.
            if (ownership.target == YtdlpOptionTarget.OUTPUT) {
                if (ownership.missingValueCount > 0 || ownership.firstValue == null) {
                    return YtdlpCommandOutputTemplateResolution.Invalid(
                        "${ownership.canonicalName} is missing its output template"
                    )
                }
                val rawValue = ownership.firstValue
                    ?: return YtdlpCommandOutputTemplateResolution.Invalid(
                        "${ownership.canonicalName} is missing its output template"
                    )
                val parsed = parseOutputValue(rawValue)
                    ?: return YtdlpCommandOutputTemplateResolution.Invalid(
                        "unsupported or malformed ${ownership.canonicalName} template: $rawValue"
                    )
                foundOutputOption = true
                parsed.keys.forEach { key -> templates[key] = parsed.template }
                index += ownership.nextIndexDelta
                continue
            }

            if (ownership.recognizedOption || ownership.ambiguousOption) {
                if (ownership.recognizedOption && ownership.missingValueCount > 0) {
                    return YtdlpCommandOutputTemplateResolution.Invalid(
                        "${ownership.canonicalName} is missing its value"
                    )
                }
                index += ownership.nextIndexDelta
                continue
            }

            index += 1
        }

        if (!foundOutputOption) return YtdlpCommandOutputTemplateResolution.None
        templates.forEach { (key, template) ->
            validateEffectiveTemplate(key, template)?.let { reason ->
                return YtdlpCommandOutputTemplateResolution.Invalid(reason)
            }
        }
        return YtdlpCommandOutputTemplateResolution.Explicit(templates.toMap())
    }

    /** Validates one app-generated template through the same confinement rules. */
    fun validateGeneratedTemplate(template: String): String? =
        validateEffectiveTemplate("default", template)

    private data class ParsedOutputValue(
        val keys: List<String>,
        val template: String?,
    )

    private fun parseOutputValue(rawValue: String): ParsedOutputValue? {
        val colonIndex = rawValue.indexOf(':')
        val candidateKeys = if (colonIndex > 0) {
            rawValue.substring(0, colonIndex)
                .split(',')
                .map { it.trim().lowercase() }
        } else {
            emptyList()
        }
        val isTyped = candidateKeys.isNotEmpty() &&
            candidateKeys.all { it in YTDLP_OUTPUT_TYPE_KEYS }
        val keys = if (isTyped) candidateKeys else listOf("default")
        val template = if (isTyped) rawValue.substring(colonIndex + 1) else rawValue

        // yt-dlp documents typed-empty values (for example `thumbnail:`) as
        // suppression. Keep that semantic explicit instead of accidentally
        // treating the empty string as a filesystem destination.
        if (isTyped && template.isEmpty()) {
            return ParsedOutputValue(keys, null)
        }
        return ParsedOutputValue(keys, template)
    }

    private data class TemplateAnalysis(
        val skeleton: String,
        val fieldKeys: List<String>,
        val formatSuffixes: List<String>,
    )

    private fun validateEffectiveTemplate(key: String, template: String?): String? {
        if (template == null) {
            return if (key == "default") {
                "empty default output template is unsupported"
            } else {
                null
            }
        }
        if (template.isEmpty()) return "empty default output template is unsupported"
        if (template == "-") return "stdout output (-o -) is unsupported for file publication"
        if (template.indexOf('\u0000') >= 0) return "NUL is not allowed in an output template"
        if (template.startsWith("~")) {
            return "home-expanded output template is not statically confined: $template"
        }
        if (containsExpandableEnvironmentVariable(template)) {
            return "environment-expanded output template is not statically confined: $template"
        }
        if (File(template).isAbsolute || template.startsWith('/')) {
            return "absolute output template is outside operation-owned staging: $template"
        }

        val analysis = analyzeTemplate(template)
            ?: return "malformed or ambiguous output template: $template"
        val segments = analysis.skeleton.split('/')
        if (segments.any { it == "." || it == ".." }) {
            return "output template contains path traversal: $template"
        }
        if (segments.dropLast(1).any { it.indexOf(DYNAMIC_FIELD) >= 0 }) {
            return "dynamic output directory cannot be proven confined: $template"
        }

        val finalSegment = segments.lastOrNull().orEmpty()
        if (finalSegment.isEmpty()) {
            return "output template does not name a file: $template"
        }
        if (finalSegment.indexOf(DYNAMIC_FIELD) >= 0) {
            val literalResidue = finalSegment.replace(DYNAMIC_FIELD.toString(), "")
            val hasNonDotLiteral = literalResidue.any { it != '.' }
            if (!hasNonDotLiteral && !isConventionalSafeDynamicFilename(finalSegment, analysis)) {
                return "dynamic output filename can resolve to a traversal component: $template"
            }
        }
        return null
    }

    private fun isConventionalSafeDynamicFilename(
        finalSegment: String,
        analysis: TemplateAnalysis,
    ): Boolean {
        // Preserve the common `%(title)s.%(ext)s` family without trusting
        // yt-dlp replacement/default syntax that can deliberately evaluate to
        // `..`. With plain string fields, empty values are replaced by yt-dlp's
        // non-empty NA placeholder before filename sanitization.
        if (finalSegment != "$DYNAMIC_FIELD.$DYNAMIC_FIELD") return false
        if (analysis.fieldKeys.size != 2 || analysis.formatSuffixes.size != 2) return false
        if (analysis.formatSuffixes.any { !SAFE_NONEMPTY_STRING_FORMAT.matches(it) }) return false
        if (analysis.fieldKeys[1] != "ext") return false
        return SIMPLE_OUTPUT_FIELD.matches(analysis.fieldKeys[0])
    }

    private val SIMPLE_OUTPUT_FIELD = Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*""")
    // Width and positive precision preserve at least one character from yt-dlp's
    // non-empty value/NA placeholder. Precision zero can collapse a field to
    // empty and combine with the literal dot into `.` or `..`.
    private val SAFE_NONEMPTY_STRING_FORMAT = Regex("""[#0+ \-]*\d*(?:\.[1-9]\d*)?s""")

    private fun containsExpandableEnvironmentVariable(template: String): Boolean {
        var index = 0
        while (index < template.length) {
            if (template[index] != '$') {
                index += 1
                continue
            }
            if (template.getOrNull(index + 1) == '$') {
                index += 2
                continue
            }
            return true
        }
        return false
    }

    /**
     * Replaces every syntactically valid yt-dlp field with a sentinel while
     * preserving literal path separators. This is intentionally narrower than
     * yt-dlp's formatter: unfamiliar/malformed forms fail closed rather than
     * being guessed safe.
     */
    private fun analyzeTemplate(template: String): TemplateAnalysis? {
        val skeleton = StringBuilder()
        val fields = mutableListOf<String>()
        val formatSuffixes = mutableListOf<String>()
        var index = 0
        while (index < template.length) {
            if (template[index] != '%') {
                skeleton.append(template[index])
                index += 1
                continue
            }
            if (template.getOrNull(index + 1) == '%') {
                skeleton.append('%')
                index += 2
                continue
            }
            if (template.getOrNull(index + 1) != '(') {
                // yt-dlp escapes unmatched old-style percent sequences into
                // literals. They carry no dynamic path authority.
                skeleton.append('%')
                index += 1
                continue
            }

            val close = template.indexOf(')', startIndex = index + 2)
            if (close < 0) return null
            val fieldKey = template.substring(index + 2, close)
            if (fieldKey.isBlank()) return null

            val formatEnd = parseFormatEnd(template, close + 1) ?: return null

            fields += fieldKey.trim()
            formatSuffixes += template.substring(close + 1, formatEnd)
            skeleton.append(DYNAMIC_FIELD)
            index = formatEnd
        }
        return TemplateAnalysis(skeleton.toString(), fields, formatSuffixes)
    }

    /** Mirrors the bundled yt-dlp 2025.11.12 external %-format suffix grammar. */
    private fun parseFormatEnd(template: String, startIndex: Int): Int? {
        var index = startIndex
        while (index < template.length && template[index] in conversionFlags) index += 1
        while (index < template.length && template[index].isDigit()) index += 1
        if (template.getOrNull(index) == '.') {
            index += 1
            val precisionStart = index
            while (index < template.length && template[index].isDigit()) index += 1
            if (index == precisionStart) return null
        }

        // STR_FORMAT_RE_TMPL has an optional [hlL] length modifier before the
        // conversion. Treat it as a modifier only when a conversion follows;
        // otherwise `l`/`h` may themselves be yt-dlp conversion characters.
        if (template.getOrNull(index) in lengthModifiers &&
            template.getOrNull(index + 1) in conversionCharacters
        ) {
            index += 1
        }
        if (template.getOrNull(index) !in conversionCharacters) return null
        return index + 1
    }
}

internal object YtdlpOutputPlanToken {
    fun forDownload(downloadId: Long, executionId: String, operationId: String): String {
        val seed = "$downloadId:${executionId.ifBlank { operationId }}"
        return UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    }
}
