package com.ireum.ytdl.util

import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAddIdentityTest {
    @Test
    fun sameNameDifferentDirectoriesRemainDistinct() {
        val first = LocalAddIdentity.keyFor("content://provider/document/dir-a%3Avideo.mp4")
        val second = LocalAddIdentity.keyFor("content://provider/document/dir-b%3Avideo.mp4")

        assertNotEquals(first, second)
    }

    @Test
    fun sameDocumentIdFromDifferentProvidersRemainsDistinct() {
        val first = LocalAddIdentity.keyFor("content://provider-a/document/primary%3Avideo.mp4")
        val second = LocalAddIdentity.keyFor("content://provider-b/document/primary%3Avideo.mp4")

        assertNotEquals(first, second)
    }

    @Test
    fun exactUriAndTreePathDuplicatesRemainComparable() {
        val uri = "content://provider/document/primary%3Avideo.mp4"
        assertEquals(LocalAddIdentity.keyFor(uri), LocalAddIdentity.keyFor(uri))

        val tree = "content://provider/tree/primary%3Amovies"
        assertEquals(
            LocalAddIdentity.keyFor(uri, tree, "video.mp4"),
            LocalAddIdentity.keyFor(uri, tree, "video.mp4")
        )
    }

    @Test
    fun treeAuthorityAndRelativePathArePartOfIdentity() {
        val uri = "content://provider/document/primary%3Amovies%2Fvideo.mp4"
        val first = LocalAddIdentity.keyFor(uri, "content://provider/tree/primary%3Amovies", "video.mp4")
        val second = LocalAddIdentity.keyFor(uri, "content://provider/tree/primary%3Aother", "video.mp4")

        assertNotEquals(first, second)
    }

    @Test
    fun unknownOrMalformedInputsFailOpenWithNoIdentity() {
        assertNull(LocalAddIdentity.keyFor(""))
        assertNull(LocalAddIdentity.keyFor("yt-dlp https://example.com/video"))
        assertNull(LocalAddIdentity.keyFor("not a uri"))
        assertNull(LocalAddIdentity.keyFor("https://example.com/video"))
        assertNull(LocalAddIdentity.keyFor("content://"))
    }

    @Test
    fun fileUrisRemainExactStableIdentities() {
        val key = LocalAddIdentity.keyFor("FILE:///storage/emulated/0/Movies/video.mp4")

        assertTrue(key?.startsWith("uri:file:") == true)
    }

    @Test
    fun absoluteRawPathsRemainExactStableIdentities() {
        val first = LocalAddIdentity.keyFor("/storage/emulated/0/Movies/video.mp4")
        val second = LocalAddIdentity.keyFor("/storage/emulated/0/Other/video.mp4")

        assertNotEquals(first, second)
    }

    @Test
    fun unknownIdentitiesAreNotCollapsedByBatchPolicy() {
        val entries = listOf("not a uri", "also not a uri")
        val seen = HashSet<String>()
        val retained = entries.filter { value ->
            val key = LocalAddIdentity.keyFor(value)
            key == null || seen.add(key)
        }

        assertEquals(entries, retained)
    }

    @Test
    fun persistedHistoryUsesExactPathAndTreeAuthority() {
        val uri = "content://provider/document/primary%3Avideo.mp4"
        val item = HistoryItem(
            id = 1L,
            url = "file:///unused",
            title = "video",
            author = "",
            duration = "",
            thumb = "",
            type = DownloadType.video,
            time = 1L,
            downloadPath = listOf(uri),
            website = "",
            format = Format(),
            downloadId = 1L,
            localTreeUri = "content://provider/tree/primary%3Amovies",
            localTreePath = "video.mp4"
        )

        val keys = LocalAddIdentity.keysForHistory(item)
        assertTrue(keys.contains(LocalAddIdentity.keyFor(uri)))
        assertTrue(keys.contains(LocalAddIdentity.keyFor(uri, item.localTreeUri, item.localTreePath)))
    }
}
