package dev.roastbook.app.data.backup

import android.content.Intent
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import dev.roastbook.app.testutil.TestGraph
import java.io.File
import java.util.UUID
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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

/** Backups written through the folder the person picked: temp name first, renamed once complete. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SafSinkTest {
    private lateinit var g: TestGraph
    private lateinit var folder: File
    private lateinit var sink: SafSink

    @Before fun setUp() {
        g = TestGraph()
        folder = File(g.context.cacheDir, "picked-${UUID.randomUUID()}")
        sink = SafSink(g.context, DirectoryDocumentsProvider.install(folder))
    }

    @After fun tearDown() {
        folder.deleteRecursively()
        g.close()
    }

    private fun write(name: String, text: String) = sink.openForWrite(name).use { it.write(text.toByteArray()) }

    @Test fun theFolderIsShownByTheNameItsProviderGives() {
        assertEquals(DirectoryDocumentsProvider.DISPLAY_NAME, sink.displayName())
    }

    @Test fun aCompletedBackupIsRenamedFromItsTempName() {
        write("roastbook-20260301-101500.zip.tmp", "zip bytes")
        assertTrue(sink.finish("roastbook-20260301-101500.zip.tmp", "roastbook-20260301-101500.zip"))
        assertEquals(listOf("roastbook-20260301-101500.zip"), folder.list()!!.toList())
        val listed = sink.list().single()
        assertEquals("roastbook-20260301-101500.zip", listed.name)
        assertEquals(9L, listed.size)
        assertEquals("zip bytes", listed.open()!!.bufferedReader().readText())
    }

    @Test fun writingAgainUnderTheSameTempNameReplacesTheLeftover() {
        write("b.zip.tmp", "half")
        write("b.zip.tmp", "whole")
        assertEquals(listOf("b.zip.tmp"), folder.list()!!.toList())
        assertEquals("whole", File(folder, "b.zip.tmp").readText())
    }

    @Test fun aProviderThatRenamesItsNewFilesStillGetsTheRightFileRenamedAndDeleted() {
        DirectoryDocumentsProvider.suffix = ".bin"
        write("c.zip.tmp", "zip")
        assertTrue(folder.list()!!.contains("c.zip.tmp.bin"))
        assertTrue(sink.finish("c.zip.tmp", "c.zip"))
        assertEquals(listOf("c.zip"), folder.list()!!.toList())

        write("d.zip.tmp", "zip")
        assertTrue("deletes the document it made, whatever it is called", sink.delete("d.zip.tmp"))
        assertFalse(folder.list()!!.contains("d.zip.tmp.bin"))
    }

    @Test fun aRefusedRenameIsAFailedBackupAndLeavesTheTempFile() {
        DirectoryDocumentsProvider.refuseRename = true
        write("e.zip.tmp", "zip")
        assertFalse(sink.finish("e.zip.tmp", "e.zip"))
        assertEquals(listOf("e.zip.tmp"), folder.list()!!.toList())
    }

    /**
     * A provider that refuses the rename and cannot report our document's name. Another
     * file under the final name (synced from another phone) is not our backup, so it proves nothing.
     */
    @Test fun anUnrelatedFileUnderTheFinalNameIsNoProofOfARename() {
        DirectoryDocumentsProvider.refuseRename = true
        DirectoryDocumentsProvider.nameless += "g.zip.tmp"
        File(folder, "g.zip").writeText("synced from another phone")
        write("g.zip.tmp", "zip")
        assertFalse(sink.finish("g.zip.tmp", "g.zip"))
        assertEquals("synced from another phone", File(folder, "g.zip").readText())
    }

    @Test fun aTempFileFromAnEarlierRunCanStillBeFinishedByName() {
        File(folder, "f.zip.tmp").writeText("zip")
        assertTrue(SafSink(g.context, DirectoryDocumentsProvider.treeUri).finish("f.zip.tmp", "f.zip"))
        assertTrue(File(folder, "f.zip").exists())
    }

    @Test fun finishingOrDeletingAFileThatIsNotThereReportsFalse() {
        assertFalse(sink.finish("missing.tmp", "missing.zip"))
        assertFalse(sink.delete("missing.zip"))
    }

    @Test fun anOldBackupIsDeletedByName() {
        File(folder, "roastbook-20250101-000000.zip").writeText("old")
        assertTrue(sink.delete("roastbook-20250101-000000.zip"))
        assertTrue(folder.list()!!.isEmpty())
    }

    @Test fun aPlainDirectorySinkIsNamedAfterItsDirectory() {
        assertEquals(folder.name, DirectorySink(folder).displayName())
    }

    /** Prefs and a manager whose chosen folder is this test's tree, granted as the picker would. */
    private fun pickedFolder(): Pair<BackupPrefs, BackupManager> = runBlocking {
        val prefs = BackupPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { File(g.context.filesDir, "b-${UUID.randomUUID()}.preferences_pb") })
        val tree = DirectoryDocumentsProvider.treeUri
        prefs.setFolder(tree.toString())
        g.context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs to BackupManager(g.context, g.db, g.photos, prefs)
    }

    @Test fun aBackupToAFolderDeletedSinceItWasPickedTurnsBackupsOff() = runBlocking {
        val (prefs, backups) = pickedFolder()
        folder.deleteRecursively()
        assertTrue(runCatching { backups.backupNow(sink) }.isFailure)
        val s = prefs.current()
        assertNull(s.folderUri)
        assertFalse(s.scheduled)
        assertEquals(BackupOff.FOLDER_GONE, s.off)
    }

    @Test fun aBackupWhoseFileNameTheFolderRefusesTurnsBackupsOff() = runBlocking {
        val (prefs, backups) = pickedFolder()
        DirectoryDocumentsProvider.refuseRename = true
        assertTrue(runCatching { backups.backupNow(sink) }.exceptionOrNull() is BackupFolderRefused)
        val s = prefs.current()
        assertNull(s.folderUri)
        assertFalse(s.scheduled)
        assertEquals(BackupOff.FOLDER_REFUSED, s.off)
        assertNull(s.lastError)
    }

    @Test fun aBackupThatFailsInAFolderStillUsableKeepsItAndRecordsTheError() = runBlocking {
        val (prefs, backups) = pickedFolder()
        DirectoryDocumentsProvider.failWrites = true
        assertTrue(runCatching { backups.backupNow(sink) }.isFailure)
        val s = prefs.current()
        assertEquals(DirectoryDocumentsProvider.treeUri.toString(), s.folderUri)
        assertTrue(s.scheduled)
        assertNull(s.off)
        assertTrue(s.lastError != null)
    }

    @Test fun theDailyJobWritesAZipIntoThePickedFolder() = runBlocking {
        g.insertCoffee(UUID.randomUUID().toString()) { copy(roaster = "Sable & Rye") }
        val prefs = BackupPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { File(g.context.filesDir, "b-${UUID.randomUUID()}.preferences_pb") })
        val backups = BackupManager(g.context, g.db, g.photos, prefs)
        val tree = DirectoryDocumentsProvider.treeUri
        prefs.setFolder(tree.toString())
        g.context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: android.content.Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                BackupWorker(appContext, workerParameters, backups, prefs)
        }

        val result = TestListenableWorkerBuilder<BackupWorker>(g.context).setWorkerFactory(factory).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val zip = folder.list()!!.single()
        assertTrue(zip, BackupManager.OUR_ZIP.matches(zip))
        assertEquals(zip, prefs.current().lastBackupName)
        assertEquals(1, prefs.current().lastBackupCoffees)
        assertNull(prefs.current().lastError)
    }

    @Test fun aDailyRunStoppedWhileAnotherBackupIsWritingRecordsNothing() = runBlocking {
        g.insertCoffee(UUID.randomUUID().toString()) { copy(roaster = "Northwind") }
        val prefs = BackupPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { File(g.context.filesDir, "b-${UUID.randomUUID()}.preferences_pb") })
        val backups = BackupManager(g.context, g.db, g.photos, prefs)
        val tree = DirectoryDocumentsProvider.treeUri
        prefs.setFolder(tree.toString())
        g.context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: android.content.Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                BackupWorker(appContext, workerParameters, backups, prefs)
        }

        // A manual backup is writing (it holds the backup lock) when the daily job starts and is then stopped.
        val writing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val manualDir = DirectorySink(File(g.context.cacheDir, "manual-${UUID.randomUUID()}"))
        val slowSink = object : BackupSink by manualDir {
            override fun openForWrite(tempName: String): OutputStream { writing.countDown(); finish.await(5, TimeUnit.SECONDS); return manualDir.openForWrite(tempName) }
        }
        val manual = launch(Dispatchers.Default) { backups.backupNow(slowSink) }
        assertTrue(writing.await(5, TimeUnit.SECONDS))

        var result: ListenableWorker.Result? = null
        val daily = launch(Dispatchers.Default) { result = TestListenableWorkerBuilder<BackupWorker>(g.context).setWorkerFactory(factory).build().doWork() }
        Thread.sleep(300) // the daily run is now waiting for the lock
        daily.cancelAndJoin()
        finish.countDown()
        manual.join()

        assertNull("a stopped run is neither a success nor a failure to retry", result)
        assertNull(prefs.current().lastError)
        assertTrue("nothing was written to the picked folder", folder.list()!!.isEmpty())
        assertEquals(1, manualDir.list().size)
    }
}
