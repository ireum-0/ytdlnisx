package com.ireum.ytdl.util.storage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.preference.PreferenceManager
import com.ireum.ytdl.util.FileUtil
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class HistoryDeletionRecord(
    val id: Long,
    val storedTargets: List<String>,
    val recordStoredTargetSnapshot: List<String> = storedTargets,
    val trustedDocumentTargets: Set<String> = emptySet()
)

class HistoryDeletionDialogState {
    var deleteAssociatedFiles: Boolean = true
}

object HistoryDeletionPolicy {
    fun recordOnly(records: List<HistoryDeletionRecord>): HistoryDeletionSummary {
        val ids = records.mapTo(linkedSetOf(), HistoryDeletionRecord::id)
        return HistoryDeletionSummary(
            recordsRequested = records.size,
            recordsRemoved = ids.size,
            removableRecordIds = ids,
            outcomes = emptyList()
        )
    }
}

object HistoryDeletionReferenceGuard {
    fun excludeRetainedTargets(
        records: List<HistoryDeletionRecord>,
        retainedStoredTargets: Sequence<String>
    ): List<HistoryDeletionRecord> {
        val retainedKeys = retainedStoredTargets
            .mapNotNull(HistoryDeletionTargetParser::deduplicationKey)
            .toSet()
        if (retainedKeys.isEmpty()) return records
        return records.map { record ->
            record.copy(
                storedTargets = record.storedTargets.filter { target ->
                    HistoryDeletionTargetParser.deduplicationKey(target) !in retainedKeys
                }
            )
        }
    }
}

enum class HistoryFileDeletionStatus {
    READY,
    DELETED,
    ALREADY_ABSENT,
    SKIPPED,
    PERMISSION_REQUIRED,
    FAILED
}

enum class HistoryFileDeletionReason {
    NONE,
    BLANK,
    MALFORMED,
    UNSUPPORTED_URI,
    DIRECTORY,
    TREE_ROOT,
    UNRESOLVED,
    ACCESS_DENIED,
    UNKNOWN
}

data class HistoryFileDeletionOutcome(
    val key: String,
    val displayName: String,
    val status: HistoryFileDeletionStatus,
    val reason: HistoryFileDeletionReason = HistoryFileDeletionReason.NONE
) {
    val allowsRecordRemoval: Boolean
        get() = status == HistoryFileDeletionStatus.DELETED ||
            status == HistoryFileDeletionStatus.ALREADY_ABSENT
}

sealed interface HistoryDeletionTarget {
    val key: String
    val displayName: String

    data class RawFile(
        val file: File,
        override val key: String,
        override val displayName: String
    ) : HistoryDeletionTarget

    data class ContentDocument(
        val value: String,
        override val key: String,
        override val displayName: String,
        val expectedRawPath: String? = null,
        val trustedAssociation: Boolean = false
    ) : HistoryDeletionTarget
}

sealed interface HistoryDeletionTargetParseResult {
    data class Valid(val target: HistoryDeletionTarget) : HistoryDeletionTargetParseResult
    data class Rejected(
        val displayName: String,
        val reason: HistoryFileDeletionReason
    ) : HistoryDeletionTargetParseResult
}

object HistoryDeletionTargetParser {
    fun parse(storedTarget: String): HistoryDeletionTargetParseResult {
        val value = storedTarget.trim()
        if (value.isBlank()) {
            return HistoryDeletionTargetParseResult.Rejected("Unnamed file", HistoryFileDeletionReason.BLANK)
        }
        return when {
            value.startsWith("content://", ignoreCase = true) -> parseContentUri(value)
            value.startsWith("file:", ignoreCase = true) -> parseFileUri(value)
            URI_SCHEME.matches(value.substringBefore('/')) -> {
                HistoryDeletionTargetParseResult.Rejected(displayName(value), HistoryFileDeletionReason.UNSUPPORTED_URI)
            }
            else -> parseRawPath(value)
        }
    }

    fun deduplicationKey(storedTarget: String): String? {
        return (parse(storedTarget) as? HistoryDeletionTargetParseResult.Valid)?.target?.key
    }

