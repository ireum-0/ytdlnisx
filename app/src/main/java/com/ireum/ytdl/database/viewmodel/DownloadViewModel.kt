package com.ireum.ytdl.database.viewmodel

import android.app.Application
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Parcelable
import android.util.DisplayMetrics
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.preference.PreferenceManager
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ireum.ytdl.App
import com.ireum.ytdl.R
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.dao.CommandTemplateDao
import com.ireum.ytdl.database.dao.DownloadDao
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.CommandTemplate
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.DownloadItemConfigureMultiple
import com.ireum.ytdl.database.models.DownloadItemSimple
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.LowQualityRedownloadItemState
import com.ireum.ytdl.database.models.PendingUndoResolutionIntent
import com.ireum.ytdl.database.models.ResultItem
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.repository.HistoryRepository
import com.ireum.ytdl.database.repository.HistoryReplacementDiagnostic
import com.ireum.ytdl.database.repository.HistoryRedownloadItemFactory
import com.ireum.ytdl.database.repository.ResultRepository
import com.ireum.ytdl.ui.downloadcard.MultipleItemFormatTuple
import com.ireum.ytdl.util.Extensions.getIDFromYoutubeURL
import com.ireum.ytdl.util.Extensions.isYoutubeURL
import com.ireum.ytdl.util.Extensions.needsDataUpdating
import com.ireum.ytdl.util.DownloadConfigurationDuplicatePolicy
import com.ireum.ytdl.util.Extensions.toListString
import com.ireum.ytdl.util.FileUtil
import com.ireum.ytdl.util.FormatUtil
import com.ireum.ytdl.util.HistoryRedownloadMarker
import com.ireum.ytdl.util.HistoryRedownloadQueuePolicy
import com.ireum.ytdl.util.LinkUtil
import com.ireum.ytdl.util.LowQualityReplacementAuthority
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.SubtitleLanguageMatcher
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.util.download.DownloadRetryBlockReason
import com.ireum.ytdl.util.download.DownloadRetryDecision
import com.ireum.ytdl.util.download.DownloadRetryItemState
import com.ireum.ytdl.util.download.DownloadRetryMetadata
import com.ireum.ytdl.util.download.DownloadRetryPolicy
import com.ireum.ytdl.util.download.DownloadRetryStrategy
import com.ireum.ytdl.util.download.supportsSameSettingsRetry
import com.ireum.ytdl.util.extractors.ytdlp.YTDLPUtil
import com.ireum.ytdl.util.preset.DownloadPreset
import com.ireum.ytdl.util.preset.DownloadPresetMapper
import com.ireum.ytdl.util.preset.DownloadPresetStore
import com.ireum.ytdl.work.AlarmScheduler
import com.ireum.ytdl.work.DownloadExecutionRecovery
import com.ireum.ytdl.work.DownloadWorker
import com.ireum.ytdl.work.LowQualityRedownloadLedger
import com.ireum.ytdl.work.UpdateMultipleDownloadsDataWorker
import com.ireum.ytdl.work.UpdateMultipleDownloadsFormatsWorker
import com.ireum.ytdl.work.withDownloadWorkerExecutionSideEffectLease
import com.ireum.ytdl.work.withDownloadWorkerExecutionLock
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.parcelize.Parcelize
import java.io.File
import java.util.Locale
import java.util.UUID


