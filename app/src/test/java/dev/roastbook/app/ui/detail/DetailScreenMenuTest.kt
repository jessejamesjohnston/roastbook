package dev.roastbook.app.ui.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The More menu (Read again, Delete), the reading banner, and the "what happened" note on the page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h2600dp")
class DetailScreenMenuTest : DetailScreenTestBase() {
    private fun more() = compose.onNodeWithContentDescription(str(R.string.detail_more)).performClick()

    /** The menu item, not the note's button of the same name (which carries a longer description). */
    private fun menuReadAgain() = compose.onNode(hasText(str(R.string.detail_read_again)) and !hasContentDescription(str(R.string.what_reread_description)))

    @Test fun readAgainFromTheMenuQueuesAReadAndShowsTheBanner() {
        insert { copy(roaster = "Northwind", coffeeName = "Cloud Terrace") }
        show()
        more()
        menuReadAgain().performClick()
        compose.awaitUntil { row()?.state == CoffeeState.EXTRACTING }
        compose.awaitUntil { vm.coffee.value?.state == CoffeeState.EXTRACTING }
        compose.onNodeWithText(str(R.string.row_extracting_subtitle)).assertIsDisplayed()
        // The title and the banner both say the bag is being read.
        assertEquals(2, compose.onAllNodesWithText(str(R.string.row_extracting)).fetchSemanticsNodes().size)
    }

    @Test fun whileReadingTheCardsCannotBeEditedAndReadAgainIsOff() {
        insert(build = { copy(roaster = "Northwind", state = CoffeeState.EXTRACTING) })
        show()
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, str(R.string.detail_facts))).assertDoesNotExist()
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, str(R.string.detail_descriptions))).assertDoesNotExist()
        more()
        menuReadAgain().assertIsNotEnabled()
    }

    @Test fun aFirstModelDownloadShowsThePreparingBanner() {
        insert(build = { copy(roaster = "Northwind", state = CoffeeState.EXTRACTING, extractionTrail = "nano=preparing") })
        show()
        compose.onNodeWithText(str(R.string.row_preparing_subtitle)).assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText(str(R.string.row_preparing)).fetchSemanticsNodes().size)
    }

    @Test fun deletingAsksFirstThenRemovesTheCoffeeAndLeaves() {
        insert { copy(roaster = "Northwind") }
        show()
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, str(R.string.detail_facts))).performClick()
        compose.onNode(hasSetTextAction() and hasText(str(R.string.field_roaster))).performTextReplacement("Larkmoor")
        more()
        compose.onNodeWithText(str(R.string.detail_delete)).performClick()
        compose.onNodeWithText(str(R.string.detail_delete_confirm)).assertIsDisplayed()
        // Bag photos aren't saved to the gallery (the default), so the dialog doesn't mention it.
        compose.onNodeWithText(str(R.string.detail_delete_body)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.detail_delete_body_gallery)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.detail_delete_yes)).performClick()
        compose.awaitUntil { backs == 1 }
        assertNull(row())
        // The open card went with the coffee rather than being saved into a deleted row.
        assertNull(sessions.session.value)
    }

    @Test fun withPhotosSavedToTheGalleryTheDeleteSaysTheyStayThere() {
        insert { copy(roaster = "Northwind") }
        runBlocking { f.graph.userPrefs.setSaveSquares(true) }
        show()
        compose.awaitUntil { vm.savesToGallery.value }
        more()
        compose.onNodeWithText(str(R.string.detail_delete)).performClick()
        compose.onNodeWithText(str(R.string.detail_delete_body_gallery)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.detail_delete_body)).assertDoesNotExist()
    }

    @Test fun cancelingTheDeleteKeepsTheCoffee() {
        insert { copy(roaster = "Northwind") }
        show()
        more()
        compose.onNodeWithText(str(R.string.detail_delete)).performClick()
        compose.onNodeWithText(str(R.string.detail_cancel)).performClick()
        compose.onNodeWithText(str(R.string.detail_delete_confirm)).assertDoesNotExist()
        assertNotNull(row())
        assertEquals(0, backs)
    }

    private fun poorRead() = insert {
        copy(roaster = "Northwind", extractionSource = ExtractionSource.OCR, extractionTrail = "nano=failed;local=absent;cloud=off;guess=1", ocrText = "NORTHWIND")
    }

    @Test fun closingTheNoteHidesItForThisCoffee() {
        poorRead()
        show()
        compose.onNodeWithText(str(R.string.detail_poor_read)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.what_hint_key)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.detail_dismiss_note)).performClick()
        compose.awaitUntil { !compose.onNodeWithText(str(R.string.detail_poor_read)).exists() }
        assertTrue("c1" in f.readNotes.dismissed.value)
    }

    @Test fun readAgainOnTheNoteQueuesARead() {
        poorRead()
        show()
        compose.onNodeWithText(str(R.string.what_reread)).performClick()
        compose.awaitUntil { row()?.state == CoffeeState.EXTRACTING }
    }

    @Test fun withoutAKeyThereIsNoLookUpButton() {
        insert { copy(roaster = "Northwind") }
        show()
        compose.onNodeWithText(str(R.string.detail_find_roaster)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.detail_look_up_roaster)).assertDoesNotExist()
    }
}
