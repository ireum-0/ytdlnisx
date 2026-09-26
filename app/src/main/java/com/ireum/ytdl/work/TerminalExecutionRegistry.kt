package com.ireum.ytdl.work

import android.content.Context
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import com.ireum.ytdl.util.storage.CacheMaintenanceAuthority
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * In-process admission fence for Terminal executions.  A durable journal is
 * the authority across process death; this registry closes the smaller race
 * in which startup reconciliation and a second worker observe the same
 * Terminal subject concurrently.  The token is deliberately execution
 * scoped and is never inferred from the DAO row alone.
 */
internal object TerminalExecutionRegistry {
    internal enum class Admission {
        ACQUIRED,
        ALREADY_COMMITTED,
        TERMINAL_FAILURE,
        BLOCKED,
        RECOVERY_FAILURE,
    }

    private val mutex = Mutex()
    private val activeTokens = mutableMapOf<Long, String>()
    /** Process-held proof for executions that crossed the post-native effect boundary. */
    private val effectLeases = mutableMapOf<Long, Pair<String, TerminalExecutionRecovery.EffectLease>>()
    private val activeLock = Any()

    suspend fun admit(
        context: Context,
        cacheRoot: File,
        subjectId: Long,
        executionToken: String,
        processId: String = YtdlpProcessIdentity.terminal(subjectId),
    ): Admission = CacheMaintenanceAuthority.withExecutionAdmission {
        mutex.withLock {
            synchronized(activeLock) {
                val existing = activeTokens[subjectId]
                if (existing != null) {
                    if (TerminalExecutionRecovery.canRelease(context, subjectId, existing)) {
                        activeTokens.remove(subjectId)
                    } else {
                        return@withLock Admission.BLOCKED
                    }
                }
                // Install the in-process fence before running the durable
                // admission checks.  Startup reconciliation can therefore never
                // mistake the tiny ACQUIRED-to-witness interval for an abandoned
                // execution; the witness is still written before this method
                // returns or native work is allowed to start.
                activeTokens[subjectId] = executionToken
            }

            fun clearProvisional() {
                synchronized(activeLock) {
                    if (activeTokens[subjectId] == executionToken) activeTokens.remove(subjectId)
                }
            }

            val existingWitness = try {
                TerminalExecutionRecovery.inspectAdmission(
                    context = context,
                    subjectId = subjectId,
                    activeExecution = { token -> isActiveNow(token) && token != executionToken },
                )
            } catch (_: Throwable) {
                clearProvisional()
                return@withLock Admission.RECOVERY_FAILURE
            }
            when (existingWitness) {
                TerminalExecutionRecovery.Admission.ALREADY_COMMITTED -> {
                    clearProvisional()
                    return@withLock Admission.ALREADY_COMMITTED
                }
                TerminalExecutionRecovery.Admission.TERMINAL_FAILURE -> {
                    clearProvisional()
                    return@withLock Admission.TERMINAL_FAILURE
                }
                TerminalExecutionRecovery.Admission.BLOCKED -> {
                    clearProvisional()
                    return@withLock Admission.BLOCKED
                }
                TerminalExecutionRecovery.Admission.PERSISTENCE_FAILURE -> {
                    clearProvisional()
                    return@withLock Admission.RECOVERY_FAILURE
                }
                TerminalExecutionRecovery.Admission.NO_WITNESS,
                TerminalExecutionRecovery.Admission.ACQUIRED -> Unit
            }

            val witnessEstablished = TerminalExecutionRecovery.begin(
                context = context,
                subjectId = subjectId,
                executionToken = executionToken,
                processId = processId,
            )
            if (!witnessEstablished) {
                clearProvisional()
                return@withLock Admission.RECOVERY_FAILURE
            }

            // A legacy/native generation may predate the durable execution
            // witness.  Its presence is still a positive reason to fence this
            // subject; admitting a second worker would otherwise rely on the
            // native process-ID collision path rather than an exact recovery
            // decision.
            try {
                YtdlpNativeProcessBarrier.configure(context)
            } catch (_: Throwable) {
                clearProvisional()
                return@withLock Admission.RECOVERY_FAILURE
            }
            val nativeAlreadyPresent = try {
                YoutubeDLCompat.hasProcessById(processId)
            } catch (_: Throwable) {
                // A failed native-liveness read is not proof that the process is
                // absent. Keep the durable ADMITTED witness and clear only the
                // process-local fence; the next recovery pass must reconcile the
                // exact subject before another execution can be admitted.
                clearProvisional()
                return@withLock Admission.RECOVERY_FAILURE
            }
            if (nativeAlreadyPresent) {
                TerminalExecutionRecovery.abandonAdmission(context, subjectId, executionToken)
                clearProvisional()
                return@withLock Admission.BLOCKED
            }

            val recoveryDecision = try {
                TerminalPublicationRecovery.admit(
                    context = context,
                    cacheRoot = cacheRoot,
                    subjectId = subjectId,
                    activeExecution = { token -> isActiveNow(token) && token != executionToken },
                    admittingExecutionToken = executionToken,
                )
            } catch (failure: Throwable) {
                clearProvisional()
                return@withLock Admission.RECOVERY_FAILURE
            }
            val decision = when (recoveryDecision) {
                TerminalPublicationRecovery.Admission.ACQUIRED -> Admission.ACQUIRED
                TerminalPublicationRecovery.Admission.ALREADY_COMMITTED -> Admission.ALREADY_COMMITTED
                TerminalPublicationRecovery.Admission.TERMINAL_FAILURE -> Admission.TERMINAL_FAILURE
                TerminalPublicationRecovery.Admission.BLOCKED -> Admission.BLOCKED
            }
            if (decision != Admission.ACQUIRED) {
                // No native/effect boundary has been crossed. Remove only the
                // provisional witness; if deletion is not proven, leave it as a
                // conservative recovery owner instead of pretending there is no
                // durable state.
                TerminalExecutionRecovery.abandonAdmission(context, subjectId, executionToken)
                clearProvisional()
            }
            decision
        }
    }

