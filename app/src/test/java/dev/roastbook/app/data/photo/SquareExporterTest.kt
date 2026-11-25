package dev.roastbook.app.data.photo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import dev.roastbook.app.testutil.TestGraph
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Save squares to Photos": off by default, and when on, one finished image in Pictures/Roastbook. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SquareExporterTest {
    private lateinit var g: TestGraph
    private lateinit var square: File

    @Before fun setUp() {
        g = TestGraph()
        square = g.stagedJpeg("square.jpg")
    }

    @After fun tearDown() { g.close() }

    private fun exporter() = MediaStoreSquareExporter(g.context, g.userPrefs)

    @Test fun nothingIsSavedWhileTheSettingIsOff() = runBlocking {
        val provider = recordingProvider()
        exporter().maybeExport(square, "roastbook-c1")
        assertTrue(provider.inserts.isEmpty())
    }

    @Test fun withTheSettingOnTheSquareLandsInPicturesAndIsPublishedWhenComplete() = runBlocking {
        g.userPrefs.setSaveSquares(true)
        val provider = recordingProvider()
        exporter().maybeExport(square, "roastbook-c1")
        val (uri, values) = provider.inserts.single()
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, uri)
        assertEquals("roastbook-c1.jpg", values.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals("image/jpeg", values.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("Pictures/Roastbook", values.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals("hidden from the gallery while it is written", 1, values.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(0, provider.updates.single().getAsInteger(MediaStore.Images.Media.IS_PENDING))
    }

    @Test fun aSquareThatIsGoneIsNotSaved() = runBlocking {
        g.userPrefs.setSaveSquares(true)
        val provider = recordingProvider()
        square.delete()
        exporter().maybeExport(square, "roastbook-c1")
        assertTrue(provider.inserts.isEmpty())
    }

    /** A media provider that refuses every insert, as a full or unmounted volume does. */
    class RefusingMediaProvider : ContentProvider() {
        var updates = 0
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int { updates++; return 0 }
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    /**
     * A media provider that accepts the insert and records it and what follows: the bytes written
     * to the new image, every update, whether it was published, and any delete. [failWrites] refuses the write, as a
     * volume that fills up or goes away after the insert does.
     */
    class RecordingMediaProvider : ContentProvider() {
        val image: Uri = Uri.parse("content://media/external/images/media/42")
        lateinit var written: File
        var failWrites = false
        val inserts = mutableListOf<Pair<Uri, ContentValues>>()
        val updates = mutableListOf<ContentValues>()
        val published = mutableListOf<Uri>()
        val deleted = mutableListOf<Uri>()
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri { inserts += uri to ContentValues(values); return image }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failWrites) throw java.io.FileNotFoundException("volume gone")
            return ParcelFileDescriptor.open(written, ParcelFileDescriptor.parseMode(mode))
        }
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int {
            if (values != null) updates += ContentValues(values)
            if (values?.getAsInteger(MediaStore.Images.Media.IS_PENDING) == 0) published += uri
            return 1
        }
        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int { deleted += uri; return 1 }
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
    }

    private fun recordingProvider() = Robolectric.setupContentProvider(RecordingMediaProvider::class.java, MediaStore.AUTHORITY).apply {
        written = File(g.context.cacheDir, "gallery-image.jpg").apply { delete() }
    }

    @Test fun thePublishedImageHoldsTheSquaresBytes() = runBlocking {
        val provider = recordingProvider()
        g.userPrefs.setSaveSquares(true)
        exporter().maybeExport(square, "roastbook-c1")
        assertEquals(listOf(provider.image), provider.published)
        assertArrayEquals(square.readBytes(), provider.written.readBytes())
        assertTrue(provider.deleted.isEmpty())
    }

    @Test fun aWriteThatFailsAfterTheInsertRemovesTheHiddenImage() = runBlocking {
        val provider = recordingProvider().apply { failWrites = true }
        g.userPrefs.setSaveSquares(true)
        exporter().maybeExport(square, "roastbook-c1") // must not throw: the hero crop already landed
        assertEquals("no half-written image left pending in the gallery", listOf(provider.image), provider.deleted)
        assertTrue(provider.published.isEmpty())
    }

    @Test fun aRefusedSaveIsSwallowedAndPublishesNothing() = runBlocking {
        val provider = Robolectric.setupContentProvider(RefusingMediaProvider::class.java, MediaStore.AUTHORITY)
        g.userPrefs.setSaveSquares(true)
        exporter().maybeExport(square, "roastbook-c1") // must not throw: the hero crop already landed
        assertEquals(0, provider.updates)
        assertTrue(square.exists())
    }
}
