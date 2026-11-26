package dev.roastbook.app.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import dev.roastbook.app.util.English
import dev.roastbook.app.util.englishPlural
import dev.roastbook.app.data.db.CoffeeRow
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.ui.components.HeroSquare
import dev.roastbook.app.ui.components.RatingDots
import dev.roastbook.app.ui.components.fileOrNull
import dev.roastbook.app.ui.components.themedTint
import dev.roastbook.app.ui.detail.extractingLines
import dev.roastbook.app.ui.theme.LocalHighContrast
import dev.roastbook.app.ui.theme.NotesStyle
import dev.roastbook.app.ui.theme.RatingStyle
import dev.roastbook.app.ui.theme.onTintVariant

/**
 * One coffee in the list. TalkBack reads the row as one item ("Hills of Lumen, Larkmoor Coffee, rated
 * 4.2 out of 5, logged Sep 3"); the Rate button stays its own control. [selected] marks the coffee
 * open in the right-hand pane on a large screen.
 */
@Composable
fun CoffeeRowCard(
    row: CoffeeRow, photoStore: PhotoStore, onClick: () -> Unit, onRate: () -> Unit, selected: Boolean = false,
) {
    val tint = row.themedTint()
    val secondary = onTintVariant()
    val extracting = row.state == CoffeeState.EXTRACTING
    val hc = LocalHighContrast.current
    val ratingLabel = row.overall?.let { stringResource(R.string.row_rating_description, English.score(it)) }
    val title = when {
        extracting -> extractingLines(row.extractionTrail, row.nextAttemptAt).first
        row.extractionSource == ExtractionSource.NONE && row.coffeeName == null -> stringResource(R.string.row_extract_failed)
        else -> row.coffeeName ?: row.roaster ?: stringResource(R.string.detail_untitled)
    }
    val rateLabel = stringResource(R.string.row_unrated_cta)
    val measurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.titleMedium
    val buttonStyle = MaterialTheme.typography.labelSmall
    val trailing: @Composable () -> Unit = {
        when {
            extracting -> CircularProgressIndicator(Modifier.size(22.dp), color = tint.accent, strokeWidth = 2.5.dp)
            row.overall != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = ratingLabel ?: "" },
                ) {
                    Text(English.score(row.overall), style = RatingStyle, color = MaterialTheme.colorScheme.onSurface)
                    RatingDots(row.overall, tint.accent)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = secondary)
            }
            row.state == CoffeeState.LOGGED -> Button(
                onClick = onRate, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(stringResource(R.string.row_unrated_cta), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, softWrap = false)
            }
            else -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = secondary)
        }
    }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = tint.tint),
        border = when {
            // In high contrast every card is outlined, so the selected one needs a heavier line.
            selected -> BorderStroke(if (hc) 4.dp else 2.dp, MaterialTheme.colorScheme.primary)
            hc -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            else -> null
        },
        // Only the coffee open beside the list says "Selected"; on a phone every card said "Not selected".
        modifier = Modifier.fillMaxWidth().then(if (selected) Modifier.semantics { this.selected = true } else Modifier),
    ) {
        BoxWithConstraints {
        // Where the rating or rate button would squeeze the name until its longest word breaks letter
        // by letter (large text, a narrow window), the button moves under the text instead.
        val density = LocalDensity.current
        val stacked = remember(title, rateLabel, row.overall, constraints.maxWidth, density) {
            fun width(text: String, style: androidx.compose.ui.text.TextStyle) = measurer.measure(text, style).size.width
            val longestWord = title.split(Regex("\\s+")).maxOfOrNull { width(it, titleStyle) } ?: 0
            val trailing = with(density) {
                when {
                    extracting -> 22.dp.roundToPx()
                    row.overall != null -> width(English.score(row.overall), RatingStyle) + 20.dp.roundToPx()
                    row.state == CoffeeState.LOGGED -> width(rateLabel, buttonStyle) + 20.dp.roundToPx()
                    else -> 20.dp.roundToPx()
                }.coerceAtLeast(64.dp.roundToPx())
            }
            trailingGoesUnder(constraints.maxWidth, longestWord, trailing, with(density) { ROW_FIXED.roundToPx() })
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            HeroSquare(
                file = photoStore.fileOrNull(row.heroThumb), size = 56.dp, radius = 10.dp, stroke = tint.fallback,
                modifier = Modifier.alpha(if (extracting) 0.55f else 1f),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                when {
                    extracting -> {
                        val (readingTitle, readingSubtitle) = extractingLines(row.extractionTrail, row.nextAttemptAt)
                        Text(readingTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(readingSubtitle, style = NotesStyle, color = secondary)
                        val photos = englishPlural(R.plurals.photos, row.photoCount)
                        Text(photos + " · " + stringResource(R.string.row_just_now),
                            style = MaterialTheme.typography.labelSmall, color = secondary)
                    }
                    row.extractionSource == ExtractionSource.NONE && row.coffeeName == null -> {
                        Text(stringResource(R.string.row_extract_failed), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(R.string.row_extract_failed_subtitle), style = NotesStyle, color = secondary)
                        Text(English.shortDate(row.loggedAt), style = MaterialTheme.typography.labelSmall, color = secondary)
                    }
                    else -> {
                        Text(row.coffeeName ?: row.roaster ?: stringResource(R.string.detail_untitled),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        if (row.tastingNotes.isNotEmpty()) {
                            Text(row.tastingNotes.joinToString(" · "), style = NotesStyle, color = secondary, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                        val byline = listOfNotNull(row.roaster.takeIf { row.coffeeName != null }, English.shortDate(row.loggedAt))
                        Text(byline.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (stacked) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { trailing() }
                }
            }
            if (!stacked) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.widthIn(min = 64.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
            }
        }
        }
    }
}

/** A row's width that isn't the text's when the button sits beside it: padding, photo and gaps. */
private val ROW_FIXED = 100.dp

/**
 * True when the title's longest word ([longestWordPx]) wouldn't fit between the photo and a
 * [trailingPx]-wide button in a row [rowWidthPx] wide, so the button goes under the text.
 */
internal fun trailingGoesUnder(rowWidthPx: Int, longestWordPx: Int, trailingPx: Int, fixedPx: Int): Boolean =
    longestWordPx > rowWidthPx - fixedPx - trailingPx
