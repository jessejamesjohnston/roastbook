package dev.roastbook.app.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.roastbook.app.testutil.TestGraph
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The daily Tier 1 job: armed only with a folder and the switch on, and what one run does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupSchedulerTest {
    private lateinit var g: TestGraph
    private val context: Context get() = g.context
    private lateinit var prefs: BackupPrefs
    private lateinit var backups: BackupManager

    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
            BackupWorker(appContext, workerParameters, backups, prefs)
    }

    /** A tree no provider serves: the folder the person picked, now unreachable. */
    private val unreachableTree = "content://com.example.nowhere/tree/backups"

    @Before fun setUp() {
        g = TestGraph()
        prefs = BackupPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { File(context.filesDir, "backup-${UUID.randomUUID()}.preferences_pb") })
        backups = BackupManager(context, g.db, g.photos, prefs)
    }

    @After fun tearDown() { g.close() }

    private fun dailyWork(): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(BackupScheduler.WORK_NAME).get()

    private fun grant(uri: String) = context.contentResolver.takePersistableUriPermission(
        Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )

    private fun worker(attempt: Int = 0) = TestListenableWorkerBuilder<BackupWorker>(context).setWorkerFactory(factory).setRunAttemptCount(attempt).build()

    // --- scheduling ---

    @Test fun aFolderWithTheSwitchOnArmsADailyJobThatWaitsForBattery() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        BackupScheduler(context).sync(BackupState(folderUri = unreachableTree, scheduled = true))
        val info = dailyWork().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(TimeUnit.HOURS.toMillis(24), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(TimeUnit.HOURS.toMillis(4), info.periodicityInfo!!.flexIntervalMillis)
        assertTrue(info.constraints.requiresBatteryNotLow())
    }

    @Test fun syncingAgainKeepsTheArmedJob() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = BackupScheduler(context)
        scheduler.sync(BackupState(folderUri = unreachableTree, scheduled = true))
        val first = dailyWork().single().id
        scheduler.sync(BackupState(folderUri = unreachableTree, scheduled = true))
        assertEquals("the day's slot is not reset by every app start", first, dailyWork().single().id)
    }

    @Test fun switchingOffOrClearingTheFolderCancelsTheJob() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val scheduler = BackupScheduler(context)
        scheduler.sync(BackupState(folderUri = unreachableTree, scheduled = true))
        scheduler.sync(BackupState(folderUri = unreachableTree, scheduled = false))
        assertEquals(WorkInfo.State.CANCELLED, dailyWork().single().state)

        scheduler.sync(BackupState(folderUri = unreachableTree, scheduled = true))
        scheduler.sync(BackupState(folderUri = null, scheduled = true))
        assertTrue(dailyWork().all { it.state == WorkInfo.State.CANCELLED })
    }

    // --- one run ---

    @Test fun aRunWithNoFolderDoesNothing() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertNull(prefs.current().lastError)
        assertNull(prefs.current().lastBackupAt)
    }

    /** A picked folder a provider serves over a plain directory, granted as the picker would. */
    private fun usableFolder(): String {
        val tree = DirectoryDocumentsProvider.install(File(context.cacheDir, "picked-${UUID.randomUUID()}")).toString()
        runBlocking { prefs.setFolder(tree) }
        grant(tree)
        return tree
    }

    private fun assertBackupsOff(why: BackupOff) = runBlocking {
        val s = prefs.current()
        assertNull("the folder is forgotten", s.folderUri)
        assertFalse("daily backup is off", s.scheduled)
        assertEquals(why, s.off)
        assertNull(s.lastError)
    }

    @Test fun aRunAfterTheFolderGrantWasRevokedTurnsBackupsOff() = runBlocking {
        prefs.setFolder(unreachableTree)
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        assertBackupsOff(BackupOff.FOLDER_GONE)
    }

    @Test fun aRunToAGrantedFolderThatCantBeReadTurnsBackupsOff() = runBlocking {
        prefs.setFolder(unreachableTree)
        grant(unreachableTree)
        assertTrue(backups.folderStillGranted(unreachableTree))
        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        assertBackupsOff(BackupOff.FOLDER_GONE)
    }

    @Test fun aRunWhoseFileNameIsRefusedTurnsBackupsOffWithoutRetrying() = runBlocking {
        usableFolder()
        DirectoryDocumentsProvider.refuseRename = true
        assertEquals(ListenableWorker.Result.failure(), worker(attempt = 0).doWork())
        assertBackupsOff(BackupOff.FOLDER_REFUSED)
    }

    @Test fun aFailedRunToAUsableFolderIsRetriedTwiceThenGivenUpAndKeepsTheFolder() = runBlocking {
        val tree = usableFolder()
        DirectoryDocumentsProvider.failWrites = true
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 0).doWork())
        assertEquals(ListenableWorker.Result.retry(), worker(attempt = 1).doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(attempt = 2).doWork())
        val s = prefs.current()
        assertTrue("the failure is shown in Settings", s.lastError != null)
        assertEquals(tree, s.folderUri)
        assertTrue(s.scheduled)
        assertNull(s.off)
    }
}
