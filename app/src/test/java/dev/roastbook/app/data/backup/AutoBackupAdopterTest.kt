package dev.roastbook.app.data.backup

import androidx.room.Room
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.RoastbookDatabase
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

/** A failed Tier 0 adoption is not retried over a library the person has already started using. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AutoBackupAdopterTest {
    private lateinit var g: TestGraph
    private val context get() = g.context

    @Before fun setUp() {
        g = TestGraph()
        AutoBackupAdopter.discardSnapshot(context)
    }

    @After fun tearDown() {
        AutoBackupAdopter.discardSnapshot(context)
        g.close()
    }

    private fun open(name: String) = Room.databaseBuilder(context, RoastbookDatabase::class.java, name)
        .addMigrations(*RoastbookDatabase.MIGRATIONS).allowMainThreadQueries().build()

    @Test fun aPendingAdoptionIsGivenUpOnceTheNewLibraryHasCoffees() = runBlocking<Unit> {
        // The snapshot holds the old library; its copy failed and left the marker saying so.
        g.insertCoffee("${UUID.randomUUID()}") { copy(roaster = "Northwind") }
        val prefs = BackupPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { File(context.filesDir, "b-${UUID.randomUUID()}.preferences_pb") })
        BackupManager(context, g.db, g.photos, prefs).refreshAutoSnapshot()
        val dir = AutoBackupAdopter.snapshotDir(context)
        File(dir, "adopting").writeText("db-pending")
        assertTrue(AutoBackupAdopter.dbAdoptionPending(context))

        // Since then the person has logged a coffee in the database Room created.
        val live = "live-${UUID.randomUUID()}.db"
        val newId = UUID.randomUUID().toString()
        open(live).apply { coffeeDao().insert(CoffeeEntity(newId, 5, 5, 5, CoffeeState.LOGGED, roaster = "Larkmoor")) }.close()

        AutoBackupAdopter.adoptIfNeeded(context, live)

        assertFalse("the snapshot may be rebuilt from the live library again", AutoBackupAdopter.dbAdoptionPending(context))
        assertFalse(AutoBackupAdopter.adoptedWithoutOriginals(context))
        val kept = open(live)
        try { assertEquals(listOf(newId), kept.coffeeDao().allCoffees().map { it.id }) } finally { kept.close() }
        context.getDatabasePath(live).delete()
    }

    /**
     * A live database that cannot be read (here an empty file: Room was stopped before it
     * made its tables) is not proof the person has coffees there. The pending adoption, and with it
     * the protection of the only copy of the snapshot's library, stays for the next start.
     */
    @Test fun anUnreadableLiveDatabaseDoesNotGiveUpAPendingAdoption() = runBlocking<Unit> {
        g.insertCoffee("${UUID.randomUUID()}") { copy(roaster = "Northwind") }
        val prefs = BackupPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { File(context.filesDir, "b-${UUID.randomUUID()}.preferences_pb") })
        BackupManager(context, g.db, g.photos, prefs).refreshAutoSnapshot()
        File(AutoBackupAdopter.snapshotDir(context), "adopting").writeText("db-pending")
        val live = "live-${UUID.randomUUID()}.db"
        context.getDatabasePath(live).apply { parentFile?.mkdirs(); writeBytes(ByteArray(0)) }

        AutoBackupAdopter.adoptIfNeeded(context, live)

        assertTrue(AutoBackupAdopter.dbAdoptionPending(context))
        context.getDatabasePath(live).delete()
    }

    /**
     * Restored Tier 0 bytes are not trusted to have the shape the snapshot writer gives them: only
     * photos/<id>/<name>.jpg under the thumbs mirror is copied out. A planted restore marker and
     * set-aside tree would otherwise be acted on at the next start and replace the live photos.
     */
    @Test fun adoptionCopiesOnlyThumbnailShapedFiles() = runBlocking<Unit> {
        g.insertCoffee("${UUID.randomUUID()}") { copy(roaster = "Northwind") }
        val prefs = BackupPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { File(context.filesDir, "b-${UUID.randomUUID()}.preferences_pb") })
        BackupManager(context, g.db, g.photos, prefs).refreshAutoSnapshot()
        val thumbs = File(AutoBackupAdopter.snapshotDir(context), "thumbs")
        fun plant(rel: String, text: String) = File(thumbs, rel).apply { parentFile?.mkdirs(); writeText(text) }
        plant("restore.inprogress", "photos.old-1")
        plant("photos.old-1/a/b.jpg", "x")
        plant("secrets/gemini.bin", "x")
        plant("photos/a/b/c.jpg", "x")
        plant("photos/c1/p1.thumb.jpg", "thumb")
        val live = "live-${UUID.randomUUID()}.db"

        RestoreJournal.recoverIfNeeded(context, live)
        AutoBackupAdopter.adoptIfNeeded(context, live)

        val files = context.filesDir
        assertFalse(File(files, "restore.inprogress").exists())
        assertFalse(File(files, "photos.old-1").exists())
        assertFalse(File(files, "secrets/gemini.bin").exists())
        assertFalse(File(files, "photos/a/b/c.jpg").exists())
        assertEquals("thumb", File(files, "photos/c1/p1.thumb.jpg").readText())

        // And a capture made after that start survives the next one.
        val capture = File(files, "photos/${UUID.randomUUID()}/${UUID.randomUUID()}.jpg").apply { parentFile?.mkdirs(); writeText("new") }
        RestoreJournal.recoverIfNeeded(context, live)
        assertTrue(capture.exists())
        context.getDatabasePath(live).delete()
    }

    /** A marker naming anything but a direct photos.old-<millis> child of filesDir rolls nothing back. */
    @Test fun aRestoreMarkerNamingAnOddTreeIsIgnored() {
        val files = context.filesDir
        val nested = File(files, "autobackup/x/photos.old-5").apply { mkdirs(); File(this, "a.jpg").writeText("x") }
        val capture = File(files, "photos/c/p.jpg").apply { parentFile?.mkdirs(); writeText("live") }
        for (name in listOf("autobackup/x/photos.old-5", "photos.old-x", "../photos.old-1")) {
            File(files, "restore.inprogress").writeText(name)
            RestoreJournal.recoverIfNeeded(context)
            assertEquals("live", capture.readText())
        }
        nested.deleteRecursively()
        capture.parentFile?.deleteRecursively()
    }
}
