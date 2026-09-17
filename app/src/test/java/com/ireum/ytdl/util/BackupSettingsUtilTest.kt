package com.ireum.ytdl.util

import com.ireum.ytdl.database.dao.KeywordGroupDao
import com.ireum.ytdl.database.models.KeywordGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class BackupSettingsUtilTest {
    @Test
    fun emptyCaptureIsASuccessfulEmptyArray() = runBlocking {
        val result = BackupSettingsUtil.backupKeywordGroups(
            keywordGroupDao { emptyList() },
        )

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().size())
    }

    @Test
    fun daoFailureIsNotCollapsedToAnEmptyCapture() = runBlocking {
        val expected = IllegalStateException("keyword DAO failed")
        val result = BackupSettingsUtil.backupKeywordGroups(
            keywordGroupDao { throw expected },
        )

        assertTrue(result.isFailure)
        assertEquals(expected.message, result.exceptionOrNull()?.message)
    }

    @Test
    fun successfulItemsRemainSerializable() = runBlocking {
        val result = BackupSettingsUtil.backupKeywordGroups(
            keywordGroupDao { listOf(KeywordGroup(id = 7L, name = "saved")) },
        )

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size())
        assertEquals(7L, result.getOrThrow()[0].asJsonObject.get("id").asLong)
    }

    @Test
    fun cachePathAndCleanupCoordinatorNamespaceAreNotPortable() {
        val coordinatorKeys = listOf(
            "cleanup_leftover_downloads",
            "cleanup_leftover_downloads_generation",
            "cleanup_leftover_downloads_anchor_day",
            "cleanup_leftover_downloads_pending_generation",
            "cleanup_leftover_downloads_pending_cadence",
            "cleanup_leftover_downloads_pending_anchor_day",
            "cleanup_leftover_downloads_pending_occurrence_at",
            "cleanup_leftover_downloads_active_generation",
            "cleanup_leftover_downloads_active_cadence",
            "cleanup_leftover_downloads_active_anchor_day",
            "cleanup_leftover_downloads_active_occurrence_at",
            "cleanup_leftover_downloads_pending_effect_phase",
            "cleanup_leftover_downloads_active_effect_phase",
            "cleanup_leftover_downloads_effect_journal",
            "cleanup_leftover_downloads_critical_store_version",
        )

        assertFalse(BackupSettingsUtil.isPortablePreferenceKey("cache_path"))
        coordinatorKeys.forEach { key ->
            assertFalse(
                "coordinator key must remain destination-local: $key",
                BackupSettingsUtil.isPortablePreferenceKey(key),
            )
        }
    }

    @Test(expected = IllegalStateException::class)
    fun nonObjectSerializationIsNotConvertedToAnEmptyCapture() {
        BackupSettingsUtil.toJsonArray(listOf("not an object"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun keywordGroupDao(getGroups: () -> List<KeywordGroup>): KeywordGroupDao {
        return Proxy.newProxyInstance(
            KeywordGroupDao::class.java.classLoader,
            arrayOf(KeywordGroupDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getGroups" -> getGroups()
                "toString" -> "KeywordGroupDaoTestProxy"
                "hashCode" -> 1
                "equals" -> false
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Byte::class.javaPrimitiveType -> 0.toByte()
                    Short::class.javaPrimitiveType -> 0.toShort()
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    Char::class.javaPrimitiveType -> '\u0000'
                    else -> null
                }
            }
        } as KeywordGroupDao
    }
}
