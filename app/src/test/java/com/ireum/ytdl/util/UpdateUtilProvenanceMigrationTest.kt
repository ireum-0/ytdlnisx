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
    private val legacyEpochKey = "ytdlp_provenance_epoch"
    private val localEpochKey = "epoch"
    private val proofKeys = setOf(
        "ytdlp_committed_source_generation", "ytdlp_committed_source", "ytdlp_committed_result",
        "ytdlp_pending_source_generation", "ytdlp_pending_source",
    )

    @Test
    fun absentEpochRetiresLegacyProofDurablyBeforeIndependentCompletionAndPreservesIntent() {
        val order = mutableListOf<String>()
        val defaults = MemoryPreferences(legacyGraph(), afterDurableCommit = { order += "retired" })
        val local = MemoryPreferences(emptyMap(), afterDurableCommit = {
            assertTrue(defaults.disk.keys.none { it in proofKeys })
            assertFalse(defaults.disk.containsKey(legacyEpochKey))
            order += "published"
        })
        migrate(defaults, local)

        assertEquals(listOf("retired", "published"), order)
        assertEquals(1, defaults.commits)
        assertEquals(1, local.commits)
        assertMigrated(defaults, local)
        assertEquals("User Nightly", defaults.disk["ytdlp_source_label"])
        assertEquals(false, defaults.disk["auto_update_ytdlp"])
        assertEquals("preserved", defaults.disk["unrelated_setting"])
    }

    @Test
    fun oldDefaultEpochRetiresLegacyProofWithoutRebasingDesiredGeneration() {
        assertLegacyRetired(legacyGraph() + (legacyEpochKey to 0))
    }

    @Test
    fun collidingDefaultEpochCannotProveForeignCommittedRuntime() {
        assertLegacyRetired(legacyGraph(includePending = false) + (legacyEpochKey to 1))
    }

    @Test
    fun collidingDefaultEpochCannotProveForeignPendingRuntime() {
        assertLegacyRetired(legacyGraph() + (legacyEpochKey to 1))
    }

    @Test
    fun previouslyValidDefaultEpochProofStillRequiresIndependentReproof() {
        assertLegacyRetired(legacyGraph(includePending = false) + mapOf(
            legacyEpochKey to 1, "ytdlp_committed_result" to "DONE:destination-runtime",
        ))
    }

    @Test
    fun everySupportedDefaultEpochRepresentationIsRetiredWithoutTypedRead() {
        listOf<Any>(-1, 2, "1", 1L, true, 1.0f, setOf("1")).forEach { representation ->
            assertLegacyRetired(legacyGraph() + (legacyEpochKey to representation))
        }
    }

    @Test
    fun currentIndependentEpochIsIdempotentAndPreservesFreshProofAcrossReopening() {
        val fresh = legacyGraph() + ("ytdlp_committed_result" to "DONE:destination-runtime")
        val defaults = MemoryPreferences(fresh)
        val local = MemoryPreferences(mapOf(localEpochKey to 1))
        migrate(defaults, local)
        migrate(defaults, local)

        assertEquals(0, defaults.commits)
        assertEquals(0, local.commits)
        assertEquals(fresh, defaults.disk)
        assertTrue(UpdateUtil.destinationProvenanceEpochIsCurrent(local.preferences))
        val reopenedDefaults = MemoryPreferences(defaults.disk)
        val reopenedLocal = MemoryPreferences(local.disk)
        migrate(reopenedDefaults, reopenedLocal)
        assertEquals(0, reopenedDefaults.commits)
        assertEquals(0, reopenedLocal.commits)
        assertEquals(fresh, reopenedDefaults.disk)
    }

    @Test
    fun defaultSourceAbsenceDoesNotMaterializeIntentOrGeneration() {
        val defaults = MemoryPreferences(legacyGraph().filterKeys {
            it !in setOf("ytdlp_source", "ytdlp_source_label", "ytdlp_source_generation")
        })
        val local = MemoryPreferences(emptyMap())
        migrate(defaults, local)

        assertFalse(defaults.disk.containsKey("ytdlp_source"))
        assertFalse(defaults.disk.containsKey("ytdlp_source_label"))
        assertFalse(defaults.disk.containsKey("ytdlp_source_generation"))
        assertTrue(defaults.disk.keys.none { it in proofKeys })
        assertEquals(1, local.disk[localEpochKey])
    }

    @Test
    fun failedRetirementCannotPublishLocalStateAndRestartRetiresOriginalDiskGraph() {
        val legacy = legacyGraph() + (legacyEpochKey to 1)
        val defaults = MemoryPreferences(legacy, failCommit = 1)
        val local = MemoryPreferences(emptyMap())
        val failure = runCatching { migrate(defaults, local) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(legacy, defaults.disk)
        assertEquals(0, local.commits)
        assertTrue(local.disk.isEmpty())
        assertFalse(UpdateUtil.destinationProvenanceEpochIsCurrent(local.preferences))
        val reopenedDefaults = MemoryPreferences(defaults.disk)
        val reopenedLocal = MemoryPreferences(local.disk)
        migrate(reopenedDefaults, reopenedLocal)
        assertMigrated(reopenedDefaults, reopenedLocal)
    }

    @Test
    fun deathAfterDurableRetirementBeforeLocalPublicationReplaysSafely() {
        val defaults = MemoryPreferences(legacyGraph() + (legacyEpochKey to "1"),
            afterDurableCommit = { error("simulated process death after retirement") })
        val local = MemoryPreferences(emptyMap())
        val failure = runCatching { migrate(defaults, local) }.exceptionOrNull()

        assertEquals("simulated process death after retirement", failure?.message)
        assertTrue(defaults.disk.keys.none { it in proofKeys })
        assertFalse(defaults.disk.containsKey(legacyEpochKey))
        assertEquals(0, local.commits)
        assertFalse(UpdateUtil.destinationProvenanceEpochIsCurrent(local.preferences))
        val reopenedDefaults = MemoryPreferences(defaults.disk)
        val reopenedLocal = MemoryPreferences(local.disk)
        migrate(reopenedDefaults, reopenedLocal)
        assertMigrated(reopenedDefaults, reopenedLocal)
    }

    @Test
    fun failedLocalPublicationIsUntrustedInMemoryAndBothRetryAndRestartAreSafe() {
        val defaults = MemoryPreferences(legacyGraph() + (legacyEpochKey to 1))
        val local = MemoryPreferences(emptyMap(), failCommit = 1)
        val failure = runCatching { migrate(defaults, local) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, local.memory[localEpochKey])
        assertTrue(local.disk.isEmpty())
        assertTrue(defaults.disk.keys.none { it in proofKeys })
        assertFalse(UpdateUtil.destinationProvenanceEpochIsCurrent(local.preferences))
        // The in-memory current value must not let a same-process retry skip
        // confirmed publication. The failed commit affects only its first call.
        migrate(defaults, local)
        assertEquals(2, defaults.commits)
        assertEquals(2, local.commits)
        assertMigrated(defaults, local)

        // A restart before that retry sees the retired graph and no local
        // discriminator. It must republish safely rather than accept old proof.
        val reopenedDefaults = MemoryPreferences(defaults.durableImages.first())
        val reopenedLocal = MemoryPreferences(emptyMap())
        migrate(reopenedDefaults, reopenedLocal)
        assertMigrated(reopenedDefaults, reopenedLocal)
    }

    @Test
    fun unsupportedFutureIndependentEpochFailsClosedWithoutErasingState() {
        val legacy = legacyGraph()
        val defaults = MemoryPreferences(legacy)
        val future = mapOf(localEpochKey to 2)
        val local = MemoryPreferences(future)
        val failure = runCatching { migrate(defaults, local) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(0, defaults.commits)
        assertEquals(0, local.commits)
        assertEquals(legacy, defaults.disk)
        assertEquals(future, local.disk)
        assertFalse(UpdateUtil.destinationProvenanceEpochIsCurrent(local.preferences))
    }

    @Test
    fun oldDefaultMarkerStillCannotBeExportedOrImportedByTypedRestore() {
        assertFalse(BackupSettingsUtil.isPortablePreferenceKey(legacyEpochKey))
        val plan = BackupRestoreParser.fromTyped(RestoreAppDataItem(settings = listOf(
            BackupSettingsItem(legacyEpochKey, "1", "Int"),
            BackupSettingsItem("ytdlp_source", "nightly", "String"),
            BackupSettingsItem("ytdlp_source_label", "User Nightly", "String"),
        )))
        assertEquals(setOf("ytdlp_source", "ytdlp_source_label"),
            plan.data.settings.orEmpty().map { it.key }.toSet())
    }

    private fun assertLegacyRetired(graph: Map<String, Any?>) {
        // Direct old-state storage; no current writer creates the fixture.
        val defaults = MemoryPreferences(graph)
        val local = MemoryPreferences(emptyMap())
        assertFalse(UpdateUtil.destinationProvenanceEpochIsCurrent(local.preferences))
        migrate(defaults, local)
        assertMigrated(defaults, local)
        val retired = defaults.disk
        migrate(defaults, local)
        assertEquals(1, defaults.commits)
        assertEquals(1, local.commits)
        assertEquals(retired, defaults.disk)
    }

    private fun migrate(defaults: MemoryPreferences, local: MemoryPreferences) =
        UpdateUtil.retirePreChangeProvenance(defaults.preferences, local.preferences)

    private fun assertMigrated(defaults: MemoryPreferences, local: MemoryPreferences) {
        assertTrue(defaults.disk.keys.none { it in proofKeys })
        assertFalse(defaults.disk.containsKey(legacyEpochKey))
        assertEquals("nightly", defaults.disk["ytdlp_source"])
        assertEquals(83L, defaults.disk["ytdlp_source_generation"])
        assertEquals(1, local.disk[localEpochKey])
        assertTrue(UpdateUtil.destinationProvenanceEpochIsCurrent(local.preferences))
    }

    // Direct pre-change preference graph, not produced by the current writer.
    private fun legacyGraph(includePending: Boolean = true): Map<String, Any?> = mapOf(
        "ytdlp_source" to "nightly", "ytdlp_source_label" to "User Nightly",
        "ytdlp_source_generation" to 83L,
        "ytdlp_committed_source_generation" to 83L,
        "ytdlp_committed_source" to "nightly", "ytdlp_committed_result" to "DONE:foreign-runtime",
        "auto_update_ytdlp" to false, "unrelated_setting" to "preserved",
    ) + if (includePending) mapOf(
        "ytdlp_pending_source_generation" to 83L, "ytdlp_pending_source" to "nightly",
    ) else emptyMap()

    /** Separates each file's memory publication from its confirmed disk image. */
    private class MemoryPreferences(
        initial: Map<String, Any?>,
        private val failCommit: Int? = null,
        private val afterDurableCommit: (() -> Unit)? = null,
    ) {
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
                "getInt" -> {
                    val key = args!![0] as String
                    if (!memory.containsKey(key)) args[1] else {
                        val value = memory[key]
                        if (value !is Int) throw ClassCastException("Non-Int value for " + key)
                        value
                    }
                }
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
                            afterDurableCommit?.invoke()
                            true
                        }
                    }
                    else -> error("Unexpected editor method " + method.name)
                }
            } as SharedPreferences.Editor
        }
    }
}
