package dev.roastbook.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import dev.roastbook.app.data.backup.BackupOff
import dev.roastbook.app.data.backup.NO_SPACE_ERROR
import dev.roastbook.app.data.prefs.UserSettings

/** A settings section: its label above the card (like every Merriment Labs app), then the card. */
@Composable
internal fun SettingsCard(title: String, content: @Composable () -> Unit) {
    SectionLabel(title)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
    ) {
        content()
    }
}

/** Backup: the folder, the last result, the daily switch, Back up now and Restore. */
@Composable
internal fun BackupCard(vm: SettingsViewModel) {
    val state by vm.state.collectAsState()
    val ui by vm.ui.collectAsState()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.folderPicked(uri) }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.restorePicked(uri) }
    val busy = ui.working != BackupUi.Working.NONE
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    SettingsCard(title = stringResource(R.string.settings_backup)) {
        Text(stringResource(R.string.settings_backup_explainer), style = MaterialTheme.typography.bodyMedium, color = muted)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // One TalkBack stop, "Backup folder, Not set", before the button (read apart, the
            // button came between the label and its value).
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.settings_backup_folder), style = MaterialTheme.typography.labelMedium, color = muted)
                Text(ui.folderName ?: stringResource(R.string.settings_backup_folder_none), style = MaterialTheme.typography.bodyLarge)
            }
            OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = !busy) {
                Text(stringResource(if (state.folderUri == null) R.string.settings_choose_folder else R.string.settings_change_folder))
            }
        }
        // The folder stopped working and Roastbook forgot it: say plainly that nothing is being backed up.
        state.off?.let { off ->
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (off == BackupOff.FOLDER_REFUSED) R.string.settings_backup_off_refused else R.string.settings_backup_off_gone),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(12.dp))
        val last = state.lastBackupAt
        Text(
            if (last == null) stringResource(R.string.settings_last_backup_never)
            else stringResource(
                R.string.settings_last_backup,
                SettingsViewModel.lastBackupWhen(
                    last, System.currentTimeMillis(),
                    justNow = stringResource(R.string.settings_last_backup_just_now),
                    yesterday = stringResource(R.string.settings_last_backup_yesterday),
                ),
                SettingsViewModel.humanBytes(state.lastBackupBytes ?: 0),
                pluralStringResource(R.plurals.coffees, state.lastBackupCoffees ?: 0, state.lastBackupCoffees ?: 0),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        // A failure that may pass (the folder is still usable) stays up until the next success;
        // while a result from Back up now is showing, that result says it instead.
        state.lastError?.takeIf { ui.message == null }?.let { error ->
            val reason = if (error.startsWith(NO_SPACE_ERROR)) R.string.error_backup_full else R.string.error_backup_io
            Text(
                stringResource(R.string.settings_last_backup_failed, stringResource(reason)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        // Rotation happens in a folder; with none chosen there is nothing to keep.
        if (state.folderUri != null) {
            Text(
                pluralStringResource(R.plurals.settings_backup_keeps, state.keepCount, state.keepCount),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        // Off and unavailable until there's a folder; choosing one switches it on.
        SwitchRow(
            title = stringResource(R.string.settings_daily_backup),
            hint = stringResource(if (state.folderUri == null) R.string.settings_daily_backup_needs_folder else R.string.settings_daily_backup_hint),
            checked = state.scheduled, enabled = state.folderUri != null && !busy, onChange = { vm.setScheduled(it) },
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { vm.backUpNow() }, enabled = state.folderUri != null && !busy) {
                if (ui.working == BackupUi.Working.BACKUP) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_backing_up))
                } else Text(stringResource(R.string.settings_back_up_now))
            }
            TextButton(
                onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                enabled = !busy,
            ) {
                Text(stringResource(if (ui.working == BackupUi.Working.RESTORE) R.string.settings_restoring else R.string.settings_restore))
            }
        }
        ui.message?.let { m -> ResultLine(m) }
    }
}

/** Cloud AI: the user's own Gemini key (save, test, remove) and, with a key, the model name. */
@Composable
internal fun CloudCard(vm: SettingsViewModel, settings: UserSettings) {
    val cloud by vm.cloud.collectAsState()
    var keyDraft by remember { mutableStateOf("") }
    var modelDraft by remember(settings.geminiModel) { mutableStateOf(settings.geminiModel) }

    SettingsCard(title = stringResource(R.string.settings_cloud)) {
        Text(
            stringResource(R.string.settings_cloud_explainer),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (cloud.hasKey) {
                stringResource(R.string.settings_cloud_key_saved, cloud.tail ?: "????")
            } else {
                stringResource(R.string.settings_cloud_key_none)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = keyDraft, onValueChange = { keyDraft = it }, singleLine = true,
            label = { Text(stringResource(R.string.settings_cloud_key_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.saveKey(keyDraft); keyDraft = "" }, enabled = keyDraft.isNotBlank()) {
                Text(stringResource(R.string.settings_cloud_save))
            }
            if (cloud.hasKey) {
                OutlinedButton(onClick = { vm.testKey() }, enabled = !cloud.testing) {
                    Text(stringResource(if (cloud.testing) R.string.settings_cloud_testing else R.string.settings_cloud_test))
                }
                TextButton(onClick = { vm.removeKey() }) { Text(stringResource(R.string.settings_cloud_remove)) }
            }
        }
        cloud.message?.let { m -> ResultLine(m, top = 6) }
        if (cloud.hasKey) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = modelDraft, onValueChange = { modelDraft = it }, singleLine = true,
                label = { Text(stringResource(R.string.settings_cloud_model)) },
                supportingText = { Text(stringResource(R.string.settings_cloud_model_hint)) },
                trailingIcon = {
                    if (modelDraft != settings.geminiModel && modelDraft.isNotBlank()) {
                        TextButton(onClick = { vm.setModel(modelDraft) }) { Text(stringResource(R.string.detail_save)) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Reminders: the second-look switch and, when on, how many days after rating. */
@Composable
internal fun RemindersCard(vm: SettingsViewModel, settings: UserSettings) {
    SettingsCard(title = stringResource(R.string.settings_reminders)) {
        Text(
            stringResource(R.string.settings_reminders_explainer),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = stringResource(R.string.settings_second_look),
            hint = pluralStringResource(
                R.plurals.settings_second_look_hint,
                settings.secondLookDays,
                settings.secondLookDays,
            ),
            checked = settings.secondLookEnabled, onChange = { vm.setSecondLookEnabled(it) },
        )
        if (settings.secondLookEnabled) {
            Spacer(Modifier.height(6.dp))
            // One choice of several: TalkBack reads them as radio buttons, not check boxes.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                UserSettings.SECOND_LOOK_CHOICES.forEach { d ->
                    FilterChip(
                        selected = settings.secondLookDays == d, onClick = { vm.setSecondLookDays(d) },
                        label = { Text(pluralStringResource(R.plurals.settings_days, d, d)) },
                        modifier = Modifier.semantics { role = Role.RadioButton },
                    )
                }
            }
        }
    }
}

/** Photos: whether the square crops are also saved to the system photo library. */
@Composable
internal fun PhotosCard(vm: SettingsViewModel, settings: UserSettings) {
    SettingsCard(title = stringResource(R.string.settings_photos)) {
        SwitchRow(
            title = stringResource(R.string.settings_save_squares), hint = stringResource(R.string.settings_save_squares_hint),
            checked = settings.saveSquaresToPhotos, onChange = { vm.setSaveSquares(it) },
        )
    }
}

/** The outcome of the last action in a card, in the error color when it failed. */
@Composable
private fun ResultLine(m: BackupUi.Message, top: Int = 8) {
    Spacer(Modifier.height(top.dp))
    Text(
        stringResource(m.res, *m.args.toTypedArray()), style = MaterialTheme.typography.bodyMedium,
        color = if (m.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}
