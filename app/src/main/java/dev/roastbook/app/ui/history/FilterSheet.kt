package dev.roastbook.app.ui.history

import androidx.compose.foundation.shape.CircleShape
import dev.roastbook.app.ui.components.focusRing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import dev.roastbook.app.ui.components.ChoiceChip
import dev.roastbook.app.ui.components.ChoiceChipDefaults
import dev.roastbook.app.data.db.DateRange
import dev.roastbook.app.data.db.HistoryFilter
import dev.roastbook.app.data.db.SourceEntity

/**
 * The filter bottom sheet: unrated and buy-again switches, one picker per field with the values the
 * journal actually holds, the logged-date range, and Clear while anything is on. Every change
 * applies at once; there is no Apply button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filter: HistoryFilter, sources: List<SourceEntity>, roasters: List<String>, origins: List<String>, processes: List<String>,
    vm: HistoryViewModel, onDismiss: () -> Unit,
) {
    // Opens all the way: half open, the sheet's scrolling column runs past the window's bottom edge,
    // so on a laptop window the date chips could not be brought into view. Taller than the window,
    // the column scrolls to its last row.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
            // The date row reads like every row above it; its choices are chips, "Any time" included.
            Text(stringResource(R.string.filter_logged), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 8.dp).semantics { heading() })
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ChoiceChipDefaults.spacing),
                verticalArrangement = Arrangement.spacedBy(ChoiceChipDefaults.spacing),
                modifier = Modifier.selectableGroup(),
            ) {
                DateRange.entries.forEach { r ->
                    ChoiceChip(selected = filter.dateRange == r, onClick = { vm.setDateRange(r) }, label = stringResource(r.label()))
                }
            }
            if (filter.isActive) {
                Spacer(Modifier.height(12.dp))
                TextButton(modifier = Modifier.focusRing(CircleShape), onClick = { vm.clearFilters() }) { Text(stringResource(R.string.filter_clear)) }
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
                .focusRing().clickable(enabled = options.isNotEmpty(), role = Role.DropdownList) { open = true }.padding(vertical = 8.dp),
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
            DropdownMenuItem(modifier = Modifier.focusRing(), text = { Text(stringResource(R.string.filter_any)) }, onClick = { onPick(null); open = false })
            options.forEach { (value, name) ->
                DropdownMenuItem(modifier = Modifier.focusRing(), text = { Text(name) }, onClick = { onPick(value); open = false })
            }
        }
    }
}

/** A filter toggle row: the whole row is the tap target, and the off state is visibly a switch. */
@Composable
private fun FilterRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .focusRing().toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }).padding(vertical = 6.dp),
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
