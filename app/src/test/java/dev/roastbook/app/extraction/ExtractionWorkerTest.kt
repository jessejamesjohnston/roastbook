package dev.roastbook.app.extraction

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.roastbook.app.data.backup.AutoBackupAdopter
import dev.roastbook.app.data.backup.BackupManager
import dev.roastbook.app.data.backup.BackupPrefs
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.testutil.TestGraph
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The background job that carries on the reads Nano is not needed for after the app leaves the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExtractionWorkerTest {
    private lateinit var g: TestGraph
    private val context: Context get() = g.context
    private val nano = ScriptedNano(status = NanoStatus.UNAVAILABLE)
    private var reader = ScriptedReader()
    private lateinit var backups: BackupManager

    private val factory = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
            val extractor = Extractor(g.db, g.photos, nano, TextOcr("NORTHWIND\nWashed"), OffCloud, reader, MemoryHealth(), Clock { 1_000_000L }, shortTimeouts)
            return ExtractionWorker(appContext, workerParameters, g.dao, ReadPass(g.dao, extractor), backups)
        }
    }

    @Before fun setUp() {
        g = TestGraph(loadPhotos = true)
        val prefs = BackupPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { File(context.filesDir, "backup-${UUID.randomUUID()}.preferences_pb") })
        backups = BackupManager(context, g.db, g.photos, prefs)
        AutoBackupAdopter.discardSnapshot(context)
    }

    @After fun tearDown() {
        AutoBackupAdopter.discardSnapshot(context)
        g.close()
    }

    private fun work(): ExtractionWorker = TestListenableWorkerBuilder<ExtractionWorker>(context).setWorkerFactory(factory).build()

    @Test fun aBagNanoCannotReadIsReadInTheBackgroundAndTheSnapshotRefreshed() = runBlocking {
        g.extractingCoffee("c1")
        assertEquals(ListenableWorker.Result.success(), work().doWork())
        val c = g.dao.get("c1")!!
        assertEquals(CoffeeState.LOGGED, c.state)
        assertEquals(ExtractionSource.OCR, c.extractionSource)
        // The Tier 0 copy holds the finished read, not the row as it was before it.
        val copy = File(AutoBackupAdopter.snapshotDir(context), AutoBackupAdopter.DB_COPY)
        val snapshot = androidx.room.Room.databaseBuilder(context, dev.roastbook.app.data.db.RoastbookDatabase::class.java, copy.absolutePath)
            .addMigrations(*dev.roastbook.app.data.db.RoastbookDatabase.MIGRATIONS).build()
        try {
            val held = snapshot.coffeeDao().get("c1")!!
            assertEquals(CoffeeState.LOGGED, held.state)
            assertEquals(ExtractionSource.OCR, held.extractionSource)
        } finally { snapshot.close() }
    }

    @Test fun aBagThatMayStillGetANanoReadWaitsForTheForeground() = runBlocking {
        nano.status = NanoStatus.AVAILABLE
        g.extractingCoffee("c1")
        assertEquals(ListenableWorker.Result.success(), work().doWork())
        val c = g.dao.get("c1")!!
        assertEquals(CoffeeState.EXTRACTING, c.state)
        assertEquals("no attempt counted", 0, c.extractionAttempts)
        assertEquals(0, nano.calls)
    }

    @Test fun aRowThatUsedEveryAttemptFinishesWithTheGuessWithoutAskingAModel() = runBlocking {
        // The process died during the last attempt, after it was counted: the row is still EXTRACTING
        // with no attempts left. It finishes with what the label text gives, and no model runs again.
        reader = ScriptedReader(LocalAvailability.READY) { """{"roaster":"Larkmoor"}""" }
        g.extractingCoffee("c1") { copy(extractionAttempts = Extractor.MAX_ATTEMPTS) }
        assertEquals(ListenableWorker.Result.success(), work().doWork())
        val c = g.dao.get("c1")!!
        assertEquals(CoffeeState.LOGGED, c.state)
        assertEquals(ExtractionSource.OCR, c.extractionSource)
        assertEquals("Northwind", c.roaster)
        assertEquals(0, reader.calls)
        assertTrue(c.extractionMeta!!, c.extractionMeta.contains("gave up after"))
    }

    @Test fun withNothingToReadTheSnapshotIsLeftAlone() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), work().doWork())
        assertFalse(AutoBackupAdopter.snapshotDir(context).exists())
    }

    @Test fun enqueueRunsTheJobUnderItsOwnName() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setWorkerFactory(factory).setExecutor(SynchronousExecutor()).build(),
        )
        g.extractingCoffee("c1")
        ExtractionWorker.enqueue(context)
        val deadline = System.currentTimeMillis() + 5_000
        fun info() = WorkManager.getInstance(context).getWorkInfosForUniqueWork("background-reads").get().single()
        while (!info().state.isFinished && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(WorkInfo.State.SUCCEEDED, info().state)
        assertEquals(CoffeeState.LOGGED, g.dao.get("c1")!!.state)
    }
}
