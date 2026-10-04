package com.ireum.ytdl.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-lifetime responsibility for the exact desired startup generation.
 * Prerequisites and retries run without Restore admission or runtime leases.
 * Only UpdateUtil may admit and prove the actual runtime mutation.
 */
internal class StartupYtdlpUpdateOwner(
    context: Context,
    scope: CoroutineScope,
    private val generationObserved: ((UpdateUtil.DesiredSource) -> Unit)? = null,
    private val attemptCompleted: ((UpdateUtil.DesiredSource, UpdateUtil.YTDLPUpdateResponse) -> Unit)? = null,
    private val awaitIdleWakeupForTesting: (suspend () -> Unit)? = null,
    private val awaitPrerequisites: suspend () -> Unit,
) {
    private val update = UpdateUtil(context.applicationContext)
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "auto_update_ytdlp" || key.startsWith("ytdlp_")) wake()
    }
    private var satisfied: UpdateUtil.DesiredSource? = null

    private val job = scope.launch(Dispatchers.IO) {
        while (currentCoroutineContext().isActive) {
            // Whole-graph clear is silent before Android R; on R+ its key is
            // null. Keep responsibility even if no preference callback arrives.
            // The exact committed-generation check below makes idle rechecks
            // observations, not repeated automatic native updates.
            if (awaitIdleWakeupForTesting != null) {
                awaitIdleWakeupForTesting.invoke()
            } else {
                withTimeoutOrNull(60_000L) { wakeups.receive() }
            }
            var retryDelay = 2_000L
            var retryGeneration: UpdateUtil.DesiredSource? = null
            while (currentCoroutineContext().isActive) {
                try {
                    val requested = update.desiredSource()
                    generationObserved?.invoke(requested)
                    if (requested == satisfied && update.startupGenerationIsCommitted(requested)) break
                    if (retryGeneration != requested) {
                        retryGeneration = requested
                        retryDelay = 2_000L
                    }
                    awaitPrerequisites()
                    // Readiness/recovery may have published a newer Restore selection.
                    if (update.desiredSource() != requested) continue
                    val result = update.updateStartupGeneration(
                        requested,
                        preferences.getBoolean("auto_update_ytdlp", false),
                    )
                    attemptCompleted?.invoke(requested, result)
                    if ((result.status == UpdateUtil.YTDLPUpdateStatus.DONE ||
                            result.status == UpdateUtil.YTDLPUpdateStatus.ALREADY_UP_TO_DATE) &&
                        update.startupGenerationIsCommitted(requested)
                    ) {
                        satisfied = requested
                        break
                    }
                    if (result.status == UpdateUtil.YTDLPUpdateStatus.SUPERSEDED &&
                        update.desiredSource() != requested
                    ) continue
                } catch (cancelled: CancellationException) {
                    // Attempt/native cancellation can be transient (for example
                    // Restore quiescence). Only cancellation of this owner retires
                    // its responsibility for the still-uncommitted generation.
                    currentCoroutineContext().ensureActive()
                } catch (failure: Exception) {
                    Log.w("StartupYtdlpUpdateOwner", "Startup yt-dlp convergence deferred", failure)
                }
                // A recovery completion need not mutate preferences. Keep a bounded
                // retry owner as well as conflated preference/lifecycle wakeups.
                withTimeoutOrNull(retryDelay) { wakeups.receive() }
                retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
            }
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        wake()
    }

    internal fun wake() {
        wakeups.trySend(Unit)
    }

    /** Tests retire this real owner before replacing its native runtime fixture. */
    internal suspend fun stop() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        job.cancelAndJoin()
        wakeups.close()
    }
}