    suspend fun release(
        context: Context,
        subjectId: Long,
        executionToken: String?,
    ) = mutex.withLock {
        val record = executionToken?.let { TerminalExecutionRecovery.read(context, subjectId) }
        val settledEffectPhases = setOf(
            TerminalExecutionRecovery.Phase.TERMINAL_FAILURE,
            TerminalExecutionRecovery.Phase.TERMINAL_STOPPED,
            TerminalExecutionRecovery.Phase.COMMITTING,
            TerminalExecutionRecovery.Phase.COMMITTED,
        )
        val durableEffectStillActive = record?.let {
            it.executionToken == executionToken &&
                it.phase in setOf(
                    TerminalExecutionRecovery.Phase.POST_NATIVE_EFFECTS,
                    TerminalExecutionRecovery.Phase.POST_NATIVE_PUBLISHING,
                )
        } == true
        val heldEffectLease = executionToken != null &&
            effectLeases[subjectId]?.first == executionToken
        val effectNotDurablySettled = heldEffectLease &&
            record?.let { it.executionToken == executionToken && it.phase in settledEffectPhases } != true
        if (
            !durableEffectStillActive && !effectNotDurablySettled &&
            executionToken != null &&
                synchronized(activeLock) { activeTokens[subjectId] == executionToken } &&
                (
                    TerminalExecutionRecovery.canRelease(context, subjectId, executionToken) ||
                        // Durable nonterminal records are the cross-process
                        // owner. Releasing only the process-local map lets a
                        // later admission invoke recovery; it still cannot
                        // cross native execution while the durable record is
                        // pending or malformed.
                        TerminalExecutionRecovery.hasRecordFile(context, subjectId)
                    )
        ) {
            releaseEffectLeaseIfSettled(context, subjectId, executionToken)
            synchronized(activeLock) {
                if (activeTokens[subjectId] == executionToken) activeTokens.remove(subjectId)
            }
        }
    }

    /**
     * Start durable post-native effect ownership before touching output,
     * journal, provider, or cache state. Cancellation and this transition are
     * serialized by the registry mutex.
     */
    internal suspend fun beginPostNativeEffects(
        context: Context,
        subjectId: Long,
        executionToken: String,
    ): Boolean = mutex.withLock {
        if (synchronized(activeLock) { activeTokens[subjectId] != executionToken }) return@withLock false
        if (effectLeases.containsKey(subjectId)) return@withLock false
        val lease = TerminalExecutionRecovery.beginPostNativeEffects(
            context = context,
            subjectId = subjectId,
            executionToken = executionToken,
        ) ?: return@withLock false
        effectLeases[subjectId] = executionToken to lease
        true
    }

