package dev.roastbook.app.ui.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.performClick
import dev.roastbook.app.R
import dev.roastbook.app.ui.rating.RatingHandoff
import dev.roastbook.app.ui.rating.RatingDraft
import dev.roastbook.app.ui.theme.RoastbookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rating card on a phone: rating a coffee in place, and the hand-over from the rating sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w400dp-h2600dp")
class DetailScreenRatingTest : DetailScreenTestBase() {
    private val ratings get() = str(R.string.detail_ratings)
    private fun done() = compose.onNodeWithContentDescription(str(R.string.detail_done_section, ratings)).performClick()
    private fun editing() = compose.onNodeWithContentDescription(str(R.string.detail_done_section, ratings)).exists()
    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    private fun reactions(level: Int) = f.context.resources.getStringArray(
        when (level) { 1 -> R.array.rating_react_1; 2 -> R.array.rating_react_2; 3 -> R.array.rating_react_3; 4 -> R.array.rating_react_4; else -> R.array.rating_react_5 },
    ).toList()

    @Test fun rateItOpensTheEditorAndDoneSavesTheRatingWithAReaction() {
        insert { copy(roaster = "Northwind") }
        show()
        compose.onNodeWithText(str(R.string.detail_unrated)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.detail_rate_cta)).performClick()
        rateAll(aroma = 5, flavor = 4, finish = 3)
        rateRow(R.string.sort_body, 2)
        done()
        // The page shows the score once its own copy of the row has caught up with the write.
        compose.awaitUntil { row()?.overall != null && vm.coffee.value?.overall != null }
        val r = row()!!
        assertEquals(4f, r.overall)
        assertEquals(2, r.body)
        assertNull(r.acidity)
        assertFalse(editing())
        compose.onNodeWithContentDescription(str(R.string.detail_overall_description, "4.0")).assertIsDisplayed()
        // One of the reactions for a four lands under the summary.
        assertTrue(reactions(4).any { runCatching { compose.onNodeWithText(it).assertIsDisplayed() }.isSuccess })
    }

    @Test fun anIncompleteRatingStaysOpenAndSaysWhatIsMissing() {
        insert { copy(roaster = "Northwind") }
        show()
        compose.onNodeWithText(str(R.string.detail_rate_cta)).performClick()
        rateRow(R.string.sort_aroma, 4)
        compose.onNodeWithText(str(R.string.edit_rating_incomplete)).assertIsDisplayed()
        // The check, a tap on the page and another card's pencil all leave it open, unsaved.
        done()
        assertTrue(editing())
        compose.onNode(hasText("Northwind") and isHeading()).performTouchInput { click() }
        assertTrue(editing())
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, str(R.string.detail_facts))).performClick()
        assertTrue(editing())
        compose.onNode(hasSetTextAction() and hasText(str(R.string.field_roaster))).assertDoesNotExist()
        awaitWritesDone()
        assertNull(row()!!.aroma)
        compose.onNode(hasContentDescription(str(R.string.sort_aroma)) and hasStateDescription(str(R.string.rate_value, 4))).assertExists()
        // Completing it clears the hint, and the check saves it.
        rateRow(R.string.sort_flavor, 3)
        rateRow(R.string.sort_finish, 5)
        compose.onNodeWithText(str(R.string.edit_rating_incomplete)).assertDoesNotExist()
        done()
        compose.awaitUntil { row()?.overall != null }
        assertEquals(listOf(4, 3, 5), listOf(row()!!.aroma, row()!!.flavor, row()!!.finish))
        assertFalse(editing())
    }

    // At phone height: the hint must be on screen where the person is rating, not below the fold.
    @org.robolectric.annotation.Config(qualifiers = "w400dp-h800dp")
    @Test fun atPhoneHeightTheHintIsInViewWhenTheCheckDoesNotClose() {
        insert { copy(roaster = "Northwind") }
        show()
        compose.onNodeWithText(str(R.string.detail_rate_cta)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(str(R.string.detail_done_section, ratings)).performScrollTo()
        rateRow(R.string.sort_aroma, 4)
        done()
        assertTrue(editing())
        compose.onNodeWithText(str(R.string.edit_rating_incomplete)).assertIsDisplayed()
    }

    // A short window: tapping the page from its top, with the scores below the fold, scrolls to the hint.
    @org.robolectric.annotation.Config(qualifiers = "w400dp-h640dp")
    @Test fun aRefusedCloseScrollsTheHintIntoView() {
        insert { copy(roaster = "Northwind") }
        show()
        compose.onNodeWithText(str(R.string.detail_rate_cta)).performScrollTo().performClick()
        rateRow(R.string.sort_aroma, 4)
        val title = compose.onNode(hasText("Northwind") and isHeading())
        title.performScrollTo()
        val hint = compose.onNodeWithText(str(R.string.edit_rating_incomplete))
        hint.assertIsNotDisplayed()
        title.performTouchInput { click() }
        assertTrue(editing())
        hint.assertIsDisplayed()
    }

    @Test fun anUntouchedRatingClosesWithoutAsking() {
        insert { copy(roaster = "Northwind") }
        show()
        compose.onNodeWithText(str(R.string.detail_rate_cta)).performClick()
        compose.onNodeWithText(str(R.string.edit_rating_incomplete)).assertDoesNotExist()
        done()
        assertFalse(editing())
        compose.onNodeWithText(str(R.string.detail_unrated)).assertIsDisplayed()
    }

    @Test fun backWithAnIncompleteRatingAsksAndDiscardClosesTheCardOnly() {
        insert { copy(roaster = "Northwind") }
        show()
        compose.onNodeWithText(str(R.string.detail_rate_cta)).performClick()
        rateRow(R.string.sort_aroma, 4)
        systemBack()
        compose.onNodeWithText(str(R.string.edit_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.edit_discard_body)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.edit_keep_editing)).performClick()
        compose.onNodeWithText(str(R.string.edit_discard_title)).assertDoesNotExist()
        assertTrue(editing())
        compose.onNodeWithText(str(R.string.edit_rating_incomplete)).assertIsDisplayed()

        systemBack()
        compose.onNodeWithText(str(R.string.edit_discard)).performClick()
        assertFalse(editing())
        assertNull(sessions.session.value)
        assertEquals(0, backs)
        awaitWritesDone()
        assertNull(row()!!.aroma)
    }

    @Test fun theBackArrowWithAnIncompleteRatingAsksBeforeLeaving() {
        insert { copy(roaster = "Northwind", aroma = 3, flavor = 4, finish = 4, overall = 11f / 3, ratedAt = 5_000) }
        show()
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, ratings)).performClick()
        // Clearing a score (tapping its dot again) makes a rated coffee's rating incomplete.
        rateRow(R.string.sort_flavor, 0)
        compose.onNodeWithText(str(R.string.edit_rating_incomplete)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.detail_back)).performClick()
        compose.onNodeWithText(str(R.string.edit_discard_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.edit_keep_editing)).performClick()
        assertEquals(0, backs)
        assertTrue(editing())

        compose.onNodeWithContentDescription(str(R.string.detail_back)).performClick()
        compose.onNodeWithText(str(R.string.edit_discard)).performClick()
        assertEquals(1, backs)
        assertNull(sessions.session.value)
        awaitWritesDone()
        assertEquals(4, row()!!.flavor)
    }

    @Test fun theBackArrowWithACompleteRatingSavesWithoutAsking() {
        insert { copy(roaster = "Northwind") }
        show()
        compose.onNodeWithText(str(R.string.detail_rate_cta)).performClick()
        rateAll(aroma = 4, flavor = 4, finish = 4)
        compose.onNodeWithContentDescription(str(R.string.detail_back)).performClick()
        compose.onNodeWithText(str(R.string.edit_discard_title)).assertDoesNotExist()
        assertEquals(1, backs)
        compose.awaitUntil { row()?.overall != null }
    }

    @Test fun aRatedCoffeeShowsItsScoreAndCanBeRerated() {
        insert { copy(roaster = "Northwind", aroma = 3, flavor = 4, finish = 4, overall = 11f / 3, ratedAt = 5_000) }
        show()
        compose.onNodeWithContentDescription(str(R.string.detail_overall_description, "3.7")).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, ratings)).performClick()
        // While editing, the header follows the draft.
        rateRow(R.string.sort_aroma, 1)
        compose.onNodeWithContentDescription(str(R.string.detail_overall_description, "3.0")).assertIsDisplayed()
        done()
        compose.awaitUntil { row()?.aroma == 1 }
        assertEquals(3f, row()!!.overall)
        assertTrue(reactions(3).any { runCatching { compose.onNodeWithText(it).assertIsDisplayed() }.isSuccess })
    }

    @Test fun closingAnUnchangedRatingWritesNothing() {
        insert { copy(roaster = "Northwind", aroma = 3, flavor = 4, finish = 4, overall = 11f / 3, ratedAt = 5_000, updatedAt = 5_000) }
        show()
        compose.onNodeWithContentDescription(str(R.string.detail_edit_section, ratings)).performClick()
        done()
        awaitWritesDone()
        assertEquals(5_000L, row()!!.ratedAt)
        assertEquals(5_000L, row()!!.updatedAt)
        reactions(4).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun addDetailsFromTheSheetOpensTheEditorWithWhatWasAlreadyPicked() {
        insert { copy(roaster = "Northwind") }
        f.pending.ratingDraft.value = RatingHandoff("c1", RatingDraft(aroma = 4, flavor = 5))
        show(editRating = true)
        compose.awaitUntil { editing() }
        assertNull(f.pending.ratingDraft.value)
        compose.onNode(hasContentDescription(str(R.string.sort_aroma)) and hasStateDescription(str(R.string.rate_value, 4))).assertExists()
        compose.onNode(hasContentDescription(str(R.string.sort_flavor)) and hasStateDescription(str(R.string.rate_value, 5))).assertExists()
        rateRow(R.string.sort_finish, 3)
        done()
        compose.awaitUntil { row()?.overall != null }
        assertEquals(listOf(4, 5, 3), listOf(row()!!.aroma, row()!!.flavor, row()!!.finish))
    }

    @Test fun aClosedAddDetailsEditorStaysClosedWhenThePageIsRecreated() {
        insert { copy(roaster = "Northwind") }
        vm = f.vm("c1")
        val restore = StateRestorationTester(compose)
        restore.setContent {
            sessions = rememberEditSessions()
            RoastbookTheme { DetailScreen(id = "c1", editRating = true, onBack = {}, vm = vm) }
        }
        compose.awaitUntil { editing() }
        done()
        compose.awaitUntil { !editing() }

        // A rotation (or Android restoring the app) composes the page again with the same request.
        restore.emulateSavedInstanceStateRestore()
        compose.awaitUntil { compose.onNodeWithText(str(R.string.detail_unrated)).exists() }
        assertFalse(editing())
    }

    @Test fun addDetailsWithNothingHandedOverOpensAnEmptyEditor() {
        insert { copy(roaster = "Northwind") }
        show(editRating = true)
        compose.awaitUntil { editing() }
        compose.onNode(hasContentDescription(str(R.string.sort_aroma)) and hasStateDescription(str(R.string.rate_state_unset))).assertExists()
    }
}
