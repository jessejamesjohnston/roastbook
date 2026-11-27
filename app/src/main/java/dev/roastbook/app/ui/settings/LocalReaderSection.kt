package dev.roastbook.app.ui.settings

import androidx.compose.foundation.shape.CircleShape
import dev.roastbook.app.ui.components.focusRing
import dev.roastbook.app.ui.components.initialFocus
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.roastbook.app.R
import dev.roastbook.app.ui.components.ReasonDialog
import dev.roastbook.app.util.English
import dev.roastbook.app.extraction.NanoStatus
import dev.roastbook.app.extraction.NanoTier
import dev.roastbook.app.reader.DeviceProfile
import dev.roastbook.app.reader.DownloadPhase
import dev.roastbook.app.reader.Eligibility
import dev.roastbook.app.reader.LiteRtLocalReader
import dev.roastbook.app.reader.LocalReaderManager
import dev.roastbook.app.reader.ReaderOffer
import dev.roastbook.app.reader.ReaderPrefs
import dev.roastbook.app.reader.ReaderState
import dev.roastbook.app.ui.components.ReaderProgressBar
import dev.roastbook.app.ui.components.readerFailureText
import dev.roastbook.app.ui.components.readerProgressText
import dev.roastbook.app.ui.components.rememberReaderDownloadStarter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State and actions for Settings' on-device reader section: the model's download state, Nano's
 * status (asked once, when created) and the reader settings. Download, pause, cancel and the
 * network rule go to [LocalReaderManager]; delete releases the loaded model before removing it.
 */
@HiltViewModel
class LocalReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: LocalReaderManager,
    private val reader: LiteRtLocalReader,
    private val prefs: ReaderPrefs,
    private val nano: NanoTier,
) : ViewModel() {
    val state: StateFlow<ReaderState?> = manager.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** False until Nano's status has been asked for once. */
    val nanoChecked = MutableStateFlow(false)
    /** Nano's status after the check; null = it didn't answer in time (see [ReaderOffer.of]). */
    val nanoStatus = MutableStateFlow<NanoStatus?>(null)
    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val totalMem: Long = DeviceProfile.totalMem(context)

    init {
        viewModelScope.launch {
            nanoStatus.value = ReaderOffer.probe(nano)
            nanoChecked.value = true
        }
    }

    fun download() { viewModelScope.launch { manager.download() } }
    fun pause() { manager.pause() }
    fun cancel() { viewModelScope.launch { manager.cancel() } }
    fun delete() { viewModelScope.launch { reader.release(); manager.delete() } }
    fun setAllowMetered(on: Boolean) { viewModelScope.launch { manager.setAllowMetered(on) } }
    fun setPreferReader(on: Boolean) { viewModelScope.launch { prefs.setPreferReader(on) } }
}

/**
 * Settings' on-device reader section (its label and card). Kept in its own file with its own
 * ViewModel so the Settings screen only places it. Shown on every device that can run the reader,
 * including one whose own AI reads bags (there it carries the choice of which one reads); elsewhere
 * it says why the reader can't run, or draws nothing (see [ReaderOffer.of]).
 */
/** Whether the reader download is waiting, running or being checked: its progress wants a notification. */
@Composable
internal fun readerDownloadActive(vm: LocalReaderViewModel = hiltViewModel()): Boolean {
    val state by vm.state.collectAsState()
    return state?.phase in setOf(DownloadPhase.WAITING_FOR_NETWORK, DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING)
}

