package dev.roastbook.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which scheme each Light/Dark and high-contrast combination draws with, and the tinted-row secondary text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThemeTest {
    @get:Rule val compose = createComposeRule()

    private data class Seen(val scheme: ColorScheme, val dark: Boolean, val highContrast: Boolean, val onTint: Color)

    private fun inTheme(dark: Boolean, highContrast: Boolean): Seen {
        var seen: Seen? = null
        compose.setContent {
            RoastbookTheme(darkTheme = dark, highContrast = highContrast) {
                seen = Seen(MaterialTheme.colorScheme, LocalDarkTheme.current, LocalHighContrast.current, onTintVariant())
            }
        }
        compose.waitForIdle()
        return seen!!
    }

    /** The app calls RoastbookTheme without darkTheme, so the configuration's night bit decides. */
    private fun inDefaultTheme(): Pair<ColorScheme, Boolean> {
        var seen: Pair<ColorScheme, Boolean>? = null
        compose.setContent { RoastbookTheme { seen = MaterialTheme.colorScheme to LocalDarkTheme.current } }
        compose.waitForIdle()
        return seen!!
    }

    @Config(qualifiers = "night")
    @Test fun byDefaultANightConfigurationDrawsDark() {
        assertEquals(DarkScheme to true, inDefaultTheme())
    }

    @Config(qualifiers = "notnight")
    @Test fun byDefaultADayConfigurationDrawsLight() {
        assertEquals(LightScheme to false, inDefaultTheme())
    }

    @Test fun lightDrawsTheLightSchemeWithInkSecondaryTextOnTints() {
        val s = inTheme(dark = false, highContrast = false)
        assertEquals(LightScheme, s.scheme)
        assertEquals(false to false, s.dark to s.highContrast)
        assertEquals(InkOnTint, s.onTint)
    }

    @Test fun darkDrawsTheDarkSchemeWithPaperSecondaryTextOnTints() {
        val s = inTheme(dark = true, highContrast = false)
        assertEquals(DarkScheme, s.scheme)
        assertEquals(true to false, s.dark to s.highContrast)
        assertEquals(PaperOnTint, s.onTint)
    }

    @Test fun highContrastLightUsesItsOwnSchemeForSecondaryText() {
        val s = inTheme(dark = false, highContrast = true)
        assertEquals(HighContrastLightScheme, s.scheme)
        assertEquals(false to true, s.dark to s.highContrast)
        assertEquals(HighContrastLightScheme.onSurfaceVariant, s.onTint)
    }

    @Test fun highContrastDarkUsesItsOwnSchemeForSecondaryText() {
        val s = inTheme(dark = true, highContrast = true)
        assertEquals(HighContrastDarkScheme, s.scheme)
        assertEquals(true to true, s.dark to s.highContrast)
        assertEquals(HighContrastDarkScheme.onSurfaceVariant, s.onTint)
    }
}
