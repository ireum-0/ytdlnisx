package com.ireum.ytdl.util.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class FileAccessStateTest {
    @Test
    fun aggregateDoesNotReportPermissionFailureAsMissing() {
        assertEquals(
            FileAccessState.PERMISSION_REQUIRED,
            FileAccessStateResolver.combine(
                listOf(FileAccessState.EXISTS, FileAccessState.PERMISSION_REQUIRED)
            )
        )
    }

    @Test
    fun aggregateKeepsUncheckedAndEmptyInputsUnknown() {
        assertEquals(FileAccessState.UNKNOWN, FileAccessStateResolver.combine(emptyList()))
        assertEquals(
            FileAccessState.UNKNOWN,
            FileAccessStateResolver.combine(listOf(FileAccessState.UNKNOWN))
        )
    }

    @Test
    fun aggregateRequiresEveryOutputToExist() {
        assertEquals(
            FileAccessState.MISSING,
            FileAccessStateResolver.combine(
                listOf(FileAccessState.EXISTS, FileAccessState.MISSING)
            )
        )
        assertEquals(
            FileAccessState.EXISTS,
            FileAccessStateResolver.combine(
                listOf(FileAccessState.EXISTS, FileAccessState.EXISTS)
            )
        )
    }
}