@Composable
fun LocalReaderSection(vm: LocalReaderViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val nano by vm.nanoStatus.collectAsState()
    val nanoChecked by vm.nanoChecked.collectAsState()
    val settings by vm.settings.collectAsState()
    val s = state ?: return
    val hasFiles = ReaderOffer.hasReaderFiles(s)
    // Reader files on disk show their controls at once; otherwise wait for Nano's answer.
    if (!nanoChecked && !(hasFiles && s.eligibility == Eligibility.ELIGIBLE)) return
    var confirmDelete by remember { mutableStateOf(false) }
    val offer = ReaderOffer.of(s.eligibility, nano, settings?.nanoFailing == true, hasFiles, everywhere = true)
    // The device has its own AI, so there is a choice to make between it and the reader.
    val hasBuiltInAi = nano == NanoStatus.AVAILABLE || nano == NanoStatus.DOWNLOADABLE
    if (offer == ReaderOffer.HIDDEN) return
    fun size(b: Long) = English.fileSize(b)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // The first download that would need the notification permission says why first (see
    // [ReaderDownloadStarter]); while it's refused, the progress says it shows only here.
    val starter = rememberReaderDownloadStarter { vm.download() }

    SectionLabel(stringResource(R.string.reader_title))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
    ) {
        if (offer == ReaderOffer.CANT_MEMORY || offer == ReaderOffer.CANT_CPU) {
            Text(stringResource(R.string.reader_cant_title), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
        }
        when (offer) {
            ReaderOffer.HIDDEN -> Unit
            ReaderOffer.CANT_MEMORY -> Text(
                stringResource(R.string.reader_cant_memory, size(vm.totalMem)),
                style = MaterialTheme.typography.bodyMedium, color = secondary,
            )
            ReaderOffer.CANT_CPU ->
                Text(stringResource(R.string.reader_cant_cpu), style = MaterialTheme.typography.bodyMedium, color = secondary)
            ReaderOffer.OFFER -> ReaderControls(
                s, size = ::size, allowMetered = settings?.allowMetered == true,
                nanoFailReason = settings?.nanoFailReason.takeIf { settings?.nanoFailing == true },
                notificationsOff = starter.notificationsOff,
                onDownload = { starter.start(DOWNLOAD) }, onPause = vm::pause, onCancel = vm::cancel, onDelete = { confirmDelete = true },
                onAllowMetered = vm::setAllowMetered,
                preferReader = settings?.preferReader == true, onPreferReader = vm::setPreferReader.takeIf { hasBuiltInAi },
            )
        }
    }

    starter.ReasonDialog()
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.reader_delete_title)) },
        text = { Text(stringResource(R.string.reader_delete_confirm)) },
        confirmButton = {
            TextButton(modifier = Modifier.focusRing(CircleShape), onClick = { confirmDelete = false; vm.delete() }) { Text(stringResource(R.string.reader_delete_yes)) }
        },
        dismissButton = { TextButton(modifier = Modifier.initialFocus().focusRing(CircleShape), onClick = { confirmDelete = false }) { Text(stringResource(R.string.detail_cancel)) } },
    )
}

/** The one action Settings' starter runs. */
private const val DOWNLOAD = "download"

@Composable
private fun ReaderControls(
    s: ReaderState, size: (Long) -> String, allowMetered: Boolean, nanoFailReason: String?, notificationsOff: Boolean,
    onDownload: () -> Unit, onPause: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit, onAllowMetered: (Boolean) -> Unit,
    preferReader: Boolean = false, onPreferReader: ((Boolean) -> Unit)? = null,
) {
    ReaderIntro(totalSize = size(s.totalBytes), nanoFailing = nanoFailReason != null)
    when (s.phase) {
        DownloadPhase.READY -> ReaderReady(sizeOnDisk = size(s.sizeOnDisk), cache = s.cacheBytes.takeIf { it > 0 }?.let(size), onDelete = onDelete)
        DownloadPhase.DOWNLOADING, DownloadPhase.VERIFYING, DownloadPhase.WAITING_FOR_NETWORK ->
            ReaderInProgress(s, size, allowMetered, notificationsOff, onPause, onCancel)
        DownloadPhase.NONE, DownloadPhase.FAILED ->
            ReaderIdleOrFailed(s, size, onDownload, onCancel)
    }
    // Which model reads bags, where the device has its own AI. Off keeps the device default.
    if (onPreferReader != null) {
        Spacer(Modifier.height(10.dp))
        SwitchRow(title = stringResource(R.string.reader_prefer), hint = stringResource(R.string.reader_prefer_hint),
            checked = preferReader, onChange = onPreferReader)
    }
    // Once the model is here there's nothing left to download, so the network rule goes away.
    if (s.phase == DownloadPhase.READY) return
    Spacer(Modifier.height(10.dp))
    // One stop for TalkBack: "Off. Download over mobile data. Uses mobile data when there's no Wi-Fi. Switch".
    SwitchRow(title = stringResource(R.string.reader_allow_mobile), hint = stringResource(R.string.reader_allow_mobile_hint),
        checked = allowMetered, onChange = onAllowMetered)
}

