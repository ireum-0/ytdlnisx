package com.ireum.ytdl.work

import kotlinx.coroutines.CancellationException

internal data class MetadataBatchResult(
    val attempted: Int,
    val completed: Int,
    val failed: Int,
    val skipped: Int,
)

internal object MetadataBatchProcessor {
    suspend fun <Id, Item> process(
        ids: Iterable<Id>,
        loadItem: suspend (Id) -> Item,
        shouldProcess: (Item) -> Boolean,
        processItem: suspend (Id, Item) -> Unit,
        onItemFailure: (Id, Exception) -> Unit = { _, _ -> },
    ): MetadataBatchResult {
        var attempted = 0
        var completed = 0
        var failed = 0
        var skipped = 0

        ids.forEach { id ->
            val item = try {
                loadItem(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                attempted += 1
                failed += 1
                onItemFailure(id, error)
                return@forEach
            }
            val process = try {
                shouldProcess(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                attempted += 1
                failed += 1
                onItemFailure(id, error)
                return@forEach
            }
            if (!process) {
                skipped += 1
                return@forEach
            }

            attempted += 1
            try {
                processItem(id, item)
                completed += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed += 1
                onItemFailure(id, error)
            }
        }

        return MetadataBatchResult(
            attempted = attempted,
            completed = completed,
            failed = failed,
            skipped = skipped,
        )
    }
}