    private fun parseContentUri(value: String): HistoryDeletionTargetParseResult {
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return HistoryDeletionTargetParseResult.Rejected(displayName(value), HistoryFileDeletionReason.MALFORMED)
        if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank() || uri.rawPath.isNullOrBlank()) {
            return HistoryDeletionTargetParseResult.Rejected(displayName(value), HistoryFileDeletionReason.MALFORMED)
        }
        val decodedPath = decode(uri.rawPath)
        if (decodedPath.endsWith('/')) {
            return HistoryDeletionTargetParseResult.Rejected(displayName(value), HistoryFileDeletionReason.DIRECTORY)
        }
        if (uri.authority.equals("media", ignoreCase = true) && decodedPath.substringAfterLast('/').toLongOrNull() == null) {
            return HistoryDeletionTargetParseResult.Rejected(displayName(value), HistoryFileDeletionReason.UNRESOLVED)
        }
        val treeId = decodedPath.substringAfter("/tree/", "").substringBefore("/document/").ifBlank { null }
        val documentId = decodedPath.substringAfter("/document/", "").ifBlank { null }
        if (treeId != null && (documentId == null || documentId == treeId)) {
            return HistoryDeletionTargetParseResult.Rejected(displayName(value), HistoryFileDeletionReason.TREE_ROOT)
        }
        return HistoryDeletionTargetParseResult.Valid(
            HistoryDeletionTarget.ContentDocument(
                value = value,
                key = documentId?.let { id ->
                    opaqueKey("content-document", "${uri.authority.lowercase(Locale.US)}:$id")
                } ?: opaqueKey("content", uri.normalize().toString()),
                displayName = displayName(decodedPath)
            )
        )
    }

    private fun parseFileUri(value: String): HistoryDeletionTargetParseResult {
        val uri = runCatching { URI(value) }.getOrNull()
            ?: return HistoryDeletionTargetParseResult.Rejected(displayName(value), HistoryFileDeletionReason.MALFORMED)
        if (!uri.scheme.equals("file", ignoreCase = true) ||
            (!uri.authority.isNullOrBlank() && !uri.authority.equals("localhost", ignoreCase = true))
        ) {
            return HistoryDeletionTargetParseResult.Rejected(displayName(value), HistoryFileDeletionReason.MALFORMED)
        }
        val path = decode(uri.rawPath.orEmpty())
        return parseRawPath(path)
    }

    private fun parseRawPath(value: String): HistoryDeletionTargetParseResult {
        if (value.isBlank() || value.endsWith('/') || value.endsWith('\\')) {
            return HistoryDeletionTargetParseResult.Rejected(displayName(value), HistoryFileDeletionReason.DIRECTORY)
        }
        val file = runCatching { File(value).absoluteFile.normalize() }.getOrNull()
            ?: return HistoryDeletionTargetParseResult.Rejected(displayName(value), HistoryFileDeletionReason.MALFORMED)
        if (file.name.isBlank() || file.parentFile == null) {
            return HistoryDeletionTargetParseResult.Rejected(displayName(value), HistoryFileDeletionReason.DIRECTORY)
        }
        val comparisonPath = runCatching { file.canonicalPath }.getOrDefault(file.path)
        return HistoryDeletionTargetParseResult.Valid(
            HistoryDeletionTarget.RawFile(
                file = file,
                key = opaqueKey("file", comparisonPath),
                displayName = file.name
            )
        )
    }

    private fun displayName(value: String): String {
        return decode(value.substringBefore('?'))
            .trimEnd('/', '\\')
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast(':')
            .ifBlank { "Unnamed file" }
    }

    private fun decode(value: String): String {
        val plusPreservingValue = value.replace("+", "%2B")
        return runCatching {
            URLDecoder.decode(plusPreservingValue, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    private fun opaqueKey(kind: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte) }
        return "$kind:$digest"
    }

    private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
}

object HistoryDeletionScope {
    fun isWithin(file: File, root: File): Boolean {
        val canonicalFile = runCatching { file.canonicalFile }.getOrElse { return false }
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { return false }
        var current: File? = canonicalFile
        while (current != null) {
            if (current == canonicalRoot) return canonicalFile != canonicalRoot
            current = current.parentFile
        }
        return false
    }
}

interface HistoryFileDeletionGateway {
    fun validate(target: HistoryDeletionTarget): HistoryFileDeletionOutcome
    fun delete(target: HistoryDeletionTarget): HistoryFileDeletionOutcome
    fun referenceKeys(target: HistoryDeletionTarget): Set<String> = naturalReferenceKeys(target)
}

internal fun naturalReferenceKeys(target: HistoryDeletionTarget): Set<String> {
    return buildSet {
        add(target.key)
        val storedTarget = when (target) {
            is HistoryDeletionTarget.RawFile -> target.file.path
            is HistoryDeletionTarget.ContentDocument -> target.value
        }
        HistoryDeletionTargetParser.deduplicationKey(storedTarget)?.let(::add)
    }
}

fun HistoryFileDeletionGateway.referencesSameFile(first: String, second: String): Boolean {
    val firstTarget = (HistoryDeletionTargetParser.parse(first) as? HistoryDeletionTargetParseResult.Valid)?.target
        ?: return false
    val secondTarget = (HistoryDeletionTargetParser.parse(second) as? HistoryDeletionTargetParseResult.Valid)?.target
        ?: return false
    val secondKeys = referenceKeys(secondTarget)
    return referenceKeys(firstTarget).any(secondKeys::contains)
}

