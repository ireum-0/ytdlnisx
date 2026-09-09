package com.ireum.ytdl.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.LogItem
import com.ireum.ytdl.database.repository.LogRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.ui.more.terminal.TerminalActivity
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.SensitiveTextRedactor
import com.ireum.ytdl.util.extractors.ytdlp.YoutubeDLCompat
import com.ireum.ytdl.util.extractors.ytdlp.YtdlpNativeProcessBarrier
import com.ireum.ytdl.util.terminal.TerminalCommandPlanFactory
import com.ireum.ytdl.util.storage.TerminalCacheOwnership
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.io.IOException
import java.util.UUID

internal object TerminalDownloadWorkerEffectTestHooks {
    /**
     * Replaces only the native Terminal result for production-boundary tests.
     * The worker still performs its normal plan, provenance, and exact move
     * path; a null result means the real native boundary is used.
     */
    @Volatile
    internal var ytdlpSuccessWithOutputDirectoryForTesting: ((Int, File) -> String?)? = null

    /** Observes the real worker immediately before its native/output seam. */
    @Volatile
    internal var beforeYtdlpExecutionForTesting: ((Int, File?) -> Unit)? = null

    /** Optional full response seam so no-cache/no-output paths can be wired. */
    @Volatile
    internal var ytdlpResponseForTesting: ((Int, File?) -> String?)? = null
}


class TerminalDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    private var itemId : Int = 0
    private var shouldCleanupTerminalCache = false
    private var terminalOutputDirectory: File? = null
    private var terminalOutputAuthority: TerminalOutputAuthority? = null
    private var terminalTaskToken: String? = null
    private var terminalPublicationJournal: PublicationRecoveryJournal.Handle? = null
    private val terminalPublishedOutputPaths = mutableListOf<String>()
    /** Set once the Terminal row has been durably deleted after publication. */
    private var terminalSemanticCommit = false

    private fun cleanupTerminalOutputDirectory() {
        val directory = terminalOutputDirectory ?: return
        val token = terminalTaskToken
        if (
            token != null &&
            (terminalPublicationJournal != null || TerminalCacheOwnership.artifactManifestFile(directory).isFile)
        ) {
            val journalSnapshot = terminalPublicationJournal?.snapshot()
            val unknownQuarantine = journalSnapshot?.let { record ->
                record.phase == PublicationRecoveryJournal.Phase.QUARANTINED_UNKNOWN ||
                    record.artifacts.any {
                        PublicationRecoveryJournal.isUnknownReservation(it.reservedDestinationPath) ||
                            PublicationRecoveryJournal.isReservationIntent(it.reservedDestinationPath)
                    }
            } == true
            // Persist the quarantine carrier before revoking live ownership.
            // Marker-revoked state is recovery-only and is never an ordinary
            // cache-import OwnedRoot.
            if (!TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = directory,
                    taskToken = token,
                    publishedDestinationPaths = terminalPublishedOutputPaths +
                        journalSnapshot?.publishedDestinations().orEmpty(),
                    phase = if (unknownQuarantine) {
                        PublicationRecoveryJournal.Phase.QUARANTINED_UNKNOWN.name
                    } else {
                        "PARTIAL_PUBLICATION"
                    },
                    subjectId = itemId.toString(),
                )
            ) {
                Log.w(
                    TAG,
                    "Could not persist Terminal recovery carrier; retaining live marker: " +
                        directory.absolutePath,
                )
                return
            }
        }
        // Failure/cancellation cleanup must not erase an exact remainder left
        // by a partial publication. Revoke this attempt's marker and retain
        // its manifest/files as recovery evidence; the UUID-scoped directory
        // and missing marker keep a later attempt from gaining authority.
        val removed = TerminalCacheOwnership.revokeOwnershipPreservingArtifacts(
            directory,
            terminalTaskToken,
        )
        if (!removed && directory.exists()) {
            Log.w(
                TAG,
                "Preserving Terminal staging without exact artifact ownership: ${directory.absolutePath}",
            )
        }
        // The worker is itself a production recovery owner.  Resolve the
        // journal/carrier immediately when possible; App startup repeats the
        // same exact reconciliation after process death.
        reconcileTerminalPublicationRecovery()
    }

    private fun reconcileTerminalPublicationRecovery() {
        runCatching {
            TerminalExecutionRecovery.reconcile(
                context = context,
                activeExecution = { token -> TerminalExecutionRegistry.isActiveNow(token) },
            )
            TerminalPublicationRecovery.reconcile(
                context = context,
                cacheRoot = File(FileUtil.getCachePath(context)),
                activeExecution = { token -> TerminalExecutionRegistry.isActiveNow(token) },
            )
        }.onFailure { error ->
            Log.w(TAG, "Terminal publication recovery convergence deferred", error)
        }
    }

    /**
     * Retire a successfully committed Terminal attempt without recursive
     * deletion.  Known manifest/marker files are removed exactly; unknown
     * descendants remain and the marker is revoked so they cannot gain
     * publication authority.
     */
    private fun retireCommittedTerminalOutput() {
        val directory = terminalOutputDirectory ?: return
        val token = terminalTaskToken ?: return
        if (!directory.exists()) return
        if (!directory.isDirectory) {
            throw IOException("Terminal committed staging path is not a directory")
        }
        val marker = TerminalCacheOwnership.markerFile(directory)
        if (marker.isFile && TerminalCacheOwnership.isOwned(directory, token)) {
            if (!TerminalCacheOwnership.removeArtifactManifest(directory)) {
                throw IOException("Terminal committed artifact manifest could not be removed")
            }
            if (marker.exists() && !marker.delete() && marker.exists()) {
                throw IOException("Terminal committed ownership marker could not be removed")
            }
        } else if (marker.exists()) {
            throw IOException("Terminal committed ownership marker could not be verified")
        }
        if (directory.listFiles()?.isEmpty() == true) {
            directory.delete()
        }
    }

    private suspend fun cleanupStoppedWorker() = withContext(Dispatchers.IO + NonCancellable) {
        if (itemId == 0) return@withContext

        val token = terminalTaskToken
            ?: TerminalExecutionRecovery.read(context, itemId.toLong())?.executionToken
        if (token.isNullOrBlank()) {
            Log.w(TAG, "Stopped Terminal worker has no durable execution witness id=$itemId")
            return@withContext
        }
        // Persist the native-stop obligation before attempting destruction.
        // A false/unresolved destroy result keeps the row, witness, and any
        // cache ownership intact so a later recovery pass can retry the exact
        // generation; no new Terminal worker can be admitted meanwhile.
        if (!TerminalExecutionRecovery.convergeTerminal(
                context = context,
                subjectId = itemId.toLong(),
                executionToken = token,
                outcome = TerminalExecutionRecovery.Outcome.STOPPED,
            )
        ) {
            Log.w(TAG, "Terminal native quiescence unresolved; retaining durable owner id=$itemId")
            return@withContext
        }
        runCatching {
            NotificationUtil(context).cancelTerminalDownloadNotification(itemId)
        }
        if (shouldCleanupTerminalCache && !terminalSemanticCommit) {
            runCatching { cleanupTerminalOutputDirectory() }
        }
        val rowConverged = runCatching {
            val dao = DBManager.getInstance(context).terminalDao
            dao.delete(itemId.toLong())
            dao.getTerminalById(itemId.toLong()) == null
        }.getOrDefault(false)
        if (!rowConverged) {
            Log.w(TAG, "Terminal stop row convergence deferred id=$itemId")
        }
        Log.i(TAG, "Stopped terminal worker cleanup completed for itemId=$itemId converged=$rowConverged")
    }

    private suspend fun convergeTerminalOutcome(
        outcome: TerminalExecutionRecovery.Outcome,
    ): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        val token = terminalTaskToken
            ?: TerminalExecutionRecovery.read(context, itemId.toLong())?.executionToken
            ?: return@withContext false
        if (!TerminalExecutionRecovery.convergeTerminal(
                context = context,
                subjectId = itemId.toLong(),
                executionToken = token,
                outcome = outcome,
            )
        ) return@withContext false
        runCatching {
            val dao = DBManager.getInstance(context).terminalDao
            dao.delete(itemId.toLong())
        }
        true
    }

    override suspend fun doWork(): Result {
        return try {
            doWorkInternal()
        } finally {
            if (isStopped) {
                cleanupStoppedWorker()
            }
            TerminalExecutionRegistry.release(
                context,
                itemId.toLong(),
                terminalTaskToken ?: TerminalExecutionRecovery.read(context, itemId.toLong())?.executionToken,
            )
        }
    }

    private suspend fun doWorkInternal(): Result {
        itemId = inputData.getInt("id", 0)
        val command = inputData.getString("command")
        val dao = DBManager.getInstance(context).terminalDao
        if (itemId == 0) return Result.failure()
        if (command.isNullOrBlank()) return Result.failure()
        // A stale WorkManager request can outlive semantic Terminal row
        // convergence.  It has no subject authority once the row is gone and
        // must not recreate an execution merely from its copied input data.
        if (dao.getTerminalById(itemId.toLong()) == null) {
            Log.i(TAG, "Skipping Terminal request whose row is already converged id=$itemId")
            return Result.success()
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val terminalTaskToken = "$itemId-${UUID.randomUUID()}"
        val processId = YtdlpProcessIdentity.terminal(itemId.toLong())
        when (
            TerminalExecutionRegistry.admit(
                context = context,
                cacheRoot = File(FileUtil.getCachePath(context)),
                subjectId = itemId.toLong(),
                executionToken = terminalTaskToken,
                processId = processId,
            )
        ) {
            TerminalExecutionRegistry.Admission.BLOCKED -> {
                Log.w(TAG, "Terminal execution admission blocked by prior exact publication state id=$itemId")
                return Result.retry()
            }
            TerminalExecutionRegistry.Admission.ALREADY_COMMITTED -> {
                Log.i(TAG, "Terminal execution already committed; skipping duplicate native run id=$itemId")
                return Result.success()
            }
            TerminalExecutionRegistry.Admission.TERMINAL_FAILURE -> {
                Log.e(TAG, "Terminal execution stopped after an unknown provider publication outcome id=$itemId")
                return Result.failure()
            }
            TerminalExecutionRegistry.Admission.RECOVERY_FAILURE -> {
                Log.e(TAG, "Terminal execution could not establish durable recovery ownership id=$itemId")
                return Result.failure()
            }
            TerminalExecutionRegistry.Admission.ACQUIRED -> Unit
        }
        this.terminalTaskToken = terminalTaskToken
        // Recovery may have converged the queue row between the initial
        // input check and durable admission.  Re-check the subject before any
        // setup/native boundary; an input snapshot alone is not permission to
        // resurrect a semantically retired Terminal item.
        if (dao.getTerminalById(itemId.toLong()) == null) {
            TerminalExecutionRecovery.abandonAdmission(
                context = context,
                subjectId = itemId.toLong(),
                executionToken = terminalTaskToken,
            )
            Log.i(TAG, "Skipping Terminal execution converged during admission id=$itemId")
            return Result.success()
        }
        return try {
        val terminalPlan = TerminalCommandPlanFactory.create(
            context = context,
            preferences = sharedPreferences,
            command = command,
            taskId = terminalTaskToken,
        )
        val dbManager = DBManager.getInstance(context)
        val logRepo = LogRepository(dbManager.logDao)
        val notificationUtil = NotificationUtil(context)
        val handler = Handler(Looper.getMainLooper())
        val redactedCommand = SensitiveTextRedactor.redactCommand(terminalPlan.sanitizedConfig)
        val notificationTitle = SensitiveTextRedactor.safeNotificationTitle(redactedCommand)

        val intent = Intent(context, TerminalActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = notificationUtil.createDownloadServiceNotification(pendingIntent, notificationTitle, NotificationUtil.DOWNLOAD_TERMINAL_RUNNING_NOTIFICATION_ID)
        val foregroundInfo = if (Build.VERSION.SDK_INT >= 33) {
            ForegroundInfo(NotificationUtil.terminalNotificationId(itemId), notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }else{
            ForegroundInfo(NotificationUtil.terminalNotificationId(itemId), notification)
        }
        runCatching {
            setForeground(foregroundInfo)
            delay(500)
        }.getOrElse { error ->
            throw IOException("Failed to enter Terminal foreground", error)
        }

        val downloadLocation = terminalPlan.downloadLocation
        val removedOptionWarning = if (terminalPlan.removedOptions.isNotEmpty()) {
            "Warning: Removed unsafe or unsupported yt-dlp option(s): " +
                    terminalPlan.removedOptions.joinToString(", ") + "\n\n"
        } else {
            ""
        }
        val configFile = File(context.cacheDir.absolutePath + "/config-TERMINAL[${System.currentTimeMillis()}].txt").apply {
            writeText(terminalPlan.sanitizedConfig)
        }
        val request = terminalPlan.createRequest(configFile)
        val noCache = !terminalPlan.usesAppCache
        shouldCleanupTerminalCache = !noCache
        if (!noCache) {
            val outputDirectory = File(
                FileUtil.getCachePath(context),
                "TERMINAL/$terminalTaskToken",
            ).canonicalFile
            if (outputDirectory.exists() && outputDirectory.listFiles()?.isNotEmpty() == true) {
                throw IOException("Terminal attempt output directory was not clean")
            }
            if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
                throw IOException("Could not create Terminal attempt output directory")
            }
            runCatching {
                TerminalCacheOwnership.ensureMarker(outputDirectory, terminalTaskToken)
            }.getOrElse { error ->
                throw IOException("Could not establish Terminal cache ownership: ${error.message}", error)
            }
            terminalOutputDirectory = outputDirectory
            terminalOutputAuthority = TerminalOutputAuthority(
                stagingRoot = outputDirectory,
                ownershipMarker = TerminalCacheOwnership.markerFile(outputDirectory),
            ).also {
                it.beginAttempt()
            }
        }





        val logDownloads = sharedPreferences.getBoolean("log_downloads", false) && !sharedPreferences.getBoolean("incognito", false)

        val initialLogDetails = "Terminal Task\n" +
                "Command:\n${redactedCommand.trim()}\n\n" +
                removedOptionWarning
        val logItem = LogItem(
            0,
            "Terminal Task",
            initialLogDetails,
            Format(),
            DownloadType.command,
            System.currentTimeMillis(),
        )

        val eventBus = EventBus.getDefault()

        try {
            if (logDownloads){
                logItem.id = logRepo.insert(logItem)
            }
            if (removedOptionWarning.isNotBlank()) {
                Log.w(TAG, removedOptionWarning.trim())
                eventBus.post(DownloadWorker.WorkerProgress(0, removedOptionWarning.trim(), itemId.toLong(), logItem.id))
                dao.updateLog(removedOptionWarning, itemId.toLong())
            }

            if (!TerminalExecutionRecovery.markNativeStarted(
                    context = context,
                    subjectId = itemId.toLong(),
                    executionToken = requireNotNull(terminalTaskToken),
                )
            ) {
                throw IOException("Could not persist Terminal native-start responsibility")
            }
            TerminalDownloadWorkerEffectTestHooks.beforeYtdlpExecutionForTesting
                ?.invoke(itemId, terminalOutputDirectory)
            val injectedOutput = terminalOutputDirectory?.let { outputDirectory ->
                TerminalDownloadWorkerEffectTestHooks
                    .ytdlpSuccessWithOutputDirectoryForTesting
                    ?.invoke(itemId, outputDirectory)
            }
            val injectedResponseOutput = TerminalDownloadWorkerEffectTestHooks
                .ytdlpResponseForTesting
                ?.invoke(itemId, terminalOutputDirectory)
            val response = if (injectedResponseOutput != null) {
                YoutubeDLResponse(
                    emptyList(),
                    0,
                    0L,
                    injectedResponseOutput,
                    "",
                )
            } else if (injectedOutput != null) {
                YoutubeDLResponse(
                    emptyList(),
                    0,
                    0L,
                    injectedOutput,
                    "",
                )
            } else {
                YoutubeDLCompat.execute(
                    applicationContext,
                    request,
                    processId,
                    true,
                    callback = { progress, _, line ->
                    val redactedLine = SensitiveTextRedactor.redactOutput(line)
                    eventBus.post(DownloadWorker.WorkerProgress(progress.toInt(), redactedLine, itemId.toLong(), logItem.id))

                    notificationUtil.updateTerminalDownloadNotification(
                        itemId,
                        redactedLine, progress.toInt(), notificationTitle,
                        NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID
                    )
                    runBlocking(Dispatchers.IO) {
                        if (logDownloads) logRepo.update(redactedLine, logItem.id)
                        dao.updateLog(redactedLine, itemId.toLong())
                    }
                    },
                    onProcessRegistered = {
                        val generationToken = YtdlpNativeProcessBarrier.generationTokenFor(processId)
                            ?: throw IOException("Terminal native generation was not published")
                        if (!TerminalExecutionRecovery.bindNativeGeneration(
                                context = context,
                                subjectId = itemId.toLong(),
                                executionToken = requireNotNull(terminalTaskToken),
                                generationToken = generationToken,
                            )
                        ) {
                            throw IOException("Terminal native generation responsibility could not be persisted")
                        }
                    },
                )
            }

            if (!TerminalExecutionRecovery.markNativeFinished(
                    context = context,
                    subjectId = itemId.toLong(),
                    executionToken = requireNotNull(terminalTaskToken),
                )
            ) {
                throw IOException("Could not persist Terminal native completion")
            }

            withContext(Dispatchers.IO) {
                if(!noCache){
                    val authority = requireNotNull(terminalOutputAuthority)
                    val outputDirectory = requireNotNull(terminalOutputDirectory)
                    val structuredMarker = terminalPlan.outputAuthorityMarkerPath
                        ?.let(::File)
                        ?.takeUnless { injectedOutput != null }
                    val sourceFiles = if (
                        terminalPlan.outputExpectation == com.ireum.ytdl.util.terminal.TerminalOutputExpectation.NO_FILES_EXPECTED
                    ) {
                        emptyList()
                    } else {
                        requireTerminalSourceFiles(
                            authority = authority,
                            output = response.out,
                            structuredMarker = structuredMarker,
                        )
                    }
                    if (sourceFiles.isNotEmpty()) {
                        check(
                            TerminalCacheOwnership.recordArtifacts(
                                directory = outputDirectory,
                                files = sourceFiles.map { it.absolutePath },
                            )
                        ) {
                            "Could not persist Terminal current-attempt output ownership"
                        }
                    }
                    if (!authority.removeStructuredMarker(structuredMarker)) {
                        throw IOException("Terminal output authority marker could not be removed")
                    }
                    // The ownership marker proves the staging root, not an
                    // output file. Remove it only after the exact-output and
                    // unproven-child checks so cleanup can still validate the
                    // current operation.
                    if (
                        terminalPlan.outputExpectation == com.ireum.ytdl.util.terminal.TerminalOutputExpectation.NO_FILES_EXPECTED
                    ) {
                        if (!TerminalCacheOwnership.removeArtifactManifest(outputDirectory)) {
                            throw IOException("Terminal artifact manifest could not be removed")
                        }
                        if (authority.hasUnprovenTemporaryArtifacts()) {
                            throw IOException("Terminal no-output command produced an unproven staging artifact")
                        }
                        val ownershipMarker = TerminalCacheOwnership.markerFile(outputDirectory)
                        if (ownershipMarker.exists() && !ownershipMarker.delete() && ownershipMarker.exists()) {
                            throw IOException("Terminal cache ownership marker could not be removed")
                        }
                        outputDirectory.deleteRecursively()
                        return@withContext
                    }
                    terminalPublicationJournal = PublicationRecoveryJournal.begin(
                        context = context,
                        kind = PublicationRecoveryJournal.Kind.TERMINAL,
                        subjectId = itemId.toString(),
                        operationId = "terminal-$itemId",
                        executionId = requireNotNull(terminalTaskToken),
                        attemptId = requireNotNull(terminalTaskToken),
                        sourceRoot = outputDirectory,
                        sourceFiles = sourceFiles,
                    ) ?: throw IOException("Could not persist Terminal publication recovery journal")
                    check(
                        terminalPublicationJournal?.markPhase(PublicationRecoveryJournal.Phase.PUBLISHING) == true
                    ) {
                        "Could not advance Terminal publication recovery journal"
                    }
                    var publicationJournalWriteFailed = false
                    val movedOutputPaths = mutableListOf<String>()
                    val returnedMovePaths = FileUtil.moveFile(
                        originDir = outputDirectory,
                        context = context,
                        destDir = downloadLocation,
                        keepCache = false,
                        progress = { p ->
                            eventBus.post(DownloadWorker.WorkerProgress(p, "", itemId.toLong(), logItem.id))
                        },
                        sourceFiles = sourceFiles,
                        onOutput = { path -> movedOutputPaths.add(path) },
                        onOutputReservationIntent = { source ->
                            val reserved = terminalPublicationJournal?.reserveIntent(source.absolutePath) == true
                            if (!reserved) publicationJournalWriteFailed = true
                            reserved
                        },
                        onOutputReservationUnknown = { source ->
                            val marked = terminalPublicationJournal?.markReservationUnknown(source.absolutePath) == true
                            if (!marked) publicationJournalWriteFailed = true
                            marked
                        },
                        onOutputReservationRolledBack = { source ->
                            val cleared = terminalPublicationJournal?.clearReservation(source.absolutePath) == true
                            if (!cleared) publicationJournalWriteFailed = true
                            cleared
                        },
                        onOutputReserved = { source, path ->
                            if (terminalPublicationJournal?.reserve(source.absolutePath, path) != true) {
                                publicationJournalWriteFailed = true
                                throw IOException(
                                    "Terminal publication recovery destination could not be reserved"
                                )
                            }
                        },
                        onOutputCommitted = { source, path ->
                            val committed = terminalPublicationJournal?.markPublished(
                                source.absolutePath,
                                path,
                            ) == true
                            if (!committed) publicationJournalWriteFailed = true
                            committed
                        },
                        onOutputWithSource = { source, path ->
                            terminalPublishedOutputPaths += path
                            if (terminalPublicationJournal?.markPublished(
                                    source.absolutePath,
                                    path,
                                ) != true
                            ) {
                                publicationJournalWriteFailed = true
                            }
                        },
                    )
                    if (publicationJournalWriteFailed) {
                        throw IOException("Terminal publication recovery journal could not be advanced")
                    }
                    val exactPublishedPaths = authority.recordMoveResults(
                        movedPaths = movedOutputPaths + returnedMovePaths,
                        sourceFiles = sourceFiles,
                    )
                    if (exactPublishedPaths.isEmpty()) {
                        throw IOException("Terminal move completed without an authoritative output path")
                    }
                    val strandedSources = sourceFiles.filter { it.exists() }
                    if (strandedSources.isNotEmpty()) {
                        throw IOException(
                            "Terminal move left current-attempt outputs in staging: " +
                                strandedSources.joinToString(limit = 5) { it.name },
                        )
                    }
                    if (!TerminalCacheOwnership.removeArtifactManifest(outputDirectory)) {
                        throw IOException("Terminal artifact manifest could not be removed")
                    }
                    if (authority.hasUnprovenTemporaryArtifacts()) {
                        throw IOException("Terminal staging contains unproven output artifacts")
                    }
                    check(
                        terminalPublicationJournal?.markPhase(
                            PublicationRecoveryJournal.Phase.COMMITTING,
                        ) == true
                    ) {
                        "Terminal publication semantic commit could not be recorded"
                    }
                }
            }
            val redactedOutput = SensitiveTextRedactor.redactOutput(response.out)
            if (logDownloads) logRepo.update(initialLogDetails + redactedOutput, logItem.id, true)
            dao.updateLog(redactedOutput, itemId.toLong())
            notificationUtil.cancelTerminalDownloadNotification(itemId)
            delay(1000)
            if (!TerminalExecutionRecovery.markCommitting(
                    context = context,
                    subjectId = itemId.toLong(),
                    executionToken = requireNotNull(terminalTaskToken),
                )
            ) {
                throw IOException("Terminal semantic commit responsibility could not be persisted")
            }
            dao.delete(itemId.toLong())
            if (dao.getTerminalById(itemId.toLong()) != null) {
                throw IOException("Terminal semantic row deletion could not be confirmed")
            }
            // From this point the semantic Terminal outcome is committed.
            // Later marker/journal retirement is convergence debt and must
            // not escape as a contradictory WorkManager failure.
            terminalSemanticCommit = true
            if (!TerminalExecutionRecovery.markCommitted(
                    context = context,
                    subjectId = itemId.toLong(),
                    executionToken = requireNotNull(terminalTaskToken),
                )
            ) {
                // COMMITTING is itself a durable idempotence witness. Keep it
                // for startup convergence rather than turning a committed
                // Terminal result into a contradictory Worker failure.
                Log.w(TAG, "Terminal execution tombstone finalization deferred id=$itemId")
            }
            terminalPublicationJournal?.let { journal ->
                check(journal.markPhase(PublicationRecoveryJournal.Phase.COMMITTED)) {
                    "Terminal publication semantic commit could not be finalized"
                }
                retireCommittedTerminalOutput()
                // Retain the COMMITTED journal as a durable idempotence
                // tombstone until a later worker admission consumes it.  An
                // App-start recovery pass may retire the staging root, but
                // must not erase the only proof before WorkManager records
                // this invocation's success.
            }
            return Result.success()
        } catch (it: Exception) {
            if (terminalSemanticCommit) {
                // The exact publication and DAO semantic commit already won.
                // Reconcile best-effort and report success even when a
                // cleanup/marker/journal sidecar failed afterwards.
                reconcileTerminalPublicationRecovery()
                return Result.success()
            }
            if (isStopped) {
                notificationUtil.cancelTerminalDownloadNotification(itemId)
                // cleanupStoppedWorker() owns the durable stop/quiescence
                // protocol from doWork's NonCancellable finally block.
                Log.i(TAG, "Terminal worker stop requested; deferring convergence id=$itemId")
                return Result.failure()
            }
            if (it is YoutubeDL.CanceledException) {
                val converged = convergeTerminalOutcome(TerminalExecutionRecovery.Outcome.STOPPED)
                if (converged && !noCache) {
                    cleanupTerminalOutputDirectory()
                }
                reconcileTerminalPublicationRecovery()
                Log.i(TAG, "Terminal worker cancelled id=$itemId converged=$converged")
                return if (converged) Result.success() else Result.failure()
            }
            val redactedMessage = it.message?.let { message ->
                SensitiveTextRedactor.redactOutput(message)
            }
            val userMessage = redactedMessage ?: it::class.java.simpleName
            handler.postDelayed({
                Toast.makeText(context, userMessage, Toast.LENGTH_SHORT).show()
            }, 1000)
            if (redactedMessage != null){
                if (logDownloads) logRepo.update(redactedMessage, logItem.id)
                dao.updateLog(redactedMessage, itemId.toLong())
            }
            notificationUtil.cancelTerminalDownloadNotification(itemId)
            val converged = convergeTerminalOutcome(TerminalExecutionRecovery.Outcome.FAILURE)
            if (converged && !noCache) {
                cleanupTerminalOutputDirectory()
            }
            Log.e(TAG, "${context.getString(R.string.failed_download)} $userMessage")
            delay(1000)
            reconcileTerminalPublicationRecovery()
            return Result.failure()
        } finally {
            FileUtil.deleteConfigFiles(request)
        }
        Result.success()
        } catch (setupFailure: Exception) {
            // Admission already established a durable execution witness. Any
            // setup failure before the handled execution region must still
            // converge through that witness; it may never escape through the
            // outer finally with only the process-local token remaining.
            val converged = convergeTerminalOutcome(TerminalExecutionRecovery.Outcome.FAILURE)
            if (converged && shouldCleanupTerminalCache) {
                runCatching { cleanupTerminalOutputDirectory() }
            }
            Log.e(TAG, "Terminal setup failed after durable admission id=$itemId", setupFailure)
            Result.failure()
        }
    }

    companion object {
        const val TAG = "DownloadWorker"
    }

}
