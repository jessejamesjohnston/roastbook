package dev.roastbook.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.roastbook.app.color.Oklch
import dev.roastbook.app.color.TintRule
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x contrast checks for every text/background pair the app draws, in all four schemes,
 * and for the bag-tinted cards across the whole hue circle. Text needs 4.5:1 (7:1 in high
 * contrast); non-text marks such as rating dots and outlines need 3:1.
 */
class ContrastTest {

    private fun ratio(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    private data class Pair(val name: String, val fg: Color, val bg: Color, val min: Double)

    private fun textPairs(s: ColorScheme, min: Double): List<Pair> = listOf(
        Pair("onSurface/surface", s.onSurface, s.surface, min),
        Pair("onSurfaceVariant/surface", s.onSurfaceVariant, s.surface, min),
        Pair("onSurfaceVariant/surfaceContainer", s.onSurfaceVariant, s.surfaceContainer, min),
        Pair("onSurfaceVariant/surfaceContainerHigh", s.onSurfaceVariant, s.surfaceContainerHigh, min),
        Pair("onSurfaceVariant/surfaceContainerHighest", s.onSurfaceVariant, s.surfaceContainerHighest, min),
        Pair("onSurface/surfaceContainerHigh", s.onSurface, s.surfaceContainerHigh, min),
        Pair("primary/surface", s.primary, s.surface, min),
        Pair("primary/surfaceContainerHigh", s.primary, s.surfaceContainerHigh, min),
        Pair("onPrimary/primary", s.onPrimary, s.primary, min),
        Pair("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer, min),
        Pair("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer, min),
        Pair("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, min),
        // Settings section labels sit on the page, above their cards.
        Pair("tertiary/surface", s.tertiary, s.surface, min),
        Pair("error/surface", s.error, s.surface, min),
        Pair("error/surfaceContainer", s.error, s.surfaceContainer, min),
        Pair("onError/error", s.onError, s.error, min),
        Pair("inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface, min),
        // Non-text: outlined fields, switch tracks, dividers that carry meaning.
        Pair("outline/surface (non-text)", s.outline, s.surface, 3.0),
        Pair("outline/surfaceContainer (non-text)", s.outline, s.surfaceContainer, 3.0),
        // The keyboard focus ring, on the page and on a card.
        Pair("focus ring primary/surface (non-text)", s.primary, s.surface, 3.0),
        Pair("focus ring primary/surfaceContainer (non-text)", s.primary, s.surfaceContainer, 3.0),
    )

    private fun check(pairs: List<Pair>): List<String> = pairs.mapNotNull { p ->
        val r = ratio(p.fg, p.bg)
        if (r + 1e-6 < p.min) "%s %.2f < %.1f".format(p.name, r, p.min) else null
    }

    @Test fun lightScheme() = assertNoFailures("light", check(textPairs(LightScheme, 4.5)))
    @Test fun darkScheme() = assertNoFailures("dark", check(textPairs(DarkScheme, 4.5)))
    @Test fun highContrastLight() = assertNoFailures("hc light", check(textPairs(HighContrastLightScheme, 7.0)))
    @Test fun highContrastDark() = assertNoFailures("hc dark", check(textPairs(HighContrastDarkScheme, 7.0)))

    /** Every bag hue and chroma the tint rule can produce, for the text and marks drawn on a tinted card. */
    @Test fun tintedCards() {
        val failures = mutableListOf<String>()
        for (hue in 0 until 360 step 5) for (c in listOf(0.0, 0.03, 0.08, 0.15, 0.3)) {
            val row = TintRule.fromSource(Oklch(0.5, c, hue.toDouble()), fallback = false)
            val lt = Color(row.tintLight); val dt = Color(row.tintDark)
            val pairs = listOf(
                Pair("light onSurface h$hue c$c", LightScheme.onSurface, lt, 4.5),
                Pair("light onTint h$hue c$c", onTintVariant(dark = false, highContrast = false), lt, 4.5),
                Pair("light primary h$hue c$c", LightScheme.primary, lt, 4.5),
                Pair("light accent h$hue c$c (non-text)", Color(row.accentLight), lt, 3.0),
                Pair("dark onSurface h$hue c$c", DarkScheme.onSurface, dt, 4.5),
                Pair("dark onTint h$hue c$c", onTintVariant(dark = true, highContrast = false), dt, 4.5),
                Pair("dark primary h$hue c$c", DarkScheme.primary, dt, 4.5),
                Pair("dark accent h$hue c$c (non-text)", Color(row.accentDark), dt, 3.0),
            )
            failures += check(pairs)
        }
        assertNoFailures("tinted cards", failures)
    }

    private fun assertNoFailures(label: String, failures: List<String>) {
        assertTrue("$label contrast failures (${failures.size}):\n" + failures.take(40).joinToString("\n"), failures.isEmpty())
    }
}
