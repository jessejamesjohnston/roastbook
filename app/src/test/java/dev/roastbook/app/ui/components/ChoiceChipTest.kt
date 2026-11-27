package dev.roastbook.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.roastbook.app.ui.theme.RoastbookTheme
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A selected choice chip stands out from the sheets and cards it sits on, in every scheme. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ChoiceChipTest {
    @get:Rule val compose = createComposeRule()

    private fun check(dark: Boolean, highContrast: Boolean) {
        var selected = Color.Unspecified
        var sheet = Color.Unspecified
        var card = Color.Unspecified
        compose.setContent {
            RoastbookTheme(darkTheme = dark, highContrast = highContrast) {
                selected = ChoiceChipDefaults.selectedContainer()
                sheet = MaterialTheme.colorScheme.surfaceContainerHigh
                card = MaterialTheme.colorScheme.surfaceContainer
            }
        }
        compose.waitForIdle()
        assertNotEquals("dark=$dark hc=$highContrast", sheet, selected)
        assertNotEquals("dark=$dark hc=$highContrast", card, selected)
    }

    @Test fun light() = check(dark = false, highContrast = false)
    @Test fun dark() = check(dark = true, highContrast = false)
    @Test fun lightHighContrast() = check(dark = false, highContrast = true)
    @Test fun darkHighContrast() = check(dark = true, highContrast = true)
}
