package com.ireum.ytdl.util.terminal

import com.ireum.ytdl.util.extractors.ytdlp.YtdlpArgumentPolicy
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandPathParser
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandPathResolution

/**
 * Terminal-owned durable command metadata: the versioned command-format marker
 * and the combined strip of every Terminal-owned option.
 *
 * The marker exists because a durably complete record and a durably incomplete
 * record can be byte-identical. Before the provider authority was materialized
 * into the command, a Terminal row could be perfectly consistent and still be
 * missing the one dimension that decides where its output is published. Exact
 * equality of such a record is not proof of the original provider, and it must
 * not be completed from the current mutable preference.
 *
 * The marker makes the current format explicit in the durable command itself.
 * It is Terminal-owned, is removed before any yt-dlp parser or the native
 * process can observe it, and is part of the command fingerprint, so it
 * inherits the exact durable dispatch ownership contract.
 */
/**
 * The exact failure surface of Terminal-owned command metadata parsing.
 *
 * Malformed or repeated Terminal-owned metadata is refused rather than
 * repaired.  Command composition stays strict and throws this, while durable
 * recovery classifies it into an explicit non-authoritative disposition.  It is
 * a distinct type so recovery can catch precisely this surface and never hide an
 * unrelated database, transaction, admission, cancellation or scheduler failure.
 */
class TerminalCommandMetadataException(message: String) : IllegalArgumentException(message)

object TerminalCommandMetadata {
    const val COMMAND_FORMAT_OPTION = "--ytdlnisx-terminal-command-format"

    /** The only durable command format this implementation writes or trusts. */
    const val CURRENT_FORMAT = "1"

    /**
     * Dispatch format generation of a pre-materializer Terminal carrier.
     *
     * Rows and carriers written before the materializer exist carry this
     * generation, and their command text was stored exactly as the user typed
     * it.
     */
    const val LEGACY_FORMAT_GENERATION = 1L

    /**
     * Dispatch format generation written only by the current writer.
     *
     * This is the durable generation proof. It lives in the TERMINAL_DISPATCH
     * carrier's own field, which the app writes, so historical command text
     * cannot forge it: the same bytes were legal user input before the format
     * marker had any meaning.
     */
    const val CURRENT_FORMAT_GENERATION = 2L

    private val PATTERN = Regex(
        "(?<!\\S)" + Regex.escape(COMMAND_FORMAT_OPTION) + "(?:=(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+))|\\s+(?:\"([^\"]*)\"|'([^']*)'|(\\S+)))",
    )

    /** Detects the option appearing as a bare token with no usable value. */
    private val BARE_TOKEN = Regex(
        "(?<!\\S)" + Regex.escape(COMMAND_FORMAT_OPTION) + "(?=\\s|=|$)",
    )

    /** Renders the current-format marker for one new Terminal command. */
    fun renderCommandFormat(format: String = CURRENT_FORMAT): String =
        "$COMMAND_FORMAT_OPTION=$format"

    /** A command with every Terminal-owned option removed. */
    data class Stripped(
        val command: String,
        val providerTreeUri: String?,
        val currentFormat: Boolean,
    )

    /**
     * Removes every Terminal-owned option from [command].
     *
     * Malformed or repeated metadata is refused rather than repaired, so an
     * unusable value can never be silently reinterpreted.
     */
    fun strip(command: String): Stripped {
        val formatMatches = PATTERN.findAll(command).toList()
        // A bare occurrence carries no value and must not be read as an
        // absent option, so it is refused instead of guessed.
        if (BARE_TOKEN.findAll(command).count() != formatMatches.size) {
            throw TerminalCommandMetadataException(
                "Terminal command format is not a usable value",
            )
        }
        val format = when {
            formatMatches.isEmpty() -> null
            // More than one format marker is ambiguous and refused.
            formatMatches.size > 1 -> throw TerminalCommandMetadataException(
                "Terminal command declares more than one command format",
            )
            else -> formatMatches.single().groupValues.drop(1).firstOrNull { it.isNotEmpty() }
                ?: throw TerminalCommandMetadataException(
                    "Terminal command format is not a usable value",
                )
        }
        // Remove the format marker first, then the provider destination, so
        // each removal operates on the result of the previous one and neither
        // Terminal-owned option survives.
        // An unrecognized format value is refused rather than treated as absent.
        // This app only ever writes CURRENT_FORMAT, so any other value is not
        // app-authored, and silently dropping it would strip unusable metadata
        // and let the remainder execute.
        if (format != null && format != CURRENT_FORMAT) {
            throw TerminalCommandMetadataException(
                "Terminal command declares an unsupported command format",
            )
        }
        val withoutFormat = if (formatMatches.isEmpty()) {
            command
        } else {
            command
                .removeRange(formatMatches.single().range)
                .replace(Regex(" +"), " ")
                .trim()
        }
        val provider = TerminalProviderDestinationOption.extract(withoutFormat)
        // Terminal-owned provider metadata is only self-bound authority when it
        // actually names a provider tree.  A present-but-unusable value is
        // malformed app-owned metadata: it is never a provider, and it must
        // never be reinterpreted as a raw path or resolved from the current
        // preference.  Refusing here also keeps current composition strict,
        // because a durable command containing one can never be admitted.
        provider.providerTreeUri?.let { value ->
            if (TerminalDestinationAuthority.classify(value) !is
                TerminalDestinationAuthority.ProviderTree
            ) {
                throw TerminalCommandMetadataException(
                    "Terminal provider destination is not a provider tree",
                )
            }
        }
        return Stripped(
            command = provider.command,
            providerTreeUri = provider.providerTreeUri,
            currentFormat = format == CURRENT_FORMAT,
        )
    }

