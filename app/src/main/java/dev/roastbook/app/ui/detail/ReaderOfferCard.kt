package dev.roastbook.app.ui.detail

import androidx.compose.foundation.shape.CircleShape
import dev.roastbook.app.ui.components.focusRing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import dev.roastbook.app.util.English
import dev.roastbook.app.reader.DownloadPhase
import dev.roastbook.app.reader.ReaderState
import dev.roastbook.app.ui.components.ReaderProgressBar
import dev.roastbook.app.ui.components.readerFailureText
import dev.roastbook.app.ui.components.readerProgressText

/**
 * The in-flow reader offer on a coffee's page, after this device's built-in AI read the bag poorly
 * ([dev.roastbook.app.reader.ReaderNudge]): what the reader is and costs, Download or Not now. Once
 * accepted ([waiting]), the same card follows the download until the bag is read again, with a way
 * out in every state: Cancel download while it runs, waits or is paused. Once it failed, Not now
 * stops this bag waiting and keeps what was downloaded and the reader switch
 * ([dev.roastbook.app.reader.ReaderPrefs.declineOffer]); Settings still offers Try again, and
 * Cancel download when part of the file is there.
 *
 * | Phase | Line | Bar | Buttons |
 * |---|---|---|---|
 * | NONE, nothing yet (the moment after Download, also over a part a failed download left) | Starting the download… | moving | Cancel download |
 * | NONE, stopped part-way (Pause) | Paused at X of Y / Download paused | none | Resume, Cancel download |
 * | WAITING_FOR_NETWORK | Waiting for Wi-Fi (or a connection), with X of Y once some arrived | still, at X | Cancel download |
 * | DOWNLOADING, no byte yet (the job just enqueued, running before its first report, or reporting 0) | Starting the download… | moving | Cancel download |
 * | DOWNLOADING | Downloading: X of Y | filling with the bytes | Cancel download |
 * | VERIFYING | Checking the download… | moving | Cancel download |
 * | FAILED | why, in Settings' words | none | Try again, Not now (this bag stops waiting) |
 * | READY, read not started yet | Reading this bag again with the on-device reader… | moving | none |
 */
@Composable
fun ReaderOfferCard(
    reader: ReaderState, waiting: Boolean,
    onDownload: () -> Unit, onNotNow: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    fun size(b: Long) = English.fileSize(b)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (!waiting) {
            Text(stringResource(R.string.detail_reader_offer_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            val body = if (reader.allowMetered) R.string.detail_reader_offer_body_mobile else R.string.detail_reader_offer_body
            Text(stringResource(body, size(reader.totalBytes)), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Buttons(
                primary = stringResource(R.string.reader_download, size(reader.totalBytes)) to onDownload,
                secondary = stringResource(R.string.detail_reader_offer_not_now) to onNotNow,
            )
            return@Column
        }
        val partial = reader.downloadedBytes > 0 && !reader.starting
        val paused = reader.phase == DownloadPhase.NONE && (partial || reader.stopped)
        val failed = reader.phase == DownloadPhase.FAILED
        val line = when (reader.phase) {
            DownloadPhase.READY -> stringResource(R.string.detail_reader_rereading)
            DownloadPhase.FAILED -> readerFailureText(reader.failure, size(reader.totalBytes))
            DownloadPhase.NONE -> when {
                partial -> stringResource(R.string.reader_partial, size(reader.downloadedBytes), size(reader.totalBytes))
                reader.stopped -> stringResource(R.string.detail_reader_paused)
                else -> stringResource(R.string.detail_reader_starting)
            }
            else -> readerProgressText(reader, ::size)
        }
        Text(
            line, style = MaterialTheme.typography.bodyLarge,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        ReaderProgressBar(reader, paused = paused, label = line)
        if (reader.phase != DownloadPhase.READY) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.detail_reader_waiting), style = MaterialTheme.typography.bodySmall, color = secondary)
        }
        when {
            reader.phase == DownloadPhase.READY -> Unit
            failed -> {
                Spacer(Modifier.height(8.dp))
                Buttons(
                    primary = stringResource(R.string.reader_retry) to onRetry,
                    secondary = stringResource(R.string.detail_reader_offer_not_now) to onNotNow,
                )
            }
            paused -> {
                Spacer(Modifier.height(8.dp))
                Buttons(
                    primary = stringResource(R.string.reader_resume) to onRetry,
                    secondary = stringResource(R.string.reader_cancel) to onCancel,
                )
            }
            else -> {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).focusRing(CircleShape)) { Text(stringResource(R.string.reader_cancel)) }
            }
        }
    }
}

/**
 * What the card shows in the moment after Download, before the accepted offer is written: the
 * download starting. An earlier job's failure or pause is set aside by that write, so neither shows
 * meanwhile; a download already under way shows as it is.
 */
fun ReaderState.asJustAccepted(): ReaderState =
    if (phase == DownloadPhase.NONE || phase == DownloadPhase.FAILED) {
        copy(phase = DownloadPhase.NONE, starting = true, stopped = false, failure = null)
    } else this

/** A filled button and a text button side by side, the second moving below at large text sizes. */
@Composable
private fun Buttons(primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Button(onClick = primary.second, modifier = Modifier.heightIn(min = 48.dp).focusRing(CircleShape, onFill = true)) { Text(primary.first) }
        TextButton(onClick = secondary.second, modifier = Modifier.heightIn(min = 48.dp).focusRing(CircleShape)) { Text(secondary.first) }
    }
}

