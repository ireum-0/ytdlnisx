package com.ireum.ytdl.database.repository

import com.ireum.ytdl.database.models.observeSources.ObservationPurposes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticKeywordInfrastructurePolicyTest {
    @Test
    fun manualPlaylistUrlGetsManagedDiscoveryWhenNoObserveSourceMatches() {
        assertTrue(
            AutomaticKeywordCoveragePolicy.needsManagedSource(
                "youtube:playlist:PL1",
                emptySet()
            )
        )
    }

    @Test
    fun matchingObserveSourceIsReused() {
        assertFalse(
            AutomaticKeywordCoveragePolicy.needsManagedSource(
                "youtube:playlist:PL1",
                setOf("youtube:playlist:PL1")
            )
        )
    }

    @Test
    fun removingMatchingObserveSourceRestoresManagedCoverage() {
        val key = "youtube:playlist:PL1"
        assertFalse(AutomaticKeywordCoveragePolicy.needsManagedSource(key, setOf(key)))
        assertTrue(AutomaticKeywordCoveragePolicy.needsManagedSource(key, emptySet()))
    }

    @Test
    fun manualSyncUsesOneStableUniqueWorkIdentity() {
        assertEquals(
            "AUTOMATIC_KEYWORD_RULE_SYNC_91",
            AutomaticKeywordRuleScheduler.workName(91)
        )
    }

    @Test
    fun managedDiscoverySourceCanNeverQueueDownloads() {
        assertFalse(
            AutomaticKeywordCoveragePolicy.mayQueueDownloads(
                ObservationPurposes.KEYWORD_DISCOVERY
            )
        )
        assertTrue(AutomaticKeywordCoveragePolicy.mayQueueDownloads(ObservationPurposes.USER))
    }
}
