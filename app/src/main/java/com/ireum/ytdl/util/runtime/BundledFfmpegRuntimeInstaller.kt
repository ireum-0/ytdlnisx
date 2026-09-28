package com.ireum.ytdl.util.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

sealed interface BundledFfmpegInstallResult {
    data class VerifiedCurrent(val generation: String) : BundledFfmpegInstallResult
    data class VerifiedNew(val generation: String) : BundledFfmpegInstallResult
    data class Failure(val reason: String, val cause: Throwable? = null) : BundledFfmpegInstallResult
}

/** Stages, validates, and transactionally publishes one owned FFmpeg payload generation. */
class BundledFfmpegRuntimeInstaller(
    private val nativeLibraryDirectory: File,
    private val payloadRoot: File,
    private val materializePayload: (stagingRoot: File) -> Unit,
) {
    internal enum class Boundary {
        STAGING_CREATED,
        MATERIALIZED,
        BEFORE_PROVENANCE_MARKERS,
        PROVENANCE_MARKERS_WRITTEN,
        JOURNAL_DURABLE,
        OLD_LIVE_BACKED_UP,
        BEFORE_NEW_LIVE_PUBLISH,
        NEW_LIVE_PUBLISHED,
        BEFORE_BACKUP_RETIREMENT,
    }

    internal var crashAtBoundaryForTesting: Boundary? = null
    internal var failureAtBoundaryForTesting: Boundary? = null

    fun install(): BundledFfmpegInstallResult {
        recoverInterruptedInstall()?.let { return it }

        val parent = payloadRoot.absoluteFile.parentFile
            ?: return BundledFfmpegInstallResult.Failure("FFmpeg payload root has no parent")
        if (!parent.exists() && !parent.mkdirs()) {
            return BundledFfmpegInstallResult.Failure("Could not create FFmpeg package directory")
        }

        val orphanCleanupFailure = cleanupOwnedOrphanStages(parent)
        if (orphanCleanupFailure != null) return orphanCleanupFailure

        val current = BundledFfmpegRuntime.validate(nativeLibraryDirectory, payloadRoot)
        if (current is BundledFfmpegRuntimeResolution.Available) {
            val generation = current.generation
                ?: return BundledFfmpegInstallResult.Failure("Verified current FFmpeg payload has no generation")
            return BundledFfmpegInstallResult.VerifiedCurrent(generation)
        }

        val attemptId = UUID.randomUUID().toString()
        val stageName = stageName(attemptId)
        val stage = File(parent, stageName)
        val ownerFile = ownerFile(parent, stageName)
        val backup = backupRoot(parent)
        val journal = journalFile(parent)
        if (backup.exists()) {
            return BundledFfmpegInstallResult.Failure(
                "Unjournaled FFmpeg backup exists; preserving it and refusing replacement",
            )
        }
        if (payloadRoot.exists() && !isExpectedDirectChild(parent, payloadRoot)) {
            return BundledFfmpegInstallResult.Failure(
                "FFmpeg live path is not a direct owned package child; preserving it",
            )
        }
        if (journal.exists()) {
            return BundledFfmpegInstallResult.Failure(
                "Unresolved FFmpeg install journal exists; preserving runtime state",
            )
        }

        var preserveForSimulatedProcessDeath = false
        try {
            if (!stage.mkdir()) throw IOException("Could not create FFmpeg staging directory")
            writeDurable(ownerFile, attemptId)
            hitBoundary(Boundary.STAGING_CREATED)

            materializePayload(stage)
            hitBoundary(Boundary.MATERIALIZED)
            hitBoundary(Boundary.BEFORE_PROVENANCE_MARKERS)
            writeDurable(File(stage, BundledFfmpegRuntime.PAYLOAD_REVISION_FILE), BundledFfmpegRuntime.PAYLOAD_REVISION)
            writeDurable(File(stage, BundledFfmpegRuntime.PAYLOAD_GENERATION_FILE), attemptId)
            hitBoundary(Boundary.PROVENANCE_MARKERS_WRITTEN)

            val stagedRuntime = BundledFfmpegRuntime.validate(nativeLibraryDirectory, stage)
            if (stagedRuntime !is BundledFfmpegRuntimeResolution.Available || stagedRuntime.generation != attemptId) {
                throw IOException(
                    "Staged FFmpeg payload did not validate: " +
                        (stagedRuntime as? BundledFfmpegRuntimeResolution.Unavailable)?.reason.orEmpty(),
                )
            }

            writeInstallJournal(journal, parent, attemptId, stageName)
            hitBoundary(Boundary.JOURNAL_DURABLE)

            if (payloadRoot.exists()) {
                if (!payloadRoot.renameTo(backup)) {
                    throw IOException("Could not move the prior FFmpeg payload to its rollback location")
                }
                hitBoundary(Boundary.OLD_LIVE_BACKED_UP)
            }

            hitBoundary(Boundary.BEFORE_NEW_LIVE_PUBLISH)
            if (!stage.renameTo(payloadRoot)) {
                throw IOException("Could not publish the verified FFmpeg staging directory")
            }
            hitBoundary(Boundary.NEW_LIVE_PUBLISHED)

            val published = BundledFfmpegRuntime.validate(nativeLibraryDirectory, payloadRoot)
            if (published !is BundledFfmpegRuntimeResolution.Available || published.generation != attemptId) {
                throw IOException(
                    "Published FFmpeg payload failed exact generation validation: " +
                        (published as? BundledFfmpegRuntimeResolution.Unavailable)?.reason.orEmpty(),
                )
            }

            hitBoundary(Boundary.BEFORE_BACKUP_RETIREMENT)
            if (backup.exists() && !isExpectedDirectChild(parent, backup)) {
                return BundledFfmpegInstallResult.Failure(
                    "FFmpeg rollback path changed before retirement; preserving it",
                )
            }
            if (backup.exists() && !backup.deleteRecursively()) {
                // The verified new generation is already live. Keep the journal so
                // startup recovery can finish retiring only this transaction's backup.
                return BundledFfmpegInstallResult.VerifiedNew(attemptId)
            }
            removeIfOwned(ownerFile, attemptId)
            journal.delete()
            return BundledFfmpegInstallResult.VerifiedNew(attemptId)
        } catch (error: SimulatedProcessDeath) {
            preserveForSimulatedProcessDeath = true
            throw error
        } catch (error: Exception) {
            val recoveryFailure = recoverInterruptedInstall()
            if (recoveryFailure != null) {
                error.addSuppressed(IOException(recoveryFailure.reason, recoveryFailure.cause))
            }
            val recovered = BundledFfmpegRuntime.validate(nativeLibraryDirectory, payloadRoot)
            if (
                recoveryFailure == null &&
                recovered is BundledFfmpegRuntimeResolution.Available &&
                recovered.generation == attemptId
            ) {
                return BundledFfmpegInstallResult.VerifiedNew(attemptId)
            }
            return BundledFfmpegInstallResult.Failure(
                error.message ?: "FFmpeg payload installation failed",
                error,
            )
        } finally {
            if (!preserveForSimulatedProcessDeath) {
                removeIfOwned(stage, ownerFile, attemptId)
            }
        }
    }

    /** Repairs only a swap recorded by this installer, or removes marked orphan staging attempts. */
    internal fun recoverInterruptedInstall(): BundledFfmpegInstallResult.Failure? {
        val parent = payloadRoot.absoluteFile.parentFile
            ?: return BundledFfmpegInstallResult.Failure("FFmpeg payload root has no parent")
        if (!parent.exists()) return null

        val journal = journalFile(parent)
        if (!journal.exists()) return cleanupOwnedOrphanStages(parent)

        val state = runCatching { readInstallJournal(journal) }.getOrElse {
            return BundledFfmpegInstallResult.Failure(
                "FFmpeg install journal is unreadable; preserving all runtime paths",
                it,
            )
        }
        val attemptId = state.attemptId
        val stageName = state.stageName
        val stage = File(parent, stageName)
        val ownerFile = ownerFile(parent, stageName)
        val backup = backupRoot(parent)
        if (backup.exists() && !isExpectedDirectChild(parent, backup)) {
            return BundledFfmpegInstallResult.Failure(
                "FFmpeg rollback path is not an owned direct child; preserving all runtime paths",
            )
        }
        val generation = readGeneration(payloadRoot)
        val live = BundledFfmpegRuntime.validate(nativeLibraryDirectory, payloadRoot)

        if (live is BundledFfmpegRuntimeResolution.Available && live.generation == attemptId) {
            if (backup.exists() && !backup.deleteRecursively()) {
                return BundledFfmpegInstallResult.Failure(
                    "Verified FFmpeg generation is live but its journal-owned backup could not be retired",
                )
            }
            removeIfOwned(stage, ownerFile, attemptId)
            if (!journal.delete() && journal.exists()) {
                return BundledFfmpegInstallResult.Failure("Could not clear completed FFmpeg install journal")
            }
            return null
        }

        if (backup.exists()) {
            if (payloadRoot.exists()) {
                if (generation != attemptId || !isExpectedDirectChild(parent, payloadRoot)) {
                    return BundledFfmpegInstallResult.Failure(
                        "FFmpeg live path is not owned by the interrupted generation; preserving backup and live path",
                    )
                }
                if (!payloadRoot.deleteRecursively()) {
                    return BundledFfmpegInstallResult.Failure(
                        "Could not remove the interrupted owned FFmpeg generation before rollback",
                    )
                }
            }
            if (!backup.renameTo(payloadRoot)) {
                return BundledFfmpegInstallResult.Failure(
                    "Could not restore the prior FFmpeg payload; preserving rollback evidence",
                )
            }
        } else if (payloadRoot.exists() && generation == attemptId) {
            // No prior live directory existed. Remove only the partial generation
            // identified by the journal's exact generation token.
            if (!payloadRoot.deleteRecursively()) {
                return BundledFfmpegInstallResult.Failure(
                    "Could not remove the interrupted owned FFmpeg generation",
                )
            }
        }

        removeIfOwned(stage, ownerFile, attemptId)
        if (!journal.delete() && journal.exists()) {
            return BundledFfmpegInstallResult.Failure("Could not clear recovered FFmpeg install journal")
        }
        return null
    }

    private fun cleanupOwnedOrphanStages(parent: File): BundledFfmpegInstallResult.Failure? {
        val prefix = ".${payloadRoot.name}.stage-"
        val stages = parent.listFiles().orEmpty().filter { candidate ->
            candidate.isDirectory && candidate.name.startsWith(prefix)
        }
        stages.forEach { stage ->
            if (!isExpectedDirectChild(parent, stage)) return@forEach
            val attemptId = stage.name.removePrefix(prefix)
            if (!isAttemptId(attemptId)) return@forEach
            val ownerFile = ownerFile(parent, stage.name)
            if (!isExpectedDirectChild(parent, ownerFile)) return@forEach
            if (ownerFile.isFile && runCatching { ownerFile.readText(Charsets.UTF_8) == attemptId }.getOrDefault(false)) {
                if (!stage.deleteRecursively() || (ownerFile.exists() && !ownerFile.delete())) {
                    return BundledFfmpegInstallResult.Failure(
                        "Could not remove an owned orphan FFmpeg staging attempt",
                    )
                }
            }
        }
        return null
    }

    private fun writeInstallJournal(journal: File, parent: File, attemptId: String, stageName: String) {
        val temporaryJournal = File(parent, "${journal.name}.$attemptId.tmp")
        writeDurable(temporaryJournal, "$attemptId\n$stageName\n${backupRoot(parent).name}")
        if (!temporaryJournal.renameTo(journal)) {
            temporaryJournal.delete()
            throw IOException("Could not publish FFmpeg install journal")
        }
    }

    private fun readInstallJournal(journal: File): JournalState {
        val lines = journal.readLines(Charsets.UTF_8)
        if (lines.size != 3) throw IOException("Malformed FFmpeg install journal")
        val attemptId = lines[0]
        val expectedStageName = stageName(attemptId)
        val parent = journal.absoluteFile.parentFile
            ?: throw IOException("FFmpeg install journal has no parent")
        val expectedBackupName = backupRoot(parent).name
        if (!isAttemptId(attemptId) || lines[1] != expectedStageName || lines[2] != expectedBackupName) {
            throw IOException("FFmpeg install journal paths are outside the owned transaction")
        }
        return JournalState(attemptId, expectedStageName)
    }

    private fun writeDurable(file: File, contents: String) {
        FileOutputStream(file, false).use { output ->
            output.write(contents.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun hitBoundary(boundary: Boundary) {
        if (crashAtBoundaryForTesting == boundary) throw SimulatedProcessDeath(boundary)
        if (failureAtBoundaryForTesting == boundary) throw IOException("Injected FFmpeg install failure at $boundary")
    }

    private fun readGeneration(root: File): String? =
        runCatching { File(root, BundledFfmpegRuntime.PAYLOAD_GENERATION_FILE).readText(Charsets.UTF_8).trim() }
            .getOrNull()

    private fun removeIfOwned(stage: File, ownerFile: File, attemptId: String) {
        if (!stage.exists() && !ownerFile.exists()) return
        val stageName = stage.name
        val parent = stage.absoluteFile.parentFile ?: return
        if (!isExpectedDirectChild(parent, stage)) return
        if (ownerFile != ownerFile(parent, stageName)) return
        if (!isExpectedDirectChild(parent, ownerFile)) return
        val markerMatches = runCatching { ownerFile.readText(Charsets.UTF_8) == attemptId }.getOrDefault(false)
        if (markerMatches) {
            if (stage.exists()) stage.deleteRecursively()
            if (ownerFile.exists()) ownerFile.delete()
        }
    }

    private fun removeIfOwned(ownerFile: File, attemptId: String) {
        if (ownerFile.isFile && runCatching { ownerFile.readText(Charsets.UTF_8) == attemptId }.getOrDefault(false)) {
            ownerFile.delete()
        }
    }

    private fun stageName(attemptId: String) = ".${payloadRoot.name}.stage-$attemptId"
    private fun ownerFile(parent: File, stageName: String) = File(parent, "$stageName.owner")
    private fun backupRoot(parent: File) = File(parent, ".${payloadRoot.name}.backup")
    private fun journalFile(parent: File) = File(parent, ".${payloadRoot.name}.install-journal")

    private fun isAttemptId(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun isExpectedDirectChild(parent: File, child: File): Boolean = runCatching {
        val canonical = child.canonicalFile
        canonical.parentFile == parent.canonicalFile && canonical.name == child.name
    }.getOrDefault(false)

    private data class JournalState(val attemptId: String, val stageName: String)

    private class SimulatedProcessDeath(boundary: Boundary) : RuntimeException("Simulated process death at $boundary")
}
