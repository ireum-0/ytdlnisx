package com.ireum.ytdl.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class LowQualityBatchResult<R>(
    val completed: List<R>,
    val failureCount: Int
)

object LowQualityBatchProcessor {
    suspend fun <T, R> process(
        items: List<T>,
        processItem: suspend (T) -> R?,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): LowQualityBatchResult<R> {
        val completed = mutableListOf<R>()
        var failures = 0
        items.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            try {
                processItem(item)?.let(completed::add)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures += 1
            }
            onProgress(index + 1, items.size)
        }
        return LowQualityBatchResult(completed, failures)
    }
}
