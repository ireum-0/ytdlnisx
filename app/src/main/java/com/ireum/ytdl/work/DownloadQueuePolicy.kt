package com.ireum.ytdl.work

internal enum class DownloadQueueObservationMode {
    STANDARD,
    PRIORITY,
}

internal data class DownloadQueuePrioritySnapshot(
    val outstandingIds: List<Long>,
    val selectableIds: List<Long>,
)

internal object DownloadQueuePolicy {
    fun observationMode(priorityItemIds: List<Long>): DownloadQueueObservationMode =
        if (priorityItemIds.isEmpty()) {
            DownloadQueueObservationMode.STANDARD
        } else {
            DownloadQueueObservationMode.PRIORITY
        }

    fun shouldStopAfterPriorities(
        priorityItemIds: List<Long>,
        continueAfterPriorityItems: Boolean,
    ): Boolean = priorityItemIds.isEmpty() && !continueAfterPriorityItems

    fun <T> prioritySnapshot(
        priorityItemIds: List<Long>,
        queueRecords: List<T>,
        eligibleItemIds: Set<Long>,
        activeOrRunningIds: Set<Long>,
        nonterminalLinkedIds: Set<Long> = emptySet(),
        idOf: (T) -> Long,
        statusOf: (T) -> String,
    ): DownloadQueuePrioritySnapshot {
        val recordsById = queueRecords.associateBy(idOf)
        val outstandingIds = priorityItemIds.distinct().filter { id ->
            recordsById[id]?.let { record ->
                isOutstandingStatus(statusOf(record)) || id in nonterminalLinkedIds
            } == true
        }
        val selectableIds = outstandingIds.filter { id ->
            val record = recordsById.getValue(id)
            id in eligibleItemIds &&
                id !in activeOrRunningIds &&
                isSelectableStatus(statusOf(record))
        }
        return DownloadQueuePrioritySnapshot(outstandingIds, selectableIds)
    }

    fun <T> selectCandidates(
        items: List<T>,
        runningIds: Set<Long>,
        prioritySnapshot: DownloadQueuePrioritySnapshot,
        availableSlots: Int,
        idOf: (T) -> Long,
    ): List<T> {
        if (availableSlots <= 0) return emptyList()

        return if (prioritySnapshot.outstandingIds.isNotEmpty()) {
            val itemsById = items.associateBy(idOf)
            prioritySnapshot.selectableIds.asSequence()
                .mapNotNull(itemsById::get)
                .take(availableSlots)
                .toList()
        } else {
            items.asSequence()
                .filter { item -> idOf(item) !in runningIds }
                .take(availableSlots)
                .toList()
        }
    }

    private fun isOutstandingStatus(status: String): Boolean = status in setOf(
        "Active",
        "PostProcessing",
        "Paused",
        "Queued",
        "WaitingForMembership",
        "Processing",
        "Scheduled",
        "Duplicate",
    )

    private fun isSelectableStatus(status: String): Boolean =
        status == "Queued" || status == "Scheduled"
}
