package com.ireum.ytdl.work

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardSubMuxStageOwnershipTest {
    @Test
    fun preExistingCandidateIsNeverReusedOrDeleted() {
        val cacheRoot = Files.createTempDirectory("hardsub-stage-collision-").toFile()
        try {
            val candidate = cacheRoot.resolve("hardsub_mux_stage/fixed-token")
            assertTrue(candidate.mkdirs())
            val sentinel = candidate.resolve("unrelated.bin").apply { writeText("keep") }

            assertFalse(HardSubMuxStageOwnership.create(cacheRoot, requestedToken = "fixed-token") != null)
            assertTrue(sentinel.exists())
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun cleanupDeletesOnlyManifestArtifactsAndPreservesUnexpectedChild() {
        val cacheRoot = Files.createTempDirectory("hardsub-stage-cleanup-").toFile()
        try {
            val stage = requireNotNull(HardSubMuxStageOwnership.create(cacheRoot, requestedToken = "owned-token"))
            val owned = stage.root.resolve("owned.mp4").apply { writeText("owned") }
            val unexpected = stage.root.resolve("unexpected.part").apply { writeText("keep") }
            assertTrue(HardSubMuxStageOwnership.recordArtifacts(stage, listOf(owned)))

            assertFalse(HardSubMuxStageOwnership.cleanup(stage))
            assertFalse(owned.exists())
            assertTrue(unexpected.exists())
            assertTrue(stage.root.exists())
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun cleanOwnedStageIsRemovedAfterExactArtifactsAreConsumed() {
        val cacheRoot = Files.createTempDirectory("hardsub-stage-success-").toFile()
        try {
            val stage = requireNotNull(HardSubMuxStageOwnership.create(cacheRoot, requestedToken = "success-token"))
            val owned = stage.root.resolve("owned.mp4").apply { writeText("owned") }
            assertTrue(HardSubMuxStageOwnership.recordArtifacts(stage, listOf(owned)))

            assertTrue(HardSubMuxStageOwnership.cleanup(stage))
            assertFalse(stage.root.exists())
        } finally {
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun malformedManifestIsPreservedAndCannotBeReplaced() {
        val cacheRoot = Files.createTempDirectory("hardsub-stage-malformed-").toFile()
        try {
            val stage = requireNotNull(HardSubMuxStageOwnership.create(cacheRoot, requestedToken = "malformed-token"))
            val malformed = stage.root.resolve(".ytdlnisx-hardsub-artifacts.txt")
                .apply { writeText("not-a-hardsub-manifest\n") }
            val candidate = stage.root.resolve("candidate.mp4").apply { writeText("candidate") }

            assertFalse(HardSubMuxStageOwnership.recordArtifacts(stage, listOf(candidate)))
            assertEquals("not-a-hardsub-manifest\n", malformed.readText())
            assertTrue(candidate.isFile)
            assertFalse(HardSubMuxStageOwnership.cleanup(stage))
            assertTrue(malformed.isFile)
            assertTrue(candidate.isFile)
        } finally {
            cacheRoot.deleteRecursively()
        }
    }
}
