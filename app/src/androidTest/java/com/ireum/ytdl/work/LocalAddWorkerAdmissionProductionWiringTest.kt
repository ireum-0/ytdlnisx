package com.ireum.ytdl.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.util.LocalAddEntryDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the LocalAddWorker admission policy from the packaged production code. */
@RunWith(AndroidJUnit4::class)
class LocalAddWorkerAdmissionProductionWiringTest {
    @Test
    fun sameNameFromDifferentAuthoritiesRemainsAdmitted() {
        val entries = listOf(
            LocalAddEntryDto("content://authority-a/document/primary%3Avideo.mp4", null),
            LocalAddEntryDto("content://authority-b/document/primary%3Avideo.mp4", null),
        )

        assertEquals(2, LocalAddWorker.deduplicateEntriesForAdmission(entries).size)
    }

    @Test
    fun exactRepeatedUriIsSuppressedOnce() {
        val entry = LocalAddEntryDto("content://authority/document/primary%3Avideo.mp4", null)

        assertEquals(
            1,
            LocalAddWorker.deduplicateEntriesForAdmission(listOf(entry, entry.copy())).size
        )
    }

    @Test
    fun unprovableEntriesAreNotCollapsed() {
        val entries = listOf(
            LocalAddEntryDto("not-a-stable-source", null),
            LocalAddEntryDto("another-not-a-stable-source", null),
        )

        assertNotEquals(1, LocalAddWorker.deduplicateEntriesForAdmission(entries).size)
    }
}