data class HistoryDeletionValidation(
    val records: List<HistoryDeletionRecord>,
    val recordTargetKeys: Map<Long, Set<String>>,
    val targets: Map<String, HistoryDeletionTarget>,
    val outcomes: Map<String, HistoryFileDeletionOutcome>
) {
    val filesReady: Int
        get() = outcomes.values.count { it.status == HistoryFileDeletionStatus.READY }

    fun revalidateRecordSnapshots(currentStoredTargets: Map<Long, List<String>>): HistoryDeletionValidation {
        val unchangedRecordIds = records
            .filter { record -> currentStoredTargets[record.id] == record.recordStoredTargetSnapshot }
            .mapTo(linkedSetOf(), HistoryDeletionRecord::id)
        val changedTargetKeys = recordTargetKeys
            .filterKeys { recordId -> recordId !in unchangedRecordIds }
            .values
            .flatten()
            .toSet()
        val activeTargetKeys = recordTargetKeys
            .filterKeys { recordId -> recordId in unchangedRecordIds }
            .values
            .flatten()
            .toSet() - changedTargetKeys
        return copy(
            recordTargetKeys = recordTargetKeys.filterKeys { recordId -> recordId in unchangedRecordIds },
            targets = targets.filterKeys { key -> key in activeTargetKeys },
            outcomes = outcomes.mapValues { (key, outcome) ->
                if (key in activeTargetKeys) {
                    outcome
                } else {
                    outcome.copy(
                        status = HistoryFileDeletionStatus.SKIPPED,
                        reason = HistoryFileDeletionReason.UNKNOWN
                    )
                }
            }
        )
    }

    fun excludeTargetsReferencedBy(retainedStoredTargets: Sequence<String>): HistoryDeletionValidation {
        val retainedKeys = retainedStoredTargets
            .mapNotNull(HistoryDeletionTargetParser::deduplicationKey)
            .toSet()
        if (retainedKeys.isEmpty()) return this
        val filteredRecordTargetKeys = recordTargetKeys.mapValues { (_, keys) -> keys - retainedKeys }
        val activeTargetKeys = filteredRecordTargetKeys.values.flatten().toSet()
        return copy(
            recordTargetKeys = filteredRecordTargetKeys,
            targets = targets.filterKeys(activeTargetKeys::contains),
            outcomes = outcomes.filterKeys(activeTargetKeys::contains)
        )
    }
}

data class HistoryDeletionSummary(
    val recordsRequested: Int,
    val recordsRemoved: Int,
    val removableRecordIds: Set<Long>,
    val outcomes: List<HistoryFileDeletionOutcome>
) {
    val filesDeleted: Int get() = outcomes.count { it.status == HistoryFileDeletionStatus.DELETED }
    val filesAlreadyAbsent: Int get() = outcomes.count { it.status == HistoryFileDeletionStatus.ALREADY_ABSENT }
    val filesSkipped: Int get() = outcomes.count { it.status == HistoryFileDeletionStatus.SKIPPED }
    val filesPermissionDenied: Int get() = outcomes.count { it.status == HistoryFileDeletionStatus.PERMISSION_REQUIRED }
    val filesFailed: Int get() = outcomes.count { it.status == HistoryFileDeletionStatus.FAILED }
    val hasFileFailures: Boolean get() = filesSkipped + filesPermissionDenied + filesFailed > 0
    val problemDisplayNames: List<String>
        get() = outcomes
            .filter { outcome ->
                outcome.status == HistoryFileDeletionStatus.SKIPPED ||
                    outcome.status == HistoryFileDeletionStatus.PERMISSION_REQUIRED ||
                    outcome.status == HistoryFileDeletionStatus.FAILED
            }
            .map(HistoryFileDeletionOutcome::displayName)
            .distinct()
}

