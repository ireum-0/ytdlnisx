package com.ireum.ytdl.util.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // Exactly one provider option and one format marker, and both parse back
        // to the exact URI and the untouched user command.
        val stripped = TerminalCommandMetadata.strip(materialized)
        assertEquals(providerA, stripped.providerTreeUri)
        assertEquals(command, stripped.command)
        assertTrue(stripped.currentFormat)
    }

    @Test
    fun materializationIsIdempotent() {
        val once = TerminalCommandIntentMaterializer.materialize(command, providerA)
        val twice = TerminalCommandIntentMaterializer.materialize(once, providerA)

        assertEquals(once, twice)
        val stripped = TerminalCommandMetadata.strip(twice)
        assertEquals(providerA, stripped.providerTreeUri)
        assertTrue(stripped.currentFormat)
    }

    @Test
    fun explicitProviderSelectionIsPreservedAndOutranksTheConfiguredDefault() {
        val picked = TerminalProviderDestinationOption.render(providerC) + " $command"

        val materialized = TerminalCommandIntentMaterializer.materialize(picked, providerA)

        // The Folder selection survives verbatim; only the format marker is added.
        assertTrue(materialized.contains(providerC))
        assertTrue(materialized.contains(command))
        assertFalse(materialized.contains(providerA))
        val stripped = TerminalCommandMetadata.strip(materialized)
        assertEquals(providerC, stripped.providerTreeUri)
        assertEquals(command, stripped.command)
        assertTrue(stripped.currentFormat)
    }

    @Test
    fun authoredNativeDestinationIsNotGivenConfiguredProviderAuthority() {
        val authored = "-P /storage/emulated/0/Custom $command"

        val materialized = TerminalCommandIntentMaterializer.materialize(authored, providerA)

        assertFalse(materialized.contains(providerA))
        val stripped = TerminalCommandMetadata.strip(materialized)
        assertEquals(authored, stripped.command)
        assertTrue(stripped.currentFormat)
    }

    @Test
    fun rawOrDefaultConfiguredDestinationIsNotFrozen() {
        // A raw destination keeps existing behavior; this follow-up must not
        // broaden into freezing every mutable preference.  Only the format
        // marker is added, never a destination authority.
        for (configured in listOf("/storage/emulated/0/YTDLnisX/Command", "primary:Music", "")) {
            val materialized = TerminalCommandIntentMaterializer.materialize(command, configured)
            val stripped = TerminalCommandMetadata.strip(materialized)
            assertEquals("configured=$configured", command, stripped.command)
            assertNull("configured=$configured", stripped.providerTreeUri)
            assertTrue("configured=$configured", stripped.currentFormat)
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

        val stripped = TerminalCommandMetadata.strip(materialized)
        assertEquals(providerA, stripped.providerTreeUri)
        assertTrue(stripped.currentFormat)
        assertEquals("", stripped.command)
    }
}
