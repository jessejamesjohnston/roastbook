package dev.roastbook.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import dev.roastbook.app.util.English
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.extraction.ExtractionTrail
import dev.roastbook.app.extraction.NanoLimits
import dev.roastbook.app.extraction.Tier
import dev.roastbook.app.extraction.TierOutcome
import dev.roastbook.app.extraction.WhatHappenedRules

/** True while Nano's model is downloading for this row (the first-download state). */
fun isPreparingReader(state: CoffeeState, trail: String?): Boolean =
    state == CoffeeState.EXTRACTING && ExtractionTrail.decode(trail)[Tier.NANO] == TierOutcome.PREPARING

/**
 * Title and subtitle for a coffee that is still being read: the usual "Reading the bag…", the
 * first-download state while the phone's AI model is still arriving, or a wait for the built-in
 * AI's usage limit (each with the retry time when the row is parked).
 */
@Composable
fun extractingLines(trail: String?, nextAttemptAt: Long?): Pair<String, String> {
    val decoded = ExtractionTrail.decode(trail)
    val nano = decoded[Tier.NANO]
    val retryAt = nextAttemptAt?.takeIf { it > System.currentTimeMillis() }
    if (nano == TierOutcome.BUSY) {
        val battery = decoded.nanoLimit == NanoLimits.Kind.BATTERY
        val sub = when {
            retryAt != null -> stringResource(if (battery) R.string.row_battery_retry_at else R.string.row_busy_retry_at, English.clock(retryAt))
            else -> stringResource(if (battery) R.string.row_battery_subtitle else R.string.row_busy_subtitle)
        }
        return stringResource(R.string.row_extracting) to sub
    }
    if (nano != TierOutcome.PREPARING) {
        return stringResource(R.string.row_extracting) to stringResource(R.string.row_extracting_subtitle)
    }
    val sub = if (retryAt != null) {
        stringResource(R.string.row_preparing_retry_at, English.clock(retryAt))
    } else stringResource(R.string.row_preparing_subtitle)
    return stringResource(R.string.row_preparing) to sub
}

/**
 * "Read by …" under the facts: which tier's reading this is, with the guess named as a guess. Null
 * when nothing was read (there is nothing to attribute).
 */
@Composable
fun extractionSourceLabel(c: CoffeeEntity): String? {
    val trail = ExtractionTrail.decode(c.extractionTrail)
    if (c.extractionSource == ExtractionSource.OCR && trail.guessedFields > 0) return stringResource(R.string.source_guess)
    val line = when (c.extractionSource) {
        ExtractionSource.NANO -> R.string.source_line_nano
        ExtractionSource.LOCAL -> R.string.source_line_local
        ExtractionSource.CLOUD -> R.string.source_line_cloud
        ExtractionSource.OCR -> R.string.source_line_ocr
        ExtractionSource.NONE -> return null
    }
    return stringResource(line)
}

/** The facts a model left blank that the label text may fill, in display order, with their labels. */
private val GUESSABLE = listOf(
    "originCountry" to R.string.field_origin_country, "region" to R.string.field_region, "producer" to R.string.field_producer,
    "process" to R.string.field_process, "varietal" to R.string.field_varietal, "elevationM" to R.string.field_elevation,
    "roastLevel" to R.string.field_roast_level, "bagSizeG" to R.string.field_bag_size, "tastingNotes" to R.string.field_tasting_notes,
)

/**
 * "Country and varietal guessed from the label text" under the facts: the fields a model's reading
 * left blank that hold the label text's guess, minus any the person has edited since. Null when
 * there are none.
 */
@Composable
fun guessedFieldsLine(c: CoffeeEntity): String? {
    val names = ExtractionTrail.decode(c.extractionTrail).guessedNames - c.userEdited.toSet()
    val labels = GUESSABLE.filter { it.first in names }.map { stringResource(it.second) }
    if (labels.isEmpty()) return null
    // Sentence case: the first label keeps its capital, the rest read in lowercase.
    val words = labels.mapIndexed { i, l -> if (i == 0) l else l.lowercase() }
    val list = when (words.size) {
        1 -> words[0]
        2 -> "${words[0]} and ${words[1]}"
        else -> words.dropLast(1).joinToString(", ") + ", and " + words.last()
    }
    return stringResource(R.string.source_guess_fields, list)
}

/**
 * The calm "what happened" card, shown once a read fell back: after a poor read a headline saying
 * so, then one line on why, one on whether the fields are a guess, a pointer to what would read
 * bags like this, and Read again (never overwrites a field the user edited). The person can close
 * it; it comes back after the next read.
 */
@Composable
fun ExtractionStatusCard(
    c: CoffeeEntity, readerOffered: Boolean, nanoFailing: Boolean, hasKey: Boolean,
    onReread: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, readerCardShown: Boolean = false,
) {
    if (c.state == CoffeeState.EXTRACTING) return
    val w = WhatHappenedRules.explain(
        source = c.extractionSource,
        trail = ExtractionTrail.decode(c.extractionTrail),
        hasOcrText = !c.ocrText.isNullOrBlank(),
        readerOffered = readerOffered,
        hasKey = hasKey,
        nanoFailing = nanoFailing,
        readerCardShown = readerCardShown,
    ) ?: return
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = secondary, modifier = Modifier.size(20.dp).padding(top = 2.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (w.poor) {
                    // Heard as soon as a read that finishes on this page turns out poor.
                    Text(
                        stringResource(R.string.detail_poor_read),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    w.lineArg?.let { stringResource(w.line, it) } ?: stringResource(w.line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                w.guess?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = secondary)
                }
                w.hint?.let { Spacer(Modifier.height(4.dp)); Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = secondary) }
            }
            // Close: the note goes until the next read. The slot is one line tall, so a one-line note
            // keeps a one-line card; the 48dp button is centered on it and reaches into the card's
            // padding, so the target stays 48dp to touch while the cross itself stays small.
            Box(Modifier.padding(start = 4.dp).size(width = 36.dp, height = 24.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.requiredSize(48.dp).offset(x = 6.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.detail_dismiss_note), tint = secondary, modifier = Modifier.size(20.dp))
                }
            }
        }
        if (w.offerReread) {
            Spacer(Modifier.height(8.dp))
            val description = stringResource(R.string.what_reread_description)
            OutlinedButton(onClick = onReread, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description }) {
                Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.what_reread))
            }
        }
    }
}
