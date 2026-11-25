package dev.roastbook.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.color.RowColors
import dev.roastbook.app.data.backup.AutoBackupAdopter
import dev.roastbook.app.data.backup.BackupManager
import dev.roastbook.app.data.backup.BackupPrefs
import dev.roastbook.app.data.backup.DirectorySink
import dev.roastbook.app.data.backup.RestoreJournal
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.data.db.RoastbookDatabase
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.data.photo.SquareExporter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A hero crop takes seconds. Whatever happens to its coffee meanwhile (a restore of a backup of the
 * same journal, which brings back the very same ids, or a delete), the crop must not write its result
 * into the library that is there when it finishes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HeroWriteGuardTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbFile = "hero-guard-test.db"
    private lateinit var db: RoastbookDatabase
    private lateinit var photos: PhotoStore
    private lateinit var manager: BackupManager
    private lateinit var folder: File

    /** Holds the crop until the test lets it go, so something can happen to the coffee meanwhile. */
    private class GatedCropper : SubjectCropper {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun crop(bitmap: Bitmap): CropResult {
            entered.complete(Unit)
            release.await()
            return CropResult(Rect(0, 0, bitmap.width, bitmap.height), null, segmented = true)
        }
    }
    private object Palette : PaletteExtractor {
        override fun extract(subject: Bitmap) = RowColors(0x111111, 0x222222, 0x333333, 0x444444, fallback = false)
    }
    private val noExport = object : SquareExporter { override suspend fun maybeExport(square: File, displayName: String) {} }

    @Before fun setUp() {
        context.getDatabasePath(dbFile).let { it.delete(); File(it.path + "-wal").delete(); File(it.path + "-shm").delete() }
        db = Room.databaseBuilder(context, RoastbookDatabase::class.java, dbFile).addMigrations(*RoastbookDatabase.MIGRATIONS).allowMainThreadQueries().build()
        // One PhotoStore shared by the backup manager and the crop, as in the app (a singleton).
        photos = object : PhotoStore(context) {
            override suspend fun load(relative: String, maxEdge: Int): Bitmap? = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        }
        photos.root.deleteRecursively()
        val prefs = BackupPrefs(PreferenceDataStoreFactory.create { File(context.filesDir, "test-${UUID.randomUUID()}.preferences_pb") })
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

    private suspend fun seedOne(): Pair<String, String> {
        val id = UUID.randomUUID().toString(); val pid = UUID.randomUUID().toString()
        File(photos.dir(id), "$pid.jpg").writeBytes(ByteArray(300) { it.toByte() })
        db.coffeeDao().insert(CoffeeEntity(id, 1, 1, 1, CoffeeState.LOGGED, roaster = "Larkmoor", heroPhotoId = pid))
        db.coffeeDao().insertPhotos(listOf(CoffeePhotoEntity(pid, id, PhotoRole.FRONT, 0, "photos/$id/$pid.jpg", takenAt = 1, addedAt = 1)))
        return id to pid
    }

    private fun squareFile(id: String, pid: String) = File(photos.root, "$id/$pid.square.jpg")

    /** Runs a crop that blocks in the cropper, does [meanwhile], then lets the crop finish. */
    private fun cropWhile(id: String, meanwhile: suspend () -> Unit) = runBlocking {
        val cropper = GatedCropper()
        val hero = HeroProcessor(db.coffeeDao(), photos, cropper, Palette, noExport)
        val job = launch(Dispatchers.Default) { hero.process(id) }
        withTimeout(10_000) { cropper.entered.await() }
        meanwhile()
        cropper.release.complete(Unit)
        withTimeout(10_000) { job.join() }
    }

    @Test fun withNothingInTheWayTheCropWritesItsResult() {
        val (id, pid) = runBlocking { seedOne() }
        cropWhile(id) {}
        val photo = runBlocking { db.coffeeDao().photos(id).single() }
        assertEquals("photos/$id/$pid.square.jpg", photo.square)
        assertTrue(squareFile(id, pid).exists())
        val c = runBlocking { db.coffeeDao().get(id)!! }
        assertTrue(c.heroSegmented)
        assertEquals(1, c.heroAttempts)
    }

    @Test fun aRestoreOfTheSameJournalMidCropKeepsTheRestoredCoffeeAsItCame() {
        val (id, pid) = runBlocking { seedOne() }
        runBlocking { manager.backupNow(DirectorySink(folder)) }
        val zip = folder.listFiles()!!.single { it.name.endsWith(".zip") }
        val backedUp = runBlocking { db.coffeeDao().get(id)!! }

        cropWhile(id) { manager.restore({ zip.inputStream() }) }

        // Same ids came back; the crop that read the old library wrote nothing into this one.
        val restored = runBlocking { db.coffeeDao().get(id)!! }
        assertEquals(backedUp, restored)
        assertFalse(restored.heroSegmented)
        assertEquals(0, restored.heroAttempts)
        assertNull(runBlocking { db.coffeeDao().photos(id).single().square })
        assertFalse(squareFile(id, pid).exists())
    }

    @Test fun aRestoreThatRemovesTheOpenCoffeeIsSeenByItsObservers() {
        runBlocking { seedOne() }
        runBlocking { manager.backupNow(DirectorySink(folder)) }
        val zip = folder.listFiles()!!.single { it.name.endsWith(".zip") }
        // Added after the backup and open in the pane when the user restores.
        val (open, _) = runBlocking { seedOne() }
        assertNotNull(runBlocking { db.coffeeDao().observe(open).first() })
        runBlocking { manager.restore({ zip.inputStream() }) }
        // The pane's existence check reads this flow: the row is gone, so the pane closes.
        assertNull(runBlocking { db.coffeeDao().observe(open).first() })
    }

    @Test fun aDeleteMidCropLeavesNoFolderBehind() {
        val (id, _) = runBlocking { seedOne() }
        cropWhile(id) {
            photos.captureLock.withLock {
                db.coffeeDao().delete(db.coffeeDao().get(id)!!)
                photos.deleteCoffee(id)
            }
        }
        assertNull(runBlocking { db.coffeeDao().get(id) })
        assertFalse("the crop must not recreate the deleted coffee's folder", File(photos.root, id).exists())
    }
}
