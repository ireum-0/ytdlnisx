package com.ireum.ytdl.util.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipAccessPolicyTest {
    @Test
    fun manualDownloadFailsWithoutAutomaticRetry() {
        val decision = MembershipAccessPolicy.decide(
            observeSourceId = 0L,
            previousIssueCode = ""
        )

        assertFalse(decision.waitForAutomaticRetry)
        assertFalse(decision.showFirstWaitingNotification)
    }

    @Test
    fun automaticDownloadWaitsAndNotifiesOnlyOnFirstTransition() {
        val first = MembershipAccessPolicy.decide(
            observeSourceId = 42L,
            previousIssueCode = ""
        )
        val repeated = MembershipAccessPolicy.decide(
            observeSourceId = 42L,
            previousIssueCode = DownloadIssueCode.MEMBERSHIP_REQUIRED.name
        )

        assertTrue(first.waitForAutomaticRetry)
        assertTrue(first.showFirstWaitingNotification)
        assertTrue(repeated.waitForAutomaticRetry)
        assertFalse(repeated.showFirstWaitingNotification)
    }
}
