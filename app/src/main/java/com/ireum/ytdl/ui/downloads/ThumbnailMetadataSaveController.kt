package com.ireum.ytdl.ui.downloads

import kotlinx.coroutines.CancellationException

internal enum class ThumbnailMetadataSaveState {
    EDITING,
    SAVING,
    COMMITTED,
    INDETERMINATE
}

internal data class ThumbnailMetadataDialogControls(
    val saveEnabled: Boolean,
    val explicitCancelEnabled: Boolean,
    val thumbnailActionsEnabled: Boolean,
    val userCancellationEnabled: Boolean
)

internal class ThumbnailMetadataSaveController(
    private val originalThumbnail: String
) {
    var state: ThumbnailMetadataSaveState = ThumbnailMetadataSaveState.EDITING
        private set

    var editedThumbnail: String = originalThumbnail
        private set

    val dialogControls: ThumbnailMetadataDialogControls
        get() = ThumbnailMetadataDialogControls(
            saveEnabled = state == ThumbnailMetadataSaveState.EDITING,
            explicitCancelEnabled = state != ThumbnailMetadataSaveState.SAVING,
            thumbnailActionsEnabled = state == ThumbnailMetadataSaveState.EDITING,
            userCancellationEnabled = state != ThumbnailMetadataSaveState.SAVING
        )

    fun replaceThumbnail(path: String, deleteThumbnail: (String) -> Unit): Boolean {
        if (state != ThumbnailMetadataSaveState.EDITING) return false
        deleteEditedThumbnailIfReplaced(path, deleteThumbnail)
        editedThumbnail = path
        return true
    }

    fun removeThumbnail(deleteThumbnail: (String) -> Unit): Boolean {
        if (state != ThumbnailMetadataSaveState.EDITING) return false
        deleteEditedThumbnailIfReplaced("", deleteThumbnail)
        editedThumbnail = ""
        return true
    }

    fun beginSave(): Boolean {
        if (state != ThumbnailMetadataSaveState.EDITING) return false
        state = ThumbnailMetadataSaveState.SAVING
        return true
    }

    suspend fun <T> persist(block: suspend () -> T): T {
        check(state == ThumbnailMetadataSaveState.SAVING) {
            "Persistence can only run after beginSave()"
        }
        return try {
            block().also {
                state = ThumbnailMetadataSaveState.COMMITTED
            }
        } catch (error: CancellationException) {
            state = ThumbnailMetadataSaveState.INDETERMINATE
            throw error
        } catch (error: Exception) {
            state = ThumbnailMetadataSaveState.EDITING
            throw error
        }
    }

    fun cleanupOnDismiss(deleteThumbnail: (String) -> Unit) {
        if (
            state == ThumbnailMetadataSaveState.EDITING &&
            editedThumbnail.isNotBlank() &&
            editedThumbnail != originalThumbnail
        ) {
            deleteThumbnail(editedThumbnail)
        }
    }

    private fun deleteEditedThumbnailIfReplaced(
        replacement: String,
        deleteThumbnail: (String) -> Unit
    ) {
        if (
            editedThumbnail.isNotBlank() &&
            editedThumbnail != originalThumbnail &&
            editedThumbnail != replacement
        ) {
            deleteThumbnail(editedThumbnail)
        }
    }
}
