package com.ireum.ytdl.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileUtilScanMediaTest {
    @Test
    fun mediaScanPreservesExactPublicationOrderAndOnlyRemovesDuplicates() {
        val first = "content://media/video-primary"
        val side = "content://media/sidecar"
        val last = "content://media/video-secondary"

        assertEquals(
            listOf(first, side, last),
            FileUtil.mediaScanPublicationOrder(listOf(first, side, last, first)),
        )
    }
}
