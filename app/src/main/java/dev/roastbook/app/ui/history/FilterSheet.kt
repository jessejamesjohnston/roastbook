package dev.roastbook.app.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import dev.roastbook.app.data.db.DateRange
import dev.roastbook.app.data.db.HistoryFilter
import dev.roastbook.app.data.db.SourceEntity

/**
 * The filter bottom sheet: unrated and buy-again switches, one picker per field with the values the
 * journal actually holds, the logged-date range, and Clear while anything is on. Every change
 * applies at once; there is no Apply button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    filter: HistoryFilter, sources: List<SourceEntity>, roasters: List<String>, origins: List<String>, processes: List<String>,
    vm: HistoryViewModel, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.filter_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            FilterRow(stringResource(R.string.filter_unrated), filter.unratedOnly) { vm.toggleUnrated() }
            FilterRow(stringResource(R.string.filter_buy_again), filter.buyAgainOnly) { vm.toggleBuyAgain() }
            Spacer(Modifier.height(8.dp))
            PickerRow(stringResource(R.string.filter_source), sources.firstOrNull { it.id == filter.sourceId }?.name,
                sources.map { it.id to it.name }) { vm.setSource(it) }
            PickerRow(stringResource(R.string.field_roaster), filter.roaster, roasters.map { it to it }) { vm.setRoaster(it) }
            PickerRow(stringResource(R.string.field_origin_country), filter.originCountry, origins.map { it to it }) { vm.setOrigin(it) }
            PickerRow(stringResource(R.string.field_process), filter.process, processes.map { it to it }) { vm.setProcess(it) }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.filter_logged), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(6.dp))
            // One choice of several: TalkBack reads them as radio buttons, not check boxes.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                val ranges = DateRange.entries
                items(ranges.size) { i ->
                    val r = ranges[i]
                    FilterChip(selected = filter.dateRange == r, onClick = { vm.setDateRange(r) },
                        label = { Text(stringResource(r.label())) },
                        modifier = Modifier.semantics { role = Role.RadioButton })
                }
            }
            if (filter.isActive) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { vm.clearFilters() }) { Text(stringResource(R.string.filter_clear)) }
            }
        }
    }
}

/** The label for a logged-date range, shared by the sheet's chips and the list's filter line. */
internal fun DateRange.label(): Int = when (this) {
    DateRange.ANY -> R.string.filter_date_any
    DateRange.DAYS_30 -> R.string.filter_date_30
    DateRange.DAYS_90 -> R.string.filter_date_90
    DateRange.THIS_YEAR -> R.string.filter_date_year
}

/** A "pick one of what you have" row: label, current value or Any, a dropdown of the live values. */
@Composable
private fun PickerRow(label: String, current: String?, options: List<Pair<String, String>>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = options.isNotEmpty(), role = Role.DropdownList) { open = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Nothing to pick yet (no coffee has a roaster, say): muted and "None yet", so the row
            // doesn't look like a control that ignores the tap.
            val empty = options.isEmpty()
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(
                if (empty) stringResource(R.string.filter_none_yet) else current ?: stringResource(R.string.filter_any),
                style = MaterialTheme.typography.bodyMedium,
                color = if (current != null && !empty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.filter_any)) }, onClick = { onPick(null); open = false })
            options.forEach { (value, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(value); open = false })
            }
        }
    }
}

/** A filter toggle row: the whole row is the tap target, and the off state is visibly a switch. */
@Composable
private fun FilterRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}
