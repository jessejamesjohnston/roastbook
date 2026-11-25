package dev.roastbook.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Container tokens. Fixed scheme: the bag art is the only color that changes.
// Every role Material components read is set explicitly, so no library default (the baseline
// purple) ever leaks in. ContrastTest holds the text pairs at 4.5:1 and outlines at 3:1.

// Light palette.
val Oat = Color(0xFFF3EEE6)
val OatContainer = Color(0xFFE9E1D6)
val OatContainerHigh = Color(0xFFE2D9CE)
val OatOutline = Color(0xFFD1C9C0)
val OatOutlineStrong = Color(0xFF857B70)
val Ink = Color(0xFF1F1B17)
val InkVariant = Color(0xFF5C5349)
val InkOnTint = Color(0xFF5A5248)
val Espresso = Color(0xFF2B1F17)
val Paper = Color(0xFFF3EDE4)
val Latte = Color(0xFFE5D3C0)
val Honey = Color(0xFFF2DDB2)
val HoneyInk = Color(0xFF3B2A0A)

// Dark palette (it also uses Paper and Espresso from the light one).
val Roast = Color(0xFF17130F)
val RoastContainer = Color(0xFF25211B)
val RoastContainerHigh = Color(0xFF2D2823)
val RoastOutline = Color(0xFF37322C)
val RoastOutlineStrong = Color(0xFF8C8378)
val PaperVariant = Color(0xFFC2BAAF)
val PaperOnTint = Color(0xFFBDB3A5)
val Mocha = Color(0xFF4A3A2E)
val Amber = Color(0xFF4B3912)
val AmberInk = Color(0xFFF6E2B8)

/** The standard light scheme: oat surfaces, ink text, espresso primary. */
val LightScheme = lightColorScheme(
    primary = Espresso, onPrimary = Paper,
    primaryContainer = Latte, onPrimaryContainer = Espresso,
    secondary = InkVariant, onSecondary = Paper,
    secondaryContainer = OatContainerHigh, onSecondaryContainer = Ink,
    tertiary = Color(0xFF6B4E12), onTertiary = Paper,
    tertiaryContainer = Honey, onTertiaryContainer = HoneyInk,
    surface = Oat, onSurface = Ink,
    surfaceContainer = OatContainer, surfaceContainerHigh = OatContainerHigh,
    surfaceContainerLow = Oat, surfaceContainerLowest = Oat, surfaceContainerHighest = OatContainerHigh,
    surfaceVariant = OatContainerHigh, onSurfaceVariant = InkVariant,
    surfaceBright = Oat, surfaceDim = OatContainerHigh,
    inverseSurface = Roast, inverseOnSurface = Paper, inversePrimary = Paper,
    outline = OatOutlineStrong, outlineVariant = OatOutline,
    background = Oat, onBackground = Ink,
    error = Color(0xFF8C3A2B), onError = Paper,
    errorContainer = Color(0xFFF4D6CF), onErrorContainer = Color(0xFF4A1409),
    scrim = Color.Black,
)

/** The standard dark scheme: roast surfaces, paper text, paper primary. */
val DarkScheme = darkColorScheme(
    primary = Paper, onPrimary = Espresso,
    primaryContainer = Mocha, onPrimaryContainer = Paper,
    secondary = PaperVariant, onSecondary = Roast,
    secondaryContainer = RoastContainerHigh, onSecondaryContainer = Paper,
    tertiary = Color(0xFFE9C987), onTertiary = Roast,
    tertiaryContainer = Amber, onTertiaryContainer = AmberInk,
    surface = Roast, onSurface = Paper,
    surfaceContainer = RoastContainer, surfaceContainerHigh = RoastContainerHigh,
    surfaceContainerLow = Roast, surfaceContainerLowest = Roast, surfaceContainerHighest = RoastContainerHigh,
    surfaceVariant = RoastContainerHigh, onSurfaceVariant = PaperVariant,
    surfaceBright = RoastContainerHigh, surfaceDim = Roast,
    inverseSurface = Paper, inverseOnSurface = Roast, inversePrimary = Espresso,
    outline = RoastOutlineStrong, outlineVariant = RoastOutline,
    background = Roast, onBackground = Paper,
    error = Color(0xFFE8A598), onError = Roast,
    errorContainer = Color(0xFF5C2319), onErrorContainer = Color(0xFFF8DAD3),
    scrim = Color.Black,
)

/**
 * The high-contrast light scheme (Settings > Appearance): white surfaces, black text, 7:1 text
 * contrast, hard outlines. Rows drop the bag tint in high contrast.
 */
val HighContrastLightScheme = lightColorScheme(
    primary = Color(0xFF1A120C), onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DFD4), onPrimaryContainer = Color.Black,
    secondary = Color(0xFF2E2923), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E0D8), onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF3F2C05), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF4E4C2), onTertiaryContainer = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceContainer = Color(0xFFF3F0EC), surfaceContainerHigh = Color(0xFFEAE5DF),
    surfaceContainerLow = Color.White, surfaceContainerLowest = Color.White, surfaceContainerHighest = Color(0xFFEAE5DF),
    surfaceVariant = Color(0xFFEAE5DF), onSurfaceVariant = Color(0xFF2E2923),
    surfaceBright = Color.White, surfaceDim = Color(0xFFEAE5DF),
    inverseSurface = Color.Black, inverseOnSurface = Color.White, inversePrimary = Color.White,
    outline = Color(0xFF3D3731), outlineVariant = Color(0xFF6E665D),
    background = Color.White, onBackground = Color.Black,
    error = Color(0xFF6E1F12), onError = Color.White,
    errorContainer = Color(0xFFF7DCD5), onErrorContainer = Color.Black,
    scrim = Color.Black,
)

/** The high-contrast dark counterpart: black surfaces, white text, light outlines. */
val HighContrastDarkScheme = darkColorScheme(
    primary = Color(0xFFFFF8EF), onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A2E24), onPrimaryContainer = Color.White,
    secondary = Color(0xFFEDE6DC), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF26221E), onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFFE3A8), onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF3A2B0A), onTertiaryContainer = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceContainer = Color(0xFF15120F), surfaceContainerHigh = Color(0xFF211D19),
    surfaceContainerLow = Color.Black, surfaceContainerLowest = Color.Black, surfaceContainerHighest = Color(0xFF211D19),
    surfaceVariant = Color(0xFF211D19), onSurfaceVariant = Color(0xFFEDE6DC),
    surfaceBright = Color(0xFF211D19), surfaceDim = Color.Black,
    inverseSurface = Color.White, inverseOnSurface = Color.Black, inversePrimary = Color.Black,
    outline = Color(0xFFD9D0C5), outlineVariant = Color(0xFF9C9388),
    background = Color.Black, onBackground = Color.White,
    error = Color(0xFFFFB4A6), onError = Color.Black,
    errorContainer = Color(0xFF4A150C), onErrorContainer = Color.White,
    scrim = Color.Black,
)
