package dev.roastbook.app.ui.settings

import androidx.compose.foundation.shape.CircleShape
import dev.roastbook.app.ui.components.focusRing
import dev.roastbook.app.ui.components.initialFocus
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.roastbook.app.R
import dev.roastbook.app.ui.components.NOTIFICATION_PERMISSION
import dev.roastbook.app.ui.components.rememberPermissionAsk
import dev.roastbook.app.util.englishPlural

/** Settings: one card per topic (backup, cloud AI, on-device reader, reminders, photos, appearance), then About. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLicenses: () -> Unit = {},
    onReplayIntro: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
    // A slot so tests can render the screen without Hilt (the reader section builds its own ViewModel).
    readerSection: @Composable () -> Unit = { LocalReaderSection() },
    // Whether the reader download is running, for the notifications row (a slot for the same reason).
    downloadActive: @Composable () -> Boolean = { readerDownloadActive() },
) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    val settings by vm.settings.collectAsState()
    val ui by vm.ui.collectAsState()
    // One record of the notification ask, shared by the row at the top and About's Check weekly.
    val notifAsk = rememberPermissionAsk(NOTIFICATION_PERMISSION)

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        // One centered column, at most 720dp with its side padding inside (24dp, 16dp under 600dp):
        // the bar, the title and the cards share it, so nothing hugs the window's edge on a laptop.
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val side = if (maxWidth < 600.dp) 16.dp else 24.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = side).padding(top = 16.dp, bottom = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(modifier = Modifier.focusRing(CircleShape), onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.detail_back))
                        }
                        Text(
                            stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = 8.dp).semantics { heading() },
                        )
                    }
                    val pendingReminder by vm.anyPendingReminder.collectAsState()
                    NotificationsOffRow(
                        NotificationNeeds(
                            reminders = settings.secondLookEnabled || pendingReminder,
                            updateNotice = settings.weeklyUpdateCheck,
                            download = downloadActive(),
                        ),
                        notifAsk,
                    )
                    BackupCard(vm)
                    CloudCard(vm, settings)
                    readerSection()
                    RemindersCard(vm, settings)
                    PhotosCard(vm, settings)
                    SettingsCard(title = stringResource(R.string.settings_appearance)) {
                        AppearanceSection(settings, onThemeMode = vm::setThemeMode, onHighContrast = vm::setHighContrast)
                    }
                    val update by vm.update.collectAsState()
                    ReplayIntroCard(onReplayIntro)
                    AboutCard(
                        version, onLicenses, update = update, weeklyCheck = settings.weeklyUpdateCheck,
                        onCheckForUpdates = { vm.checkForUpdates(version) }, onUpdatePageOpened = vm::updatePageOpened,
                        onWeeklyCheck = vm::setWeeklyUpdateCheck, notifAsk = notifAsk,
                    )
                }
            }
        }
    }

    ui.pendingRestore?.let { p ->
        val date = SettingsViewModel.backupMadeAt(p.manifest.createdAt)
        val coffees = englishPlural(R.plurals.coffees, p.manifest.coffees)
        val photos = englishPlural(R.plurals.photos, p.manifest.photos)
        AlertDialog(
            onDismissRequest = { vm.cancelRestore() },
            title = { Text(stringResource(R.string.settings_restore_title)) },
            text = { Text(stringResource(R.string.settings_restore_body, date, coffees, photos)) },
            confirmButton = { TextButton(modifier = Modifier.focusRing(CircleShape), onClick = { vm.confirmRestore() }) { Text(stringResource(R.string.settings_restore_yes)) } },
            dismissButton = { TextButton(modifier = Modifier.initialFocus().focusRing(CircleShape), onClick = { vm.cancelRestore() }) { Text(stringResource(R.string.detail_cancel)) } },
        )
    }
}