class HistoryFileDeletionEngine(
    private val gateway: HistoryFileDeletionGateway
) {
    fun excludeTargetsReferencedBy(
        validation: HistoryDeletionValidation,
        retainedStoredTargets: Sequence<String>
    ): HistoryDeletionValidation {
        val retainedReferenceKeys = retainedStoredTargets
            .mapNotNull { storedTarget ->
                (HistoryDeletionTargetParser.parse(storedTarget) as? HistoryDeletionTargetParseResult.Valid)?.target
            }
            .flatMap { target -> gateway.referenceKeys(target).asSequence() }
            .toSet()
        if (retainedReferenceKeys.isEmpty()) return validation

        val protectedTargetKeys = validation.targets
            .filterValues { target -> gateway.referenceKeys(target).any(retainedReferenceKeys::contains) }
            .keys
        if (protectedTargetKeys.isEmpty()) return validation

        val filteredRecordTargetKeys = validation.recordTargetKeys.mapValues { (_, keys) ->
            keys - protectedTargetKeys
        }
        val activeTargetKeys = filteredRecordTargetKeys.values.flatten().toSet()
        return validation.copy(
            recordTargetKeys = filteredRecordTargetKeys,
            targets = validation.targets.filterKeys(activeTargetKeys::contains),
            outcomes = validation.outcomes.filterKeys(activeTargetKeys::contains)
        )
    }

    fun validate(records: List<HistoryDeletionRecord>): HistoryDeletionValidation {
        val targets = linkedMapOf<String, HistoryDeletionTarget>()
        val outcomes = linkedMapOf<String, HistoryFileDeletionOutcome>()
        val recordTargetKeys = linkedMapOf<Long, MutableSet<String>>()
        val canonicalKeyByReferenceKey = mutableMapOf<String, String>()

        records.forEach { record ->
            val keys = recordTargetKeys.getOrPut(record.id) { linkedSetOf() }
            record.storedTargets.filter(String::isNotBlank).forEachIndexed { index, storedTarget ->
                when (val parsed = HistoryDeletionTargetParser.parse(storedTarget)) {
                    is HistoryDeletionTargetParseResult.Valid -> {
                        val target = (parsed.target as? HistoryDeletionTarget.ContentDocument)?.let { contentTarget ->
                            contentTarget.copy(
                                trustedAssociation = storedTarget in record.trustedDocumentTargets
                            )
                        } ?: parsed.target
                        val referenceKeys = gateway.referenceKeys(target)
                        val canonicalKey = referenceKeys
                            .asSequence()
                            .mapNotNull(canonicalKeyByReferenceKey::get)
                            .firstOrNull()
                            ?: target.key
                        val existingTarget = targets[canonicalKey]
                        keys += canonicalKey
                        if (existingTarget == null) {
                            targets[canonicalKey] = target
                        } else if (
                            target is HistoryDeletionTarget.ContentDocument &&
                            target.trustedAssociation &&
                            (existingTarget !is HistoryDeletionTarget.ContentDocument ||
                                !existingTarget.trustedAssociation)
                        ) {
                            val expectedRawPath = target.expectedRawPath
                                ?: (existingTarget as? HistoryDeletionTarget.RawFile)?.file?.let { file ->
                                    runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                                }
                            targets[canonicalKey] = target.copy(
                                key = canonicalKey,
                                expectedRawPath = expectedRawPath
                            )
                        } else if (
                            target is HistoryDeletionTarget.RawFile &&
                            existingTarget is HistoryDeletionTarget.ContentDocument
                        ) {
                            if (existingTarget.trustedAssociation) {
                                val rawPath = runCatching { target.file.canonicalPath }
                                    .getOrDefault(target.file.absolutePath)
                                targets[canonicalKey] = existingTarget.copy(
                                    expectedRawPath = existingTarget.expectedRawPath ?: rawPath
                                )
                            } else {
                                targets[canonicalKey] = target.copy(key = canonicalKey)
                            }
                        }
                        referenceKeys.forEach { referenceKey ->
                            canonicalKeyByReferenceKey.putIfAbsent(referenceKey, canonicalKey)
                        }
                    }
                    is HistoryDeletionTargetParseResult.Rejected -> {
                        val key = "rejected:${record.id}:$index"
                        keys += key
                        outcomes[key] = HistoryFileDeletionOutcome(
                            key = key,
                            displayName = parsed.displayName,
                            status = HistoryFileDeletionStatus.SKIPPED,
                            reason = parsed.reason
                        )
                    }
                }
            }
        }

        targets.forEach { (key, target) ->
            outcomes[key] = gateway.validate(target)
        }

        return HistoryDeletionValidation(
            records = records,
            recordTargetKeys = recordTargetKeys.mapValues { it.value.toSet() },
            targets = targets,
            outcomes = outcomes
        )
    }

    fun execute(validation: HistoryDeletionValidation): HistoryDeletionSummary {
        val outcomes = validation.outcomes.toMutableMap()
        validation.targets.forEach { (key, target) ->
            if (outcomes[key]?.status == HistoryFileDeletionStatus.READY) {
                val validatedDisplayName = outcomes[key]?.displayName
                val deletionOutcome = gateway.delete(target)
                outcomes[key] = if (validatedDisplayName.isNullOrBlank()) {
                    deletionOutcome
                } else {
                    deletionOutcome.copy(displayName = validatedDisplayName)
                }
            }
        }
        val removableIds = validation.recordTargetKeys
            .filterValues { keys -> keys.all { outcomes[it]?.allowsRecordRemoval == true } }
            .keys
        return HistoryDeletionSummary(
            recordsRequested = validation.records.size,
            recordsRemoved = 0,
            removableRecordIds = removableIds,
            outcomes = outcomes.values.toList()
        )
    }
}

class RawHistoryFileDeletionGateway : HistoryFileDeletionGateway {
    override fun validate(target: HistoryDeletionTarget): HistoryFileDeletionOutcome {
        if (target !is HistoryDeletionTarget.RawFile) return unresolved(target)
        val file = target.file
        return runCatching {
            when {
                file.exists() && file.isDirectory -> outcome(target, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.DIRECTORY)
                file.exists() && !file.isFile -> outcome(target, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.UNRESOLVED)
                file.exists() && file.parentFile?.canWrite() != true -> {
                    outcome(target, HistoryFileDeletionStatus.PERMISSION_REQUIRED, HistoryFileDeletionReason.ACCESS_DENIED)
                }
                file.exists() -> outcome(target, HistoryFileDeletionStatus.READY)
                file.parentFile?.let { it.exists() && it.isDirectory && it.canRead() } == true -> {
                    outcome(target, HistoryFileDeletionStatus.ALREADY_ABSENT)
                }
                file.parentFile?.exists() == true -> {
                    outcome(target, HistoryFileDeletionStatus.PERMISSION_REQUIRED, HistoryFileDeletionReason.ACCESS_DENIED)
                }
                else -> outcome(target, HistoryFileDeletionStatus.FAILED, HistoryFileDeletionReason.UNKNOWN)
            }
        }.getOrElse { error -> classifyFailure(target, error) }
    }

