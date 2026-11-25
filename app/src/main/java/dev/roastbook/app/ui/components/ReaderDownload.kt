package dev.roastbook.app.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.roastbook.app.R
import dev.roastbook.app.reader.DownloadFailure
import dev.roastbook.app.reader.DownloadPhase
import dev.roastbook.app.reader.ReaderState

/**
 * Starts the reader's download the same way wherever it is offered (Settings, a coffee's page).
 * The download is a foreground job whose notification carries its progress and Pause. On Android
 * 13+ that notification shows only with the notification permission (the job runs either way), so
 * the first download that would need it says why in one sentence first ([explaining]); Continue
 * shows Android's dialog, and the download starts whatever the answer.
 */
class ReaderDownloadStarter internal constructor(
    private val needsExplaining: () -> Boolean,
    private val explainingState: androidx.compose.runtime.MutableState<String?>,
    private val ask: () -> Unit,
    private val run: (String) -> Unit,
    private val notificationsOffState: () -> Boolean,
) {
    /** True while the one-sentence explanation and Continue stand in for the download button. */
    val explaining: Boolean get() = explainingState.value != null

    /** Notifications are off, so the download's progress shows only in the app. */
    val notificationsOff: Boolean get() = notificationsOffState()

    /** Starts [action] (a name the caller's `onStart` knows), after the explanation when it is due. */
    fun start(action: String) {
        if (needsExplaining()) explainingState.value = action else run(action)
    }

    /** Continue under the explanation: Android's dialog, then the waiting action. */
    fun continueToAsk() = ask()
}

/**
 * Remembers a [ReaderDownloadStarter]. [onStart] runs the named action once the permission question
 * is settled. The waiting action is saved with the screen, so a rotation during the explanation keeps it.
 */
@Composable
fun rememberReaderDownloadStarter(onStart: (String) -> Unit): ReaderDownloadStarter {
    val context = LocalContext.current
    val currentOnStart by rememberUpdatedState(onStart)
    // Read from the permission itself, so the state survives the activity being recreated.
    var notifOff by remember { mutableStateOf(notificationsOff(context)) }
    val notifAsk = rememberPermissionAsk(NOTIFICATION_PERMISSION)
    val explaining = rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifOff = !granted
        notifAsk.answered(granted)
        val action = explaining.value
        explaining.value = null
        if (action != null) currentOnStart(action)
    }
    return remember(notifAsk, launcher) {
        ReaderDownloadStarter(
            needsExplaining = {
                notifAsk.refresh()
                Build.VERSION.SDK_INT >= 33 && notificationsOff(context) && notifAsk.value == PermissionAsk.FIRST_ASK
            },
            explainingState = explaining,
            ask = { notifAsk.markAsked(); launcher.launch(NOTIFICATION_PERMISSION) },
            run = { currentOnStart(it) },
            notificationsOffState = { notifOff },
        )
    }
}

private fun notificationsOff(context: Context) = Build.VERSION.SDK_INT >= 33 &&
    ContextCompat.checkSelfPermission(context, NOTIFICATION_PERMISSION) != PackageManager.PERMISSION_GRANTED

/** Why the last download failed, in the same words wherever the reader's state shows. */
@Composable
fun readerFailureText(failure: DownloadFailure?, totalSize: String): String = when (failure) {
    DownloadFailure.NO_SPACE -> stringResource(R.string.reader_failed_space, totalSize)
    DownloadFailure.CHECKSUM -> stringResource(R.string.reader_failed_checksum)
    DownloadFailure.SERVER, null -> stringResource(R.string.reader_failed_server)
}

/** The progress line of a download that is running, being checked, or waiting for a network. */
@Composable
fun readerProgressText(s: ReaderState, size: (Long) -> String): String = when {
    s.phase == DownloadPhase.VERIFYING -> stringResource(R.string.reader_verifying)
    // Waiting with a part on disk says how far it got, so a stalled download doesn't look lost.
    s.phase == DownloadPhase.WAITING_FOR_NETWORK && s.downloadedBytes > 0 -> stringResource(
        if (s.allowMetered) R.string.reader_waiting_network_at else R.string.reader_waiting_wifi_at,
        size(s.downloadedBytes), size(s.totalBytes),
    )
    s.phase == DownloadPhase.WAITING_FOR_NETWORK ->
        stringResource(if (s.allowMetered) R.string.reader_waiting_network else R.string.reader_waiting_wifi)
    else -> stringResource(R.string.reader_downloading, size(s.downloadedBytes), size(s.totalBytes))
}

/**
 * The download's bar, the same in Settings and on a coffee's page. It fills with the bytes while
 * the download runs (or stands still where it stopped while waiting for a network), and moves
 * without a value while there is no size to show: starting, checking the file, or the bag being
 * read once the reader is here. Paused and failed downloads draw no bar, so they don't look busy.
 * TalkBack hears the bar's value and [label].
 */
@Composable
fun ReaderProgressBar(s: ReaderState, paused: Boolean, label: String) {
    if (paused || s.phase == DownloadPhase.FAILED) return
    val known = s.totalBytes > 0 &&
        (s.phase == DownloadPhase.DOWNLOADING || s.phase == DownloadPhase.WAITING_FOR_NETWORK)
    Spacer(Modifier.height(8.dp))
    val modifier = Modifier.fillMaxWidth().semantics { stateDescription = label }
    if (known) {
        val fraction = (s.downloadedBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { fraction }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}
