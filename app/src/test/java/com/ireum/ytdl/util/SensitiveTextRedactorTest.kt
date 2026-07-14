package com.ireum.ytdl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveTextRedactorTest {

    @Test
    fun redactCommand_removesSeparateAndAttachedSensitiveOptions() {
        val command = "yt-dlp --cookies /private/cookies.txt --proxy=https://user:pass@proxy.example -u alice -psecret https://example.com/video"

        val redacted = SensitiveTextRedactor.redactCommand(command)

        assertFalse(redacted.contains("/private/cookies.txt"))
        assertFalse(redacted.contains("user:pass"))
        assertFalse(redacted.contains("alice"))
        assertFalse(redacted.contains("secret"))
        assertTrue(redacted.contains("https://example.com/video"))
    }

    @Test
    fun redactOutput_handlesMultilineHeadersTokensAndEmbeddedCommands() {
        val diagnostics = """
            Command: yt-dlp --add-header "Authorization: Bearer command-secret" --cookies=/tmp/cookies.txt
            Authorization: Bearer first-secret
            Cookie: session=second-secret
            Request: https://example.com/video?token=query-secret&quality=best
            extractor:po_token=web+token-secret;visitor_data=visitor-secret
            ERROR: requested format is not available
        """.trimIndent()

        val redacted = SensitiveTextRedactor.redactOutput(diagnostics)

        listOf(
            "command-secret",
            "/tmp/cookies.txt",
            "first-secret",
            "second-secret",
            "query-secret",
            "token-secret",
            "visitor-secret"
        ).forEach { secret -> assertFalse(redacted.contains(secret)) }
        assertTrue(redacted.contains("quality=best"))
        assertTrue(redacted.contains("ERROR: requested format is not available"))
    }

    @Test
    fun redactOutput_preservesNonSensitiveDiagnostics() {
        val diagnostics = "HTTP Error 404: Not Found\nformat=137+140\nproxy connection failed"

        assertEquals(diagnostics, SensitiveTextRedactor.redactOutput(diagnostics))
    }

    @Test
    fun uppercaseShortOptionsRemainVisible() {
        val command = "yt-dlp -P /output -U https://example.com/video"

        val redacted = SensitiveTextRedactor.redactCommand(command)

        assertTrue(redacted.contains("-P /output"))
        assertTrue(redacted.contains("-U https://example.com/video"))
    }
}
