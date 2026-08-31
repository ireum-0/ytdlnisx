package com.ireum.ytdl.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A producer scope whose parent is the exact Fragment-view or dialog
 * lifecycle and which can be cancelled before its Undo owner is abandoned.
 */
internal class UndoPresentationLifetime {
    private var scope: CoroutineScope? = null

    fun attach(parent: CoroutineScope): CoroutineScope {
        cancel()
        val parentJob = parent.coroutineContext[Job]
        return CoroutineScope(
            parent.coroutineContext + SupervisorJob(parentJob),
        ).also { scope = it }
    }

    fun current(): CoroutineScope? = scope

    fun cancel() {
        scope?.cancel()
        scope = null
    }
}
