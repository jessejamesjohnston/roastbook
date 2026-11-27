package dev.roastbook.app.ui.theme

import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The desktop caption bar takes the page's color, with dark buttons on a light page and light
 * buttons on a dark one, and follows the in-app Light/Dark choice and high contrast, not only the
 * system's (as in the other Merriment Labs apps).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CaptionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val light = WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS or
        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    private val transparent = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND

    private fun painted(dark: Boolean, highContrast: Boolean = false): Pair<Int, Int> {
        var page = 0
        compose.setContent {
            RoastbookTheme(darkTheme = dark, highContrast = highContrast) {
                page = MaterialTheme.colorScheme.surface.toArgb()
                PaintWindow()
            }
        }
        compose.waitForIdle()
        val appearance = compose.activity.window.insetsController!!.systemBarsAppearance
        val background = (compose.activity.window.decorView.background as android.graphics.drawable.ColorDrawable).color
        assertEquals("the window under the caption takes the page color", page, background)
        return appearance to page
    }

    @Test fun aLightPageGetsDarkCaptionButtonsOverItsOwnColor() {
        val (appearance, _) = painted(dark = false)
        assertEquals(light or transparent, appearance and (light or transparent))
    }

    @Test fun aDarkPageGetsLightCaptionButtons() {
        val (appearance, _) = painted(dark = true)
        assertEquals(transparent, appearance and (light or transparent))
    }

    @Test fun theAppsWindowIsPaintedInsideItsTheme() {
        val main = listOf("src/main", "app/src/main").map { java.io.File(it, "java/dev/roastbook/app/MainActivity.kt") }.first { it.isFile }.readText()
        val theme = main.indexOf("RoastbookTheme(darkTheme = dark")
        assertEquals("PaintWindow runs inside the app's theme", true, theme >= 0 && main.indexOf("PaintWindow()", theme) > theme)
    }

    @Test fun highContrastDarkIsPaintedToo() {
        val (appearance, _) = painted(dark = true, highContrast = true)
        assertEquals(transparent, appearance and (light or transparent))
    }
}
