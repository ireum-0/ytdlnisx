package com.ireum.ytdl.database

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.google.gson.Gson
import com.ireum.ytdl.database.models.AutomaticKeywordRuleTypes
import com.ireum.ytdl.database.models.AutomaticKeywordSyncStatus
import com.ireum.ytdl.database.models.CommandTemplate
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.HistoryKeywordAssignmentSources
import com.ireum.ytdl.database.models.HistoryReplacementBarrier
import com.ireum.ytdl.database.models.PlaylistGroupMember
import com.ireum.ytdl.database.models.PlaylistItemCrossRef
import com.ireum.ytdl.database.models.YoutuberGroupMember
import com.ireum.ytdl.database.models.YoutuberGroupRelation
import com.ireum.ytdl.database.models.KeywordGroupMember
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.TemplateShortcut
import com.ireum.ytdl.database.models.SearchHistoryItem
import com.ireum.ytdl.database.models.CookieItem
import com.ireum.ytdl.database.models.RestorePlan
import com.ireum.ytdl.database.models.WorkManagerHandoffCarrier
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.RestoreRemapResult
import com.ireum.ytdl.database.viewmodel.HistoryRedownloadRestorePolicy
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadIssueStage
import com.ireum.ytdl.util.storage.HistoryReferenceMutationCoordinator
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.ireum.ytdl.util.BackupSettingsUtil
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.AutomaticKeywordObservationCoverage
import com.ireum.ytdl.database.repository.AutomaticKeywordRuleScheduler
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryKeywordAssignmentRepository
import com.ireum.ytdl.database.repository.ObserveSourcesRepository
import com.ireum.ytdl.database.models.observeSources.ObservationPurposes
import com.ireum.ytdl.util.AutomaticKeywordNormalizer
import com.ireum.ytdl.work.CleanupScheduleCoordinator
import com.ireum.ytdl.work.HardSubScanWorker
import com.ireum.ytdl.work.DownloadWorker
import com.ireum.ytdl.work.DownloadWorkerExecutionOwners
import com.ireum.ytdl.work.LowQualityRedownloadLedger
import com.ireum.ytdl.work.MoveCacheFilesWorker
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.HistoryDateFetchNotification
import com.ireum.ytdl.util.LowQualityRedownloadNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.TimeUnit

enum class RestorePhase {
    PREPARED,
    QUIESCED,
    FILES_READY,
    APPLYING,
    DATA_COMMITTED,
    RECONCILING,
    COMPLETE,
}

sealed interface RestoreOutcome {
    data class Completed(val operationId: String) : RestoreOutcome
    data class RejectedBeforeOwnership(val reason: String) : RestoreOutcome
    data class RecoveryPending(
        val operationId: String?,
        val phase: RestorePhase?,
        val reason: String,
    ) : RestoreOutcome
    data class CommittedReconciliationPending(
        val operationId: String,
        val reason: String,
    ) : RestoreOutcome
}

class RestoreRecoveryBlockedException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal data class RestoreJournal(
    val operationId: String,
    val phase: String,
    val planDigest: String,
    val createdAt: Long,
    val lastError: String = "",
    val preservedCachePath: String = "",
    val preservedCachePathPresent: Boolean = false,
    val cleanupCadence: String = "",
    val membershipWaitingNotificationIds: List<Long> = emptyList(),
    val lowQualityOperationIds: List<String> = emptyList(),
    val historyDateFetchWasActive: Boolean = false,
    val historyDateFetchOperationId: String? = null,
    val historyDateFetchOperationIds: List<String> = emptyList(),
    val supersededDownloadNotificationIds: List<Long> = emptyList(),
    val supersededObserveSourceNotificationIds: List<Long> = emptyList(),
    val sidecarsCaptured: Boolean = false,
)

internal data class RestorePointer(
    val operationId: String,
    val planDigest: String,
)

internal data class RestoreRecord(
    val journal: RestoreJournal,
    val plan: RestorePlan,
    val operationDirectory: File,
)

private data class PersistedRestorePlan(
    val appMarker: String,
    val formatVersion: Int?,
    val compatibility: com.ireum.ytdl.database.models.BackupCompatibility,
    val capabilities: Set<String>,
    val data: com.ireum.ytdl.database.models.RestoreAppDataItem,
)

/** File-backed durable carrier for the one active destructive Reset. */
internal object RestoreOperationStore {
    const val ROOT_DIRECTORY = "restore-transactions"
    private const val ACTIVE_FILE = "active.json"
    private const val ACTIVE_LOCK_FILE = "active.lock"
    private const val JOURNAL_FILE = "journal.json"
    private const val PLAN_FILE = "plan.json"
    private val gson = Gson()

    fun root(context: Context): File = File(context.noBackupFilesDir, ROOT_DIRECTORY)

    fun operationDirectory(context: Context, operationId: String): File =
        File(root(context), operationId)

    fun createOperation(context: Context, operationId: String): File {
        val directory = operationDirectory(context, operationId)
        check(directory.parentFile?.canonicalFile == root(context).canonicalFile)
        check(directory.mkdirs() || directory.isDirectory)
        val payload = File(directory, "payload")
        check(payload.mkdirs() || payload.isDirectory)
        return directory
    }

    fun writePlan(directory: File, plan: RestorePlan): String {
        val bytes = gson.toJson(
            PersistedRestorePlan(
                appMarker = plan.appMarker,
                formatVersion = plan.formatVersion,
                compatibility = plan.compatibility,
                capabilities = plan.capabilities,
                data = plan.data,
            ),
        ).toByteArray(Charsets.UTF_8)
        writeAtomic(File(directory, PLAN_FILE), bytes)
        return digest(bytes)
    }

    fun readPlan(directory: File): Pair<RestorePlan, String> {
        val bytes = readAtomic(File(directory, PLAN_FILE))
        val persisted = gson.fromJson(
            bytes.toString(Charsets.UTF_8),
            PersistedRestorePlan::class.java,
        ) ?: throw IllegalArgumentException("restore plan is null")
        require(persisted.capabilities.none { it.isBlank() }) { "restore plan capability is blank" }
        val validated = BackupRestoreParser.fromTyped(persisted.data)
        val persistedPlan = RestorePlan(
            appMarker = persisted.appMarker,
            formatVersion = persisted.formatVersion,
            compatibility = persisted.compatibility,
            capabilities = persisted.capabilities,
            data = validated.data,
        )
        return BackupRestoreParser.validatePlan(persistedPlan) to digest(bytes)
    }

    fun writeJournal(directory: File, journal: RestoreJournal) {
        writeAtomic(File(directory, JOURNAL_FILE), gson.toJson(journal).toByteArray(Charsets.UTF_8))
    }

    fun readJournal(directory: File): RestoreJournal = gson.fromJson(
        readAtomic(File(directory, JOURNAL_FILE)).toString(Charsets.UTF_8),
        RestoreJournal::class.java,
    )

