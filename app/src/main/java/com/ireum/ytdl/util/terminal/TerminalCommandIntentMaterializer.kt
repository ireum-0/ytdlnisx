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
        // Start from a fully stripped command so this is idempotent and a
        // repeated materialization can never append a second format marker,
        // which the durable classifier would refuse.
        val stripped = TerminalCommandMetadata.strip(command)
        val existingProvider = stripped.providerTreeUri
        val body = when {
            existingProvider != null -> {
                // The selection is preserved exactly, but only when it is a
                // usable provider authority.  A malformed value is refused here
                // instead of becoming durable and failing later at execution.
                if (TerminalDestinationAuthority.providerTreeOrNull(
                        TerminalDestinationAuthority.classify(existingProvider),
                    ) == null
                ) {
                    throw IllegalArgumentException(
                        "Terminal provider destination is not a usable location",
                    )
                }
                // An explicit selection is more specific than the configured
                // default.
                listOfNotNull(
                    TerminalProviderDestinationOption.render(existingProvider),
                    stripped.command.trim().takeIf(String::isNotEmpty),
                ).joinToString(" ")
            }
            // A manual authored native destination already governs output, so
            // the configured default must not be injected as competing
            // authority.
            declaresAuthoredOutputPath(stripped.command) -> stripped.command
            else -> {
                // Only a provider default becomes durable metadata.  A raw or
                // default destination keeps its existing behavior and is not
                // frozen here.
                val configured = TerminalDestinationAuthority.classify(configuredCommandPath)
                val provider = TerminalDestinationAuthority.providerTreeOrNull(configured)
                    ?: return stampFormat(stripped.command)
                listOfNotNull(
                    TerminalProviderDestinationOption.render(provider.treeUri),
                    stripped.command.trim().takeIf(String::isNotEmpty),
                ).joinToString(" ")
            }
        }
        return stampFormat(body)
    }

    /**
     * Marks one newly created command as the current durable format.
     *
     * Every new row is stamped, not only provider-bound ones, so the persisted
     * generation is explicit for raw and default destinations too.  Otherwise a
     * legacy incomplete record would be indistinguishable from a complete
     * current one, and fixing that ambiguity by blocking commands without
     * provider metadata would block valid raw Terminal work as well.
     */
    private fun stampFormat(command: String): String {
        val marker = TerminalCommandMetadata.renderCommandFormat()
        val trimmed = command.trim()
        return if (trimmed.isEmpty()) marker else "$marker $trimmed"
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
}
