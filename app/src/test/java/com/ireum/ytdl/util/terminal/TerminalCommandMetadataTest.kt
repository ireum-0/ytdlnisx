package com.ireum.ytdl.util.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A durably complete record and a durably incomplete one can be byte-identical.
 * These cases pin the distinction the durable dispatch gates rely on, so a
 * pre-materializer command can never be completed from the current preference.
 */
class TerminalCommandMetadataTest {
    private val command = "https://example.com/video"
    private val providerA = "content://com.android.externalstorage.documents/tree/primary%3AA"
    private val providerC = "content://com.android.externalstorage.documents/tree/primary%3AC"

    @Test
    fun everyTerminalOwnedOptionIsStrippedBeforeParsing() {
        val durable = TerminalCommandIntentMaterializer.materialize(command, providerA)

        val stripped = TerminalCommandMetadata.strip(durable)

        assertEquals(command, stripped.command)
        assertEquals(providerA, stripped.providerTreeUri)
        assertTrue(stripped.currentFormat)
        // Neither Terminal-owned option may survive into the native command.
        assertFalse(stripped.command.contains(TerminalCommandMetadata.COMMAND_FORMAT_OPTION))
        assertFalse(stripped.command.contains(TerminalProviderDestinationOption.OPTION))
    }

    @Test
    fun currentFormatIsRecognizedAndClassifiedAsComplete() {
        val durable = TerminalCommandIntentMaterializer.materialize(command, providerA)

        assertEquals(
            TerminalCommandMetadata.DurableAuthority.CurrentFormat,
            TerminalCommandMetadata.classifyDurable(durable),
        )
    }

    @Test
    fun currentFormatRawRowIsNotBlocked() {
        // A raw/default row created through the corrected boundary must keep
        // working; the legacy fence may not block commands merely for lacking
        // provider metadata.
        val durable = TerminalCommandIntentMaterializer.materialize(
            command,
            "/storage/emulated/0/YTDLnisX/Command",
        )

        assertEquals(
            TerminalCommandMetadata.DurableAuthority.CurrentFormat,
            TerminalCommandMetadata.classifyDurable(durable),
        )
    }

    @Test
    fun preMaterializerCommandWithoutAnyAuthorityIsAmbiguous() {
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.Ambiguous,
            TerminalCommandMetadata.classifyDurable(command),
        )
    }

    @Test
    fun preMaterializerExplicitProviderIsSelfBound() {
        val legacy = TerminalProviderDestinationOption.render(providerC) + " $command"

        assertEquals(
            TerminalCommandMetadata.DurableAuthority.SelfBound,
            TerminalCommandMetadata.classifyDurable(legacy),
        )
        assertEquals(providerC, TerminalCommandMetadata.strip(legacy).providerTreeUri)
    }

    @Test
    fun preMaterializerAuthoredNativeDestinationIsSelfBound() {
        val legacy = "-P /storage/emulated/0/Custom $command"

        assertEquals(
            TerminalCommandMetadata.DurableAuthority.SelfBound,
            TerminalCommandMetadata.classifyDurable(legacy),
        )
    }

    @Test
    fun unknownFormatMarkerIsNotTrustedAsCurrentFormat() {
        val foreign = "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION}=99 $command"

        // An unrecognized format is not the current format, and it carries no
        // destination authority of its own.
        assertFalse(TerminalCommandMetadata.strip(foreign).currentFormat)
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.Ambiguous,
            TerminalCommandMetadata.classifyDurable(foreign),
        )
    }

    @Test
    fun malformedOrRepeatedMetadataStaysFailClosed() {
        val repeated = TerminalCommandMetadata.renderCommandFormat() + " " +
            TerminalCommandMetadata.renderCommandFormat() + " $command"
        assertTrue(runCatching { TerminalCommandMetadata.strip(repeated) }.isFailure)

        val empty = "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION}= $command"
        assertTrue(runCatching { TerminalCommandMetadata.strip(empty) }.isFailure)
    }

    @Test
    fun formatMarkerParticipatesInTheDurableCommandIdentity() {
        val providerBound =
            TerminalCommandIntentMaterializer.materialize(command, providerA)
        val rawBound = TerminalCommandIntentMaterializer.materialize(
            command,
            providerC,
        )

        // The same user command bound to different providers is a different
        // exact durable identity.
        assertTrue(providerBound != rawBound)
    }
}