    /**
     * Whether a durably stored command carries proof of the output authority it
     * will execute under.
     */
    sealed interface DurableAuthority {
        /**
         * Written by this implementation. The destination was materialized into
         * the command before the row and carrier were staged, and the carrier
         * independently proves the current dispatch format generation, so the
         * durable identity is complete.
         */
        data object CurrentFormat : DurableAuthority

        /**
         * A pre-materializer command that already carries its own exact output
         * authority, either explicit provider metadata or a valid authored
         * native home destination. It stays runnable under the existing
         * contract.
         */
        data object SelfBound : DurableAuthority

        /**
         * A pre-materializer command with neither. Its original configured
         * provider is unrecoverable from the durable representation, so it must
         * never inherit authority from the current preference.
         */
        data object Ambiguous : DurableAuthority
    }

    /**
     * A durable classification that never throws.
     *
     * Already-durable state may legitimately contain Terminal-owned metadata
     * that this implementation would refuse to compose, because those bytes were
     * legal user input before they had any application meaning.  Recovery must
     * still reach a decision for such a row instead of aborting the batch it is
     * reconciling, so malformed metadata becomes an explicit non-authoritative
     * disposition rather than an exception.
     */
    sealed interface DurableClassification {
        /** The command could be parsed; [authority] carries the decision. */
        data class Parsed(val authority: DurableAuthority) : DurableClassification

        /**
         * Terminal-owned metadata in this durable command is malformed,
         * repeated, or unusable.  It is never current format, never self-bound
         * by guessing around the defect, and never resolved from the current
         * preference.
         */
        data class Malformed(val reason: String) : DurableClassification
    }

    /**
     * Classifies an already-durable command without propagating a metadata parse
     * failure.
     *
     * Only [TerminalCommandMetadataException] is caught.  Any other failure is a
     * genuine infrastructure or programming fault and is deliberately allowed to
     * propagate rather than being reported as malformed Terminal metadata.
     */
    fun classifyDurableResult(
        command: String,
        formatGeneration: Long,
    ): DurableClassification = try {
        DurableClassification.Parsed(classifyDurable(command, formatGeneration))
    } catch (malformed: TerminalCommandMetadataException) {
        DurableClassification.Malformed(
            malformed.message ?: "Terminal-owned command metadata is malformed",
        )
    }

    /**
     * Classifies one durably stored Terminal command against the independently
     * durable [formatGeneration] proven by its carrier.
     *
     * The marker alone is never proof.  The command string is user-controlled
     * text, and the exact marker bytes were legal input before the marker had any
     * application meaning, so a current-format classification additionally
     * requires a carrier written at [CURRENT_FORMAT_GENERATION].
     *
     * Generation is deliberately not a blanket consistency requirement: a
     * pre-materializer command that carries its own exact authority stays
     * self-bound whatever generation its carrier records, and marker text must
     * neither upgrade it to current format nor destroy that authority.
     *
     * This is only meaningful for a command that is already durable. A command
     * being composed for the first time has no format marker yet and is
     * materialized before it is ever stored.
     */
    fun classifyDurable(command: String, formatGeneration: Long): DurableAuthority {
        val stripped = strip(command)
        if (stripped.currentFormat &&
            formatGeneration >= CURRENT_FORMAT_GENERATION
        ) {
            return DurableAuthority.CurrentFormat
        }
        // Otherwise the row predates this writer, and only its own command text
        // can still carry authority.  Marker text never outranks that.
        if (stripped.providerTreeUri != null) return DurableAuthority.SelfBound
        // An authored native home destination is its own exact authority.
        if (declaresAuthoredNativeHome(stripped.command)) return DurableAuthority.SelfBound
        return DurableAuthority.Ambiguous
    }

    private fun declaresAuthoredNativeHome(command: String): Boolean {
        val sanitized = runCatching {
            YtdlpArgumentPolicy.stripExternalFfmpegLocationOptionsWithReport(command)
        }.getOrNull() ?: return false
        val resolution = runCatching {
            YtdlpCommandPathParser.resolve(sanitized.commandString)
        }.getOrNull() ?: return false
        val pathMap = (resolution as? YtdlpCommandPathResolution.Explicit)?.pathMap
        return pathMap?.home != null
    }
}
