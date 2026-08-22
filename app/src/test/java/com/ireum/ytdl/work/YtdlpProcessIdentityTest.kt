package com.ireum.ytdl.work

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtdlpProcessIdentityTest {
    @Test
    fun downloadAndTerminalIdsWithSameNumericValueAreDifferentProcessDomains() {
        val download = YtdlpProcessIdentity.download(41L, "E1")
        val terminal = YtdlpProcessIdentity.terminal(41L)

        assertNotEquals(download, terminal)
        assertTrue(download.startsWith("download:41:E1"))
        assertTrue(terminal.startsWith("terminal:41"))
    }

    @Test
    fun downloadExecutionTokenParticipatesInProcessIdentity() {
        assertNotEquals(
            YtdlpProcessIdentity.download(41L, "E1"),
            YtdlpProcessIdentity.download(41L, "E2"),
        )
    }
}
