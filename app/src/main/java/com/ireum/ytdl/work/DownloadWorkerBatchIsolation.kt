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
 * Worker/global cancellation remains cancellation.  An item-local cancellation
 * may complete only that child when the caller has confirmed its durable local
 * Paused/Cancelled state.  Other failures are collected until all siblings
 * finish, then rethrown so the worker cannot report a handled success.
 */
internal suspend fun <T> runDownloadItemsIndependently(
    items: Iterable<T>,
    runItem: suspend (T) -> Unit,
) {
    runDownloadItemsIndependently(
        items = items,
        isItemLocalCancellation = { false },
        runItem = runItem,
    )
}

internal suspend fun <T> runDownloadItemsIndependently(
    items: Iterable<T>,
    isItemLocalCancellation: suspend (T) -> Boolean,
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
                    val itemLocal = if (scopeJob?.isActive == true) {
                        val localCancellation = try {
                            isItemLocalCancellation(item)
                        } catch (classificationCancellation: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        localCancellation &&
                            scopeJob?.isActive == true
                    } else {
                        false
                    }
                    if (itemLocal) return@launch
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
