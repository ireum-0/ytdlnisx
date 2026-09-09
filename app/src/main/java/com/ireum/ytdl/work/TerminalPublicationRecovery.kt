package com.ireum.ytdl.work

import android.content.Context
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.storage.TerminalCacheOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reconciliation for Terminal publication journals, used both by the worker
 * after a failed attempt and by App startup after process death.
 *
 * A process can die after an exact move has created one or more destinations
 * but before TerminalDownloadWorker reaches its failure cleanup.  In that
 * window the app-private publication journal is durable while the live cache
 * marker is still present.  Convert only that exact journal/root pair into a
 * marker-revoked recovery carrier.  Recovery carriers are intentionally not
 * ordinary cache-import roots. This object never moves artifacts; it removes
 * only carrier-listed exact remainder files after a verified terminal result.
 */
internal object TerminalPublicationRecovery {
    private const val UNKNOWN_QUARANTINE_PHASE = "QUARANTINED_UNKNOWN"

    internal data class ReconcileResult(
        val journalCount: Int,
        val quarantinedCount: Int,
        val retiredCount: Int = 0,
    )

    /**
     * Synchronous-before-native admission result.  ALREADY_COMMITTED means
     * that a prior Terminal attempt had already published its exact outputs;
     * the WorkManager invocation must complete as a no-op rather than execute
     * the command again.  BLOCKED preserves an unresolved prior obligation.
     */
    internal enum class Admission {
        ACQUIRED,
        ALREADY_COMMITTED,
        /** A fenced UNKNOWN provider outcome was terminalized; do not retry. */
        TERMINAL_FAILURE,
        BLOCKED,
    }