    fun publishActive(context: Context, pointer: RestorePointer) {
        val directory = operationDirectory(context, pointer.operationId)
        check(directory.isDirectory) { "restore operation directory is missing" }
        withActiveLock(context) {
            check(!File(root(context), ACTIVE_FILE).exists()) {
                "another restore operation already owns the active pointer"
            }
            writeAtomic(File(root(context), ACTIVE_FILE), gson.toJson(pointer).toByteArray(Charsets.UTF_8))
        }
    }

    fun load(context: Context): RestoreRecord? {
        val active = File(root(context), ACTIVE_FILE)
        if (!active.isFile) return null
        try {
            val pointer = gson.fromJson(
                readAtomic(active).toString(Charsets.UTF_8),
                RestorePointer::class.java,
            ) ?: throw IllegalArgumentException("active pointer is null")
            require(pointer.operationId.matches(Regex("[0-9a-fA-F-]{36}"))) { "invalid operation id" }
            val directory = operationDirectory(context, pointer.operationId)
            require(directory.canonicalFile.parentFile == root(context).canonicalFile) {
                "operation escapes carrier root"
            }
            require(directory.isDirectory) { "operation directory is missing" }
            val journal = readJournal(directory)
            require(journal.operationId == pointer.operationId) { "operation owner mismatch" }
            val (plan, digest) = readPlan(directory)
            require(digest == journal.planDigest && digest == pointer.planDigest) {
                "plan integrity mismatch"
            }
            RestoreRecord(journal, plan, directory)
        } catch (error: Exception) {
            throw RestoreRecoveryBlockedException("Active restore journal is malformed", error)
        }
    }

    fun updatePhase(record: RestoreRecord, phase: RestorePhase, error: String = ""): RestoreRecord {
        val next = record.copy(journal = record.journal.copy(phase = phase.name, lastError = error))
        writeJournal(record.operationDirectory, next.journal)
        return next
    }

    fun updateJournal(record: RestoreRecord, journal: RestoreJournal): RestoreRecord {
        writeJournal(record.operationDirectory, journal)
        return record.copy(journal = journal)
    }

    fun recordError(record: RestoreRecord, error: Throwable) {
        writeJournal(
            record.operationDirectory,
            record.journal.copy(lastError = error.message ?: error.javaClass.simpleName),
        )
    }

    fun clearActiveIfOwned(context: Context, operationId: String) {
        withActiveLock(context) {
            val active = File(root(context), ACTIVE_FILE)
            if (!active.isFile) return@withActiveLock
            val pointer = runCatching {
                gson.fromJson(readAtomic(active).toString(Charsets.UTF_8), RestorePointer::class.java)
            }.getOrNull()
            if (pointer?.operationId == operationId) {
                check(active.delete() || !active.exists()) { "could not retire active pointer" }
            }
        }
    }

    fun retireOperation(record: RestoreRecord) {
        record.operationDirectory.deleteRecursively()
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        check(file.parentFile?.mkdirs() != false)
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "could not publish ${file.name}" }
    }

    private fun readAtomic(file: File): ByteArray {
        require(file.isFile) { "missing restore carrier ${file.name}" }
        return FileInputStream(file).use { it.readBytes() }
    }

    private fun <T> withActiveLock(context: Context, block: () -> T): T {
        val lockFile = File(root(context), ACTIVE_LOCK_FILE)
        check(lockFile.parentFile?.mkdirs() != false)
        FileOutputStream(lockFile, true).use { output ->
            val lock = try {
                output.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            check(lock != null) { "another restore owner is publishing the active pointer" }
            lock.use { return block() }
        }
    }
}

enum class RestoreAdmission { ALLOWED, DEFERRED }

/** Central fail-closed admission surface used by all conflicting workers. */
internal object RestoreGate {
    fun admission(context: Context): RestoreAdmission = try {
        if (RestoreOperationStore.load(context) == null) {
            RestoreAdmission.ALLOWED
        } else {
            RestoreAdmission.DEFERRED
        }
    } catch (_: RestoreRecoveryBlockedException) {
        RestoreAdmission.DEFERRED
    }

    fun isRestoreInProgress(context: Context): Boolean =
        admission(context) == RestoreAdmission.DEFERRED
}

/**
 * Owns the destructive Reset protocol.  The plan is durable before the
 * active pointer is published; every later phase is a forward, repeatable
 * operation over that same plan.
 */
object RestoreTransactionCoordinator {
    private val operationMutex = Mutex()

    @Volatile
    internal var beforeActivePublicationForTesting: (() -> Unit)? = null

    @Volatile
    internal var afterPreparedBeforeQuiescenceForTesting: (() -> Unit)? = null

    @Volatile
    internal var afterFilesReadyBeforeApplyForTesting: (() -> Unit)? = null

    @Volatile
    internal var stagingFailureForTesting: ((Long) -> Unit)? = null

    @Volatile
    internal var afterRoomCommitBeforeJournalForTesting: (() -> Unit)? = null

    @Volatile
    internal var preferenceCommitOverrideForTesting: ((Boolean) -> Boolean)? = null

    @Volatile
    internal var reconciliationFailureForTesting: (() -> Unit)? = null

    @Volatile
    internal var observeSchedulingFailureForTesting: (() -> Unit)? = null

    @Volatile
    internal var automaticKeywordReconciliationFailureForTesting: (() -> Unit)? = null

    @Volatile
    internal var downloadSchedulingFailureForTesting: (() -> Unit)? = null

    @Volatile
    internal var finalFilePublicationFailureForTesting: ((String) -> Unit)? = null

    @Volatile
    internal var roomApplyFailureForTesting: (() -> Unit)? = null

    suspend fun begin(context: Context, plan: RestorePlan): RestoreOutcome = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val existing = try {
                RestoreOperationStore.load(context)
            } catch (blocked: RestoreRecoveryBlockedException) {
                return@withLock RestoreOutcome.RecoveryPending(null, null, blocked.message.orEmpty())
            }
            if (existing != null) {
                return@withLock drive(context, existing)
            }

