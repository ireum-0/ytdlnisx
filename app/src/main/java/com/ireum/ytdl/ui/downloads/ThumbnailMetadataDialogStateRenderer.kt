package com.ireum.ytdl.ui.downloads

import android.view.View
import androidx.appcompat.app.AlertDialog

internal interface ThumbnailMetadataDialogControlTarget {
    fun setSaveEnabled(enabled: Boolean)
    fun setExplicitCancelEnabled(enabled: Boolean)
    fun setThumbnailActionsEnabled(enabled: Boolean)
    fun setUserCancellationEnabled(enabled: Boolean)
}

internal object ThumbnailMetadataDialogStateRenderer {
    fun render(
        controls: ThumbnailMetadataDialogControls,
        target: ThumbnailMetadataDialogControlTarget
    ) {
        target.setSaveEnabled(controls.saveEnabled)
        target.setExplicitCancelEnabled(controls.explicitCancelEnabled)
        target.setThumbnailActionsEnabled(controls.thumbnailActionsEnabled)
        target.setUserCancellationEnabled(controls.userCancellationEnabled)
    }

    fun render(
        dialog: AlertDialog,
        controller: ThumbnailMetadataSaveController,
        thumbnailActions: List<View>
    ) {
        render(
            controller.dialogControls,
            object : ThumbnailMetadataDialogControlTarget {
                override fun setSaveEnabled(enabled: Boolean) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = enabled
                }

                override fun setExplicitCancelEnabled(enabled: Boolean) {
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = enabled
                }

                override fun setThumbnailActionsEnabled(enabled: Boolean) {
                    thumbnailActions.forEach { it.isEnabled = enabled }
                }

                override fun setUserCancellationEnabled(enabled: Boolean) {
                    dialog.setCancelable(enabled)
                    dialog.setCanceledOnTouchOutside(enabled)
                }
            }
        )
    }
}
