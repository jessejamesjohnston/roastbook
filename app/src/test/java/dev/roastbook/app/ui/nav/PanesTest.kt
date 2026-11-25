package dev.roastbook.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.R
import dev.roastbook.app.ui.theme.RoastbookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The large-screen panes: the window split, the two panes' widths, and the right pane's tip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w1280dp-h800dp")
class PanesTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun withNoCoffeesTheRightPaneSaysWhereTheyWillShowUp() {
        compose.setContent { RoastbookTheme { DetailPlaceholder(hasCoffees = false) } }
        compose.onNodeWithText(context.getString(R.string.pane_empty_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.pane_empty_body)).assertExists()
        compose.onNodeWithText(context.getString(R.string.pane_pick_title)).assertDoesNotExist()
    }

    @Test fun withCoffeesTheRightPaneAsksForOneToBePicked() {
        compose.setContent { RoastbookTheme { DetailPlaceholder(hasCoffees = true) } }
        compose.onNodeWithText(context.getString(R.string.pane_pick_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.pane_pick_body)).assertExists()
        compose.onNodeWithText(context.getString(R.string.pane_empty_title)).assertDoesNotExist()
    }

    private fun panes(split: PaneSplit) {
        compose.setContent {
            RoastbookTheme {
                Box(Modifier.requiredSize(1000.dp, 600.dp)) {
                    ListDetailPanes(
                        split,
                        list = { Box(Modifier.fillMaxSize().testTag("list")) },
                        detail = { Box(Modifier.fillMaxSize().testTag("detail")) },
                    )
                }
            }
        }
    }

    @Test fun aSeamlessSplitPutsAHairlineBetweenThePanes() {
        panes(PaneSplit(true, listWidth = 400.dp))
        val list = compose.onNodeWithTag("list").getBoundsInRoot()
        val detail = compose.onNodeWithTag("detail").getBoundsInRoot()
        assertEquals(400.dp, list.right - list.left)
        assertEquals(1.dp, detail.left - list.right)
        assertEquals(599.dp, detail.right - detail.left)
    }

    @Test fun aHingeLeavesItsWidthEmptyBetweenThePanes() {
        panes(PaneSplit(true, listWidth = 480.dp, gap = 40.dp))
        val list = compose.onNodeWithTag("list").getBoundsInRoot()
        val detail = compose.onNodeWithTag("detail").getBoundsInRoot()
        assertEquals(480.dp, list.right - list.left)
        assertEquals(40.dp, detail.left - list.right)
        assertEquals(480.dp, detail.right - detail.left)
    }

    private fun splitFor(width: Dp, height: Dp): PaneSplit {
        var out: PaneSplit? = null
        compose.setContent { out = rememberPaneSplit(width, height) }
        compose.waitForIdle()
        return out!!
    }

    @Test fun withoutAFoldTheWindowSplitsInHalfWhenWide() {
        val s = splitFor(1280.dp, 800.dp)
        assertTrue(s.twoPane)
        assertEquals(640.dp, s.listWidth)
        assertEquals(0.dp, s.gap)
    }

    @Test fun withoutAFoldANarrowWindowIsOnePane() = assertFalse(splitFor(412.dp, 800.dp).twoPane)
}
