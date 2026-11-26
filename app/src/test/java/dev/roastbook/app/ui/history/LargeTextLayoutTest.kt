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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * At 200% text on a narrow phone a coffee's name keeps the card's width: the rate button moves
 * under it instead of squeezing it into a sliver where words break letter by letter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LargeTextLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    private val row = CoffeeRow(
        "c1", CoffeeState.LOGGED, 1_000L, "Lantern Roasters", "Northwind Morning", emptyList(), null, false,
        null, null, null, null, true, ExtractionSource.NANO, 1, null, null, null, 0,
    )

    private fun show() = compose.setContent { RoastbookTheme { CoffeeRowCard(row, PhotoStore(context), onClick = {}, onRate = {}) } }

    @Config(qualifiers = "w320dp-h700dp", fontScale = 2.0f)
    @Test fun atLargeTextTheRateButtonSitsUnderTheName() {
        show()
        val name = compose.onNodeWithText("Northwind Morning", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText(context.getString(R.string.row_unrated_cta), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("button $button under name $name", button.top >= name.bottom)
        // Robolectric's text metrics aren't a device's, so the words themselves are checked on an emulator.
    }

    @Config(qualifiers = "w400dp-h800dp")
    @Test fun atNormalTextTheRateButtonSitsBesideTheName() {
        show()
        val name = compose.onNodeWithText("Northwind Morning", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText(context.getString(R.string.row_unrated_cta), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("button $button beside name $name", button.left >= name.right)
    }
}
