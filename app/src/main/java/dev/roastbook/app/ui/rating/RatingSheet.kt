package dev.roastbook.app.ui.rating

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.ui.theme.NotesStyle
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay

/**
 * The rating flow as a bottom sheet (scrim over whatever is behind). On Save the sheet shows the
 * reaction line for a beat, then closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingSheet(coffee: CoffeeEntity, accent: Color, onSave: (RatingDraft) -> Unit, onMore: (RatingDraft) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var draft by androidx.compose.runtime.saveable.rememberSaveable(coffee.id, stateSaver = RatingDraft.Saver) {
        mutableStateOf(RatingDraft.from(coffee))
    }
    var reaction by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reaction) { if (reaction != null) { delay(1400); onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(coffee.coffeeName ?: coffee.roaster ?: stringResource(R.string.detail_untitled), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.rate_title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (reaction != null) {
                Text(
                    reaction!!,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            } else {
                RatingEditor(draft, accent, onChange = { draft = it }, showNotes = false, showProfile = false)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Saves whatever is entered so far, then opens the Detail editor (profile + notes).
                    TextButton(onClick = { onMore(draft) }) { Text(stringResource(R.string.rate_more)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.detail_cancel)) }
                    Button(enabled = draft.complete, onClick = {
                        onSave(draft)
                        reaction = Reactions.forOverall(context, draft.overall ?: 3f)
                    }) { Text(stringResource(R.string.rate_save)) }
                }
            }
        }
    }
}

/** Read-only rating: score rows and profile rows on one grid, a buy-again pill, notes under a header. */
@Composable
fun RatingSummary(c: CoffeeEntity, accent: Color) {
    val secondary = dev.roastbook.app.ui.theme.onTintVariant()
    @Composable fun row(label: Int, v: Int?, ends: Pair<Int, Int>? = null) {
        val state = if (v == null) stringResource(R.string.rate_state_unset) else stringResource(R.string.rate_value, v)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) { stateDescription = state },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (ends != null) {
                Text(stringResource(ends.first), style = MaterialTheme.typography.labelSmall, color = secondary)
                Spacer(Modifier.width(6.dp))
            }
            dev.roastbook.app.ui.components.RatingDots((v ?: 0).toFloat(), accent, 9.dp)
            if (ends != null) {
                Spacer(Modifier.width(6.dp))
                Text(stringResource(ends.second), style = MaterialTheme.typography.labelSmall, color = secondary)
            }
        }
    }
    Column {
        row(R.string.sort_aroma, c.aroma)
        row(R.string.sort_flavor, c.flavor)
        row(R.string.sort_finish, c.finish)
        if (c.acidity != null || c.body != null) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.rate_profile), style = MaterialTheme.typography.labelMedium, color = secondary)
            Spacer(Modifier.height(4.dp))
            if (c.acidity != null) row(R.string.sort_acidity, c.acidity, R.string.profile_acidity_low to R.string.profile_acidity_high)
            if (c.body != null) row(R.string.sort_body, c.body, R.string.profile_body_light to R.string.profile_body_full)
        }
        if (c.buyAgain) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                    .background(accent.copy(alpha = 0.18f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(Icons.Default.Check, null, Modifier.padding(end = 4.dp).size(14.dp), tint = accent)
                Text(stringResource(R.string.rate_buy_again), style = MaterialTheme.typography.labelMedium, color = accent)
            }
        }
        if (!c.notes.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.detail_notes_header), style = MaterialTheme.typography.labelMedium, color = secondary)
            Spacer(Modifier.height(4.dp))
            Text(c.notes, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
