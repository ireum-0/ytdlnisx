package com.ireum.ytdl.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.util.SourceSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the production Observe absence gate, not a duplicate test policy. */
@RunWith(AndroidJUnit4::class)
class ObserveSourceSnapshotProductionWiringTest {
    @Test
    fun onlyAuthoritativeSnapshotsReachDestructiveAbsenceBoundary() {
        assertTrue(
            ObserveSourceWorker.permitsDestructiveAbsenceReconciliation(
                SourceSnapshot.Authority.AUTHORITATIVE,
            )
        )
        assertFalse(
            ObserveSourceWorker.permitsDestructiveAbsenceReconciliation(
                SourceSnapshot.Authority.PARTIAL,
            )
        )
        assertFalse(
            ObserveSourceWorker.permitsDestructiveAbsenceReconciliation(
                SourceSnapshot.Authority.FAILED,
            )
        )
    }

    @Test
    fun partialSnapshotsAdvanceRunProgressWithoutEstablishingInitialBaseline() {
        assertTrue(
            ObserveSourceWorker.canAdvanceObserveRun(
                SourceSnapshot.LifecycleProgress.FORWARD_PROGRESS,
            )
        )
        assertFalse(
            ObserveSourceWorker.canEstablishInitialNewUploadBaseline(
                SourceSnapshot.LifecycleProgress.FORWARD_PROGRESS,
            )
        )
        assertFalse(
            ObserveSourceWorker.shouldUseInitialNewUploadBaseline(
                getOnlyNewUploads = true,
                runCount = 0,
                progress = SourceSnapshot.LifecycleProgress.FORWARD_PROGRESS,
            )
        )
        assertTrue(
            ObserveSourceWorker.canEstablishInitialNewUploadBaseline(
                SourceSnapshot.LifecycleProgress.INITIAL_BASELINE_ELIGIBLE,
            )
        )
        assertTrue(
            ObserveSourceWorker.shouldUseInitialNewUploadBaseline(
                getOnlyNewUploads = true,
                runCount = 0,
                progress = SourceSnapshot.LifecycleProgress.INITIAL_BASELINE_ELIGIBLE,
            )
        )
        assertFalse(
            ObserveSourceWorker.shouldUseInitialNewUploadBaseline(
                getOnlyNewUploads = true,
                runCount = 1,
                progress = SourceSnapshot.LifecycleProgress.INITIAL_BASELINE_ELIGIBLE,
            )
        )
        assertFalse(
            ObserveSourceWorker.canAdvanceObserveRun(
                SourceSnapshot.LifecycleProgress.NONE,
            )
        )
    }

    @Test
    fun nonAuthoritativeAbsenceNeverProducesDeletionCandidates() {
        val processed = listOf("https://example.com/previous")
        val incoming = emptySet<String>()
        val canonicalize: (String) -> String = { it.trim() }

        assertTrue(
            ObserveSourceWorker.missingSourceLinksForDestructiveReconciliation(
                SourceSnapshot.Authority.AUTHORITATIVE,
                processed,
                incoming,
                canonicalize,
            ).contains(processed.single())
        )
        assertTrue(
            ObserveSourceWorker.missingSourceLinksForDestructiveReconciliation(
                SourceSnapshot.Authority.PARTIAL,
                processed,
                incoming,
                canonicalize,
            ).isEmpty()
        )
        assertTrue(
            ObserveSourceWorker.missingSourceLinksForDestructiveReconciliation(
                SourceSnapshot.Authority.FAILED,
                processed,
                incoming,
                canonicalize,
            ).isEmpty()
        )
    }
}
