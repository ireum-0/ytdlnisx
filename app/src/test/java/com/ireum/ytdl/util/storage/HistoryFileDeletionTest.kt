package com.ireum.ytdl.util.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class HistoryFileDeletionTest {
    @Test
    fun deletionDialogDefaultsToFilesAndEachNewDialogResetsTheChoice() {
        val first = HistoryDeletionDialogState()
        assertTrue(first.deleteAssociatedFiles)

        first.deleteAssociatedFiles = false
        assertFalse(first.deleteAssociatedFiles)
        assertTrue(HistoryDeletionDialogState().deleteAssociatedFiles)
    }

    @Test
    fun recordOnlyDeletionDoesNotAccessOrAlterAssociatedFiles() {
        val parent = createTempDirectory("history-record-only-").toFile()
        val target = File(parent, "preserved.mp4").apply { writeText("media") }
        val records = listOf(HistoryDeletionRecord(9L, listOf(target.absolutePath)))

        val result = HistoryDeletionPolicy.recordOnly(records)

        assertEquals(1, result.recordsRemoved)
        assertEquals(setOf(9L), result.removableRecordIds)
        assertTrue(result.outcomes.isEmpty())
        assertTrue(target.isFile)
        parent.deleteRecursively()
    }

    @Test
    fun rawFileIsValidatedAndDeletedWithoutTouchingItsParent() {
        val parent = createTempDirectory("history-delete-").toFile()
        val target = File(parent, "video.mp4").apply { writeText("media") }
        val sibling = File(parent, "keep.txt").apply { writeText("keep") }
        val engine = HistoryFileDeletionEngine(RawHistoryFileDeletionGateway())

        val result = engine.execute(
            engine.validate(listOf(HistoryDeletionRecord(1L, listOf(target.absolutePath))))
        )

        assertEquals(1, result.filesDeleted)
        assertEquals(setOf(1L), result.removableRecordIds)
        assertFalse(target.exists())
        assertTrue(parent.isDirectory)
        assertTrue(sibling.isFile)
        parent.deleteRecursively()
    }

    @Test
    fun trustedRawScopeRejectsTargetsOutsideTheApprovedRoot() {
        val root = createTempDirectory("history-scope-").toFile()
        val inside = File(root, "media/video.mp4")
        val outside = File(root.parentFile, "untrusted.mp4")

        assertTrue(HistoryDeletionScope.isWithin(inside, root))
        assertFalse(HistoryDeletionScope.isWithin(root, root))
        assertFalse(HistoryDeletionScope.isWithin(outside, root))
        root.deleteRecursively()
    }

    @Test
    fun emptyAndBlankTargetListsAllowRecordRemovalWithoutFileAccess() {
        val gateway = RecordingGateway()
        val engine = HistoryFileDeletionEngine(gateway)
        val result = engine.execute(
            engine.validate(
                listOf(
                    HistoryDeletionRecord(1L, emptyList()),
                    HistoryDeletionRecord(2L, listOf("", "   "))
                )
            )
        )

        assertEquals(0, gateway.validations)
        assertEquals(0, gateway.deletions)
        assertEquals(setOf(1L, 2L), result.removableRecordIds)
    }

    @Test
    fun existingDirectoryAndSafTreeRootAreRefused() {
        val parent = createTempDirectory("history-directory-").toFile()
        val child = File(parent, "child.mp4").apply { writeText("media") }
        val engine = HistoryFileDeletionEngine(RawHistoryFileDeletionGateway())

        val result = engine.execute(
            engine.validate(
                listOf(
                    HistoryDeletionRecord(1L, listOf(parent.absolutePath)),
                    HistoryDeletionRecord(2L, listOf("content://com.example.documents/tree/primary%3ADownload"))
                )
            )
        )

        assertEquals(2, result.filesSkipped)
        assertTrue(result.removableRecordIds.isEmpty())
        assertTrue(child.isFile)
        parent.deleteRecursively()
    }

    @Test
    fun safDocumentUriIsAcceptedButTreeDocumentRootIsRefused() {
        val document = HistoryDeletionTargetParser.parse(
            "content://com.example.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fvideo.mp4"
        )
        val treeRootDocument = HistoryDeletionTargetParser.parse(
            "content://com.example.documents/tree/primary%3ADownload/document/primary%3ADownload"
        )

        assertTrue(document is HistoryDeletionTargetParseResult.Valid)
        assertEquals(
            HistoryFileDeletionReason.TREE_ROOT,
            (treeRootDocument as HistoryDeletionTargetParseResult.Rejected).reason
        )
        assertEquals(
            HistoryFileDeletionReason.DIRECTORY,
            (HistoryDeletionTargetParser.parse("content://media/external/files/") as HistoryDeletionTargetParseResult.Rejected).reason
        )
        assertEquals(
            HistoryFileDeletionReason.UNRESOLVED,
            (HistoryDeletionTargetParser.parse("content://media/external/file") as HistoryDeletionTargetParseResult.Rejected).reason
        )
    }

    @Test
    fun fileUriDecodingPreservesPlusAndDeduplicatesWithRawPath() {
        val rawPath = "/storage/emulated/0/Download/a+b.mp4"
        val fileUri = "file:///storage/emulated/0/Download/a+b.mp4"

        val parsed = HistoryDeletionTargetParser.parse(fileUri)

        assertTrue(parsed is HistoryDeletionTargetParseResult.Valid)
        assertEquals("a+b.mp4", (parsed as HistoryDeletionTargetParseResult.Valid).target.displayName)
        assertEquals(
            HistoryDeletionTargetParser.deduplicationKey(rawPath),
            HistoryDeletionTargetParser.deduplicationKey(fileUri)
        )
    }

    @Test
    fun equivalentTreeAndSingleDocumentUrisHaveSameDeletionKey() {
        val treeDocument =
            "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fvideo.mp4"
        val singleDocument =
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fvideo.mp4"

        assertEquals(
            HistoryDeletionTargetParser.deduplicationKey(singleDocument),
            HistoryDeletionTargetParser.deduplicationKey(treeDocument)
        )
    }

    @Test
    fun duplicateTargetsAreValidatedAndDeletedOnceAcrossRecords() {
        val gateway = RecordingGateway()
        val engine = HistoryFileDeletionEngine(gateway)
        val shared = File("shared.mp4").absolutePath

        val validation = engine.validate(
            listOf(
                HistoryDeletionRecord(1L, listOf(shared, shared)),
                HistoryDeletionRecord(2L, listOf(shared))
            )
        )
        val result = engine.execute(validation)

        assertEquals(1, gateway.validations)
        assertEquals(1, gateway.deletions)
        assertEquals(1, result.filesDeleted)
        assertEquals(setOf(1L, 2L), result.removableRecordIds)
    }

    @Test
    fun retainedRecordReferenceIsExcludedFromSelectedDeletion() {
        val shared = File("shared.mp4").absoluteFile
        val unique = File("unique.mp4").absoluteFile
        val records = listOf(
            HistoryDeletionRecord(1L, listOf(shared.absolutePath, unique.absolutePath))
        )

        val guarded = HistoryDeletionReferenceGuard.excludeRetainedTargets(
            records,
            sequenceOf(shared.toURI().toString())
        )

        assertEquals(listOf(unique.absolutePath), guarded.single().storedTargets)
        assertEquals(records.single().recordStoredTargetSnapshot, guarded.single().recordStoredTargetSnapshot)
    }

    @Test
    fun newlyRetainedReferenceIsExcludedBeforeExecution() {
        val gateway = RecordingGateway()
        val engine = HistoryFileDeletionEngine(gateway)
        val shared = File("shared.mp4").absolutePath
        val unique = File("unique.mp4").absolutePath
        val validation = engine.validate(
            listOf(HistoryDeletionRecord(1L, listOf(shared, unique)))
        ).excludeTargetsReferencedBy(sequenceOf(shared))

        val result = engine.execute(validation)

        assertEquals(1, gateway.deletions)
        assertEquals(setOf(1L), result.removableRecordIds)
    }

    @Test
    fun canonicalTargetReplacementKeepsNaturalReferenceKeys() {
        val rawPath = File("shared.mp4").absolutePath
        val rawKey = HistoryDeletionTargetParser.deduplicationKey(rawPath)!!
        val contentUri = "content://example.documents/document/shared.mp4"
        val gateway = object : HistoryFileDeletionGateway {
            var deletions = 0

            override fun validate(target: HistoryDeletionTarget) = HistoryFileDeletionOutcome(
                target.key,
                target.displayName,
                HistoryFileDeletionStatus.READY
            )

            override fun delete(target: HistoryDeletionTarget): HistoryFileDeletionOutcome {
                deletions += 1
                return HistoryFileDeletionOutcome(
                    target.key,
                    target.displayName,
                    HistoryFileDeletionStatus.DELETED
                )
            }

            override fun referenceKeys(target: HistoryDeletionTarget): Set<String> {
                return naturalReferenceKeys(target) + if (target is HistoryDeletionTarget.ContentDocument) {
                    setOf(rawKey)
                } else {
                    emptySet()
                }
            }
        }
        val engine = HistoryFileDeletionEngine(gateway)
        val validation = engine.validate(
            listOf(HistoryDeletionRecord(1L, listOf(contentUri, rawPath)))
        )

        val guarded = engine.excludeTargetsReferencedBy(validation, sequenceOf(rawPath))
        val result = engine.execute(guarded)

        assertEquals(0, gateway.deletions)
        assertEquals(setOf(1L), result.removableRecordIds)
    }

    @Test
    fun trustedDocumentReplacementPreservesEquivalentRawPath() {
        val rawPath = File("shared.mp4").absolutePath
        val rawKey = HistoryDeletionTargetParser.deduplicationKey(rawPath)!!
        val contentUri = "content://example.documents/document/shared.mp4"
        val gateway = object : HistoryFileDeletionGateway {
            override fun validate(target: HistoryDeletionTarget) = HistoryFileDeletionOutcome(
                target.key,
                target.displayName,
                HistoryFileDeletionStatus.READY
            )

            override fun delete(target: HistoryDeletionTarget) = HistoryFileDeletionOutcome(
                target.key,
                target.displayName,
                HistoryFileDeletionStatus.DELETED
            )

            override fun referenceKeys(target: HistoryDeletionTarget): Set<String> {
                return naturalReferenceKeys(target) + if (target is HistoryDeletionTarget.ContentDocument) {
                    setOf(rawKey)
                } else {
                    emptySet()
                }
            }
        }
        val engine = HistoryFileDeletionEngine(gateway)

        val validation = engine.validate(
            listOf(
                HistoryDeletionRecord(
                    id = 1L,
                    storedTargets = listOf(rawPath, contentUri),
                    trustedDocumentTargets = setOf(contentUri)
                )
            )
        )

        val target = validation.targets.values.single() as HistoryDeletionTarget.ContentDocument
        assertEquals(File(rawPath).canonicalPath, target.expectedRawPath)
    }

    @Test
    fun trustedDocumentFirstPreservesLaterEquivalentRawPath() {
        val rawPath = File("shared.mp4").absolutePath
        val rawKey = HistoryDeletionTargetParser.deduplicationKey(rawPath)!!
        val contentUri = "content://example.documents/document/shared.mp4"
        val gateway = object : HistoryFileDeletionGateway {
            override fun validate(target: HistoryDeletionTarget) = HistoryFileDeletionOutcome(
                target.key,
                target.displayName,
                HistoryFileDeletionStatus.READY
            )

            override fun delete(target: HistoryDeletionTarget) = HistoryFileDeletionOutcome(
                target.key,
                target.displayName,
                HistoryFileDeletionStatus.DELETED
            )

            override fun referenceKeys(target: HistoryDeletionTarget): Set<String> {
                return naturalReferenceKeys(target) + if (target is HistoryDeletionTarget.ContentDocument) {
                    setOf(rawKey)
                } else {
                    emptySet()
                }
            }
        }
        val engine = HistoryFileDeletionEngine(gateway)

        val validation = engine.validate(
            listOf(
                HistoryDeletionRecord(
                    id = 1L,
                    storedTargets = listOf(contentUri, rawPath),
                    trustedDocumentTargets = setOf(contentUri)
                )
            )
        )

        val target = validation.targets.values.single() as HistoryDeletionTarget.ContentDocument
        assertEquals(File(rawPath).canonicalPath, target.expectedRawPath)
    }

    @Test
    fun partialDeletionRemovesOnlyRecordsWhoseTargetsSucceeded() {
        val gateway = RecordingGateway(failedNames = setOf("blocked.mp4"))
        val engine = HistoryFileDeletionEngine(gateway)
        val result = engine.execute(
            engine.validate(
                listOf(
                    HistoryDeletionRecord(1L, listOf(File("ok.mp4").absolutePath)),
                    HistoryDeletionRecord(2L, listOf(File("blocked.mp4").absolutePath)),
                    HistoryDeletionRecord(3L, listOf(File("ok.mp4").absolutePath, File("blocked.mp4").absolutePath))
                )
            )
        )

        assertEquals(1, result.filesDeleted)
        assertEquals(1, result.filesFailed)
        assertEquals(setOf(1L), result.removableRecordIds)
    }

    @Test
    fun permissionAndAmbiguousFailuresAreNotReportedAsMissing() {
        val gateway = object : HistoryFileDeletionGateway {
            override fun validate(target: HistoryDeletionTarget): HistoryFileDeletionOutcome {
                val status = if (target.displayName == "permission.mp4") {
                    HistoryFileDeletionStatus.PERMISSION_REQUIRED
                } else {
                    HistoryFileDeletionStatus.FAILED
                }
                return HistoryFileDeletionOutcome(target.key, target.displayName, status)
            }

            override fun delete(target: HistoryDeletionTarget): HistoryFileDeletionOutcome {
                throw AssertionError("Invalid targets must not be deleted")
            }
        }
        val engine = HistoryFileDeletionEngine(gateway)
        val result = engine.execute(
            engine.validate(
                listOf(
                    HistoryDeletionRecord(1L, listOf(File("permission.mp4").absolutePath)),
                    HistoryDeletionRecord(2L, listOf(File("ambiguous.mp4").absolutePath))
                )
            )
        )

        assertEquals(1, result.filesPermissionDenied)
        assertEquals(1, result.filesFailed)
        assertEquals(0, result.filesAlreadyAbsent)
        assertTrue(result.removableRecordIds.isEmpty())
    }

    @Test
    fun inconclusiveContentAbsenceNeverAllowsRecordRemoval() {
        val target = (
            HistoryDeletionTargetParser.parse("content://media/external/file/42") as
                HistoryDeletionTargetParseResult.Valid
            ).target

        val readable = inconclusiveContentAbsenceOutcome(target, hasReadAccess = true)
        val inaccessible = inconclusiveContentAbsenceOutcome(target, hasReadAccess = false)

        assertEquals(HistoryFileDeletionStatus.FAILED, readable.status)
        assertEquals(HistoryFileDeletionReason.UNKNOWN, readable.reason)
        assertEquals(HistoryFileDeletionStatus.PERMISSION_REQUIRED, inaccessible.status)
        assertEquals(HistoryFileDeletionReason.ACCESS_DENIED, inaccessible.reason)
        assertFalse(readable.allowsRecordRemoval)
        assertFalse(inaccessible.allowsRecordRemoval)
    }

    @Test
    fun alreadyAbsentTargetsAllowRecordRemoval() {
        val gateway = RecordingGateway(absentNames = setOf("gone.mp4"))
        val engine = HistoryFileDeletionEngine(gateway)
        val result = engine.execute(
            engine.validate(listOf(HistoryDeletionRecord(7L, listOf(File("gone.mp4").absolutePath))))
        )

        assertEquals(1, result.filesAlreadyAbsent)
        assertEquals(setOf(7L), result.removableRecordIds)
        assertEquals(0, gateway.deletions)
    }

    @Test
    fun changedRecordSnapshotBlocksItsTargetAndAnySharedDeletion() {
        val gateway = RecordingGateway()
        val engine = HistoryFileDeletionEngine(gateway)
        val shared = File("shared.mp4").absolutePath
        val independent = File("independent.mp4").absolutePath
        val validation = engine.validate(
            listOf(
                HistoryDeletionRecord(1L, listOf(shared)),
                HistoryDeletionRecord(2L, listOf(shared)),
                HistoryDeletionRecord(3L, listOf(independent))
            )
        )

        val revalidated = validation.revalidateRecordSnapshots(
            mapOf(
                1L to listOf(File("reconnected.mp4").absolutePath),
                2L to listOf(shared),
                3L to listOf(independent)
            )
        )
        val result = engine.execute(revalidated)

        assertEquals(1, gateway.deletions)
        assertEquals(1, result.filesDeleted)
        assertEquals(1, result.filesSkipped)
        assertEquals(setOf(3L), result.removableRecordIds)
    }

    @Test
    fun privateTargetsAreNotExposedByOutcomeKeysOrDiagnostics() {
        val privatePath = File("private/account/video.mp4").absolutePath
        val gateway = RecordingGateway(failedNames = setOf("video.mp4"))
        val engine = HistoryFileDeletionEngine(gateway)
        val result = engine.execute(
            engine.validate(listOf(HistoryDeletionRecord(1L, listOf(privatePath))))
        )

        val diagnostic = result.outcomes.joinToString()
        assertFalse(diagnostic.contains(privatePath))
        assertFalse(diagnostic.contains("account"))
        assertTrue(diagnostic.contains("video.mp4"))
    }

    private class RecordingGateway(
        private val failedNames: Set<String> = emptySet(),
        private val absentNames: Set<String> = emptySet()
    ) : HistoryFileDeletionGateway {
        var validations = 0
        var deletions = 0

        override fun validate(target: HistoryDeletionTarget): HistoryFileDeletionOutcome {
            validations += 1
            val status = if (target.displayName in absentNames) {
                HistoryFileDeletionStatus.ALREADY_ABSENT
            } else {
                HistoryFileDeletionStatus.READY
            }
            return HistoryFileDeletionOutcome(target.key, target.displayName, status)
        }

        override fun delete(target: HistoryDeletionTarget): HistoryFileDeletionOutcome {
            deletions += 1
            val status = if (target.displayName in failedNames) {
                HistoryFileDeletionStatus.FAILED
            } else {
                HistoryFileDeletionStatus.DELETED
            }
            return HistoryFileDeletionOutcome(target.key, target.displayName, status)
        }
    }
}
