package com.ireum.ytdl.ui.downloads

import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.LowQualityRedownloadOperationState
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase

internal class LowQualitySelectionDialogActionOwner(
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit
) {
    private var actionTaken = false

    fun confirm(): Boolean = takeAction(onConfirm)

    fun cancel(): Boolean = takeAction(onCancel)

    private fun takeAction(action: () -> Unit): Boolean {
        if (actionTaken) return false
        actionTaken = true
        action()
        return true
    }
}

internal fun shouldPresentLowQualitySelection(
    operation: LowQualityRedownloadOperation?,
    candidateCount: Int
): Boolean = operation?.stateValue == LowQualityRedownloadOperationState.RUNNING &&
    operation.phaseValue == LowQualityRedownloadPhase.AWAITING_SELECTION &&
    candidateCount > 0
