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

    fun consumesFollowingToken(token: String): Boolean {
        if (!token.startsWith('-') || token.startsWith("--") || token.length < 2) {
            return false
        }
        val cluster = token.substring(1)
        cluster.forEachIndexed { index, option ->
            if (option in VALUE_TAKING_OPTIONS) {
                return index == cluster.lastIndex
            }
            if (option !in NO_VALUE_OPTIONS) return false
        }
        return false
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
    fun resolve(command: String): YtdlpCommandPathResolution {
        val tokens = YtdlpCommandTokenizer.tokenize(command)
            ?: return YtdlpCommandPathResolution.Invalid("unbalanced shell quoting")
        val paths = linkedMapOf<String, File>()
        var foundPathOption = false
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token == "--") break

            val clusteredPath = YtdlpShortOptionClusterParser.match(
                token = token,
                followingToken = tokens.getOrNull(index + 1),
                target = 'P',
            )
            if (clusteredPath != null) {
                val match = clusteredPath
                val value = match.value
                    ?: return YtdlpCommandPathResolution.Invalid("-P is missing its path")
                val parsed = parsePathMapValue(value)
                    ?: return YtdlpCommandPathResolution.Invalid(
                        "unsupported or malformed -P path: $value"
                    )
                foundPathOption = true
                parsed.keys.forEach { key -> paths[key] = parsed.directory }
                if (match.consumesFollowingToken) index += 1
                index += 1
                continue
            }

            // A path-looking token can be the required value of a preceding
            // option. Never grant final-destination authority from that shape.
            // These value-taking option tables mirror the bundled yt-dlp
            // 2025.11.12 optparse surface; exact no-value options that are
            // prefixes of value-taking options are handled explicitly below.
            if (!isPathOptionToken(token) && consumesFollowingToken(token)) {
                if (tokens.getOrNull(index + 1) == null) {
                    return YtdlpCommandPathResolution.Invalid("$token is missing its value")
                }
                index += 2
                continue
            }

            val optionAndValue = when {
                isLongPathsOptionToken(token) && !token.contains('=') -> {
                    val value = tokens.getOrNull(index + 1)
                        ?: return YtdlpCommandPathResolution.Invalid("$token is missing its path")
                    index += 1
                    "--paths" to value
                }
                isLongPathsOptionToken(token) && token.contains('=') ->
                    "--paths" to token.substringAfter('=')
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

    // Generated from the bundled yt-dlp 2025.11.12 OptionParser. This is
    // deliberately scoped to options that consume the following token because
    // only those can make a later-looking -P/--paths token cease to be an
    // option. Keep in sync when the bundled yt-dlp version changes.
    private val VALUE_TAKING_LONG_OPTIONS = setOf(
        "--add-headers",
        "--age-limit",
        "--alias",
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
        "--output",
        "--output-na-placeholder",
        "--parse-metadata",
        "--password",
        "--paths",
        "--playlist-end",
        "--playlist-items",
        "--playlist-start",
        "--plugin-dirs",
        "--postprocessor-args",
        "--ppa",
        "--preset-alias",
        "--print",
        "--print-to-file",
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
        "--replace-in-metadata",
        "--retries",
        "--retry-sleep",
        "--skip-playlist-after-errors",
        "--sleep-interval",
        "--sleep-requests",
        "--sleep-subtitles",
        "--socket-timeout",
        "--source-address",
        "--sponskrub-args",
        "--sponskrub-location",
        "--sponsorblock-api",
        "--sponsorblock-chapter-title",
        "--sponsorblock-mark",
        "--sponsorblock-remove",
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

    // optparse resolves an exact option before considering long-option
    // abbreviations. These five no-value options are exact prefixes of a
    // value-taking option in the bundled parser and therefore do not consume
    // the following token.
    private val EXACT_NO_VALUE_PREFIXES = setOf(
        "--geo-bypass",
        "--netrc",
        "--progress",
        "--sponskrub",
        "--update",
    )

    internal fun consumesFollowingToken(token: String): Boolean {
        if (token == "-" || token == "--" || token.contains('=')) return false
        if (token.startsWith("--")) {
            if (token in EXACT_NO_VALUE_PREFIXES) return false
            if (token in VALUE_TAKING_LONG_OPTIONS) return true
            // yt-dlp accepts unambiguous long-option abbreviations. If a token
            // is a prefix of a value-taking option, treating the next token as
            // consumed is conservative for ambiguous abbreviations too and,
            // critically, prevents false path authority.
            return VALUE_TAKING_LONG_OPTIONS.any { canonical -> canonical.startsWith(token) }
        }
        if (!token.startsWith('-') || token.length < 2) return false

        // Python optparse accepts short-option clusters. Once a value-taking
        // short option is encountered, the remainder of the same token is its
        // value; only when it is the final character does it consume the next
        // token.
        return YtdlpShortOptionClusterParser.consumesFollowingToken(token)
    }

    private fun isPathOptionToken(token: String): Boolean =
        isLongPathsOptionToken(token) ||
            YtdlpShortOptionClusterParser.match(token, null, 'P') != null

    private fun isLongPathsOptionToken(token: String): Boolean {
        val optionName = token.substringBefore('=')
        if (!optionName.startsWith("--")) return false
        if (optionName == "--paths") return true
        return VALUE_TAKING_LONG_OPTIONS.count { canonical -> canonical.startsWith(optionName) } == 1 &&
            VALUE_TAKING_LONG_OPTIONS.any { canonical -> canonical == "--paths" && canonical.startsWith(optionName) }
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

            // The app appends operation-owned -P home/temp after persisted
            // authored options. If authored `--` terminates option parsing,
            // those confinement options become positional arguments instead.
            if (token == "--") {
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

            // yt-dlp aliases can expand to -o/-P after this static policy has
            // run, so user-defined aliases are an unmodelled destination route.
            if (token == "--alias" || token.startsWith("--alias=")) {
                return YtdlpCommandOutputTemplateResolution.Invalid(
                    "--alias may inject an unmodelled output destination"
                )
            }
            authoredIndependentWriteOptions.firstOrNull { option ->
                token == option || token.startsWith("$option=")
            }?.let { option ->
                return YtdlpCommandOutputTemplateResolution.Invalid(
                    "$option writes outside the operation-owned output contract"
                )
            }
            authoredExecutionAuthorityOptions.firstOrNull { option ->
                token == option || token.startsWith("$option=")
            }?.let { option ->
                return YtdlpCommandOutputTemplateResolution.Invalid(
                    "$option may execute an untrusted external runtime"
                )
            }
            authoredOutputSemanticOptions.firstOrNull { option ->
                token == option || token.startsWith("$option=")
            }?.let { option ->
                return YtdlpCommandOutputTemplateResolution.Invalid(
                    "$option can invalidate pre-execution output confinement"
                )
            }

            // Python optparse accepts short-option clusters. In particular,
            // `-qoVALUE`, `-qo VALUE`, and `-vqoVALUE` all select -o, while
            // `-qP VALUE` and `-qPVALUE` select -P. The output policy must
            // inspect the effective -o rather than treating these as opaque
            // unknown flags before native execution.
            val clusteredOutput = YtdlpShortOptionClusterParser.match(
                token = token,
                followingToken = tokens.getOrNull(index + 1),
                target = 'o',
            )
            if (clusteredOutput == null &&
                token != "--output" &&
                !token.startsWith("--output=") &&
                YtdlpCommandPathParser.consumesFollowingToken(token)
            ) {
                if (tokens.getOrNull(index + 1) == null) {
                    return YtdlpCommandOutputTemplateResolution.Invalid(
                        "$token is missing its value"
                    )
                }
                index += 2
                continue
            }
            if (clusteredOutput != null) {
                val rawValue = clusteredOutput.value
                    ?: return YtdlpCommandOutputTemplateResolution.Invalid(
                        "-o is missing its output template"
                    )
                val parsed = parseOutputValue(rawValue)
                    ?: return YtdlpCommandOutputTemplateResolution.Invalid(
                        "unsupported or malformed -o template: $rawValue"
                    )
                foundOutputOption = true
                parsed.keys.forEach { key -> templates[key] = parsed.template }
                if (clusteredOutput.consumesFollowingToken) index += 1
                index += 1
                continue
            }

            val optionAndValue = when {
                token == "--output" -> {
                    val value = tokens.getOrNull(index + 1)
                        ?: return YtdlpCommandOutputTemplateResolution.Invalid(
                            "$token is missing its output template"
                        )
                    index += 1
                    token to value
                }
                token.startsWith("--output=") ->
                    "--output" to token.substringAfter('=')
                else -> null
            }

            if (optionAndValue != null) {
                val (option, rawValue) = optionAndValue
                val parsed = parseOutputValue(rawValue)
                    ?: return YtdlpCommandOutputTemplateResolution.Invalid(
                        "unsupported or malformed $option template: $rawValue"
                    )
                foundOutputOption = true
                parsed.keys.forEach { key -> templates[key] = parsed.template }
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
