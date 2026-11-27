package dev.roastbook.app.ui.onboarding

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import dev.roastbook.app.EscapeKey
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import dev.roastbook.app.R
import dev.roastbook.app.testutil.TestGraph
import dev.roastbook.app.ui.theme.RoastbookTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Past the first page, the intro steps back one page by its Back button, the system Back, or Escape. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OnboardingBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val graph = TestGraph()
    @After fun tearDown() = graph.close()
    private fun text(id: Int) = compose.activity.getString(id)

    private var done = 0
    private fun show() {
        val vm = OnboardingViewModel(graph.userPrefs)
        compose.setContent { RoastbookTheme { OnboardingScreen(onDone = { done++ }, vm = vm, readerNotice = { Box {} }) } }
    }

    private fun toPageTwo() {
        compose.onNodeWithText(text(R.string.onboarding_1_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_back)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        compose.onNodeWithText(text(R.string.onboarding_2_title)).assertIsDisplayed()
    }

    @Test fun theBackButtonStepsBackOnePage() {
        show()
        toPageTwo()
        compose.onNodeWithText(text(R.string.onboarding_back)).performClick()
        compose.onNodeWithText(text(R.string.onboarding_1_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_back)).assertDoesNotExist()
    }

    @Test fun theSystemBackStepsBackOnePageAndLeavesTheIntroAlone() {
        show()
        toPageTwo()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(text(R.string.onboarding_1_title)).assertIsDisplayed()
        assertEquals(0, done)
    }

    /** A real Esc, down then up, through the activity's own Esc-as-Back mapping, as the app runs it. */
    private fun escape() {
        compose.onNodeWithText(text(R.string.onboarding_back)).requestFocus()
        val activity = compose.activity
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            compose.runOnUiThread {
                EscapeKey.dispatch(activity, KeyEvent(action, KeyEvent.KEYCODE_ESCAPE)) { activity.window.superDispatchKeyEvent(it) }
            }
            compose.waitForIdle()
        }
    }

    @Test fun escapeStepsBackOnePage() {
        show()
        toPageTwo()
        compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        compose.onNodeWithText(text(R.string.onboarding_3_title)).assertIsDisplayed()
        escape()
        compose.onNodeWithText(text(R.string.onboarding_2_title)).assertIsDisplayed()
    }

    @Test fun escapeOnPageTwoLandsOnPageOneAndStaysInTheIntro() {
        show()
        toPageTwo()
        escape()
        compose.onNodeWithText(text(R.string.onboarding_1_title)).assertIsDisplayed()
        assertFalse("the intro is still open", compose.activity.isFinishing)
        assertEquals(0, done)
    }
}
