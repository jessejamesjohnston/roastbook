package dev.roastbook.app.ui.rating

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.ui.theme.RoastbookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The live rating grid: dots and the adjustable rows, the overall score, buy again and notes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RatingEditorTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var draft by mutableStateOf(RatingDraft())

    private fun show(showNotes: Boolean = true, showProfile: Boolean = true, embedded: Boolean = false) {
        compose.setContent {
            RoastbookTheme {
                RatingEditor(draft, Color(0xFF8D5524), onChange = { draft = it }, showNotes = showNotes, showProfile = showProfile, embedded = embedded)
            }
        }
    }

    private fun text(id: Int) = context.getString(id)
    private fun row(label: Int, substring: Boolean = false): SemanticsNodeInteraction =
        compose.onNodeWithContentDescription(text(label), substring = substring)
    private fun SemanticsNodeInteraction.setTo(value: Float) = performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    private fun state(s: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, s)
    private fun outOfFive(v: Int) = context.getString(R.string.rate_value, v)

    // Real text measurement: Robolectric's default graphics mode does not measure text.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun atTwiceTheTextSizeInANarrowCardNoRowLabelBreaksOntoASecondLine() {
        val oneLine = mutableMapOf<Int, Int>()
        val labels = listOf(R.string.sort_aroma, R.string.sort_flavor, R.string.sort_finish, R.string.sort_acidity, R.string.sort_body)
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                RoastbookTheme {
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.bodyMedium
                    labels.forEach { oneLine[it] = measurer.measure(text(it), style).size.height }
                    // A 360dp phone: the card's content is about 300dp wide.
                    Box(Modifier.width(300.dp)) {
                        RatingEditor(RatingDraft(aroma = 3, acidity = 2, body = 4), Color(0xFF8D5524), onChange = {}, showNotes = false, embedded = true)
                    }
                }
            }
        }
        for (label in labels) {
            val rowNode = compose.onNode(
                hasContentDescription(text(label), substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress),
                useUnmergedTree = true,
            ).fetchSemanticsNode()
            // The row's first child is its label; one line tall means it was not broken mid-word.
            assertEquals(text(label), oneLine.getValue(label).toFloat(), rowNode.children.first().boundsInRoot.height, 1f)
        }
    }

    @Test fun theOverallScoreAppearsOnceAromaFlavorAndFinishAreIn() {
        show()
        compose.onNodeWithText("–").assertExists()
        row(R.string.sort_aroma).setTo(4f)
        row(R.string.sort_flavor).setTo(5f)
        compose.onNodeWithText("–").assertExists()
        row(R.string.sort_finish).setTo(4f)
        assertEquals(RatingDraft(aroma = 4, flavor = 5, finish = 4), draft)
        compose.onNodeWithText("%.1f".format(13f / 3)).assertExists()
        row(R.string.sort_aroma).assert(state(outOfFive(4)))
    }

    @Test fun tappingADotSetsTheRowAndTappingItAgainClearsIt() {
        show()
        val aroma = row(R.string.sort_aroma)
        aroma.assert(state(text(R.string.rate_state_unset)))
        // The fifth dot is the last 28dp of the row.
        aroma.performTouchInput { click(Offset(width - 14.dp.toPx(), centerY)) }
        assertEquals(5, draft.aroma)
        aroma.assert(state(outOfFive(5)))
        aroma.performTouchInput { click(Offset(width - 14.dp.toPx(), centerY)) }
        assertNull(draft.aroma)
    }

    @Test fun swipingARowDownToZeroClearsItAndAboveFiveStopsAtFive() {
        show()
        val flavor = row(R.string.sort_flavor)
        flavor.setTo(9f)
        assertEquals(5, draft.flavor)
        flavor.setTo(0f)
        assertNull(draft.flavor)
    }

    @Test fun theProfileRowsReadTheirEndsAndSetAcidityAndBody() {
        show()
        compose.onNodeWithText(text(R.string.rate_profile)).assertExists()
        val acidity = context.getString(R.string.rate_range, text(R.string.profile_acidity_low), text(R.string.profile_acidity_high))
        compose.onNodeWithContentDescription("${text(R.string.sort_acidity)}, $acidity").setTo(2f)
        row(R.string.sort_body, substring = true).setTo(3f)
        assertEquals(2, draft.acidity)
        assertEquals(3, draft.body)
        // Profile rows describe the coffee; they don't move the score.
        assertNull(draft.overall)
    }

    @Test fun buyAgainToggles() {
        show()
        val toggle = compose.onNodeWithText(text(R.string.rate_buy_again))
        toggle.assertIsOff()
        toggle.performClick()
        assertTrue(draft.buyAgain)
        toggle.assertIsOn()
    }

    @Test fun notesAreTypedIntoTheDraft() {
        show()
        compose.onNodeWithText(text(R.string.detail_notes_header)).assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("Plum and cocoa")
        assertEquals("Plum and cocoa", draft.notes)
    }

    @Test fun withoutNotesOrProfileOnlyTheScoreRowsAndBuyAgainShow() {
        show(showNotes = false, showProfile = false)
        row(R.string.sort_finish).assertExists()
        compose.onNodeWithText(text(R.string.rate_buy_again)).assertExists()
        compose.onNodeWithText(text(R.string.rate_profile)).assertDoesNotExist()
        row(R.string.sort_acidity, substring = true).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun embeddedInTheDetailCardTheScoreAndOverallLinesAreLeftToTheCard() {
        show(embedded = true)
        compose.onNodeWithText(text(R.string.rate_score)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.rate_overall)).assertDoesNotExist()
        row(R.string.sort_aroma).assertExists()
    }

    @Test fun aDraftIsCompleteWithTheThreeScoreDimensionsAndScoresOnlyThose() {
        assertFalse(RatingDraft(aroma = 5, flavor = 5, acidity = 5, body = 5).complete)
        val d = RatingDraft(aroma = 2, flavor = 3, finish = 4, acidity = 1, body = 5)
        assertTrue(d.complete)
        assertEquals(3f, d.overall)
        assertEquals(listOf(2, 3, 1, 5, 4), d.values)
    }

    @Test fun aDraftStartsFromTheCoffeesSavedRating() {
        val c = CoffeeEntity("c1", 1, 1, 1, CoffeeState.RATED, aroma = 4, flavor = 3, finish = 5, acidity = 2, body = 1, buyAgain = true, notes = "Jammy")
        assertEquals(RatingDraft(4, 3, 2, 1, 5, true, "Jammy"), RatingDraft.from(c))
        assertEquals("", RatingDraft.from(c.copy(notes = null)).notes)
    }

    @Test fun eachReactionFitsTheRoundedScoreAndNeverRepeatsBackToBack() {
        val buckets = mapOf(
            1f to R.array.rating_react_1, 2.4f to R.array.rating_react_2, 3f to R.array.rating_react_3,
            3.6f to R.array.rating_react_4, 5f to R.array.rating_react_5,
        )
        for ((overall, array) in buckets) {
            val options = context.resources.getStringArray(array).toList()
            var last: String? = null
            repeat(6) {
                val r = Reactions.forOverall(context, overall)
                assertTrue("$r for $overall", r in options)
                assertNotEquals(last, r)
                last = r
            }
        }
    }
}
