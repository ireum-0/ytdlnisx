package com.ireum.ytdl.work

import android.content.Context
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.storage.TerminalCacheOwnership
import java.io.File

/**
 * Startup-only reconciliation for Terminal publication journals.
 *
 * A process can die after an exact move has created one or more destinations
 * but before TerminalDownloadWorker reaches its failure cleanup.  In that
 * window the app-private publication journal is durable while the live cache
 * marker is still present.  Convert only that exact journal/root pair into a
 * marker-revoked recovery carrier.  Recovery carriers are intentionally not
 * ordinary cache-import roots and this object never moves or deletes artifact
 * files.
 */
internal object TerminalPublicationRecovery {
    internal data class ReconcileResult(
        val journalCount: Int,
        val quarantinedCount: Int,
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
    ): ReconcileResult {
        val terminalNamespace = runCatching {
            File(cacheRoot.canonicalFile, "TERMINAL").canonicalFile
        }.getOrNull() ?: return ReconcileResult(0, 0)
        val records = PublicationRecoveryJournal.readAll(journalStorage)
            .filter { it.kind == PublicationRecoveryJournal.Kind.TERMINAL }
        var quarantinedCount = 0
        records.forEach { record ->
            val root = runCatching { File(record.sourceRoot).canonicalFile }.getOrNull()
                ?: return@forEach
            // Terminal workers use the task token as the directory name.  The
            // exact parent/name check prevents a journal from authenticating a
            // sibling, nested, or otherwise relocated directory.
            if (
                root.parentFile?.canonicalFile != terminalNamespace ||
                root.name != record.executionId ||
                !root.isDirectory
            ) return@forEach

            val marker = TerminalCacheOwnership.markerFile(root)
            val carrier = TerminalCacheOwnership.recoveryCarrierFile(root)
            if (!marker.isFile || !TerminalCacheOwnership.isOwned(root, record.executionId)) {
                return@forEach
            }

            val journal = PublicationRecoveryJournal.open(journalStorage, record)
                ?: return@forEach
            // A reservation is persisted before the provider/raw destination
            // is created.  If startup observes that exact destination, it can
            // safely complete the pair before converting the live root into
            // recovery-only quarantine state.
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
                    phase = effectiveRecord.phase.name,
                )
            ) return@forEach

            if (TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(root, record.executionId)) {
                quarantinedCount += 1
            }
        }
        return ReconcileResult(records.size, quarantinedCount)
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
