package dev.roastbook.app.ui.detail

import android.text.format.DateFormat
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.extraction.ExtractionTrail
import dev.roastbook.app.extraction.Tier
import dev.roastbook.app.extraction.TierOutcome
import dev.roastbook.app.extraction.WhatHappenedRules
import java.util.Date

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
    val nano = ExtractionTrail.decode(trail)[Tier.NANO]
    val retryAt = nextAttemptAt?.takeIf { it > System.currentTimeMillis() }
    if (nano == TierOutcome.BUSY) {
        // English like every other line: the app's strings are English only.
        val sub = if (retryAt != null) stringResource(R.string.row_busy_retry_at, englishTime(retryAt)) else stringResource(R.string.row_busy_subtitle)
        return stringResource(R.string.row_extracting) to sub
    }
    if (nano != TierOutcome.PREPARING) {
        return stringResource(R.string.row_extracting) to stringResource(R.string.row_extracting_subtitle)
    }
    val context = LocalContext.current
    val sub = if (retryAt != null) {
        stringResource(R.string.row_preparing_retry_at, DateFormat.getTimeFormat(context).format(Date(retryAt)))
    } else stringResource(R.string.row_preparing_subtitle)
    return stringResource(R.string.row_preparing) to sub
}

/** A clock time as "3:04 PM", in English whatever the device's language. */
internal fun englishTime(at: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
    java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US).format(java.time.Instant.ofEpochMilli(at).atZone(zone))

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
