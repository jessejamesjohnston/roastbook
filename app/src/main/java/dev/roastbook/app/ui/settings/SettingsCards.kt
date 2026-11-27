package dev.roastbook.app.ui.settings

import dev.roastbook.app.ui.components.PermissionAskState
import dev.roastbook.app.ui.components.PermissionAsks
import dev.roastbook.app.ui.components.PermissionAsk
import dev.roastbook.app.ui.components.NOTIFICATION_PERMISSION
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import androidx.compose.ui.semantics.error
import androidx.compose.foundation.shape.CircleShape
import dev.roastbook.app.ui.components.focusRing
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import dev.roastbook.app.ui.components.ChoiceChip
import dev.roastbook.app.ui.components.ChoiceChipDefaults
import dev.roastbook.app.util.englishPlural
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
            OutlinedButton(modifier = Modifier.focusRing(CircleShape), onClick = { pickFolder.launch(null) }, enabled = !busy) {
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
                englishPlural(R.plurals.coffees, state.lastBackupCoffees ?: 0),
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
                englishPlural(R.plurals.settings_backup_keeps, state.keepCount),
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
            Button(modifier = Modifier.focusRing(CircleShape, onFill = true), onClick = { vm.backUpNow() }, enabled = state.folderUri != null && !busy) {
                if (ui.working == BackupUi.Working.BACKUP) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_backing_up))
                } else Text(stringResource(R.string.settings_back_up_now))
            }
            TextButton(
                modifier = Modifier.focusRing(CircleShape),
                onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                enabled = !busy,
            ) {
                Text(stringResource(if (ui.working == BackupUi.Working.RESTORE) R.string.settings_restoring else R.string.settings_restore))
            }
        }
        ui.message?.let { m -> ResultLine(m) }
    }
    // What Android's own backup carries (data_extraction_rules.xml), under the card, in the muted color.
    Text(
        stringResource(R.string.settings_android_backup_note), style = MaterialTheme.typography.bodyMedium, color = muted,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
    )
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
        // A refused key is said under the field, as the field's own error, and clears once it is edited.
        val refusal = cloud.message?.takeIf { it.isError }?.let { stringResource(it.res, *it.args.toTypedArray()) }
        OutlinedTextField(
            value = keyDraft, onValueChange = { keyDraft = it; vm.clearCloudMessage() }, singleLine = true,
            label = { Text(stringResource(R.string.settings_cloud_key_hint)) },
            isError = refusal != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().semantics { if (refusal != null) error(refusal) },
        )
        if (refusal != null) {
            Text(
                refusal, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(modifier = Modifier.focusRing(CircleShape, onFill = true), onClick = { vm.saveKey(keyDraft); keyDraft = "" }, enabled = keyDraft.isNotBlank()) {
                Text(stringResource(R.string.settings_cloud_save))
            }
            if (cloud.hasKey) {
                OutlinedButton(modifier = Modifier.focusRing(CircleShape), onClick = { vm.testKey() }, enabled = !cloud.testing) {
                    Text(stringResource(if (cloud.testing) R.string.settings_cloud_testing else R.string.settings_cloud_test))
                }
                TextButton(modifier = Modifier.focusRing(CircleShape), onClick = { vm.removeKey() }) { Text(stringResource(R.string.settings_cloud_remove)) }
            }
        }
        // A good result answers in place, under the buttons; a refusal sits under the field above.
        cloud.message?.takeIf { !it.isError }?.let { m -> ResultLine(m, top = 6) }
        if (cloud.hasKey) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = modelDraft, onValueChange = { modelDraft = it }, singleLine = true,
                label = { Text(stringResource(R.string.settings_cloud_model)) },
                supportingText = { Text(stringResource(R.string.settings_cloud_model_hint)) },
                trailingIcon = {
                    if (modelDraft != settings.geminiModel && modelDraft.isNotBlank()) {
                        TextButton(modifier = Modifier.focusRing(CircleShape), onClick = { vm.setModel(modelDraft) }) { Text(stringResource(R.string.detail_save)) }
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
            hint = englishPlural(R.plurals.settings_second_look_hint, settings.secondLookDays),
            checked = settings.secondLookEnabled, onChange = { vm.setSecondLookEnabled(it) },
        )
        if (settings.secondLookEnabled) {
            Spacer(Modifier.height(6.dp))
            // One choice of several: TalkBack reads them as radio buttons, not check boxes.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ChoiceChipDefaults.spacing),
                verticalArrangement = Arrangement.spacedBy(ChoiceChipDefaults.spacing),
                modifier = Modifier.selectableGroup(),
            ) {
                UserSettings.SECOND_LOOK_CHOICES.forEach { d ->
                    ChoiceChip(selected = settings.secondLookDays == d, onClick = { vm.setSecondLookDays(d) },
                        label = englishPlural(R.plurals.settings_days, d))
                }
            }
        }
    }
}

/** The parts that need notifications and are in use, each named for the row's sentence. */
internal data class NotificationNeeds(val reminders: Boolean, val updateNotice: Boolean, val download: Boolean) {
    val any: Boolean get() = reminders || updateNotice || download
}

/**
 * When notifications are off and the person said no to Android or Not now to a reason (Android
 * 13+), a tonal row says what can't show, naming whichever parts are in use (reminders, the update
 * notice, the download's progress), with the way back: "Allow notifications" opens Android's
 * dialog while it still asks; once refused for good, "Open Settings" opens the app's page there.
 * Nothing shows before any answer, once allowed, or when nothing needs notifications.
 */
@Composable
internal fun NotificationsOffRow(needs: NotificationNeeds, ask: PermissionAskState) {
    if (Build.VERSION.SDK_INT < 33) return
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> ask.answered(granted) }
    val context = LocalContext.current
    val answered = ask.value == PermissionAsk.ASK_AGAIN || ask.value == PermissionAsk.BLOCKED ||
        (ask.value == PermissionAsk.FIRST_ASK && ask.declined)
    if (!answered || !needs.any) return
    val names = buildList {
        if (needs.reminders) add(stringResource(R.string.notif_off_reminders))
        if (needs.updateNotice) add(stringResource(R.string.notif_off_update))
        if (needs.download) add(stringResource(R.string.notif_off_download))
    }
    val list = when (names.size) {
        1 -> names[0]
        2 -> stringResource(R.string.list_two, names[0], names[1])
        else -> stringResource(R.string.list_three, names[0], names[1], names[2])
    }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.settings_notifications_off, list), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f),
        )
        if (ask.value == PermissionAsk.BLOCKED) {
            TextButton(modifier = Modifier.focusRing(CircleShape), onClick = { PermissionAsks.openAppSettings(context) }) {
                Text(stringResource(R.string.permission_open_settings))
            }
        } else {
            TextButton(modifier = Modifier.focusRing(CircleShape), onClick = { ask.markAsked(); request.launch(NOTIFICATION_PERMISSION) }) {
                Text(stringResource(R.string.settings_allow_notifications))
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
