package dev.roastbook.app.data.backup

import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.extraction.Clock
import dev.roastbook.app.extraction.ExtractionTimeouts
import dev.roastbook.app.extraction.Extractor
import dev.roastbook.app.extraction.MemoryHealth
import dev.roastbook.app.extraction.NanoStatus
import dev.roastbook.app.extraction.OcrTier
import dev.roastbook.app.extraction.OffCloud
import dev.roastbook.app.extraction.ScriptedNano
import dev.roastbook.app.extraction.ScriptedReader
import dev.roastbook.app.testutil.TestGraph
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Work that read the library a restore replaced does not write into the restored one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RestoreFenceTest {
    private lateinit var g: TestGraph
    private lateinit var backups: BackupManager
    private lateinit var folder: File

    @Before fun setUp() {
        g = TestGraph(loadPhotos = true)
        val prefs = BackupPrefs(PreferenceDataStoreFactory.create { File(g.context.filesDir, "b-${UUID.randomUUID()}.preferences_pb") })
        backups = BackupManager(g.context, g.db, g.photos, prefs)
        folder = File(g.context.cacheDir, "sink-${UUID.randomUUID()}")
    }

    @After fun tearDown() {
        folder.deleteRecursively()
        AutoBackupAdopter.discardSnapshot(g.context)
        RestoreJournal.clear(g.context)
        g.close()
    }

    /** OCR that waits for the test to let it answer, so a restore can land while a read is running. */
    private class GatedOcr(private val text: String) : OcrTier {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun recognize(image: Bitmap): String { started.complete(Unit); release.await(); return text }
    }

    /**
     * A read of a coffee is running when a restore brings back that same coffee (the same
     * id) as it was in the backup. The read's result describes the replaced row; it is dropped
     * instead of changing the restored row's fields and provenance.
     */
    @Test fun aReadRunningAcrossARestoreDoesNotWriteIntoTheRestoredRow() = runBlocking {
        val id = UUID.randomUUID().toString(); val pid = UUID.randomUUID().toString()
        g.insertCoffee(id) { copy(roaster = "Larkmoor") }
        File(g.photos.dir(id), "$pid.jpg").outputStream().use {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        g.dao.insertPhotos(listOf(CoffeePhotoEntity(pid, id, PhotoRole.FRONT, 0, "photos/$id/$pid.jpg", takenAt = 1, addedAt = 1)))
        backups.backupNow(DirectorySink(folder))
        val zip = folder.listFiles()!!.single { it.name.endsWith(".zip") }
        val archived: CoffeeEntity = g.dao.get(id)!!

        // Read again: the row goes back to EXTRACTING and the read starts.
        g.dao.update(archived.copy(state = CoffeeState.EXTRACTING, updatedAt = 2_000))
        val ocr = GatedOcr("LARKMOOR\nNatural process")
        val extractor = Extractor(g.db, g.photos, ScriptedNano(status = NanoStatus.UNAVAILABLE), ocr, OffCloud, ScriptedReader(), MemoryHealth(), Clock { 1_000_000L }, ExtractionTimeouts())
        val read = async(Dispatchers.Default) { extractor.run(id) }
        withTimeout(10_000) { ocr.started.await() }

        backups.restore({ zip.inputStream() })
        ocr.release.complete(Unit)
        withTimeout(10_000) { read.await() }

        val after = g.dao.get(id)!!
        assertEquals(archived.state, after.state)
        assertEquals(archived.process, after.process)
        assertEquals(ExtractionSource.NONE, after.extractionSource)
        assertEquals(archived.extractionMeta, after.extractionMeta)
        assertEquals(archived.userEdited, after.userEdited)
    }
}
