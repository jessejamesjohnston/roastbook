package dev.roastbook.app.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The row colors come from the bag's own pixels, and the crop square stays on the subject and inside the photo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaletteAndCropGeometryTest {
    private val palette = AndroidPaletteExtractor()

    private fun red(c: Int) = Color.red(c)
    private fun blue(c: Int) = Color.blue(c)

    private fun solid(color: Int, size: Int = 200) = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** A segmentation foreground: the subject's pixels in a square, transparent everywhere else. */
    private fun cutout(color: Int, size: Int = 200, subject: Int = 100): Bitmap = solid(Color.TRANSPARENT, size).apply {
        val from = (size - subject) / 2
        for (y in from until from + subject) for (x in from until from + subject) setPixel(x, y, color)
    }

    @Test fun aRedBagGivesRedRowColors() {
        val colors = palette.extract(solid(Color.rgb(200, 30, 30)))
        assertFalse(colors.fallback)
        assertTrue("accent leans red", red(colors.accentLight) > blue(colors.accentLight))
        assertTrue("tint leans red", red(colors.tintLight) > blue(colors.tintLight))
    }

    @Test fun theTransparentBackgroundOfACutoutIsNotCounted() {
        val colors = palette.extract(cutout(Color.rgb(30, 60, 210)))
        assertFalse(colors.fallback)
        assertTrue("accent leans blue", blue(colors.accentLight) > red(colors.accentLight))
    }

    @Test fun aGrayBagFallsBackToTheNeutralRowColors() {
        assertTrue(palette.extract(solid(Color.rgb(128, 128, 128))).fallback)
    }

    @Test fun aCutoutWithOnlyAFewSubjectPixelsStillTakesItsColorFromThem() {
        val colors = palette.extract(cutout(Color.rgb(200, 30, 30), subject = 4))
        assertFalse(colors.fallback)
        assertTrue("accent leans red, not the black of the background", red(colors.accentLight) > blue(colors.accentLight))
    }

    // --- crop geometry ---

    @Test fun theFallbackSquareIsCenteredOnTheLongSide() {
        assertEquals(Rect(50, 0, 350, 300), MlKitSubjectCropper.centerSquare(400, 300))
        assertEquals(Rect(0, 150, 300, 450), MlKitSubjectCropper.centerSquare(300, 600))
    }

    @Test fun aTallBagIsCroppedToItsShortEdgeAroundItsCenter() {
        val box = Rect(150, 50, 250, 350) // 100 wide, 300 tall, centered at (200, 200)
        assertEquals(Rect(148, 148, 252, 252), MlKitSubjectCropper.squareAround(box, 400, 400))
    }

    @Test fun aTinySubjectStillGetsAQuarterOfThePhotoAndStaysInside() {
        val box = Rect(0, 0, 20, 20) // in the corner
        assertEquals(Rect(0, 0, 100, 100), MlKitSubjectCropper.squareAround(box, 400, 400))
    }

    @Test fun aSubjectLargerThanThePhotoIsClampedToThePhoto() {
        val box = Rect(-10, -10, 410, 310)
        assertEquals(Rect(50, 0, 350, 300), MlKitSubjectCropper.squareAround(box, 400, 300))
    }
}
