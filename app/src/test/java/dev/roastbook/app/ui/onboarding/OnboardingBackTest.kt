package dev.roastbook.app.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import dev.roastbook.app.R
import dev.roastbook.app.testutil.TestGraph
import dev.roastbook.app.ui.theme.RoastbookTheme
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test fun escapeStepsBackOnePage() {
        show()
        toPageTwo()
        compose.onNodeWithText(text(R.string.onboarding_next)).requestFocus()
        compose.onNodeWithText(text(R.string.onboarding_next)).performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithText(text(R.string.onboarding_1_title)).assertIsDisplayed()
    }
}
