package com.ireum.ytdl.ui.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ThumbnailMetadataSaveControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savingBlocksUserDismissalAndPreservesPendingThumbnailOnDismiss() {
        val thumbnail = temporaryFolder.newFile("pending.jpg")
        val controller = controllerWith(thumbnail.absolutePath)
        val target = RecordingDialogTarget()

        assertTrue(controller.beginSave())
        ThumbnailMetadataDialogStateRenderer.render(controller.dialogControls, target)
        controller.cleanupOnDismiss(::deleteThumbnail)

        assertEquals(ThumbnailMetadataSaveState.SAVING, controller.state)
        assertFalse(target.saveIsEnabled)
        assertFalse(target.explicitCancelIsEnabled)
        assertFalse(target.thumbnailActionsAreEnabled)
        assertFalse(target.userCancellationIsEnabled)
        assertTrue(thumbnail.exists())
    }

    @Test
    fun successfulPersistenceCommitsBeforeReturningAndPreservesThumbnail() = runBlocking {
        val thumbnail = temporaryFolder.newFile("committed.jpg")
        val controller = controllerWith(thumbnail.absolutePath)
        assertTrue(controller.beginSave())

        val result = controller.persist {
            assertEquals(ThumbnailMetadataSaveState.SAVING, controller.state)
            "saved"
        }
        controller.cleanupOnDismiss(::deleteThumbnail)

        assertEquals("saved", result)
        assertEquals(ThumbnailMetadataSaveState.COMMITTED, controller.state)
        assertTrue(thumbnail.exists())
    }

    @Test
    fun ordinaryFailureRestoresEditingAndRetainsThumbnailUntilDismissal() = runBlocking {
        val thumbnail = temporaryFolder.newFile("retry.jpg")
        val controller = controllerWith(thumbnail.absolutePath)
        val expected = IOException("database unavailable")
        assertTrue(controller.beginSave())

        try {
            controller.persist<Unit> { throw expected }
        } catch (actual: IOException) {
            assertSame(expected, actual)
        }

        assertEquals(ThumbnailMetadataSaveState.EDITING, controller.state)
        assertTrue(controller.dialogControls.saveEnabled)
        assertTrue(controller.dialogControls.explicitCancelEnabled)
        assertTrue(controller.dialogControls.thumbnailActionsEnabled)
        assertTrue(controller.dialogControls.userCancellationEnabled)
        assertTrue(thumbnail.exists())

        controller.cleanupOnDismiss(::deleteThumbnail)
        assertFalse(thumbnail.exists())
    }

    @Test
    fun cancellationBecomesIndeterminateRethrowsAndPreservesThumbnail() = runBlocking {
        val thumbnail = temporaryFolder.newFile("possibly-committed.jpg")
        val controller = controllerWith(thumbnail.absolutePath)
        val expected = CancellationException("owner destroyed")
        assertTrue(controller.beginSave())

        try {
            controller.persist<Unit> { throw expected }
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
        controller.cleanupOnDismiss(::deleteThumbnail)

        assertEquals(ThumbnailMetadataSaveState.INDETERMINATE, controller.state)
        assertTrue(controller.dialogControls.userCancellationEnabled)
        assertTrue(controller.dialogControls.explicitCancelEnabled)
        assertTrue(thumbnail.exists())
    }

    @Test
    fun dismissBeforeSaveDeletesChangedTemporaryThumbnail() {
        val thumbnail = temporaryFolder.newFile("cancelled-edit.jpg")
        val controller = controllerWith(thumbnail.absolutePath)

        controller.cleanupOnDismiss(::deleteThumbnail)

        assertEquals(ThumbnailMetadataSaveState.EDITING, controller.state)
        assertFalse(thumbnail.exists())
    }

    @Test
    fun secondSaveWhileSavingIsRejected() {
        val controller = ThumbnailMetadataSaveController("")

        assertTrue(controller.beginSave())
        assertFalse(controller.beginSave())
        assertEquals(ThumbnailMetadataSaveState.SAVING, controller.state)
    }

    @Test
    fun thumbnailSelectionCaptureAndRemovalAreDisabledWhileSaving() {
        val first = temporaryFolder.newFile("selected.jpg")
        val second = temporaryFolder.newFile("late-selection.jpg")
        val controller = controllerWith(first.absolutePath)
        assertTrue(controller.beginSave())

        assertFalse(controller.dialogControls.thumbnailActionsEnabled)
        assertFalse(controller.replaceThumbnail(second.absolutePath, ::deleteThumbnail))
        assertFalse(controller.removeThumbnail(::deleteThumbnail))
        assertEquals(first.absolutePath, controller.editedThumbnail)
        assertTrue(first.exists())
        assertTrue(second.exists())
    }

    @Test
    fun sharedRendererAppliesIdenticalPolicyToBothDialogTargets() {
        val controller = ThumbnailMetadataSaveController("")
        assertTrue(controller.beginSave())
        val playerDialog = RecordingDialogTarget()
        val historyDialog = RecordingDialogTarget()

        ThumbnailMetadataDialogStateRenderer.render(controller.dialogControls, playerDialog)
        ThumbnailMetadataDialogStateRenderer.render(controller.dialogControls, historyDialog)

        assertEquals(playerDialog, historyDialog)
    }

    private fun controllerWith(editedThumbnail: String): ThumbnailMetadataSaveController {
        return ThumbnailMetadataSaveController("").also {
            assertTrue(it.replaceThumbnail(editedThumbnail, ::deleteThumbnail))
        }
    }

    private fun deleteThumbnail(path: String) {
        java.io.File(path).delete()
    }

    private data class RecordingDialogTarget(
        var saveIsEnabled: Boolean = true,
        var explicitCancelIsEnabled: Boolean = true,
        var thumbnailActionsAreEnabled: Boolean = true,
        var userCancellationIsEnabled: Boolean = true
    ) : ThumbnailMetadataDialogControlTarget {
        override fun setSaveEnabled(enabled: Boolean) {
            saveIsEnabled = enabled
        }

        override fun setExplicitCancelEnabled(enabled: Boolean) {
            explicitCancelIsEnabled = enabled
        }

        override fun setThumbnailActionsEnabled(enabled: Boolean) {
            thumbnailActionsAreEnabled = enabled
        }

        override fun setUserCancellationEnabled(enabled: Boolean) {
            userCancellationIsEnabled = enabled
        }
    }
}
