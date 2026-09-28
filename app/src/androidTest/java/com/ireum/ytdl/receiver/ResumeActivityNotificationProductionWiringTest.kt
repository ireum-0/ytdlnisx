package com.ireum.ytdl.receiver

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Bundle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ireum.ytdl.database.Converters
import com.ireum.ytdl.database.DBManager
import com.ireum.ytdl.database.dao.DownloadClaimTestHooks
import com.ireum.ytdl.database.enums.DownloadType
import com.ireum.ytdl.database.models.AudioPreferences
import com.ireum.ytdl.database.models.DownloadItem
import com.ireum.ytdl.database.models.Format
import com.ireum.ytdl.database.models.VideoPreferences
import com.ireum.ytdl.database.repository.DownloadRepository
import com.ireum.ytdl.database.viewmodel.DownloadViewModel
import com.ireum.ytdl.util.NotificationUtil
import com.ireum.ytdl.util.download.DownloadIssueCode
import com.ireum.ytdl.work.DownloadWorkerEffectTestHooks
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResumeActivityNotificationProductionWiringTest {
    @Test
    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 25)
    fun resumeAndRetryNotificationsReachExactCapabilitiesOnApi24And25() = runBlocking {
        exerciseNotificationCapabilities()
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun resumeAndRetryNotificationsReachExactCapabilitiesOnApi26AndAbove() = runBlocking {
        exerciseNotificationCapabilities()
    }

    private suspend fun exerciseNotificationCapabilities() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertFalse("test requires no overlay authority", Settings.canDrawOverlays(application))
        val requestedPermissions = application.packageManager
            .getPackageInfo(application.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
        assertFalse(requestedPermissions.contains(Manifest.permission.SYSTEM_ALERT_WINDOW))
        val activityInfo = application.packageManager.getActivityInfo(
            ComponentName(application, ResumeActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        assertFalse("ResumeActivity must remain non-exported", activityInfo.exported)

        val database = Room.inMemoryDatabaseBuilder(application, DBManager::class.java)
            .addTypeConverter(Converters())
            .allowMainThreadQueries()
            .build()
        val previousDatabaseOverride = DownloadWorkerEffectTestHooks.dbManagerForTesting
        val previousFactory = ResumeActivity.downloadViewModelFactoryForTesting
        DownloadWorkerEffectTestHooks.dbManagerForTesting = database
        ResumeActivity.downloadViewModelFactoryForTesting = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                check(modelClass == DownloadViewModel::class.java)
                return DownloadViewModel(application, database, true) as T
            }
        }

        try {
            exerciseResume(application, database, tokenKind = ResumeTokenKind.EXACT)
            exerciseResume(application, database, tokenKind = ResumeTokenKind.STALE)
            exerciseResume(application, database, tokenKind = ResumeTokenKind.MISSING)
            exerciseRetry(application, database, tokenKind = RetryTokenKind.EXACT)
            exerciseRetry(application, database, tokenKind = RetryTokenKind.STALE_OPERATION)
            exerciseRetry(application, database, tokenKind = RetryTokenKind.STALE_ATTEMPT)
        } finally {
            ResumeActivity.downloadViewModelFactoryForTesting = previousFactory
            DownloadWorkerEffectTestHooks.dbManagerForTesting = previousDatabaseOverride
            database.close()
        }
    }

    private suspend fun exerciseResume(
        application: Application,
        database: DBManager,
        tokenKind: ResumeTokenKind,
    ) {
        val executionId = "resume-notification-E1-${UUID.randomUUID()}"
        val token = when (tokenKind) {
            ResumeTokenKind.EXACT -> executionId
            ResumeTokenKind.STALE -> "stale-$executionId"
            ResumeTokenKind.MISSING -> null
        }
        val id = database.downloadDao.insertRaw(
            download(
                status = DownloadRepository.Status.Paused.name,
                executionId = executionId,
            ),
        )
        val notificationId = NotificationUtil.DOWNLOAD_RESUME_NOTIFICATION_ID + id.toInt()
        val wasAccepted = tokenKind == ResumeTokenKind.EXACT
        var existingWorkIds: Set<UUID>? = null
        try {
            existingWorkIds = downloadWorkIds(application)
            NotificationUtil(application).createResumeDownload(id.toInt(), "Resume test", token)
            dispatchPostedAction(application, notificationId)

            val after = requireNotNull(database.downloadDao.getNullableDownloadById(id))
            if (wasAccepted) {
                assertNotEquals(DownloadRepository.Status.Paused.name, after.status)
                assertFalse(isNotificationActive(application, notificationId))
            } else {
                assertEquals(DownloadRepository.Status.Paused.name, after.status)
                assertEquals(executionId, after.executionId)
                assertTrue(isNotificationActive(application, notificationId))
            }
        } finally {
            existingWorkIds?.let { cancelNewDownloadWork(application, it) }
            NotificationUtil(application).cancelDownloadNotification(notificationId)
            database.downloadDao.delete(id)
        }
    }

    private suspend fun exerciseRetry(
        application: Application,
        database: DBManager,
        tokenKind: RetryTokenKind,
    ) {
        val operationId = "retry-notification-operation-${UUID.randomUUID()}"
        val attempt = 0
        val executionId = "retry-notification-E1-${UUID.randomUUID()}"
        val tokenOperationId = when (tokenKind) {
            RetryTokenKind.EXACT, RetryTokenKind.STALE_ATTEMPT -> operationId
            RetryTokenKind.STALE_OPERATION -> "stale-$operationId"
        }
        val tokenAttempt = if (tokenKind == RetryTokenKind.STALE_ATTEMPT) attempt + 1 else attempt
        val id = database.downloadDao.insertRaw(
            download(
                status = DownloadRepository.Status.Error.name,
                executionId = executionId,
                operationId = operationId,
                retryAttempt = attempt,
                lastIssueCode = DownloadIssueCode.NETWORK_TIMEOUT.name,
            ),
        )
        val notificationId = NotificationUtil.DOWNLOAD_ERRORED_NOTIFICATION_ID + id.toInt()
        val wasAccepted = tokenKind == RetryTokenKind.EXACT
        var existingWorkIds: Set<UUID>? = null
        try {
            existingWorkIds = downloadWorkIds(application)
            NotificationUtil(application).createDownloadErrored(
                id = id,
                title = "Retry test",
                error = "network timeout",
                logID = null,
                res = application.resources,
                retryable = true,
                allowReconfigure = false,
                retryCapabilityOperationId = tokenOperationId,
                retryCapabilityAttempt = tokenAttempt,
            )
            dispatchPostedAction(application, notificationId)

            val after = requireNotNull(database.downloadDao.getNullableDownloadById(id))
            if (wasAccepted) {
                assertEquals(operationId, after.operationId)
                assertEquals(attempt + 1, after.retryAttempt)
                assertFalse(isNotificationActive(application, notificationId))
            } else {
                assertEquals(DownloadRepository.Status.Error.name, after.status)
                assertEquals(operationId, after.operationId)
                assertEquals(attempt, after.retryAttempt)
                assertTrue(isNotificationActive(application, notificationId))
            }
        } finally {
            existingWorkIds?.let { cancelNewDownloadWork(application, it) }
            NotificationUtil(application).cancelDownloadNotification(notificationId)
            database.downloadDao.delete(id)
        }
    }

    private fun dispatchPostedAction(application: Application, notificationId: Int) {
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        val notification = notificationManager.activeNotifications
            .firstOrNull { it.id == notificationId }
            ?.notification
        assertNotNull("NotificationUtil did not post notification $notificationId", notification)
        val pendingIntent = requireNotNull(notification?.actions?.singleOrNull()?.actionIntent)
        val activityCreated = CountDownLatch(1)
        val activityDestroyed = CountDownLatch(1)
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {
                if (activity is ResumeActivity) activityCreated.countDown()
            }

            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (activity is ResumeActivity) activityDestroyed.countDown()
            }
        }
        application.registerActivityLifecycleCallbacks(observer)
        try {
            pendingIntent.send()
            assertTrue("ResumeActivity was not launched", activityCreated.await(10, TimeUnit.SECONDS))
            assertTrue("ResumeActivity did not finish its capability decision", activityDestroyed.await(20, TimeUnit.SECONDS))
        } finally {
            application.unregisterActivityLifecycleCallbacks(observer)
        }
    }

    private suspend fun downloadWorkIds(application: Application): Set<UUID> = withContext(Dispatchers.IO) {
        WorkManager.getInstance(application)
            .getWorkInfosByTag("download")
            .get(20, TimeUnit.SECONDS)
            .map { it.id }
            .toSet()
    }

    private suspend fun cancelNewDownloadWork(application: Application, existingIds: Set<UUID>) {
        val workManager = WorkManager.getInstance(application)
        val addedIds = withContext(Dispatchers.IO) {
            workManager.getWorkInfosByTag("download")
                .get(20, TimeUnit.SECONDS)
                .filter { it.id !in existingIds && !it.state.isFinished }
                .map { it.id }
        }
        val operations = addedIds.map(workManager::cancelWorkById)
        operations.forEach { it.result.get(20, TimeUnit.SECONDS) }
        if (addedIds.isNotEmpty()) {
            withTimeout(20_000L) {
                while (withContext(Dispatchers.IO) {
                        addedIds.any { workManager.getWorkInfoById(it).get(10, TimeUnit.SECONDS)?.state?.isFinished == false }
                    }
                ) {
                    delay(25L)
                }
            }
        }
    }

    private fun isNotificationActive(application: Application, notificationId: Int): Boolean =
        application.getSystemService(NotificationManager::class.java)
            .activeNotifications.any { it.id == notificationId }

    private fun download(
        status: String,
        executionId: String,
        operationId: String = "",
        retryAttempt: Int = 0,
        lastIssueCode: String = "",
    ) = DownloadItem(
        id = 0L,
        url = "https://example.invalid/${UUID.randomUUID()}",
        title = "Resume notification production wiring",
        author = "test",
        thumb = "",
        duration = "",
        type = DownloadType.video,
        format = Format(container = "mp4"),
        container = "mp4",
        downloadSections = "",
        allFormats = mutableListOf(),
        downloadPath = "",
        website = "example.invalid",
        downloadSize = "",
        playlistTitle = "",
        audioPreferences = AudioPreferences(),
        videoPreferences = VideoPreferences(),
        extraCommands = "",
        customFileNameTemplate = "",
        SaveThumb = false,
        status = status,
        downloadStartTime = 0L,
        logID = null,
        operationId = operationId,
        retryAttempt = retryAttempt,
        lastIssueCode = lastIssueCode,
        executionId = executionId,
    )

    private enum class ResumeTokenKind { EXACT, STALE, MISSING }
    private enum class RetryTokenKind { EXACT, STALE_OPERATION, STALE_ATTEMPT }
}
