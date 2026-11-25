package dev.roastbook.app.ui.detail

import android.text.format.Formatter
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
import androidx.compose.material3.LinearProgressIndicator
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
import dev.roastbook.app.reader.DownloadPhase
import dev.roastbook.app.reader.ReaderState

/**
 * The in-flow reader offer on a coffee's page, after this device's built-in AI read the bag poorly
 * ([dev.roastbook.app.reader.ReaderNudge]): what the reader is and costs, Download or Not now. Once
 * accepted, the same card follows the download until the bag is read again.
 */
@Composable
fun ReaderOfferCard(
    reader: ReaderState, waiting: Boolean,
    onDownload: () -> Unit, onNotNow: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (!waiting) {
            Text(stringResource(R.string.detail_reader_offer_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.detail_reader_offer_body, size(reader.totalBytes)), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.reader_download, size(reader.totalBytes)))
                }
                TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.detail_reader_offer_not_now)) }
            }
            return@Column
        }
        val line = when (reader.phase) {
            DownloadPhase.VERIFYING -> stringResource(R.string.reader_verifying)
            DownloadPhase.WAITING_FOR_NETWORK ->
                stringResource(if (reader.allowMetered) R.string.reader_waiting_network else R.string.reader_waiting_wifi)
            DownloadPhase.FAILED -> stringResource(R.string.reader_failed_server)
            else -> stringResource(R.string.reader_downloading, size(reader.downloadedBytes), size(reader.totalBytes))
        }
        Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (reader.phase == DownloadPhase.DOWNLOADING && reader.totalBytes > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (reader.downloadedBytes.toFloat() / reader.totalBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.detail_reader_waiting), style = MaterialTheme.typography.bodySmall, color = secondary)
        if (reader.phase == DownloadPhase.FAILED || reader.phase == DownloadPhase.NONE) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (reader.phase == DownloadPhase.FAILED) R.string.reader_retry else R.string.reader_resume))
            }
        }
    }
}
