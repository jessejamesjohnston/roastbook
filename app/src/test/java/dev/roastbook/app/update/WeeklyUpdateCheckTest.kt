package dev.roastbook.app.update

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.roastbook.app.data.prefs.UserPrefs
import dev.roastbook.app.data.prefs.UserSettings
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The weekly check is off by default, armed only by its switch, and announces each new version once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WeeklyUpdateCheckTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = UserPrefs(PreferenceDataStoreFactory.create { File(context.filesDir, "prefs-${UUID.randomUUID()}.preferences_pb") })
    private val server = MockWebServer().apply { start() }
    private val checker = UpdateChecker().apply { apiBase = server.url("/").toString() }
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
            UpdateCheckWorker(appContext, workerParameters, checker, prefs)
    }

    @After fun tearDown() { server.shutdown() }

    private fun weeklyWork(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WeeklyUpdateCheck.WORK_NAME).get()
    private fun runWorker() = runBlocking { TestListenableWorkerBuilder<UpdateCheckWorker>(context).setWorkerFactory(factory).build().doWork() }
    private fun release(tag: String) = MockResponse().setBody("""{"tag_name":"$tag"}""")

    @Test fun theWeeklyCheckIsOffByDefault() = runBlocking {
        assertFalse(UserSettings().weeklyUpdateCheck)
        assertFalse(prefs.current().weeklyUpdateCheck)
    }

    @Test fun withTheSwitchOffNoJobIsArmedAndARunAsksNothing() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        WeeklyUpdateCheck(context).sync(false)
        assertTrue(weeklyWork().none { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, server.requestCount)
    }

    @Test fun theSwitchArmsAWeeklyJobThatNeedsANetworkAndTurningItOffCancelsIt() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        WeeklyUpdateCheck(context).sync(true)
        val info = weeklyWork().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(TimeUnit.DAYS.toMillis(7), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(androidx.work.NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        WeeklyUpdateCheck(context).sync(false)
        assertEquals(WorkInfo.State.CANCELLED, weeklyWork().single().state)
    }

    private fun allowNotifications() =
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

    @Test fun aNewerVersionIsAnnouncedOnceInAQuietNotification() {
        allowNotifications()
        runBlocking { prefs.setWeeklyUpdateCheck(true) }
        server.enqueue(release("v99.0.0"))
        server.enqueue(release("v99.0.0"))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        val n = notifications.single()
        assertEquals("Roastbook 99.0.0 is available", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(UpdateCheckWorker.CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("a tap opens the release page", UpdateChecker.RELEASES_PAGE, shadowOf(n.contentIntent).savedIntent.dataString)
        assertEquals("99.0.0", runBlocking { prefs.current() }.lastAnnouncedVersion)
        runWorker()
        assertEquals("the same version is not announced twice", 1, notifications.size)
        assertEquals(2, server.requestCount)
    }

    @Test fun withoutTheNotificationPermissionANewVersionStaysUnannouncedForALaterWeek() {
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { prefs.setWeeklyUpdateCheck(true) }
        server.enqueue(release("v99.0.0"))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue(notifications.isEmpty())
        assertEquals(null, runBlocking { prefs.current() }.lastAnnouncedVersion)
    }

    @Test fun nothingNewOrAFailedCheckPostsNothingAndWaitsForNextWeek() {
        allowNotifications()
        runBlocking { prefs.setWeeklyUpdateCheck(true) }
        server.enqueue(release("v0.0.1"))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue(notifications.isEmpty())
    }
}
