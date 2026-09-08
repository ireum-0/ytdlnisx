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
            // Persist the quarantine carrier before revoking live ownership.
            // Marker-revoked state is recovery-only and is never an ordinary
            // cache-import OwnedRoot.
            if (!TerminalCacheOwnership.recordRecoveryCarrier(
                    directory = directory,
                    taskToken = token,
                    publishedDestinationPaths = terminalPublishedOutputPaths +
                        terminalPublicationJournal?.snapshot()?.publishedDestinations().orEmpty(),
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
            TerminalPublicationRecovery.reconcile(
                context = context,
                cacheRoot = File(FileUtil.getCachePath(context)),
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

        val processId = YtdlpProcessIdentity.terminal(itemId.toLong())
        YoutubeDL.getInstance().destroyProcessById(processId)
        YoutubeDLCompat.destroyProcessById(processId)
        runCatching {
            NotificationUtil(context).cancelTerminalDownloadNotification(itemId)
        }
        if (shouldCleanupTerminalCache && !terminalSemanticCommit) {
            runCatching { cleanupTerminalOutputDirectory() }
        }
        runCatching {
            DBManager.getInstance(context).terminalDao.delete(itemId.toLong())
        }
        Log.i(TAG, "Stopped terminal worker cleanup completed for itemId=$itemId")
    }

    override suspend fun doWork(): Result {
        return try {
            doWorkInternal()
        } finally {
            if (isStopped) {
                cleanupStoppedWorker()
            }
            TerminalExecutionRegistry.release(itemId.toLong(), terminalTaskToken)
        }
    }

    private suspend fun doWorkInternal(): Result {
        itemId = inputData.getInt("id", 0)
        val command = inputData.getString("command")
        val dao = DBManager.getInstance(context).terminalDao
        if (itemId == 0) return Result.failure()
        if (command.isNullOrBlank()) return Result.failure()

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val terminalTaskToken = "$itemId-${UUID.randomUUID()}"
        when (
            TerminalExecutionRegistry.admit(
                context = context,
                cacheRoot = File(FileUtil.getCachePath(context)),
                subjectId = itemId.toLong(),
                executionToken = terminalTaskToken,
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
            TerminalExecutionRegistry.Admission.ACQUIRED -> Unit
        }
        this.terminalTaskToken = terminalTaskToken
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
        }.onFailure {
            Log.e(TAG, "Failed to enter foreground", it)
            return Result.retry()
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

            val processId = YtdlpProcessIdentity.terminal(itemId.toLong())
            YoutubeDL.getInstance().destroyProcessById(processId)
            YoutubeDLCompat.destroyProcessById(processId)
            val injectedOutput = terminalOutputDirectory?.let { outputDirectory ->
                TerminalDownloadWorkerEffectTestHooks
                    .ytdlpSuccessWithOutputDirectoryForTesting
                    ?.invoke(itemId, outputDirectory)
            }
            val response = if (injectedOutput != null) {
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
                )
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
            dao.delete(itemId.toLong())
            // From this point the semantic Terminal outcome is committed.
            // Later marker/journal retirement is convergence debt and must
            // not escape as a contradictory WorkManager failure.
            terminalSemanticCommit = true
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
            if (isStopped || it is YoutubeDL.CanceledException) {
                notificationUtil.cancelTerminalDownloadNotification(itemId)
                if (!noCache) {
                    cleanupTerminalOutputDirectory()
                }
                runCatching {
                    dao.delete(itemId.toLong())
                }
                reconcileTerminalPublicationRecovery()
                Log.i(TAG, "Terminal worker stopped or cancelled itemId=$itemId")
                return Result.success()
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
            if (!noCache) {
                cleanupTerminalOutputDirectory()
            }
            Log.e(TAG, "${context.getString(R.string.failed_download)} $userMessage")
            delay(1000)
            dao.delete(itemId.toLong())
            reconcileTerminalPublicationRecovery()
            return Result.failure()
        } finally {
            FileUtil.deleteConfigFiles(request)
        }
        return Result.success()
    }

    companion object {
        const val TAG = "DownloadWorker"
    }

}