            val validatedPlan = try {
                BackupRestoreParser.validatePlan(plan)
            } catch (error: Exception) {
                return@withLock RestoreOutcome.RejectedBeforeOwnership(
                    error.message ?: "Restore payload validation failed",
                )
            }
            val operationId = UUID.randomUUID().toString()
            val directory = try {
                RestoreOperationStore.createOperation(context, operationId)
            } catch (error: Exception) {
                return@withLock RestoreOutcome.RejectedBeforeOwnership(
                    error.message ?: "Could not create restore staging area",
                )
            }
            var preparedJournal: RestoreJournal? = null
            try {
                stagePlanPayloads(directory, validatedPlan)
                val preferences = PreferenceManager.getDefaultSharedPreferences(context)
                val journal = RestoreJournal(
                    operationId = operationId,
                    phase = RestorePhase.PREPARED.name,
                    planDigest = RestoreOperationStore.writePlan(directory, validatedPlan),
                    createdAt = System.currentTimeMillis(),
                    preservedCachePath = preferences.getString("cache_path", "").orEmpty(),
                    preservedCachePathPresent = preferences.contains("cache_path"),
                    cleanupCadence = preferences.getString("cleanup_leftover_downloads", "").orEmpty(),
                )
                preparedJournal = journal
                RestoreOperationStore.writeJournal(directory, journal)
                beforeActivePublicationForTesting?.invoke()
                RestoreOperationStore.publishActive(
                    context,
                    RestorePointer(operationId, journal.planDigest),
                )
            } catch (error: Exception) {
                RestoreOperationStore.clearActiveIfOwned(context, operationId)
                directory.deleteRecursively()
                return@withLock RestoreOutcome.RejectedBeforeOwnership(
                    error.message ?: "Restore preparation failed",
                )
            }
            return@withLock drive(
                context,
                RestoreRecord(
                    journal = checkNotNull(preparedJournal),
                    plan = validatedPlan,
                    operationDirectory = directory,
                ),
            )
        }
    }

    /** Startup and test entry point.  A malformed carrier remains blocking. */
    suspend fun recover(context: Context): RestoreOutcome = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val record = try {
                RestoreOperationStore.load(context)
            } catch (blocked: RestoreRecoveryBlockedException) {
                return@withLock RestoreOutcome.RecoveryPending(
                    operationId = null,
                    phase = null,
                    reason = blocked.message.orEmpty(),
                )
            }
                ?: return@withLock RestoreOutcome.Completed("no-active-restore")
            drive(context, record)
        }
    }

    private suspend fun drive(context: Context, initial: RestoreRecord): RestoreOutcome {
        var record = initial
        try {
            while (true) {
                when (RestorePhase.valueOf(record.journal.phase)) {
                    RestorePhase.PREPARED -> {
                        afterPreparedBeforeQuiescenceForTesting?.invoke()
                        record = quiesce(context, record)
                        record = RestoreOperationStore.updatePhase(record, RestorePhase.QUIESCED)
                    }
                    RestorePhase.QUIESCED -> {
                        publishRequiredFiles(context, record)
                        record = RestoreOperationStore.updatePhase(record, RestorePhase.FILES_READY)
                    }
                    RestorePhase.FILES_READY -> {
                        // FILES_READY is a durable restart boundary. Recheck
                        // and republish idempotently before any Room row can
                        // bind a custom-thumbnail path.
                        publishRequiredFiles(context, record)
                        afterFilesReadyBeforeApplyForTesting?.invoke()
                        record = RestoreOperationStore.updatePhase(record, RestorePhase.APPLYING)
                        record = capturePostCommitSidecars(context, record)
                        val applied = applyAuthoritativeState(context, record)
                        publishPreferences(context, record, applied)
                        afterRoomCommitBeforeJournalForTesting?.invoke()
                        record = RestoreOperationStore.updatePhase(record, RestorePhase.DATA_COMMITTED)
                    }
                    RestorePhase.APPLYING -> {
                        record = capturePostCommitSidecars(context, record)
                        val applied = applyAuthoritativeState(context, record)
                        publishPreferences(context, record, applied)
                        afterRoomCommitBeforeJournalForTesting?.invoke()
                        record = RestoreOperationStore.updatePhase(record, RestorePhase.DATA_COMMITTED)
                    }
                    RestorePhase.DATA_COMMITTED -> {
                        record = RestoreOperationStore.updatePhase(record, RestorePhase.RECONCILING)
                    }
                    RestorePhase.RECONCILING -> {
                        reconcilePostCommit(context, record)
                        record = RestoreOperationStore.updatePhase(record, RestorePhase.COMPLETE)
                        RestoreOperationStore.clearActiveIfOwned(context, record.journal.operationId)
                        RestoreOperationStore.retireOperation(record)
                        return RestoreOutcome.Completed(record.journal.operationId)
                    }
                    RestorePhase.COMPLETE -> {
                        RestoreOperationStore.clearActiveIfOwned(context, record.journal.operationId)
                        RestoreOperationStore.retireOperation(record)
                        return RestoreOutcome.Completed(record.journal.operationId)
                    }
                }
            }
        } catch (error: Exception) {
            runCatching { RestoreOperationStore.recordError(record, error) }
            val phase = runCatching { RestorePhase.valueOf(record.journal.phase) }.getOrNull()
            if (phase == RestorePhase.RECONCILING || phase == RestorePhase.DATA_COMMITTED) {
                RestoreOutcome.CommittedReconciliationPending(
                    record.journal.operationId,
                    error.message ?: "Post-commit reconciliation remains pending",
                )
            } else {
                RestoreOutcome.RecoveryPending(
                    record.journal.operationId,
                    phase,
                    error.message ?: "Restore remains recoverable",
                )
            }
        }
    }

    private fun stagePlanPayloads(directory: File, plan: RestorePlan) {
        plan.data.customThumbnails.orEmpty().forEach { thumbnail ->
            stagingFailureForTesting?.invoke(thumbnail.historyId)
            val bytes = Base64.decode(thumbnail.base64, Base64.DEFAULT)
            val digest = RestoreOperationStore.digest(bytes)
            val staged = File(
                File(directory, "payload"),
                "history_${thumbnail.historyId}_${digest}.${thumbnail.extension}.stage",
            )
            if (staged.isFile && staged.length() == bytes.size.toLong() &&
                RestoreOperationStore.digest(staged.readBytes()) == digest
            ) {
                return@forEach
            }
            FileOutputStream(staged).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            check(
                staged.isFile && staged.length() == bytes.size.toLong() &&
                    RestoreOperationStore.digest(staged.readBytes()) == digest
            ) {
                "thumbnail staging did not produce a complete file"
            }
        }
    }

    private suspend fun quiesce(context: Context, record: RestoreRecord): RestoreRecord {
        var current = record
        val capturesMembershipNotifications = current.plan.data.queued != null ||
            current.plan.data.observeSources != null
        if (capturesMembershipNotifications) {
            val initialIds = DBManager.getInstance(context)
                .observeSourcesDao
                .getAllMembershipRetryDownloadIds()
            current = RestoreOperationStore.updateJournal(
                current,
                current.journal.copy(
                    membershipWaitingNotificationIds = (
                        current.journal.membershipWaitingNotificationIds + initialIds
                    ).distinct().sorted(),
                ),
            )
        }
        // F10's coordinator remains the sole cleanup authority.  Its
        // settings-reset handoff is made only after the durable Reset owner
        // exists, so a failed pre-publication preparation cannot alter it.
        if (current.plan.data.settings != null) {
            check(CleanupScheduleCoordinator.prepareForSettingsReset(context)) {
                "Cleanup authority is not durably quiescent"
            }
            current = RestoreOperationStore.updateJournal(
                current,
                current.journal.copy(
                    cleanupCadence = CleanupScheduleCoordinator.currentCadenceForSettings(context),
                ),
            )
        }
        val workManager = WorkManager.getInstance(context)
        val data = record.plan.data
        val hasHistoryReset = data.downloads != null
        val hasDownloadReset = hasHistoryReset || listOfNotNull(
            data.queued,
            data.paused,
            data.scheduled,
            data.cancelled,
            data.errored,
            data.saved,
        ).isNotEmpty()
        val hasSourceReset = data.observeSources != null || data.automaticKeywordRules != null || hasDownloadReset
        val hasKeywordReset = hasHistoryReset || data.automaticKeywordRules != null || data.observeSources != null
        val tags = buildList {
            if (hasDownloadReset) {
                add("DownloadWorker")
                add("download")
                add("cancelScheduledDownload")
                add("updateFormats")
                add("updateData")
                add(MoveCacheFilesWorker.TAG)
                add("cacheFiles")
            }
            if (hasHistoryReset) {
                add(HardSubScanWorker.TAG)
                add("local_add_worker")
                add("history_date_fetch")
            }
            if (hasSourceReset) add("observeSources")
            if (hasKeywordReset) add("automaticKeywordRules")
            if (record.plan.data.settings != null) add(CleanupScheduleCoordinator.TAG)
            if (hasDownloadReset) add("low_quality_redownload")
        }
        tags.forEach { tag ->
            workManager.cancelAllWorkByTag(tag)
                .result
                .get(QUIESCENCE_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        val deadline = System.currentTimeMillis() + QUIESCENCE_TIMEOUT_MS
        var quiesced = false
        while (System.currentTimeMillis() < deadline) {
            val workActive = tags.any { tag ->
                val infos = workManager.getWorkInfosByTag(tag)
                    .get(QUIESCENCE_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                infos.any { info -> !info.state.isFinished }
            }
            val nativeDownloadActive = if (hasDownloadReset) {
                DBManager.getInstance(context)
                    .downloadDao
                    .getActiveAndPostProcessingDownloadsList()
                    .any { item ->
                        DownloadWorkerExecutionOwners.isOwnedBy(item.id, item.executionId) ||
                            DownloadWorker.hasRegisteredNativeProcess(item.id, item.executionId)
                    }
            } else {
                false
            }
            if (!workActive && !nativeDownloadActive) {
                quiesced = true
                break
            }
            delay(QUIESCENCE_POLL_MS)
        }
        if (!quiesced) {
            throw IllegalStateException("Conflicting WorkManager work did not quiesce")
        }
        if (capturesMembershipNotifications) {
            val finalIds = DBManager.getInstance(context)
                .observeSourcesDao
                .getAllMembershipRetryDownloadIds()
            val allIds = (
                current.journal.membershipWaitingNotificationIds + finalIds
            ).distinct().sorted()
            if (allIds != current.journal.membershipWaitingNotificationIds) {
                current = RestoreOperationStore.updateJournal(
                    current,
                    current.journal.copy(membershipWaitingNotificationIds = allIds),
                )
            }
        }
        return current
    }

    /**
     * Captures external notification identities before the Room graph is
     * replaced.  The journal, rather than an in-memory list, remains the
     * authority if Room commits and the process dies before DATA_COMMITTED.
     */
    private suspend fun capturePostCommitSidecars(
        context: Context,
        record: RestoreRecord,
    ): RestoreRecord {
        val data = record.plan.data
        val historyReset = data.downloads != null
        val downloadReset = historyReset || listOfNotNull(
            data.queued,
            data.paused,
            data.scheduled,
            data.cancelled,
            data.errored,
            data.saved,
        ).isNotEmpty()
        val sourceReset = data.observeSources != null || data.automaticKeywordRules != null || downloadReset
        if (!historyReset && !downloadReset && !sourceReset) return record
        // Capture the pre-apply external identities once. If SQLite commits
        // and the process dies before DATA_COMMITTED, re-applying the plan
        // must not recapture newly-created destination identities as if they
        // were superseded pre-reset state.
        if (record.journal.sidecarsCaptured) return record

        val db = DBManager.getInstance(context)
        val historyIds = if (historyReset) db.historyDao.getAllIds() else emptyList()
        val statusSet = buildSet {
            if (data.queued != null) {
                add(DownloadRepository.Status.Queued.name)
                add(DownloadRepository.Status.WaitingForMembership.name)
            }
            if (data.paused != null) add(DownloadRepository.Status.Paused.name)
            if (data.scheduled != null) add(DownloadRepository.Status.Scheduled.name)
            if (data.cancelled != null) add(DownloadRepository.Status.Cancelled.name)
            if (data.errored != null) add(DownloadRepository.Status.Error.name)
            if (data.saved != null) add(DownloadRepository.Status.Saved.name)
        }
        val downloadIds = if (statusSet.isEmpty()) {
            emptyList()
        } else {
            db.downloadDao.getAllDownloadsList()
                .filter { it.status in statusSet }
                .map { it.id }
        }
        val lowQualityOperationIds = linkedSetOf<String>()
        downloadIds.chunked(800).forEach { batch ->
            lowQualityOperationIds += db.lowQualityRedownloadDao
                .getOperationIdsForRestoreReset(batch, emptyList())
        }
        historyIds.chunked(800).forEach { batch ->
            lowQualityOperationIds += db.lowQualityRedownloadDao
                .getOperationIdsForRestoreReset(emptyList(), batch)
        }
        val oldObserveSourceIds = if (sourceReset) {
            db.observeSourcesDao.getAllSourcesIncludingManaged().map { it.id }
        } else {
            emptyList()
        }
        val historyDateFetchOperationIds = linkedSetOf<String>()
        historyIds.chunked(800).forEach { batch ->
            historyDateFetchOperationIds += db.historyDateFetchDao.getOperationIdsForHistoryIds(batch)
        }
        val updated = record.journal.copy(
            lowQualityOperationIds = (
                record.journal.lowQualityOperationIds + lowQualityOperationIds
            ).distinct().sorted(),
            historyDateFetchWasActive = record.journal.historyDateFetchWasActive ||
                (historyReset && db.historyDateFetchDao.getActiveOperation() != null),
            historyDateFetchOperationId = record.journal.historyDateFetchOperationId
                ?: if (historyReset) db.historyDateFetchDao.getActiveOperation()?.operationId else null,
            historyDateFetchOperationIds = (
                record.journal.historyDateFetchOperationIds + historyDateFetchOperationIds
            ).distinct().sorted(),
            supersededDownloadNotificationIds = (
                record.journal.supersededDownloadNotificationIds + downloadIds
            ).distinct().sorted(),
            supersededObserveSourceNotificationIds = (
                record.journal.supersededObserveSourceNotificationIds + oldObserveSourceIds
            ).distinct().sorted(),
            sidecarsCaptured = true,
        )
        return if (updated == record.journal) record else {
            RestoreOperationStore.updateJournal(record, updated)
        }
    }

    private fun publishRequiredFiles(context: Context, record: RestoreRecord) {
        finalFilePublicationFailureForTesting?.invoke(record.journal.operationId)
        val finalRoot = File(context.filesDir, "restored_custom_thumbnails")
        check(finalRoot.mkdirs() || finalRoot.isDirectory)
        record.plan.data.customThumbnails.orEmpty().forEach { thumbnail ->
            val bytes = Base64.decode(thumbnail.base64, Base64.DEFAULT)
            val digest = RestoreOperationStore.digest(bytes)
            val staged = File(
                File(record.operationDirectory, "payload"),
                "history_${thumbnail.historyId}_${digest}.${thumbnail.extension}.stage",
            )
            check(staged.isFile && staged.length() == bytes.size.toLong()) {
                "required staged thumbnail is missing"
            }
            val finalFile = File(
                finalRoot,
                "restore_${record.journal.operationId}_${thumbnail.historyId}_${digest}.${thumbnail.extension}",
            )
            if (!finalFile.isFile || finalFile.length() != bytes.size.toLong() ||
                RestoreOperationStore.digest(finalFile.readBytes()) != digest
            ) {
                val temporary = File(finalRoot, ".${finalFile.name}.tmp")
                FileInputStream(staged).use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output)
                        output.flush()
                        output.fd.sync()
                    }
                }
                if (finalFile.exists()) {
                    check(finalFile.delete()) { "could not replace corrupted thumbnail" }
                }
                check(temporary.renameTo(finalFile)) { "could not publish thumbnail" }
            }
            check(
                finalFile.isFile && finalFile.length() == bytes.size.toLong() &&
                    RestoreOperationStore.digest(finalFile.readBytes()) == digest
            ) {
                "published thumbnail is incomplete"
            }
        }
    }

    private data class AppliedState(
        val visibleGroups: Set<String>? = null,
        val visibleYoutubers: Set<String>? = null,
        val visibleKeywords: Set<String>? = null,
    )

    private suspend fun applyAuthoritativeState(
        context: Context,
        record: RestoreRecord,
    ): AppliedState {
        val db = DBManager.getInstance(context)
        val data = record.plan.data
        val historyRepository = HistoryKeywordAssignmentRepository(db)
        val downloadRepository = DownloadRepository(db)
        var visibleGroups: Set<String>? = null
        var visibleYoutubers: Set<String>? = null
        var visibleKeywords: Set<String>? = null

        return HistoryReferenceMutationCoordinator.withRestoreLock {
            db.withTransaction {
                val oldHistoryIds = if (data.downloads != null) db.historyDao.getAllIds() else emptyList()
                if (data.downloads != null) {
                    if (oldHistoryIds.isNotEmpty()) {
                        oldHistoryIds.chunked(800).forEach { batch ->
                            db.automaticKeywordRuleDao.deleteAssignmentsForHistoryIds(batch)
                            db.historyReplacementBarrierDao.deleteForHistoryIds(batch)
                            db.downloadPrimarySuccessAuthorityDao.deleteForHistoryIds(batch)
                            db.historyDateFetchDao.deleteItemsForHistoryIds(batch)
                            db.lowQualityRedownloadDao.deleteItemsForHistoryIds(batch)
                        }
                    }
                    db.playlistDao.clearPlaylistItems()
                    db.historyDao.nuke()
                    val historyDateOperationIds = (
                        record.journal.historyDateFetchOperationIds +
                            listOfNotNull(record.journal.historyDateFetchOperationId)
                    ).distinct()
                    if (historyDateOperationIds.isNotEmpty()) {
                        db.historyDateFetchDao.deleteOrphanedOperationsForRestoreReset(
                            historyDateOperationIds,
                        )
                    }
                    if (record.journal.lowQualityOperationIds.isNotEmpty()) {
                        db.lowQualityRedownloadDao.deleteOrphanedOperationsForRestoreReset(
                            record.journal.lowQualityOperationIds,
                        )
                    }
                    db.workManagerHandoffCarrierDao.deleteOutstandingForKinds(
                        listOf(WorkManagerHandoffCarrier.HARD_SUB_SCAN),
                    )
                }
                roomApplyFailureForTesting?.invoke()

                suspend fun clearDownloadCategory(delete: suspend () -> Unit) {
                    delete()
                    if (record.journal.lowQualityOperationIds.isNotEmpty()) {
                        db.lowQualityRedownloadDao.deleteOrphanedOperationsForRestoreReset(
                            record.journal.lowQualityOperationIds,
                        )
                    }
                }
                val resetDownloadStatuses = buildSet {
                    if (data.queued != null) {
                        add(DownloadRepository.Status.Queued.name)
                        add(DownloadRepository.Status.WaitingForMembership.name)
                    }
                    if (data.paused != null) add(DownloadRepository.Status.Paused.name)
                    if (data.scheduled != null) add(DownloadRepository.Status.Scheduled.name)
                    if (data.cancelled != null) add(DownloadRepository.Status.Cancelled.name)
                    if (data.errored != null) add(DownloadRepository.Status.Error.name)
                    if (data.saved != null) add(DownloadRepository.Status.Saved.name)
                }
                val targetedDownloadIds = if (resetDownloadStatuses.isNotEmpty()) {
                    db.downloadDao.getAllDownloadsList()
                        .filter { it.status in resetDownloadStatuses }
                        .map { it.id }
                        .toSet()
                } else {
                    emptySet()
                }
                if (targetedDownloadIds.isNotEmpty()) {
                    downloadRepository.clearRestoreTargetedUndoCarriers(targetedDownloadIds)
                    targetedDownloadIds.toList().chunked(800).forEach { batch ->
                        db.historyReplacementBarrierDao.deleteForDownloadIds(batch)
                        db.downloadPrimarySuccessAuthorityDao.deleteForDownloadIds(batch)
                        db.lowQualityRedownloadDao.deleteItemsForDownloadIds(batch)
                    }
                }
                if (resetDownloadStatuses.isNotEmpty()) {
                    db.workManagerHandoffCarrierDao.deleteOutstandingForKinds(
                        listOf(
                            WorkManagerHandoffCarrier.SCHEDULE_START,
                            WorkManagerHandoffCarrier.SCHEDULE_END,
                        ),
                    )
                }
                data.queued?.let {
                    clearDownloadCategory { db.downloadDao.deleteQueued() }
                }
                data.paused?.let {
                    clearDownloadCategory { db.downloadDao.deletePaused() }
                }
                data.scheduled?.let {
                    clearDownloadCategory { db.downloadDao.deleteScheduled() }
                }
                data.cancelled?.let {
                    clearDownloadCategory { db.downloadDao.deleteCancelled() }
                }
                data.errored?.let {
                    clearDownloadCategory { db.downloadDao.deleteErrored() }
                }
                data.saved?.let {
                    clearDownloadCategory { db.downloadDao.deleteSaved() }
                }

                val sourceMap = linkedMapOf<Long, Long>()
                if (data.observeSources != null) {
                    // This is the same user-source-only scope as the existing
                    // repository Reset contract; managed discovery sources
                    // belong to post-commit automatic-keyword reconciliation.
                    val oldUserSourceIds = db.observeSourcesDao.getAllSources().map { it.id }
                    if (oldUserSourceIds.isNotEmpty()) {
                        db.workManagerHandoffCarrierDao
                            .deleteOutstandingObserveRetryForSourceIds(oldUserSourceIds)
                    }
                    db.observeSourcesDao.deleteAllForRestoreReset()
                    data.observeSources.forEach { source ->
                        val destination = source.copy(
                            id = 0L,
                            observationPurpose = ObservationPurposes.USER,
                            managedConditionKey = "",
                            downloadItemTemplate = source.downloadItemTemplate.copy(
                                id = 0L,
                                observeSourceId = 0L,
                                executionId = "",
                            ),
                        )
                        val newId = db.observeSourcesDao.insert(destination)
                        check(newId > 0L) { "ObserveSource restore did not allocate an identity" }
                        sourceMap[source.id] = newId
                        db.observeSourcesDao.update(
                            destination.copy(
                                id = newId,
                                downloadItemTemplate = destination.downloadItemTemplate.copy(
                                    observeSourceId = newId,
                                ),
                            ),
                        )
                    }
                }

                val ruleMap = linkedMapOf<Long, Long>()
                if (data.downloads != null || data.automaticKeywordRules != null) {
                    db.automaticKeywordRuleDao.getAllRules().forEach { rule ->
                        db.automaticKeywordRuleDao.deleteAssignmentsForSource(
                            HistoryKeywordAssignmentSources.RULE,
                            rule.id,
                        )
                        db.automaticKeywordRuleDao.deleteRule(rule.id)
                    }
                    data.automaticKeywordRules.orEmpty().forEach { rule ->
                        val inserted = db.automaticKeywordRuleDao.insertRule(
                            rule.copy(
                                id = 0L,
                                conditionType = AutomaticKeywordRuleTypes.PLAYLIST,
                                revision = 1L,
                                manualSyncStatus = normalizeSyncStatus(rule.manualSyncStatus),
                                discoveryStatus = normalizeSyncStatus(rule.discoveryStatus),
                            ),
                        )
                        check(inserted > 0L) { "Automatic keyword rule restore did not allocate an identity" }
                        ruleMap[rule.id] = inserted
                    }
                    val keywords = data.automaticKeywordRuleKeywords.orEmpty()
                        .mapNotNull { keyword ->
                            ruleMap[keyword.ruleId]?.let { destinationRuleId ->
                                keyword.copy(ruleId = destinationRuleId)
                            }
                        }
                    if (keywords.isNotEmpty()) db.automaticKeywordRuleDao.insertRuleKeywords(keywords)
                    data.automaticKeywordRuleVideoMatches.orEmpty().forEach { match ->
                        ruleMap[match.ruleId]?.let { destinationRuleId ->
                            db.automaticKeywordRuleDao.insertVideoMatch(
                                match.copy(ruleId = destinationRuleId),
                            )
                        }
                    }
                }

                val historyMap = linkedMapOf<Long, Long>()
                data.downloads?.forEach { history ->
                    val thumbnailPath = deterministicThumbnailPath(context, record, history.id)
                    val destination = history.copy(
                        id = 0L,
                        downloadId = 0L,
                        customThumb = if (thumbnailPath.isFile) thumbnailPath.absolutePath else "",
                    )
                    val newId = historyRepository.insertHistoryWithinRestoreTransaction(destination)
                    check(newId > 0L) { "History restore did not allocate an identity" }
                    historyMap[history.id] = newId
                }

                val downloadMap = linkedMapOf<Long, Long>()
                val downloadCategories = listOfNotNull(
                    data.queued,
                    data.paused,
                    data.scheduled,
                    data.cancelled,
                    data.errored,
                    data.saved,
                ).flatten()
                downloadCategories.forEach { item ->
                    val destinationSourceId = sourceMap[item.observeSourceId] ?: 0L
                    val remapped = remapDownload(item, destinationSourceId, historyMap)
                    val newId = downloadRepository.insertRestoredDownloadWithinRestoreTransaction(
                        remapped.first,
                        remapped.second,
                        preserveOrderPosition = remapped.first.status == DownloadRepository.Status.Paused.name,
                    )
                    check(newId > 0L) { "Download restore did not allocate an identity" }
                    if (item.id > 0L) downloadMap[item.id] = newId
                }
                data.downloads?.forEach { history ->
                    val destinationDownloadId = downloadMap[history.downloadId] ?: 0L
                    historyMap[history.id]?.let { historyId ->
                        db.historyDao.updateDownloadIdById(historyId, destinationDownloadId)
                    }
                }

                if (data.historyKeywordAssignments != null) {
                    val grouped = data.historyKeywordAssignments.mapNotNull { assignment ->
                        val historyId = historyMap[assignment.historyItemId] ?: return@mapNotNull null
                        val sourceId = when (assignment.sourceType) {
                            HistoryKeywordAssignmentSources.MANUAL -> 0L
                            HistoryKeywordAssignmentSources.RULE -> ruleMap[assignment.sourceId]
                            HistoryKeywordAssignmentSources.LEGACY_OBSERVE_SOURCE -> sourceMap[assignment.sourceId]
                            else -> null
                        } ?: return@mapNotNull null
                        assignment.copy(historyItemId = historyId, sourceId = sourceId)
                    }.groupBy { it.historyItemId }
                    grouped.forEach { (historyId, assignments) ->
                        historyRepository.restoreAssignmentsWithinRestoreTransaction(
                            historyId,
                            assignments,
                        )
                    }
                }

                if (data.playlists != null || data.playlistItemCrossRefs != null ||
                    data.playlistGroups != null || data.playlistGroupMembers != null
                ) {
                    db.playlistDao.clearPlaylistItems()
                    db.playlistGroupDao.clearMembers()
                    db.playlistGroupDao.clearGroups()
                    db.playlistDao.clearPlaylists()
                    val playlistMap = linkedMapOf<Long, Long>()
                    data.playlists.orEmpty().forEach { playlist ->
                        playlistMap[playlist.id] = db.playlistDao.insertPlaylist(playlist.copy(id = 0L))
                    }
                    val groupMap = linkedMapOf<Long, Long>()
                    data.playlistGroups.orEmpty().forEach { group ->
                        val id = db.playlistGroupDao.getGroupByName(group.name)?.id
                            ?: db.playlistGroupDao.insertGroup(group.copy(id = 0L))
                        groupMap[group.id] = id
                    }
                    val crossRefs = data.playlistItemCrossRefs.orEmpty().mapNotNull { relation ->
                        val playlistId = playlistMap[relation.playlistId]
                        val historyId = historyMap[relation.historyItemId]
                        if (playlistId == null || historyId == null) null
                        else PlaylistItemCrossRef(playlistId, historyId)
                    }
                    if (crossRefs.isNotEmpty()) db.playlistDao.insertPlaylistItems(crossRefs)
                    val members = data.playlistGroupMembers.orEmpty().mapNotNull { member ->
                        val groupId = groupMap[member.groupId]
                        val playlistId = playlistMap[member.playlistId]
                        if (groupId == null || playlistId == null) null
                        else PlaylistGroupMember(groupId, playlistId)
                    }
                    if (members.isNotEmpty()) db.playlistGroupDao.insertMembers(members)
                }

                if (data.keywordGroups != null || data.keywordGroupMembers != null) {
                    db.keywordGroupDao.clearMembers()
                    db.keywordGroupDao.clearGroups()
                    val groupMap = linkedMapOf<Long, Long>()
                    data.keywordGroups.orEmpty().forEach { group ->
                        groupMap[group.id] = db.keywordGroupDao.insertGroup(group.copy(id = 0L))
                    }
                    val members = data.keywordGroupMembers.orEmpty().mapNotNull { member ->
                        groupMap[member.groupId]?.let { KeywordGroupMember(it, member.keyword) }
                    }
                    if (members.isNotEmpty()) db.keywordGroupDao.insertMembers(members)
                }
                visibleKeywords = data.historyVisibleChildKeywords?.toSet()

                if (data.youtuberGroups != null || data.youtuberGroupMembers != null ||
                    data.youtuberGroupRelations != null || data.youtuberMeta != null ||
                    data.historyVisibleChildYoutuberGroups != null || data.historyVisibleChildYoutubers != null
                ) {
                    db.youtuberGroupDao.clearMembers()
                    db.youtuberGroupDao.clearRelations()
                    db.youtuberGroupDao.clearGroups()
                    db.youtuberMetaDao.clearAll()
                    val groupMap = linkedMapOf<Long, Long>()
                    data.youtuberGroups.orEmpty().forEach { group ->
                        groupMap[group.id] = db.youtuberGroupDao.insertGroup(group.copy(id = 0L))
                    }
                    val members = data.youtuberGroupMembers.orEmpty().mapNotNull { member ->
                        groupMap[member.groupId]?.let { YoutuberGroupMember(it, member.author) }
                    }
                    if (members.isNotEmpty()) db.youtuberGroupDao.insertMembers(members)
                    val relations = data.youtuberGroupRelations.orEmpty().mapNotNull { relation ->
                        val parent = groupMap[relation.parentGroupId]
                        val child = groupMap[relation.childGroupId]
                        if (parent == null || child == null || parent == child) null
                        else YoutuberGroupRelation(parent, child)
                    }
                    if (relations.isNotEmpty()) db.youtuberGroupDao.insertRelations(relations)
                    data.youtuberMeta.orEmpty().forEach { meta -> db.youtuberMetaDao.upsert(meta) }
                    visibleGroups = data.historyVisibleChildYoutuberGroups
                        ?.mapNotNull { groupMap[it]?.toString() }?.toSet()
                    visibleYoutubers = data.historyVisibleChildYoutubers?.toSet()
                }

                if (data.cookies != null) {
                    db.cookieDao.deleteAll()
                    data.cookies.forEach { db.cookieDao.insert(it.copy(id = 0L)) }
                }
                if (data.templates != null) {
                    db.commandTemplateDao.deleteAll()
                    data.templates.forEach { db.commandTemplateDao.insert(it.copy(id = 0L)) }
                }
                if (data.shortcuts != null) {
                    db.commandTemplateDao.deleteAllShortcuts()
                    data.shortcuts.forEach { db.commandTemplateDao.insertShortcut(it.copy(id = 0L)) }
                }
                if (data.searchHistory != null) {
                    db.searchHistoryDao.deleteAll()
                    data.searchHistory.forEach { db.searchHistoryDao.insert(it.copy(id = 0L)) }
                }
                AppliedState(visibleGroups, visibleYoutubers, visibleKeywords)
            }
        }
    }

    private fun deterministicThumbnailPath(
        context: Context,
        record: RestoreRecord,
        backupHistoryId: Long,
    ): File {
        val item = record.plan.data.customThumbnails.orEmpty()
            .firstOrNull { it.historyId == backupHistoryId }
            ?: return File(record.operationDirectory, "no-thumbnail")
        val bytes = Base64.decode(item.base64, Base64.DEFAULT)
        val digest = RestoreOperationStore.digest(bytes)
        return File(
            File(context.filesDir, "restored_custom_thumbnails"),
            "restore_${record.journal.operationId}_${backupHistoryId}_${digest}.${item.extension}",
        )
    }

    private fun remapDownload(
        item: DownloadItem,
        destinationSourceId: Long,
        historyMap: Map<Long, Long>,
    ): Pair<DownloadItem, HistoryReplacementBarrier?> {
        // Preserve operationId until the existing refusal policy decides
        // whether a persisted barrier can be reconstructed. It is a durable
        // replacement identity, not a destination numeric Download identity.
        var destination = item.copy(
            id = 0L,
            observeSourceId = destinationSourceId,
            executionId = "",
        )
        var barrier: HistoryReplacementBarrier? = null
        val persistedRefusal = HistoryReplacementDiagnostic
            .persistedHistoryReplacementIssue(item.lastIssueCode)
        when (val marker = HistoryRedownloadMarker.remap(item.playlistURL, historyMap)) {
            RestoreRemapResult.NotMarker -> {
                if (persistedRefusal != null) {
                    destination = destination.copy(
                        playlistURL = "",
                        status = DownloadRepository.Status.Error.name,
                    )
                }
            }
            is RestoreRemapResult.Mapped -> {
                val mappedItem = destination.copy(playlistURL = marker.encodedMarker)
                val revoked = HistoryRedownloadRestorePolicy.revokeOrphanQualityMarker(
                    item = mappedItem,
                    hasPersistedRefusal = persistedRefusal != null,
                )
                if (revoked != null) {
                    destination = revoked
                } else {
                    val mappedHistoryId = HistoryRedownloadMarker
                        .parse(marker.encodedMarker)
                        ?.historyId
                    val canReconstructBarrier = persistedRefusal != null &&
                        mappedHistoryId != null &&
                        mappedHistoryId > 0L &&
                        mappedItem.operationId.isNotBlank() &&
                        mappedItem.url.isNotBlank()
                    if (!canReconstructBarrier) {
                        destination = if (persistedRefusal == null) {
                            mappedItem
                        } else {
                            mappedItem.copy(
                                playlistURL = "",
                                status = DownloadRepository.Status.Error.name,
                            )
                        }
                    } else {
                        destination = mappedItem
                        barrier = HistoryReplacementBarrier(
                            downloadId = 0L,
                            operationId = mappedItem.operationId,
                            historyId = mappedHistoryId!!,
                            expectedSourceUrl = mappedItem.url,
                            expectedType = mappedItem.type.name,
                            issueCode = persistedRefusal.code.name,
                            issueStage = item.lastIssueStage.ifBlank {
                                persistedRefusal.stage.name
                            },
                            createdAt = System.currentTimeMillis(),
                        )
                    }
                }
            }
            RestoreRemapResult.Unmappable -> {
                val unmappableMarker = HistoryRedownloadMarker.parse(item.playlistURL)
                destination = destination.copy(
                    playlistURL = "",
                    status = DownloadRepository.Status.Error.name,
                    lastIssueCode = persistedRefusal?.code?.name
                        ?: if (unmappableMarker?.isQualityReplacement == true) {
                            DownloadIssueCode.HISTORY_REPLACEMENT_NOT_AUTHORIZED.name
                        } else {
                            DownloadIssueCode.HISTORY_TARGET_DELETED.name
                        },
                    lastIssueStage = item.lastIssueStage.ifBlank {
                        persistedRefusal?.stage?.name ?: DownloadIssueStage.HISTORY.name
                    },
                )
            }
        }
        return destination to barrier
    }

    private fun normalizeSyncStatus(value: String): String =
        if (value == AutomaticKeywordSyncStatus.QUEUED ||
            value == AutomaticKeywordSyncStatus.RUNNING
        ) {
            AutomaticKeywordSyncStatus.NEVER
        } else {
            value
        }

    private fun publishPreferences(
        context: Context,
        record: RestoreRecord,
        applied: AppliedState,
    ) {
        val data = record.plan.data
        if (data.settings == null && applied.visibleGroups == null &&
            applied.visibleYoutubers == null && applied.visibleKeywords == null
        ) return

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val snapshot = preferences.all.toMap()
        val editor = preferences.edit()
        if (data.settings != null) {
            editor.clear()
            if (record.journal.preservedCachePathPresent ||
                record.journal.preservedCachePath.isNotBlank()
            ) {
                editor.putString("cache_path", record.journal.preservedCachePath)
            }
            if (record.journal.cleanupCadence.isNotBlank()) {
                editor.putString("cleanup_leftover_downloads", record.journal.cleanupCadence)
            }
            data.settings.forEach { putPortable(editor, it) }
        }
        applied.visibleGroups?.let { editor.putStringSet("history_visible_child_youtuber_groups", it) }
        applied.visibleYoutubers?.let { editor.putStringSet("history_visible_child_youtubers", it) }
        applied.visibleKeywords?.let { editor.putStringSet("history_visible_child_keywords", it) }

        val committed = editor.commit()
        val accepted = preferenceCommitOverrideForTesting?.invoke(committed) ?: committed
        if (accepted) return

        // SharedPreferences may update the process map before commit() reports
        // false.  Explicitly compensate, but never rely on compensation for
        // restart recovery: the active journal remains authoritative.
        val compensation = preferences.edit().clear()
        snapshot.forEach { (key, value) ->
            when (value) {
                is String -> compensation.putString(key, value)
                is Boolean -> compensation.putBoolean(key, value)
                is Int -> compensation.putInt(key, value)
                is Long -> compensation.putLong(key, value)
                is Float -> compensation.putFloat(key, value)
                is Set<*> -> compensation.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(compensation.commit()) { "Preference compensation commit failed" }
        throw IllegalStateException("Restore preference commit was not durable")
    }

    private fun putPortable(editor: SharedPreferences.Editor, item: com.ireum.ytdl.database.models.BackupSettingsItem) {
        require(BackupSettingsUtil.isPortablePreferenceKey(item.key)) {
            "Non-portable preference ${item.key}"
        }
        when (item.type) {
            "String" -> editor.putString(item.key, item.value)
            "Boolean" -> editor.putBoolean(item.key, item.value == "true")
            "Int" -> editor.putInt(item.key, item.value.toInt())
            "Long" -> editor.putLong(item.key, item.value.toLong())
            "Float" -> editor.putFloat(item.key, item.value.toFloat())
            "StringSet", "Set", "HashSet", "LinkedHashSet", "ArraySet" -> {
                val members = Gson().fromJson(item.value, Array<String>::class.java)?.toSet()
                    ?: error("Invalid StringSet preference ${item.key}")
                editor.putStringSet(item.key, members)
            }
            else -> error("Unsupported preference type ${item.type}")
        }
    }

    private suspend fun reconcilePostCommit(context: Context, record: RestoreRecord) {
        reconciliationFailureForTesting?.invoke()
        val notificationUtil = NotificationUtil(context)
        record.journal.supersededDownloadNotificationIds.forEach { id ->
            notificationUtil.cancelDownloadNotifications(id.toInt())
        }
        record.journal.supersededObserveSourceNotificationIds.forEach { id ->
            notificationUtil.cancelObserveRetryConfirmation(id)
        }
        record.journal.membershipWaitingNotificationIds.forEach { id ->
            notificationUtil.cancelMembershipWaitingNotification(id)
        }
        val db = DBManager.getInstance(context)
        if (record.journal.lowQualityOperationIds.isNotEmpty()) {
            LowQualityRedownloadLedger.refresh(
                context,
                record.journal.lowQualityOperationIds,
            )
            if (db.lowQualityRedownloadDao.getActiveOperation() == null) {
                LowQualityRedownloadNotification(context).cancel()
            }
        }
        if (
            record.journal.historyDateFetchWasActive &&
            db.historyDateFetchDao.getActiveOperation() == null
        ) {
            HistoryDateFetchNotification(context).cancel()
        }
        val data = record.plan.data
        val hasHistoryReset = data.downloads != null
        val hasDownloadReset = hasHistoryReset || listOfNotNull(
            data.queued,
            data.paused,
            data.scheduled,
            data.cancelled,
            data.errored,
            data.saved,
        ).isNotEmpty()
        if (data.observeSources != null || data.automaticKeywordRules != null || hasDownloadReset) {
            observeSchedulingFailureForTesting?.invoke()
            val repository = ObserveSourcesRepository(
                db.observeSourcesDao,
                WorkManager.getInstance(context),
                PreferenceManager.getDefaultSharedPreferences(context),
                context,
            )
            db.observeSourcesDao.getAllSources().filter {
                it.observationPurpose == ObservationPurposes.USER &&
                    it.status == ObserveSourcesRepository.SourceStatus.ACTIVE
            }.forEach { source ->
                check(repository.observeTaskAndAwait(source, allowDuringRestore = true)) {
                    "ObserveSource scheduling reconciliation was not accepted"
                }
            }
        }
        if (hasHistoryReset || data.automaticKeywordRules != null || data.observeSources != null) {
            automaticKeywordReconciliationFailureForTesting?.invoke()
            AutomaticKeywordObservationCoverage(
                context,
                db,
                allowDuringRestore = true,
            ).reconcile()
            db.automaticKeywordRuleDao.getAllEnabledRules()
                .filter { it.pendingApplyToExisting }
                .forEach { rule ->
                    val operation = AutomaticKeywordRuleScheduler.enqueue(
                        context,
                        rule.id,
                        AutomaticKeywordRuleScheduler.Mode.APPLY_EXISTING,
                        allowDuringRestore = true,
                    ) ?: error("Automatic keyword scheduling was not admitted")
                    operation.result.get(
                        QUIESCENCE_QUERY_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS,
                    )
                }
        }
        val downloadRepository = DownloadRepository(db)
        // A Reset containing only persistent states (for example paused,
        // cancelled, errored, or saved) must not manufacture runnable work.
        // Reconstruct scheduler state only when the backup carried a runnable
        // queue/schedule category, preserving absent-category semantics.
        val hasRunnableDownloadReset = data.queued != null || data.scheduled != null
        if (hasRunnableDownloadReset) {
            val queued = db.downloadDao.getQueuedDownloadsList()
                .filter { it.status == DownloadRepository.Status.Queued.name }
            val scheduled = downloadRepository.getScheduledDownloads()
            val runnableItems = (queued + scheduled).distinctBy { it.id }
            if (runnableItems.isNotEmpty()) {
                downloadSchedulingFailureForTesting?.invoke()
                check(
                    downloadRepository.startDownloadWorker(
                        runnableItems,
                        context,
                        allowDuringRestore = true,
                        awaitAcceptance = true,
                    ).isSuccess
                ) {
                    "Download scheduling reconciliation was not accepted"
                }
            }
        }
        if (data.settings != null) {
            CleanupScheduleCoordinator.reconcile(context)
        }
    }

    private const val QUIESCENCE_TIMEOUT_MS = 30_000L
    private const val QUIESCENCE_QUERY_TIMEOUT_MS = 5_000L
    private const val QUIESCENCE_POLL_MS = 100L
}
