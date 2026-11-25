package dev.roastbook.app.ui.onboarding

import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.roastbook.app.R
import dev.roastbook.app.testutil.TestGraph
import dev.roastbook.app.ui.theme.RoastbookTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The three-page intro: paging forward, Skip and Get started both end it and remember that it ran. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OnboardingScreenTest {
    @get:Rule val compose = createComposeRule()
    private val graph = TestGraph()
    private val context get() = graph.context

    @After fun tearDown() = graph.close()

    private fun show(onDone: () -> Unit) {
        val vm = OnboardingViewModel(graph.userPrefs)
        compose.setContent {
            RoastbookTheme {
                OnboardingScreen(onDone = onDone, vm = vm, readerNotice = { Box(Modifier.testTag(NOTICE)) })
            }
        }
    }

    private fun onboardingDone(): Boolean = runBlocking { graph.userPrefs.settings.first() }.onboardingDone

    /** The write finishes off the main thread and resumes on it; let both run before reading. */
    private fun awaitOnboardingDone(): Boolean {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (onboardingDone()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun text(id: Int) = context.getString(id)

    private fun walkToTheLastPage() {
        compose.onNodeWithText(text(R.string.onboarding_1_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        compose.onNodeWithText(text(R.string.onboarding_2_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        compose.onNodeWithText(text(R.string.onboarding_3_title)).assertIsDisplayed()
    }

    @Test fun nextWalksThroughThePagesAndGetStartedFinishes() {
        var done = 0
        show { done++ }
        walkToTheLastPage()
        assertEquals(0, done)
        compose.onNodeWithText(text(R.string.onboarding_start)).performClick()
        assertEquals(1, done)
        assertTrue(awaitOnboardingDone())
    }

    @Test fun withAnimationsRemovedNextStillReachesEveryPage() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var done = 0
        show { done++ }
        walkToTheLastPage()
        compose.onNodeWithText(text(R.string.onboarding_start)).performClick()
        assertEquals(1, done)
    }

    @Test fun theReaderNoticeSitsOnThePageAboutReadingTheBag() {
        show { }
        compose.onNodeWithTag(NOTICE, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        compose.onNodeWithText(text(R.string.onboarding_2_title)).assertIsDisplayed()
        compose.onNodeWithTag(NOTICE, useUnmergedTree = true).assertExists()
    }

    @Test fun skipEndsTheIntroFromTheFirstPage() {
        var done = 0
        show { done++ }
        compose.onNodeWithText(text(R.string.onboarding_skip)).performClick()
        assertEquals(1, done)
        assertTrue(awaitOnboardingDone())
    }

    @Test fun nothingIsRecordedUntilTheIntroEnds() {
        show { }
        compose.onNodeWithText(text(R.string.onboarding_next)).performClick()
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(onboardingDone())
    }

    private companion object {
        const val NOTICE = "onboarding-test-reader-notice"
    }
}