    /**
     * Reconcile exact prior Terminal publication state before a new native
     * execution is admitted.  A row is only a mutable queue record; a
     * COMMITTING/COMPLETE journal with all exact destinations present is the
     * durable semantic witness.  If no exact witness exists, a prior journal
     * or quarantine carrier blocks re-entry rather than allowing duplicate
     * output production.
     */
    internal suspend fun admit(
        context: Context,
        cacheRoot: File,
        subjectId: Long,
        activeExecution: (String) -> Boolean,
        admittingExecutionToken: String? = null,
    ): Admission = withContext(Dispatchers.IO + NonCancellable) {
        val journalStorage = File(context.filesDir, "publication-recovery")
        val before = PublicationRecoveryJournal.readAll(journalStorage)
            .filter {
                it.kind == PublicationRecoveryJournal.Kind.TERMINAL &&
                    it.subjectId == subjectId.toString()
            }
        if (before.any { activeExecution(it.executionId) }) return@withContext Admission.BLOCKED

        val unknownBefore = before.filter { record ->
            PublicationRecoveryJournal.isUnknownTerminal(record) ||
                record.artifacts.any {
                    PublicationRecoveryJournal.isUnknownReservation(it.reservedDestinationPath) ||
                        PublicationRecoveryJournal.isReservationIntent(it.reservedDestinationPath)
                }
        }
        if (unknownBefore.isNotEmpty()) {
            // The process-local execution fence is the only positive liveness
            // proof available after a restart.  A Terminal DAO row by itself
            // is not enough to justify replaying an opaque provider create.
            reconcile(
                context = context,
                cacheRoot = cacheRoot,
                journalStorage = journalStorage,
                terminalRowExists = { id ->
                    DBManager.getInstance(context).terminalDao.getTerminalById(id) != null
                },
                allowUnknownWithoutRow = true,
                activeExecution = activeExecution,
            )
            val afterUnknown = PublicationRecoveryJournal.readAll(journalStorage)
                .filter {
                    it.kind == PublicationRecoveryJournal.Kind.TERMINAL &&
                        it.subjectId == subjectId.toString()
                }
                .any { record ->
                    PublicationRecoveryJournal.isUnknownTerminal(record) ||
                        record.artifacts.any {
                            PublicationRecoveryJournal.isUnknownReservation(
                                it.reservedDestinationPath,
                            )
                        }
                }
            val quarantinedRemainders = TerminalCacheOwnership
                .listRecoveryRoots(cacheRoot)
                .filter { it.subjectId?.toLongOrNull() == subjectId }
            if (
                afterUnknown || quarantinedRemainders.any {
                    it.phase == UNKNOWN_QUARANTINE_PHASE
                }
            ) {
                // Terminal failure is durable in the journal/carrier.  Remove
                // the queue row when possible so startup observers cannot
                // treat it as runnable work; a failed delete leaves the same
                // admission fence in place and still never runs native code.
                runCatching {
                    val dao = DBManager.getInstance(context).terminalDao
                    dao.delete(subjectId)
                }
                return@withContext Admission.TERMINAL_FAILURE
            }
        }

        fun allDestinationsExist(record: PublicationRecoveryJournal.Record): Boolean =
            record.artifacts.all { artifact ->
                val destination = artifact.destinationPath
                !destination.isNullOrBlank() && destinationExists(destination, context)
            }

        val committedWitness = before.firstOrNull { record ->
            record.phase in setOf(
                PublicationRecoveryJournal.Phase.COMMITTING,
                PublicationRecoveryJournal.Phase.COMPLETE,
                PublicationRecoveryJournal.Phase.COMMITTED,
            ) && allDestinationsExist(record)
        }
        if (committedWitness != null) {
            val dao = DBManager.getInstance(context).terminalDao
            // Adopt the durable semantic result before a new worker can
            // create a task token.  The delete is idempotent and does not
            // depend on row presence as proof of liveness.
            val rowDeleted = runCatching {
                dao.delete(subjectId)
                dao.getTerminalById(subjectId) == null
            }.getOrDefault(false)
            if (!rowDeleted) {
                // Keep the exact witness and block admission when the DAO
                // cannot converge.  Returning ALREADY_COMMITTED here would
                // let a later WorkManager retry observe the same row and
                // attempt the native command again.
                return@withContext Admission.BLOCKED
            }
            PublicationRecoveryJournal.open(journalStorage, committedWitness)?.let { journal ->
                // These are convergence sidecars only.  A failure leaves the
                // exact journal for startup recovery, but never reopens the
                // native command.
                val markedCommitted = runCatching {
                    journal.markPhase(PublicationRecoveryJournal.Phase.COMMITTED)
                }.getOrDefault(false)
                val rootRetired = if (markedCommitted) {
                    runCatching { retireCommittedRoot(committedWitness) }.getOrDefault(false)
                } else {
                    false
                }
                // Clear only after both the phase and exact staging-root
                // convergence succeeded.  If either sidecar fails, the
                // COMMITTED witness remains durable for a later admission or
                // startup retry; it is never replaced by a best-effort scan.
                if (markedCommitted && rootRetired) {
                    runCatching { journal.clear() }
                }
            }
            return@withContext Admission.ALREADY_COMMITTED
        }

        // Startup reconciliation may already have converted a partial
        // journal into a marker-revoked recovery carrier.  That carrier is
        // explicit quarantine state, not a new worker's staging root.
        val recoveryRemainders = TerminalCacheOwnership.listRecoveryRoots(cacheRoot)
            .filter { it.subjectId?.toLongOrNull() == subjectId }
        if (recoveryRemainders.any { it.phase == UNKNOWN_QUARANTINE_PHASE }) {
            runCatching {
                DBManager.getInstance(context).terminalDao.delete(subjectId)
            }
            return@withContext Admission.TERMINAL_FAILURE
        }
        if (recoveryRemainders.isNotEmpty()) {
            // A marker-revoked generic carrier is an abandoned failed
            // execution, not a live owner.  Once the exact Terminal native
            // identity is positively absent, install a durable terminal
            // tombstone before deleting the stale queue row. This gives the
            // old carrier a convergence owner and prevents a permanent
            // BLOCKED -> WorkManager retry fixed point. A live/opaque native
            // generation remains blocked and is never stolen by recovery.
            if (recoveryRemainders.any { activeExecution(it.taskToken) } ||
                YoutubeDLCompat.hasProcessById(YtdlpProcessIdentity.terminal(subjectId))
            ) {
                return@withContext Admission.BLOCKED
            }
            val generic = recoveryRemainders.first()
            val provisionalWitness = admittingExecutionToken?.let { token ->
                TerminalExecutionRecovery.read(context, subjectId)?.let { witness ->
                    witness.executionToken == token &&
                        witness.phase == TerminalExecutionRecovery.Phase.ADMITTED
                }
            } == true
            if (
                !provisionalWitness &&
                !TerminalExecutionRecovery.recordLegacyTerminalFailure(
                    context = context,
                    subjectId = subjectId,
                    executionToken = generic.taskToken,
                    processId = YtdlpProcessIdentity.terminal(subjectId),
                )
            ) {
                return@withContext Admission.BLOCKED
            }
            runCatching {
                DBManager.getInstance(context).terminalDao.delete(subjectId)
            }
            reconcile(
                context = context,
                cacheRoot = cacheRoot,
                journalStorage = journalStorage,
                terminalRowExists = { id ->
                    DBManager.getInstance(context).terminalDao.getTerminalById(id) != null
                },
                allowUnknownWithoutRow = true,
                activeExecution = activeExecution,
            )
            return@withContext Admission.TERMINAL_FAILURE
        }

        // A journal in any non-terminal phase is an exact prior obligation,
        // even if its root currently has no files.  Do not let a new native
        // execution race its recovery owner.
        if (before.isNotEmpty()) return@withContext Admission.BLOCKED

        // Run the normal startup reconciliation for records that may have
        // become visible between the initial read and this admission check,
        // then re-check the durable namespace once more.
        reconcile(
            context = context,
            cacheRoot = cacheRoot,
            journalStorage = journalStorage,
            terminalRowExists = { id -> DBManager.getInstance(context).terminalDao.getTerminalById(id) != null },
            activeExecution = activeExecution,
        )
        val after = PublicationRecoveryJournal.readAll(journalStorage).filter {
            it.kind == PublicationRecoveryJournal.Kind.TERMINAL && it.subjectId == subjectId.toString()
        }
        if (after.isNotEmpty() || TerminalCacheOwnership.listRecoveryRoots(cacheRoot).any {
                it.subjectId?.toLongOrNull() == subjectId
            }) {
            Admission.BLOCKED
        } else {
            Admission.ACQUIRED
        }
    }

