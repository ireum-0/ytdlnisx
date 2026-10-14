package com.ireum.ytdl.work

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatBatchMembershipTest {
    @Test fun selectedAndCapturedOtherRemainSeparate() {
        assertEquals(FormatBatchMembership(listOf(1), listOf(2), emptyList()), FormatBatchMembership.partition(listOf(1, 2), listOf(1, 2), listOf(1)))
    }
    @Test fun lateIdsCannotWidenBundle() {
        assertEquals(FormatBatchMembership(listOf(1), listOf(2), emptyList()), FormatBatchMembership.partition(listOf(1, 2), listOf(1, 2, 3), listOf(1, 3)))
    }
    @Test fun refusedCapturedRowHasNoDownstreamOwnership() {
        assertEquals(FormatBatchMembership(listOf(1), emptyList(), listOf(2)), FormatBatchMembership.partition(listOf(1, 2), listOf(1), listOf(1)))
    }
    @Test fun nullSelectionRefreshesSuccessfulWholeBundle() {
        assertEquals(FormatBatchMembership(listOf(1, 2), emptyList(), emptyList()), FormatBatchMembership.partition(listOf(1, 2), listOf(1, 2), null))
    }
    @Test fun emptySelectionRefreshesSuccessfulWholeBundle() {
        assertEquals(FormatBatchMembership(listOf(1, 2), emptyList(), emptyList()), FormatBatchMembership.partition(listOf(1, 2), listOf(1, 2), emptyList()))
    }
    @Test fun duplicateCaptureDoesNotDuplicateEffects() {
        assertEquals(FormatBatchMembership(emptyList(), listOf(2), listOf(1)), FormatBatchMembership.partition(listOf(1, 1, 2), listOf(2), listOf(7)))
    }
}
