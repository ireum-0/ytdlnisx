package com.ireum.ytdl.util

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Verifies the production cache-path resolver rejects provider-only roots. */
@RunWith(AndroidJUnit4::class)
class CacheStorageAuthorityProductionWiringTest {
    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private var hadCachePath = false
    private var previousCachePath: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        hadCachePath = preferences.contains("cache_path")
        previousCachePath = preferences.getString("cache_path", null)
    }

    @After
    fun tearDown() {
        val editor = preferences.edit()
        if (hadCachePath) {
            editor.putString("cache_path", previousCachePath)
        } else {
            editor.remove("cache_path")
        }
        assertTrue(editor.commit())
    }

    @Test
    fun persistedSafTreesFallBackBeforeNativeCacheResolution() {
        // Establish the expected app-owned default independently of whatever
        // preference state the test process inherited.
        assertTrue(preferences.edit().remove("cache_path").commit())
        val defaultPath = File(FileUtil.getCachePath(context)).canonicalFile
        val providerValues = listOf(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            "content://com.android.externalstorage.documents/tree/0123-4567%3Amedia",
        )

        providerValues.forEach { providerValue ->
            assertTrue(preferences.edit().putString("cache_path", providerValue).commit())

            assertFalse(FileUtil.isSupportedCachePathSelection(context, providerValue))
            assertEquals(defaultPath, File(FileUtil.getCachePath(context)).canonicalFile)
        }
    }

    @Test
    fun unsupportedRawCacheFallsBackBeforeNativeCacheResolution() {
        assertTrue(preferences.edit().remove("cache_path").commit())
        val defaultPath = File(FileUtil.getCachePath(context)).canonicalFile
        val unavailable = File(
            context.filesDir,
            "missing-parent-${UUID.randomUUID()}${File.separator}cache",
        ).absolutePath
        assertFalse(FileUtil.isSupportedCachePathSelection(context, unavailable))
        assertTrue(preferences.edit().putString("cache_path", unavailable).commit())

        assertEquals(defaultPath, File(FileUtil.getCachePath(context)).canonicalFile)
    }

    @Test
    fun appOwnedFilesystemCacheRemainsSupported() {
        val externalFiles = requireNotNull(context.getExternalFilesDir(null))
        val custom = File(externalFiles, "cache-authority-${UUID.randomUUID()}")
        try {
            assertTrue(FileUtil.isSupportedCachePathSelection(context, custom.absolutePath))
            assertTrue(preferences.edit().putString("cache_path", custom.absolutePath).commit())

            assertEquals(custom.canonicalFile, File(FileUtil.getCachePath(context)).canonicalFile)
        } finally {
            custom.deleteRecursively()
        }
    }
}
