package com.ireum.ytdl.database

internal fun RestoreOutcome.isCompleted(): Boolean = this is RestoreOutcome.Completed
