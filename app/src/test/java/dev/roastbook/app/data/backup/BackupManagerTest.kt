package dev.roastbook.app.data.backup

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.data.photo.PhotoStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tier 1 round trip and every way a restore must refuse. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupManagerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: RoastbookDatabase
    private lateinit var photos: PhotoStore
    private lateinit var prefs: BackupPrefs
    private lateinit var manager: BackupManager
    private lateinit var folder: File
    private val dbFile = "backup-test.db"

    @Before fun setUp() {
        context.getDatabasePath(dbFile).let { it.delete(); File(it.path + "-wal").delete(); File(it.path + "-shm").delete() }
        db = Room.databaseBuilder(context, RoastbookDatabase::class.java, dbFile).addMigrations(*RoastbookDatabase.MIGRATIONS).allowMainThreadQueries().build()
        photos = PhotoStore(context)
        photos.root.deleteRecursively()
        prefs = BackupPrefs(PreferenceDataStoreFactory.create { File(context.filesDir, "test-${UUID.randomUUID()}.preferences_pb") })
        manager = BackupManager(context, db, photos, prefs)
        folder = File(context.cacheDir, "sink-${UUID.randomUUID()}")
    }
    @After fun tearDown() {
        db.close()
        photos.root.deleteRecursively()
        folder.deleteRecursively()
        AutoBackupAdopter.discardSnapshot(context)
        RestoreJournal.clear(context)
        context.filesDir.listFiles()?.filter { it.name.startsWith("photos.old-") }?.forEach { it.deleteRecursively() }
    }

    private suspend fun seed(n: Int): List<String> = (1..n).map {
        val id = UUID.randomUUID().toString(); val pid = UUID.randomUUID().toString()
        db.coffeeDao().insert(CoffeeEntity(id, it.toLong(), it.toLong(), it.toLong(), CoffeeState.LOGGED, roaster = "Roaster $it", overall = 4f))
        File(photos.dir(id), "$pid.jpg").writeBytes(ByteArray(300) { b -> (b + it).toByte() })
        File(photos.dir(id), "$pid.thumb.jpg").writeBytes(ByteArray(40) { 7 })
        db.coffeeDao().insertPhotos(listOf(CoffeePhotoEntity(pid, id, PhotoRole.FRONT, 0, "photos/$id/$pid.jpg", thumb = "photos/$id/$pid.thumb.jpg", takenAt = 1, addedAt = 1)))
        id
    }

    private fun onlyZip(): File = folder.listFiles()!!.single { it.name.endsWith(".zip") }

    @Test fun backupWritesAVerifiableZipAndRecordsIt() = runTest {
        seed(2)
        val summary = manager.backupNow(DirectorySink(folder))
        val zip = onlyZip()
        assertTrue(BackupManager.OUR_ZIP.matches(zip.name))
        assertEquals(summary.name, zip.name)
        assertFalse(File(folder, zip.name + ".tmp").exists())
        val manifest = manager.inspect { zip.inputStream() }.manifest
        assertEquals(2, manifest.coffees)
        assertEquals(2, manifest.photos)
        assertEquals(1 + 4, manifest.files.size) // db + 2 originals + 2 thumbs
        assertEquals(4, manifest.schemaVersion) // v4: parked-retry time + extraction trail
        val s = prefs.current()
        assertEquals(zip.name, s.lastBackupName)
        assertEquals(2, s.lastBackupCoffees)
    }

    @Test fun rotationKeepsTheNewest() = runTest {
        seed(1)
        val sink = DirectorySink(folder)
        // Pre-existing older zips with our name shape, plus a stranger's file that must be left alone.
        listOf("roastbook-20200101-000000.zip", "roastbook-20200102-000000.zip", "roastbook-20200103-000000.zip").forEach { File(folder, it).writeText("x") }
        File(folder, "holiday.jpg").writeText("keep me")
        repeat(5) { manager.backupNow(sink) }
        val zips = folder.listFiles()!!.filter { it.name.endsWith(".zip") }.map { it.name }.sorted()
        assertEquals(BackupState.DEFAULT_KEEP, zips.size)
        assertFalse(zips.contains("roastbook-20200101-000000.zip"))
        assertTrue(File(folder, "holiday.jpg").exists())
    }

    @Test fun restoreRoundTripReplacesRowsAndPhotos() = runTest {
        val ids = seed(2)
        val photoRows = db.coffeeDao().allPhotos().sortedBy { it.id }
        val files = photos.root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(context.filesDir).path to it.readBytes() }
        manager.backupNow(DirectorySink(folder))
        val zip = onlyZip()
        // Diverge: delete one, add another, scribble on a photo.
        db.coffeeDao().get(ids[0])!!.let { db.coffeeDao().delete(it) }
        photos.deleteCoffee(ids[0])
        seed(1)
        photos.file(photoRows.single { it.coffeeId == ids[1] }.original).writeBytes(ByteArray(12) { 1 })
        val m = manager.restore({ zip.inputStream() })
        assertEquals(2, m.coffees)
        val after = db.coffeeDao().allCoffees().map { it.id }.toSet()
        assertEquals(ids.toSet(), after)
        // The photo rows and every photo's bytes come back exactly as backed up.
        assertEquals(photoRows, db.coffeeDao().allPhotos().sortedBy { it.id })
        assertEquals(4, files.size)
        files.forEach { (path, bytes) -> assertTrue(path, File(context.filesDir, path).readBytes().contentEquals(bytes)) }
        assertEquals(2, photos.root.listFiles()!!.size) // the third coffee's directory is gone
        // Idempotent: again.
        manager.restore({ zip.inputStream() })
        assertEquals(ids.toSet(), db.coffeeDao().allCoffees().map { it.id }.toSet())
    }

    @Test fun tornZipIsRefusedAndNothingChanges() = runTest {
        val ids = seed(1)
        manager.backupNow(DirectorySink(folder))
        val zip = onlyZip()
        val flipped = rewriteZip(zip) { name, bytes -> if (name.endsWith(".jpg") && !name.contains("thumb")) bytes.also { it[10] = (it[10] + 1).toByte() } else bytes }
        try { manager.restore({ flipped.inputStream() }); fail("expected Corrupt") } catch (e: RestoreError.Corrupt) {}
        assertEquals(ids.toSet(), db.coffeeDao().allCoffees().map { it.id }.toSet())
    }

    @Test fun traversalPathIsRefused() = runTest {
        seed(1)
        manager.backupNow(DirectorySink(folder))
        val evil = rewriteZip(onlyZip(), extra = mapOf("photos/../../shared_prefs/evil.xml" to "boo".toByteArray()))
        try { manager.restore({ evil.inputStream() }); fail("expected UnsafePath") } catch (e: RestoreError.UnsafePath) {}
    }

    @Test fun newerSchemaIsRefused() = runTest {
        seed(1)
        manager.backupNow(DirectorySink(folder))
        val future = rewriteZip(onlyZip()) { name, bytes -> if (name == BackupManager.MANIFEST_ENTRY) bytes.decodeToString().replace(Regex("\"schemaVersion\":\\d+"), "\"schemaVersion\":99").also { check(it.contains("\"schemaVersion\":99")) }.toByteArray() else bytes }
        try { manager.restore({ future.inputStream() }); fail("expected NewerSchema") } catch (e: RestoreError.NewerSchema) {}
    }

    @Test fun notAZipIsRefused() = runTest {
        val junk = File(folder.apply { mkdirs() }, "junk.zip").apply { writeText("hello") }
        try { manager.inspect { junk.inputStream() }; fail("expected BadArchive") } catch (e: RestoreError.BadArchive) {}
    }

    @Test fun autoSnapshotHoldsTheRowsAndThumbsOnly() = runTest {
        val ids = seed(1)
        manager.refreshAutoSnapshot(force = true)
        val dir = AutoBackupAdopter.snapshotDir(context)
        val copy = File(dir, AutoBackupAdopter.DB_COPY)
        assertTrue(copy.exists())
        val thumbs = File(dir, AutoBackupAdopter.THUMBS).walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertEquals(1, thumbs.size)
        assertTrue(thumbs.single().endsWith(".thumb.jpg"))
        // The copy is a real database with the row in it.
        val opened = Room.databaseBuilder(context, RoastbookDatabase::class.java, copy.absolutePath).addMigrations(*RoastbookDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try { assertEquals(ids.toSet(), opened.coffeeDao().allIds().toSet()) } finally { opened.close() }
        assertNotNull(prefs.current().snapshotAt)
        dir.deleteRecursively()
    }

    // ---------- Naming, rotation, snapshots and restore safety ----------

    /** A folder whose provider refuses the rename out of the temp name (the SAF failure mode). */
    private class RenameRefusingSink(private val inner: BackupSink) : BackupSink by inner {
        override fun finish(tempName: String, finalName: String): Boolean = false
    }

    private class FullSink(inner: BackupSink) : BackupSink by inner {
        override fun openForWrite(tempName: String): java.io.OutputStream = throw java.io.IOException("write failed: ENOSPC (No space left on device)")
    }

    @Test fun aBackupThatRunsOutOfSpaceIsRecordedAsSuch() = runTest {
        seed(1)
        runCatching { manager.backupNow(FullSink(DirectorySink(folder))) }
        assertTrue(prefs.current().lastError!!.startsWith(NO_SPACE_ERROR))
    }

    @Test fun anyOtherFailureIsNotCalledOutOfSpace() = runTest {
        seed(1)
        val broken = object : BackupSink by DirectorySink(folder) {
            override fun openForWrite(tempName: String): java.io.OutputStream = throw java.io.IOException("connection reset")
        }
        runCatching { manager.backupNow(broken) }
        assertFalse(prefs.current().lastError!!.startsWith(NO_SPACE_ERROR))
    }

    @Test fun outOfSpaceIsFoundAnywhereInTheCauseChain() {
        val errno = android.system.ErrnoException("write", android.system.OsConstants.ENOSPC)
        assertTrue(isOutOfSpace(java.io.IOException("copy failed", errno)))
        assertFalse(isOutOfSpace(java.io.IOException("copy failed", android.system.ErrnoException("write", android.system.OsConstants.EIO))))
    }

    /** A backup that could not be named is a failure, not a quiet success. */
    @Test fun aRefusedRenameFailsTheBackupAndRotatesNothing() = runTest {
        seed(1)
        val sink = DirectorySink(folder)
        // Old zips a rotation would take, plus one real success to protect.
        (1..8).forEach { File(folder, "roastbook-2020010$it-000000.zip").writeText("x") }
        manager.backupNow(sink, now = 1_700_000_000_000)
        val recorded = prefs.current()
        val before = folder.listFiles()!!.map { it.name }.toSet()

        try {
            manager.backupNow(RenameRefusingSink(sink), now = 1_700_000_100_000)
            fail("expected BackupError")
        } catch (e: BackupError) {
        }

        val after = prefs.current()
        assertEquals(recorded.lastBackupAt, after.lastBackupAt)
        assertEquals(recorded.lastBackupName, after.lastBackupName)
        assertNotNull(after.lastError)
        // No temp left behind, and the rotation the failed run would have driven never happened.
        assertEquals(before, folder.listFiles()!!.map { it.name }.toSet())
    }

    /** By name alone "-10.zip" sorts under "-2.zip", so the newest of a busy second went first. */
    @Test fun tenBackupsInTheSameSecondKeepTheNewestSeven() = runTest {
        seed(1)
        val sink = DirectorySink(folder)
        val now = 1_700_000_000_000L
        val order = (1..10).map { manager.backupNow(sink, now = now).name }
        assertEquals(10, order.distinct().size) // a rotated-away name is never handed out again
        assertTrue(order.last().endsWith("-10.zip"))
        val left = folder.listFiles()!!.filter { it.name.endsWith(".zip") }.map { it.name }.toSet()
        assertEquals(order.takeLast(BackupState.DEFAULT_KEEP).toSet(), left)
    }

    /** MAX(updated_at) never moves when a row is deleted, so the snapshot kept the deletion. */
    @Test fun aDeleteOnlyChangeReachesTheNextSnapshot() = runTest {
        val ids = seed(2)
        manager.refreshAutoSnapshot()
        assertEquals(ids.toSet(), snapshotIds())
        db.coffeeDao().get(ids[0])!!.let { db.coffeeDao().delete(it) }
        manager.refreshAutoSnapshot()
        assertEquals(setOf(ids[1]), snapshotIds())
    }

    /** The snapshot a reinstall would adopt describes the restored library, not the replaced one. */
    @Test fun aRestoreRebuildsTheSnapshotFromTheRestoredLibrary() = runTest {
        val ids = seed(1)
        manager.backupNow(DirectorySink(folder), now = 1_700_000_000_000)
        val zip = onlyZip()
        val later = seed(1)
        manager.refreshAutoSnapshot() // the snapshot now holds both coffees
        assertEquals((ids + later).toSet(), snapshotIds())
        manager.restore({ zip.inputStream() })
        assertEquals(ids.toSet(), snapshotIds())
    }

    /** A generation is built beside the live snapshot and swapped in whole. */
    @Test fun aSnapshotIsOnlySwappedInWhole() = runTest {
        seed(1)
        AutoBackupAdopter.nextDir(context).apply { mkdirs() }.let { File(it, "junk").writeText("leftover") }
        AutoBackupAdopter.oldDir(context).apply { mkdirs() }.let { File(it, "junk").writeText("leftover") }
        manager.refreshAutoSnapshot()
        assertFalse(AutoBackupAdopter.nextDir(context).exists())
        assertFalse(AutoBackupAdopter.oldDir(context).exists())
        val dir = AutoBackupAdopter.snapshotDir(context)
        assertTrue(File(dir, AutoBackupAdopter.DB_COPY).exists())
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".part") })
    }

    /**
     * Killed between the swap's two renames, only the previous generation is left, under a
     * name the Auto Backup include list skips. The next start puts it back before anything else,
     * not the next refresh, which waits until the app leaves the foreground.
     */
    @Test fun aSnapshotSwapKilledBetweenItsRenamesIsRepairedAtTheNextStart() = runTest {
        val ids = seed(1)
        manager.refreshAutoSnapshot()
        assertTrue(AutoBackupAdopter.snapshotDir(context).renameTo(AutoBackupAdopter.oldDir(context)))

        AutoBackupAdopter.adoptIfNeeded(context, dbFile)

        assertFalse(AutoBackupAdopter.oldDir(context).exists())
        assertEquals(ids.toSet(), snapshotIds())
    }

    /**
     * The restore committed and only the Tier 0 rebuild after it failed. That is a finished
     * restore (its caller goes on to arm the restored reminders), not a failed one; the snapshot is
     * rebuilt at the next refresh.
     */
    @Test fun aSnapshotRebuildThatFailsAfterTheCommitStillReturnsTheRestore() = runTest {
        val ids = seed(2)
        manager.backupNow(DirectorySink(folder), now = 1_700_000_000_000)
        seed(1)
        // A generation directory nothing can be written into or removed from: the rebuild fails.
        val next = AutoBackupAdopter.nextDir(context).apply { mkdirs(); File(this, "stuck").writeText("x") }
        next.setWritable(false)
        try {
            org.junit.Assume.assumeFalse("permissions do not apply to this user", next.canWrite())
            val m = manager.restore({ onlyZip().inputStream() })
            assertEquals(2, m.coffees)
        } finally {
            next.setWritable(true)
        }
        assertEquals(ids.toSet(), db.coffeeDao().allCoffees().map { it.id }.toSet())
        assertFalse(RestoreJournal.marker(context).exists())
        manager.refreshAutoSnapshot()
        assertEquals(ids.toSet(), snapshotIds())
    }

    /** A half-written copy is not a database and must never be adopted as one. */
    @Test fun aSnapshotThatIsNotADatabaseIsNotAdopted() = runTest {
        val dir = AutoBackupAdopter.snapshotDir(context).apply { mkdirs() }
        File(dir, AutoBackupAdopter.DB_COPY).writeBytes(ByteArray(64) { 9 })
        val fresh = "fresh-${UUID.randomUUID()}.db"
        AutoBackupAdopter.adoptIfNeeded(context, fresh)
        assertFalse(context.getDatabasePath(fresh).exists())
        assertFalse(AutoBackupAdopter.adoptedWithoutOriginals(context))
    }

    /**
     * An adoption whose database copy failed (a full disk) left Room to create an empty database.
     * The snapshot is not rebuilt from that empty database, and the next start adopts it after all.
     */
    @Test fun aFailedAdoptionIsRetriedAndItsSnapshotIsNotOverwritten() = runTest {
        val ids = seed(2)
        manager.refreshAutoSnapshot()
        val dir = AutoBackupAdopter.snapshotDir(context)
        val snapshotBytes = File(dir, AutoBackupAdopter.DB_COPY).readBytes()
        // The state a failed copy leaves: the marker says the database never landed, and Room has
        // since made an empty database under the live name.
        File(dir, "adopting").writeText("db-pending")
        val fresh = "fresh-${UUID.randomUUID()}.db"
        Room.databaseBuilder(context, RoastbookDatabase::class.java, fresh).addMigrations(*RoastbookDatabase.MIGRATIONS).allowMainThreadQueries().build()
            .apply { openHelper.writableDatabase.version }.close()

        manager.refreshAutoSnapshot() // the app went to the background meanwhile
        assertTrue("the snapshot was not rebuilt", File(dir, AutoBackupAdopter.DB_COPY).readBytes().contentEquals(snapshotBytes))

        AutoBackupAdopter.adoptIfNeeded(context, fresh)
        val adopted = Room.databaseBuilder(context, RoastbookDatabase::class.java, fresh).addMigrations(*RoastbookDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try { assertEquals(ids.toSet(), adopted.coffeeDao().allCoffees().map { it.id }.toSet()) } finally { adopted.close() }
        assertFalse(AutoBackupAdopter.dbAdoptionPending(context))
    }

    /** Adoption copies through a temp name, skips `.part` leftovers, marks itself last. */
    @Test fun adoptionLandsTheThumbsBeforeItMarksItselfDone() = runTest {
        val ids = seed(1)
        manager.refreshAutoSnapshot()
        val dir = AutoBackupAdopter.snapshotDir(context)
        File(dir, AutoBackupAdopter.THUMBS + "/photos/${ids[0]}/half.thumb.jpg.part").writeBytes(ByteArray(8))
        photos.root.deleteRecursively()
        val fresh = "fresh-${UUID.randomUUID()}.db"
        AutoBackupAdopter.adoptIfNeeded(context, fresh)

        val live = context.getDatabasePath(fresh)
        assertTrue(live.exists())
        assertFalse(File(live.path + ".tmp").exists())
        assertTrue(AutoBackupAdopter.adoptedWithoutOriginals(context))
        val landed = File(photos.root, ids[0]).listFiles()!!.map { it.name }
        assertTrue(landed.any { it.endsWith(".thumb.jpg") })
        assertFalse(landed.any { it.endsWith(".part") })
        live.delete()
    }

    /** Killed between the database copy and the thumbs, the next start finishes the job. */
    @Test fun anAdoptionKilledBeforeTheThumbsFinishesAtTheNextStart() = runTest {
        val ids = seed(1)
        manager.refreshAutoSnapshot()
        val dir = AutoBackupAdopter.snapshotDir(context)
        val fresh = "fresh-${UUID.randomUUID()}.db"
        // The crash state: the database landed, the thumbs did not, no "adopted" marker was written.
        File(dir, AutoBackupAdopter.DB_COPY).copyTo(context.getDatabasePath(fresh).apply { parentFile?.mkdirs() }, overwrite = true)
        File(dir, "adopting").writeText("1")
        photos.root.deleteRecursively()

        AutoBackupAdopter.adoptIfNeeded(context, fresh)

        assertTrue(File(photos.root, ids[0]).listFiles()!!.any { it.name.endsWith(".thumb.jpg") })
        assertTrue(AutoBackupAdopter.adoptedWithoutOriginals(context))
        assertFalse(File(dir, "adopting").exists())
        context.getDatabasePath(fresh).delete()
    }

    /** The file the dialog described is the only file the restore will accept. */
    @Test fun anArchiveThatChangedAfterInspectionIsRefused() = runTest {
        val ids = seed(1)
        manager.backupNow(DirectorySink(folder), now = 1_700_000_000_000)
        val zip = onlyZip()
        val seen = manager.inspect { zip.inputStream() }
        val swapped = rewriteZip(zip) { name, bytes ->
            if (name.endsWith(".jpg") && !name.contains("thumb")) bytes.also { it[10] = (it[10] + 1).toByte() } else bytes
        }
        try {
            manager.restore({ swapped.inputStream() }, seen.sha256)
            fail("expected Changed")
        } catch (e: RestoreError.Changed) {
        }
        assertEquals(ids.toSet(), db.coffeeDao().allCoffees().map { it.id }.toSet())
        assertNotEquals(seen.sha256, manager.inspect { swapped.inputStream() }.sha256)
        // And the digest inspect hands out is the one the restore computes, or every real restore
        // through Settings would refuse itself.
        assertEquals(seen.manifest.coffees, manager.restore({ zip.inputStream() }, seen.sha256).coffees)
    }

    /** Two entries under one name let the checked copy and the written copy differ. */
    @Test fun aDuplicateEntryIsRefused() = runTest {
        seed(1)
        manager.backupNow(DirectorySink(folder), now = 1_700_000_000_000)
        // ZipOutputStream refuses to write one name twice, so the second entry is written under a
        // same-length stand-in and renamed in the bytes afterwards, which is what a crafted zip does.
        val stand = "manifesX.json"
        val doubled = File(folder, "doubled-${UUID.randomUUID()}.zip")
        doubled.writeBytes(renamedEntry(rewriteZip(onlyZip(), extra = mapOf(stand to "{}".toByteArray())).readBytes(), stand, BackupManager.MANIFEST_ENTRY))
        try { manager.restore({ doubled.inputStream() }); fail("expected BadArchive") } catch (e: RestoreError.BadArchive) {}
        try { manager.inspect { doubled.inputStream() }; fail("expected BadArchive") } catch (e: RestoreError.BadArchive) {}
    }

    /** An archive of ten thousand and one entries is not one of ours. */
    @Test fun anArchiveWithTooManyEntriesIsRefused() = runTest {
        val many = buildZip((1..BackupManager.MAX_ENTRIES + 1).map { "photos/f$it" to ByteArray(1) })
        try { manager.inspect { many.inputStream() }; fail("expected BadArchive") } catch (e: RestoreError.BadArchive) {}
    }

    /** The manifest is read into memory, so its size is bounded. */
    @Test fun anOversizedManifestIsRefused() = runTest {
        val fat = buildZip(listOf(BackupManager.MANIFEST_ENTRY to ByteArray(BackupManager.MAX_MANIFEST_BYTES + 1) { 32 }))
        try { manager.inspect { fat.inputStream() }; fail("expected BadArchive") } catch (e: RestoreError.BadArchive) {}
    }

    /** An entry that keeps expanding past what the manifest declares is stopped mid-write. */
    @Test fun anArchiveThatExpandsPastItsManifestIsRefused() = runTest {
        val ids = seed(1)
        val bomb = ByteArray(2 shl 20) // declared as 100 bytes below
        val manifest = manifestBytes(listOf(ManifestFile(BackupManager.DB_ENTRY, "00".repeat(32), 100)), coffees = 1, photos = 0)
        // Manifest first: the bound has to bite while the entry is still being written.
        val zip = buildZip(listOf(BackupManager.MANIFEST_ENTRY to manifest, BackupManager.DB_ENTRY to bomb))
        try { manager.restore({ zip.inputStream() }); fail("expected BadArchive") } catch (e: RestoreError.BadArchive) {}
        assertEquals(ids.toSet(), db.coffeeDao().allCoffees().map { it.id }.toSet())
    }

    /** Killed between the photo swap and the row commit, the next start puts the photos back. */
    @Test fun aRestoreKilledBetweenThePhotoSwapAndTheRowsRollsBack() = runTest {
        val ids = seed(1)
        val original = File(photos.dir(ids[0]), "keep.jpg").apply { writeBytes(ByteArray(11) { 3 }) }
        val aside = File(context.filesDir, "photos.old-1")
        // Exactly the state the restore is in for one instant: marker down, old tree set aside,
        // the archive's photos in place, the rows still the old ones.
        RestoreJournal.begin(context, aside)
        assertTrue(photos.root.renameTo(aside))
        File(photos.dir("from-the-archive"), "new.jpg").writeBytes(ByteArray(4))

        RestoreJournal.recoverIfNeeded(context)

        assertTrue(original.exists())
        assertEquals(11, original.length())
        assertFalse(File(photos.root, "from-the-archive").exists())
        assertFalse(RestoreJournal.marker(context).exists())
        assertFalse(aside.exists())
    }

    /**
     * A restore that fails after the photo swap puts the photos back in this process too, and
     * the Tier 0 snapshot went first, so a crash in that window leaves nothing to adopt.
     */
    @Test fun aRestoreThatFailsAfterThePhotoSwapRollsBackAndLeavesNoSnapshot() = runTest {
        val ids = seed(1)
        val original = File(photos.dir(ids[0]), "keep.jpg").apply { writeBytes(ByteArray(11) { 3 }) }
        manager.refreshAutoSnapshot() // there is a snapshot of the pre-restore library to discard
        // A database whose photo row names a coffee that is not there: readable, but the live
        // insert fails on the foreign key, which is a failure after the photos have been swapped.
        val poisoned = poisonedDatabase()
        val bytes = poisoned.readBytes()
        val manifest = manifestBytes(listOf(ManifestFile(BackupManager.DB_ENTRY, sha256(bytes), bytes.size.toLong())), coffees = 0, photos = 1)
        val zip = buildZip(listOf(BackupManager.DB_ENTRY to bytes, BackupManager.MANIFEST_ENTRY to manifest))

        var failure: Exception? = null
        try { manager.restore({ zip.inputStream() }); fail("expected the row insert to fail") } catch (e: Exception) { failure = e }
        // Proof that the failure landed after the photo swap and not during verification.
        assertTrue("failed too early: $failure", failure is android.database.sqlite.SQLiteConstraintException)

        assertEquals(ids.toSet(), db.coffeeDao().allCoffees().map { it.id }.toSet())
        assertTrue(original.exists())
        assertEquals(11, original.length())
        assertFalse(RestoreJournal.marker(context).exists())
        assertTrue(context.filesDir.listFiles()!!.none { it.name.startsWith("photos.old-") })
        // The snapshot was dropped before the rows were touched: a reinstall now has nothing to adopt.
        assertFalse(File(AutoBackupAdopter.snapshotDir(context), AutoBackupAdopter.DB_COPY).exists())
        val fresh = "fresh-${UUID.randomUUID()}.db"
        AutoBackupAdopter.adoptIfNeeded(context, fresh)
        assertFalse(context.getDatabasePath(fresh).exists())
    }

    /** Killed after the rows committed but before the marker went: the restored photos stay. */
    @Test fun aRestoreKilledAfterTheRowsCommittedKeepsTheRestoredPhotos() = runTest {
        seed(1)
        manager.backupNow(DirectorySink(folder), now = 1_700_000_000_000)
        manager.restore({ onlyZip().inputStream() })
        val restoredPhoto = photos.root.walkTopDown().first { it.isFile }
        // The instant between the row commit and the marker's removal: marker down (naming the
        // restored library), the previous photos still set aside.
        val aside = File(context.filesDir, "photos.old-2").apply { mkdirs(); File(this, "previous.jpg").writeBytes(ByteArray(5)) }
        val live = db.coffeeDao()
        RestoreJournal.begin(context, aside, RestoreJournal.fingerprint(live.allCoffees(), live.allPhotos()))

        RestoreJournal.recoverIfNeeded(context, dbFile)

        assertTrue("the restored photo is still there", restoredPhoto.exists())
        assertFalse(aside.exists())
        assertFalse(RestoreJournal.marker(context).exists())
    }

    /** Before the rows commit, the same marker still rolls back: the live rows are the old library. */
    @Test fun aRestoreKilledBeforeTheRowsCommittedStillRollsBackWithAFingerprint() = runTest {
        val ids = seed(1)
        val original = File(photos.dir(ids[0]), "keep.jpg").apply { writeBytes(ByteArray(11) { 3 }) }
        val aside = File(context.filesDir, "photos.old-3")
        RestoreJournal.begin(context, aside, RestoreJournal.fingerprint(listOf(CoffeeEntity(UUID.randomUUID().toString(), 1, 1, 1, CoffeeState.LOGGED)), emptyList()))
        assertTrue(photos.root.renameTo(aside))
        File(photos.dir(UUID.randomUUID().toString()), "new.jpg").writeBytes(ByteArray(4))

        RestoreJournal.recoverIfNeeded(context, dbFile)

        assertTrue(original.exists())
        assertFalse(aside.exists())
    }

    /**
     * Restoring an older backup of the same coffees. The live rows have the archive's ids
     * before the commit too, so matching ids are no proof the rows landed: the photos the live rows
     * name (here a square written after the backup) must come back.
     */
    @Test fun aRestoreKilledBeforeTheRowsCommittedRollsBackEvenWhenTheIdsMatch() = runTest {
        val ids = seed(1)
        val dao = db.coffeeDao()
        val archived = RestoreJournal.fingerprint(dao.allCoffees(), dao.allPhotos())
        val archivedTree = File(context.cacheDir, "archived-${UUID.randomUUID()}").also { photos.root.copyRecursively(it) }
        // Since the backup: the coffee was rated again and its hero square written.
        val photo = dao.allPhotos().single()
        val square = File(photos.dir(ids[0]), "${photo.id}.square.jpg").apply { writeBytes(ByteArray(9) { 5 }) }
        dao.updatePhoto(photo.copy(square = "photos/${ids[0]}/${photo.id}.square.jpg"))
        dao.update(dao.get(ids[0])!!.copy(overall = 5f, updatedAt = 99))
        // The kill: marker down, the current tree set aside, the archive's tree in place, rows unchanged.
        val aside = File(context.filesDir, "photos.old-4")
        RestoreJournal.begin(context, aside, archived)
        assertTrue(photos.root.renameTo(aside))
        archivedTree.copyRecursively(photos.root)
        archivedTree.deleteRecursively()

        RestoreJournal.recoverIfNeeded(context, dbFile)

        assertTrue("the square the live rows name is back", square.exists())
        assertFalse(aside.exists())
        assertFalse(RestoreJournal.marker(context).exists())
    }

    /**
     * A start that cannot put the previous photos back keeps the marker and the set-aside
     * tree, so a later start tries again instead of forgetting where the photos went.
     */
    @Test fun aRollbackThatFailsAtStartIsTriedAgainAtTheNextStart() = runTest {
        val ids = seed(1)
        val original = File(photos.dir(ids[0]), "keep.jpg").apply { writeBytes(ByteArray(11) { 3 }) }
        val aside = File(context.filesDir, "photos.old-5")
        RestoreJournal.begin(context, aside)
        assertTrue(photos.root.renameTo(aside))
        // The archive's tree holds the same coffee's directory, and nothing can be written into it
        // or deleted from it: the rename back and the copy fallback both fail.
        val locked = photos.dir(ids[0]).apply { File(this, "from-the-archive.jpg").writeBytes(ByteArray(4)) }
        locked.setWritable(false)
        try {
            org.junit.Assume.assumeFalse("permissions do not apply to this user", locked.canWrite())
            RestoreJournal.recoverIfNeeded(context, dbFile)
            assertTrue("the marker stays for the next start", RestoreJournal.marker(context).exists())
            assertTrue("the previous photos are still set aside", File(aside, "${ids[0]}/keep.jpg").exists())
        } finally {
            locked.setWritable(true)
        }

        RestoreJournal.recoverIfNeeded(context, dbFile)

        assertEquals(11, original.length())
        assertFalse(RestoreJournal.marker(context).exists())
        assertFalse(aside.exists())
    }

    /**
     * A coffee deleted while the backup is reading its photo. The read finishes from the open
     * file, so the archive holds every byte; the size it records must be those bytes too, or the
     * restore refuses an archive the backup reported as a success.
     */
    @Test fun aPhotoDeletedWhileTheBackupReadsItStillRestores() = runTest {
        val id = UUID.randomUUID().toString(); val pid = UUID.randomUUID().toString()
        db.coffeeDao().insert(CoffeeEntity(id, 1, 1, 1, CoffeeState.LOGGED, roaster = "Larkmoor"))
        val bytes = ByteArray(2 shl 20).also { java.util.Random(7).nextBytes(it) } // incompressible: read in many chunks
        File(photos.dir(id), "$pid.jpg").writeBytes(bytes)
        db.coffeeDao().insertPhotos(listOf(CoffeePhotoEntity(pid, id, PhotoRole.FRONT, 0, "photos/$id/$pid.jpg", takenAt = 1, addedAt = 1)))
        val dir = DirectorySink(folder)
        var deleted = false
        // Deletes the coffee's photos once the archive is well into the photo (past the small database entry).
        val deleting = object : BackupSink by dir {
            override fun openForWrite(tempName: String): java.io.OutputStream {
                val inner = dir.openForWrite(tempName)
                return object : java.io.OutputStream() {
                    var n = 0L
                    fun tick(k: Int) { n += k; if (!deleted && n > 500_000) { deleted = true; File(photos.root, id).deleteRecursively() } }
                    override fun write(b: Int) { inner.write(b); tick(1) }
                    override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); tick(len) }
                    override fun flush() = inner.flush()
                    override fun close() = inner.close()
                }
            }
        }

        manager.backupNow(deleting)
        assertTrue("the delete landed mid-backup", deleted)
        assertFalse(File(photos.root, id).exists())

        manager.restore({ onlyZip().inputStream() })
        assertTrue(photos.file("photos/$id/$pid.jpg").readBytes().contentEquals(bytes))
    }

    /** Restored rows may only name ids and photo paths of the shape the app writes. */
    @Test fun restoredRowsThatNameOtherFilesAreRefused() = runTest {
        val ids = seed(1)
        val coffee = UUID.randomUUID().toString(); val photo = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "crafted-${UUID.randomUUID()}.db")
        Room.databaseBuilder(context, RoastbookDatabase::class.java, file.absolutePath).addMigrations(*RoastbookDatabase.MIGRATIONS).allowMainThreadQueries().build().apply {
            coffeeDao().insert(CoffeeEntity(coffee, 1, 1, 1, CoffeeState.LOGGED))
            coffeeDao().insertPhotos(listOf(CoffeePhotoEntity(photo, coffee, PhotoRole.FRONT, 0, "photos/$coffee/$photo.jpg", thumb = "secrets/gemini.bin", takenAt = 1, addedAt = 1)))
            close()
        }
        val bytes = file.readBytes()
        val manifest = manifestBytes(listOf(ManifestFile(BackupManager.DB_ENTRY, sha256(bytes), bytes.size.toLong())), coffees = 1, photos = 1)
        val zip = buildZip(listOf(BackupManager.DB_ENTRY to bytes, BackupManager.MANIFEST_ENTRY to manifest))
        try { manager.restore({ zip.inputStream() }); fail("expected UnsafePath") } catch (e: RestoreError.UnsafePath) {}
        assertEquals(ids.toSet(), db.coffeeDao().allCoffees().map { it.id }.toSet())
    }

    @Test fun storedPathsResolveOnlyInsideThePhotoTree() = runTest {
        val outside = File(context.filesDir, "secrets").apply { mkdirs(); File(this, "gemini.bin").writeText("x") }
        assertFalse(photos.file("secrets/gemini.bin").exists())
        assertFalse(photos.file("photos/../secrets/gemini.bin").exists())
        assertFalse(photos.deleteCoffee("../secrets"))
        assertTrue(File(outside, "gemini.bin").exists())
        outside.deleteRecursively()
    }

    /** The happy path leaves no marker and no set-aside tree to recover from. */
    @Test fun aFinishedRestoreLeavesNoMarkerOrSetAsideTree() = runTest {
        seed(1)
        manager.backupNow(DirectorySink(folder), now = 1_700_000_000_000)
        manager.restore({ onlyZip().inputStream() })
        assertFalse(RestoreJournal.marker(context).exists())
        assertTrue(context.filesDir.listFiles()!!.none { it.name.startsWith("photos.old-") })
    }

    /** The counts in the manifest describe the database the archive carries. */
    @Test fun manifestCountsComeFromTheCapturedDatabase() = runTest {
        seed(3)
        manager.backupNow(DirectorySink(folder), now = 1_700_000_000_000)
        val manifest = manager.inspect { onlyZip().inputStream() }.manifest
        val staged = File(context.cacheDir, "staged-${UUID.randomUUID()}.db")
        ZipInputStream(onlyZip().inputStream()).use { zin ->
            generateSequence { zin.nextEntry }.forEach { e ->
                if (e.name == BackupManager.DB_ENTRY) staged.outputStream().use { zin.copyTo(it) }
            }
        }
        val opened = Room.databaseBuilder(context, RoastbookDatabase::class.java, staged.absolutePath)
            .addMigrations(*RoastbookDatabase.MIGRATIONS).allowMainThreadQueries().build()
        try {
            assertEquals(opened.coffeeDao().allCoffees().size, manifest.coffees)
            assertEquals(opened.coffeeDao().allPhotos().size, manifest.photos)
        } finally { opened.close(); staged.delete() }
    }

    // ---------- helpers for the restore-safety tests ----------

    private suspend fun snapshotIds(): Set<String> {
        val copy = File(AutoBackupAdopter.snapshotDir(context), AutoBackupAdopter.DB_COPY)
        val opened = Room.databaseBuilder(context, RoastbookDatabase::class.java, copy.absolutePath)
            .addMigrations(*RoastbookDatabase.MIGRATIONS).allowMainThreadQueries().build()
        return try { opened.coffeeDao().allIds().toSet() } finally { opened.close() }
    }

    private fun buildZip(entries: List<Pair<String, ByteArray>>): File {
        val out = File(folder.apply { mkdirs() }, "built-${UUID.randomUUID()}.zip")
        ZipOutputStream(out.outputStream()).use { zos ->
            entries.forEach { (name, bytes) -> zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry() }
        }
        return out
    }

    /** Rewrites an entry name in place; the two names must be the same length (they sit in two headers). */
    private fun renamedEntry(zip: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length)
        val needle = from.toByteArray(); val replacement = to.toByteArray()
        var hits = 0
        for (i in 0..zip.size - needle.size) {
            if ((needle.indices).all { zip[i + it] == needle[it] }) { replacement.forEachIndexed { k, b -> zip[i + k] = b }; hits++ }
        }
        assertEquals(2, hits) // the local header and the central directory
        return zip
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifestBytes(files: List<ManifestFile>, coffees: Int, photos: Int): ByteArray {
        val m = BackupManifest(
            schemaVersion = db.openHelper.readableDatabase.version, appVersion = "test", createdAt = 1_700_000_000_000,
            coffees = coffees, photos = photos, files = files,
        )
        return kotlinx.serialization.json.Json.encodeToString(BackupManifest.serializer(), m).toByteArray()
    }

    /** A valid database holding one photo row whose coffee is missing: the live insert rejects it. */
    private fun poisonedDatabase(): File {
        val file = File(context.cacheDir, "poison-${UUID.randomUUID()}.db")
        Room.databaseBuilder(context, RoastbookDatabase::class.java, file.absolutePath)
            .addMigrations(*RoastbookDatabase.MIGRATIONS).allowMainThreadQueries().build()
            .also { it.openHelper.writableDatabase.version }
            .close()
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        raw.use {
            // Well-formed ids and path (so the row checks pass) for a coffee that is not there.
            val ghost = UUID.randomUUID().toString(); val missing = UUID.randomUUID().toString()
            it.execSQL(
                "INSERT INTO coffee_photo (id, coffee_id, role, position, original, square, thumb, taken_at, added_at) " +
                    "VALUES ('$ghost', '$missing', 'FRONT', 0, 'photos/$missing/$ghost.jpg', NULL, NULL, 1, 1)"
            )
        }
        return file
    }

    /** Copies a zip, letting the caller rewrite entry bytes by name and append extra entries (manifest untouched unless rewritten). */
    private fun rewriteZip(src: File, extra: Map<String, ByteArray> = emptyMap(), rewrite: (String, ByteArray) -> ByteArray = { _, b -> b }): File {
        val out = File(src.parentFile, "rewritten-${UUID.randomUUID()}.zip")
        ZipOutputStream(out.outputStream()).use { zos ->
            ZipInputStream(src.inputStream()).use { zin ->
                generateSequence { zin.nextEntry }.forEach { e ->
                    val bytes = ByteArrayOutputStream().also { zin.copyTo(it) }.toByteArray()
                    zos.putNextEntry(ZipEntry(e.name)); zos.write(rewrite(e.name, bytes)); zos.closeEntry()
                }
            }
            extra.forEach { (name, bytes) -> zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry() }
        }
        return out
    }
}
