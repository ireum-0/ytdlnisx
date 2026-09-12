package com.ireum.ytdl.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ireum.ytdl.util.FileUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises operation-local move failures without the legacy global side channel. */
@RunWith(AndroidJUnit4::class)
class BackupMoveResultProductionWiringTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun moveFailureRemainsBoundAfterUnrelatedMoves() = runBlocking {
        val root = File(
            FileUtil.getCachePath(context),
            "backup-move-result-${System.nanoTime()}",
        ).apply { mkdirs() }
        try {
            val failedOrigin = File(root, "failed-origin").apply { mkdirs() }
            val failedSource = File(failedOrigin, "blocked/payload.dat").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val successfulSource = File(failedOrigin, "published.json").apply {
                writeText("published")
            }
            val failedDestination = File(root, "failed-destination").apply { mkdirs() }
            File(failedDestination, "blocked").writeText("not a directory")

            val failedResult = FileUtil.moveFileWithResult(
                originDir = failedOrigin,
                context = context,
                destDir = failedDestination.absolutePath,
                keepCache = true,
                progress = {},
                sourceFiles = listOf(failedSource, successfulSource),
            )
            assertTrue(failedResult.paths.any { it.endsWith("published.json") })
            assertTrue(failedResult.failures.any { it.contains("payload.dat") })

            val unrelatedOrigin = File(root, "unrelated-origin").apply { mkdirs() }
            val unrelatedSource = File(unrelatedOrigin, "unrelated.json").apply {
                writeText("unrelated")
            }
            val unrelatedDestination = File(root, "unrelated-destination").apply { mkdirs() }
            val unrelatedResult = FileUtil.moveFileWithResult(
                originDir = unrelatedOrigin,
                context = context,
                destDir = unrelatedDestination.absolutePath,
                keepCache = true,
                progress = {},
                sourceFiles = listOf(unrelatedSource),
            )

            assertTrue(unrelatedResult.failures.isEmpty())
            assertFalse(failedResult.failures.isEmpty())
            assertTrue(failedResult.failures.any { it.contains("payload.dat") })
        } finally {
            root.deleteRecursively()
        }
    }
}
