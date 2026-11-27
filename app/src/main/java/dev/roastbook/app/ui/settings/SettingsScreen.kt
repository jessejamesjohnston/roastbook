package dev.roastbook.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.roastbook.app.R
import dev.roastbook.app.util.englishPlural

/** Settings: one card per topic (backup, cloud AI, on-device reader, reminders, photos, appearance), then About. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLicenses: () -> Unit = {},
    onReplayIntro: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
    // A slot so tests can render the screen without Hilt (the reader section builds its own ViewModel).
    readerSection: @Composable () -> Unit = { LocalReaderSection() },
) {
    val context = LocalContext.current
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    val settings by vm.settings.collectAsState()
    val ui by vm.ui.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.detail_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        // On a tablet the cards keep a readable measure instead of stretching edge to edge.
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                BackupCard(vm)
                CloudCard(vm, settings)
                readerSection()
                RemindersCard(vm, settings)
                PhotosCard(vm, settings)
                SettingsCard(title = stringResource(R.string.settings_appearance)) {
                    AppearanceSection(settings, onThemeMode = vm::setThemeMode, onHighContrast = vm::setHighContrast)
                }
                val update by vm.update.collectAsState()
                AboutCard(
                    version, onLicenses, onReplayIntro = onReplayIntro, update = update, weeklyCheck = settings.weeklyUpdateCheck,
                    onCheckForUpdates = { vm.checkForUpdates(version) }, onUpdatePageOpened = vm::updatePageOpened,
                    onWeeklyCheck = vm::setWeeklyUpdateCheck,
                )
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
            confirmButton = { TextButton(onClick = { vm.confirmRestore() }) { Text(stringResource(R.string.settings_restore_yes)) } },
            dismissButton = { TextButton(onClick = { vm.cancelRestore() }) { Text(stringResource(R.string.detail_cancel)) } },
        )
    }
}