    fun reconcile(
        context: Context,
        cacheRoot: File,
        activeExecution: ((String) -> Boolean)? = null,
    ): ReconcileResult = reconcile(
        cacheRoot = cacheRoot,
        journalStorage = File(context.filesDir, "publication-recovery"),
        context = context,
        activeExecution = activeExecution,
    )

    internal fun reconcile(
        cacheRoot: File,
        journalStorage: File,
        context: Context? = null,
        terminalRowExists: ((Long) -> Boolean)? = null,
        allowUnknownWithoutRow: Boolean = false,
        activeExecution: ((String) -> Boolean)? = null,
    ): ReconcileResult {
        val terminalNamespace = runCatching {
            File(cacheRoot.canonicalFile, "TERMINAL").canonicalFile
        }.getOrNull()
        val records = PublicationRecoveryJournal.readAll(journalStorage)
            .filter { it.kind == PublicationRecoveryJournal.Kind.TERMINAL }
        val unknownRecoveryRoots = records.asSequence()
            .filter { record ->
                PublicationRecoveryJournal.isUnknownTerminal(record) ||
                    record.artifacts.any {
                        PublicationRecoveryJournal.isUnknownReservation(it.reservedDestinationPath) ||
                            PublicationRecoveryJournal.isReservationIntent(it.reservedDestinationPath)
                    }
            }
            .mapNotNull { record ->
                runCatching { File(record.sourceRoot).canonicalFile.absolutePath }.getOrNull()
            }
            .toSet()
        var quarantinedCount = 0
        var retiredCount = 0
        records.forEach { record ->
            val journal = PublicationRecoveryJournal.open(journalStorage, record)
                ?: return@forEach
            // Resolve reservations before deciding whether the durable record
            // is complete.  A reservation is exact authority for one
            // destination, never a directory-membership hint.
            var reservationFailed = false
            var unknownReservation = false
            record.artifacts
                .filter { it.destinationPath.isNullOrBlank() }
                .forEach { artifact ->
                    val reserved = artifact.reservedDestinationPath
                    if (!reserved.isNullOrBlank()) {
                        if (
                            PublicationRecoveryJournal.isUnknownReservation(reserved) ||
                                PublicationRecoveryJournal.isReservationIntent(reserved)
                        ) {
                            // An UNKNOWN or still-pending provider intent is
                            // terminalized below once the exact
                            // recovery/quarantine carrier is durable. It is
                            // never interpreted as a destination.
                            unknownReservation = true
                            reservationFailed = true
                        } else if (FileUtil.isRecoverablePublicationComplete(
                                sourcePath = artifact.sourcePath,
                                destinationPath = reserved,
                                context = context,
                            )
                        ) {
                            if (!journal.markPublished(artifact.sourcePath, reserved)) {
                                reservationFailed = true
                            }
                        } else if (!destinationExists(reserved, context)) {
                            if (!journal.clearReservation(artifact.sourcePath)) {
                                reservationFailed = true
                            }
                        } else {
                            // A provider document may be visible before its
                            // stream copy/finalization is complete.  Do not
                            // promote from existence alone; preserve the
                            // exact reservation for a later proof-producing
                            // reconciliation.
                            reservationFailed = true
                        }
                    }
                }
            if (
                PublicationRecoveryJournal.isUnknownTerminal(record) &&
                    record.artifacts.any {
                        PublicationRecoveryJournal.isUnknownReservation(it.reservedDestinationPath) ||
                            PublicationRecoveryJournal.isReservationIntent(it.reservedDestinationPath)
                    }
            ) {
                unknownReservation = true
            }
            if (unknownReservation) {
                val root = runCatching { File(record.sourceRoot).canonicalFile }.getOrNull()
                val terminalNamespace = runCatching {
                    File(cacheRoot.canonicalFile, "TERMINAL").canonicalFile
                }.getOrNull()
                if (
                    root == null || terminalNamespace == null ||
                        root.parentFile?.canonicalFile != terminalNamespace ||
                        root.name != record.executionId ||
                        !root.isDirectory
                ) {
                    // The journal itself remains the durable quarantine
                    // evidence when no exact Terminal carrier can be safely
                    // reconstructed.  It is still non-runnable and cannot be
                    // replayed or promoted.
                    journal.terminalizeUnknownReservation()
                    return@forEach
                }
                val marker = TerminalCacheOwnership.markerFile(root)
                val carrier = TerminalCacheOwnership.recoveryCarrierFile(root)
                val markerIsLive = marker.isFile &&
                    TerminalCacheOwnership.isOwned(root, record.executionId)
                if (markerIsLive) {
                    if (activeExecution?.invoke(record.executionId) == true) return@forEach
                    if (
                        !allowUnknownWithoutRow &&
                            (context != null || terminalRowExists != null) &&
                            !terminalRowAbsent(record, context, terminalRowExists)
                    ) return@forEach
                    if (!TerminalCacheOwnership.artifactManifestFile(root).isFile) {
                        journal.terminalizeUnknownReservation()
                        return@forEach
                    }
                    if (
                        (!carrier.isFile ||
                            !TerminalCacheOwnership.isValidRecoveryCarrier(
                                root,
                                record.executionId,
                                requiredPhase = UNKNOWN_QUARANTINE_PHASE,
                                requiredSubjectId = record.subjectId,
                            )) &&
                        !TerminalCacheOwnership.recordRecoveryCarrier(
                            directory = root,
                            taskToken = record.executionId,
                            publishedDestinationPaths = record.publishedDestinations(),
                            phase = UNKNOWN_QUARANTINE_PHASE,
                            subjectId = record.subjectId,
                        )
                    ) return@forEach
                    if (!TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(root, record.executionId)) {
                        return@forEach
                    }
                    quarantinedCount += 1
                }
                val carrierIsValid = carrier.isFile &&
                    TerminalCacheOwnership.isValidRecoveryCarrier(
                        root,
                        record.executionId,
                        requiredPhase = UNKNOWN_QUARANTINE_PHASE,
                        requiredSubjectId = record.subjectId,
                    )
                if (!marker.isFile && carrierIsValid) {
                    if (
                        journal.terminalizeUnknownReservation() &&
                            journal.clear()
                    ) retiredCount += 1
                } else if (!marker.isFile) {
                    // Preserve the terminalized journal if a provider-bound
                    // root cannot be converted into a recovery carrier.
                    journal.terminalizeUnknownReservation()
                }
                return@forEach
            }
            if (reservationFailed) return@forEach
            val effectiveRecord = journal.snapshot()

            val allDestinationsExist = effectiveRecord.artifacts.all { artifact ->
                val destination = artifact.destinationPath
                !destination.isNullOrBlank() && destinationExists(destination, context)
            }

            // A Terminal journal is not retired merely because a destination
            // happens to exist.  The worker records COMMITTING before deleting
            // its DAO row and COMMITTED only after that semantic terminal
            // boundary.  For legacy COMPLETE records, an absent DAO row is
            // the durable terminal fact that the old worker had already
            // completed its row deletion before process death.
            val semanticCommitKnown = when (effectiveRecord.phase) {
                PublicationRecoveryJournal.Phase.COMMITTED -> true
                PublicationRecoveryJournal.Phase.COMMITTING,
                PublicationRecoveryJournal.Phase.COMPLETE -> terminalRowAbsent(
                    effectiveRecord,
                    context,
                    terminalRowExists,
                )
                else -> false
            }
            if (allDestinationsExist && semanticCommitKnown) {
                // Keep a COMMITTED Terminal tombstone until the next worker
                // admission consumes it.  Clearing it from asynchronous App
                // startup would reopen the process-death window immediately
                // after dao.delete(), allowing a restarted WorkManager task
                // to run the command a second time.
                if (retireCommittedRoot(effectiveRecord) && journal.markPhase(
                        PublicationRecoveryJournal.Phase.COMMITTED,
                    )
                ) {
                    retiredCount += 1
                }
                return@forEach
            }

            // A marker-revoked carrier is already the explicit recovery-only
            // owner.  Once the journal is converted to QUARANTINED, retire the
            // duplicate journal; the carrier remains discoverable for a
            // deliberate recovery action and is never ordinary import input.

            val root = runCatching { File(record.sourceRoot).canonicalFile }.getOrNull()
                ?: return@forEach
            // Terminal workers use the task token as the directory name.  The
            // exact parent/name check prevents a journal from authenticating a
            // sibling, nested, or otherwise relocated directory.
            if (
                terminalNamespace == null ||
                root.parentFile?.canonicalFile != terminalNamespace ||
                root.name != record.executionId ||
                !root.isDirectory
            ) return@forEach

            val marker = TerminalCacheOwnership.markerFile(root)
            val carrier = TerminalCacheOwnership.recoveryCarrierFile(root)
            val markerIsLive = marker.isFile &&
                TerminalCacheOwnership.isOwned(root, record.executionId)
            val carrierIsValid = carrier.isFile &&
                TerminalCacheOwnership.isValidRecoveryCarrier(root, record.executionId)

            // A prior worker may already have revoked the marker and written
            // the recovery carrier before process death.  That carrier is the
            // explicit recovery-only semantic outcome; retire the duplicate
            // journal once it has been validated, but never promote it into
            // ordinary publication authority.
            if (!markerIsLive && carrierIsValid) {
                if (
                    journal.markPhase(PublicationRecoveryJournal.Phase.QUARANTINED) &&
                    journal.clear()
                ) retiredCount += 1
                return@forEach
            }
            if (!markerIsLive) return@forEach

            // A live marker may still belong to an actively running worker.
            // Production reconciliation must not revoke that execution's
            // root merely because a durable journal is visible.  Only a
            // proven terminal-row absence (or the explicit test equivalent)
            // allows startup to convert the live journal into quarantine.
            if ((context != null || terminalRowExists != null) &&
                !terminalRowAbsent(effectiveRecord, context, terminalRowExists)
            ) return@forEach

            // The worker creates its exact artifact manifest before opening a
            // publication journal.  If it is missing or malformed, retain the
            // live marker/journal for diagnostics rather than revoking a root
            // whose child ownership cannot be reconstructed exactly.
            if (!TerminalCacheOwnership.artifactManifestFile(root).isFile) return@forEach
            if (
                (!carrier.isFile ||
                    !TerminalCacheOwnership.isValidRecoveryCarrier(root, record.executionId)) &&
                !TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = root,
                    taskToken = record.executionId,
                    publishedDestinationPaths = effectiveRecord.publishedDestinations(),
                    phase = "QUARANTINED_FAILURE",
                    subjectId = effectiveRecord.subjectId,
                )
            ) return@forEach

            if (TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(root, record.executionId)) {
                quarantinedCount += 1
                // Once the marker-revoked carrier has been written and
                // validated, it becomes the durable recovery owner.  The
                // journal no longer needs to be a second candidate source.
                if (
                    TerminalCacheOwnership.isValidRecoveryCarrier(root, record.executionId) &&
                    journal.markPhase(PublicationRecoveryJournal.Phase.QUARANTINED) &&
                    journal.clear()
                ) {
                    retiredCount += 1
                }
            }
        }

