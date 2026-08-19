package com.ireum.ytdl.util.extractors.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtdlpRetryLogTest {
    @Test
    fun initialSuccessHasNoRetryDetails() {
        assertEquals("", accumulated())
    }

    @Test
    fun tokenPreservingRetrySuccessRetainsItsDetails() {
        val details = accumulated(
            entry("token retry", "first failure", "--extractor-args youtube:player_client=web")
        )

        assertTrue(details.contains("Reason: token retry"))
        assertTrue(details.contains("first failure"))
        assertEquals(1, details.occurrencesOf("\nRetry:\n"))
    }

    @Test
    fun publicFallbackSuccessRetainsBothAttemptsInOrderWithoutDuplication() {
        val details = accumulated(
            entry("token retry", "initial 403", "token-command"),
            entry("public fallback", "token retry 403", "public-command"),
        )
        assertTrue(details.indexOf("Reason: token retry") < details.indexOf("Reason: public fallback"))
        assertTrue(details.contains("initial 403"))
        assertTrue(details.contains("token retry 403"))
        assertTrue(details.contains("token-command"))
        assertTrue(details.contains("public-command"))
        assertEquals(1, details.occurrencesOf("Reason: token retry\n"))
        assertEquals(1, details.occurrencesOf("Reason: public fallback\n"))
    }

    @Test
    fun allRetryPathsFailStillRetainsEveryAttempt() {
        val details = accumulated(
            entry("token retry", "initial failure", "token-command"),
            entry("public fallback", "retry failure", "public-command"),
        )

        assertEquals(2, details.occurrencesOf("\nRetry:\n"))
    }

    @Test
    fun retryDetailsRemainRedacted() {
        val details = entry(
            notice = "public fallback",
            error = "request failed with access_token=super-secret",
            command = "--cookies C:\\private\\cookies.txt https://example.com?po_token=private-token",
        )

        assertFalse(details.contains("super-secret"))
        assertFalse(details.contains("private-token"))
        assertFalse(details.contains("cookies.txt"))
        assertTrue(details.contains("***"))
    }

    private fun entry(notice: String, error: String, command: String): String {
        return YtdlpRetryLog.format(
            notice = notice,
            errorLabel = "Previous error",
            errorMessage = error,
            command = command,
            diagnostics = "diagnostics",
        )
    }

    private fun accumulated(vararg entries: String): String =
        entries.fold("") { details, entry -> YtdlpRetryLog.append(details, entry) }

    private fun String.occurrencesOf(needle: String): Int =
        windowed(needle.length).count { it == needle }
}
