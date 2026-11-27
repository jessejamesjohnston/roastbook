package dev.roastbook.app.ui.settings

import androidx.compose.foundation.shape.CircleShape
import dev.roastbook.app.ui.components.focusRing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import dev.roastbook.app.data.prefs.ThemeMode
import dev.roastbook.app.data.prefs.UserSettings
import dev.roastbook.app.ui.theme.AppLanguage
import dev.roastbook.app.ui.theme.RowTitleStyle

/** Settings > Appearance: theme (System default), high contrast, and the per-app language. */
@Composable
fun AppearanceSection(settings: UserSettings, onThemeMode: (ThemeMode) -> Unit, onHighContrast: (Boolean) -> Unit) {
    val context = LocalContext.current
    var languagePicker by remember { mutableStateOf(false) }
    val language = remember(languagePicker) { AppLanguage.current(context) }

    Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    val modes = listOf(
        ThemeMode.SYSTEM to R.string.settings_theme_system,
        ThemeMode.LIGHT to R.string.settings_theme_light,
        ThemeMode.DARK to R.string.settings_theme_dark,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, (mode, label) ->
            val shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size)
            SegmentedButton(
                selected = settings.themeMode == mode,
                onClick = { onThemeMode(mode) },
                modifier = Modifier.focusRing(shape),
                shape = shape,
                label = { Text(stringResource(label)) },
            )
        }
    }
    Text(stringResource(R.string.settings_theme_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    Spacer(Modifier.height(12.dp))
    SwitchRow(
        title = stringResource(R.string.settings_high_contrast),
        hint = stringResource(R.string.settings_high_contrast_hint),
        checked = settings.highContrast,
        onChange = onHighContrast,
    )
    if (AppLanguage.available) {
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                .focusRing(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = stringResource(R.string.settings_language_change)) { languagePicker = true }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_language), style = RowTitleStyle)
                Text(
                    language?.let { AppLanguage.displayName(it) } ?: stringResource(R.string.settings_language_device),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (languagePicker) {
        AlertDialog(
            onDismissRequest = { languagePicker = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    val options = listOf<String?>(null) + AppLanguage.supported
                    options.forEach { tag ->
                        val selected = language == tag
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                                .focusRing(RoundedCornerShape(8.dp)).selectable(selected = selected, role = Role.RadioButton, onClick = {
                                    AppLanguage.set(context, tag); languagePicker = false
                                }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(tag?.let { AppLanguage.displayName(it) } ?: stringResource(R.string.settings_language_device),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (AppLanguage.supported.size == 1) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.settings_language_only_english), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(modifier = Modifier.focusRing(CircleShape), onClick = { languagePicker = false }) { Text(stringResource(R.string.detail_cancel)) } },
        )
    }
}

/**
 * A settings toggle: the whole row is one switch for touch, keyboard and TalkBack (it reads the
 * title, the hint and the on/off state together), and the row is at least 56 dp tall.
 */
@Composable
fun SwitchRow(title: String, hint: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .focusRing(RoundedCornerShape(12.dp)).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = RowTitleStyle,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A Settings section label, above its card: small, semibold, in the accent color, like every
 * Merriment Labs app. TalkBack users can jump between them with heading navigation.
 */
@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() })
}
