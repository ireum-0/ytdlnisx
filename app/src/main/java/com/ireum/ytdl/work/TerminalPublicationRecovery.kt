package com.ireum.ytdl.work

import android.content.Context
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.storage.TerminalCacheOwnership
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
    internal data class ReconcileResult(
        val journalCount: Int,
        val quarantinedCount: Int,
        val retiredCount: Int = 0,
    )

    fun reconcile(context: Context, cacheRoot: File): ReconcileResult = reconcile(
        cacheRoot = cacheRoot,
        journalStorage = File(context.filesDir, "publication-recovery"),
        context = context,
    )

    internal fun reconcile(
        cacheRoot: File,
        journalStorage: File,
        context: Context? = null,
        terminalRowExists: ((Long) -> Boolean)? = null,
    ): ReconcileResult {
        val terminalNamespace = runCatching {
            File(cacheRoot.canonicalFile, "TERMINAL").canonicalFile
        }.getOrNull()
        val records = PublicationRecoveryJournal.readAll(journalStorage)
            .filter { it.kind == PublicationRecoveryJournal.Kind.TERMINAL }
        var quarantinedCount = 0
        var retiredCount = 0
        records.forEach { record ->
            val journal = PublicationRecoveryJournal.open(journalStorage, record)
                ?: return@forEach
            // Resolve reservations before deciding whether the durable record
            // is complete.  A reservation is exact authority for one
            // destination, never a directory-membership hint.
            var reservationFailed = false
            record.artifacts
                .filter { it.destinationPath.isNullOrBlank() }
                .forEach { artifact ->
                    val reserved = artifact.reservedDestinationPath
                    if (!reserved.isNullOrBlank()) {
                        if (destinationExists(reserved, context)) {
                            if (!journal.markPublished(artifact.sourcePath, reserved)) {
                                reservationFailed = true
                            }
                        } else if (!journal.clearReservation(artifact.sourcePath)) {
                            reservationFailed = true
                        }
                    }
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
                if (retireCommittedRoot(effectiveRecord) && journal.markPhase(
                        PublicationRecoveryJournal.Phase.COMMITTED,
                    ) && journal.clear()
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
            if (!terminalRowAbsent(subjectId, context, terminalRowExists)) return@forEach
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
