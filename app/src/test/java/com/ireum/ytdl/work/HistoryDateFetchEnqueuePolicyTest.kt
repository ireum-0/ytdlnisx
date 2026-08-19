package com.ireum.ytdl.work

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDateFetchEnqueuePolicyTest {
    @Test
    fun everyStartAndRecoveryKeepsExistingUniqueWork() {
        assertEquals(ExistingWorkPolicy.KEEP, HistoryDateFetchEnqueuePolicy.workPolicy)
    }
}
