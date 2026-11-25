package dev.roastbook.app.ui.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.view.View
import android.view.Window
import androidx.activity.ComponentDialog

/** The coffee page as the right-hand pane on a large screen: cards edit in a dialog over both panes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w900dp-h2600dp")
class DetailScreenPaneTest : DetailScreenTestBase() {
    private val facts get() = str(R.string.detail_facts)
    private fun field(label: Int) = compose.onNode(hasSetTextAction() and hasText(str(label)))
    private fun save() = compose.onNodeWithText(str(R.string.detail_save))
    private fun dialogOpen() = save().exists()

    /** Back (or Escape) inside the editing dialog. */
    private fun backInDialog() {
        compose.waitForIdle()
        compose.runOnUiThread { topDialog().onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** The front-most dialog window, found through the window manager's view list (no public API reaches it). */
    private fun topDialog(): ComponentDialog {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val views = (global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<*>)
            .filterIsInstance<View>()
        return views.asReversed().firstNotNullOf { v ->
            runCatching { (v.javaClass.getDeclaredField("mWindow").apply { isAccessible = true }.get(v) as Window).callback as? ComponentDialog }.getOrNull()
        }
    }

    private fun northwind() = insert { copy(roaster = "Northwind", coffeeName = "Cloud Terrace") }

    @Test fun thePaneHasNoBackArrowAndPutsTheTitleBesideTheHero() {
        northwind()
        show(inPane = true)
        compose.onNodeWithContentDescription(str(R.string.detail_back)).assertDoesNotExist()
        val title = compose.onNode(hasText("Cloud Terrace") and isHeading()).fetchSemanticsNode().boundsInRoot
        // The 260dp hero sits to the left of the title in a wide window.
        val heroWidth = with(compose.density) { 260.dp.toPx() }
        assertTrue("title beside the hero", title.left >= heroWidth)
    }

    @Test fun factsEditedInTheDialogAreSavedAndTheCardStaysReadOnly() {
        northwind()
        show(inPane = true)
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, facts)).performClick()
        assertTrue(dialogOpen())
        // The card behind the dialog keeps showing the facts, not a second editor.
        compose.onNodeWithContentDescription(str(R.string.detail_done_section, facts)).assertDoesNotExist()
        field(R.string.field_roaster).performTextReplacement("Larkmoor")
        save().performClick()
        compose.awaitUntil { row()?.roaster == "Larkmoor" }
        assertNull(sessions.session.value)
        assertTrue(!dialogOpen())
    }

    @Test fun cancelWithNothingChangedClosesTheDialog() {
        northwind()
        show(inPane = true)
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, facts)).performClick()
        compose.onNodeWithText(str(R.string.edit_discard)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.detail_cancel)).performClick()
        compose.awaitUntil { sessions.session.value == null }
        assertTrue(!dialogOpen())
    }

    @Test fun discardThrowsAwayWhatWasTyped() {
        northwind()
        show(inPane = true)
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, facts)).performClick()
        field(R.string.field_roaster).performTextReplacement("Larkmoor")
        compose.onNodeWithText(str(R.string.edit_discard)).performClick()
        compose.awaitUntil { sessions.session.value == null }
        assertEquals("Northwind", row()!!.roaster)
    }

    @Test fun anIncompleteNewRatingCannotBeSavedAndSaysWhy() {
        northwind()
        show(inPane = true)
        compose.onNodeWithText(str(R.string.detail_rate_cta)).performClick()
        rateRow(R.string.sort_aroma, 4)
        save().assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.edit_rating_incomplete)).assertIsDisplayed()
        rateRow(R.string.sort_flavor, 4)
        rateRow(R.string.sort_finish, 5)
        compose.onNodeWithText(str(R.string.edit_rating_incomplete)).assertDoesNotExist()
        save().assertIsEnabled().performClick()
        compose.awaitUntil { row()?.overall != null }
        assertEquals(listOf(4, 4, 5), listOf(row()!!.aroma, row()!!.flavor, row()!!.finish))
    }

    @Test fun theDescriptionsDialogIsTitledForItsCard() {
        northwind()
        show(inPane = true)
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, str(R.string.detail_descriptions))).performClick()
        // Card header plus dialog title.
        assertEquals(2, compose.onAllNodesWithText(str(R.string.detail_descriptions)).fetchSemanticsNodes().size)
        field(R.string.detail_bag_description).performTextReplacement("Grown on volcanic soil.")
        save().performClick()
        compose.awaitUntil { row()?.bagDescription == "Grown on volcanic soil." }
    }

    @Test fun backWithUnsavedChangesAsksBeforeDiscarding() {
        northwind()
        show(inPane = true)
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, facts)).performClick()
        field(R.string.field_roaster).performTextReplacement("Larkmoor")
        backInDialog()
        compose.onNodeWithText(str(R.string.edit_discard_title)).assertExists()
        compose.onNodeWithText(str(R.string.edit_discard_body)).assertExists()
        compose.onNodeWithText(str(R.string.edit_keep_editing)).performClick()
        compose.onNodeWithText(str(R.string.edit_discard_title)).assertDoesNotExist()
        assertNotNull(sessions.session.value)

        backInDialog()
        compose.onNodeWithText(str(R.string.edit_discard_title)).assertExists()
        // The confirm dialog's Discard (beside Keep editing), not the editor's own Discard (beside Save).
        compose.onNode(hasText(str(R.string.edit_discard)) and hasAnySibling(hasText(str(R.string.edit_keep_editing)))).performClick()
        compose.onNodeWithText(str(R.string.edit_discard_title)).assertDoesNotExist()
        compose.awaitUntil { sessions.session.value == null }
        assertEquals("Northwind", row()!!.roaster)
    }

    @Test fun backWithNothingChangedJustClosesTheDialog() {
        northwind()
        show(inPane = true)
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, facts)).performClick()
        backInDialog()
        compose.awaitUntil { sessions.session.value == null }
        compose.onNodeWithText(str(R.string.edit_discard_title)).assertDoesNotExist()
    }
}
