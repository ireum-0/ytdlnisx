package com.ireum.ytdl.util.player

import android.content.Context
import android.util.Log
import com.ireum.ytdl.database.DBManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A process-lifetime owner for playback-position writes.
 *
 * An Activity is allowed to disappear immediately after accepting a playback
 * position (notably from onDestroy()).  Therefore the write must not be
 * owned by that Activity's lifecycle scope.  The tail for each History ID is
 * also shared by every Activity instance, so recreation cannot create a
 * second unordered writer for the same row.
 */
internal class OrderedPlaybackPositionWriter<T>(
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit = {},
    private val write: suspend (historyId: Long, value: T) -> Unit
) {
    private val lock = Any()
    private val tails = mutableMapOf<Long, CompletableDeferred<Unit>>()

    /**
     * Accept one logical submission and return a handle for its eventual
     * durable write.  Submissions for different History IDs are independent;
     * submissions for one ID are linked in acceptance order.
     */
    fun submit(historyId: Long, value: T): Job {
        val completion = CompletableDeferred<Unit>()
        val predecessor = synchronized(lock) {
            tails.put(historyId, completion)
        }

        return scope.launch {
            try {
                // Completion is deliberately normal even when the preceding
                // write failed, so one transient failure cannot strand all
                // later accepted positions for the row.
                predecessor?.await()
                write(historyId, value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                onFailure(failure)
            } finally {
                completion.complete(Unit)
                synchronized(lock) {
                    if (tails[historyId] === completion) {
                        tails.remove(historyId)
                    }
                }
            }
        }
    }
}

/**
 * Durable playback-position persistence shared by all VideoPlayerActivity
 * instances in this process.  The scope intentionally is not tied to an
 * Activity lifecycle; accepted writes remain owned after onDestroy().
 */
object PlaybackPositionPersistence {
    private const val TAG = "PlaybackPosition"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Request(val context: Context, val positionMs: Long)

    private val writer = OrderedPlaybackPositionWriter<Request>(
        scope = scope,
        write = { historyId, request ->
            // UPDATE affects zero rows when the History item was deleted. It
            // cannot recreate a missing row, which is the required behavior
            // for delayed playback writes.
            DBManager.getInstance(request.context)
                .historyDao
                .updatePlaybackPosition(historyId, request.positionMs)
        },
        onFailure = { failure ->
            Log.w(TAG, "Ordered playback-position persistence failed", failure)
        }
    )

    fun submit(context: Context, historyId: Long, positionMs: Long): Job {
        return writer.submit(
            historyId = historyId,
            value = Request(context.applicationContext, positionMs)
        )
    }
}
