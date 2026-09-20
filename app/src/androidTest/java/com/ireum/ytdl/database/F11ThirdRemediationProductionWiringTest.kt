package com.ireum.ytdl.database

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ireum.ytdl.R
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.BackupSettingsItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.HistoryItem
import com.ireum.ytdl.database.models.RestoreAppDataItem
import com.ireum.ytdl.database.models.RestorePlan
import com.ireum.ytdl.util.BackupSettingsUtil
import com.ireum.ytdl.util.LocalAddEntryDto
import com.ireum.ytdl.util.LocalAddStorage
import com.ireum.ytdl.util.NavbarUtil
import com.ireum.ytdl.work.LocalAddResponsibilityReconciler
import com.ireum.ytdl.work.LocalAddWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-wiring evidence for the third F11 remediation wave.
 *
 * The tests deliberately use exact LocalAdd session/WorkManager identities and
 * an actual direct preference writer. They do not treat raw entry keys or
 * broad WorkManager tag counts as live ownership.
 */
@RunWith(AndroidJUnit4::class)
class F11ThirdRemediationProductionWiringTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var workManager: WorkManager
    private lateinit var database: DBManager
    private var originalPreferences: Map<String, Any?> = emptyMap()
    private val sessions = mutableListOf<String>()

    @Before
    fun setUp(): Unit = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        originalPreferences = preferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        workManager = WorkManager.getInstance(context)
        database = DBManager.getInstance(context)
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        runCatching { RestoreTransactionCoordinator.recover(context) }
        RestoreOperationStore.root(context).deleteRecursively()
        database.historyDao.nuke()
        database.downloadDao.deleteAll()
        clearLocalAddRuntime()
        clearHooks()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        clearHooks()
        runCatching { RestoreTransactionCoordinator.recover(context) }
        workManager.cancelAllWork().result.get(20, TimeUnit.SECONDS)
        sessions.forEach { sessionId ->
            runCatching { LocalAddStorage.retireSession(context, sessionId) }
        }
        val editor = preferences.edit().clear()
        originalPreferences.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit())
        RestoreOperationStore.root(context).deleteRecursively()
    }

    @Test
    fun legacyLiveMarkerlessSessionIsBackfilledAndReconstructed() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        sessions += sessionId
        val request = delayedLocalAddRequest(sessionId)
        LocalAddStorage.saveEntries(
            context,
            sessionId,
            listOf(LocalAddEntryDto("content://provider/document/$sessionId", null)),
        )
        workManager.enqueueUniqueWork(
            LocalAddStorage.uniqueWorkName(sessionId),
            ExistingWorkPolicy.KEEP,
            request,
        ).result.get(20, TimeUnit.SECONDS)
        val original = requireNotNull(
            workManager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)
        )
        assertFalse(original.state.isFinished)
        assertNull(LocalAddStorage.loadWorkOwner(context, sessionId))

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            historyPlan("https://example.com/f11-third-legacy-live"),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        val owner = requireNotNull(LocalAddStorage.loadWorkOwner(context, sessionId))
        assertEquals(LocalAddStorage.OWNER_ACCEPTED, owner.state)
        assertNotEquals(request.id.toString(), owner.requestId)
        val current = workManager.getWorkInfosForUniqueWork(owner.uniqueWorkName)
            .get(10, TimeUnit.SECONDS)
            .filter { !it.state.isFinished }
        assertEquals(1, current.size)
        assertEquals(owner.requestId, current.single().id.toString())
    }

    @Test
    fun staleMarkerlessEntriesAndFinishedWorkAreNeverRevived() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        sessions += sessionId
        LocalAddStorage.saveEntries(
            context,
            sessionId,
            listOf(LocalAddEntryDto("content://provider/document/$sessionId", null)),
        )
        val request = delayedLocalAddRequest(sessionId)
        workManager.enqueueUniqueWork(
            LocalAddStorage.uniqueWorkName(sessionId),
            ExistingWorkPolicy.KEEP,
            request,
        ).result.get(20, TimeUnit.SECONDS)
        workManager.cancelUniqueWork(LocalAddStorage.uniqueWorkName(sessionId))
            .result.get(20, TimeUnit.SECONDS)
        assertTrue(
            requireNotNull(workManager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS))
                .state.isFinished
        )

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            historyPlan("https://example.com/f11-third-legacy-stale"),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertNull(LocalAddStorage.loadWorkOwner(context, sessionId))
        assertTrue(
            workManager.getWorkInfosForUniqueWork(LocalAddStorage.uniqueWorkName(sessionId))
                .get(10, TimeUnit.SECONDS)
                .none { !it.state.isFinished }
        )
    }

    @Test
    fun settingsOnlyResetPreservesLiveLocalAddRuntimeAuthority() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        sessions += sessionId
        val request = delayedLocalAddRequest(sessionId)
        LocalAddResponsibilityReconciler.publishSession(
            context,
            sessionId,
            request,
            listOf(LocalAddEntryDto("content://provider/document/$sessionId", null)),
        )
        val before = requireNotNull(LocalAddStorage.loadWorkOwner(context, sessionId))

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            settingsPlan(
                BackupSettingsItem("proxy", "f11-third-proxy", "String"),
            ),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        val after = requireNotNull(LocalAddStorage.loadWorkOwner(context, sessionId))
        assertEquals(before.requestId, after.requestId)
        assertEquals(
            1,
            workManager.getWorkInfosForUniqueWork(after.uniqueWorkName)
                .get(10, TimeUnit.SECONDS)
                .count { !it.state.isFinished },
        )
        assertTrue(LocalAddStorage.loadEntries(context, sessionId).isNotEmpty())
    }

    @Test
    fun backupAndHistoricalImportExcludeLocalAddRuntimeAuthority() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        sessions += sessionId
        LocalAddStorage.saveEntries(
            context,
            sessionId,
            listOf(LocalAddEntryDto("content://provider/document/$sessionId", null)),
        )
        val ownerKey = "local_add_owner_$sessionId"
        val entriesKey = "local_add_entries_$sessionId"
        val backup = BackupSettingsUtil.backupSettings(preferences).getOrThrow()
        assertTrue(backup.none { item ->
            val key = item.asJsonObject.get("key").asString
            key == ownerKey || key == entriesKey
        })

        val foreignKey = "local_add_entries_foreign-installation"
        val outcome = RestoreTransactionCoordinator.begin(
            context,
            settingsPlan(
                BackupSettingsItem(foreignKey, "[]", "String"),
            ),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertFalse(preferences.contains(foreignKey))
        assertTrue(preferences.contains(entriesKey))
    }

    @Test
    fun localAddProducerWinsBeforeRestorePublication() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        sessions += sessionId
        val request = delayedLocalAddRequest(sessionId)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }

        val scope = CoroutineScope(currentCoroutineContext())
        val producer = scope.async(Dispatchers.IO) {
            LocalAddResponsibilityReconciler.publishSession(
                context,
                sessionId,
                request,
                listOf(LocalAddEntryDto("content://provider/document/$sessionId", null)),
            )
        }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val reset = scope.async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(
                context,
                historyPlan("https://example.com/f11-third-producer-wins"),
            )
        }
        assertFalse(reset.isCompleted)
        release.countDown()
        producer.await()
        assertTrue(reset.await() is RestoreOutcome.Completed)

        val owner = requireNotNull(LocalAddStorage.loadWorkOwner(context, sessionId))
        assertEquals(LocalAddStorage.OWNER_ACCEPTED, owner.state)
        assertEquals(
            1,
            workManager.getWorkInfosForUniqueWork(owner.uniqueWorkName)
                .get(10, TimeUnit.SECONDS)
                .count { !it.state.isFinished },
        )
    }

    @Test
    fun restoreWinsBeforeLateLocalAddPublication() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        sessions += sessionId
        val request = delayedLocalAddRequest(sessionId)
        lateinit var lateProducer: Deferred<Result<Unit>>
        val scope = CoroutineScope(currentCoroutineContext())
        val started = CountDownLatch(1)
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            lateProducer = scope.async(Dispatchers.IO) {
                started.countDown()
                runCatching {
                    LocalAddResponsibilityReconciler.publishSession(
                        context,
                        sessionId,
                        request,
                        listOf(LocalAddEntryDto("content://provider/document/$sessionId", null)),
                    )
                }
            }
        }

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            historyPlan("https://example.com/f11-third-restore-wins"),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertTrue(started.await(20, TimeUnit.SECONDS))
        assertTrue(lateProducer.await().isFailure)
        assertNull(LocalAddStorage.loadWorkOwner(context, sessionId))
        assertTrue(LocalAddStorage.loadEntries(context, sessionId).isEmpty())
    }

    @Test
    fun retiredLocalAddSessionIsNotResurrectedByRestore() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        sessions += sessionId
        val request = delayedLocalAddRequest(sessionId)
        LocalAddResponsibilityReconciler.publishSession(
            context,
            sessionId,
            request,
            listOf(LocalAddEntryDto("content://provider/document/$sessionId", null)),
        )
        LocalAddResponsibilityReconciler.retireAndCancel(context, sessionId)

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            historyPlan("https://example.com/f11-third-retired"),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertEquals(
            LocalAddStorage.OWNER_RETIRED,
            LocalAddStorage.loadWorkOwner(context, sessionId)?.state,
        )
        assertTrue(
            workManager.getWorkInfosForUniqueWork(LocalAddStorage.uniqueWorkName(sessionId))
                .get(10, TimeUnit.SECONDS)
                .none { !it.state.isFinished }
        )
    }

    @Test
    fun directPortablePreferenceWriterWinsBeforeRestorePublication() = runBlocking {
        NavbarUtil.init(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }

        val scope = CoroutineScope(currentCoroutineContext())
        val writer = scope.async(Dispatchers.IO) {
            NavbarUtil.setStartFragment(R.id.historyFragment)
        }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        val reset = scope.async(Dispatchers.IO) {
            RestoreTransactionCoordinator.begin(
                context,
                settingsPlan(BackupSettingsItem("start_destination", "Home", "String")),
            )
        }
        assertFalse(reset.isCompleted)
        release.countDown()
        writer.await()
        assertEquals("History", preferences.getString("start_destination", null))
        assertTrue(reset.await() is RestoreOutcome.Completed)
        assertEquals("Home", preferences.getString("start_destination", null))
    }

    @Test
    fun restoreWinsBeforeDirectPortablePreferenceWriter() = runBlocking {
        NavbarUtil.init(context)
        check(preferences.edit().putString("start_destination", "Home").commit())
        val writerStarted = CountDownLatch(1)
        var writerFailure: Throwable? = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = {
            writerStarted.countDown()
            writerFailure = runCatching {
                NavbarUtil.setStartFragment(R.id.historyFragment)
            }.exceptionOrNull()
        }

        val outcome = RestoreTransactionCoordinator.begin(
            context,
            settingsPlan(BackupSettingsItem("start_destination", "Queue", "String")),
        )

        assertTrue(outcome is RestoreOutcome.Completed)
        assertTrue(writerStarted.await(20, TimeUnit.SECONDS))
        assertTrue(writerFailure is IllegalStateException)
        assertEquals("Queue", preferences.getString("start_destination", null))
    }

    private fun delayedLocalAddRequest(sessionId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<LocalAddWorker>()
            .setInitialDelay(1L, TimeUnit.HOURS)
            .setInputData(workDataOf(LocalAddWorker.KEY_SESSION_ID to sessionId))
            .addTag(LocalAddWorker.TAG)
            .build()

    private fun settingsPlan(vararg settings: BackupSettingsItem): RestorePlan =
        BackupRestoreParser.fromTyped(
            RestoreAppDataItem(settings = settings.toList()),
        )

    private fun historyPlan(url: String): RestorePlan =
        BackupRestoreParser.fromTyped(
            RestoreAppDataItem(
                downloads = listOf(
                    HistoryItem(
                        id = 1L,
                        url = url,
                        title = "F11 Third Reset",
                        author = "F11",
                        duration = "1:00",
                        durationSeconds = 60L,
                        thumb = "",
                        type = DownloadType.video,
                        time = System.currentTimeMillis() / 1000L,
                        downloadPath = listOf("/destination/$url"),
                        website = "F11",
                        format = Format(container = "mp4"),
                        filesize = 3L,
                        downloadId = 0L,
                    ),
                ),
            ),
        )

    private fun clearLocalAddRuntime() {
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith("local_add_") }
            .forEach(editor::remove)
        check(editor.commit())
    }

    private fun clearHooks() {
        RestoreMutationAdmission.ordinaryAuthorityAcquiredForTesting = null
        RestoreMutationAdmission.restorePublicationAuthorityAcquiredForTesting = null
        RestoreTransactionCoordinator.afterPreparedBeforeQuiescenceForTesting = null
        RestoreTransactionCoordinator.afterQuiescedBeforeFilesReadyForTesting = null
        RestoreTransactionCoordinator.afterFilesReadyBeforeApplyForTesting = null
        RestoreTransactionCoordinator.afterRoomCommitBeforeJournalForTesting = null
        RestoreTransactionCoordinator.afterDataCommittedBeforeReconciliationForTesting = null
        RestoreTransactionCoordinator.afterReconciliationBeforeCompleteForTesting = null
        RestoreTransactionCoordinator.afterCompleteBeforeRetirementForTesting = null
    }
}
