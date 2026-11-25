package dev.roastbook.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.roastbook.app.color.TintRule
import dev.roastbook.app.ui.theme.LocalDarkTheme
import dev.roastbook.app.ui.theme.LocalHighContrast
import dev.roastbook.app.ui.theme.RoastbookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which tint and accent a coffee's row gets in each theme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ComponentsTest {
    @get:Rule val compose = createComposeRule()

    private fun tintIn(dark: Boolean, highContrast: Boolean, light: Int?, darkTint: Int?): Pair<ThemedTint, Color> {
        var out: ThemedTint? = null
        var primary = Color.Unspecified
        compose.setContent {
            RoastbookTheme(darkTheme = dark, highContrast = highContrast) {
                CompositionLocalProvider(LocalDarkTheme provides dark, LocalHighContrast provides highContrast) {
                    out = themedTint(light, darkTint, 0xFF112233.toInt(), 0xFF445566.toInt(), fallback = false)
                    primary = MaterialTheme.colorScheme.primary
                }
            }
        }
        compose.waitForIdle()
        return out!! to primary
    }

    @Test fun lightThemeUsesTheBagsLightTint() {
        val (t, _) = tintIn(dark = false, highContrast = false, light = 0xFFAABBCC.toInt(), darkTint = 0xFF010203.toInt())
        assertEquals(Color(0xFFAABBCC.toInt()), t.tint)
        assertEquals(Color(0xFF112233.toInt()), t.accent)
        assertFalse(t.fallback)
    }

    @Test fun darkThemeUsesTheBagsDarkTint() {
        val (t, _) = tintIn(dark = true, highContrast = false, light = 0xFFAABBCC.toInt(), darkTint = 0xFF010203.toInt())
        assertEquals(Color(0xFF010203.toInt()), t.tint)
        assertEquals(Color(0xFF445566.toInt()), t.accent)
    }

    @Test fun aCoffeeWithNoPaletteFallsBackToTheNeutralTint() {
        val (t, _) = tintIn(dark = false, highContrast = false, light = null, darkTint = null)
        assertEquals(Color(TintRule.neutral.tintLight), t.tint)
        assertTrue(t.fallback)
    }

    @Test fun highContrastDropsTheBagTintForThePrimaryColor() {
        val (t, primary) = tintIn(dark = false, highContrast = true, light = 0xFFAABBCC.toInt(), darkTint = null)
        assertEquals(primary, t.accent)
        assertTrue(t.fallback)
    }
}
