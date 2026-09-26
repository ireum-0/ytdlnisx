package com.ireum.ytdl.util.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable Terminal command must resolve its destination authority before it
 * is stored, so a later preference change cannot redirect it.  These cases pin
 * the exact precedence the materializer must preserve.
 */
class TerminalCommandIntentMaterializerTest {
    private val providerA = "content://com.android.externalstorage.documents/tree/primary%3AA"
    private val providerC = "content://com.android.externalstorage.documents/tree/primary%3AC"
    private val command = "https://example.com/video"

    @Test
    fun configuredProviderIsMaterializedIntoTheDurableCommand() {
        val materialized = TerminalCommandIntentMaterializer.materialize(command, providerA)

        assertTrue(materialized.contains(providerA))
        assertTrue(materialized.contains(command))
        // Exactly one provider option, and it parses back to the exact URI.
        val extracted = TerminalProviderDestinationOption.extract(materialized)
        assertEquals(providerA, extracted.providerTreeUri)
        assertEquals(command, extracted.command)
    }

    @Test
    fun materializationIsIdempotent() {
        val once = TerminalCommandIntentMaterializer.materialize(command, providerA)
        val twice = TerminalCommandIntentMaterializer.materialize(once, providerA)

        assertEquals(once, twice)
        assertEquals(providerA, TerminalProviderDestinationOption.extract(twice).providerTreeUri)
    }

    @Test
    fun explicitProviderSelectionIsPreservedAndOutranksTheConfiguredDefault() {
        val picked = TerminalProviderDestinationOption.render(providerC) + " $command"

        val materialized = TerminalCommandIntentMaterializer.materialize(picked, providerA)

        assertEquals(picked, materialized)
        assertFalse(materialized.contains(providerA))
    }

    @Test
    fun authoredNativeDestinationIsNotGivenConfiguredProviderAuthority() {
        val authored = "-P /storage/emulated/0/Custom $command"

        val materialized = TerminalCommandIntentMaterializer.materialize(authored, providerA)

        assertEquals(authored, materialized)
        assertFalse(materialized.contains(providerA))
    }

    @Test
    fun rawOrDefaultConfiguredDestinationIsNotFrozen() {
        // A raw destination keeps existing behavior; this follow-up must not
        // broaden into freezing every mutable preference.
        for (configured in listOf("/storage/emulated/0/YTDLnisX/Command", "primary:Music", "")) {
            assertEquals(
                "configured=$configured",
                command,
                TerminalCommandIntentMaterializer.materialize(command, configured),
            )
        }
    }

    @Test
    fun malformedProviderMetadataStaysFailClosed() {
        val malformed = "${TerminalProviderDestinationOption.OPTION}=content:// $command"
        val failure = runCatching {
            TerminalCommandIntentMaterializer.materialize(malformed, providerA)
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun repeatedProviderMetadataStaysFailClosed() {
        val repeated = TerminalProviderDestinationOption.render(providerA) + " " +
            TerminalProviderDestinationOption.render(providerC) + " $command"
        val failure = runCatching {
            TerminalCommandIntentMaterializer.materialize(repeated, providerA)
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun emptyCommandStillCarriesTheBoundAuthority() {
        val materialized = TerminalCommandIntentMaterializer.materialize("  ", providerA)

        assertEquals(providerA, TerminalProviderDestinationOption.extract(materialized).providerTreeUri)
    }
}
