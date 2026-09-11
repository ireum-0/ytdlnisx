package com.ireum.ytdl.work

import com.ireum.ytdl.util.LocalAddEntryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Exercises the batch identity policy consumed by LocalAddWorker. */
class LocalAddWorkerAdmissionTest {
    @Test
    fun sameNameDifferentProvidersAreBothAdmittedByProductionPolicy() {
        val entries = listOf(
            LocalAddEntryDto("content://provider-a/document/primary%3Avideo.mp4", null),
            LocalAddEntryDto("content://provider-b/document/primary%3Avideo.mp4", null),
        )

        assertEquals(2, LocalAddWorker.deduplicateEntriesForAdmission(entries).size)
    }

    @Test
    fun exactRepeatedUriIsAdmittedOnlyOnceByProductionPolicy() {
        val entry = LocalAddEntryDto("content://provider/document/primary%3Avideo.mp4", null)
        val entries = listOf(entry, entry.copy())

        assertEquals(1, LocalAddWorker.deduplicateEntriesForAdmission(entries).size)
    }

    @Test
    fun unknownIdentityDoesNotSuppressAnotherEntry() {
        val entries = listOf(
            LocalAddEntryDto("yt-dlp https://example.com/video.mp4", null),
            LocalAddEntryDto("same display name but no identity", null),
        )

        assertNotEquals(1, LocalAddWorker.deduplicateEntriesForAdmission(entries).size)
    }
}
