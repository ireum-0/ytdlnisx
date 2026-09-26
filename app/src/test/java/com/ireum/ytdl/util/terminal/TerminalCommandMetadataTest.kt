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
            TerminalCommandMetadata.classifyDurable(
                durable,
                TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
            ),
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
            TerminalCommandMetadata.classifyDurable(
                durable,
                TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
            ),
        )
    }

    @Test
    fun preMaterializerCommandWithoutAnyAuthorityIsAmbiguous() {
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.Ambiguous,
            TerminalCommandMetadata.classifyDurable(
                command,
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
            ),
        )
    }

    /**
     * The marker lives in user-controlled command text, so on its own it is
     * never proof that this writer created the row.
     */
    @Test
    fun markerBytesOnALegacyGenerationAreNotProofOfTheCurrentFormat() {
        val historicalMarker =
            "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION}=${TerminalCommandMetadata.CURRENT_FORMAT} " +
                command

        assertEquals(
            TerminalCommandMetadata.DurableAuthority.Ambiguous,
            TerminalCommandMetadata.classifyDurable(
                historicalMarker,
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
            ),
        )
    }

    @Test
    fun markerTextNeitherUpgradesNorDestroysLegacySelfBoundAuthority() {
        val historicalMarker =
            "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION}=${TerminalCommandMetadata.CURRENT_FORMAT} "

        // Marker text on a generation-1 carrier is not current format, so it can
        // never be the generation proof by itself.
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.Ambiguous,
            TerminalCommandMetadata.classifyDurable(
                historicalMarker + command,
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
            ),
        )
        // The very same marker plus its own exact provider authority stays
        // self-bound, so generation is not a blanket consistency requirement.
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.SelfBound,
            TerminalCommandMetadata.classifyDurable(
                historicalMarker + TerminalProviderDestinationOption.render(providerC) + " $command",
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
            ),
        )
        // And the current generation does make the same marker current format.
        assertEquals(
            TerminalCommandMetadata.DurableAuthority.CurrentFormat,
            TerminalCommandMetadata.classifyDurable(
                historicalMarker + command,
                TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
            ),
        )
    }

    @Test
    fun legacyMarkerWithExactProviderMetadataStaysSelfBound() {
        val historical =
            "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION}=${TerminalCommandMetadata.CURRENT_FORMAT} " +
                "${TerminalProviderDestinationOption.render(providerC)} $command"

        assertEquals(
            TerminalCommandMetadata.DurableAuthority.SelfBound,
            TerminalCommandMetadata.classifyDurable(
                historical,
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
            ),
        )
    }

    @Test
    fun legacyMarkerWithAuthoredNativeHomeStaysSelfBound() {
        val historical =
            "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION}=${TerminalCommandMetadata.CURRENT_FORMAT} " +
                "-P /storage/emulated/0/Custom $command"

        assertEquals(
            TerminalCommandMetadata.DurableAuthority.SelfBound,
            TerminalCommandMetadata.classifyDurable(
                historical,
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
            ),
        )
    }

    @Test
    fun preMaterializerExplicitProviderIsSelfBound() {
        val legacy = TerminalProviderDestinationOption.render(providerC) + " $command"

        assertEquals(
            TerminalCommandMetadata.DurableAuthority.SelfBound,
            TerminalCommandMetadata.classifyDurable(
                legacy,
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
            ),
        )
        assertEquals(providerC, TerminalCommandMetadata.strip(legacy).providerTreeUri)
    }

    @Test
    fun preMaterializerAuthoredNativeDestinationIsSelfBound() {
        val legacy = "-P /storage/emulated/0/Custom $command"

        assertEquals(
            TerminalCommandMetadata.DurableAuthority.SelfBound,
            TerminalCommandMetadata.classifyDurable(
                legacy,
                TerminalCommandMetadata.LEGACY_FORMAT_GENERATION,
            ),
        )
    }

    @Test
    fun unknownFormatMarkerIsRefusedRatherThanTreatedAsAbsent() {
        val foreign = "${TerminalCommandMetadata.COMMAND_FORMAT_OPTION}=99 $command"

        // This app only ever writes CURRENT_FORMAT, so another value is not
        // app-authored and must not be silently dropped.
        assertTrue(runCatching { TerminalCommandMetadata.strip(foreign) }.isFailure)
        assertTrue(
            runCatching {
                TerminalCommandMetadata.classifyDurableResult(
                    foreign,
                    TerminalCommandMetadata.CURRENT_FORMAT_GENERATION,
                )
            }.getOrNull() is TerminalCommandMetadata.DurableClassification.Malformed,
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
