package com.ireum.ytdl.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueuePolicyTest {
    private data class QueueRecord(val id: Long, val status: String = "Queued")

    @Test
    fun nonemptyPriorityIdsAlwaysUsePriorityObservation() {
        assertEquals(
            DownloadQueueObservationMode.PRIORITY,
            DownloadQueuePolicy.observationMode(listOf(12L)),
        )
        assertEquals(
            DownloadQueueObservationMode.STANDARD,
            DownloadQueuePolicy.observationMode(emptyList()),
        )
    }

    @Test
    fun priorityCandidateIsSelectedBeforeNormalRowsWithoutDuplication() {
        val snapshot = prioritySnapshot(
            priorityIds = listOf(12L),
            records = listOf(QueueRecord(12L)),
            eligibleIds = setOf(12L, 1L, 2L, 3L),
        )
        val selected = DownloadQueuePolicy.selectCandidates(
            items = listOf(12L, 1L, 2L, 3L),
            runningIds = emptySet(),
            prioritySnapshot = snapshot,
            availableSlots = 2,
            idOf = { it },
        )

        assertEquals(listOf(12L), selected)
        assertEquals(selected.size, selected.distinct().size)
    }

    @Test
    fun normalProcessingResumesAfterPrioritiesWhenContinuationIsEnabled() {
        val selected = DownloadQueuePolicy.selectCandidates(
            items = listOf(1L, 2L, 3L),
            runningIds = emptySet(),
            prioritySnapshot = DownloadQueuePrioritySnapshot(emptyList(), emptyList()),
            availableSlots = 2,
            idOf = { it },
        )

        assertEquals(listOf(1L, 2L), selected)
        assertFalse(
            DownloadQueuePolicy.shouldStopAfterPriorities(
                priorityItemIds = emptyList(),
                continueAfterPriorityItems = true,
            )
        )
    }

    @Test
    fun processingStopsAfterPrioritiesWhenContinuationIsDisabled() {
        assertTrue(
            DownloadQueuePolicy.shouldStopAfterPriorities(
                priorityItemIds = emptyList(),
                continueAfterPriorityItems = false,
            )
        )
        assertFalse(
            DownloadQueuePolicy.shouldStopAfterPriorities(
                priorityItemIds = listOf(12L),
                continueAfterPriorityItems = false,
            )
        )
    }

    @Test
    fun stalePrioritiesDoNotConsumeSlotsAndLaterCandidatesKeepPriorityOrder() {
        val snapshot = prioritySnapshot(
            priorityIds = listOf(99L, 3L, 2L),
            records = listOf(QueueRecord(2L), QueueRecord(3L)),
            eligibleIds = setOf(2L, 3L),
        )

        val selected = DownloadQueuePolicy.selectCandidates(
            items = listOf(2L, 3L),
            runningIds = emptySet(),
            prioritySnapshot = snapshot,
            availableSlots = 2,
            idOf = { it },
        )

        assertEquals(listOf(3L, 2L), snapshot.outstandingIds)
        assertEquals(listOf(3L, 2L), selected)
    }

    @Test
    fun runningAndStateIneligiblePrioritiesRemainOutstandingButDoNotConsumeSlots() {
        val snapshot = prioritySnapshot(
            priorityIds = listOf(1L, 2L, 3L),
            records = listOf(
                QueueRecord(1L, "Active"),
                QueueRecord(2L, "Paused"),
                QueueRecord(3L),
            ),
            eligibleIds = setOf(1L, 2L, 3L),
            runningIds = setOf(1L),
        )

        val selected = DownloadQueuePolicy.selectCandidates(
            items = listOf(1L, 2L, 3L, 4L),
            runningIds = setOf(1L),
            prioritySnapshot = snapshot,
            availableSlots = 1,
            idOf = { it },
        )

        assertEquals(listOf(1L, 2L, 3L), snapshot.outstandingIds)
        assertEquals(listOf(3L), snapshot.selectableIds)
        assertEquals(listOf(3L), selected)
    }

    @Test
    fun allRunningPrioritiesBlockOrdinaryItemsWithoutLosingPriorityIds() {
        val snapshot = prioritySnapshot(
            priorityIds = listOf(12L),
            records = listOf(QueueRecord(12L, "Active")),
            eligibleIds = emptySet(),
            runningIds = setOf(12L),
        )

        val selected = DownloadQueuePolicy.selectCandidates(
            items = listOf(1L, 2L),
            runningIds = setOf(12L),
            prioritySnapshot = snapshot,
            availableSlots = 1,
            idOf = { it },
        )

        assertEquals(listOf(12L), snapshot.outstandingIds)
        assertTrue(selected.isEmpty())
        assertFalse(
            DownloadQueuePolicy.shouldStopAfterPriorities(
                snapshot.outstandingIds,
                continueAfterPriorityItems = false,
            )
        )
    }

    @Test
    fun exhaustedPrioritiesEitherContinueWithOrdinaryItemsOrStopByPolicy() {
        val snapshot = prioritySnapshot(
            priorityIds = listOf(12L, 13L),
            records = listOf(QueueRecord(13L, "Cancelled")),
            eligibleIds = emptySet(),
        )
        val selected = DownloadQueuePolicy.selectCandidates(
            items = listOf(1L, 2L),
            runningIds = emptySet(),
            prioritySnapshot = snapshot,
            availableSlots = 1,
            idOf = { it },
        )

        assertTrue(snapshot.outstandingIds.isEmpty())
        assertEquals(listOf(1L), selected)
        assertFalse(
            DownloadQueuePolicy.shouldStopAfterPriorities(
                snapshot.outstandingIds,
                continueAfterPriorityItems = true,
            )
        )
        assertTrue(
            DownloadQueuePolicy.shouldStopAfterPriorities(
                snapshot.outstandingIds,
                continueAfterPriorityItems = false,
            )
        )
    }

    @Test
    fun pendingCancellationRemainsOutstandingUntilItsLinkedItemIsTerminal() {
        val pending = prioritySnapshot(
            priorityIds = listOf(12L),
            records = listOf(QueueRecord(12L, "Cancelled")),
            eligibleIds = emptySet(),
            nonterminalLinkedIds = setOf(12L),
        )
        val committed = prioritySnapshot(
            priorityIds = listOf(12L),
            records = listOf(QueueRecord(12L, "Cancelled")),
            eligibleIds = emptySet(),
        )

        assertEquals(listOf(12L), pending.outstandingIds)
        assertTrue(pending.selectableIds.isEmpty())
        assertTrue(committed.outstandingIds.isEmpty())
    }

    private fun prioritySnapshot(
        priorityIds: List<Long>,
        records: List<QueueRecord>,
        eligibleIds: Set<Long>,
        runningIds: Set<Long> = emptySet(),
        nonterminalLinkedIds: Set<Long> = emptySet(),
    ) = DownloadQueuePolicy.prioritySnapshot(
        priorityItemIds = priorityIds,
        queueRecords = records,
        eligibleItemIds = eligibleIds,
        activeOrRunningIds = runningIds,
        nonterminalLinkedIds = nonterminalLinkedIds,
        idOf = QueueRecord::id,
        statusOf = QueueRecord::status,
    )
}
