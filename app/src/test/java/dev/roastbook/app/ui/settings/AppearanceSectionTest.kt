package dev.roastbook.app.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.roastbook.app.R
import dev.roastbook.app.data.prefs.ThemeMode
import dev.roastbook.app.data.prefs.UserSettings
import dev.roastbook.app.ui.theme.AppLanguage
import dev.roastbook.app.ui.theme.RoastbookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings > Appearance: the theme choice, high contrast, the language picker, and the section label. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppearanceSectionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = context.getString(id)

    private val themes = mutableListOf<ThemeMode>()
    private val contrasts = mutableListOf<Boolean>()

    /** Renders the section the way Settings does: callbacks write the settings, the section redraws. */
    private fun show(initial: UserSettings = UserSettings()) {
        compose.setContent {
            var settings by remember { mutableStateOf(initial) }
            RoastbookTheme {
                Column {
                    SectionLabel(str(R.string.settings_appearance))
                    AppearanceSection(
                        settings,
                        onThemeMode = { themes += it; settings = settings.copy(themeMode = it) },
                        onHighContrast = { contrasts += it; settings = settings.copy(highContrast = it) },
                    )
                }
            }
        }
    }

    /** A choice in the language picker (a radio row), not the Settings row that opens it. */
    private fun option(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    @Test fun theStoredThemeIsTheSelectedSegment() {
        show(UserSettings(themeMode = ThemeMode.DARK))
        compose.onNodeWithText(str(R.string.settings_theme_dark)).assertIsSelected()
        compose.onNodeWithText(str(R.string.settings_theme_system)).assertIsNotSelected()
        compose.onNodeWithText(str(R.string.settings_theme_light)).assertIsNotSelected()
        compose.onNodeWithText(str(R.string.settings_theme_hint)).assertExists()
    }

    @Test fun tappingASegmentChoosesThatTheme() {
        show()
        compose.onNodeWithText(str(R.string.settings_theme_system)).assertIsSelected()
        compose.onNodeWithText(str(R.string.settings_theme_light)).performClick()
        compose.onNodeWithText(str(R.string.settings_theme_light)).assertIsSelected()
        compose.onNodeWithText(str(R.string.settings_theme_dark)).performClick()
        compose.onNodeWithText(str(R.string.settings_theme_system)).performClick()
        assertEquals(listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM), themes)
    }

    @Test fun highContrastIsOneSwitchThatReportsItsState() {
        show()
        compose.onNodeWithText(str(R.string.settings_high_contrast)).assertIsOff().performClick()
        compose.onNodeWithText(str(R.string.settings_high_contrast)).assertIsOn()
        // The hint is read with the title as one TalkBack stop.
        compose.onNode(hasText(str(R.string.settings_high_contrast)) and hasText(str(R.string.settings_high_contrast_hint))).assertExists()
        compose.onNodeWithText(str(R.string.settings_high_contrast)).performClick()
        assertEquals(listOf(true, false), contrasts)
    }

    @Test fun theSectionLabelIsAHeading() {
        show()
        compose.onNode(hasText(str(R.string.settings_appearance)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }

    @Test fun withNoAppLanguageTheRowSaysDeviceLanguage() {
        show()
        compose.onNode(hasText(str(R.string.settings_language)) and hasText(str(R.string.settings_language_device))).assertExists()
    }

    @Test fun choosingALanguageSetsItForTheAppAndClosesThePicker() {
        show()
        compose.onNodeWithText(str(R.string.settings_language)).performClick()
        // The picker: follow the device, every supported language, and a note while English is the only one.
        compose.onNodeWithText(str(R.string.settings_language_only_english)).assertExists()
        option(str(R.string.settings_language_device)).assertIsSelected()
        option(AppLanguage.displayName("en")).assertIsNotSelected().performClick()
        compose.waitForIdle()
        assertEquals(LocaleList.forLanguageTags("en"), context.getSystemService(LocaleManager::class.java).applicationLocales)
        assertTrue(compose.onAllNodes(hasText(str(R.string.settings_language_only_english))).fetchSemanticsNodes().isEmpty())
        // The row now names the chosen language.
        compose.onNode(hasText(str(R.string.settings_language)) and hasText(AppLanguage.displayName("en"))).assertExists()
    }

    @Test fun choosingDeviceLanguageClearsTheAppLanguage() {
        AppLanguage.set(context, "en")
        show()
        compose.onNode(hasText(str(R.string.settings_language)) and hasText(AppLanguage.displayName("en"))).assertExists()
        compose.onNodeWithText(str(R.string.settings_language)).performClick()
        option(AppLanguage.displayName("en")).assertIsSelected()
        option(str(R.string.settings_language_device)).performClick()
        compose.waitForIdle()
        assertTrue(context.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty)
    }

    @Test fun cancelClosesThePickerWithoutAChange() {
        show()
        compose.onNodeWithText(str(R.string.settings_language)).performClick()
        compose.onNodeWithText(str(R.string.detail_cancel)).performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText(str(R.string.settings_language_only_english))).fetchSemanticsNodes().isEmpty())
        assertTrue(context.getSystemService(LocaleManager::class.java).applicationLocales.isEmpty)
    }
}