class DownloadViewModel private constructor(
    private val application: Application,
    private val databaseOverride: DBManager?,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, null)

    /** Test-only constructor that preserves the production object graph. */
    internal constructor(
        application: Application,
        database: DBManager,
        @Suppress("UNUSED_PARAMETER") testOnly: Boolean,
    ) :
        this(application, database)

    private companion object {
        const val DUP_LOG_TAG = "DuplicateCheck"
    }

    private val dbManager: DBManager
    val repository : DownloadRepository
    private val sharedPreferences: SharedPreferences
    private val commandTemplateDao: CommandTemplateDao
    private val formatUtil = FormatUtil(application)
    private val notificationUtil = NotificationUtil(application)
    private val ytdlpUtil: YTDLPUtil
    private val resources : Resources
    private val downloadPresetStore: DownloadPresetStore
    private var legacyUndoPresentationOwner: DownloadRepository.UndoPresentationOwner? = null

    override fun onCleared() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            repository.abandonPendingUndoSnapshots()
        }
        super.onCleared()
    }

    /** Invokes the production lifecycle handoff for deterministic instrumentation coverage. */
    internal suspend fun clearForTesting() {
        // Mirror the lifecycle-owned cancellation that accompanies
        // ViewModel.onCleared(), while retaining the production handoff seam
        // used by these deterministic tests.
        viewModelScope.cancel()
        viewModelScope.coroutineContext[Job]?.join()
        onCleared()
    }

    /** Transfers only the exact capabilities issued to this Fragment view. */
    internal fun abandonPendingUndoCapabilitiesForView(
        owner: DownloadRepository.UndoPresentationOwner,
    ) {
        repository.abandonPendingUndoCapabilitiesForView(owner)
    }

    /** Compatibility overload for older callers; production views retain the returned handle. */
    internal fun abandonPendingUndoCapabilitiesForView() {
        repository.abandonPendingUndoCapabilitiesForView()
    }

    /** Gives a recreated Fragment view a distinct process-local Undo owner. */
    internal fun beginUndoPresentationOwner(): DownloadRepository.UndoPresentationOwner {
        return repository.beginUndoPresentationOwner().also {
            // Only the compatibility overloads use this slot.  Production
            // callers pass the returned immutable handle at every boundary.
            legacyUndoPresentationOwner = it
        }
    }

    /** A Snackbar becomes positive UI authority only after its onShown callback. */
    internal fun acknowledgeUndoPublication(token: String) {
        repository.acknowledgeUndoPublication(token)
    }

    internal fun acknowledgeUndoPublication(
        token: String,
        owner: DownloadRepository.UndoPresentationOwner,
    ) {
        repository.acknowledgeUndoPublication(token, owner)
    }

    /** Prevents a producer from publishing into a recreated/dead view. */
    internal fun isUndoPresentationOwnerActive(
        owner: DownloadRepository.UndoPresentationOwner,
    ): Boolean = repository.isUndoPresentationOwnerActive(owner)

    /** Closes the exact producer-to-Snackbar handoff on consumer failure. */
    internal fun abandonUndoCapabilityAfterConsumerFailure(token: String) {
        repository.abandonUndoCapabilityAfterProducerFailure(token)
    }

    /** Re-delivers a removal capability when durable intent acceptance failed. */
    internal fun reofferRemovalUndoCapabilityAfterResolutionFailure(
        token: String,
        intent: PendingUndoResolutionIntent,
        owner: DownloadRepository.UndoPresentationOwner,
    ): Boolean {
        return try {
            runBlocking(Dispatchers.IO) {
                repository.reofferRemovalUndoCapabilityAfterResolutionFailure(
                    token = token,
                    intent = intent,
                    owner = owner,
                )
            }
        } catch (failure: Throwable) {
            repository.abandonUndoCapabilityAfterProducerFailure(token)
            false
        }
    }

    /** Re-delivers a cancellation capability when durable intent acceptance failed. */
    internal fun reofferCancellationUndoCapabilityAfterResolutionFailure(
        token: String,
        intent: PendingUndoResolutionIntent,
        owner: DownloadRepository.UndoPresentationOwner,
    ): Boolean {
        return try {
            runBlocking(Dispatchers.IO) {
                repository.reofferCancellationUndoCapabilityAfterResolutionFailure(
                    token = token,
                    intent = intent,
                    owner = owner,
                )
            }
        } catch (failure: Throwable) {
            repository.abandonUndoCapabilityAfterProducerFailure(token)
            false
        }
    }

    private fun legacyUndoOwner(): DownloadRepository.UndoPresentationOwner =
        legacyUndoPresentationOwner ?: beginUndoPresentationOwner()

    val allDownloads : Flow<PagingData<DownloadItem>>
    val queuedDownloads : Flow<PagingData<DownloadItemSimple>>
    val activeDownloads : Flow<List<DownloadItem>>
    val activePausedDownloads : Flow<List<DownloadItem>>
    val processingDownloads : Flow<List<DownloadItemConfigureMultiple>>
    val cancelledDownloads : Flow<PagingData<DownloadItemSimple>>
    val erroredDownloads : Flow<PagingData<DownloadItemSimple>>
    val savedDownloads : Flow<PagingData<DownloadItemSimple>>
    val scheduledDownloads : Flow<PagingData<DownloadItemSimple>>

    val activeDownloadsCount : Flow<Int>
    val activePausedDownloadsCount : Flow<Int>
    val queuedDownloadsCount : Flow<Int>
    val pausedDownloadsCount : Flow<Int>
    val cancelledDownloadsCount : Flow<Int>
    val erroredDownloadsCount : Flow<Int>
    val savedDownloadsCount : Flow<Int>
    val scheduledDownloadsCount : Flow<Int>
    val pausedAllDownloads = MediatorLiveData(PausedAllDownloadsState.HIDDEN)
    private val pausedAllDownloadsFlow : Flow<PausedAllDownloadsState>
    private var isPausingResuming = false
    enum class PausedAllDownloadsState {
        PAUSE, RESUME, PROCESSING, HIDDEN
    }

    @Parcelize
    data class AlreadyExistsIDs(
        var downloadItemID: Long,
        var historyItemID : Long?
    ) : Parcelable

    val alreadyExistsUiState: MutableStateFlow<List<AlreadyExistsIDs>> = MutableStateFlow(
        mutableListOf()
    )

    private var extraCommandsForAudio: List<CommandTemplate> = listOf()
    private var extraCommandsForVideo: List<CommandTemplate> = listOf()

    private val dao: DownloadDao
    private val historyRepository: HistoryRepository
    private val resultRepository: ResultRepository

    private val urlsForAudioType = listOf(
        "music",
        "audio",
        "soundcloud"
    )

    var processingItems = MutableStateFlow(false)
    var processingItemsJob : Job? = null
    var processingSort = MutableStateFlow("ASC")

    init {
        dbManager = databaseOverride ?: DBManager.getInstance(application)
        dao = dbManager.downloadDao
        commandTemplateDao = DBManager.getInstance(application).commandTemplateDao
        repository = DownloadRepository(dbManager)
        historyRepository = HistoryRepository(dbManager.historyDao, dbManager.playlistDao)
        resultRepository = ResultRepository(dbManager.resultDao, commandTemplateDao, application)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
        downloadPresetStore = DownloadPresetStore(application, sharedPreferences)
        ytdlpUtil = YTDLPUtil(application, commandTemplateDao)

        activeDownloadsCount = repository.activeDownloadsCount
        activePausedDownloadsCount = repository.activePausedDownloadsCount
        queuedDownloadsCount = repository.queuedDownloadsCount
        pausedDownloadsCount = repository.pausedDownloadsCount
        cancelledDownloadsCount = repository.cancelledDownloadsCount
        erroredDownloadsCount = repository.erroredDownloadsCount
        savedDownloadsCount = repository.savedDownloadsCount
        scheduledDownloadsCount = repository.scheduledDownloadsCount

        allDownloads = repository.allDownloads.flow.cachedIn(viewModelScope)
        queuedDownloads = repository.queuedDownloads.flow.cachedIn(viewModelScope)
        activeDownloads = repository.activeDownloads
        activePausedDownloads = repository.activePausedDownloads
        processingDownloads = repository.processingDownloads
        savedDownloads = repository.savedDownloads.flow.cachedIn(viewModelScope)
        scheduledDownloads = repository.scheduledDownloads.flow.cachedIn(viewModelScope)
        cancelledDownloads = repository.cancelledDownloads.flow.cachedIn(viewModelScope)
        erroredDownloads = repository.erroredDownloads.flow.cachedIn(viewModelScope)
        viewModelScope.launch(Dispatchers.IO){
            if (sharedPreferences.getBoolean("use_extra_commands", false)){
                extraCommandsForAudio = commandTemplateDao.getAllTemplatesAsExtraCommandsForAudio()
                extraCommandsForVideo = commandTemplateDao.getAllTemplatesAsExtraCommandsForVideo()
            }
        }

        pausedAllDownloadsFlow = combine(
            activeDownloadsCount,
            repository.runnableQueuedDownloadsCount,
            pausedDownloadsCount,
            repository.pausedDownloads,
        ) { active, queued, paused, pausedItems ->
            if (isPausingResuming) {
                return@combine PausedAllDownloadsState.PROCESSING
            }
            if (DownloadExecutionRecovery.pendingDownloadIds(application).isNotEmpty()) {
                return@combine PausedAllDownloadsState.HIDDEN
            }
            if (pausedItems.any { item ->
                    DownloadExecutionRecovery.hasPendingRecovery(application, item.id)
                }
            ) {
                return@combine PausedAllDownloadsState.HIDDEN
            }

            if (active == 0 && queued == 0 && paused == 0) {
                return@combine PausedAllDownloadsState.HIDDEN
            }else if (paused > 1 || (active == 0 && queued > 0) || (paused > 0 && active > 0)) {
                return@combine PausedAllDownloadsState.RESUME
            }else if (active > 1 || (active > 0 && queued > 0)) {
                return@combine PausedAllDownloadsState.PAUSE
            }else{
                return@combine PausedAllDownloadsState.HIDDEN
            }
        }

        pausedAllDownloads.addSource(pausedAllDownloadsFlow.asLiveData()) {
            pausedAllDownloads.value = it
        }

        val confTmp = Configuration(application.resources.configuration)
        val locale = if (Build.VERSION.SDK_INT < 33) {
            sharedPreferences.getString("app_language", "")!!.ifEmpty { Locale.getDefault().language }
        }else{
            Locale.getDefault().language
        }.run {
            split("-")
        }.run {
            if (this.size == 1) Locale(this[0]) else Locale(this[0], this[1])
        }
        confTmp.setLocale(locale)
        val metrics = DisplayMetrics()
        resources = Resources(application.assets, metrics, confTmp)
    }

    fun deleteDownload(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        deleteDownloadAndWait(id)
    }

    suspend fun deleteDownloadAndWait(id: Long) = withContext(Dispatchers.IO) {
        LowQualityRedownloadLedger.refresh(application, repository.delete(id))
        notificationUtil.cancelMembershipWaitingNotification(id)
    }

    suspend fun deleteDownloadForUndo(id: Long): DownloadRepository.DownloadUndoHandle? =
        deleteDownloadForUndo(id, legacyUndoOwner())

    suspend fun deleteDownloadForUndo(
        id: Long,
        owner: DownloadRepository.UndoPresentationOwner,
    ): DownloadRepository.DownloadUndoHandle? {
        var committedHandle: DownloadRepository.DownloadUndoHandle? = null
        return try {
            withContext(Dispatchers.IO) {
                val handle = repository.deleteForUndo(id, owner) ?: return@withContext null
                // Keep this outside the withContext success boundary.  If the
                // caller is cancelled while the dispatcher is returning the
                // result, the durable carrier still gets an exact successor.
                committedHandle = handle
                notificationUtil.cancelMembershipWaitingNotification(id)
                handle
            }
        } catch (cancelled: CancellationException) {
            committedHandle?.let { repository.abandonUndoCapabilityAfterProducerFailure(it.token.value) }
            throw cancelled
        } catch (failure: Throwable) {
            committedHandle?.let { repository.abandonUndoCapabilityAfterProducerFailure(it.token.value) }
            throw failure
        }
    }

    suspend fun restoreDownloadUndo(handle: DownloadRepository.DownloadUndoHandle): Long? =
        withContext(Dispatchers.IO) {
            val restoredId = repository.restoreUndo(handle.token, handle.owner)
            if (restoredId != null) {
                LowQualityRedownloadLedger.refresh(application, handle.affectedOperationIds)
            }
            restoredId
        }

    fun restoreDownloadUndoFromUi(handle: DownloadRepository.DownloadUndoHandle): Boolean {
        var reoffered = false
        val accepted = try {
            runBlocking(Dispatchers.IO) {
                val result = try {
                    repository.acceptRemovalUndoResolution(
                        handle.token.value,
                        PendingUndoResolutionIntent.RESTORE,
                        handle.owner,
                    )
                } catch (failure: Throwable) {
                    repository.abandonUndoCapabilityAfterProducerFailure(handle.token.value)
                    false
                }
                if (
                    !result &&
                        !repository.hasDurableUndoResolutionIntent(
                            handle.token.value,
                            PendingUndoResolutionIntent.RESTORE,
                        )
                ) {
                    reoffered = repository.reofferRemovalUndoCapabilityAfterResolutionFailure(
                        handle.token.value,
                        PendingUndoResolutionIntent.RESTORE,
                        handle.owner,
                    )
                }
                result
            }
        } catch (failure: Throwable) {
            repository.abandonUndoCapabilityAfterProducerFailure(handle.token.value)
            false
        }
        if (!accepted) return false
        viewModelScope.launch(Dispatchers.IO) {
            restoreDownloadUndo(handle)
        }
        return true
    }

    suspend fun commitDownloadUndo(handle: DownloadRepository.DownloadUndoHandle) =
        withContext(Dispatchers.IO) {
            LowQualityRedownloadLedger.refresh(
                application,
                repository.commitUndo(handle.token, handle.owner),
            )
        }

    fun commitDownloadUndoFromUi(handle: DownloadRepository.DownloadUndoHandle): Boolean {
        var reoffered = false
        val accepted = try {
            runBlocking(Dispatchers.IO) {
                val result = try {
                    repository.acceptRemovalUndoResolution(
                        handle.token.value,
                        PendingUndoResolutionIntent.COMMIT,
                        handle.owner,
                    )
                } catch (failure: Throwable) {
                    repository.abandonUndoCapabilityAfterProducerFailure(handle.token.value)
                    false
                }
                if (
                    !result &&
                        !repository.hasDurableUndoResolutionIntent(
                            handle.token.value,
                            PendingUndoResolutionIntent.COMMIT,
                        )
                ) {
                    reoffered = repository.reofferRemovalUndoCapabilityAfterResolutionFailure(
                        handle.token.value,
                        PendingUndoResolutionIntent.COMMIT,
                        handle.owner,
                    )
                }
                result
            }
        } catch (failure: Throwable) {
            repository.abandonUndoCapabilityAfterProducerFailure(handle.token.value)
            false
        }
        if (!accepted) return false
        viewModelScope.launch(Dispatchers.IO) {
            commitDownloadUndo(handle)
        }
        return true
    }

    suspend fun updateDownload(item: DownloadItem){
        if (item.status == DownloadRepository.Status.Cancelled.name) {
            withContext(Dispatchers.IO) {
                val expectedExecutionId = dao.getNullableDownloadById(item.id)?.executionId.orEmpty()
                var recoveryRecorded = false
                try {
                    withDownloadWorkerExecutionSideEffectLease(item.id, expectedExecutionId) {
                        val semanticResult = withDownloadWorkerExecutionLock {
                            val current = dao.getNullableDownloadById(item.id)
                            if (current == null || current.executionId != expectedExecutionId) {
                                return@withDownloadWorkerExecutionLock null
                            }
                            check(
                                DownloadExecutionRecovery.recordPending(
                                    context = application,
                                    item = current,
                                    disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                    phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
                                )
                            ) {
                                "Could not persist cancellation recovery responsibility for ${current.id}"
                            }
                            recoveryRecorded = true
                            repository.convergeUserStopSemantic(
                                id = item.id,
                                expectedExecutionId = expectedExecutionId,
                                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                            )
                        }
                        if (semanticResult != null) {
                            when (val outcome = semanticResult.outcome) {
                                DownloadRepository.UserStopSemanticOutcome.USER_STOP_COMMITTED,
                                DownloadRepository.UserStopSemanticOutcome.USER_STOP_ALREADY_SATISFIED -> {
                                    val operationIds = semanticResult.affectedOperationIds.toMutableSet()
                                    val quiesced = cancelDownloadOnlyOwned(
                                        id = item.id,
                                        expectedExecutionId = expectedExecutionId,
                                        recoveryRecorded = true,
                                        stopDisposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                    )
                                    if (sharedPreferences.getBoolean("incognito", false) && quiesced) {
                                        operationIds += repository.delete(item.id)
                                    }
                                    check(quiesced) {
                                        "Native quiescence remained unresolved for cancelled download ${item.id}"
                                    }
                                    LowQualityRedownloadLedger.refresh(application, operationIds)
                                }
                                DownloadRepository.UserStopSemanticOutcome.COMMITTED_HISTORY_ALREADY_WON -> {
                                    check(
                                        DownloadExecutionRecovery.convergeCommittedHistoryFinalization(
                                            context = application,
                                            dbManager = dbManager,
                                            downloadId = item.id,
                                            executionId = expectedExecutionId,
                                        )
                                    ) {
                                        "Committed History finalization remained unresolved for ${item.id}"
                                    }
                                }
                                DownloadRepository.UserStopSemanticOutcome.STRONGER_USER_CANCEL_ALREADY_WON -> {
                                    check(
                                        DownloadExecutionRecovery.convergeUserStopBeforeGenericCleanup(
                                            context = application,
                                            dbManager = dbManager,
                                            downloadId = item.id,
                                            executionId = expectedExecutionId,
                                        )
                                    ) {
                                        "Stronger Cancel convergence remained unresolved for ${item.id}"
                                    }
                                }
                                DownloadRepository.UserStopSemanticOutcome.OWNERSHIP_LOST -> Unit
                                is DownloadRepository.UserStopSemanticOutcome.RETRYABLE_PERSISTENCE_FAILURE -> {
                                    throw outcome.error
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (recoveryRecorded) {
                        DownloadExecutionRecovery.retainRecoveryResponsibility(
                            context = application,
                            downloadId = item.id,
                            dbManager = dbManager,
                            failure = cancelled,
                        )
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    if (recoveryRecorded) {
                        DownloadExecutionRecovery.retainRecoveryResponsibility(
                            context = application,
                            downloadId = item.id,
                            dbManager = dbManager,
                            failure = failure,
                        )
                    }
                    throw failure
                }
            }
            return
        }
        if (sharedPreferences.getBoolean("incognito", false)){
            if (item.status == DownloadRepository.Status.Error.toString()){
                LowQualityRedownloadLedger.transition(
                    application,
                    item.id,
                    LowQualityRedownloadItemState.FAILED,
                    "ITEM_FAILED"
                )
                LowQualityRedownloadLedger.refresh(application, repository.delete(item.id))
                return
            }
        }

        val persistedItem = if (item.id > 0L) {
            withContext(Dispatchers.IO) { dao.getNullableDownloadById(item.id) }
        } else {
            null
        }
        if (
            persistedItem != null &&
            (
                HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(persistedItem.lastIssueCode) ||
                    dbManager.historyReplacementBarrierDao
                        .getByDownloadIdBlocking(persistedItem.id) != null
                )
        ) {
            return
        }

        repository.update(item)
    }

    suspend fun putToSaved(item: DownloadItem) {
        val result = repository.saveForLater(item)
        LowQualityRedownloadLedger.refresh(application, result.affectedOperationIds)
        val id = result.downloadId
        if (item.needsDataUpdating()) {
            continueUpdatingDataInBackground(listOf(id))
        }
    }

    fun getItemByID(id: Long) : DownloadItem {
        return repository.getItemByID(id)
    }

    fun getAllByIDs(ids: List<Long>) : List<DownloadItem> {
        return repository.getAllItemsByIDs(ids)
    }

    fun getHistoryItemById(id: Long) : HistoryItem? {
        return historyRepository.getItem(id)
    }

    fun getDownloadType(t: DownloadType? = null, url: String) : DownloadType {
        var type = t

        if (type == null){
            val preferredDownloadType = sharedPreferences.getString("preferred_download_type", DownloadType.auto.toString())
            type = if (sharedPreferences.getBoolean("remember_download_type", false)){
                DownloadType.valueOf(sharedPreferences.getString("last_used_download_type",
                    preferredDownloadType)!!)
            }else{
                DownloadType.valueOf(preferredDownloadType!!)
            }
        }

        return when(type){
            DownloadType.auto -> {
                if (urlsForAudioType.any { url.contains(it) }){
                    DownloadType.audio
                }else{
                    DownloadType.video
                }
            }

            else -> type
        }
    }

    fun createDownloadItemFromResult(
        result: ResultItem?,
        url: String = "",
        givenType: DownloadType,
        presetId: String? = null,
        applyQuickDownloadPreset: Boolean = false
    ) : DownloadItem {
        val resultItem = result ?: createEmptyResultItem(url)
        val baseType = getDownloadType(givenType, resultItem.url)
        val requestedPreset = when {
            applyQuickDownloadPreset && baseType != DownloadType.command -> {
                downloadPresetStore.quickDownloadPreset()
            }
            presetId != null -> downloadPresetStore.preset(presetId)
            else -> null
        }

        val embedSubs = sharedPreferences.getBoolean("embed_subtitles", false)
        val saveSubs = sharedPreferences.getBoolean("write_subtitles", false)
        val saveAutoSubs = sharedPreferences.getBoolean("write_auto_subtitles", false)
        val recodeVideo = sharedPreferences.getBoolean("recode_video", false)
        val addChapters = sharedPreferences.getBoolean("add_chapters", false)
        val saveThumb = sharedPreferences.getBoolean("write_thumbnail", false)
        val embedThumb = sharedPreferences.getBoolean("embed_thumbnail", false)
        val videoEmbedThumb = sharedPreferences.getBoolean("video_embed_thumbnail", false)
        val cropThumb = sharedPreferences.getBoolean("crop_thumbnail", false)

        var type = if (applyQuickDownloadPreset && requestedPreset != null) {
            requestedPreset.configuration.type.toDownloadType()
        } else {
            baseType
        }
        if(type == DownloadType.command && commandTemplateDao.getTotalNumber() == 0) type = DownloadType.video

        val customFileNameTemplate = when(type) {
            DownloadType.audio -> sharedPreferences.getString("file_name_template_audio", "%(uploader).30B - %(title).170B")
            DownloadType.video -> sharedPreferences.getString("file_name_template", "%(uploader).30B - %(title).170B")
            else -> ""
        }

        val downloadPath = when(type){
            DownloadType.audio -> sharedPreferences.getString("music_path", FileUtil.getDefaultAudioPath())
            DownloadType.video -> sharedPreferences.getString("video_path",  FileUtil.getDefaultVideoPath())
            else -> sharedPreferences.getString("command_path", FileUtil.getDefaultCommandPath())
        }

        val container = when(type){
            DownloadType.audio -> sharedPreferences.getString("audio_format", "")
            else -> sharedPreferences.getString("video_format", "")
        }


        val sponsorblock = sharedPreferences.getStringSet("sponsorblock_filters", emptySet())
        val bitrate = sharedPreferences.getString("audio_bitrate", "")

        val audioPreferences = AudioPreferences(embedThumb, cropThumb,false, ArrayList(sponsorblock!!), bitrate!!)


        val preferredAudioFormats = getPreferredAudioFormats(resultItem.formats)
        val subsLanguages = sharedPreferences.getString("subs_lang", "en.*,.*-orig")!!

        val videoPreferences = VideoPreferences(
            embedSubs,
            addChapters, false,
            ArrayList(sponsorblock),
            saveSubs,
            saveAutoSubs,
            subsLanguages,
            audioFormatIDs = preferredAudioFormats,
            recodeVideo = recodeVideo,
            embedThumbnail = videoEmbedThumb
        )

        val extraCommands = siteExtraCommands(type, resultItem.url)

        val downloadItem = DownloadItem(0,
            resultItem.url,
            resultItem.title,
            resultItem.author,
            resultItem.thumb,
            resultItem.duration,
            type,
            getFormat(resultItem.formats, type, resultItem.url),
            container!!,
            "",
            resultItem.formats.toMutableList(),
            downloadPath!!, resultItem.website,
            "",
            if (resultItem.playlistTitle == resultRepository.YTDLNIS_SEARCH) "" else resultItem.playlistTitle,
            audioPreferences,
            videoPreferences,
            extraCommands,
            customFileNameTemplate!!,
            saveThumb,
            DownloadRepository.Status.Queued.toString(),
            0,
            null,
            playlistURL = resultItem.playlistURL,
            playlistIndex = resultItem.playlistIndex,
            incognito = sharedPreferences.getBoolean("incognito", false),
            availableSubtitles = resultItem.availableSubtitles,
            mediaPublishedAt = resultItem.mediaPublishedAt
        )
        val shouldApplyPreset = requestedPreset != null && (
            applyQuickDownloadPreset || requestedPreset.configuration.type.toDownloadType() == downloadItem.type
        )
        return if (shouldApplyPreset) applyDownloadPreset(downloadItem, requestedPreset) else downloadItem
    }

    fun applyDownloadPreset(item: DownloadItem, preset: DownloadPreset): DownloadItem {
        val targetType = preset.configuration.type.toDownloadType()
        val targetItem = if (targetType == item.type) {
            item
        } else {
            item.copy(
                downloadPath = when (targetType) {
                    DownloadType.audio -> sharedPreferences.getString(
                        "music_path",
                        FileUtil.getDefaultAudioPath()
                    )!!
                    else -> sharedPreferences.getString(
                        "video_path",
                        FileUtil.getDefaultVideoPath()
                    )!!
                },
                customFileNameTemplate = when (targetType) {
                    DownloadType.audio -> sharedPreferences.getString(
                        "file_name_template_audio",
                        "%(uploader).30B - %(title).170B"
                    )!!
                    else -> sharedPreferences.getString(
                        "file_name_template",
                        "%(uploader).30B - %(title).170B"
                    )!!
                },
                extraCommands = siteExtraCommands(targetType, item.url)
            )
        }
        val resolvedFormat = if (targetType == item.type) {
            item.format
        } else {
            runCatching { getFormat(item.allFormats, targetType, item.url) }.getOrDefault(item.format)
        }
        return DownloadPresetMapper.applyTo(targetItem, preset, resolvedFormat)
    }

    private fun siteExtraCommands(type: DownloadType, url: String): String {
        return when (type) {
            DownloadType.audio -> extraCommandsForAudio
            DownloadType.video -> extraCommandsForVideo
            else -> emptyList()
        }.filter { template ->
            template.urlRegex.isEmpty() || template.urlRegex.any { pattern ->
                runCatching { Regex(pattern).containsMatchIn(url) }.getOrDefault(false)
            }
        }.joinToString(" ") { it.content }
    }

    fun createResultItemFromDownload(downloadItem: DownloadItem) : ResultItem {
        return ResultItem(
            0,
            downloadItem.url,
            downloadItem.title,
            downloadItem.author,
            downloadItem.duration,
            downloadItem.thumb,
            downloadItem.website,
            downloadItem.playlistTitle,
            downloadItem.allFormats,
            "",
            arrayListOf(),
            downloadItem.playlistURL,
            downloadItem.playlistIndex,
            System.currentTimeMillis(),
            mediaPublishedAt = downloadItem.mediaPublishedAt
        )
    }

    fun createResultItemFromHistory(downloadItem: HistoryItem) : ResultItem {
        return ResultItem(
            0,
            downloadItem.url,
            downloadItem.title,
            downloadItem.author,
            downloadItem.duration,
            downloadItem.thumb,
            downloadItem.website,
            "",
            arrayListOf(),
            "",
            arrayListOf(),
            "",
            null,
            System.currentTimeMillis(),
            mediaPublishedAt = downloadItem.mediaPublishedAt
        )

    }

    fun createEmptyResultItem(url: String) : ResultItem {
        return ResultItem(
            0,
            url,
            "",
            "",
            "",
            "",
            "",
            "",
            arrayListOf(),
            "",
            arrayListOf(),
            "",
            null,
            System.currentTimeMillis()
        )
    }

    fun switchDownloadType(list: List<DownloadItem>, type: DownloadType) : List<DownloadItem>{

        list.forEach {
            val format = getFormat(it.allFormats, type, it.url)
            it.format = format

            var updatedDownloadPath = ""
            var container = ""

            when(type){
                DownloadType.audio -> {
                    updatedDownloadPath = sharedPreferences.getString("music_path", FileUtil.getDefaultAudioPath())!!
                    container = sharedPreferences.getString("audio_format", "")!!
                }
                DownloadType.video -> {
                    updatedDownloadPath = sharedPreferences.getString("video_path", FileUtil.getDefaultVideoPath())!!
                    container = sharedPreferences.getString("video_format", "")!!
                }
                DownloadType.command -> {
                    updatedDownloadPath = sharedPreferences.getString("command_path", FileUtil.getDefaultCommandPath())!!
                    container = ""
                }
                else -> {
                    updatedDownloadPath = ""
                }
            }

            it.downloadPath = updatedDownloadPath
            it.type = type
            it.container = container
        }
        return list
    }

    suspend fun createDownloadItemFromHistory(
        historyItem: HistoryItem,
        resolveSubtitleAvailability: Boolean = true,
        preferCompatibleVideo: Boolean = false
    ) : DownloadItem {
        var embedSubs = sharedPreferences.getBoolean("embed_subtitles", false)
        var saveSubs = sharedPreferences.getBoolean("write_subtitles", false)
        var saveAutoSubs = sharedPreferences.getBoolean("write_auto_subtitles", false)
        val recodeVideo = sharedPreferences.getBoolean("recode_video", false)
        val addChapters = sharedPreferences.getBoolean("add_chapters", false)
        val saveThumb = sharedPreferences.getBoolean("write_thumbnail", false)
        val embedThumb = sharedPreferences.getBoolean("embed_thumbnail", false)
        val cropThumb = sharedPreferences.getBoolean("crop_thumbnail", false)
        val subsLanguages = sharedPreferences.getString("subs_lang", "en.*,.*-orig")!!
        var availableSubtitles: List<String> = listOf()

        if (resolveSubtitleAvailability && historyItem.type == DownloadType.video) {
            val manualSubs = runCatching {
                resultRepository
                    .getResultsFromSource(historyItem.url, resetResults = false, addToResults = false, singleItem = true)
                    .firstOrNull()
                    ?.availableSubtitles
                    .orEmpty()
            }.getOrDefault(listOf())

            availableSubtitles = manualSubs
            if (SubtitleLanguageMatcher.hasRequestedSubtitle(manualSubs, subsLanguages)) {
                // For history re-download: force burn-in when requested subtitle language exists,
                // even if this item was previously marked hardSubDone.
                embedSubs = true
                saveSubs = true
                saveAutoSubs = false
            }
        }

        val customFileNameTemplate = when(historyItem.type) {
            DownloadType.audio -> sharedPreferences.getString("file_name_template_audio", "%(uploader).30B - %(title).170B")
            DownloadType.video -> sharedPreferences.getString("file_name_template", "%(uploader).30B - %(title).170B")
            else -> ""
        }

        var container = when(historyItem.type){
            DownloadType.audio -> sharedPreferences.getString("audio_format", "Default")!!
            DownloadType.video -> sharedPreferences.getString("video_format", "Default")!!
            else -> ""
        }

        val defaultPath = when(historyItem.type) {
            DownloadType.audio -> sharedPreferences.getString("music_path", FileUtil.getDefaultAudioPath())
                ?: FileUtil.getDefaultAudioPath()
            DownloadType.video -> sharedPreferences.getString("video_path", FileUtil.getDefaultVideoPath())
                ?: FileUtil.getDefaultVideoPath()
            DownloadType.command -> sharedPreferences.getString("command_path", FileUtil.getDefaultCommandPath())
                ?: FileUtil.getDefaultCommandPath()
            else -> ""
        }

        val sponsorblock = sharedPreferences.getStringSet("sponsorblock_filters", emptySet())
        val audioBitrate = sharedPreferences.getString("audio_bitrate", "")

        val extraCommands = when (historyItem.type) {
            DownloadType.audio -> extraCommandsForAudio
            DownloadType.video -> extraCommandsForVideo
            else -> listOf()
        }.filter {
            it.urlRegex.isEmpty() || it.urlRegex.any { u ->
                Regex(u).containsMatchIn(historyItem.url)
            }
        }.joinToString(" ") { it.content }

        val audioPreferences = AudioPreferences(embedThumb, cropThumb,false, ArrayList(sponsorblock!!), audioBitrate!!)
        val videoPreferences = VideoPreferences(embedSubs, addChapters, false, ArrayList(sponsorblock), saveSubs, saveAutoSubs, subsLanguages, recodeVideo = recodeVideo)
        val isLocalFormatLike = historyItem.format.format_id.isLocalFormatLike()
        val isLocalHistorySource =
            historyItem.localTreeUri.isNotBlank() ||
                historyItem.localTreePath.isNotBlank() ||
                historyItem.downloadPath.any { it.startsWith("content://") } ||
                isLocalFormatLike
        var path = defaultPath
        if (!isLocalHistorySource) {
            val bestPath = historyItem.downloadPath.firstOrNull { FileUtil.exists(it) }
                ?: historyItem.downloadPath.firstOrNull()
            bestPath?.let {
                File(it).parent?.apply {
                    if (File(this).exists()){
                        path = this
                    }
                }
            }
        }
        val normalizedFormat = cloneFormat(historyItem.format).apply {
            if (isLocalHistorySource || format_id.isLocalFormatLike()) {
                format_id = "best"
            }
        }
        if (preferCompatibleVideo && historyItem.type == DownloadType.video && !isLocalHistorySource) {
            normalizedFormat.applyCompatibleVideoRedownload(resources)
            videoPreferences.compatibilityMode = true
            container = container.takeUnless {
                it.equals("Default", ignoreCase = true) || it.equals("webm", ignoreCase = true)
            } ?: "mkv"
        }
        val downloadItem = DownloadItem(0,
            historyItem.url,
            historyItem.title,
            historyItem.author,
            historyItem.thumb,
            historyItem.duration,
            historyItem.type,
            normalizedFormat,
            container,
            "",
            ArrayList(),
            path,
            historyItem.website,
            "",
            "",
            audioPreferences,
            videoPreferences,
            extraCommands,
            customFileNameTemplate!!,
            saveThumb,
            DownloadRepository.Status.Queued.toString(),
            0,
            null,
            playlistURL = HistoryRedownloadMarker.regular(historyItem.id),
            incognito = sharedPreferences.getBoolean("incognito", false),
            availableSubtitles = availableSubtitles,
            mediaPublishedAt = historyItem.mediaPublishedAt
        )
        return downloadItem
    }

    suspend fun createQualityRedownloadItem(
        historyItem: HistoryItem,
        expectedMinimumHeight: Int,
        sourceFormats: List<Format>
    ): DownloadItem = HistoryRedownloadItemFactory(application, dbManager)
        .createQualityReplacement(historyItem, expectedMinimumHeight, sourceFormats)

    private fun String.isLocalFormatLike(): Boolean {
        val normalized = trim().lowercase(Locale.getDefault())
        return normalized == "local" || normalized.startsWith("local+")
    }

    private fun Format.applyCompatibleVideoRedownload(resources: Resources) {
        format_id = inferGenericVideoFormatId(resources)
        vcodec = ""
        acodec = ""
        container = ""
    }

    private fun Format.inferGenericVideoFormatId(resources: Resources): String {
        val defaultFormats = resources.getStringArray(R.array.video_formats_values)
        val candidates = listOf(format_note, format_id, encoding, tbr.orEmpty())
            .joinToString(" ")
            .lowercase(Locale.getDefault())

        val explicitHeight = Regex("""(?<!\d)(2160|1440|1080|720|480|360|240)p(?!\d)""")
            .find(candidates)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val dimensionHeight = Regex("""(?:^|[^\d])(2160|1440|1080|720|480|360|240)(?:$|[^\d])""")
            .find(candidates)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val parsedHeight = explicitHeight ?: dimensionHeight
        val genericHeight = when {
            parsedHeight == null -> null
            parsedHeight >= 2160 -> 2160
            parsedHeight >= 1440 -> 1440
            parsedHeight >= 1080 -> 1080
            parsedHeight >= 720 -> 720
            parsedHeight >= 480 -> 480
            parsedHeight >= 360 -> 360
            parsedHeight >= 240 -> 240
            else -> null
        }

        val genericId = genericHeight?.let { "${it}p_ytdlnisxgeneric" }
        return if (genericId != null && defaultFormats.contains(genericId)) genericId else "best"
    }


    fun getFormat(formats: List<Format>, type: DownloadType, url: String? = null) : Format {
        when(type) {
            DownloadType.audio -> {
                return cloneFormat (
                    try {
                        val theFormats = formats.filter { it.vcodec.isBlank() || it.vcodec == "none" }.ifEmpty {
                            formatUtil.getGenericAudioFormats(resources).sortedByDescending { it.filesize }
                        }
                        FormatUtil(application).sortAudioFormats(theFormats).first()
                    }catch (e: Exception){
                        formatUtil.getGenericAudioFormats(resources).first()
                    }
                )

            }
            DownloadType.video -> {
                return cloneFormat(
                    try {
                        val theFormats = formats.filter { it.vcodec.isNotBlank() && it.vcodec != "none" }.ifEmpty {
                            formatUtil.getGenericVideoFormats(resources).sortedByDescending { it.filesize }
                        }

                        FormatUtil(application).sortVideoFormats(theFormats).first()
                    }catch (e: Exception){
                        formatUtil.getGenericVideoFormats(resources).first()
                    }
                )
            }
            else -> {
                val preferredCommandTemplates = commandTemplateDao.getPreferredCommandTemplates()
                var template : CommandTemplate? = null
                if (url != null) {
                    template = preferredCommandTemplates.firstOrNull { it.urlRegex.isEmpty() || it.urlRegex.any { u ->
                        Regex(u).containsMatchIn(url)
                    } }
                }

                if (template == null) {
                    template = commandTemplateDao.getFirst()
                }
                return generateCommandFormat(
                    template ?: CommandTemplate(
                        0,
                        "",
                        sharedPreferences.getString("lastCommandTemplateUsed", "") ?: "",
                        useAsExtraCommand = false,
                        useAsExtraCommandAudio = false,
                        useAsExtraCommandVideo = false,
                        useAsExtraCommandDataFetching = false
                    )
                )
            }
        }
    }

    private fun cloneFormat(item: Format) : Format {
        val string = Gson().toJson(item, Format::class.java)
        return Gson().fromJson(string, Format::class.java)
    }

    fun getPreferredAudioFormats(formats: List<Format>) : ArrayList<String>{
        val preferredAudioFormats = arrayListOf<String>()
        val audioFormatIDPreference = sharedPreferences.getString("format_id_audio", "").toString().split(",").filter { it.isNotEmpty() }
        for (f in formats.sortedBy { it.format_id }){
            val fId = audioFormatIDPreference.sorted().find { it.contains(f.format_id) }
            if (fId != null) {
                if (fId.split("+").all { formats.map { f-> f.format_id }.contains(it) }){
                    preferredAudioFormats.addAll(fId.split("+"))
                    break
                }
            }
        }
        if (preferredAudioFormats.isEmpty()){
            val audioF = getFormat(formats, DownloadType.audio)
            if (!formatUtil.getGenericAudioFormats(resources).contains(audioF)){
                preferredAudioFormats.add(audioF.format_id)
            }
        }
        return preferredAudioFormats
    }

    fun generateCommandFormat(c: CommandTemplate) : Format {
        return Format(
            c.title,
            c.id.toString(),
            "",
            "",
            "",
            0,
            c.content.replace("\n", " ")
        )
    }

    suspend fun toggleProcessingSort() : String {
        processingItems.emit(true)
        processingSort.value = if (processingSort.value == "ASC") "DESC" else "ASC"
        repository.reverseProcessingDownloads()
        processingItems.emit(false)
        return processingSort.value
    }

    fun turnDownloadItemsToProcessingDownloads(itemIDs: List<Long>, deleteExisting : Boolean = false) = viewModelScope.launch(Dispatchers.IO){
        val job = viewModelScope.launch(Dispatchers.IO) {
            repository.deleteProcessing()
            processingItems.emit(true)
            try {
                itemIDs.forEach {
                    val item = repository.getItemByID(it)
                    val errorSnapshot = item.takeIf {
                        it.status == DownloadRepository.Status.Error.toString()
                    }?.copy()
                    if (processingItemsJob?.isCancelled == true) throw CancellationException()
                    val refusalConverged = convergePersistedHistoryRefusal(item.id)
                    if (
                        refusalConverged ||
                            repository.isCommittedHistoryReplacement(item.id) ||
                            HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(item.lastIssueCode) ||
                            dbManager.historyReplacementBarrierDao
                                .getByDownloadIdBlocking(item.id) != null ||
                            hasTerminalHistoryReplacementLedger(item)
                    ) {
                        throw IllegalStateException(
                            retryBlockedMessage(
                                DownloadRetryDecision.Blocked(
                                    DownloadRetryBlockReason.HISTORY_REPLACEMENT_MISMATCH
                                )
                            )
                        )
                    }
                    if (item.status == DownloadRepository.Status.Error.toString()) {
                        when (val decision = prepareRetryMetadata(
                            item = item,
                            strategy = DownloadRetryStrategy.RECONFIGURED,
                            settingsConfirmed = true
                        )) {
                            is DownloadRetryDecision.Allowed ->
                                applyRetryMetadata(item, decision.metadata)
                            is DownloadRetryDecision.Blocked ->
                                throw IllegalStateException(retryBlockedMessage(decision))
                        }
                    }
                    if (!deleteExisting) item.id = 0
                    item.status = DownloadRepository.Status.Processing.toString()
                    if (errorSnapshot != null && deleteExisting) {
                        val transitioned = withDownloadWorkerExecutionLock {
                            dao.updateForQueueIfSnapshot(
                                item = item,
                                expectedStatus = errorSnapshot.status,
                                expectedExecutionId = errorSnapshot.executionId,
                                expectedOperationId = errorSnapshot.operationId,
                                expectedRetryAttempt = errorSnapshot.retryAttempt,
                                expectedIssueCode = errorSnapshot.lastIssueCode,
                                expectedIssueStage = errorSnapshot.lastIssueStage,
                            )
                        }
                        check(transitioned) { "Download changed before Processing transition ${item.id}" }
                    } else {
                        repository.update(item)
                    }
                }
                processingItems.emit(false)
            } catch (e: Exception) {
                deleteProcessing()
                processingItems.emit(false)
            }
        }
        processingItemsJob = job
    }

    fun turnHistoryItemsToProcessingDownloads(itemIDs: List<Long>, downloadNow: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        val job = viewModelScope.launch(Dispatchers.IO) {
            repository.deleteProcessing()
            processingItems.emit(true)
            try {
                itemIDs.forEach {
                    val item = historyRepository.getItem(it)
                    if (processingItemsJob?.isCancelled == true) {
                        throw CancellationException()
                    }

                    if (downloadNow) {
                        val downloadItem = createDownloadItemFromHistory(item)
                        downloadItem.status = DownloadRepository.Status.Queued.toString()
                        queueDownloads(listOf(downloadItem))
                    }else{
                        val downloadItem = createDownloadItemFromHistory(item, resolveSubtitleAvailability = false)
                        downloadItem.status = DownloadRepository.Status.Processing.toString()
                        repository.insert(downloadItem)
                    }
                }
            } catch (e: Exception) {
                deleteProcessing()
            } finally {
                processingItems.emit(false)
            }
        }
        processingItemsJob = job
    }


    fun turnResultItemsToProcessingDownloads(itemIDs: List<Long>, downloadNow: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        val job = viewModelScope.launch(Dispatchers.IO) {
            repository.deleteProcessing()
            processingItems.emit(true)
            try {
                val pendingInsert = mutableListOf<DownloadItem>()
                suspend fun flushPendingInsert() {
                    if (pendingInsert.isEmpty()) return
                    repository.insertAll(pendingInsert.toList())
                    pendingInsert.clear()
                }
                itemIDs.forEach { id ->
                    val item = resultRepository.getItemByID(id) ?: return@forEach
                    val preferredType = getDownloadType(url = item.url).toString()
                    val downloadItem = createDownloadItemFromResult(result = item, givenType = DownloadType.valueOf(
                        preferredType
                    ))
                    downloadItem.status = DownloadRepository.Status.Processing.toString()

                    if (processingItemsJob?.isCancelled == true) {
                        throw CancellationException()
                    }

                    if (downloadNow) {
                        downloadItem.status = DownloadRepository.Status.Queued.toString()
                        queueDownloads(listOf(downloadItem))
                    }else{
                        pendingInsert.add(downloadItem)
                        if (pendingInsert.size >= 10) {
                            flushPendingInsert()
                        }
                    }
                }
                flushPendingInsert()
                processingItems.emit(false)
            }catch (e: Exception) {
                deleteProcessing()
                processingItems.emit(false)
            }
        }

        processingItemsJob = job

    }

    fun insert(item: DownloadItem) = viewModelScope.launch(Dispatchers.IO){
        repository.insert(item)
    }

    fun insertAll(items: List<DownloadItem>)= viewModelScope.launch(Dispatchers.IO){
        items.forEach{
            repository.insert(it)
        }
    }

    fun insertToProcessing(items: List<DownloadItem>)= viewModelScope.launch(Dispatchers.IO){
        repository.deleteProcessing()
        items.forEach{
            it.status = DownloadRepository.Status.Processing.toString()
            repository.insert(it)
        }
    }

    fun deleteCancelled() = viewModelScope.launch(Dispatchers.IO) {
        LowQualityRedownloadLedger.refresh(application, repository.deleteCancelled())
    }

    fun deleteScheduled() = viewModelScope.launch(Dispatchers.IO) {
        LowQualityRedownloadLedger.refresh(application, repository.deleteScheduled())
    }

    fun deleteErrored() = viewModelScope.launch(Dispatchers.IO) {
        LowQualityRedownloadLedger.refresh(
            application,
            repository.deleteErrored(),
        )
    }

    fun deleteQueued() = viewModelScope.launch(Dispatchers.IO) {
        val membershipWaitingIds = repository.getMembershipWaitingDownloads().map(DownloadItem::id)
        LowQualityRedownloadLedger.refresh(application, repository.deleteQueued())
        membershipWaitingIds.forEach(notificationUtil::cancelMembershipWaitingNotification)
    }

    fun deleteSaved() = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteSaved()
    }

    fun deleteProcessing() = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteProcessing()
    }

    fun deleteWithDuplicateStatus() = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteWithDuplicateStatus()
    }

    suspend fun deleteAllWithID(ids: List<Long>) = withContext(Dispatchers.IO) {
        LowQualityRedownloadLedger.refresh(application, repository.deleteAllWithIDs(ids))
        ids.distinct().forEach(notificationUtil::cancelMembershipWaitingNotification)
    }

    private suspend fun cancelActiveQueued(): List<com.ireum.ytdl.work.DownloadCancellationRegistry.Publication> = withContext(Dispatchers.IO) {
        processingItemsJob?.apply { cancel(CancellationException()) }
        val result = repository.cancelActiveQueuedWithResult(
            recoveryContext = application,
            recoveryDisposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
        )
        var firstFailure = result.failure
        result.publications.forEach { publication ->
            try {
                withDownloadWorkerExecutionSideEffectLease(
                    downloadId = publication.downloadId,
                    executionId = publication.executionId,
                ) {
                    check(
                        cancelDownloadOnlyOwned(
                            id = publication.downloadId,
                            expectedExecutionId = publication.executionId,
                            // The repository already recorded the exact
                            // nonblank execution before committing the
                            // cancellation.  Re-recording here could
                            // overwrite a still-live generation observation
                            // after a worker has started quiescing.
                            recoveryRecorded = publication.executionId.isNotBlank(),
                            stopDisposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                        )
                    ) {
                        "Native quiescence remained unresolved for cancelled download ${publication.downloadId}"
                    }
                }
            } catch (cancelled: CancellationException) {
                firstFailure = firstFailure?.also {
                    if (it !== cancelled) it.addSuppressed(cancelled)
                } ?: cancelled
            } catch (failure: Exception) {
                firstFailure = firstFailure?.also {
                    if (it !== failure) it.addSuppressed(failure)
                } ?: failure
            }
        }
        try {
            LowQualityRedownloadLedger.refresh(application, result.affectedOperationIds)
        } catch (cancelled: CancellationException) {
            firstFailure = firstFailure?.also {
                if (it !== cancelled) it.addSuppressed(cancelled)
            } ?: cancelled
        } catch (failure: Exception) {
            firstFailure = firstFailure?.also {
                if (it !== failure) it.addSuppressed(failure)
            } ?: failure
        }
        firstFailure?.let { throw it }
        result.publications
    }

    fun getQueued() : List<DownloadItem> {
        return repository.getQueuedDownloads()
    }

    fun getScheduled() : List<DownloadItem> {
        return repository.getScheduledDownloads()
    }

    fun getCancelled() : List<DownloadItem> {
        return repository.getCancelledDownloads()
    }
    fun getErrored() : List<DownloadItem> {
        return repository.getErroredDownloads()
    }

    fun getSaved() : List<DownloadItem> {
        return repository.getSavedDownloads()
    }

    fun getActiveDownloads() : List<DownloadItem>{
        return repository.getActiveDownloads()
    }

    fun getActiveAndPostProcessingDownloads() : List<DownloadItem>{
        return dao.getActiveAndPostProcessingDownloadsList()
    }

    suspend fun requeueActiveDownloadsForExit(ids: List<Long>) =
        withContext(Dispatchers.IO + NonCancellable) {
            var firstFailure: Exception? = null
            fun recordFailure(failure: Exception) {
                firstFailure = firstFailure?.also {
                    if (it !== failure) it.addSuppressed(failure)
                } ?: failure
            }

            try {
                WorkManager.getInstance(application).cancelAllWorkByTag("download")
            } catch (cancelled: CancellationException) {
                recordFailure(cancelled)
            } catch (failure: Exception) {
                recordFailure(failure)
            }

            val snapshots = withDownloadWorkerExecutionLock {
                repository.getAllItemsByIDs(ids)
                    .filter {
                        it.status in setOf(
                            DownloadRepository.Status.Active.name,
                            DownloadRepository.Status.PostProcessing.name,
                        )
                    }
            }
            snapshots.forEach { snapshot ->
                try {
                    withDownloadWorkerExecutionSideEffectLease(
                        downloadId = snapshot.id,
                        executionId = snapshot.executionId,
                    ) {
                        val current = withDownloadWorkerExecutionLock {
                            dao.getNullableDownloadById(snapshot.id)
                                ?.takeIf {
                                    it.status in setOf(
                                        DownloadRepository.Status.Active.name,
                                        DownloadRepository.Status.PostProcessing.name,
                                    ) && it.executionId == snapshot.executionId
                                }
                        } ?: return@withDownloadWorkerExecutionSideEffectLease

                        // Establish an exact durable recovery carrier before
                        // quiescing the native process.  If any later step
                        // fails, exit is withheld and startup can retry this
                        // Download without exposing a fresh queued attempt.
                        check(
                            DownloadExecutionRecovery.recordPending(
                                context = application,
                                item = current,
                            )
                        ) {
                            "Could not persist exit recovery responsibility for ${current.id}"
                        }
                        val userStopPreparation =
                            DownloadExecutionRecovery.prepareUserStopBeforeNative(
                                context = application,
                                dbManager = dbManager,
                                downloadId = current.id,
                                executionId = current.executionId,
                            )
                        check(
                            userStopPreparation !=
                                DownloadExecutionRecovery.UserStopPreparation.BLOCKED
                        ) {
                            "User-stop semantic recovery remained unresolved for ${current.id}"
                        }
                        if (
                            userStopPreparation ==
                                DownloadExecutionRecovery.UserStopPreparation.COMMITTED_HISTORY_ALREADY_WON
                        ) {
                            check(
                                DownloadExecutionRecovery.convergeCommittedHistoryFinalization(
                                    context = application,
                                    dbManager = dbManager,
                                    downloadId = current.id,
                                    executionId = current.executionId,
                                )
                            ) {
                                "Committed History finalization remained unresolved for ${current.id}"
                            }
                            return@withDownloadWorkerExecutionSideEffectLease
                        }
                        if (
                            userStopPreparation ==
                                DownloadExecutionRecovery.UserStopPreparation.READY_FOR_NATIVE_QUIESCENCE
                        ) {
                            check(
                                DownloadExecutionRecovery.quiesceAfterDurableStop(
                                    context = application,
                                    downloadId = current.id,
                                    executionId = current.executionId,
                                    dbManager = dbManager,
                                )
                            ) {
                                "User-stop native quiescence remained unresolved for ${current.id}"
                            }
                        } else {
                            check(
                                DownloadWorker.cancelProcessesForExecution(
                                    current.id,
                                    current.executionId,
                                )
                            ) {
                                "Native process did not quiesce while exiting ${current.id}"
                            }
                            check(
                                DownloadExecutionRecovery.markNativeQuiescent(
                                    context = application,
                                    downloadId = current.id,
                                    executionId = current.executionId,
                                    exactGenerationProof = true,
                                )
                            ) {
                                "Exit recovery carrier was not acknowledged for ${current.id}"
                            }

                            val requeueResult = withDownloadWorkerExecutionLock {
                                val latest = dao.getNullableDownloadById(current.id)
                                    ?.takeIf {
                                        it.status in setOf(
                                            DownloadRepository.Status.Active.name,
                                            DownloadRepository.Status.PostProcessing.name,
                                        ) && it.executionId == current.executionId
                                    }
                                latest?.let {
                                    // One repository primitive owns both the
                                    // modern exact-token and legacy blank-token
                                    // transitions. A committed History replacement
                                    // is finalization debt, never runnable work.
                                    repository.requeueRunningDownload(it.id, it.executionId)
                                }
                            } ?: return@withDownloadWorkerExecutionSideEffectLease

                            // Keep completeAndDelete outside the global claim lock.
                            // It may perform cache/reference cleanup, while this
                            // exact Download lease still prevents resource reuse.
                            if (
                                requeueResult ==
                                    DownloadRepository.RunningDownloadRequeueResult.COMMITTED_HISTORY_FINALIZATION_DEBT
                            ) {
                                repository.completeAndDelete(
                                    id = current.id,
                                    expectedExecutionId = current.executionId,
                                )
                            }
                        }

                        // Leave the exact carrier for the next lifecycle pass.
                        // The process is about to terminate, and admission
                        // remains fenced by this journal if termination is
                        // delayed or the finalization write was partial.
                    }
                } catch (cancelled: CancellationException) {
                    recordFailure(cancelled)
                    runCatching {
                        DownloadExecutionRecovery.scheduleRecovery(application, snapshot.id)
                    }.onFailure { schedulingFailure ->
                        recordFailure(
                            schedulingFailure as? Exception
                                ?: IllegalStateException(
                                    "Exit recovery scheduling failed for ${snapshot.id}",
                                    schedulingFailure,
                                )
                        )
                    }
                } catch (failure: Exception) {
                    recordFailure(failure)
                    runCatching {
                        DownloadExecutionRecovery.scheduleRecovery(application, snapshot.id)
                    }.onFailure { schedulingFailure ->
                        recordFailure(
                            schedulingFailure as? Exception
                                ?: IllegalStateException(
                                    "Exit recovery scheduling failed for ${snapshot.id}",
                                    schedulingFailure,
                                )
                        )
                    }
                }
            }
            firstFailure?.let { throw it }
        }

    fun getActiveDownloadsCount() : Int {
        return repository.getActiveDownloadsCount()
    }

    fun getActiveQueuedDownloadsCount() : Int {
        return dao.getDownloadsCountByStatus(listOf(DownloadRepository.Status.Active, DownloadRepository.Status.Queued).toListString())
    }

    fun getQueuedDownloadsCount() : Int {
        return dao.getDownloadsCountByStatus(listOf(DownloadRepository.Status.Queued).toListString())
    }

    fun getActiveAndQueuedDownloadIDs() : List<Long>{
        return repository.getActiveAndQueuedDownloadIDs()
    }

    suspend fun resetScheduleTimeForItemsAndStartDownload(items: List<Long>) = withContext(Dispatchers.IO) {
        items.forEach { convergePersistedHistoryRefusal(it) }
        if (dbManager.downloadDao.resetScheduleTimeForItems(items) == 0) return@withContext
        repository.startDownloadWorker(emptyList(), application)
    }

    suspend fun resetScheduleItemForAllScheduledItemsAndStartDownload() = withContext(Dispatchers.IO) {
        repository.getScheduledDownloads().forEach { convergePersistedHistoryRefusal(it.id) }
        if (dbManager.downloadDao.resetScheduleTimeForAllScheduledItems() == 0) return@withContext
        repository.startDownloadWorker(emptyList(), application)
    }

    suspend fun rescheduleExistingDownload(id: Long, startTime: Long) = withContext(Dispatchers.IO) {
        if (convergePersistedHistoryRefusal(id)) return@withContext
        if (dao.rescheduleQueuedOrScheduled(id, startTime) == 1) {
            repository.startDownloadWorker(emptyList(), application)
        }
    }

    suspend fun putAtTopOfQueue(ids: List<Long>) = withContext(Dispatchers.IO) {
        dao.putAtTopOfTheQueue(ids)
    }


    suspend fun putAtBottomOfQueue(ids: List<Long>) = withContext(Dispatchers.IO) {
        dao.putAtBottomOfTheQueue(ids)
    }


    fun putAtPosition(current: Long, id: Long) = viewModelScope.launch(Dispatchers.IO) {
        dao.putAtPosition(current, id)
    }

    fun reQueueDownloadItems(items: List<Long>) = viewModelScope.launch(Dispatchers.IO) {
        reQueueDownloadItemsAndWait(items)
    }

    suspend fun reQueueDownloadItemsAndWait(items: List<Long>) = withContext(Dispatchers.IO) {
        val candidates = items.distinct().mapNotNull { id ->
            if (DownloadExecutionRecovery.hasPendingRecovery(application, id)) {
                // A bulk requeue is still a resumability publication.  Keep
                // the exact old execution fenced until its native authority
                // has been recovered, rather than relying on admission to
                // reject the newly queued row later.
                Log.w(
                    "DownloadViewModel",
                    "Skipping requeue while native recovery remains pending for download $id",
                )
                null
            } else if (convergePersistedHistoryRefusal(id)) {
                null
            } else {
                repository.getItemByID(id).takeUnless {
                    repository.isCommittedHistoryReplacement(it.id) ||
                        hasTerminalHistoryReplacementLedger(it)
                }
            }
        }
        var requeuedCount = 0
        val requeuedIds = linkedSetOf<Long>()
        candidates.forEach { candidate ->
            val requeued = withDownloadWorkerExecutionSideEffectLease(
                downloadId = candidate.id,
                executionId = candidate.executionId,
            ) {
                withDownloadWorkerExecutionLock {
                    val current = dao.getNullableDownloadById(candidate.id)
                    if (
                        current == null ||
                            current.executionId != candidate.executionId ||
                            DownloadExecutionRecovery.hasPendingRecovery(application, candidate.id) ||
                            HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(
                                current.lastIssueCode
                            ) ||
                            dbManager.historyReplacementBarrierDao.getByDownloadId(candidate.id) != null ||
                            repository.isCommittedHistoryReplacement(candidate.id) ||
                            hasTerminalHistoryReplacementLedger(current)
                    ) {
                        0
                    } else {
                        // The per-Download lease is acquired before the
                        // global claim lock, and only this short Room update
                        // is performed while both are held.
                        dao.reQueueDownloadItems(listOf(candidate.id))
                    }
                }
            }
            requeuedCount += requeued
            if (requeued > 0) requeuedIds += candidate.id
        }
        if (requeuedCount == 0) return@withContext
        requeuedIds.forEach(notificationUtil::cancelMembershipWaitingNotification)
        repository.startDownloadWorker(emptyList(), application)
    }

    /** Resumes only the exact paused execution represented by a notification. */
    suspend fun resumePausedDownloadAndWait(
        id: Long,
        expectedExecutionId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (expectedExecutionId.isBlank()) return@withContext false
        withDownloadWorkerExecutionSideEffectLease(id, expectedExecutionId) {
            val resumed = withDownloadWorkerExecutionLock {
                if (DownloadExecutionRecovery.hasPendingRecovery(application, id)) {
                    // A Paused row is not resumable while its exact prior
                    // execution still has durable or process-local native
                    // recovery responsibility.
                    return@withDownloadWorkerExecutionLock false
                }
                val current = dao.getNullableDownloadById(id)
                    ?: return@withDownloadWorkerExecutionLock false
                if (
                    HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(
                        current.lastIssueCode
                    ) ||
                        dbManager.historyReplacementBarrierDao.getByDownloadId(id) != null ||
                        hasTerminalHistoryReplacementLedger(current)
                ) {
                    return@withDownloadWorkerExecutionLock false
                }
                dao.resumePausedIfExecutionOwned(id, expectedExecutionId) == 1
            }
            if (!resumed) {
                // Converge a refusal outside the global claim lock.  This is
                // diagnostic/terminal convergence only; the exact paused
                // execution was not requeued.
                convergePersistedHistoryRefusal(id)
                return@withDownloadWorkerExecutionSideEffectLease false
            }
            repository.startDownloadWorker(emptyList(), application)
            true
        }
    }

    suspend fun retryFailedDownload(
        itemId: Long,
        expectedOperationId: String? = null,
        expectedRetryAttempt: Int? = null,
    ): DownloadRetryDecision =
        withContext(Dispatchers.IO) {
            val observed = repository.getItemByID(itemId)
            withDownloadWorkerExecutionSideEffectLease(itemId, observed.executionId) {
                withDownloadWorkerExecutionLock {
                    val item = repository.getItemByID(itemId)
                    if (item.executionId != observed.executionId) {
                        return@withDownloadWorkerExecutionLock DownloadRetryDecision.Blocked(
                            DownloadRetryBlockReason.NOT_FAILED
                        )
                    }
                    if (DownloadExecutionRecovery.hasPendingRecovery(application, itemId)) {
                        // Retrying an Error row is also a new execution
                        // publication.  Do not let it bypass an unresolved
                        // exact native owner left by an earlier stop.
                        return@withDownloadWorkerExecutionLock DownloadRetryDecision.Blocked(
                            DownloadRetryBlockReason.NATIVE_RECOVERY_PENDING
                        )
                    }
                    if (repository.isCommittedHistoryReplacement(itemId)) {
                        return@withDownloadWorkerExecutionLock DownloadRetryDecision.Blocked(
                            DownloadRetryBlockReason.HISTORY_REPLACEMENT_MISMATCH
                        )
                    }
                    if (
                        (expectedOperationId != null && item.operationId != expectedOperationId) ||
                        (expectedRetryAttempt != null && item.retryAttempt != expectedRetryAttempt)
                    ) {
                        return@withDownloadWorkerExecutionLock DownloadRetryDecision.Blocked(
                            DownloadRetryBlockReason.NOT_FAILED
                        )
                    }
                    val expectedExecutionId = item.executionId
                    val expectedOperationId = item.operationId
                    val expectedRetryAttempt = item.retryAttempt
                    val expectedIssueCode = item.lastIssueCode
                    val expectedIssueStage = item.lastIssueStage
                    val decision = prepareRetryMetadata(
                        item = item,
                        strategy = DownloadRetryStrategy.SAME_SETTINGS,
                        settingsConfirmed = false
                    )
                    if (decision is DownloadRetryDecision.Allowed) {
                        applyRetryMetadata(item, decision.metadata)
                        item.status = DownloadRepository.Status.Queued.toString()
                        item.downloadStartTime = 0L
                        val transitioned = dao.updateForQueueIfSnapshot(
                            item = item,
                            expectedStatus = DownloadRepository.Status.Error.toString(),
                            expectedExecutionId = expectedExecutionId,
                            expectedOperationId = expectedOperationId,
                            expectedRetryAttempt = expectedRetryAttempt,
                            expectedIssueCode = expectedIssueCode,
                            expectedIssueStage = expectedIssueStage,
                        )
                        if (!transitioned) {
                            DownloadRetryDecision.Blocked(
                                DownloadRetryBlockReason.NOT_FAILED
                            )
                        } else {
                            repository.startDownloadWorker(listOf(item), application, false)
                            decision
                        }
                    } else {
                        decision
                    }
                }
            }
        }

    private suspend fun prepareRetryMetadata(
        item: DownloadItem,
        strategy: DownloadRetryStrategy,
        settingsConfirmed: Boolean
    ): DownloadRetryDecision {
        val state = when (item.status) {
            DownloadRepository.Status.Error.toString() -> DownloadRetryItemState.ERROR
            DownloadRepository.Status.Cancelled.toString() -> DownloadRetryItemState.CANCELED
            else -> DownloadRetryItemState.OTHER
        }
        val issueCode = runCatching { DownloadIssueCode.valueOf(item.lastIssueCode) }
            .getOrDefault(DownloadIssueCode.UNKNOWN)
        val currentStrategy = runCatching { DownloadRetryStrategy.valueOf(item.retryStrategy) }
            .getOrDefault(DownloadRetryStrategy.ORIGINAL)
        val hasValidOutput = runCatching {
            dbManager.historyDao.getItemByDownloadId(item.id)
                ?.downloadPath
                ?.any(FileUtil::exists) == true
        }.getOrDefault(false)
        val hasTerminalHistoryReplacementLedger = hasTerminalHistoryReplacementLedger(item)
        return DownloadRetryPolicy.prepare(
            current = DownloadRetryMetadata(
                operationId = item.operationId,
                attempt = item.retryAttempt,
                strategy = currentStrategy
            ),
            itemState = state,
            requestedStrategy = strategy,
            issueRetryable = issueCode.supportsSameSettingsRetry(),
            hasValidOutput = hasValidOutput,
            settingsConfirmed = settingsConfirmed,
            historyReplacementMismatch =
                HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(item.lastIssueCode) ||
                    dbManager.historyReplacementBarrierDao
                        .getByDownloadIdBlocking(item.id) != null ||
                    hasTerminalHistoryReplacementLedger,
            operationIdFactory = { UUID.randomUUID().toString() }
        )
    }

    private suspend fun hasTerminalHistoryReplacementLedger(item: DownloadItem): Boolean {
        val marker = HistoryRedownloadMarker.parse(item.playlistURL) ?: return false
        if (item.id <= 0L) return marker.isQualityReplacement
        val ledgerItem = dbManager.lowQualityRedownloadDao.getItemByDownloadId(item.id)
        val operation = ledgerItem?.let {
            dbManager.lowQualityRedownloadDao.getOperation(it.operationId)
        }
        if (marker.isQualityReplacement) {
            if (
                !LowQualityReplacementAuthority.isCoherent(
                    marker = marker,
                    item = ledgerItem,
                    operation = operation,
                    expectedDownloadId = item.id,
                    expectedSourceUrl = item.url,
                    expectedType = item.type,
                )
            ) {
                return true
            }
        }
        if (ledgerItem == null) return false
        return ledgerItem.stateValue.isTerminal || operation?.stateValue?.isTerminal == true
    }

    private suspend fun convergePersistedHistoryRefusal(id: Long): Boolean {
        if (id <= 0L) return false
        val current = dao.getNullableDownloadById(id) ?: return false
        val hasRefusal =
            HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(current.lastIssueCode) ||
                dbManager.historyReplacementBarrierDao.getByDownloadIdBlocking(id) != null
        if (!hasRefusal) return false
        repository.convergeHistoryReplacementRefusal(
            id = id,
            expectedExecutionId = current.executionId,
            forceError = true,
        )
        return true
    }

    private fun applyRetryMetadata(item: DownloadItem, metadata: DownloadRetryMetadata) {
        item.operationId = metadata.operationId
        item.retryAttempt = metadata.attempt
        item.retryStrategy = metadata.strategy.name
    }

    fun retryBlockedMessage(decision: DownloadRetryDecision.Blocked): String {
        return when (decision.reason) {
            com.ireum.ytdl.util.download.DownloadRetryBlockReason.ATTEMPT_LIMIT ->
                resources.getString(R.string.retry_attempt_limit_reached)
            com.ireum.ytdl.util.download.DownloadRetryBlockReason.NOT_RETRYABLE ->
                resources.getString(R.string.retry_requires_reconfiguration)
            com.ireum.ytdl.util.download.DownloadRetryBlockReason.CANCELED ->
                resources.getString(R.string.canceled_download_not_retried)
            DownloadRetryBlockReason.HISTORY_REPLACEMENT_MISMATCH ->
                resources.getString(R.string.download_retry_not_available)
            else -> resources.getString(R.string.download_retry_not_available)
        }
    }

    suspend fun queueProcessingDownloads(ignoreDuplicates: Boolean = false) : QueueDownloadsResult {
        val processingItems = repository.getAllProcessingDownloads()
        return queueDownloads(processingItems, ignoreDuplicates)
    }

    suspend fun checkProcessingDuplicates(ignoreDuplicates: Boolean = false): List<AlreadyExistsIDs> {
        val processingItems = repository.getAllProcessingDownloads()
        return detectAndMarkDuplicates(processingItems, ignoreDuplicates)
    }

    private suspend fun hydrateHistoryRedownloadBeforeQueue(
        item: DownloadItem,
        sourceSnapshot: DownloadItem? = null,
    ): Throwable? = withContext(Dispatchers.IO) {
        suspend fun persistPreparationMetadata(): Boolean {
            val source = sourceSnapshot
            if (source == null || item.id == 0L) {
                repository.update(item)
                return true
            }
            val preparationItem = item.copy(
                status = source.status,
                downloadStartTime = source.downloadStartTime,
                executionId = source.executionId,
                operationId = source.operationId,
                retryAttempt = source.retryAttempt,
                retryStrategy = source.retryStrategy,
            )
            return dao.updateForQueueIfSnapshot(
                item = preparationItem,
                expectedStatus = source.status,
                expectedExecutionId = source.executionId,
                expectedOperationId = source.operationId,
                expectedRetryAttempt = source.retryAttempt,
                expectedIssueCode = source.lastIssueCode,
                expectedIssueStage = source.lastIssueStage,
            )
        }

        if (item.type != DownloadType.video) return@withContext null
        val marker = item.playlistURL ?: return@withContext null
        val redownloadMarker = HistoryRedownloadMarker.parse(marker) ?: return@withContext null
        val historyId = redownloadMarker.historyId
        val historyItem = runCatching {
            historyRepository.getItem(historyId)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Log.w(DUP_LOG_TAG, "Failed to load history item for redownload id=$historyId", error)
            return@withContext error
        }
        if (redownloadMarker.isQualityReplacement) {
            item.videoPreferences.embedSubs = false
            item.videoPreferences.writeSubs = false
            item.videoPreferences.writeAutoSubs = false
            if (item.id != 0L && !persistPreparationMetadata()) {
                return@withContext IllegalStateException(
                    "Download changed before History redownload preparation ${item.id}"
                )
            }
            return@withContext null
        }
        val subsLanguages = sharedPreferences.getString("subs_lang", "en.*,.*-orig")!!
        val requiresHardSubLookup =
            item.videoPreferences.embedSubs ||
                item.videoPreferences.compatibilityMode ||
                historyItem.hardSubDone

        val manualSubs = item.availableSubtitles.takeIf { it.isNotEmpty() }
            ?: runCatching {
                resultRepository
                    .getResultsFromSource(
                        historyItem.url,
                        resetResults = false,
                        addToResults = false,
                        singleItem = true
                    )
                    .firstOrNull()
                    ?.availableSubtitles
                    .orEmpty()
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Log.w(DUP_LOG_TAG, "Failed to resolve subtitles for history redownload id=$historyId", error)
                if (requiresHardSubLookup) {
                    return@withContext IllegalStateException("Unable to resolve subtitles for this re-download.", error)
                }
                return@withContext null
            }

        item.availableSubtitles = manualSubs
        if (SubtitleLanguageMatcher.hasRequestedSubtitle(manualSubs, subsLanguages)) {
            item.videoPreferences.embedSubs = true
            item.videoPreferences.writeSubs = true
            item.videoPreferences.writeAutoSubs = false
        }
        if (item.id != 0L) {
            if (!persistPreparationMetadata()) {
                return@withContext IllegalStateException(
                    "Download changed before History redownload preparation ${item.id}"
                )
            }
        }
        null
    }

    data class QueueDownloadsResult(
        var message: String,
        var duplicateDownloadIDs : List<AlreadyExistsIDs>,
        var succeeded: Boolean = true
    )

    suspend fun queueDownloads(items: List<DownloadItem>, ignoreDuplicates : Boolean = false) : QueueDownloadsResult {
        val context = App.instance
        val alarmScheduler = AlarmScheduler(context)
        val queuedItems = mutableListOf<DownloadItem>()
        val sourceSnapshots = items.asSequence()
            .filter { it.id > 0L }
            .associate { it.id to it.copy() }

        val recoveryBlockedIds = withContext(Dispatchers.IO) {
            sourceSnapshots.keys.filterTo(linkedSetOf()) { id ->
                DownloadExecutionRecovery.hasPendingRecovery(application, id)
            }
        }
        if (recoveryBlockedIds.isNotEmpty()) {
            // Existing rows with unresolved native authority are not ordinary
            // queue inputs. Refuse before mutating the supplied snapshots or
            // running History hydration; the recovery carrier remains the
            // owner of this exact execution until reconciliation clears it.
            Log.w(
                "DownloadViewModel",
                "Refusing queue publication while native recovery remains pending for " +
                    recoveryBlockedIds.joinToString(),
            )
            return QueueDownloadsResult(
                message = context.getString(R.string.download_queue_failed),
                duplicateDownloadIDs = emptyList(),
                succeeded = false,
            )
        }

        //download id, history item id
        //history item id if the existing item is already downloaded
        //if history id is empty, it just found an existing item in the queue/active list
        val existingItemIDs = mutableListOf<AlreadyExistsIDs>()

        val durableMismatchIds = withContext(Dispatchers.IO) {
            items.asSequence()
                .map(DownloadItem::id)
                .filter { it > 0L }
                .toList()
                .takeIf { it.isNotEmpty() }
                ?.let { ids ->
                    dao.getDownloadsByIdsSuspend(ids)
                        .filter { item ->
                            HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(item.lastIssueCode)
                        }
                        .mapTo(hashSetOf<Long>(), DownloadItem::id)
                        .also { mismatchIds ->
                            mismatchIds += dbManager.historyReplacementBarrierDao
                                .getDownloadIds(ids)
                        }
                }
                .orEmpty()
        }

        items.forEach { item ->
            val terminalHistoryReplacementLedger = hasTerminalHistoryReplacementLedger(item)
            val refusalConverged = item.id > 0L && withContext(Dispatchers.IO) {
                convergePersistedHistoryRefusal(item.id)
            }
            if (
                item.id in durableMismatchIds ||
                refusalConverged ||
                HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(item.lastIssueCode) ||
                terminalHistoryReplacementLedger
            ) {
                return QueueDownloadsResult(
                    message = retryBlockedMessage(
                        DownloadRetryDecision.Blocked(
                            DownloadRetryBlockReason.HISTORY_REPLACEMENT_MISMATCH
                        )
                    ),
                    duplicateDownloadIDs = emptyList(),
                    succeeded = false
                )
            }
            if (item.status == DownloadRepository.Status.Error.toString()) {
                when (val decision = prepareRetryMetadata(
                    item = item,
                    strategy = DownloadRetryStrategy.RECONFIGURED,
                    settingsConfirmed = true
                )) {
                    is DownloadRetryDecision.Allowed -> applyRetryMetadata(item, decision.metadata)
                    is DownloadRetryDecision.Blocked -> {
                        return QueueDownloadsResult(
                            message = retryBlockedMessage(decision),
                            duplicateDownloadIDs = emptyList(),
                            succeeded = false
                        )
                    }
                }
            } else if (item.operationId.isBlank()) {
                item.operationId = UUID.randomUUID().toString()
            }
        }

        items.forEachIndexed { idx, it ->
            if (it.downloadStartTime > 0) {
                it.status = DownloadRepository.Status.Scheduled.toString()
            }else {
                it.status = DownloadRepository.Status.Queued.toString()
            }
            if (it.rowNumber == 0 && items.size > 1) {
                it.rowNumber = idx + 1
            }
            queuedItems.add(it)
        }

        val result = QueueDownloadsResult("", listOf())

        suspend fun persistQueuedItems(): List<DownloadItem> {
            val persisted = mutableListOf<DownloadItem>()
            queuedItems.forEach { item ->
                val snapshot = sourceSnapshots[item.id]
                val transitioned = if (snapshot == null || item.id <= 0L) {
                    val persistedId = repository.update(item)
                    if (item.id <= 0L) {
                        item.id = persistedId
                    }
                    true
                } else {
                    // Recheck the barrier at the mutation boundary. The
                    // preflight above is only an early user-facing refusal;
                    // this exact lease/lock sequence closes the race with
                    // cancellation recovery or a newer execution.
                    withDownloadWorkerExecutionSideEffectLease(
                        downloadId = item.id,
                        executionId = snapshot.executionId,
                    ) {
                        withDownloadWorkerExecutionLock {
                            if (DownloadExecutionRecovery.hasPendingRecovery(application, item.id)) {
                                false
                            } else {
                                dao.updateForQueueIfSnapshot(
                                    item = item,
                                    expectedStatus = snapshot.status,
                                    expectedExecutionId = snapshot.executionId,
                                    expectedOperationId = snapshot.operationId,
                                    expectedRetryAttempt = snapshot.retryAttempt,
                                    expectedIssueCode = snapshot.lastIssueCode,
                                    expectedIssueStage = snapshot.lastIssueStage,
                                )
                            }
                        }
                    }
                }
                if (transitioned) {
                    persisted += item
                } else {
                    result.succeeded = false
                }
            }
            return persisted
        }

        val duplicateIDs = detectAndMarkDuplicates(queuedItems, ignoreDuplicates)
        if (duplicateIDs.isNotEmpty()) {
            existingItemIDs.addAll(duplicateIDs)
            queuedItems.removeAll { item ->
                duplicateIDs.any { dup -> dup.downloadItemID == item.id }
            }
        }

        var hydrationError: Throwable? = null
        for (item in queuedItems) {
            hydrationError = hydrateHistoryRedownloadBeforeQueue(
                item = item,
                sourceSnapshot = sourceSnapshots[item.id],
            )
            if (hydrationError != null) break
        }
        if (hydrationError != null) {
            result.succeeded = false
            result.message = hydrationError.localizedMessage ?: context.getString(R.string.download_queue_failed)
            if (existingItemIDs.isNotEmpty()) {
                alreadyExistsUiState.value = existingItemIDs.toList()
                result.duplicateDownloadIDs = existingItemIDs.toList()
            }
            return result
        }

        //if scheduler is on
        val useScheduler = sharedPreferences.getBoolean("use_scheduler", false)
        if (useScheduler && !alarmScheduler.isDuringTheScheduledTime()){
            if (alarmScheduler.canSchedule()){
                persistQueuedItems()
                alarmScheduler.schedule()
            }else{
                sharedPreferences.edit().putBoolean("use_scheduler", false).apply()
                result.succeeded = false
                result.message = context.getString(R.string.enable_alarm_permission)
            }
        }else{
            val queued = persistQueuedItems()
            println(queued.size)

            result.message = repository.startDownloadWorker(queued, context).fold(
                onSuccess = { updatedRowCount ->
                    // ?깃났 ?? startDownloadWorker媛 諛섑솚??Int(?낅뜲?댄듃????ぉ ??瑜??ъ슜??硫붿떆吏 ?앹꽦
                    // ?덈? ?ㅼ뼱, context.getString(R.string.downloads_queued, updatedRowCount) 媛숈? ?뺥깭
                    // 留뚯빟 ?뱀젙 硫붿떆吏媛 ?꾩슂 ?녿떎硫? 鍮?臾몄옄??""??諛섑솚?????덉뒿?덈떎.
                    context.getString(R.string.downloads_have_been_queued) // ?덉떆: 怨좎젙???깃났 硫붿떆吏
                },
                onFailure = { exception ->
                    result.succeeded = false
                    // ?ㅽ뙣 ?? exception 媛앹껜瑜??ъ슜???ㅻ쪟 硫붿떆吏 ?앹꽦
                    // ?덈? ?ㅼ뼱, exception.localizedMessage ?먮뒗 ?ъ슜?먯뿉寃?蹂댁뿬以??쇰컲?곸씤 ?ㅻ쪟 硫붿떆吏
                    exception.localizedMessage ?: context.getString(R.string.download_queue_failed) // ?덉떆: ?ㅽ뙣 硫붿떆吏
                }
            )


            val idsToUpdateDataInBackground = queued.filter { it.needsDataUpdating() && it.downloadStartTime > 0 }.map { it.id }
            if (idsToUpdateDataInBackground.isNotEmpty()) {
                continueUpdatingDataInBackground(idsToUpdateDataInBackground)
            }
        }


        if (existingItemIDs.isNotEmpty()){
            alreadyExistsUiState.value = existingItemIDs.toList()
            result.duplicateDownloadIDs = existingItemIDs.toList()
        }

        return result
    }

    private suspend fun detectAndMarkDuplicates(
        items: List<DownloadItem>,
        ignoreDuplicates: Boolean
    ): List<AlreadyExistsIDs> {
        val context = App.instance
        val existingItemIDs = mutableListOf<AlreadyExistsIDs>()
        val downloadArchive = runCatching {
            File(FileUtil.getDownloadArchivePath(context)).useLines { it.toList() }
        }
            .getOrElse { listOf() }
            .mapNotNull { it.split(" ").getOrNull(1) }
        val checkDuplicate = sharedPreferences.getString("prevent_duplicate_downloads", "")!!
        val activeAndQueuedDownloads = withContext(Dispatchers.IO) {
            repository.getActiveAndQueuedDownloads()
        }
        val pendingHistoryRedownloads = withContext(Dispatchers.IO) {
            repository.getPendingObservationDownloads()
        }
        val batchItemIds = items.asSequence().map { it.id }.filter { it > 0L }.toSet()
        val seenBatchUrlTypeKeys = mutableSetOf<String>()
        val seenHistoryRedownloadIds = mutableSetOf<Long>()

        suspend fun markDuplicate(item: DownloadItem, historyId: Long? = null) {
            if (item.id == 0L) {
                val id = repository.insert(item)
                item.id = id
            }
            item.status = DownloadRepository.Status.Duplicate.toString()
            repository.update(item)
            existingItemIDs.add(AlreadyExistsIDs(item.id, historyId))
        }

        items.forEachIndexed { idx, it ->
            val canonicalUrl = canonicalDuplicateUrl(it.url)
            val equivalentUrls = equivalentDuplicateUrls(it.url)
            Log.d(
                DUP_LOG_TAG,
                "checkDuplicates idx=$idx id=${it.id} type=${it.type} mode=$checkDuplicate ignore=$ignoreDuplicates url=${it.url} canonical=$canonicalUrl equivalents=$equivalentUrls"
            )
            var isDuplicate = false
            if (it.isHistoryRedownload()) {
                val marker = it.playlistURL.orEmpty()
                val pendingMarkers = pendingHistoryRedownloads
                    .asSequence()
                    .filter { pending -> pending.id !in batchItemIds }
                    .map { pending -> pending.playlistURL }
                    .asIterable()
                val isDuplicate = HistoryRedownloadQueuePolicy.isDuplicate(
                    markerValue = marker,
                    pendingMarkerValues = pendingMarkers,
                    seenHistoryIds = seenHistoryRedownloadIds
                )
                if (isDuplicate) {
                    Log.d(
                        DUP_LOG_TAG,
                        "duplicate(history-redownload) id=${it.id} marker=$marker"
                    )
                    markDuplicate(it)
                } else {
                    Log.d(
                        DUP_LOG_TAG,
                        "skip original-history duplicate check for redownload id=${it.id} marker=$marker url=${it.url}"
                    )
                }
                return@forEachIndexed
            }
            if (checkDuplicate.isNotEmpty() && !ignoreDuplicates) {
                if (checkDuplicate == "url_type") {
                    val batchKey = "${it.type}:$canonicalUrl"
                    if (!seenBatchUrlTypeKeys.add(batchKey)) {
                        Log.d(
                            DUP_LOG_TAG,
                            "duplicate(url_type) batchMatch id=${it.id} type=${it.type} requestUrl=${it.url} canonical=$canonicalUrl"
                        )
                        isDuplicate = true
                        markDuplicate(it)
                        return@forEachIndexed
                    }
                }
                when (checkDuplicate) {
                    "download_archive" -> {
                        if (downloadArchive.any { d -> it.url.contains(d) }) {
                            isDuplicate = true
                            markDuplicate(it)
                        }
                    }

                    "url_type" -> {
                        val existingDownload = activeAndQueuedDownloads.firstOrNull { a ->
                            a.type == it.type && areSameDuplicateUrl(a.url, it.url)
                        }
                        if (existingDownload != null) {
                            Log.d(
                                DUP_LOG_TAG,
                                "duplicate(url_type) activeQueuedMatch id=${it.id} existingId=${existingDownload.id} type=${it.type} requestUrl=${it.url} existingUrl=${existingDownload.url}"
                            )
                            isDuplicate = true
                            markDuplicate(it)
                        } else {
                            val history = getHistoryByEquivalentUrl(it.url)
                                .filter { item -> item.downloadPath.any { path -> FileUtil.exists(path) } }
                            Log.d(
                                DUP_LOG_TAG,
                                "url_type history lookup id=${it.id} type=${it.type} exactCount=${history.size} canonicalCount=${history.size} requestUrl=${it.url} canonical=$canonicalUrl equivalents=$equivalentUrls"
                            )

                            val existingHistoryItem = history.firstOrNull { h -> h.type == it.type }

                            if (existingHistoryItem != null) {
                                Log.d(
                                    DUP_LOG_TAG,
                                    "duplicate(url_type) historyMatch id=${it.id} historyId=${existingHistoryItem.id} type=${it.type} requestUrl=${it.url} historyUrl=${existingHistoryItem.url}"
                                )
                                isDuplicate = true
                                markDuplicate(it, existingHistoryItem.id)
                            }
                        }
                    }

                    "config" -> {
                        val currentCommand = ytdlpUtil.buildYoutubeDLRequest(
                            it,
                            ytdlpUtil.resolveInitialYoutubeMediaAccessProfile(it),
                        )
                        val parsedCurrentCommand = ytdlpUtil.parseYTDLRequestString(currentCommand)
                        val existingDownload = DownloadConfigurationDuplicatePolicy.findMatch(
                            activeAndQueuedDownloads,
                            it,
                        )

                        if (existingDownload != null) {
                            Log.d(
                                DUP_LOG_TAG,
                                "duplicate(config) activeQueuedMatch id=${it.id} existingId=${existingDownload.id} url=${it.url}"
                            )
                            isDuplicate = true
                            markDuplicate(it)
                        } else {
                            val history = withContext(Dispatchers.IO) {
                                historyRepository.getItemsByUrl(it.url)
                                    .filter { item -> item.downloadPath.any { path -> FileUtil.exists(path) } }
                            }
                            val canonicalHistory = withContext(Dispatchers.IO) {
                                equivalentUrls.flatMap { historyRepository.getItemsByUrl(it) }
                                    .distinctBy { item -> item.id }
                                    .filter { item -> item.downloadPath.any { path -> FileUtil.exists(path) } }
                            }
                            Log.d(
                                DUP_LOG_TAG,
                                "config history lookup id=${it.id} exactCount=${history.size} canonicalCount=${canonicalHistory.size} requestUrl=${it.url} canonical=$canonicalUrl equivalents=$equivalentUrls"
                            )

                            val existingHistoryItem = history.firstOrNull { h ->
                                h.command.replace("(-P \"(.*?)\")|(--trim-filenames \"(.*?)\")".toRegex(), "") ==
                                    parsedCurrentCommand.replace("(-P \"(.*?)\")|(--trim-filenames \"(.*?)\")".toRegex(), "")
                            }

                            if (existingHistoryItem != null) {
                                Log.d(
                                    DUP_LOG_TAG,
                                    "duplicate(config) historyCommandMatch id=${it.id} historyId=${existingHistoryItem.id} requestUrl=${it.url} historyUrl=${existingHistoryItem.url}"
                                )
                                isDuplicate = true
                                markDuplicate(it, existingHistoryItem.id)
                            }
                        }
                    }
                }
            }

            if (!isDuplicate) {
                Log.d(
                    DUP_LOG_TAG,
                    "not-duplicate id=${it.id} type=${it.type} mode=$checkDuplicate url=${it.url} canonical=$canonicalUrl"
                )
            }
        }

        if (existingItemIDs.isNotEmpty()) {
            alreadyExistsUiState.value = existingItemIDs.toList()
        }
        return existingItemIDs
    }

    private fun DownloadItem.isHistoryRedownload(): Boolean {
        return HistoryRedownloadMarker.parse(playlistURL) != null
    }

    private fun canonicalDuplicateUrl(url: String): String {
        return LinkUtil.canonicalYoutubeVideoUrlOrSelf(url)
    }

    private fun equivalentDuplicateUrls(url: String): List<String> {
        return LinkUtil.equivalentYoutubeVideoUrls(url)
    }

    private fun areSameDuplicateUrl(a: String, b: String): Boolean {
        return canonicalDuplicateUrl(a) == canonicalDuplicateUrl(b)
    }

    private suspend fun getHistoryByEquivalentUrl(url: String): List<HistoryItem> {
        return withContext(Dispatchers.IO) {
            equivalentDuplicateUrls(url)
                .flatMap { historyRepository.getItemsByUrl(it) }
                .distinctBy { it.id }
        }
    }

    fun getQueuedCollectedFileSize() : Long {
        return dbManager.downloadDao.getSelectedFormatFromQueued().filter { it.filesize > 10 }.sumOf { it.filesize }
    }

    fun getTotalSize(status: List<DownloadRepository.Status>) : LiveData<Int> {
        return dbManager.downloadDao.getDownloadsCountByStatusFlow(status.map { it.toString() }).asLiveData()
    }

    fun checkAllQueuedItemsAreScheduledAfterNow(items: List<Long>, inverted: Boolean, currentStartTime: Long) : Boolean {
        return dbManager.downloadDao.checkAllQueuedItemsAreScheduledAfterNow(items, inverted.toString(), currentStartTime)
    }

    fun getItemIDsNotPresentIn(items: List<Long>, status: List<DownloadRepository.Status>) : List<Long> {
        return dbManager.downloadDao.getDownloadIDsNotPresentInList(items.ifEmpty { listOf(-1L) }, status.map { it.toString() })
    }

    suspend fun moveProcessingToSavedCategory(){
        val refused = repository.getAllProcessingDownloads().filter { item ->
            HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(item.lastIssueCode) ||
                dbManager.historyReplacementBarrierDao.getByDownloadIdBlocking(item.id) != null
        }
        dao.updateProcessingtoSavedStatus()
        refused.forEach { item ->
            repository.convergeHistoryReplacementRefusal(
                id = item.id,
                expectedExecutionId = item.executionId,
                forceError = true,
            )
        }
    }


    fun updateAllProcessingFormats(selectedItems: List<Long>?, formatTuples : List<MultipleItemFormatTuple>) = viewModelScope.launch(Dispatchers.IO) {
        val items = if (selectedItems.isNullOrEmpty()) {
            repository.getAllProcessingDownloads()
        }else {
            repository.getAllItemsByIDs(selectedItems)
        }

        items.forEach {
            val ft = formatTuples.first { ft -> ft.url == it.url }.formatTuple
            ft.format?.apply {
                it.format = this
            }

            if (it.type == DownloadType.video) {
                it.videoPreferences.audioFormatIDs.clear()
                ft.audioFormats?.map { a -> a.format_id }?.let { list ->
                    it.videoPreferences.audioFormatIDs.addAll(list)
                }
            }

            repository.update(it)
        }

    }

    suspend fun updateProcessingCommandFormat(selectedItems: List<Long>?, format: Format){
        val items = if (selectedItems.isNullOrEmpty()) {
            repository.getAllProcessingDownloads()
        }else {
            repository.getAllItemsByIDs(selectedItems)
        }

        items.forEach {
            it.format = format
            repository.update(it)
        }
    }

    suspend fun updateProcessingContainer(checkedItems: List<Long>?, cont: String) {
        var container = ""
        if (cont != resources.getString(R.string.defaultValue)) {
            container = cont
        }

        if (checkedItems.isNullOrEmpty()) {
            dao.updateProcessingContainer(container)
        }else {
            dao.updateContainerByIds(checkedItems, container)
        }

    }

    suspend fun updateProcessingDownloadPath(selectedItems: List<Long>?, path: String){
        if (selectedItems.isNullOrEmpty()) {
            dao.updateProcessingDownloadPath(path)
        }else {
            dao.updateDownloadPathByIDs(selectedItems, path)
        }
    }

    fun getProcessingDownloads(checkedItems: List<Long>?) : List<DownloadItem> {
        return if (checkedItems.isNullOrEmpty()) {
            repository.getAllProcessingDownloads()
        }else {
            repository.getAllItemsByIDs(checkedItems)
        }

    }

    fun updateDownloadItemFormats(id: Long, list: List<Format>) = viewModelScope.launch(Dispatchers.IO) {
        val item = repository.getItemByID(id)
        item.allFormats.clear()
        item.allFormats.addAll(list)
        item.format = getFormat(list, item.type, item.url)

        runCatching {
            resultRepository.getAllByURL(item.url).forEach {
                it.formats = list
                resultRepository.update(it)
            }
        }
    }

    fun updateProcessingFormatByUrl(url: String, list: List<Format>) = viewModelScope.launch(Dispatchers.IO) {
        val items = repository.getProcessingDownloadsByUrl(url)
        items.forEach { item ->
            item.allFormats.clear()
            item.allFormats.addAll(list)
            item.format = getFormat(list, item.type, item.url)
            repository.update(item)
        }

        kotlin.runCatching {
            resultRepository.getAllByURL(url).forEach {
                it.formats = list
                resultRepository.update(it)
            }
        }
    }

    fun removeUnavailableDownloadAndResultByURL(url: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteProcessingByUrl(url)
        resultRepository.deleteByUrl(url)
    }

    suspend fun continueUpdatingFormatsOnBackground(selectedItems: List<Long>?){
        val allProcessing = repository.getAllProcessingDownloads().map { it.id }

        val ids = if (selectedItems.isNullOrEmpty()) {
            allProcessing
        }else {
            selectedItems
        }

        moveProcessingToSavedCategory()

        val id = System.currentTimeMillis().toInt()
        val workRequest = OneTimeWorkRequestBuilder<UpdateMultipleDownloadsFormatsWorker>()
            .setInputData(
                Data.Builder()
                    .putLongArray("ids", ids.toLongArray())
                    .putLongArray("other_ids_in_bundle", allProcessing.filter { !ids.contains(it) }.toLongArray())
                    .putInt("id", id)
                    .build())
            .addTag("updateFormats")
            .build()
        val context = App.instance
        WorkManager.getInstance(context).enqueueUniqueWork(
            id.toString(),
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

    }

    private fun continueUpdatingDataInBackground(ids: List<Long>){
        val workRequest = OneTimeWorkRequestBuilder<UpdateMultipleDownloadsDataWorker>()
            .setInputData(
                Data.Builder()
                    .putLongArray("ids", ids.toLongArray())
                    .build())
            .addTag("updateData")
            .build()
        val context = App.instance
        WorkManager.getInstance(context).enqueueUniqueWork(
            System.currentTimeMillis().toString(),
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

    }

    suspend fun updateProcessingType(selectedItems: List<Long>?, newType: DownloadType) {
        val processing = if (selectedItems.isNullOrEmpty()) {
            repository.getAllProcessingDownloads()
        }else{
            repository.getAllItemsByIDs(selectedItems)
        }

        processing.apply {
            val new = switchDownloadType(this, newType)
            new.forEach {
                repository.update(it)
            }
        }
    }

    suspend fun updateProcessingDownloadTimeAndQueueScheduled(time: Long, ignoreDuplicates: Boolean = false) : QueueDownloadsResult {
        val processing = repository.getAllProcessingDownloads()
        processing.forEach {
            it.downloadStartTime = time
            it.status = DownloadRepository.Status.Scheduled.toString()
        }
        return queueDownloads(processing, ignoreDuplicates)
    }

    fun checkIfAllProcessingItemsHaveSameType(selectedItems: List<Long>?) : Pair<Boolean, DownloadType> {
        val types = if (!selectedItems.isNullOrEmpty()) {
            dao.getProcessingDownloadTypesByIDs(selectedItems)
        }else {
            dao.getProcessingDownloadTypes()
        }

        if (types.isEmpty()) {
            return Pair(false, DownloadType.command)
        }

        return Pair(types.size == 1, DownloadType.valueOf(types.first()))
    }

    fun checkIfAllProcessingItemsHaveSameContainer(checkedItems: List<Long>?) : Pair<Boolean, String> {
        val containers = if (checkedItems.isNullOrEmpty()) {
            dao.getProcessingDownloadContainers()
        }else {
            dao.getDownloadContainersByIDs(checkedItems)
        }

        return Pair(containers.size == 1, containers.first())
    }


    suspend fun updateItemsWithIdsToProcessingStatus(ids: List<Long>) {
        repository.deleteProcessing()
        val eligibleIds = ids.mapNotNull { id ->
            if (convergePersistedHistoryRefusal(id)) {
                null
            } else {
                repository.getItemByID(id).takeUnless { hasTerminalHistoryReplacementLedger(it) }?.id
            }
        }
        dao.updateItemsToProcessing(eligibleIds)
        val first = dao.getFirstProcessingDownload()
    }

    suspend fun updateToStatus(
        id: Long,
        status: DownloadRepository.Status,
        expectedExecutionId: String? = null,
    ): Boolean {
        if (status == DownloadRepository.Status.Saved) {
            LowQualityRedownloadLedger.refresh(application, repository.moveToSaved(id))
            return true
        } else {
            return repository.setDownloadStatus(id, status, expectedExecutionId)
        }
    }

    suspend fun restoreMembershipWaiting(item: DownloadItem) {
        val current = dbManager.downloadDao.getNullableDownloadById(item.id)
        if (
            current != null &&
                (
                    HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(
                        current.lastIssueCode
                    ) || dbManager.historyReplacementBarrierDao.getByDownloadId(item.id) != null ||
                        hasTerminalHistoryReplacementLedger(current)
                )
        ) {
            if (
                HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(
                    current.lastIssueCode
                ) || dbManager.historyReplacementBarrierDao.getByDownloadId(item.id) != null
            ) {
                val convergence = repository.convergeHistoryReplacementRefusal(
                    id = item.id,
                    expectedExecutionId = current.executionId,
                    forceError = true,
                )
                LowQualityRedownloadLedger.refresh(application, convergence.affectedOperationIds)
            }
            return
        }
        val restored = dbManager.observeSourcesDao.parkDownloadForMembership(
            downloadId = item.id,
            sourceId = item.observeSourceId,
            expectedStatus = DownloadRepository.Status.Cancelled.toString(),
            issueCode = DownloadIssueCode.MEMBERSHIP_REQUIRED.name,
            issueStage = item.lastIssueStage
        ) > 0
        if (restored) {
            item.status = DownloadRepository.Status.WaitingForMembership.toString()
            notificationUtil.createMembershipWaiting(
                item.id,
                item.title.ifEmpty { item.url },
                resources
            )
        }
    }

    fun getURLsByStatus(list: List<DownloadRepository.Status>) : List<String> {
        return dao.getURLsByStatus(list.map { it.toString() })
    }

    fun getIDsByStatus(list: List<DownloadRepository.Status>) : List<Long> {
        return dao.getIDsByStatus(list.map { it.toString() })
    }

    fun getURLsByIds(list: List<Long>) : List<String> {
        return dao.getURLsByID(list)
    }

    fun getIDsBetweenTwoItems(item1: Long, item2: Long, statuses: List<String>) : List<Long> {
        return if (statuses == listOf(DownloadRepository.Status.Queued.name)) {
            dao.getQueuedIDsBetweenTwoItems(item1, item2)
        } else {
            dao.getIDsBetweenTwoItems(item1, item2, statuses)
        }
    }

    fun getScheduledIDsBetweenTwoItems(item1: Long, item2: Long) : List<Long> {
        return dao.getScheduledIDsBetweenTwoItems(item1, item2)
    }

    suspend fun updateProcessingIncognito(selectedItems: List<Long>?, incognito: Boolean) {
        if (selectedItems.isNullOrEmpty()) {
            dao.updateProcessingIncognito(incognito)
        }else {
            dao.updateIncognitoByIDs(incognito, selectedItems)
        }
    }

    fun areAllProcessingIncognito(selectedItems: List<Long>?) : Boolean {
        return if (selectedItems.isNullOrEmpty()) {
            dao.getProcessingAsIncognitoCount() > 0
        }else {
            dao.getProcessingAsIncognitoCountByIDs(selectedItems) > 0
        }
    }

    suspend fun cancelDownloadOnly(
        id: Long,
        expectedExecutionId: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            var recoveryRecorded = false
            var resolvedExecutionId = expectedExecutionId.orEmpty()
            try {
                withDownloadWorkerExecutionSideEffectLease(id, expectedExecutionId.orEmpty()) {
                    val semanticResult = withDownloadWorkerExecutionLock {
                        val current = dao.getNullableDownloadById(id)
                            ?.takeIf {
                                expectedExecutionId.isNullOrBlank() ||
                                    it.executionId == expectedExecutionId
                            }
                            ?: return@withDownloadWorkerExecutionLock null
                        val exactExecutionId = current.executionId
                        resolvedExecutionId = exactExecutionId
                        check(
                            DownloadExecutionRecovery.recordPending(
                                context = application,
                                item = current,
                                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
                            )
                        ) {
                            "Could not persist cancellation recovery responsibility for ${current.id}"
                        }
                        recoveryRecorded = true
                        repository.convergeUserStopSemantic(
                            id = id,
                            expectedExecutionId = exactExecutionId,
                            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                        )
                    }
                    when (val outcome = semanticResult?.outcome) {
                        DownloadRepository.UserStopSemanticOutcome.USER_STOP_COMMITTED,
                        DownloadRepository.UserStopSemanticOutcome.USER_STOP_ALREADY_SATISFIED -> {
                            check(
                                cancelDownloadOnlyOwned(
                                    id = id,
                                    expectedExecutionId = resolvedExecutionId,
                                    recoveryRecorded = true,
                                    stopDisposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                )
                            ) {
                                "Native quiescence remained unresolved for cancelled download $id"
                            }
                        }
                        DownloadRepository.UserStopSemanticOutcome.COMMITTED_HISTORY_ALREADY_WON -> {
                            check(
                                DownloadExecutionRecovery.convergeCommittedHistoryFinalization(
                                    context = application,
                                    dbManager = dbManager,
                                    downloadId = id,
                                    executionId = resolvedExecutionId,
                                )
                            ) {
                                "Committed History finalization remained unresolved for $id"
                            }
                        }
                        DownloadRepository.UserStopSemanticOutcome.STRONGER_USER_CANCEL_ALREADY_WON -> {
                            check(
                                DownloadExecutionRecovery.convergeUserStopBeforeGenericCleanup(
                                    context = application,
                                    dbManager = dbManager,
                                    downloadId = id,
                                    executionId = resolvedExecutionId,
                                )
                            ) {
                                "Stronger Cancel convergence remained unresolved for $id"
                            }
                        }
                        DownloadRepository.UserStopSemanticOutcome.OWNERSHIP_LOST,
                        null -> return@withDownloadWorkerExecutionSideEffectLease
                        is DownloadRepository.UserStopSemanticOutcome.RETRYABLE_PERSISTENCE_FAILURE -> {
                            throw outcome.error
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (recoveryRecorded) {
                    DownloadExecutionRecovery.retainRecoveryResponsibility(
                        context = application,
                        downloadId = id,
                        dbManager = dbManager,
                        failure = cancelled,
                    )
                }
                throw cancelled
            } catch (failure: Exception) {
                if (recoveryRecorded) {
                    DownloadExecutionRecovery.retainRecoveryResponsibility(
                        context = application,
                        downloadId = id,
                        dbManager = dbManager,
                        failure = failure,
                    )
                }
                throw failure
            }
        }
    }

    private suspend fun cancelDownloadOnlyOwned(
        id: Long,
        expectedExecutionId: String,
        recoveryRecorded: Boolean = false,
        stopDisposition: DownloadExecutionRecovery.RecoveryDisposition? = null,
    ): Boolean {
        val current = withDownloadWorkerExecutionLock {
            val current = dao.getNullableDownloadById(id)
            current?.takeIf {
                expectedExecutionId.isBlank() || it.executionId == expectedExecutionId
            }
        }
        if (current == null) return false
        if (
            !recoveryRecorded &&
                !DownloadExecutionRecovery.recordPending(
                    context = application,
                    item = current,
                    disposition = stopDisposition
                        ?: DownloadExecutionRecovery.RecoveryDisposition.GENERIC,
                )
        ) {
            DownloadExecutionRecovery.retainRecoveryResponsibility(
                context = application,
                downloadId = id,
                dbManager = dbManager,
                failure = IllegalStateException(
                    "Could not persist cancellation recovery responsibility for $id",
                ),
            )
            return false
        }
        if (stopDisposition != null) {
            when (
                DownloadExecutionRecovery.prepareUserStopBeforeNative(
                    context = application,
                    dbManager = dbManager,
                    downloadId = id,
                    executionId = current.executionId,
                )
            ) {
                DownloadExecutionRecovery.UserStopPreparation.NOT_PENDING,
                DownloadExecutionRecovery.UserStopPreparation.BLOCKED -> return false
                DownloadExecutionRecovery.UserStopPreparation.COMMITTED_HISTORY_ALREADY_WON -> {
                    if (!DownloadExecutionRecovery.convergeCommittedHistoryFinalization(
                            context = application,
                            dbManager = dbManager,
                            downloadId = id,
                            executionId = current.executionId,
                        )
                    ) {
                        return false
                    }
                }
                DownloadExecutionRecovery.UserStopPreparation.READY_FOR_NATIVE_QUIESCENCE -> {
                    if (
                        !DownloadExecutionRecovery.quiesceAfterDurableStop(
                            context = application,
                            downloadId = id,
                            executionId = current.executionId,
                            dbManager = dbManager,
                        )
                    ) {
                        return false
                    }
                }
            }
        } else if (
            !DownloadExecutionRecovery.quiesceAfterDurableStop(
                context = application,
                downloadId = id,
                executionId = current.executionId,
                dbManager = dbManager,
            )
        ) {
            return false
        }
        notificationUtil.cancelRunningDownloadNotification(id.toInt())
        notificationUtil.cancelMembershipWaitingNotification(id)
        return true
    }

    suspend fun cancelDownload(id: Long) {
        withContext(Dispatchers.IO) {
            val expectedExecutionId = dao.getNullableDownloadById(id)?.executionId.orEmpty()
            var recoveryRecorded = false
            try {
                withDownloadWorkerExecutionSideEffectLease(id, expectedExecutionId) {
                    val semanticResult = withDownloadWorkerExecutionLock {
                        val current = dao.getNullableDownloadById(id)
                        if (current == null || current.executionId != expectedExecutionId) {
                            return@withDownloadWorkerExecutionLock null
                        }
                        check(
                            DownloadExecutionRecovery.recordPending(
                                context = application,
                                item = current,
                                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
                            )
                        ) {
                            "Could not persist cancellation recovery responsibility for ${current.id}"
                        }
                        recoveryRecorded = true
                        repository.convergeUserStopSemantic(
                            id = id,
                            expectedExecutionId = expectedExecutionId,
                            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                        )
                    }
                    if (semanticResult != null) {
                        when (val outcome = semanticResult.outcome) {
                            DownloadRepository.UserStopSemanticOutcome.USER_STOP_COMMITTED,
                            DownloadRepository.UserStopSemanticOutcome.USER_STOP_ALREADY_SATISFIED -> {
                                check(
                                    cancelDownloadOnlyOwned(
                                        id,
                                        expectedExecutionId,
                                        recoveryRecorded = true,
                                        stopDisposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                    )
                                ) {
                                    "Native quiescence remained unresolved for cancelled download $id"
                                }
                                LowQualityRedownloadLedger.refresh(
                                    application,
                                    semanticResult.affectedOperationIds,
                                )
                            }
                            DownloadRepository.UserStopSemanticOutcome.COMMITTED_HISTORY_ALREADY_WON -> {
                                check(
                                    DownloadExecutionRecovery.convergeCommittedHistoryFinalization(
                                        context = application,
                                        dbManager = dbManager,
                                        downloadId = id,
                                        executionId = expectedExecutionId,
                                    )
                                ) {
                                    "Committed History finalization remained unresolved for $id"
                                }
                            }
                            DownloadRepository.UserStopSemanticOutcome.STRONGER_USER_CANCEL_ALREADY_WON -> {
                                check(
                                    DownloadExecutionRecovery.convergeUserStopBeforeGenericCleanup(
                                        context = application,
                                        dbManager = dbManager,
                                        downloadId = id,
                                        executionId = expectedExecutionId,
                                    )
                                ) {
                                    "Stronger Cancel convergence remained unresolved for $id"
                                }
                            }
                            DownloadRepository.UserStopSemanticOutcome.OWNERSHIP_LOST -> Unit
                            is DownloadRepository.UserStopSemanticOutcome.RETRYABLE_PERSISTENCE_FAILURE -> {
                                throw outcome.error
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (recoveryRecorded) {
                    DownloadExecutionRecovery.retainRecoveryResponsibility(
                        context = application,
                        downloadId = id,
                        dbManager = dbManager,
                        failure = cancelled,
                    )
                }
                throw cancelled
            } catch (failure: Exception) {
                if (recoveryRecorded) {
                    DownloadExecutionRecovery.retainRecoveryResponsibility(
                        context = application,
                        downloadId = id,
                        dbManager = dbManager,
                        failure = failure,
                    )
                }
                throw failure
            }
        }
    }

    suspend fun beginUndoableCancellation(id: Long): String? =
        beginUndoableCancellation(id, legacyUndoOwner())

    suspend fun beginUndoableCancellation(
        id: Long,
        owner: DownloadRepository.UndoPresentationOwner,
    ): String? {
        var recoveryRecorded = false
        var unpublishedUndoToken: String? = null
        return try {
            withContext(Dispatchers.IO) {
                val expectedExecutionId = dao.getNullableDownloadById(id)?.executionId.orEmpty()
                withDownloadWorkerExecutionSideEffectLease(id, expectedExecutionId) {
                    val outcome = withDownloadWorkerExecutionLock {
                        val current = dao.getNullableDownloadById(id)
                        if (current == null || current.executionId != expectedExecutionId) {
                            return@withDownloadWorkerExecutionLock null
                        }
                        if (current.executionId.isNotBlank()) {
                            check(
                                DownloadExecutionRecovery.recordPending(
                                    context = application,
                                    item = current,
                                    disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                    phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
                                )
                            ) {
                                "Could not persist cancellation recovery responsibility for ${current.id}"
                            }
                            recoveryRecorded = true
                        }
                        repository.beginUndoableCancellation(
                            id = id,
                            expectedExecutionId = expectedExecutionId,
                            owner = owner,
                        ).also { outcome ->
                            outcome.pendingToken?.let { unpublishedUndoToken = it }
                        }
                    }
                    // Capture the exact durable Undo carrier before any
                    // native quiescence, ledger refresh, or dispatcher
                    // handoff can fail.  Every post-commit exit below must
                    // transfer this token rather than leaving an unpublished
                    // PREPARED authority behind.
                    outcome?.pendingToken?.let { unpublishedUndoToken = it }
                    if (outcome == null || !outcome.changed) {
                        if (!recoveryRecorded) {
                            null
                        } else {
                            // The undoable transaction exposes only a
                            // changed flag. Re-read through the typed
                            // operation-aware protocol before deciding that
                            // the user stop lost: an already-cancelled row is
                            // idempotent, while committed History is a
                            // stronger result that needs finalization.
                            when (
                                val semanticResult = repository.convergeUserStopSemantic(
                                    id = id,
                                    expectedExecutionId = expectedExecutionId,
                                    disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                ).outcome
                            ) {
                                DownloadRepository.UserStopSemanticOutcome.USER_STOP_COMMITTED,
                                DownloadRepository.UserStopSemanticOutcome.USER_STOP_ALREADY_SATISFIED -> {
                                    check(
                                        cancelDownloadOnlyOwned(
                                            id = id,
                                            expectedExecutionId = expectedExecutionId,
                                            recoveryRecorded = true,
                                            stopDisposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                                        )
                                    ) {
                                        "Native quiescence remained unresolved for cancelled download $id"
                                    }
                                    null
                                }
                                DownloadRepository.UserStopSemanticOutcome.COMMITTED_HISTORY_ALREADY_WON -> {
                                    check(
                                        DownloadExecutionRecovery.convergeCommittedHistoryFinalization(
                                            context = application,
                                            dbManager = dbManager,
                                            downloadId = id,
                                            executionId = expectedExecutionId,
                                        )
                                    ) {
                                        "Committed History finalization remained unresolved for $id"
                                    }
                                    null
                                }
                                DownloadRepository.UserStopSemanticOutcome.STRONGER_USER_CANCEL_ALREADY_WON -> {
                                    check(
                                        DownloadExecutionRecovery.convergeUserStopBeforeGenericCleanup(
                                            context = application,
                                            dbManager = dbManager,
                                            downloadId = id,
                                            executionId = expectedExecutionId,
                                        )
                                    ) {
                                        "Stronger Cancel convergence remained unresolved for $id"
                                    }
                                    null
                                }
                                DownloadRepository.UserStopSemanticOutcome.OWNERSHIP_LOST -> {
                                    DownloadExecutionRecovery.retainRecoveryResponsibility(
                                        context = application,
                                        downloadId = id,
                                        dbManager = dbManager,
                                        failure = IllegalStateException(
                                            "Cancellation recovery lost exact execution for $id",
                                        ),
                                    )
                                    null
                                }
                                is DownloadRepository.UserStopSemanticOutcome.RETRYABLE_PERSISTENCE_FAILURE -> {
                                    throw semanticResult.error
                                }
                            }
                        }
                    } else if (!recoveryRecorded) {
                        // Queued rows without an execution owner have no
                        // exact native authority to recover.  Keep the
                        // existing undoable cancellation protocol, but do
                        // not manufacture a blank-execution recovery carrier
                        // that would block the later Undo publication.
                        LowQualityRedownloadLedger.refresh(
                            application,
                            outcome.affectedOperationIds,
                        )
                        outcome.pendingToken
                    } else {
                        check(
                            cancelDownloadOnlyOwned(
                                id,
                                expectedExecutionId,
                                recoveryRecorded = true,
                                stopDisposition = DownloadExecutionRecovery.RecoveryDisposition.USER_CANCEL,
                            )
                        ) {
                            "Native quiescence remained unresolved for cancelled download $id"
                        }
                        LowQualityRedownloadLedger.refresh(application, outcome.affectedOperationIds)
                        outcome.pendingToken
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            unpublishedUndoToken?.let(repository::abandonUndoCapabilityAfterProducerFailure)
            if (recoveryRecorded) {
                DownloadExecutionRecovery.retainRecoveryResponsibility(
                    context = application,
                    downloadId = id,
                    dbManager = dbManager,
                    failure = cancelled,
                )
            }
            throw cancelled
        } catch (failure: Throwable) {
            unpublishedUndoToken?.let(repository::abandonUndoCapabilityAfterProducerFailure)
            if (recoveryRecorded) {
                DownloadExecutionRecovery.retainRecoveryResponsibility(
                    context = application,
                    downloadId = id,
                    dbManager = dbManager,
                    failure = failure,
                )
            }
            throw failure
        }
    }

    /**
     * Restores a cancelled ordinary download for the legacy no-ledger Undo
     * path.  A refused History replacement is deliberately not deleted and
     * requeued: that would turn an Undo gesture into implicit abandonment of
     * the refusal barrier.
     */
    suspend fun undoCancelledDownload(item: DownloadItem) {
        withContext(Dispatchers.IO) {
            val deleted = withDownloadWorkerExecutionSideEffectLease(
                downloadId = item.id,
                executionId = item.executionId,
            ) {
                val current = withDownloadWorkerExecutionLock {
                    val current = dbManager.downloadDao.getNullableDownloadById(item.id)
                    if (
                        current == null ||
                            (item.executionId.isNotBlank() && current.executionId != item.executionId) ||
                            DownloadExecutionRecovery.hasPendingRecovery(application, item.id)
                    ) {
                        null
                    } else {
                        current
                    }
                } ?: return@withDownloadWorkerExecutionSideEffectLease false

                if (
                    HistoryReplacementDiagnostic.isPersistedHistoryReplacementRefusal(
                        current.lastIssueCode
                    ) || dbManager.historyReplacementBarrierDao.getByDownloadId(item.id) != null ||
                        hasTerminalHistoryReplacementLedger(current)
                ) {
                    val convergence = repository.convergeHistoryReplacementRefusal(
                        id = item.id,
                        expectedExecutionId = current.executionId,
                        forceError = true,
                    )
                    LowQualityRedownloadLedger.refresh(
                        application,
                        convergence.affectedOperationIds,
                    )
                    return@withDownloadWorkerExecutionSideEffectLease false
                }

                // Keep deletion under the exact per-Download lease after the
                // barrier check.  An unresolved E1 therefore cannot lose its
                // only row while a legacy Undo gesture is being handled.
                deleteDownloadAndWait(item.id)
                true
            }
            if (deleted) {
                queueDownloads(listOf(item))
            }
        }
    }

    fun undoPendingCancellation(item: DownloadItem, token: String) =
        undoPendingCancellation(item, token, legacyUndoOwner())

    fun undoPendingCancellation(
        item: DownloadItem,
        token: String,
        owner: DownloadRepository.UndoPresentationOwner,
    ): Boolean {
        val accepted = try {
            runBlocking(Dispatchers.IO) {
                val result = try {
                    repository.acceptCancellationUndoResolution(
                        token,
                        PendingUndoResolutionIntent.RESTORE,
                        owner,
                    )
                } catch (failure: Throwable) {
                    repository.abandonUndoCapabilityAfterProducerFailure(token)
                    false
                }
                if (
                    !result &&
                        !repository.hasDurableUndoResolutionIntent(
                            token,
                            PendingUndoResolutionIntent.RESTORE,
                        )
                ) {
                    repository.reofferCancellationUndoCapabilityAfterResolutionFailure(
                        token,
                        PendingUndoResolutionIntent.RESTORE,
                        owner,
                    )
                }
                result
            }
        } catch (failure: Throwable) {
            repository.abandonUndoCapabilityAfterProducerFailure(token)
            false
        }
        if (!accepted) return false
        viewModelScope.launch(Dispatchers.IO) {
            val originalStatus = runCatching {
                DownloadRepository.Status.valueOf(item.status)
            }.getOrNull() ?: run {
                // The UI intent was already accepted synchronously.  A stale
                // presentation snapshot must therefore transfer that exact
                // RESTORE choice to recovery instead of leaving RESOLVING
                // authority with no reachable resolver.
                repository.abandonUndoCapabilityAfterProducerFailure(token)
                return@launch
            }
            val resolution = withDownloadWorkerExecutionSideEffectLease(
                downloadId = item.id,
                executionId = item.executionId,
            ) {
                withDownloadWorkerExecutionLock {
                    val current = dao.getNullableDownloadById(item.id)
                    if (
                        current == null ||
                            current.executionId != item.executionId ||
                            DownloadExecutionRecovery.hasPendingRecovery(application, item.id)
                    ) {
                        // Undo is a requeue publication.  It must wait for
                        // the exact cancellation execution's recovery debt;
                        // a later admission fence is not an authorization to
                        // restore Cancelled/E1 now.
                        null
                    } else {
                        repository.undoPendingCancellation(
                            item.id,
                            token,
                            originalStatus,
                            owner,
                        )
                    }
                }
            } ?: run {
                // The selected RESTORE cannot be executed by this UI attempt
                // (for example, exact execution recovery is still pending).
                // Preserve the selected intent under the token-scoped
                // same-process recovery owner.
                repository.abandonUndoCapabilityAfterProducerFailure(token)
                return@launch
            }
            LowQualityRedownloadLedger.refresh(application, resolution.affectedOperationIds)
            when (resolution.restoredStatus) {
                DownloadRepository.Status.Queued -> {
                    repository.getItemByID(item.id).let { restored ->
                        repository.startDownloadWorker(listOf(restored), application)
                    }
                }
                DownloadRepository.Status.WaitingForMembership -> {
                    notificationUtil.createMembershipWaiting(
                        item.id,
                        item.title.ifEmpty { item.url },
                        resources
                    )
                }
                else -> Unit
            }
        }
        return true
    }

    fun commitPendingCancellation(id: Long, token: String) =
        commitPendingCancellation(id, token, legacyUndoOwner())

    fun commitPendingCancellation(
        id: Long,
        token: String,
        owner: DownloadRepository.UndoPresentationOwner,
    ): Boolean {
        val accepted = try {
            runBlocking(Dispatchers.IO) {
                val result = try {
                    repository.acceptCancellationUndoResolution(
                        token,
                        PendingUndoResolutionIntent.COMMIT,
                        owner,
                    )
                } catch (failure: Throwable) {
                    repository.abandonUndoCapabilityAfterProducerFailure(token)
                    false
                }
                if (
                    !result &&
                        !repository.hasDurableUndoResolutionIntent(
                            token,
                            PendingUndoResolutionIntent.COMMIT,
                        )
                ) {
                    repository.reofferCancellationUndoCapabilityAfterResolutionFailure(
                        token,
                        PendingUndoResolutionIntent.COMMIT,
                        owner,
                    )
                }
                result
            }
        } catch (failure: Throwable) {
            repository.abandonUndoCapabilityAfterProducerFailure(token)
            false
        }
        if (!accepted) return false
        viewModelScope.launch(Dispatchers.IO) {
            LowQualityRedownloadLedger.refresh(
                application,
                repository.commitPendingCancellation(id, token, owner)
            )
        }
        return true
    }

    suspend fun pauseDownload(id: Long)  {
        withContext(Dispatchers.IO) {
            val expectedExecutionId = dao.getNullableDownloadById(id)?.executionId.orEmpty()
            var recoveryRecorded = false
            try {
                withDownloadWorkerExecutionSideEffectLease(id, expectedExecutionId) {
                    val semanticResult = withDownloadWorkerExecutionLock {
                        val current = dao.getNullableDownloadById(id)
                        if (current == null || current.executionId != expectedExecutionId) {
                            return@withDownloadWorkerExecutionLock null
                        }
                        check(
                            DownloadExecutionRecovery.recordPending(
                                context = application,
                                item = current,
                                disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                                phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
                            )
                        ) {
                            "Could not persist pause recovery responsibility for ${current.id}"
                        }
                        recoveryRecorded = true
                        repository.convergeUserStopSemantic(
                            id = id,
                            expectedExecutionId = expectedExecutionId,
                            disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                        )
                    }
                    when (val outcome = semanticResult?.outcome) {
                        DownloadRepository.UserStopSemanticOutcome.USER_STOP_COMMITTED,
                        DownloadRepository.UserStopSemanticOutcome.USER_STOP_ALREADY_SATISFIED -> {
                            check(
                                cancelDownloadOnlyOwned(
                                    id,
                                    expectedExecutionId,
                                    recoveryRecorded = true,
                                    stopDisposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                                )
                            ) {
                                "Native quiescence remained unresolved for paused download $id"
                            }
                        }
                        DownloadRepository.UserStopSemanticOutcome.COMMITTED_HISTORY_ALREADY_WON -> {
                            check(
                                DownloadExecutionRecovery.convergeCommittedHistoryFinalization(
                                    context = application,
                                    dbManager = dbManager,
                                    downloadId = id,
                                    executionId = expectedExecutionId,
                                )
                            ) {
                                "Committed History finalization remained unresolved for $id"
                            }
                        }
                        DownloadRepository.UserStopSemanticOutcome.STRONGER_USER_CANCEL_ALREADY_WON -> {
                            check(
                                DownloadExecutionRecovery.convergeUserStopBeforeGenericCleanup(
                                    context = application,
                                    dbManager = dbManager,
                                    downloadId = id,
                                    executionId = expectedExecutionId,
                                )
                            ) {
                                "Stronger Cancel convergence remained unresolved for $id"
                            }
                        }
                        DownloadRepository.UserStopSemanticOutcome.OWNERSHIP_LOST,
                        null -> Unit
                        is DownloadRepository.UserStopSemanticOutcome.RETRYABLE_PERSISTENCE_FAILURE -> {
                            throw outcome.error
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (recoveryRecorded) {
                    DownloadExecutionRecovery.retainRecoveryResponsibility(
                        context = application,
                        downloadId = id,
                        dbManager = dbManager,
                        failure = cancelled,
                    )
                }
                throw cancelled
            } catch (failure: Exception) {
                if (recoveryRecorded) {
                    DownloadExecutionRecovery.retainRecoveryResponsibility(
                        context = application,
                        downloadId = id,
                        dbManager = dbManager,
                        failure = failure,
                    )
                }
                throw failure
            }
        }
    }

    suspend fun pauseAllDownloads() {
        pausedAllDownloads.value = PausedAllDownloadsState.PROCESSING
        isPausingResuming = true
        val activeDownloadsList = withContext(Dispatchers.IO){
            withDownloadWorkerExecutionLock {
                getActiveAndPostProcessingDownloads()
            }
        }
        var firstFailure: Exception? = null
        if (activeDownloadsList.isNotEmpty()) {
            withContext(Dispatchers.IO){
                activeDownloadsList.forEach { item ->
                    try {
                        withDownloadWorkerExecutionSideEffectLease(item.id, item.executionId) {
                            val semanticResult = withDownloadWorkerExecutionLock {
                                val current = dao.getNullableDownloadById(item.id)
                                if (current?.executionId != item.executionId) {
                                    return@withDownloadWorkerExecutionLock null
                                }
                                check(
                                    DownloadExecutionRecovery.recordPending(
                                        context = application,
                                        item = current,
                                        disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                                        phase = DownloadExecutionRecovery.RecoveryPhase.SEMANTIC_STOP_PENDING,
                                    )
                                ) {
                                        "Could not persist pause recovery responsibility for ${current.id}"
                                }
                                repository.convergeUserStopSemantic(
                                    id = item.id,
                                    expectedExecutionId = item.executionId,
                                    disposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                                )
                            }
                            when (val outcome = semanticResult?.outcome) {
                                DownloadRepository.UserStopSemanticOutcome.USER_STOP_COMMITTED,
                                DownloadRepository.UserStopSemanticOutcome.USER_STOP_ALREADY_SATISFIED -> {
                                    check(
                                        cancelDownloadOnlyOwned(
                                            item.id,
                                            item.executionId,
                                            recoveryRecorded = true,
                                            stopDisposition = DownloadExecutionRecovery.RecoveryDisposition.USER_PAUSE,
                                        )
                                    ) {
                                        "Native quiescence remained unresolved for paused download ${item.id}"
                                    }
                                }
                                DownloadRepository.UserStopSemanticOutcome.COMMITTED_HISTORY_ALREADY_WON -> {
                                    check(
                                        DownloadExecutionRecovery.convergeCommittedHistoryFinalization(
                                            context = application,
                                            dbManager = dbManager,
                                            downloadId = item.id,
                                            executionId = item.executionId,
                                        )
                                    ) {
                                        "Committed History finalization remained unresolved for ${item.id}"
                                    }
                                }
                                DownloadRepository.UserStopSemanticOutcome.STRONGER_USER_CANCEL_ALREADY_WON -> {
                                    check(
                                        DownloadExecutionRecovery.convergeUserStopBeforeGenericCleanup(
                                            context = application,
                                            dbManager = dbManager,
                                            downloadId = item.id,
                                            executionId = item.executionId,
                                        )
                                    ) {
                                        "Stronger Cancel convergence remained unresolved for ${item.id}"
                                    }
                                }
                                DownloadRepository.UserStopSemanticOutcome.OWNERSHIP_LOST,
                                null -> {
                                    error("Pause semantic ownership was lost for ${item.id}")
                                }
                                is DownloadRepository.UserStopSemanticOutcome.RETRYABLE_PERSISTENCE_FAILURE -> {
                                    throw outcome.error
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        DownloadExecutionRecovery.retainRecoveryResponsibility(
                            context = application,
                            downloadId = item.id,
                            dbManager = dbManager,
                            failure = cancelled,
                        )
                        firstFailure = firstFailure?.also {
                            if (it !== cancelled) it.addSuppressed(cancelled)
                        } ?: cancelled
                    } catch (failure: Exception) {
                        DownloadExecutionRecovery.retainRecoveryResponsibility(
                            context = application,
                            downloadId = item.id,
                            dbManager = dbManager,
                            failure = failure,
                        )
                        firstFailure = firstFailure?.also {
                            if (it !== failure) it.addSuppressed(failure)
                        } ?: failure
                    }
                }
            }
            firstFailure?.let { failure ->
                Log.w(
                    "DownloadViewModel",
                    "Pause-all did not quiesce every exact Download execution",
                    failure,
                )
            }
        }
        WorkManager.getInstance(application).cancelAllWorkByTag("download")
        delay(1000)
        isPausingResuming = false
        pausedAllDownloads.value = if (
            firstFailure == null &&
            DownloadExecutionRecovery.pendingDownloadIds(application).isEmpty()
        ) {
            PausedAllDownloadsState.RESUME
        } else {
            PausedAllDownloadsState.HIDDEN
        }
    }

    fun resumeAllDownloads() = viewModelScope.launch {
        pausedAllDownloads.value = PausedAllDownloadsState.PROCESSING
        isPausingResuming = true
        WorkManager.getInstance(application).cancelAllWorkByTag("download")
        val paused = withContext(Dispatchers.IO) {
            dao.getPausedDownloadsList()
        }

        withContext(Dispatchers.IO){
            paused.forEach { item ->
                convergePersistedHistoryRefusal(item.id)
                resumePausedDownloadAndWait(item.id, item.executionId)
            }
        }
        delay(1000)
        isPausingResuming = false
        pausedAllDownloads.value = if (
            DownloadExecutionRecovery.pendingDownloadIds(application).isEmpty() &&
                paused.none { item ->
                    DownloadExecutionRecovery.hasPendingRecovery(application, item.id)
                }
        ) {
            PausedAllDownloadsState.PAUSE
        } else {
            PausedAllDownloadsState.HIDDEN
        }
    }

    fun deleteAll() = viewModelScope.launch {
        cancelAllDownloadsImpl()
        LowQualityRedownloadLedger.refresh(application, repository.deleteAll())
    }

    fun cancelAllDownloads() = viewModelScope.launch {
        cancelAllDownloadsImpl()
    }

    private suspend fun cancelAllDownloadsImpl() {
        var firstFailure: Exception? = null
        try {
            WorkManager.getInstance(application).cancelAllWorkByTag("download")
        } catch (cancelled: CancellationException) {
            firstFailure = cancelled
        } catch (failure: Exception) {
            firstFailure = failure
        }

        try {
            cancelActiveQueued()
        } catch (cancelled: CancellationException) {
            firstFailure = firstFailure?.also {
                if (it !== cancelled) it.addSuppressed(cancelled)
            } ?: cancelled
        } catch (failure: Exception) {
            firstFailure = firstFailure?.also {
                if (it !== failure) it.addSuppressed(failure)
            } ?: failure
        }
        firstFailure?.let { throw it }
    }

    fun resumeDownload(itemID: Long) = viewModelScope.launch {
        kotlin.runCatching {
            val persistedItem = withContext(Dispatchers.IO) {
                dao.getNullableDownloadById(itemID)
            }
            val executionId = persistedItem?.takeIf {
                it.status == DownloadRepository.Status.Paused.name
            }?.executionId.orEmpty()
            if (executionId.isBlank()) return@runCatching
            resumePausedDownloadAndWait(itemID, executionId)
        }
    }
}