    override fun delete(target: HistoryDeletionTarget): HistoryFileDeletionOutcome {
        if (target !is HistoryDeletionTarget.RawFile) return unresolved(target)
        val revalidated = validate(target)
        if (revalidated.status != HistoryFileDeletionStatus.READY) return revalidated
        return runCatching {
            when {
                target.file.delete() -> outcome(target, HistoryFileDeletionStatus.DELETED)
                !target.file.exists() && target.file.parentFile?.canRead() == true -> {
                    outcome(target, HistoryFileDeletionStatus.ALREADY_ABSENT)
                }
                target.file.parentFile?.canWrite() == false -> {
                    outcome(target, HistoryFileDeletionStatus.PERMISSION_REQUIRED, HistoryFileDeletionReason.ACCESS_DENIED)
                }
                else -> outcome(target, HistoryFileDeletionStatus.FAILED, HistoryFileDeletionReason.UNKNOWN)
            }
        }.getOrElse { error -> classifyFailure(target, error) }
    }

    private fun unresolved(target: HistoryDeletionTarget) =
        outcome(target, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.UNRESOLVED)

    private fun classifyFailure(target: HistoryDeletionTarget, error: Throwable): HistoryFileDeletionOutcome {
        return if (error is SecurityException) {
            outcome(target, HistoryFileDeletionStatus.PERMISSION_REQUIRED, HistoryFileDeletionReason.ACCESS_DENIED)
        } else {
            outcome(target, HistoryFileDeletionStatus.FAILED, HistoryFileDeletionReason.UNKNOWN)
        }
    }
}

