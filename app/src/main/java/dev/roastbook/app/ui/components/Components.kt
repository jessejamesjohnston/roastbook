package dev.roastbook.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.roastbook.app.color.TintRule
import dev.roastbook.app.data.db.CoffeeRow
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.ui.theme.LocalDarkTheme
import dev.roastbook.app.ui.theme.LocalHighContrast
import java.io.File

/** Row/Detail colors for one coffee in the current theme. */
data class ThemedTint(val tint: Color, val accent: Color, val fallback: Boolean)

/**
 * Picks the stored light or dark colors for the current theme, using [TintRule.neutral] for any not
 * computed yet. High contrast ignores the bag entirely and returns the scheme's plain container and
 * primary, marked as fallback.
 */
@Composable
fun themedTint(tintLight: Int?, tintDark: Int?, accentLight: Int?, accentDark: Int?, fallback: Boolean): ThemedTint {
    // High contrast drops the bag tint: plain container, the scheme's primary for marks, and an
    // outline so the card edge still reads.
    if (LocalHighContrast.current) {
        return ThemedTint(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.primary, fallback = true)
    }
    val dark = LocalDarkTheme.current
    val neutral = TintRule.neutral
    val tint = (if (dark) tintDark ?: neutral.tintDark else tintLight ?: neutral.tintLight)
    val accent = (if (dark) accentDark ?: neutral.accentDark else accentLight ?: neutral.accentLight)
    return ThemedTint(Color(tint), Color(accent), fallback || tintLight == null)
}

/** [themedTint] for a History row's stored colors. */
@Composable
fun CoffeeRow.themedTint(): ThemedTint = themedTint(tintLight, tintDark, accentLight, accentDark, paletteFallback)

/** The square hero image; white-packaging rows get a 1 dp inset stroke at 12% ink so the box doesn't float. */
@Composable
fun HeroSquare(file: File?, size: Dp, radius: Dp, stroke: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(radius)
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (stroke) Modifier.border(BorderStroke(1.dp, ink), shape) else Modifier),
    ) {
        if (file != null && file.exists()) {
            AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

/** Five 7 dp dots: filled / half / outline, in the row's accent. */
@Composable
fun RatingDots(overall: Float, accent: Color, dot: Dp = 7.dp) {
    Row {
        for (i in 1..5) {
            val fill = (overall - (i - 1)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .size(dot)
                    .clip(CircleShape)
                    .border(1.dp, accent, CircleShape)
                    .background(if (fill >= 0.75f) accent else if (fill >= 0.25f) accent.copy(alpha = 0.45f) else Color.Transparent),
            )
            if (i < 5) Spacer(Modifier.width(3.dp))
        }
    }
}

/** The file for a stored relative path, or null when there is no path; the file may not exist. */
fun PhotoStore.fileOrNull(relative: String?): File? = relative?.let { file(it) }
