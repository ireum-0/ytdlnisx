package com.ireum.ytdl.ui.more.settings

import android.content.Context
import com.ireum.ytdl.work.CleanupScheduleCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the asynchronous handoff from the cleanup ListPreference to the
 * durable schedule coordinator.  The Preference callback must return false:
 * the coordinator, rather than Preference's automatic persistence, owns the
 * cleanup cadence authority.
 */
internal class CleanupSchedulePreferenceController(
    context: Context,
    private val scope: CoroutineScope,
    private val applyPersistedCadence: (String) -> Unit,
    private val configure: suspend (Context, String?) -> Boolean = { appContext, cadence ->
        CleanupScheduleCoordinator.configure(appContext, cadence)
    },
) {
    private val appContext = context.applicationContext
    private var latestRequest = 0L
    private var transitionJob: Job? = null

    /**
     * Starts a lifecycle-owned transition and keeps the Preference value at
     * the last durable coordinator state until that transition has completed.
     */
    fun request(newValue: Any?): Boolean {
        val requestedCadence = newValue as? String ?: return false
        val requestId = ++latestRequest
        transitionJob?.cancel()
        transitionJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    configure(appContext, requestedCadence)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Read the coordinator-owned value below.  A failed commit
                // leaves the previous value; a committed authority followed
                // by enqueue failure remains visible and recoverable.
            }

            if (requestId != latestRequest) return@launch
            val persistedCadence = CleanupScheduleCoordinator
                .currentCadenceForSettings(appContext)
            applyPersistedCadence(persistedCadence)
        }
        return false
    }

    /**
     * Invalidates a pending preference transition before a screen-level reset
     * takes ownership of the cleanup cadence.  Cancelling the lifecycle job
     * also prevents a late completion from applying or publishing an older
     * user request after reset has started.
     */
    fun cancelPendingRequest() {
        latestRequest++
        transitionJob?.cancel()
        transitionJob = null
    }
}