class AndroidHistoryFileDeletionGateway(
    context: Context
) : HistoryFileDeletionGateway {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val rawGateway = RawHistoryFileDeletionGateway()

    override fun validate(target: HistoryDeletionTarget): HistoryFileDeletionOutcome {
        return when (target) {
            is HistoryDeletionTarget.RawFile -> validateRawFile(target)
            is HistoryDeletionTarget.ContentDocument -> validateContentDocument(target)
        }
    }

    override fun delete(target: HistoryDeletionTarget): HistoryFileDeletionOutcome {
        return when (target) {
            is HistoryDeletionTarget.RawFile -> deleteRawFile(target)
            is HistoryDeletionTarget.ContentDocument -> deleteContentDocument(target)
        }
    }

    override fun referenceKeys(target: HistoryDeletionTarget): Set<String> {
        return buildSet {
            addAll(naturalReferenceKeys(target))
            if (target is HistoryDeletionTarget.ContentDocument) {
                contentRawPath(target)?.let { rawPath ->
                    HistoryDeletionTargetParser.deduplicationKey(rawPath)?.let(::add)
                }
            }
        }
    }

    private fun validateRawFile(target: HistoryDeletionTarget.RawFile): HistoryFileDeletionOutcome {
        val trustedRawTarget = isTrustedRawTarget(target.file)
        val direct = if (trustedRawTarget) {
            rawGateway.validate(target)
        } else {
            outcome(target, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.ACCESS_DENIED)
        }
        if (
            trustedRawTarget &&
            direct.status == HistoryFileDeletionStatus.FAILED &&
            isConclusivelyAbsentWithinTrustedRoot(target.file)
        ) {
            return outcome(target, HistoryFileDeletionStatus.ALREADY_ABSENT)
        }
        if (trustedRawTarget) {
            if (direct.status == HistoryFileDeletionStatus.READY ||
                direct.status == HistoryFileDeletionStatus.ALREADY_ABSENT ||
                direct.status == HistoryFileDeletionStatus.SKIPPED
            ) {
                return direct
            }
        }

        var bestFailure = direct
        rawContentAlternatives(target).forEach { contentTarget ->
            val result = validateContentDocument(contentTarget)
            when (result.status) {
                HistoryFileDeletionStatus.READY,
                HistoryFileDeletionStatus.ALREADY_ABSENT -> return result
                HistoryFileDeletionStatus.PERMISSION_REQUIRED -> bestFailure = result
                else -> Unit
            }
        }
        return bestFailure
    }

    private fun validateContentDocument(target: HistoryDeletionTarget.ContentDocument): HistoryFileDeletionOutcome {
        val uri = runCatching { Uri.parse(target.value) }.getOrNull()
            ?: return outcome(target, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.MALFORMED)
        if (isTreeRoot(uri)) {
            return outcome(target, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.TREE_ROOT)
        }
        if (uri.authority != MediaStore.AUTHORITY && !DocumentsContract.isDocumentUri(appContext, uri)) {
            return outcome(target, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.UNSUPPORTED_URI)
        }
        if (uri.authority == MediaStore.AUTHORITY && uri.lastPathSegment?.toLongOrNull() == null) {
            return outcome(target, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.UNRESOLVED)
        }
        val projection = mutableListOf(
            OpenableColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE
        ).apply {
            if (uri.authority == MediaStore.AUTHORITY) {
                add(MediaStore.MediaColumns.DATA)
            }
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                add(DocumentsContract.Document.COLUMN_FLAGS)
            }
        }.toTypedArray()
        return try {
            resolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor -> validateContentCursor(target, uri, cursor) }
                ?: outcome(target, HistoryFileDeletionStatus.FAILED, HistoryFileDeletionReason.UNRESOLVED)
        } catch (_: FileNotFoundException) {
            inconclusiveContentAbsenceOutcome(target, canReadUri(uri))
        } catch (_: SecurityException) {
            outcome(target, HistoryFileDeletionStatus.PERMISSION_REQUIRED, HistoryFileDeletionReason.ACCESS_DENIED)
        } catch (_: Exception) {
            outcome(target, HistoryFileDeletionStatus.FAILED, HistoryFileDeletionReason.UNKNOWN)
        }
    }

    private fun validateContentCursor(
        target: HistoryDeletionTarget.ContentDocument,
        uri: Uri,
        cursor: Cursor
    ): HistoryFileDeletionOutcome {
        if (!cursor.moveToFirst()) {
            return inconclusiveContentAbsenceOutcome(target, canReadUri(uri))
        }
        val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        val mimeType = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else resolver.getType(uri).orEmpty()
        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val providerDisplayName = if (displayNameIndex >= 0) {
            cursor.getString(displayNameIndex).orEmpty().ifBlank { target.displayName }
        } else {
            target.displayName
        }
        val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
        val supportsDelete = if (flagsIndex >= 0 && !cursor.isNull(flagsIndex)) {
            cursor.getInt(flagsIndex) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
        } else {
            null
        }
        val mediaPathIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
        val mediaPath = if (mediaPathIndex >= 0) cursor.getString(mediaPathIndex).orEmpty() else ""
        return when {
            mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> {
                outcome(target, providerDisplayName, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.DIRECTORY)
            }
            mimeType.isBlank() -> {
                outcome(target, providerDisplayName, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.UNRESOLVED)
            }
            supportsDelete == false -> {
                outcome(target, providerDisplayName, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.UNRESOLVED)
            }
            uri.authority == MediaStore.AUTHORITY &&
                !isAllowedMediaStoreTarget(target, mediaPath) -> {
                outcome(target, providerDisplayName, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.ACCESS_DENIED)
            }
            uri.authority != MediaStore.AUTHORITY && !isAllowedDocumentTarget(target) -> {
                outcome(target, providerDisplayName, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.ACCESS_DENIED)
            }
            uri.authority != MediaStore.AUTHORITY && !canWriteUri(uri) -> {
                outcome(target, providerDisplayName, HistoryFileDeletionStatus.PERMISSION_REQUIRED, HistoryFileDeletionReason.ACCESS_DENIED)
            }
            else -> outcome(target, providerDisplayName, HistoryFileDeletionStatus.READY)
        }
    }

    private fun deleteRawFile(target: HistoryDeletionTarget.RawFile): HistoryFileDeletionOutcome {
        val trustedRawTarget = isTrustedRawTarget(target.file)
        val direct = if (trustedRawTarget) {
            rawGateway.delete(target)
        } else {
            outcome(target, HistoryFileDeletionStatus.SKIPPED, HistoryFileDeletionReason.ACCESS_DENIED)
        }
        if (trustedRawTarget) {
            if (direct.status == HistoryFileDeletionStatus.DELETED) {
                FileUtil.cleanupDeletedRawFileArtifacts(target.file.absolutePath)
                return direct
            }
            if (direct.status == HistoryFileDeletionStatus.ALREADY_ABSENT ||
                direct.status == HistoryFileDeletionStatus.SKIPPED
            ) {
                return direct
            }
        }

        var bestFailure = direct
        rawContentAlternatives(target).forEach { contentTarget ->
            val result = deleteContentDocument(contentTarget)
            when (result.status) {
                HistoryFileDeletionStatus.DELETED -> return result
                HistoryFileDeletionStatus.ALREADY_ABSENT -> return result
                HistoryFileDeletionStatus.PERMISSION_REQUIRED -> bestFailure = result
                else -> Unit
            }
        }
        return bestFailure
    }

    private fun rawContentAlternatives(
        target: HistoryDeletionTarget.RawFile
    ): List<HistoryDeletionTarget.ContentDocument> {
        val safUri = runCatching {
            FileUtil.buildDocumentUriForPath(target.file.absolutePath)
        }.getOrNull()
        val mediaUri = runCatching { findMediaStoreUri(target.file) }.getOrNull()
        return listOfNotNull(safUri, mediaUri)
            .distinctBy(Uri::toString)
            .map { uri ->
                HistoryDeletionTarget.ContentDocument(
                    value = uri.toString(),
                    key = target.key,
                    displayName = target.displayName,
                    expectedRawPath = runCatching { target.file.canonicalPath }
                        .getOrDefault(target.file.absolutePath)
                )
            }
    }

    private fun deleteContentDocument(target: HistoryDeletionTarget.ContentDocument): HistoryFileDeletionOutcome {
        val uri = Uri.parse(target.value)
        val revalidatedBeforeDelete = validateContentDocument(target)
        if (revalidatedBeforeDelete.status != HistoryFileDeletionStatus.READY) return revalidatedBeforeDelete
        return try {
            val deleted = if (DocumentsContract.isDocumentUri(appContext, uri)) {
                DocumentsContract.deleteDocument(resolver, uri)
            } else {
                resolver.delete(uri, null, null) > 0
            }
            if (deleted) {
                target.expectedRawPath?.let(FileUtil::cleanupDeletedRawFileArtifacts)
                outcome(target, HistoryFileDeletionStatus.DELETED)
            } else {
                val revalidated = validateContentDocument(target)
                when (revalidated.status) {
                    HistoryFileDeletionStatus.ALREADY_ABSENT -> revalidated
                    HistoryFileDeletionStatus.PERMISSION_REQUIRED -> revalidated
                    else -> outcome(target, HistoryFileDeletionStatus.FAILED, HistoryFileDeletionReason.UNKNOWN)
                }
            }
        } catch (_: SecurityException) {
            outcome(target, HistoryFileDeletionStatus.PERMISSION_REQUIRED, HistoryFileDeletionReason.ACCESS_DENIED)
        } catch (_: Exception) {
            outcome(target, HistoryFileDeletionStatus.FAILED, HistoryFileDeletionReason.UNKNOWN)
        }
    }

    private fun isTreeRoot(uri: Uri): Boolean {
        if (!DocumentsContract.isTreeUri(uri)) return false
        if (!DocumentsContract.isDocumentUri(appContext, uri)) return true
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        return treeId.isNullOrBlank() || documentId.isNullOrBlank() || treeId == documentId
    }

    private fun canWriteUri(uri: Uri): Boolean {
        val directGrant = appContext.checkUriPermission(
            uri,
            android.os.Process.myPid(),
            android.os.Process.myUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
        return directGrant || resolver.persistedUriPermissions.any { permission ->
            permission.isWritePermission && persistedGrantContains(permission.uri, uri)
        }
    }

    private fun canReadUri(uri: Uri): Boolean {
        val directGrant = appContext.checkUriPermission(
            uri,
            android.os.Process.myPid(),
            android.os.Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
        return directGrant || resolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && persistedGrantContains(permission.uri, uri)
        }
    }

    private fun persistedGrantContains(grantUri: Uri, targetUri: Uri): Boolean {
        if (grantUri == targetUri) return true
        if (grantUri.authority != targetUri.authority || !DocumentsContract.isTreeUri(grantUri)) return false
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(grantUri) }.getOrNull()
            ?.trimEnd('/')
            ?.takeIf(String::isNotBlank)
            ?: return false
        val documentId = runCatching { DocumentsContract.getDocumentId(targetUri) }.getOrNull()
            ?.trimEnd('/')
            ?.takeIf(String::isNotBlank)
            ?: return false
        return documentId == treeId || documentId.startsWith("$treeId/")
    }

    private fun findMediaStoreUri(file: File): Uri? {
        val collection = MediaStore.Files.getContentUri("external")
        return resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(file.absolutePath),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null
            else android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
    }

    private fun contentRawPath(target: HistoryDeletionTarget.ContentDocument): String? {
        target.expectedRawPath?.takeIf(String::isNotBlank)?.let { return it }
        val uri = runCatching { Uri.parse(target.value) }.getOrNull() ?: return null
        rawPathFromDocumentUri(uri)?.let { return it }
        if (uri.authority != MediaStore.AUTHORITY) return null
        return runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
            }
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun rawPathFromDocumentUri(uri: Uri): String? {
        if (!DocumentsContract.isDocumentUri(appContext, uri)) return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return null
        if (documentId.startsWith("raw:", ignoreCase = true)) {
            if (!uri.authority.equals(DOWNLOADS_DOCUMENTS_AUTHORITY, ignoreCase = true) &&
                !uri.authority.equals(EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY, ignoreCase = true)
            ) {
                return null
            }
            return documentId.substringAfter(':').takeIf(String::isNotBlank)
        }
        if (!uri.authority.equals(EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY, ignoreCase = true)) return null
        val volumeId = documentId.substringBefore(':', missingDelimiterValue = "")
        val relativePath = documentId.substringAfter(':', missingDelimiterValue = "")
        if (volumeId.isBlank() || relativePath.isBlank()) return null
        val root = when {
            volumeId.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
            volumeId.equals("home", ignoreCase = true) ->
                File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOCUMENTS)
            else -> File("/storage", volumeId)
        }
        return File(root, relativePath).absolutePath
    }

    private fun isTrustedRawTarget(file: File): Boolean {
        return trustedRawRoot(file) != null
    }

    private fun trustedRawRoot(file: File): File? {
        val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
        val configuredRoots = listOf(
            FileUtil.getDefaultApplicationPath(),
            preferences.getString("music_path", FileUtil.getDefaultAudioPath()).orEmpty(),
            preferences.getString("video_path", FileUtil.getDefaultVideoPath()).orEmpty(),
            preferences.getString("command_path", FileUtil.getDefaultCommandPath()).orEmpty()
        ).mapNotNull(::rawRootFile)
        return configuredRoots.firstOrNull { root -> HistoryDeletionScope.isWithin(file, root) }
    }

    private fun rawRootFile(value: String): File? {
        val path = when {
            value.isBlank() || value.startsWith("content://", ignoreCase = true) -> return null
            value.startsWith("file://", ignoreCase = true) -> Uri.parse(value).path.orEmpty()
            else -> value
        }
        return path.takeIf(String::isNotBlank)?.let(::File)
    }

    private fun isConclusivelyAbsentWithinTrustedRoot(file: File): Boolean {
        val root = trustedRawRoot(file) ?: return false
        var ancestor = file.parentFile
        while (ancestor != null && (ancestor == root || HistoryDeletionScope.isWithin(ancestor, root))) {
            if (ancestor.exists()) return ancestor.isDirectory && ancestor.canRead()
            if (ancestor == root) return false
            ancestor = ancestor.parentFile
        }
        return false
    }

    private fun isAllowedMediaStoreTarget(
        target: HistoryDeletionTarget.ContentDocument,
        mediaPath: String
    ): Boolean {
        if (mediaPath.isBlank()) return false
        val expectedPath = target.expectedRawPath
        if (expectedPath == null) return isTrustedRawTarget(File(mediaPath))
        val actualCanonical = runCatching { File(mediaPath).canonicalPath }.getOrDefault(mediaPath)
        val expectedCanonical = runCatching { File(expectedPath).canonicalPath }.getOrDefault(expectedPath)
        return actualCanonical == expectedCanonical && isTrustedRawTarget(File(expectedCanonical))
    }

    private fun isAllowedDocumentTarget(target: HistoryDeletionTarget.ContentDocument): Boolean {
        if (target.trustedAssociation) return true
        val uri = runCatching { Uri.parse(target.value) }.getOrNull()
        if (uri != null && hasExactPersistedWriteGrant(uri)) return true
        if (uri != null && configuredSafDestinationContains(uri)) return true
        val rawPath = contentRawPath(target) ?: return false
        return isTrustedRawTarget(File(rawPath))
    }

    private fun hasExactPersistedWriteGrant(uri: Uri): Boolean {
        return resolver.persistedUriPermissions.any { permission ->
            permission.isWritePermission && permission.uri == uri
        }
    }

    private fun configuredSafDestinationContains(targetUri: Uri): Boolean {
        val preferences = PreferenceManager.getDefaultSharedPreferences(appContext)
        val configuredTreeUris = listOf("music_path", "video_path", "command_path")
            .mapNotNull { key -> preferences.getString(key, null) }
            .filter { value -> value.startsWith("content://", ignoreCase = true) }
            .mapNotNull { value -> runCatching { Uri.parse(value) }.getOrNull() }
        return configuredTreeUris.any { configuredUri ->
            DocumentsContract.isTreeUri(configuredUri) &&
                persistedGrantContains(configuredUri, targetUri) &&
                resolver.persistedUriPermissions.any { permission ->
                    permission.isWritePermission && permission.uri == configuredUri
                }
        }
    }

    private companion object {
        const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
        const val DOWNLOADS_DOCUMENTS_AUTHORITY = "com.android.providers.downloads.documents"
    }
}

internal fun inconclusiveContentAbsenceOutcome(
    target: HistoryDeletionTarget,
    hasReadAccess: Boolean
): HistoryFileDeletionOutcome {
    return if (hasReadAccess) {
        outcome(target, HistoryFileDeletionStatus.FAILED, HistoryFileDeletionReason.UNKNOWN)
    } else {
        outcome(
            target,
            HistoryFileDeletionStatus.PERMISSION_REQUIRED,
            HistoryFileDeletionReason.ACCESS_DENIED
        )
    }
}

private fun outcome(
    target: HistoryDeletionTarget,
    status: HistoryFileDeletionStatus,
    reason: HistoryFileDeletionReason = HistoryFileDeletionReason.NONE
): HistoryFileDeletionOutcome {
    return HistoryFileDeletionOutcome(target.key, target.displayName, status, reason)
}

private fun outcome(
    target: HistoryDeletionTarget,
    displayName: String,
    status: HistoryFileDeletionStatus,
    reason: HistoryFileDeletionReason = HistoryFileDeletionReason.NONE
): HistoryFileDeletionOutcome {
    return HistoryFileDeletionOutcome(target.key, displayName, status, reason)
}
