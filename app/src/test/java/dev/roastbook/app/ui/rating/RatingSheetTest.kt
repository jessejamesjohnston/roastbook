package dev.roastbook.app.ui.rating

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.ui.theme.RoastbookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The quick-rate bottom sheet and the read-only summary on the Detail card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RatingSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val accent = Color(0xFF3E7C59)
    private val coffee = CoffeeEntity("c1", 1, 1, 1, CoffeeState.LOGGED, roaster = "Larkmoor", coffeeName = "Harbor Fog")

    private val saved = mutableListOf<RatingDraft>()
    private val more = mutableListOf<RatingDraft>()
    private var dismissed = 0

    private fun show(c: CoffeeEntity = coffee) {
        compose.setContent {
            RoastbookTheme { RatingSheet(c, accent, onSave = { saved += it }, onMore = { more += it }, onDismiss = { dismissed++ }) }
        }
    }

    private fun text(id: Int) = context.getString(id)
    private fun row(label: Int): SemanticsNodeInteraction = compose.onNodeWithContentDescription(text(label))
    private fun SemanticsNodeInteraction.setTo(value: Float) = performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    private fun state(s: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, s)

    private fun rateScores(aroma: Int, flavor: Int, finish: Int) {
        row(R.string.sort_aroma).setTo(aroma.toFloat())
        row(R.string.sort_flavor).setTo(flavor.toFloat())
        row(R.string.sort_finish).setTo(finish.toFloat())
    }

    @Test fun saveWaitsForTheScoresThenShowsAReactionAndCloses() {
        show()
        compose.onNodeWithText("Harbor Fog").assertExists()
        compose.onNodeWithText(text(R.string.rate_title)).assertExists()
        compose.onNodeWithText(text(R.string.rate_save)).assertIsNotEnabled()
        rateScores(5, 5, 4)
        compose.onNodeWithText(text(R.string.rate_save)).assertIsEnabled()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text(R.string.rate_save)).performClick()
        compose.mainClock.advanceTimeBy(200)
        assertEquals(listOf(RatingDraft(aroma = 5, flavor = 5, finish = 4)), saved)
        // The reaction replaces the controls for a beat, then the sheet closes itself.
        val reactions = context.resources.getStringArray(R.array.rating_react_5).toList()
        compose.onNode(SemanticsMatcher("one of the 5-point reactions") { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { it.text in reactions } == true
        }).assertExists()
        compose.onNodeWithText(text(R.string.rate_save)).assertDoesNotExist()
        assertEquals(0, dismissed)
        compose.mainClock.advanceTimeBy(1_300)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    /** A short window (a desktop window that is not maximized): Save and Cancel are on screen at once. */
    @Config(qualifiers = "w1100dp-h480dp")
    @Test fun inAShortWindowTheActionsAreVisibleWithoutScrolling() {
        show()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.rate_save)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.detail_cancel)).assertIsDisplayed()
    }

    /** A disabled Save says why, as the large-screen dialog does, and the reason goes once Save is enabled. */
    @Config(qualifiers = "w400dp-h800dp")
    @Test fun aDisabledSaveSaysWhyUntilTheScoresAreIn() {
        show()
        compose.onNodeWithText(text(R.string.edit_rating_incomplete)).assertIsDisplayed()
        rateScores(4, 4, 5)
        compose.onNodeWithText(text(R.string.edit_rating_incomplete)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.edit_rating_incomplete_short)).assertDoesNotExist()
    }

    /** The reason never takes more than one line, and Save stays on screen, on a 360dp phone at 200% text. */
    @Config(qualifiers = "w360dp-h640dp", fontScale = 2.0f)
    @Test fun onANarrowPhoneWithLargeTextTheReasonTakesOneLineAndSaveStaysOnScreen() {
        show()
        compose.onNodeWithText(text(R.string.rate_save)).assertIsDisplayed()
        assertEquals("one line", 1, hintLines())
    }

    /**
     * Where the full sentence would wrap, the same is said in fewer words. Robolectric's text layout
     * doesn't wrap like a device's, so the choice is tested on its own here and the 360dp case on an
     * emulator.
     */
    @Test fun whereTheFullReasonWouldWrapTheShortOneIsChosen() {
        val full = text(R.string.edit_rating_incomplete)
        val short = text(R.string.edit_rating_incomplete_short)
        assertEquals(full, saveHint(full, short) { true })
        assertEquals(short, saveHint(full, short) { it == short })
        assert(short.length < full.length)
    }

    /** Lines in the shown reason, whichever form it takes. */
    private fun hintLines(): Int {
        val node = compose.onNode(
            hasText(text(R.string.edit_rating_incomplete)) or hasText(text(R.string.edit_rating_incomplete_short)),
        ).fetchSemanticsNode()
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!(layout)
        return layout.single().lineCount
    }

    @Test fun addDetailsHandsOverWhatIsEnteredSoFar() {
        show()
        row(R.string.sort_aroma).setTo(3f)
        compose.onNodeWithText(text(R.string.rate_more)).performClick()
        assertEquals(listOf(RatingDraft(aroma = 3)), more)
        assertTrue(saved.isEmpty())
    }

    @Test fun cancelClosesWithoutSaving() {
        show()
        rateScores(2, 2, 2)
        compose.onNodeWithText(text(R.string.detail_cancel)).performClick()
        assertEquals(1, dismissed)
        assertTrue(saved.isEmpty())
    }

    @Test fun theSheetStartsFromTheCoffeesRatingAndIsTitledByRoasterWhenUnnamed() {
        show(coffee.copy(coffeeName = null, aroma = 3, flavor = 4, finish = 2, notes = "Kept"))
        compose.onNodeWithText("Larkmoor").assertExists()
        row(R.string.sort_flavor).assert(state(context.getString(R.string.rate_value, 4)))
        compose.onNodeWithText(text(R.string.rate_save)).assertIsEnabled().performClick()
        assertEquals(listOf(RatingDraft(aroma = 3, flavor = 4, finish = 2, notes = "Kept")), saved)
    }

    @Test fun aCoffeeWithNoNameOrRoasterIsUntitled() {
        show(coffee.copy(coffeeName = null, roaster = null))
        compose.onNodeWithText(text(R.string.detail_untitled)).assertExists()
    }

    @Test fun aHalfEnteredRatingSurvivesARotation() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            RoastbookTheme { RatingSheet(coffee, accent, onSave = { saved += it }, onMore = { more += it }, onDismiss = { dismissed++ }) }
        }
        row(R.string.sort_aroma).setTo(4f)
        row(R.string.sort_finish).setTo(2f)
        restore.emulateSavedInstanceStateRestore()
        row(R.string.sort_aroma).assert(state(context.getString(R.string.rate_value, 4)))
        row(R.string.sort_flavor).assert(state(text(R.string.rate_state_unset)))
        compose.onNodeWithText(text(R.string.rate_more)).performClick()
        assertEquals(listOf(RatingDraft(aroma = 4, finish = 2)), more)
    }

    @Test fun aHalfEnteredRatingComesBackWhenTheSheetReopensAfterItsCoffeeReloads() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            // Not saved: after the process comes back, History reloads the coffee before the sheet shows.
            var loaded by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { loaded = true }
            RoastbookTheme { if (loaded) RatingSheet(coffee, accent, onSave = { saved += it }, onMore = { more += it }, onDismiss = { dismissed++ }) }
        }
        row(R.string.sort_aroma).setTo(4f)
        row(R.string.sort_finish).setTo(2f)
        restore.emulateSavedInstanceStateRestore()
        row(R.string.sort_aroma).assert(state(context.getString(R.string.rate_value, 4)))
        row(R.string.sort_finish).assert(state(context.getString(R.string.rate_value, 2)))
        row(R.string.sort_flavor).assert(state(text(R.string.rate_state_unset)))
    }

    @Test fun theSummaryShowsScoresProfileBuyAgainAndNotes() {
        val c = coffee.copy(aroma = 4, flavor = 5, finish = 3, acidity = 2, body = null, buyAgain = true, notes = "Stone fruit")
        compose.setContent { RoastbookTheme { RatingSummary(c, accent) } }
        compose.onNodeWithText(text(R.string.sort_flavor), useUnmergedTree = true).assertExists()
        compose.onNode(hasText(text(R.string.sort_flavor)).and(state(context.getString(R.string.rate_value, 5)))).assertExists()
        compose.onNodeWithText(text(R.string.rate_profile)).assertExists()
        compose.onNode(hasText(text(R.string.sort_acidity)).and(state(context.getString(R.string.rate_value, 2)))).assertExists()
        compose.onNodeWithText(text(R.string.sort_body), substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.rate_buy_again)).assertExists()
        compose.onNodeWithText(text(R.string.detail_notes_header)).assertExists()
        compose.onNodeWithText("Stone fruit").assertExists()
    }

    @Test fun theSummaryOfABodyOnlyProfileWithoutNotesOrBuyAgain() {
        val c = coffee.copy(aroma = null, flavor = 2, finish = 2, body = 4, notes = " ")
        compose.setContent { RoastbookTheme { RatingSummary(c, accent) } }
        compose.onNode(hasText(text(R.string.sort_aroma)).and(state(text(R.string.rate_state_unset)))).assertExists()
        compose.onNode(hasText(text(R.string.sort_body)).and(state(context.getString(R.string.rate_value, 4)))).assertExists()
        compose.onNodeWithText(text(R.string.sort_acidity), substring = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.rate_buy_again)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.detail_notes_header)).assertDoesNotExist()
    }

    @Test fun aSummaryWithNoProfileHasNoProfileHeader() {
        compose.setContent { RoastbookTheme { RatingSummary(coffee.copy(aroma = 3, flavor = 3, finish = 3), accent) } }
        compose.onNodeWithText(text(R.string.rate_profile)).assertDoesNotExist()
    }
}
