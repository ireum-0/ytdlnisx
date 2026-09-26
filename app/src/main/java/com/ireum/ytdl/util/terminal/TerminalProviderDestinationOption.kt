package com.ireum.ytdl.util.terminal

/**
 * App-owned Terminal command metadata for a Folder-picked provider
 * destination.
 *
 * The Terminal Folder picker used to serialize a provider selection as
 * `FileUtil.formatPath(content://...)`, which invented a raw pathname that
 * never existed and then let the planner treat it as an authored native `-P`.
 * The selection is instead carried as one structured, versioned Terminal-owned
 * option that the planner removes before the command reaches any yt-dlp parser
 * or the native process.
 *
 * Carrying it inside the command is deliberate.  `TerminalItem.command` is
 * already the durable Terminal semantic identity: it is copied into the
 * TERMINAL_DISPATCH carrier payload and covered by
 * `terminalCommandFingerprint`, and the worker refuses any request whose
 * command or fingerprint is not the current one.  A per-command provider
 * destination therefore inherits exact durable binding and stale-request
 * separation without a Room schema migration or a second scheduler.
 */
object TerminalProviderDestinationOption {
    const val OPTION = "--ytdlnisx-terminal-provider-destination"

    private val PATTERN = Regex(
        "(?<!\\S)" + Regex.escape(OPTION) + "(?:=|\\s+)" +
            "(?:\"([^\"]*)\"|'([^']*)'|(\\S+))",
    )

    /** Renders the structured option for one exact provider tree grant. */
    fun render(treeUri: String): String {
        val trimmed = treeUri.trim()
        if (trimmed.isEmpty()) {
            throw TerminalCommandMetadataException(
                "Terminal provider destination requires a tree URI",
            )
        }
        return "$OPTION=$trimmed"
    }

    /** The command with every Terminal-owned provider option removed. */
    data class Extracted(
        val command: String,
        val providerTreeUri: String?,
    )

    /**
     * Removes the Terminal-owned provider option from [command].
     *
     * More than one occurrence is ambiguous and refused rather than resolved
     * by picking a winner.  A malformed or empty value is refused here so an
     * unusable provider destination never degrades into a raw path.
     */
    fun extract(command: String): Extracted {
        val matches = PATTERN.findAll(command).toList()
        if (matches.isEmpty()) return Extracted(command, null)
        if (matches.size > 1) {
            throw TerminalCommandMetadataException(
                "Terminal command declares more than one provider destination",
            )
        }
        val value = matches.single().groupValues.drop(1).firstOrNull { it.isNotEmpty() }
            ?: throw TerminalCommandMetadataException(
                "Terminal provider destination is not a usable location",
            )
        val remaining = command
            .removeRange(matches.single().range)
            .replace(Regex(" +"), " ")
            .trim()
        return Extracted(remaining, value)
    }
}
