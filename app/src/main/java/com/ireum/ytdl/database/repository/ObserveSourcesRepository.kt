package com.ireum.ytdl.database.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import com.ireum.ytdl.R
import com.ireum.ytdl.database.RestoreGate
import com.ireum.ytdl.database.RestoreMutationAdmission
import com.ireum.ytdl.database.RestoreTransactionCoordinator
import com.ireum.ytdl.database.RestoreTransactionCoordinator.RestoreReconciliationAuthority
import com.ireum.ytdl.database.dao.ObserveSourcesDao
import com.ireum.ytdl.database.models.observeSources.ObserveSourcesItem
import com.ireum.ytdl.util.Extensions.calculateNextTimeForObserving
import com.ireum.ytdl.work.ObserveSourceWorker
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

private const val RESTORE_SCHEDULER_ACCEPTANCE_TIMEOUT_MS = 5_000L

class ObserveSourcesRepository(
    private val observeSourcesDao: ObserveSourcesDao,
    private val workManager: WorkManager,
    private val sharedPreferences: SharedPreferences,
    private val context: Context? = null,
) {
    val items: Flow<List<ObserveSourcesItem>> = observeSourcesDao.getAllSourcesFlow()

    enum class SourceStatus { ACTIVE, STOPPED }
    enum class EveryCategory { HOUR, DAY, WEEK, MONTH }
    enum class FinalEffect { RUNTIME_PUBLICATION, DOWNLOAD_ADMISSION, DESTRUCTIVE_SYNC, SUCCESSOR_PUBLICATION }
    data class FinishRunResult(
        val committed: Boolean,
        val revokedMembershipDownloadIds: List<Long> = emptyList(),
    )

    sealed class GenerationResult<out T> {
        data class Current<T>(val value: T) : GenerationResult<T>()
        data object Stale : GenerationResult<Nothing>()
    }

    companion object {
        @Volatile
        internal var beforeFinalEffectForTesting: (suspend (FinalEffect, Long, Long) -> Unit)? = null

        @Volatile
        internal var afterDurableStopBeforeCancellationForTesting: (suspend (Long, Long) -> Unit)? = null

        @Volatile
        internal var observeRequestCreatedForTesting: ((Long, Data) -> Unit)? = null

        val everyCategoryName = mapOf(
            EveryCategory.HOUR to R.string.hour,
            EveryCategory.DAY to R.string.day,
            EveryCategory.WEEK to R.string.week,
            EveryCategory.MONTH to R.string.month,
        )
    }

    fun getAll(): List<ObserveSourcesItem> = observeSourcesDao.getAllSources()
    fun getByURL(url: String): ObserveSourcesItem = observeSourcesDao.getByURL(url)
    fun getByID(id: Long): ObserveSourcesItem = observeSourcesDao.getByID(id)
    fun getByIDOrNull(id: Long): ObserveSourcesItem? = observeSourcesDao.getByIDOrNull(id)

    suspend fun insert(item: ObserveSourcesItem): Long = withOrdinaryMutation {
        if (observeSourcesDao.checkIfExistsWithSameURL(item.url)) return@withOrdinaryMutation -1L
        observeSourcesDao.insert(item.copy(configurationGeneration = 1L))
    }

    suspend fun insertAndSchedule(item: ObserveSourcesItem): Long = withOrdinaryMutation {
        if (observeSourcesDao.checkIfExistsWithSameURL(item.url)) return@withOrdinaryMutation -1L
        val id = observeSourcesDao.insert(item.copy(configurationGeneration = 1L))
        if (id <= 0L) return@withOrdinaryMutation id
        val inserted = observeSourcesDao.getByIDOrNull(id) ?: return@withOrdinaryMutation -1L
        if (inserted.status == SourceStatus.ACTIVE && enqueueObservation(inserted, null) == null) {
            return@withOrdinaryMutation -1L
        }
        id
    }

    /**
     * Pre-generation WorkManager requests are compatibility triggers only: they
     * may schedule the currently durable ACTIVE generation, but never execute
     * using configuration captured by the legacy request.
     */
    suspend fun reconcileLegacyRequest(sourceId: Long, legacyRequestId: String): Boolean = withOrdinaryMutation {
        val current = observeSourcesDao.getByIDOrNull(sourceId)
            ?: return@withOrdinaryMutation true
        if (current.status != SourceStatus.ACTIVE) return@withOrdinaryMutation true
        val uniqueWork = workManager.getWorkInfosForUniqueWork("OBSERVE$sourceId")
            .get(RESTORE_SCHEDULER_ACCEPTANCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (uniqueWork.any { info ->
                info.id.toString() != legacyRequestId &&
                    !info.state.isFinished &&
                    info.inputData.getLong(ObserveSourceWorker.INPUT_CONFIGURATION_GENERATION, 0L) ==
                    current.configurationGeneration
            }
        ) return@withOrdinaryMutation true
        enqueueObservation(current, null) != null
    }

    /** CASes the UI's configuration snapshot and schedules only its new generation. */
    suspend fun reconfigure(
        item: ObserveSourcesItem,
        resetProcessedLinks: Boolean,
        resetRunCount: Boolean = false,
    ): Boolean =
        withOrdinaryMutation {
            val updated = observeSourcesDao.advanceUserConfigurationIfGeneration(
                id = item.id,
                expectedGeneration = item.configurationGeneration,
                name = item.name,
                url = item.url,
                downloadItemTemplate = item.downloadItemTemplate,
                everyNr = item.everyNr,
                everyCategory = item.everyCategory,
                everyTime = item.everyTime,
                weeklyConfig = item.weeklyConfig,
                monthlyConfig = item.monthlyConfig,
                startsTime = item.startsTime,
                endsDate = item.endsDate,
                endsAfterCount = item.endsAfterCount,
                getOnlyNewUploads = item.getOnlyNewUploads,
                retryMissingDownloads = item.retryMissingDownloads,
                syncWithSource = item.syncWithSource,
                excludeShorts = item.excludeShorts,
                autoAddKeyword = item.autoAddKeyword,
                resetRunCount = resetRunCount,
                resetProcessedLinks = resetProcessedLinks,
            )
            if (updated != 1) return@withOrdinaryMutation false
            val current = observeSourcesDao.getByIDOrNull(item.id) ?: return@withOrdinaryMutation false
            enqueueObservation(current, null) != null
        }

    suspend fun stop(item: ObserveSourcesItem): List<Long>? = withOrdinaryMutation {
        val cancelledIds = observeSourcesDao.stopAndCancelWaitingIfGeneration(
            item.id,
            item.configurationGeneration,
        ) ?: return@withOrdinaryMutation null
        afterDurableStopBeforeCancellationForTesting?.invoke(
            item.id,
            item.configurationGeneration + 1L,
        )
        cancelObservationTaskByIDInternal(item.id)
        cancelledIds
    }

    suspend fun delete(item: ObserveSourcesItem): List<Long>? = withOrdinaryMutation {
        val cancelledIds = observeSourcesDao.deleteAndCancelWaitingIfGeneration(
            item.id,
            item.configurationGeneration,
        ) ?: return@withOrdinaryMutation null
        cancelObservationTaskByIDInternal(item.id)
        cancelledIds
    }

    suspend fun deleteAll(): List<Long> = withOrdinaryMutation {
        val cancelledIds = observeSourcesDao.deleteAllAndCancelWaiting()
        cancelAllObservationTasksInternal()
        cancelledIds
    }

    suspend fun updateRestoredTemplate(item: ObserveSourcesItem): Boolean = withOrdinaryMutation {
        observeSourcesDao.updateTemplateForRestore(item.id, item.downloadItemTemplate) == 1
    }

    suspend fun publishRuntimeIfCurrent(item: ObserveSourcesItem): Boolean {
        beforeFinalEffectForTesting?.invoke(
            FinalEffect.RUNTIME_PUBLICATION,
            item.id,
            item.configurationGeneration,
        )
        return withOrdinaryMutation {
        if (!observeSourcesDao.isActiveGeneration(item.id, item.configurationGeneration)) {
            return@withOrdinaryMutation false
        }
        updateRuntime(item) == 1
        }
    }

    /**
     * The generation check and short final publication share admission with
     * edit/STOP/delete. Long extraction and network work must remain outside.
     */
    suspend fun <T> withActiveGeneration(
        sourceId: Long,
        expectedGeneration: Long,
        finalEffect: FinalEffect? = null,
        block: suspend () -> T,
    ): GenerationResult<T> {
        finalEffect?.let { beforeFinalEffectForTesting?.invoke(it, sourceId, expectedGeneration) }
        return withOrdinaryMutation {
        if (!observeSourcesDao.isActiveGeneration(sourceId, expectedGeneration)) {
            GenerationResult.Stale
        } else {
            GenerationResult.Current(block())
        }
        }
    }

    suspend fun withAutomaticallyStoppedGeneration(
        previousGeneration: Long,
        sourceId: Long,
        block: suspend () -> Unit,
    ): Boolean = withOrdinaryMutation {
        val current = observeSourcesDao.getByIDOrNull(sourceId)
        if (current?.status != SourceStatus.STOPPED ||
            current.configurationGeneration != previousGeneration + 1L
        ) return@withOrdinaryMutation false
        block()
        true
    }

    /** Runtime publication and recurring successor are one generation-fenced boundary. */
    suspend fun finishRunAndSchedule(
        item: ObserveSourcesItem,
        revoke: Boolean,
    ): FinishRunResult {
        beforeFinalEffectForTesting?.invoke(
            FinalEffect.SUCCESSOR_PUBLICATION,
            item.id,
            item.configurationGeneration,
        )
        return withOrdinaryMutation {
        val revokedMembershipDownloadIds = if (revoke) {
            observeSourcesDao.finishAndRevokeAndCancelWaitingIfGeneration(item)
                ?: return@withOrdinaryMutation FinishRunResult(committed = false)
        } else {
            if (updateRuntime(item) != 1) {
                return@withOrdinaryMutation FinishRunResult(committed = false)
            }
            emptyList()
        }
        if (revoke) {
            cancelObservationTaskByIDInternal(item.id)
            return@withOrdinaryMutation FinishRunResult(
                committed = true,
                revokedMembershipDownloadIds = revokedMembershipDownloadIds,
            )
        }
        val current = observeSourcesDao.getByIDOrNull(item.id)
            ?: return@withOrdinaryMutation FinishRunResult(committed = false)
        FinishRunResult(committed = enqueueObservation(current, null) != null)
        }
    }

    fun cancelObservationTaskByID(id: Long) {
        val appContext = context?.applicationContext ?: return
        RestoreMutationAdmission.withOrdinaryMutationBlocking(appContext) {
            cancelObservationTaskByIDInternal(id)
        }
    }

    fun observeTask(item: ObserveSourcesItem) {
        val appContext = context?.applicationContext
        if (appContext == null) {
            val current = observeSourcesDao.getByIDOrNull(item.id) ?: return
            if (current.configurationGeneration == item.configurationGeneration) enqueueObservation(current, null)
        } else {
            RestoreMutationAdmission.withOrdinaryMutationBlocking(appContext) {
                val current = observeSourcesDao.getByIDOrNull(item.id) ?: return@withOrdinaryMutationBlocking
                if (current.configurationGeneration == item.configurationGeneration) enqueueObservation(current, null)
            }
        }
    }

    /** Ordinary callers receive only the enqueue acceptance boundary. */
    suspend fun observeTaskAndAwait(item: ObserveSourcesItem): Boolean = withOrdinaryMutation {
        val current = observeSourcesDao.getByIDOrNull(item.id)
            ?.takeIf { it.status == SourceStatus.ACTIVE && it.configurationGeneration == item.configurationGeneration }
            ?: return@withOrdinaryMutation false
        val operation = enqueueObservation(current, null) ?: return@withOrdinaryMutation false
        operation.result.get(RESTORE_SCHEDULER_ACCEPTANCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        true
    }

    internal suspend fun cancelObservationTaskByIDForRestore(
        id: Long,
        authority: RestoreReconciliationAuthority,
    ) {
        RestoreMutationAdmission.withRestoreMutation {
            RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(
                requireNotNull(context).applicationContext,
                authority,
            )
            cancelObservationTaskByIDInternal(id)
        }
    }

    internal suspend fun observeTaskAndAwaitForRestore(
        item: ObserveSourcesItem,
        authority: RestoreReconciliationAuthority,
    ): Boolean = RestoreMutationAdmission.withRestoreMutation {
        val appContext = requireNotNull(context).applicationContext
        RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(appContext, authority)
        val current = observeSourcesDao.getByIDOrNull(item.id)
            ?.takeIf { it.status == SourceStatus.ACTIVE && it.configurationGeneration == item.configurationGeneration }
            ?: return@withRestoreMutation false
        val operation = enqueueObservation(current, authority) ?: return@withRestoreMutation false
        operation.result.get(RESTORE_SCHEDULER_ACCEPTANCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        true
    }

    private fun enqueueObservation(
        item: ObserveSourcesItem,
        authority: RestoreReconciliationAuthority?,
    ): Operation? {
        val appContext = context?.applicationContext
        if (authority == null) {
            if (appContext != null && RestoreGate.isRestoreInProgress(appContext)) return null
            if (item.status != SourceStatus.ACTIVE ||
                !observeSourcesDao.isActiveGeneration(item.id, item.configurationGeneration)
            ) return null
        } else {
            RestoreTransactionCoordinator.requireCurrentReconciliationAuthority(
                requireNotNull(appContext),
                authority,
            )
        }
        cancelObservationTaskByIDInternal(item.id)

        val nextRunAt = item.calculateNextTimeForObserving()
        val initialDelay = (nextRunAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val allowMeteredNetworks = sharedPreferences.getBoolean("metered_networks", true)
        val workConstraints = Constraints.Builder().apply {
            setRequiredNetworkType(if (allowMeteredNetworks) NetworkType.CONNECTED else NetworkType.UNMETERED)
        }.build()
        val inputData = Data.Builder()
            .putLong(ObserveSourceWorker.INPUT_SOURCE_ID, item.id)
            .putLong(ObserveSourceWorker.INPUT_CONFIGURATION_GENERATION, item.configurationGeneration)
            .build()
        observeRequestCreatedForTesting?.invoke(item.id, inputData)
        val workRequest = OneTimeWorkRequestBuilder<ObserveSourceWorker>()
            .addTag("observeSources")
            .addTag(item.id.toString())
            .addTag("observation_${item.id}")
            .setConstraints(workConstraints)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .build()
        return workManager.enqueueUniqueWork(
            "OBSERVE${item.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest,
        )
    }

    private fun cancelObservationTaskByIDInternal(id: Long) {
        workManager.cancelUniqueWork("OBSERVE$id")
        workManager.cancelAllWorkByTag("observation_$id")
        workManager.cancelAllWorkByTag(id.toString())
    }

    private fun cancelAllObservationTasksInternal() {
        workManager.cancelAllWorkByTag("observeSources")
    }

    private suspend fun updateRuntime(item: ObserveSourcesItem): Int =
        observeSourcesDao.updateRuntimeIfGeneration(
            id = item.id,
            expectedGeneration = item.configurationGeneration,
            runCount = item.runCount,
            ignoredLinks = item.ignoredLinks,
            alreadyProcessedLinks = item.alreadyProcessedLinks,
            runHistory = item.runHistory,
            runInProgress = item.runInProgress,
            currentRunStatus = item.currentRunStatus,
            retryPromptedLinks = item.retryPromptedLinks,
            observedLinks = item.observedLinks,
        )

    private suspend fun <T> withOrdinaryMutation(block: suspend () -> T): T {
        val appContext = context?.applicationContext
        return if (appContext == null) block() else {
            RestoreMutationAdmission.withOrdinaryMutation(appContext, block)
        }
    }
}
