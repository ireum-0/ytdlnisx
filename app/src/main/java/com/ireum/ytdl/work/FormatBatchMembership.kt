package com.ireum.ytdl.work

/** Downstream membership is the successful exact transition, never the later live status set. */
internal data class FormatBatchMembership(val selected: List<Long>, val other: List<Long>, val refusedOrStale: List<Long>) {
    companion object {
        fun partition(captured: List<Long>, transitioned: List<Long>, selection: List<Long>?): FormatBatchMembership {
            val bundle = captured.distinct()
            val actual = bundle.filter { it in transitioned }
            val selected = selection?.takeIf { it.isNotEmpty() }?.toSet() ?: bundle.toSet()
            return FormatBatchMembership(actual.filter { it in selected }, actual.filterNot { it in selected }, bundle.filterNot { it in actual })
        }
    }
}
