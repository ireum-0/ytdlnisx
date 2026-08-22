package com.ireum.ytdl.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationUtilConcurrencyTest {
    @Test
    fun eachNotificationBuildUsesAnIndependentMutableBuilder() {
        val utility = NotificationUtil(
            ApplicationProvider.getApplicationContext<Context>()
        )
        val method = NotificationUtil::class.java.getDeclaredMethod(
            "getBuilder",
            String::class.java,
        ).apply { isAccessible = true }

        val first = method.invoke(utility, NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID)
        val second = method.invoke(utility, NotificationUtil.DOWNLOAD_SERVICE_CHANNEL_ID)

        assertNotSame(first as NotificationCompat.Builder, second as NotificationCompat.Builder)
    }

    @Test
    fun executionTokenSeparatesRunningNotificationCapabilities() {
        val method = NotificationUtil.Companion::class.java.getDeclaredMethod(
            "actionUri",
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType!!,
            String::class.java,
        ).apply { isAccessible = true }

        val first = method.invoke(NotificationUtil.Companion, "download", "pause", 41L, "E1")
        val second = method.invoke(NotificationUtil.Companion, "download", "pause", 41L, "E2")
        val terminal = method.invoke(NotificationUtil.Companion, "terminal", "cancel", 41L, null)

        assertNotEquals(first, second)
        assertNotEquals(first, terminal)
    }
}
