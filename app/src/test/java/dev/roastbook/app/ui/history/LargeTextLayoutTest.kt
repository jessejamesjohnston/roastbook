package dev.roastbook.app.ui.history

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeRow
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.ui.theme.RoastbookTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A coffee's name never breaks mid-word in the journal: where its longest word wouldn't fit beside
 * the rate button, the button moves under the text. Robolectric's text metrics aren't a device's, so
 * the rule is checked with typical glyph widths at every text size from 100% to 200% on a 320 dp
 * phone, and the layout itself with the button beside the name at normal size; the emulator walk
 * shows the real text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LargeTextLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    /** Roughly how wide a word sets at [sp] (bold sans averages about 0.6 em per letter). */
    private fun width(word: String, sp: Float, scale: Float) = (word.length * 0.6f * sp * scale).roundToInt()

    @Test fun aLongOneWordNameAlwaysHasRoomForItsLongestWord() {
        val row = 320
        val fixed = 100 // padding, photo and gaps, in dp at density 1
        for (scale in listOf(1.0f, 1.15f, 1.3f, 1.5f, 1.8f, 2.0f)) {
            for (name in listOf("Yirgacheffe", "Northwind", "Kochere", "Sidamo")) {
                val word = width(name, 16f, scale) // titleMedium
                val button = maxOf(64, width("Tried it yet?", 11f, scale) + 20) // labelSmall plus padding
                val under = trailingGoesUnder(row, word, button, fixed)
                val room = if (under) row - fixed + 8 + 0 else row - fixed - button
                assertTrue("$name at ${(scale * 100).roundToInt()}%: $word in $room (button under: $under)", word <= room)
            }
        }
    }

    @Test fun theButtonStaysBesideANameThatFits() {
        assertTrue(!trailingGoesUnder(rowWidthPx = 400, longestWordPx = 120, trailingPx = 90, fixedPx = 100))
        assertTrue(trailingGoesUnder(rowWidthPx = 320, longestWordPx = 160, trailingPx = 90, fixedPx = 100))
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test fun atNormalTextTheRateButtonSitsBesideTheName() {
        val row = CoffeeRow(
            "c1", CoffeeState.LOGGED, 1_000L, "Lantern Roasters", "Northwind Morning", emptyList(), null, false,
            null, null, null, null, true, ExtractionSource.NANO, 1, null, null, null, 0,
        )
        compose.setContent { RoastbookTheme { CoffeeRowCard(row, PhotoStore(context), onClick = {}, onRate = {}) } }
        val name = compose.onNodeWithText("Northwind Morning", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText(context.getString(R.string.row_unrated_cta), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("button $button beside name $name", button.left >= name.right)
    }
}
