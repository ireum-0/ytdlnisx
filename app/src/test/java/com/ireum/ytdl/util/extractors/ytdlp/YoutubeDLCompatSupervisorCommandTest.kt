package com.ireum.ytdl.util.extractors.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class YoutubeDLCompatSupervisorCommandTest {
    @Test
    fun supervisorCommandPreservesBundledPythonBeforeWritableYtdlp() {
        val bundledPython = "/app/lib/libpython.so"
        val appDataYtdlp = "/data/no_backup/youtubedl-android/yt-dlp/yt-dlp"
        val command = listOf(bundledPython, appDataYtdlp, "--skip-download")

        val supervisor = YoutubeDLCompat.buildSupervisorCommand(
            pythonPath = bundledPython,
            markerPath = "/data/no_backup/process.marker",
            command = command,
        )

        assertEquals(bundledPython, supervisor[0])
        assertEquals("-c", supervisor[1])
        assertEquals(bundledPython, supervisor[4])
        assertEquals(appDataYtdlp, supervisor[5])
        assertNotEquals(appDataYtdlp, supervisor[0])
    }
}
