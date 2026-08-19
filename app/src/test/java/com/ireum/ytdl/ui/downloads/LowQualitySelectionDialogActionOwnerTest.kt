package com.ireum.ytdl.ui.downloads

import com.ireum.ytdl.database.models.LowQualityRedownloadOperation
import com.ireum.ytdl.database.models.LowQualityRedownloadPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowQualitySelectionDialogActionOwnerTest {
    @Test
    fun backOrOutsideCancellationInvokesCancellation() {
        var confirmations = 0
        var cancellations = 0
        val owner = owner(
            onConfirm = { confirmations += 1 },
            onCancel = { cancellations += 1 }
        )

        assertTrue(owner.cancel())
        assertEquals(0, confirmations)
        assertEquals(1, cancellations)
    }

    @Test
    fun explicitConfirmationRunsOnceWithoutCancellation() {
        var confirmations = 0
        var cancellations = 0
        val owner = owner(
            onConfirm = { confirmations += 1 },
            onCancel = { cancellations += 1 }
        )

        assertTrue(owner.confirm())
        assertEquals(1, confirmations)
        assertEquals(0, cancellations)
    }

    @Test
    fun explicitCancellationRunsOnceWithoutConfirmation() {
        var confirmations = 0
        var cancellations = 0
        val owner = owner(
            onConfirm = { confirmations += 1 },
            onCancel = { cancellations += 1 }
        )

        assertTrue(owner.cancel())
        assertEquals(0, confirmations)
        assertEquals(1, cancellations)
    }

    @Test
    fun duplicateDialogCallbacksCannotTakeASecondAction() {
        var confirmations = 0
        var cancellations = 0
        val owner = owner(
            onConfirm = { confirmations += 1 },
            onCancel = { cancellations += 1 }
        )

        assertTrue(owner.cancel())
        assertFalse(owner.cancel())
        assertFalse(owner.confirm())
        assertEquals(0, confirmations)
        assertEquals(1, cancellations)
    }

    @Test
    fun onlyRunningAwaitingSelectionWithCandidatesIsPresentable() {
        val operation = LowQualityRedownloadOperation(
            operationId = "operation",
            phase = LowQualityRedownloadPhase.AWAITING_SELECTION.name
        )

        assertTrue(shouldPresentLowQualitySelection(operation, candidateCount = 1))
        assertFalse(shouldPresentLowQualitySelection(operation, candidateCount = 0))
        assertFalse(
            shouldPresentLowQualitySelection(
                operation.copy(phase = LowQualityRedownloadPhase.PREPARING.name),
                candidateCount = 1
            )
        )
    }

    private fun owner(
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) = LowQualitySelectionDialogActionOwner(onConfirm, onCancel)
}
