package com.ireum.ytdl.util.storage

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.work.PublicationRecoveryJournal
import com.ireum.ytdl.work.TerminalExecutionRecovery
import com.ireum.ytdl.work.TerminalExecutionRegistry
import com.ireum.ytdl.work.YtdlpProcessIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Production cache-deletion wiring for durable Terminal ownership. */
@RunWith(AndroidJUnit4::class)
class TerminalCacheProtectionProductionWiringTest {
    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private var hadCachePath = false
    private var previousCachePath: String? = null
    private val subjectIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        hadCachePath = preferences.contains("cache_path")
        previousCachePath = preferences.getString("cache_path", null)
    }

    @After
    fun tearDown() {
        subjectIds.forEach { subjectId ->
            TerminalExecutionRecovery.clearForTesting(
                File(context.filesDir, "terminal-execution-recovery"),
                subjectId,
            )
        }
        subjectIds.clear()
        val editor = preferences.edit()
        if (hadCachePath) editor.putString("cache_path", previousCachePath)
        else editor.remove("cache_path")
        assertTrue(editor.commit())
    }

    @Test
    fun processRestartExecutionWitnessesAndPublicationJournalProtectExactRoots() = runBlocking {
        val cacheRoot = cacheRoot("durable-owners")
        val roots = mutableListOf<File>()
        var publication: PublicationRecoveryJournal.Handle? = null
        try {
            useCacheRoot(cacheRoot)
            val phases = listOf(
                TerminalExecutionRecovery.Phase.ADMITTED,
                TerminalExecutionRecovery.Phase.NATIVE_STARTED,
                TerminalExecutionRecovery.Phase.NATIVE_FINISHED,
                TerminalExecutionRecovery.Phase.NATIVE_QUIESCENCE_PENDING,
            )
            phases.forEachIndexed { index, phase ->
                val (subjectId, token, root) = newOwnedRoot(cacheRoot, "phase-$index")
                roots += root
                assertTrue(
                    TerminalExecutionRecovery.begin(
                        context,
                        subjectId,
                        token,
                        YtdlpProcessIdentity.terminal(subjectId),
                    ),
                )
                when (phase) {
                    TerminalExecutionRecovery.Phase.ADMITTED -> Unit
                    TerminalExecutionRecovery.Phase.NATIVE_STARTED ->
                        assertTrue(TerminalExecutionRecovery.markNativeStarted(context, subjectId, token))
                    TerminalExecutionRecovery.Phase.NATIVE_FINISHED -> {
                        assertTrue(TerminalExecutionRecovery.markNativeStarted(context, subjectId, token))
                        assertTrue(TerminalExecutionRecovery.markNativeFinished(context, subjectId, token))
                    }
                    TerminalExecutionRecovery.Phase.NATIVE_QUIESCENCE_PENDING -> {
                        assertTrue(TerminalExecutionRecovery.markNativeStarted(context, subjectId, token))
                        assertTrue(
                            TerminalExecutionRecovery.markQuiescencePending(
                                context,
                                subjectId,
                                token,
                                TerminalExecutionRecovery.Outcome.STOPPED,
                            ),
                        )
                    }
                    else -> error("Unexpected phase fixture: $phase")
                }
                assertEquals(phase, TerminalExecutionRecovery.read(context, subjectId)?.phase)
                assertFalse(TerminalExecutionRegistry.isActiveNow(token))
            }

            val (publicationSubject, publicationToken, publicationRoot) =
                newOwnedRoot(cacheRoot, "publication")
            roots += publicationRoot
            val publicationSource = File(publicationRoot, "prepared-output.bin").apply {
                writeText("durable publication owner")
            }
            publication = PublicationRecoveryJournal.begin(
                context = context,
                kind = PublicationRecoveryJournal.Kind.TERMINAL,
                subjectId = publicationSubject.toString(),
                operationId = publicationToken,
                executionId = publicationToken,
                attemptId = publicationToken,
                sourceRoot = publicationRoot,
                sourceFiles = listOf(publicationSource),
            )
            assertTrue(publication != null)
            assertEquals(PublicationRecoveryJournal.Phase.PREPARED, publication?.snapshot()?.phase)

            val manager = AppCacheManager(context)
            val deletion = manager.delete(setOf(AppCacheCategory.TERMINAL_CACHE))
            val exactSnapshot = requireNotNull(
                manager.snapshotExact(AppCacheCategory.TERMINAL_CACHE),
            )
            val exactDeletion = manager.deleteExact(exactSnapshot)

            assertTrue(deletion.failedEntries > 0)
            assertTrue(exactDeletion.failedEntries > 0)
            roots.forEach { root ->
                assertTrue("durable owner was deleted at ${root.name}", root.isDirectory)
                assertTrue(File(root, "payload.bin").isFile)
            }
            assertTrue(publicationSource.isFile)
        } finally {
            publication?.let { handle ->
                val record = handle.snapshot()
                record.artifacts.forEach { artifact ->
                    if (artifact.destinationPath.isNullOrBlank()) {
                        assertTrue(
                            handle.markPublished(
                                artifact.sourcePath,
                                File(cacheRoot, "retired-${UUID.randomUUID()}.bin").absolutePath,
                            ),
                        )
                    }
                }
                assertTrue(handle.markPhase(PublicationRecoveryJournal.Phase.COMMITTED))
                assertTrue(handle.clear())
            }
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun markerRevokedQuarantinedUnknownCarrierProtectsItsRecoveryRoot() = runBlocking {
        val cacheRoot = cacheRoot("recovery-carrier")
        try {
            useCacheRoot(cacheRoot)
            val token = "${System.nanoTime()}-${UUID.randomUUID()}"
            val root = File(File(cacheRoot, "TERMINAL"), token).apply { mkdirs() }
            assertTrue(TerminalCacheOwnership.ensureMarker(root, token).isFile)
            val payload = File(root, "partial-output.bin").apply { writeText("recovery") }
            assertTrue(TerminalCacheOwnership.recordArtifacts(root, listOf(payload.absolutePath)))
            assertTrue(
                TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = root,
                    taskToken = token,
                    phase = "QUARANTINED_UNKNOWN",
                ),
            )
            assertTrue(TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(root, token))
            assertFalse(TerminalCacheOwnership.markerFile(root).exists())

            val deletion = AppCacheManager(context).delete(setOf(AppCacheCategory.TERMINAL_CACHE))

            assertTrue(deletion.failedEntries > 0)
            assertTrue(payload.isFile)
            assertTrue(TerminalCacheOwnership.recoveryCarrierFile(root).isFile)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun staleRootIsRemovableAndExactTerminalWitnessRetiresItsMarker() = runBlocking {
        val cacheRoot = cacheRoot("retired-roots")
        var retiredSubjectId: Long? = null
        try {
            useCacheRoot(cacheRoot)
            val staleRoot = File(File(cacheRoot, "TERMINAL"), "stale-${UUID.randomUUID()}")
                .apply { mkdirs() }
            val stalePayload = File(staleRoot, "unowned.bin").apply { writeText("stale") }

            val (subjectId, token, retiredRoot) = newOwnedRoot(cacheRoot, "retired")
            retiredSubjectId = subjectId
            assertTrue(
                TerminalExecutionRecovery.begin(
                    context,
                    subjectId,
                    token,
                    YtdlpProcessIdentity.terminal(subjectId),
                ),
            )
            assertTrue(
                TerminalExecutionRecovery.markTerminalFailure(
                    context,
                    subjectId,
                    token,
                    outcome = TerminalExecutionRecovery.Outcome.STOPPED,
                ),
            )
            assertEquals(
                TerminalExecutionRecovery.Phase.TERMINAL_STOPPED,
                TerminalExecutionRecovery.read(context, subjectId)?.phase,
            )

            val deletion = AppCacheManager(context).delete(setOf(AppCacheCategory.TERMINAL_CACHE))

            assertTrue(deletion.deletedFiles > 0)
            assertFalse(stalePayload.exists())
            assertFalse(staleRoot.exists())
            assertFalse(retiredRoot.exists())
        } finally {
            retiredSubjectId?.let { subjectId ->
                TerminalExecutionRecovery.clearForTesting(
                    File(context.filesDir, "terminal-execution-recovery"),
                    subjectId,
                )
                subjectIds.remove(subjectId)
            }
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun malformedMarkerAndOpaqueExecutionNamespaceFailClosed() = runBlocking {
        val cacheRoot = cacheRoot("opaque-state")
        var opaqueRecord: File? = null
        try {
            useCacheRoot(cacheRoot)
            val malformedToken = "malformed-${UUID.randomUUID()}"
            val malformedRoot = File(File(cacheRoot, "TERMINAL"), malformedToken).apply { mkdirs() }
            val malformedPayload = File(malformedRoot, "keep.bin").apply { writeText("unknown marker") }
            TerminalCacheOwnership.markerFile(malformedRoot).writeText(
                "ytdlnisx-terminal-owner\nversion=unexpected\ntaskToken=$malformedToken\n",
            )

            val markerDeletion = AppCacheManager(context).delete(setOf(AppCacheCategory.TERMINAL_CACHE))
            assertTrue(markerDeletion.failedEntries > 0)
            assertTrue(malformedPayload.isFile)

            val staleRoot = File(File(cacheRoot, "TERMINAL"), "stale-${UUID.randomUUID()}")
                .apply { mkdirs() }
            val stalePayload = File(staleRoot, "keep-until-namespace-recovers.bin")
                .apply { writeText("global opaque namespace") }
            val recoveryDirectory = File(context.filesDir, "terminal-execution-recovery")
                .apply { mkdirs() }
            opaqueRecord = File(
                recoveryDirectory,
                "ytdlnisx-terminal-execution-malformed-${UUID.randomUUID()}.json",
            ).apply { writeText("{") }

            val namespaceDeletion = AppCacheManager(context).delete(setOf(AppCacheCategory.TERMINAL_CACHE))

            assertTrue(namespaceDeletion.skippedCategories.contains(AppCacheCategory.TERMINAL_CACHE))
            assertTrue(malformedPayload.isFile)
            assertTrue(stalePayload.isFile)
        } finally {
            opaqueRecord?.delete()
            cacheRoot.deleteRecursively()
        }
    }

    private fun cacheRoot(label: String): File = File(
        requireNotNull(context.getExternalFilesDir(null)),
        "terminal-cache-$label-${UUID.randomUUID()}",
    ).apply { mkdirs() }

    private fun useCacheRoot(cacheRoot: File) {
        assertTrue(preferences.edit().putString("cache_path", cacheRoot.absolutePath).commit())
    }

    private fun newOwnedRoot(cacheRoot: File, label: String): Triple<Long, String, File> {
        val subjectId = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1L)
        subjectIds += subjectId
        val token = "$subjectId-$label-${UUID.randomUUID()}"
        val root = File(File(cacheRoot, "TERMINAL"), token).apply { mkdirs() }
        assertTrue(TerminalCacheOwnership.ensureMarker(root, token).isFile)
        File(root, "payload.bin").writeText("$label payload")
        assertTrue(File(root, "payload.bin").isFile)
        return Triple(subjectId, token, root)
    }
}