/** What the reader is, what it costs to download, and a note when the device's own AI has been failing. */
@Composable
private fun ReaderIntro(totalSize: String, nanoFailing: Boolean) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    if (nanoFailing) {
        Text(stringResource(R.string.reader_nano_failing), style = MaterialTheme.typography.bodySmall, color = secondary)
        Spacer(Modifier.height(6.dp))
    }
    Text(stringResource(R.string.reader_explainer, totalSize), style = MaterialTheme.typography.bodyMedium, color = secondary)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.reader_model_credit), style = MaterialTheme.typography.bodySmall, color = secondary)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.reader_speed_note), style = MaterialTheme.typography.bodySmall, color = secondary)
    Spacer(Modifier.height(12.dp))
}

/**
 * The model is downloaded and checked: its size on disk and Delete. Once the reader has run, the
 * size includes the cache its runtime saved, and the line says how much of it that is, so the number
 * is no surprise next to the download size. Delete removes both.
 */
@Composable
private fun ReaderReady(sizeOnDisk: String, cache: String?, onDelete: () -> Unit) {
    Text(
        if (cache == null) stringResource(R.string.reader_ready, sizeOnDisk) else stringResource(R.string.reader_ready_cache, sizeOnDisk, cache),
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp).focusRing(CircleShape)) { Text(stringResource(R.string.reader_delete)) }
}

/** Downloading, checking, or waiting for a network: the progress line and bar, then Pause and Cancel. */
@Composable
private fun ReaderInProgress(
    s: ReaderState, size: (Long) -> String, allowMetered: Boolean, notificationsOff: Boolean,
    onPause: () -> Unit, onCancel: () -> Unit,
) {
    val label = readerProgressText(s.copy(allowMetered = allowMetered), size)
    // A polite live region: TalkBack hears "Checking the download…" and the like as they change.
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    ReaderProgressBar(s, paused = false, label = label)
    if (notificationsOff) {
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.reader_notif_denied),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    // Pause keeps what's downloaded (Resume continues from there); Cancel throws it away.
    // FlowRow: at large text sizes the second button moves below instead of squeezing to two lines.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (s.phase != DownloadPhase.VERIFYING) {
            OutlinedButton(onClick = onPause, modifier = Modifier.heightIn(min = 48.dp).focusRing(CircleShape)) { Text(stringResource(R.string.reader_pause)) }
        }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).focusRing(CircleShape)) { Text(stringResource(R.string.reader_cancel)) }
    }
}

/** Not downloaded, paused part-way, or failed: why it failed, how far it got, and Download, Resume or Retry. */
@Composable
private fun ReaderIdleOrFailed(
    s: ReaderState, size: (Long) -> String, onDownload: () -> Unit, onCancel: () -> Unit,
) {
    s.failure?.let { f ->
        Text(
            readerFailureText(f, size(s.totalBytes)),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            // Heard when a download or its check ends this way while the person waits on the page.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(8.dp))
    }
    if (s.downloadedBytes > 0 && s.failure == null) {
        Text(
            stringResource(R.string.reader_partial, size(s.downloadedBytes), size(s.totalBytes)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp).focusRing(CircleShape, onFill = true)) {
            Text(
                when {
                    s.failure != null -> stringResource(R.string.reader_retry)
                    s.downloadedBytes > 0 -> stringResource(R.string.reader_resume)
                    else -> stringResource(R.string.reader_download, size(s.totalBytes))
                },
            )
        }
        if (s.downloadedBytes > 0) {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).focusRing(CircleShape)) { Text(stringResource(R.string.reader_cancel)) }
        }
    }
}
