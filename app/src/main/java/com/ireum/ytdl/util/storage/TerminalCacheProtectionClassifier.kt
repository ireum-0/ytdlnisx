package com.ireum.ytdl.util.storage

import android.content.Context
import com.ireum.ytdl.work.PublicationRecoveryJournal
import com.ireum.ytdl.work.TerminalExecutionRecovery
import com.ireum.ytdl.work.TerminalExecutionRegistry
import java.io.File

/**
 * Durable ownership decision for one exact Terminal cache root.
 *
 * Cache cleanup runs after process restart too, so a missing in-process token
 * cannot by itself authorize deletion.  The execution witness, publication
 * journal, owner marker, and marker-revoked recovery carrier are read afresh
 * at each destructive entry boundary.
 */
internal object TerminalCacheProtectionClassifier {
    internal enum class Decision {
        REMOVABLE,
        PROTECTED,
        UNKNOWN,
    }

    private data class DurableState(
        val executions: List<TerminalExecutionRecovery.Record>,
        val publications: List<PublicationRecoveryJournal.Record>,
    )

    /** An opaque global journal namespace blocks even an otherwise empty clear. */
    fun recoveryNamespacesHealthy(context: Context): Boolean = discover(context) != null

    fun classify(
        context: Context,
        terminalCacheRoot: File,
        entry: File,
    ): Decision {
        val state = discover(context) ?: return Decision.UNKNOWN
        val terminalRoot = runCatching { terminalCacheRoot.canonicalFile }.getOrNull()
            ?: return Decision.UNKNOWN
        val candidate = runCatching { entry.canonicalFile }.getOrNull()
            ?: return Decision.UNKNOWN
        if (candidate == terminalRoot || !isWithin(candidate, terminalRoot)) {
            return Decision.UNKNOWN
        }
        val relative = runCatching { terminalRoot.toPath().relativize(candidate.toPath()) }
            .getOrNull() ?: return Decision.UNKNOWN
        if (relative.nameCount < 1) return Decision.UNKNOWN
        val taskRoot = runCatching {
            File(terminalRoot, relative.getName(0).toString()).canonicalFile
        }.getOrNull() ?: return Decision.UNKNOWN
        if (taskRoot.parentFile != terminalRoot || !taskRoot.isDirectory) {
            // Terminal cache entries are rooted at TERMINAL/<executionToken>.
            // Unknown direct children do not carry enough identity to delete.
            return Decision.UNKNOWN
        }

        val executionToken = taskRoot.name
        if (executionToken.isBlank() || TerminalExecutionRegistry.isActiveNow(executionToken)) {
            return Decision.PROTECTED
        }

        val markerToken = when (val marker = TerminalCacheOwnership.inspectMarker(taskRoot)) {
            TerminalCacheOwnership.MarkerInspection.Absent -> null
            is TerminalCacheOwnership.MarkerInspection.Valid -> marker.taskToken
            is TerminalCacheOwnership.MarkerInspection.Opaque -> return Decision.UNKNOWN
        }
        if (markerToken != null && markerToken != executionToken) return Decision.UNKNOWN

        when (TerminalCacheOwnership.inspectRecoveryCarrier(taskRoot)) {
            TerminalCacheOwnership.RecoveryCarrierInspection.Absent -> Unit
            is TerminalCacheOwnership.RecoveryCarrierInspection.Valid -> return Decision.PROTECTED
            is TerminalCacheOwnership.RecoveryCarrierInspection.Opaque -> return Decision.UNKNOWN
        }

        val exactExecutionRecords = state.executions.filter {
            it.executionToken == executionToken
        }
        if (exactExecutionRecords.any { !it.terminal }) return Decision.PROTECTED

        val exactPublicationRecords = mutableListOf<PublicationRecoveryJournal.Record>()
        state.publications.asSequence()
            .filter { it.kind == PublicationRecoveryJournal.Kind.TERMINAL }
            .forEach { record ->
                val sourceRoot = runCatching { File(record.sourceRoot).canonicalFile }
                    .getOrNull() ?: return Decision.UNKNOWN
                if (sourceRoot == taskRoot) {
                    if (record.executionId != executionToken) return Decision.UNKNOWN
                    exactPublicationRecords += record
                }
            }
        if (exactPublicationRecords.any {
                it.phase != PublicationRecoveryJournal.Phase.COMMITTED
            }
        ) {
            // COMPLETE/COMMITTING and both quarantine states still own
            // publication or recovery responsibility.
            return Decision.PROTECTED
        }

        val artifactManifest = TerminalCacheOwnership.artifactManifestFile(taskRoot)
        val hasManifest = runCatching {
            if (!artifactManifest.exists()) false
            else artifactManifest.isFile && artifactManifest.canonicalFile.parentFile == taskRoot &&
                artifactManifest.readLines().all { it.isNotBlank() }
        }.getOrElse { return Decision.UNKNOWN }
        if (runCatching { artifactManifest.exists() }.getOrDefault(true) && !hasManifest) {
            return Decision.UNKNOWN
        }

        val explicitlyRetired = exactExecutionRecords.any { it.terminal } ||
            exactPublicationRecords.any {
                it.phase == PublicationRecoveryJournal.Phase.COMMITTED
            }
        if (markerToken != null && !explicitlyRetired) {
            // A valid marker names a real execution owner. Without an exact
            // terminal witness, absence of other journals cannot retire it.
            return Decision.UNKNOWN
        }
        if (hasManifest && !explicitlyRetired) return Decision.UNKNOWN

        return Decision.REMOVABLE
    }

    private fun discover(context: Context): DurableState? {
        val executions = when (val result = TerminalExecutionRecovery.discover(context)) {
            is TerminalExecutionRecovery.DiscoveryResult.Healthy -> result.records
            is TerminalExecutionRecovery.DiscoveryResult.Unavailable,
            is TerminalExecutionRecovery.DiscoveryResult.Opaque -> return null
        }
        val publications = when (val result = PublicationRecoveryJournal.discover(context)) {
            is PublicationRecoveryJournal.DiscoveryResult.Healthy -> result.records
            is PublicationRecoveryJournal.DiscoveryResult.Unavailable,
            is PublicationRecoveryJournal.DiscoveryResult.Opaque -> return null
        }
        return DurableState(executions, publications)
    }

    private fun isWithin(candidate: File, root: File): Boolean = runCatching {
        candidate.toPath().normalize().startsWith(root.toPath().normalize())
    }.getOrDefault(false)
}