    /** Linearizes the final pre-publication gate against user cancellation. */
    internal suspend fun markPublicationStarted(
        context: Context,
        subjectId: Long,
        executionToken: String,
    ): Boolean = mutex.withLock {
        if (synchronized(activeLock) { activeTokens[subjectId] != executionToken }) return@withLock false
        val lease = effectLeases[subjectId]?.takeIf { it.first == executionToken }?.second
            ?: return@withLock false
        TerminalExecutionRecovery.markPublicationStarted(
            context = context,
            subjectId = subjectId,
            executionToken = executionToken,
            effectLease = lease,
        )
    }

    /** Only the exact live effect owner can persist its quiescent terminal state. */
    internal suspend fun completePostNativeEffects(
        context: Context,
        subjectId: Long,
        executionToken: String,
        outcome: TerminalExecutionRecovery.Outcome,
    ): Boolean = mutex.withLock {
        if (synchronized(activeLock) { activeTokens[subjectId] != executionToken }) return@withLock false
        val lease = effectLeases[subjectId]?.takeIf { it.first == executionToken }?.second
            ?: return@withLock false
        TerminalExecutionRecovery.completePostNativeEffects(
            context = context,
            subjectId = subjectId,
            executionToken = executionToken,
            effectLease = lease,
            fallbackOutcome = outcome,
        )
    }

    /**
     * Serialize user cancellation with admission.  A missing witness means a
     * worker has not crossed durable admission and may be removed after its
     * WorkManager request is cancelled.  Any witness is converged through the
     * exact native-generation/quiescence protocol before the row can be
     * deleted; an unreadable witness is conservatively retained.
     */
    internal suspend fun cancel(
        context: Context,
        subjectId: Long,
    ): Boolean = mutex.withLock {
        val token = synchronized(activeLock) { activeTokens[subjectId] }
            ?: TerminalExecutionRecovery.read(context, subjectId)?.executionToken
        if (token == null) {
            return@withLock !TerminalExecutionRecovery.hasRecordFile(context, subjectId)
        }
        val converged = TerminalExecutionRecovery.convergeTerminal(
            context = context,
            subjectId = subjectId,
            executionToken = token,
            outcome = TerminalExecutionRecovery.Outcome.STOPPED,
        )
        if (converged) {
            val workerStillOwnsEffects = effectLeases[subjectId]?.first == token &&
                synchronized(activeLock) { activeTokens[subjectId] == token }
            if (!workerStillOwnsEffects) {
                releaseEffectLeaseIfSettled(context, subjectId, token)
                synchronized(activeLock) {
                    if (activeTokens[subjectId] == token) activeTokens.remove(subjectId)
                }
            }
        }
        converged
    }

    internal suspend fun isActive(executionToken: String): Boolean = mutex.withLock {
        isActiveNow(executionToken)
    }

    internal fun isActiveNow(executionToken: String): Boolean = synchronized(activeLock) {
        activeTokens.values.any { it == executionToken }
    }

    private fun releaseEffectLeaseIfSettled(
        context: Context,
        subjectId: Long,
        executionToken: String?,
    ) {
        if (executionToken == null) return
        val record = TerminalExecutionRecovery.read(
            context = context,
            subjectId = subjectId,
        )
        if (
            record?.let {
                it.executionToken == executionToken &&
                    it.phase in setOf(
                    TerminalExecutionRecovery.Phase.TERMINAL_FAILURE,
                    TerminalExecutionRecovery.Phase.TERMINAL_STOPPED,
                    TerminalExecutionRecovery.Phase.COMMITTING,
                    TerminalExecutionRecovery.Phase.COMMITTED,
                )
            } != true
        ) return
        val owner = effectLeases[subjectId]?.takeIf { it.first == executionToken } ?: return
        effectLeases.remove(subjectId)
        owner.second.close()
    }
}
