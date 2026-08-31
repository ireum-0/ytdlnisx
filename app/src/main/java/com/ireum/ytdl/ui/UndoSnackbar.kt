package com.ireum.ytdl.ui

import android.view.View
import com.google.android.material.R as MaterialR
import com.google.android.material.snackbar.Snackbar

/**
 * Installs an Undo action that is dismissed only after its synchronous
 * authority acceptance succeeds.  Snackbar's normal setAction listener
 * dismisses before a failed persistence handoff can be re-offered.
 */
internal fun Snackbar.setManualUndoAction(
    label: CharSequence,
    onClick: () -> Boolean,
): Snackbar {
    // setAction creates and exposes the material action view.  Replace its
    // default listener so a failed durable acceptance leaves this exact
    // capability visible to the same presentation owner.
    setAction(label) { }
    view.findViewById<View>(MaterialR.id.snackbar_action)?.setOnClickListener {
        if (onClick()) dismiss()
    }
    return this
}
