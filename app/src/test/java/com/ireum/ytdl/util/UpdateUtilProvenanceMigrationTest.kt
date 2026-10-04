package com.ireum.ytdl.util

import android.content.SharedPreferences
import com.ireum.ytdl.database.BackupRestoreParser
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUtilProvenanceMigrationTest {
    private val epochKey = "ytdlp_provenance_epoch"
    private val proofKeys = setOf(
        "ytdlp_committed_source_generation", "ytdlp_committed_source", "ytdlp_committed_result",
        "ytdlp_pending_source_generation", "ytdlp_pending_source",
    )

    @Test
    fun absentEpochRetiresLegacyProofDurablyBeforeCompletionAndPreservesIntent() {
        val store = MemoryPreferences(legacyGraph())
        UpdateUtil.retirePreChangeProvenance(store.preferences)

        assertEquals(2, store.commits)
        assertTrue(store.durableImages[0].keys.none { it in proofKeys })
        assertFalse(store.durableImages[0].containsKey(epochKey))
        assertEquals(1, store.durableImages[1][epochKey])
        assertTrue(store.disk.keys.none { it in proofKeys })
        assertEquals("nightly", store.disk["ytdlp_source"])
        assertEquals("User Nightly", store.disk["ytdlp_source_label"])
        assertEquals(83L, store.disk["ytdlp_source_generation"])
        assertTrue(UpdateUtil.destinationProvenanceEpochIsCurrent(store.preferences))
    }

    @Test
    fun oldEpochRetiresLegacyProofWithoutRebasingDesiredGeneration() {
        val store = MemoryPreferences(legacyGraph() + (epochKey to 0))
        UpdateUtil.retirePreChangeProvenance(store.preferences)

        assertEquals(1, store.disk[epochKey])
        assertEquals(83L, store.disk["ytdlp_source_generation"])
        assertTrue(store.disk.keys.none { it in proofKeys })
    }

    @Test
    fun currentEpochIsIdempotentAndPreservesFreshCommittedAndPendingProof() {
        val current = legacyGraph() + mapOf(epochKey to 1, "ytdlp_committed_result" to "DONE:destination-runtime")
        val store = MemoryPreferences(current)
        UpdateUtil.retirePreChangeProvenance(store.preferences)
        UpdateUtil.retirePreChangeProvenance(store.preferences)

        assertEquals(0, store.commits)
        assertEquals(current, store.disk)
        assertTrue(UpdateUtil.destinationProvenanceEpochIsCurrent(store.preferences))
    }

    @Test
    fun defaultSourceAbsenceDoesNotMaterializeIntentOrGeneration() {
        val store = MemoryPreferences(legacyGraph().filterKeys {
            it !in setOf("ytdlp_source", "ytdlp_source_label", "ytdlp_source_generation")
        })
        UpdateUtil.retirePreChangeProvenance(store.preferences)

        assertFalse(store.disk.containsKey("ytdlp_source"))
        assertFalse(store.disk.containsKey("ytdlp_source_label"))
        assertFalse(store.disk.containsKey("ytdlp_source_generation"))
        assertTrue(store.disk.keys.none { it in proofKeys })
        assertEquals(1, store.disk[epochKey])
    }

    @Test
    fun failedRetirementCannotCompleteEpochAndRestartRetiresTheOriginalDiskGraph() {
        val legacy = legacyGraph()
        val store = MemoryPreferences(legacy, failCommit = 1)
        val failure = runCatching { UpdateUtil.retirePreChangeProvenance(store.preferences) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, store.commits)
        assertEquals(legacy, store.disk)
        assertFalse(store.memory.containsKey(epochKey))
        assertFalse(UpdateUtil.destinationProvenanceEpochIsCurrent(store.preferences))
        val reopened = MemoryPreferences(store.disk)
        UpdateUtil.retirePreChangeProvenance(reopened.preferences)
        assertEquals(1, reopened.disk[epochKey])
        assertTrue(reopened.disk.keys.none { it in proofKeys })
        assertEquals(83L, reopened.disk["ytdlp_source_generation"])
    }

    @Test
    fun failedEpochPublicationStaysUntrustedInMemoryAndRecoversFromRetiredDiskGraph() {
        val store = MemoryPreferences(legacyGraph(), failCommit = 2)
        val failure = runCatching { UpdateUtil.retirePreChangeProvenance(store.preferences) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, store.memory[epochKey])
        assertFalse(store.disk.containsKey(epochKey))
        assertTrue(store.disk.keys.none { it in proofKeys })
        assertFalse(UpdateUtil.destinationProvenanceEpochIsCurrent(store.preferences))
        val reopened = MemoryPreferences(store.disk)
        UpdateUtil.retirePreChangeProvenance(reopened.preferences)
        assertTrue(UpdateUtil.destinationProvenanceEpochIsCurrent(reopened.preferences))
        assertEquals(1, reopened.disk[epochKey])
        assertEquals(83L, reopened.disk["ytdlp_source_generation"])
        val recovered = reopened.disk.toMap()
        UpdateUtil.retirePreChangeProvenance(reopened.preferences)
        assertEquals(2, reopened.commits)
        assertEquals(recovered, reopened.disk)
    }

    @Test
    fun unsupportedFutureEpochFailsClosedWithoutErasingItsState() {
        val future = legacyGraph() + (epochKey to 2)
        val store = MemoryPreferences(future)
        val failure = runCatching { UpdateUtil.retirePreChangeProvenance(store.preferences) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, store.commits)
        assertEquals(future, store.disk)
        assertFalse(UpdateUtil.destinationProvenanceEpochIsCurrent(store.preferences))
    }

    @Test
    fun epochIsDestinationLocalAndTypedRestoreCannotImportIt() {
        assertFalse(BackupSettingsUtil.isPortablePreferenceKey(epochKey))
        val plan = BackupRestoreParser.fromTyped(RestoreAppDataItem(settings = listOf(
            BackupSettingsItem(epochKey, "1", "Int"),
            BackupSettingsItem("ytdlp_source", "nightly", "String"),
            BackupSettingsItem("ytdlp_source_label", "User Nightly", "String"),
        )))
        assertEquals(setOf("ytdlp_source", "ytdlp_source_label"),
            plan.data.settings.orEmpty().map { it.key }.toSet())
    }

    // The old fixture is seeded directly; it never passes through a current writer.
    private fun legacyGraph(): Map<String, Any?> = mapOf(
        "ytdlp_source" to "nightly",
        "ytdlp_source_label" to "User Nightly",
        "ytdlp_source_generation" to 83L,
        "ytdlp_committed_source_generation" to 83L,
        "ytdlp_committed_source" to "nightly",
        "ytdlp_committed_result" to "DONE:foreign-runtime",
        "ytdlp_pending_source_generation" to 83L,
        "ytdlp_pending_source" to "nightly",
        "auto_update_ytdlp" to false,
        "unrelated_setting" to "preserved",
    )

    /** Models Android's memory publication on a failed synchronous disk commit. */
    private class MemoryPreferences(initial: Map<String, Any?>, private val failCommit: Int? = null) {
        var memory = initial.toMap()
            private set
        var disk = initial.toMap()
            private set
        var commits = 0
            private set
        val durableImages = mutableListOf<Map<String, Any?>>()
        private val removed = Any()

        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> memory.toMap()
                "getInt" -> memory[args!![0] as String] ?: args[1]
                "edit" -> editor()
                "toString" -> "MigrationMemoryPreferences"
                else -> error("Unexpected preference method " + method.name)
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "remove" -> { changes[args!![0] as String] = removed; proxy }
                    "putInt" -> { changes[args!![0] as String] = args[1]; proxy }
                    "commit" -> {
                        val next = memory.toMutableMap()
                        changes.forEach { (key, value) ->
                            if (value === removed) next.remove(key) else next[key] = value
                        }
                        memory = next.toMap()
                        commits += 1
                        if (commits == failCommit) false else {
                            disk = memory.toMap()
                            durableImages += disk
                            true
                        }
                    }
                    else -> error("Unexpected editor method " + method.name)
                }
            } as SharedPreferences.Editor
        }
    }
}
