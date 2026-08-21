package com.ireum.ytdl.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runs independent download items without letting one item's terminal failure
 * cancel the other items that were already claimed by the worker.
 *
 * Cancellation remains cancellation.  Other failures are collected until all
 * siblings finish, then rethrown so the worker cannot report a handled success.
 */
internal suspend fun <T> runDownloadItemsIndependently(
    items: Iterable<T>,
    runItem: suspend (T) -> Unit,
) {
    val failures = ConcurrentLinkedQueue<Exception>()
    supervisorScope {
        val scopeJob = currentCoroutineContext()[Job]
        items.forEach { item ->
            launch(Dispatchers.IO) {
                try {
                    runItem(item)
                } catch (cancelled: CancellationException) {
                    scopeJob?.cancel(cancelled)
                    throw cancelled
                } catch (failure: Exception) {
                    failures.add(failure)
                }
            }
        }
    }
    failures.firstOrNull()?.let { throw it }
}
