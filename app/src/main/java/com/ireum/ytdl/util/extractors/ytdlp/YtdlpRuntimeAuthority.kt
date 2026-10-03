package com.ireum.ytdl.util.extractors.ytdlp

import android.content.Context
import android.os.Looper
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock

/**
 * Authority for the one app-private yt-dlp installation, independent of row
 * state and updater source-selection generations. Fair admission prevents new
 * readers from passing a waiting promotion. Leases stay on the synchronous
 * native pipeline thread; no unrelated app lock is held by this coordinator.
 *
 * A reader may hand unresolved native completion back to the existing durable
 * barrier. A writer must prove that namespace quiescent before touching files:
 * losing process-local readers after process death never grants mutation.
 */
internal object YtdlpRuntimeAuthority {
    private var lock = ReentrantReadWriteLock(true)

    @Volatile internal var consumerAdmittedForTesting: (() -> Unit)? = null
    @Volatile internal var consumerRequestedForTesting: ((String) -> Unit)? = null
    @Volatile internal var mutationRequestedForTesting: (() -> Unit)? = null
    @Volatile internal var mutationAdmittedForTesting: (() -> Unit)? = null
    @Volatile internal var mutationReleasedForTesting: (() -> Unit)? = null

    internal fun mutationOwnedByCurrentThread(): Boolean = lock.isWriteLockedByCurrentThread

    internal fun resetProcessLocalAuthorityForTesting() {
        check(!lock.isWriteLocked && lock.readLockCount == 0)
        lock = ReentrantReadWriteLock(true)
    }

    internal class Mutation internal constructor(private val owner: Thread) {
        private var active = true

        internal fun requireOwned() {
            check(active && Thread.currentThread() === owner && lock.isWriteLockedByCurrentThread) {
                "yt-dlp mutation authority is not owned by this execution"
            }
        }

        internal fun retire() { active = false }
    }

    private fun requireBackgroundThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "yt-dlp runtime authority must not wait on the main thread"
        }
    }

    private fun acquire(lease: Lock, admissionCheck: () -> Unit) {
        while (true) {
            admissionCheck()
            if (lease.tryLock(50, TimeUnit.MILLISECONDS)) return
        }
    }

    fun <T> withConsumer(context: Context, identity: String,
                        admissionCheck: () -> Unit = {}, block: () -> T): T {
        requireBackgroundThread()
        check(!lock.isWriteLockedByCurrentThread) {
            "Mutation-owned execution must not reenter ordinary consumer admission"
        }
        val reader = lock.readLock()
        consumerRequestedForTesting?.invoke(identity)
        while (true) {
            acquire(reader, admissionCheck)
            try {
                admissionCheck()
                if (YtdlpNativeProcessBarrier.runtimeMutationDebtIsAbsent(context)) {
                    consumerAdmittedForTesting?.invoke()
                    return block()
                }
            } finally {
                reader.unlock()
            }
            // Never upgrade a reader. Exclusive recovery reuses exact native
            // tokens, then validates usability before retiring publication debt.
            withMutation(context, admissionCheck) { }
        }
    }

    fun <T> withMutation(context: Context, admissionCheck: () -> Unit = {}, block: (Mutation) -> T): T {
        requireBackgroundThread()
        check(lock.readHoldCount == 0 && !lock.isWriteLockedByCurrentThread) {
            "yt-dlp runtime authority cannot be upgraded or recursively acquired"
        }
        val writer = lock.writeLock()
        mutationRequestedForTesting?.invoke()
        acquire(writer, admissionCheck)
        val authority = Mutation(Thread.currentThread())
        var publication: YtdlpNativeProcessBarrier.PreparedProcess? = null
        var verificationStarted = false
        try {
            admissionCheck()
            check(YtdlpNativeProcessBarrier.recoverRuntimeMutationNativeDebt(context)) {
                "yt-dlp mutation native recovery remains unresolved"
            }
            check(YtdlpNativeProcessBarrier.runtimeMutationIsQuiescent(context)) {
                "yt-dlp native recovery remains unresolved before runtime mutation"
            }
            publication = YtdlpNativeProcessBarrier.beginRuntimeMutation(context)
            mutationAdmittedForTesting?.invoke()
            val result = block(authority)
            verificationStarted = true
            verifyAndComplete(context, authority, publication)
            return result
        } catch (failure: Throwable) {
            // A safe fallback may make the old runtime usable despite failure.
            // Interrupted/unresolved completion retains the durable carrier.
            if (publication != null && !verificationStarted && !Thread.currentThread().isInterrupted &&
                YtdlpNativeProcessBarrier.runtimeMutationIsQuiescent(context)) {
                try {
                    verifyAndComplete(context, authority, publication)
                } catch (verificationFailure: Throwable) {
                    if (verificationFailure !== failure) failure.addSuppressed(verificationFailure)
                }
            }
            throw failure
        } finally {
            authority.retire()
            writer.unlock()
            mutationReleasedForTesting?.invoke()
        }
    }

    private fun verifyAndComplete(context: Context, authority: Mutation,
                                  publication: YtdlpNativeProcessBarrier.PreparedProcess) {
        check(YtdlpNativeProcessBarrier.runtimeMutationIsQuiescent(context)) {
            "yt-dlp mutation native generation is not quiescent"
        }
        YoutubeDLCompat.validateRuntimeUnderMutation(context, authority)
        check(YtdlpNativeProcessBarrier.completeRuntimeMutation(publication).isProvenQuiescent) {
            "yt-dlp runtime validation was not durably finalized"
        }
    }
}
