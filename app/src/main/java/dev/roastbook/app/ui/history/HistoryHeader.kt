package dev.roastbook.app.ui.history

import dev.roastbook.app.ui.components.ChipRingShape
import dev.roastbook.app.ui.components.focusRing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import dev.roastbook.app.ui.components.ChoiceChipDefaults
import dev.roastbook.app.util.englishPlural
import dev.roastbook.app.util.englishString
import dev.roastbook.app.data.backup.BackupNudge
import dev.roastbook.app.data.db.DateRange
import dev.roastbook.app.data.db.HistoryFilter
import dev.roastbook.app.data.db.HistorySort
import dev.roastbook.app.data.db.SourceEntity

/**
 * The top of the journal list: the title with its counter (or, while filtered, the filter line),
 * the filter and settings buttons, the backup banner when there is one, search, and the sort chips.
 */
@Composable
fun HistoryHeader(
    filter: HistoryFilter, sources: List<SourceEntity>, count: Int, unrated: Int, backupNudge: BackupNudge?,
    sort: HistorySort, descending: Boolean,
    onClearFilters: () -> Unit, onOpenFilters: () -> Unit, onSettings: () -> Unit,
    onSearch: (String) -> Unit, onSort: (HistorySort) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // One word on one line: at large text on a narrow phone it shrinks to fit rather than
            // breaking mid-word.
            val title = MaterialTheme.typography.headlineMedium
            Text(stringResource(R.string.app_name), style = title, maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = title.fontSize),
                modifier = Modifier.semantics { heading() })
            CounterOrFilterLine(filter, sources, count, unrated, onClearFilters)
        }
        val filtered = filter.isActive
        IconButton(
            onClick = onOpenFilters,
            modifier = Modifier.clip(CircleShape)
                .background(if (filtered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer).focusRing(CircleShape, onFill = true),
        ) {
            Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.history_filters),
                tint = if (filtered) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onSettings, modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).focusRing(CircleShape)) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.history_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    backupNudge?.let { nudge ->
        Spacer(Modifier.height(12.dp))
        BackupBanner(nudge, onSettings)
    }
    Spacer(Modifier.height(12.dp))
    TextField(
        value = filter.search, onValueChange = onSearch, singleLine = true,
        placeholder = { Text(stringResource(R.string.history_search_hint), style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        shape = RoundedCornerShape(24.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    SortChips(sort, descending, onSort)
    Spacer(Modifier.height(6.dp))
}

/** "12 coffees, 2 to rate", or, while any filter is on, what is showing and a Clear link. */
@Composable
private fun CounterOrFilterLine(filter: HistoryFilter, sources: List<SourceEntity>, count: Int, unrated: Int, onClearFilters: () -> Unit) {
    val active = listOfNotNull(
        stringResource(R.string.filter_unrated).takeIf { filter.unratedOnly },
        stringResource(R.string.filter_buy_again).takeIf { filter.buyAgainOnly },
        filter.sourceId?.let { id -> sources.firstOrNull { it.id == id }?.name },
        filter.roaster, filter.originCountry, filter.process,
        filter.dateRange.takeIf { it != DateRange.ANY }?.let { stringResource(it.label()) },
    )
    if (active.isEmpty()) {
        val coffees = englishPlural(R.plurals.coffees, count)
        Text(
            if (unrated > 0) englishString(R.string.history_count_to_rate, coffees, unrated) else coffees,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else Row(verticalAlignment = Alignment.CenterVertically) {
        // The counter line becomes the filter line: same slot, nothing below it moves.
        Text(stringResource(R.string.filter_showing, active.joinToString(" · ")),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.filter_clear_short), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp).minimumInteractiveComponentSize().clip(RoundedCornerShape(6.dp))
                .focusRing(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = stringResource(R.string.filter_clear)) { onClearFilters() }
                .padding(horizontal = 4.dp, vertical = 2.dp))
    }
}

/** The backup reminder above the list; tapping it opens Settings, where the fix is. */
@Composable
private fun BackupBanner(nudge: BackupNudge, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.tertiaryContainer)
            .focusRing(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { onSettings() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (nudge) {
                is BackupNudge.Off -> stringResource(R.string.banner_backup_off)
                is BackupNudge.NoFolder,
                is BackupNudge.NeverBackedUp -> stringResource(R.string.banner_no_folder)
                is BackupNudge.Stale -> englishPlural(R.plurals.banner_no_backup, nudge.days)
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        // Nothing can back up while backups are off: the action is choosing a folder, in Settings.
        val action = if (nudge is BackupNudge.Off) R.string.settings_choose_folder else R.string.banner_back_up
        Text(stringResource(action), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

/** One chip per sort; tapping the selected chip again flips its direction. */
@Composable
private fun SortChips(sort: HistorySort, descending: Boolean, onSort: (HistorySort) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
        val chips = listOf(
            HistorySort.RECENT to R.string.sort_recent, HistorySort.OVERALL to R.string.sort_overall,
            HistorySort.AROMA to R.string.sort_aroma, HistorySort.FLAVOR to R.string.sort_flavor,
            HistorySort.ACIDITY to R.string.sort_acidity, HistorySort.BODY to R.string.sort_body,
            HistorySort.FINISH to R.string.sort_finish, HistorySort.PRICE to R.string.sort_price,
        )
        items(chips.size) { i ->
            val (s, label) = chips[i]
            val labelText = stringResource(label)
            val direction = stringResource(
                if (s == HistorySort.RECENT) { if (descending) R.string.sort_descending else R.string.sort_ascending }
                else { if (descending) R.string.sort_value_descending else R.string.sort_value_ascending },
            )
            // TalkBack: one sort of several, so a radio button; the selected one names its
            // label before its direction ("Recent, newest first", "Price, highest first").
            FilterChip(
                selected = sort == s, onClick = { onSort(s) },
                label = { Text(labelText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clearAndSetSemantics {}) },
                leadingIcon = if (sort == s) ({
                    Icon(if (descending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward, null, Modifier.size(16.dp))
                }) else null,
                modifier = Modifier.semantics {
                    role = Role.RadioButton
                    contentDescription = if (sort == s) "$labelText, $direction" else labelText
                }.focusRing(ChipRingShape, onFill = sort == s),
                shape = RoundedCornerShape(8.dp),
                colors = ChoiceChipDefaults.colors(),
            )
        }
    }
}
