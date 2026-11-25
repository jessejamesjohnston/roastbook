package dev.roastbook.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import coil3.imageDecoderEnabled
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.roastbook.app.data.db.CoffeeRow
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.testutil.TestGraph
import dev.roastbook.app.ui.detail.awaitUntil
import dev.roastbook.app.ui.theme.LocalDarkTheme
import dev.roastbook.app.ui.theme.LocalHighContrast
import dev.roastbook.app.ui.theme.RoastbookTheme
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the rating dots and the hero square actually draw, read back from the rendered pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComponentsDrawingTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var g: TestGraph

    @Before fun setUp() { g = TestGraph() }
    @After fun tearDown() { g.close() }

    private fun close(a: Color, b: Color) =
        abs(a.red - b.red) < 0.04f && abs(a.green - b.green) < 0.04f && abs(a.blue - b.blue) < 0.04f

    // --- rating dots ---

    /** Each dot's center pixel, over a white card, in dot order. */
    private fun dotCenters(overall: Float, accent: Color): List<Color> {
        compose.setContent {
            RoastbookTheme(darkTheme = false) {
                Box(Modifier.background(Color.White).testTag("dots")) { RatingDots(overall, accent, dot = 20.dp) }
            }
        }
        val px = compose.onNodeWithTag("dots").captureToImage().toPixelMap()
        val dot = px.height // one row of square dots
        val step = (px.width - dot) / 4 // dot plus gap
        return (0 until 5).map { i -> px[i * step + dot / 2, dot / 2] }
    }

    @Test fun twoAndAHalfFillsTwoDotsHalfFillsTheThirdAndLeavesTwoEmpty() {
        val accent = Color(0xFF8B2E16)
        val half = accent.copy(alpha = 0.45f).compositeOver(Color.White)
        val centers = dotCenters(2.5f, accent)
        assertTrue("dot 1 filled: ${centers[0]}", close(centers[0], accent))
        assertTrue("dot 2 filled: ${centers[1]}", close(centers[1], accent))
        assertTrue("dot 3 half: ${centers[2]}", close(centers[2], half))
        assertTrue("dot 4 empty: ${centers[3]}", close(centers[3], Color.White))
        assertTrue("dot 5 empty: ${centers[4]}", close(centers[4], Color.White))
    }

    @Test fun aScoreJustShortOfAWholeDotRoundsTheLastDotUp() {
        val accent = Color(0xFF1B5E20)
        val centers = dotCenters(3.8f, accent)
        assertTrue("0.8 of a dot reads as a full dot: ${centers[3]}", close(centers[3], accent))
        assertTrue(close(centers[4], Color.White))
    }

    // --- hero square ---

    @Test fun aWhiteBagGetsAnInsetStrokeAndOthersDoNot() {
        var container = Color.Unspecified
        compose.setContent {
            RoastbookTheme(darkTheme = false) {
                container = MaterialTheme.colorScheme.surfaceContainerHigh
                Row {
                    HeroSquare(null, size = 40.dp, radius = 0.dp, stroke = true, modifier = Modifier.testTag("stroked"))
                    HeroSquare(null, size = 40.dp, radius = 0.dp, stroke = false, modifier = Modifier.testTag("plain"))
                }
            }
        }
        compose.onNodeWithTag("stroked").assertWidthIsEqualTo(40.dp).assertHeightIsEqualTo(40.dp)
        val stroked = compose.onNodeWithTag("stroked").captureToImage().toPixelMap()
        val plain = compose.onNodeWithTag("plain").captureToImage().toPixelMap()
        assertTrue("interior is the empty container", close(stroked[stroked.width / 2, stroked.height / 2], container))
        assertTrue("the edge carries the stroke", !close(stroked[0, stroked.height / 2], container))
        assertTrue("no stroke: the edge is the container", close(plain[0, plain.height / 2], container))
    }

    @Test fun aMissingPhotoFileShowsTheEmptyContainer() {
        var container = Color.Unspecified
        val missing = File(g.context.filesDir, "photos/none/none.square.jpg")
        compose.setContent {
            RoastbookTheme(darkTheme = false) {
                container = MaterialTheme.colorScheme.surfaceContainerHigh
                HeroSquare(missing, size = 40.dp, radius = 0.dp, stroke = false, modifier = Modifier.testTag("hero"))
            }
        }
        val px = compose.onNodeWithTag("hero").captureToImage().toPixelMap()
        assertTrue(close(px[px.width / 2, px.height / 2], container))
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test fun aStoredPhotoFillsTheSquare() {
        // Robolectric can't run ImageDecoder; the image loader falls back to BitmapFactory, which it can.
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(g.context).imageDecoderEnabled(false).build())
        try { drawStoredPhoto() } finally { coil3.SingletonImageLoader.reset() }
    }

    private fun drawStoredPhoto() {
        val red = Color(0xFFD03020)
        val photo = File(g.context.filesDir, "hero-solid.png").apply {
            outputStream().use { out ->
                android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(red.toArgb()) }
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        compose.setContent {
            RoastbookTheme(darkTheme = false) {
                HeroSquare(photo, size = 40.dp, radius = 0.dp, stroke = false, modifier = Modifier.testTag("hero"))
            }
        }
        // The image loads off the main thread: wait until the center shows the photo, not the empty container.
        compose.awaitUntil {
            val px = compose.onNodeWithTag("hero").captureToImage().toPixelMap()
            close(px[px.width / 2, px.height / 2], red)
        }
        photo.delete()
    }

    // --- row helpers ---

    @Test fun aRowsTintComesFromItsStoredColors() {
        val row = CoffeeRow(
            id = "c1", state = CoffeeState.RATED, loggedAt = 1, roaster = "Northwind", coffeeName = null, tastingNotes = emptyList(),
            overall = 4f, buyAgain = false, tintLight = 0xFFAABBCC.toInt(), tintDark = 0xFF010203.toInt(), accentLight = 0xFF112233.toInt(),
            accentDark = 0xFF445566.toInt(), paletteFallback = false, extractionSource = ExtractionSource.NANO, extractionAttempts = 1,
            extractionTrail = null, nextAttemptAt = null, heroThumb = null, photoCount = 1,
        )
        var tint: ThemedTint? = null
        compose.setContent {
            CompositionLocalProvider(LocalDarkTheme provides false, LocalHighContrast provides false) { tint = row.themedTint() }
        }
        compose.waitForIdle()
        assertEquals(ThemedTint(Color(0xFFAABBCC.toInt()), Color(0xFF112233.toInt()), fallback = false), tint)
    }

    @Test fun aStoredPhotoPathResolvesAndNoPathIsNoFile() {
        assertNull(g.photos.fileOrNull(null))
        assertEquals(File(g.context.filesDir, "photos/c1/p1.thumb.jpg"), g.photos.fileOrNull("photos/c1/p1.thumb.jpg"))
    }
}
