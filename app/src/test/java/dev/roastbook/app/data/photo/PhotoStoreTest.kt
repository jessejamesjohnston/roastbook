package dev.roastbook.app.data.photo

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import dev.roastbook.app.testutil.TestGraph
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Picked photos are staged whole or not at all, and stored photos come back upright and no larger than asked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoStoreTest {
    private lateinit var g: TestGraph
    private val store get() = g.photos

    @Before fun setUp() { g = TestGraph(loadPhotos = true) }
    @After fun tearDown() { g.close() }

    /** A JPEG whose left half is red and right half blue, so a mirror or a turn shows. */
    private fun jpeg(w: Int, h: Int, orientation: Int? = null, takenAt: String? = null): File {
        val f = File(g.context.cacheDir, "src-${w}x$h-${orientation ?: 0}.jpg")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, if (x < w / 2) Color.RED else Color.BLUE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (orientation != null || takenAt != null) {
            ExifInterface(f).apply {
                orientation?.let { setAttribute(ExifInterface.TAG_ORIENTATION, it.toString()) }
                takenAt?.let { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it) }
                saveAttributes()
            }
        }
        return f
    }

    private fun isRed(c: Int) = Color.red(c) > 200 && Color.blue(c) < 60
    private fun isBlue(c: Int) = Color.blue(c) > 200 && Color.red(c) < 60

    // --- staging a picked photo ---

    @Test fun aPickedPhotoIsCopiedIntoStaging() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val staged = store.stageFromStream { ByteArrayInputStream(bytes) }!!
        assertEquals(store.staging, staged.parentFile)
        assertArrayEquals(bytes, staged.readBytes())
    }

    @Test fun aPhotoThatCannotBeOpenedStagesNothing() = runBlocking {
        assertNull(store.stageFromStream { null })
    }

    @Test fun aCopyThatBreaksOffLeavesNoHalfFileBehind() = runBlocking {
        val breaking = object : InputStream() {
            var sent = 0
            override fun read(): Int = if (sent++ < 10) 7 else throw IOException("the provider went away")
        }
        try {
            store.stageFromStream { breaking }
            fail("the failure reaches the caller")
        } catch (e: IOException) {
            assertEquals("the provider went away", e.message)
        }
        assertTrue("staging holds no partial copy", store.staging.listFiles().orEmpty().isEmpty())
    }

    // --- decoding ---

    @Test fun aLargePhotoIsScaledToTheRequestedEdge() {
        val bmp = PhotoStore.decodeUpright(jpeg(300, 200), 256)!!
        assertEquals(256, bmp.width)
        assertEquals(170, bmp.height)
    }

    @Test fun aPhotoTakenSidewaysComesBackUpright() {
        val bmp = PhotoStore.decodeUpright(jpeg(40, 20, ExifInterface.ORIENTATION_ROTATE_90), 2048)!!
        assertEquals(20, bmp.width)
        assertEquals(40, bmp.height)
        assertTrue("the left half is now on top", isRed(bmp.getPixel(10, 5)))
        assertTrue(isBlue(bmp.getPixel(10, 35)))
    }

    @Test fun aMirroredPhotoIsFlippedBack() {
        val bmp = PhotoStore.decodeUpright(jpeg(40, 20, ExifInterface.ORIENTATION_FLIP_HORIZONTAL), 2048)!!
        assertEquals(40, bmp.width)
        assertTrue(isBlue(bmp.getPixel(5, 10)))
        assertTrue(isRed(bmp.getPixel(35, 10)))
    }

    @Test fun aStoredPhotoLoadsAtTheAskedSize() = runBlocking {
        val imported = store.importOriginal("c1", "p1", jpeg(300, 200))
        val bmp = store.load(imported.relativePath, 100)!!
        assertEquals(100, bmp.width)
        assertNull("a path the app never writes loads nothing", store.load("../outside.jpg", 100))
    }

    @Test fun theCaptureTimeComesFromTheCamerasExifDate() = runBlocking {
        val imported = store.importOriginal("c1", "p1", jpeg(40, 20, takenAt = "2026:03:14 09:26:53"))
        val expected = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse("2026:03:14 09:26:53")!!.time
        assertEquals(expected, imported.takenAt)
    }

    /** Ids become directory and file names: a traversing id from a restored row writes nothing anywhere. */
    @Test fun heroRenditionsRefuseIdsThatAreNotPlainNames() = runBlocking<Unit> {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val crop = android.graphics.Rect(0, 0, 64, 64)
        for ((coffee, photo) in listOf("../x" to "p", "c" to "../../y/p", "c" to "a/b", "." to "p")) {
            try {
                store.writeHeroRenditions(coffee, photo, bmp, crop)
                fail("wrote renditions for $coffee / $photo")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertTrue(!File(g.context.filesDir, "x").exists())
        assertTrue(!File(g.context.filesDir, "y").exists())
        assertTrue(!File(store.root, "c").exists())
        val (square, thumb) = store.writeHeroRenditions("c0ffee", "p1", bmp, crop)
        assertEquals("photos/c0ffee/p1.square.jpg", square)
        assertEquals("photos/c0ffee/p1.thumb.jpg", thumb)
    }
}