        // Marker-revoked carriers are recovery-only state, never ordinary
        // cache-import input. Once the Terminal row is absent, that absence
        // is the durable semantic failure outcome of the old attempt. The
        // production startup/worker owner now retires only the carrier's
        // exact remaining files and control records; published destinations
        // and unknown descendants remain untouched. A live row means a
        // retry/re-entry may still be converging, so preserve the carrier.
        TerminalCacheOwnership.listRecoveryRoots(cacheRoot).forEach { recovery ->
            val subjectId = recovery.subjectId?.toLongOrNull() ?: return@forEach
            if (!terminalRowAbsent(subjectId, context, terminalRowExists)) {
                // Generic marker-revoked state has a finite convergence owner
                // once no exact native generation is live. Preserve an
                // active/opaque generation as BLOCKED; otherwise install a
                // terminal tombstone before retiring the stale queue row.
                if (
                    recovery.phase in setOf("PARTIAL_PUBLICATION", "QUARANTINED_FAILURE") &&
                    context != null &&
                    activeExecution != null &&
                    activeExecution.invoke(recovery.taskToken) != true &&
                    !YoutubeDLCompat.hasProcessById(YtdlpProcessIdentity.terminal(subjectId)) &&
                    TerminalExecutionRecovery.recordLegacyTerminalFailure(
                        context = context,
                        subjectId = subjectId,
                        executionToken = recovery.taskToken,
                        processId = YtdlpProcessIdentity.terminal(subjectId),
                    )
                ) {
                    runCatching {
                        runBlocking(Dispatchers.IO) {
                            DBManager.getInstance(context).terminalDao.delete(subjectId)
                        }
                    }
                }
                if (!terminalRowAbsent(subjectId, context, terminalRowExists)) return@forEach
            }
            // A generic carrier cannot become the successor of an UNKNOWN
            // journal.  Keep both durable records until an explicit
            // QUARANTINED_UNKNOWN carrier is established; otherwise generic
            // retirement could erase the remaining source while the UNKNOWN
            // journal is still the only semantic fence.
            if (unknownRecoveryRoots.contains(recovery.directory.absolutePath)) return@forEach
            if (recovery.phase !in setOf("PARTIAL_PUBLICATION", "QUARANTINED_FAILURE")) {
                return@forEach
            }
            if (TerminalCacheOwnership.retireRecoveryArtifacts(recovery)) {
                retiredCount += 1
            }
        }
        return ReconcileResult(records.size, quarantinedCount, retiredCount)
    }

    private fun terminalRowAbsent(
        record: PublicationRecoveryJournal.Record,
        context: Context?,
        terminalRowExists: ((Long) -> Boolean)? = null,
    ): Boolean {
        val id = record.subjectId.toLongOrNull() ?: return false
        return terminalRowAbsent(id, context, terminalRowExists)
    }

    private fun terminalRowAbsent(
        id: Long,
        context: Context?,
        terminalRowExists: ((Long) -> Boolean)? = null,
    ): Boolean {
        terminalRowExists?.let { return !runCatching { it(id) }.getOrDefault(true) }
        return context?.let {
            runCatching {
                DBManager.getInstance(it).terminalDao.getTerminalById(id) == null
            }.getOrDefault(false)
        } ?: false
    }

    /**
     * Remove only exact Terminal carrier files after semantic commit.  Any
     * unknown descendant is preserved; deleting the live marker revokes its
     * root authority even when such a child remains.
     */
    private fun retireCommittedRoot(
        record: PublicationRecoveryJournal.Record,
    ): Boolean {
        val root = runCatching { File(record.sourceRoot).canonicalFile }.getOrNull() ?: return false
        if (!root.exists()) return true
        if (!root.isDirectory) return false
        val marker = TerminalCacheOwnership.markerFile(root)
        if (marker.isFile && TerminalCacheOwnership.isOwned(root, record.executionId)) {
            if (!TerminalCacheOwnership.removeArtifactManifest(root)) return false
            if (marker.exists() && !marker.delete() && marker.exists()) return false
        } else if (marker.exists()) {
            // A malformed/live-looking marker is not safe to retire from a
            // journal alone.  Preserve the carrier for a later retry.
            return false
        }
        if (TerminalCacheOwnership.recoveryCarrierFile(root).isFile) {
            // A quarantine carrier is a separate recovery record; leave it
            // in place rather than deleting evidence as a side effect.
            return true
        }
        // No recursive deletion: only prune the now-empty exact root.
        if (root.listFiles()?.isEmpty() == true) {
            root.delete()
        }
        return true
    }

    private fun destinationExists(path: String, context: Context?): Boolean {
        val normalized = path.trim()
        if (normalized.startsWith("content://", ignoreCase = true)) {
            return context?.let { FileUtil.exists(normalized, it) } == true
        }
        return if (context == null) {
            File(normalized).exists()
        } else {
            FileUtil.exists(normalized, context)
        }
    }
}
