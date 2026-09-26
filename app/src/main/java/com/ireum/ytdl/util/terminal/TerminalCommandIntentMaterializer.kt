package com.ireum.ytdl.util.terminal

import com.ireum.ytdl.util.extractors.ytdlp.YtdlpArgumentPolicy
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandPathParser
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpCommandPathResolution

/**
 * Materializes the exact durable Terminal command before it is stored.
 *
 * A configured provider `command_path` used to be resolved only at planning
 * time, after the Terminal row and its dispatch carrier were already durable.
 * A preference change between insert and execution therefore redirected an
 * already-durable Terminal intent onto a newer archive, because the durable
 * command/fingerprint never mentioned the destination at all.
 *
 * This boundary snapshots that configured [ProviderTree] authority into the
 * command itself, using the Terminal-owned provider metadata, so the resolved
 * destination becomes part of the exact durable semantic identity.  It
 * deliberately reuses the carrier that is already closed and owned by the
 * Terminal dispatch contract, rather than introducing a second persistence
 * mechanism or scheduler.
 */
object TerminalCommandIntentMaterializer {
    /**
     * Returns the exact command that must be inserted into the Terminal row and
     * staged into TERMINAL_DISPATCH.
     *
     * Precedence is preserved exactly:
     * `authored native --paths` > `explicit provider metadata` >
     * `configured provider default` > `configured raw/default destination`.
     */
    fun materialize(command: String, configuredCommandPath: String): String {
        // Malformed or repeated provider metadata stays fail-closed under the
        // existing parser contract rather than being silently repaired.
        val existing = TerminalProviderDestinationOption.extract(command).providerTreeUri
        if (existing != null) {
            // The selection is preserved exactly, but only when it is a usable
            // provider authority.  A malformed value is refused here instead of
            // becoming durable and failing later at execution.
            if (TerminalDestinationAuthority.providerTreeOrNull(
                    TerminalDestinationAuthority.classify(existing),
                ) == null
            ) {
                throw IllegalArgumentException(
                    "Terminal provider destination is not a usable location",
                )
            }
            // An explicit selection is more specific than the configured
            // default and is preserved exactly as supplied.
            return command
        }
        // A manual authored native destination already governs output, so the
        // configured default must not be injected as competing authority.
        if (declaresAuthoredOutputPath(command)) return command
        // Only a provider default becomes durable metadata.  A raw or default
        // destination keeps its existing behavior and is not frozen here.
        val configured = TerminalDestinationAuthority.classify(configuredCommandPath)
        if (TerminalDestinationAuthority.providerTreeOrNull(configured) == null) return command
        return materializeProviderAuthority(command, configuredCommandPath)
    }

    /**
     * True when the command itself already names a native output destination.
     *
     * This mirrors the planner's own `configDeclaresOutputPath` decision so the
     * materialized command and the executed plan can never disagree about
     * which destination governs output.
     */
    private fun declaresAuthoredOutputPath(command: String): Boolean {
        val sanitized = runCatching {
            YtdlpArgumentPolicy.stripExternalFfmpegLocationOptionsWithReport(command)
        }.getOrNull() ?: return false
        val pathResolution = runCatching {
            YtdlpCommandPathParser.resolve(sanitized.commandString)
        }.getOrNull() ?: return false
        // An unparseable path is left for the planner to refuse with its own
        // diagnostic; it must not gain injected output authority here.
        return pathResolution is YtdlpCommandPathResolution.Explicit
    }

    /**
     * Renders the configured provider authority as Terminal-owned metadata.
     *
     * Only the single structured option is added, so the exact provider URI
     * survives process death and later planning removes it before any yt-dlp
     * parser or the native process can observe it.
     */
    private fun materializeProviderAuthority(command: String, treeUri: String): String {
        val option = TerminalProviderDestinationOption.render(treeUri)
        val trimmed = command.trim()
        return if (trimmed.isEmpty()) option else "$option $trimmed"
    }
}
