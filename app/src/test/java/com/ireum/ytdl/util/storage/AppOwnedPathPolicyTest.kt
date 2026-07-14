package com.ireum.ytdl.util.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppOwnedPathPolicyTest {
    @Test
    fun acceptsOnlyTheRootAndItsDescendants() {
        val parent = Files.createTempDirectory("cache-policy").toFile()
        try {
            val root = File(parent, "owned").apply { mkdirs() }
            val child = File(root, "nested/file.tmp")
            val sibling = File(parent, "owned-backup/file.tmp")

            assertTrue(AppOwnedPathPolicy.isWithin(root, listOf(root)))
            assertTrue(AppOwnedPathPolicy.isWithin(child, listOf(root)))
            assertFalse(AppOwnedPathPolicy.isWithin(sibling, listOf(root)))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun canonicalTraversalCannotEscapeTheOwnedRoot() {
        val parent = Files.createTempDirectory("cache-traversal").toFile()
        try {
            val root = File(parent, "owned").apply { mkdirs() }
            val escaped = File(root, "../outside/file.tmp")

            assertFalse(AppOwnedPathPolicy.isWithin(escaped, listOf(root)))
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun importedCookiesAreProtectedFromAppCacheCleanup() {
        val appCache = Files.createTempDirectory("app-cache-protection").toFile()
        try {
            val cookies = File(appCache, "cookies.txt")
            val temporaryFile = File(appCache, "temporary.part")
            val protectedEntries = protectedAppCacheEntries(appCache)

            assertTrue(protectedEntries.any { AppOwnedPathPolicy.isWithin(cookies, listOf(it)) })
            assertFalse(protectedEntries.any { AppOwnedPathPolicy.isWithin(temporaryFile, listOf(it)) })
        } finally {
            appCache.deleteRecursively()
        }
    }
}
