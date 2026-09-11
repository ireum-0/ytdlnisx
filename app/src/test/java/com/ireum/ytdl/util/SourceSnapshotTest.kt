package com.ireum.ytdl.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSnapshotTest {
    @Test
    fun authoritativeEmptySnapshotPermitsLegitimateEmptySourceSync() {
        val snapshot = SourceSnapshot.authoritative(emptyList())

        assertEqualsAuthority(SourceSnapshot.Authority.AUTHORITATIVE, snapshot)
        assertTrue(snapshot.items.isEmpty())
        assertTrue(snapshot.permitsDestructiveAbsenceReconciliation)
    }

    @Test
    fun partialSnapshotWithUsableItemsNeverAuthorizesAbsence() {
        val snapshot = SourceSnapshot.partial(
            items = listOf(resultItem()),
            diagnostic = "NewPipe conversion dropped an item",
        )

        assertEqualsAuthority(SourceSnapshot.Authority.PARTIAL, snapshot)
        assertTrue(snapshot.items.isNotEmpty())
        assertFalse(snapshot.permitsDestructiveAbsenceReconciliation)
    }

    @Test
    fun lifecycleProgressIsIndependentFromDestructiveMembershipAuthority() {
        val partial = SourceSnapshot.partial(
            items = listOf(resultItem()),
            diagnostic = "yt-dlp source extraction is error-tolerant",
        )
        assertEquals(
            SourceSnapshot.LifecycleProgress.FORWARD_PROGRESS,
            partial.lifecycleProgress,
        )
        assertFalse(partial.permitsDestructiveAbsenceReconciliation)

        val authoritative = SourceSnapshot.authoritative(emptyList())
        assertEquals(
            SourceSnapshot.LifecycleProgress.INITIAL_BASELINE_ELIGIBLE,
            authoritative.lifecycleProgress,
        )
        assertTrue(authoritative.permitsDestructiveAbsenceReconciliation)

        val failed = SourceSnapshot.failed()
        assertEquals(
            SourceSnapshot.LifecycleProgress.NONE,
            failed.lifecycleProgress,
        )
    }

    @Test
    fun failedSnapshotNeverAuthorizesAbsence() {
        val snapshot = SourceSnapshot.failed(
            cause = IllegalStateException("continuation failed"),
        )

        assertEqualsAuthority(SourceSnapshot.Authority.FAILED, snapshot)
        assertTrue(snapshot.items.isEmpty())
        assertFalse(snapshot.permitsDestructiveAbsenceReconciliation)
    }

    @Test
    fun newPipeConversionDropIsPartialEvenWhenSomeItemsWereConverted() {
        val snapshot = SourceSnapshotAuthority.fromNewPipe(
            items = listOf(resultItem()),
            conversionDropped = true,
            diagnostic = "item conversion failed",
        )

        assertEqualsAuthority(SourceSnapshot.Authority.PARTIAL, snapshot)
        assertTrue(snapshot.items.isNotEmpty())
        assertFalse(snapshot.permitsDestructiveAbsenceReconciliation)
    }

    @Test
    fun newPipeContinuationFailureIsPartialRatherThanAuthoritativeEmpty() {
        val snapshot = SourceSnapshotAuthority.fromNewPipe(
            items = emptyList(),
            continuationIncomplete = true,
            diagnostic = "continuation returned an empty page",
        )

        assertEqualsAuthority(SourceSnapshot.Authority.PARTIAL, snapshot)
        assertFalse(snapshot.permitsDestructiveAbsenceReconciliation)
    }

    @Test
    fun newPipeGenuinelyEmptyCompletedSourceIsAuthoritative() {
        val snapshot = SourceSnapshotAuthority.fromNewPipe(emptyList())

        assertEqualsAuthority(SourceSnapshot.Authority.AUTHORITATIVE, snapshot)
        assertTrue(snapshot.permitsDestructiveAbsenceReconciliation)
    }

    @Test
    fun ytDlpErrorTolerantSourceListNeverBecomesAuthoritative() {
        val snapshot = SourceSnapshotAuthority.fromYtdlp(
            items = listOf(resultItem()),
            singleItem = false,
            ignoredChildErrors = true,
            diagnostic = "ignored child error",
        )

        assertEqualsAuthority(SourceSnapshot.Authority.PARTIAL, snapshot)
        assertTrue(snapshot.items.isNotEmpty())
        assertFalse(snapshot.permitsDestructiveAbsenceReconciliation)
    }

    @Test
    fun ytDlpParseDropNeverBecomesAuthoritative() {
        val snapshot = SourceSnapshotAuthority.fromYtdlp(
            items = listOf(resultItem()),
            singleItem = true,
            parseDropped = true,
            diagnostic = "one JSON line could not be parsed",
        )

        assertEqualsAuthority(SourceSnapshot.Authority.PARTIAL, snapshot)
        assertTrue(snapshot.items.isNotEmpty())
        assertFalse(snapshot.permitsDestructiveAbsenceReconciliation)
    }

    @Test
    fun extractionFailureIsFailedWithoutInventingAnEmptyAuthoritativeSource() {
        val snapshot = SourceSnapshotAuthority.fromYtdlp(
            items = emptyList(),
            singleItem = false,
            executionFailure = IllegalStateException("extractor failed"),
        )

        assertEqualsAuthority(SourceSnapshot.Authority.FAILED, snapshot)
        assertFalse(snapshot.permitsDestructiveAbsenceReconciliation)
    }

    @Test
    fun fallbackCanReplaceFailureButCannotUpgradeADegradedPrimarySnapshot() {
        val partial = SourceSnapshot.partial(
            listOf(resultItem()),
            diagnostic = "primary extractor dropped a page",
        )
        val authoritativeFallback = SourceSnapshot.authoritative(listOf(resultItem()))

        val preserved = SourceSnapshotAuthority.preserveOrFallback(partial) { authoritativeFallback }
        assertEqualsAuthority(SourceSnapshot.Authority.PARTIAL, preserved)

        val failed = SourceSnapshot.failed(diagnostic = "primary extractor failed")
        val repaired = SourceSnapshotAuthority.preserveOrFallback(failed) { authoritativeFallback }
        assertEqualsAuthority(SourceSnapshot.Authority.AUTHORITATIVE, repaired)
    }

    @Test
    fun snapshotCopiesItemListSoCallerCannotChangeAuthorityMembership() {
        val source = mutableListOf<com.ireum.ytdl.database.models.ResultItem>()
        val snapshot = SourceSnapshot.partial(source)

        assertNotSame(source, snapshot.items)
        assertTrue(snapshot.items.isEmpty())
        assertFalse(snapshot.permitsDestructiveAbsenceReconciliation)
    }

    private fun assertEqualsAuthority(
        expected: SourceSnapshot.Authority,
        actual: SourceSnapshot,
    ) {
        org.junit.Assert.assertEquals(expected, actual.authority)
    }

    private fun resultItem() = com.ireum.ytdl.database.models.ResultItem(
        id = 0L,
        url = "https://example.com/item",
        title = "item",
        author = "author",
        duration = "",
        thumb = "",
        website = "",
        playlistTitle = "",
        urls = "",
        chapters = null,
    )
}
