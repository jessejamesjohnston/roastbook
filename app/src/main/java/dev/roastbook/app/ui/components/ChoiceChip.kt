package dev.roastbook.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The one look of a "pick one of several" chip: the sort chips, the date range, the reminder days. */
object ChoiceChipDefaults {
    /**
     * A selected chip's fill. Material's default (secondary container) is the same oat as the sheets
     * and cards these chips sit on, which left the selected chip looking like plain text.
     */
    @Composable
    fun selectedContainer(): Color = MaterialTheme.colorScheme.primary

    @Composable
    fun colors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = selectedContainer(),
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
    )

    /** The space between chips in a row and between rows of chips. */
    val spacing = 8.dp
}

/** One choice of several, read by TalkBack as a radio button. */
@Composable
fun ChoiceChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = RoundedCornerShape(8.dp),
        colors = ChoiceChipDefaults.colors(),
        modifier = modifier.semantics { role = Role.RadioButton }.focusRing(ChipRingShape, onFill = selected),
    )
}
