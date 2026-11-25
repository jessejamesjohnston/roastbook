package dev.roastbook.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Whether the app is drawing dark, after the user's Light / Dark / System choice is applied. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** Settings > Appearance > High contrast: plain surfaces, 7:1 text, no bag tints. */
val LocalHighContrast = staticCompositionLocalOf { false }

/**
 * [darkTheme] defaults to the configuration's night bit, which already reflects the user's choice:
 * the app sets its own night mode through UiModeManager, so "System" follows the device and
 * "Light"/"Dark" override it for this app only.
 */
@Composable
fun RoastbookTheme(darkTheme: Boolean = isSystemInDarkTheme(), highContrast: Boolean = false, content: @Composable () -> Unit) {
    val scheme = when {
        highContrast && darkTheme -> HighContrastDarkScheme
        highContrast -> HighContrastLightScheme
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalDarkTheme provides darkTheme, LocalHighContrast provides highContrast) {
        MaterialTheme(colorScheme = scheme, typography = RoastbookTypography, content = content)
    }
}

/** The color for secondary text on a tinted row, for the given theme and contrast setting. */
fun onTintVariant(dark: Boolean, highContrast: Boolean): Color = when {
    highContrast && dark -> HighContrastDarkScheme.onSurfaceVariant
    highContrast -> HighContrastLightScheme.onSurfaceVariant
    dark -> PaperOnTint
    else -> InkOnTint
}

/** [onTintVariant] for the current theme and contrast setting. */
@Composable
fun onTintVariant(): Color = onTintVariant(LocalDarkTheme.current, LocalHighContrast.current)
