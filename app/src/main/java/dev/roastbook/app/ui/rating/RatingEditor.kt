package dev.roastbook.app.ui.rating

import androidx.compose.ui.semantics.heading
import dev.roastbook.app.ui.components.ChipRingShape
import dev.roastbook.app.ui.components.focusRing
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.roastbook.app.R
import dev.roastbook.app.util.English
import dev.roastbook.app.util.englishString
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.ui.theme.RatingStyle

/** The five SCA-lite dimensions plus buy-again and notes. Null = not chosen yet. */
data class RatingDraft(
    val aroma: Int? = null,
    val flavor: Int? = null,
    val acidity: Int? = null,
    val body: Int? = null,
    val finish: Int? = null,
    val buyAgain: Boolean = false,
    val notes: String = "",
) {
    val values: List<Int?> get() = listOf(aroma, flavor, acidity, body, finish)
    /** Saveable once the three score dimensions are in; the profile is optional. */
    val complete: Boolean get() = aroma != null && flavor != null && finish != null
    /** Score = mean of the valenced dimensions only; acidity and body describe, they don't judge. */
    val overall: Float? get() = listOf(aroma, flavor, finish).takeIf { l -> l.all { it != null } }?.map { it!! }?.average()?.toFloat()

    companion object {
        fun from(c: CoffeeEntity) = RatingDraft(c.aroma, c.flavor, c.acidity, c.body, c.finish, c.buyAgain, c.notes ?: "")

        /** Keeps a half-entered rating across a rotation or a fold (0 stands for "not chosen"). */
        val Saver: androidx.compose.runtime.saveable.Saver<RatingDraft, Any> = androidx.compose.runtime.saveable.listSaver(
            save = { d -> listOf(d.aroma ?: 0, d.flavor ?: 0, d.acidity ?: 0, d.body ?: 0, d.finish ?: 0, if (d.buyAgain) 1 else 0, d.notes) },
            restore = { l ->
                fun dim(i: Int) = (l[i] as Int).takeIf { it in 1..5 }
                RatingDraft(dim(0), dim(1), dim(2), dim(3), dim(4), l[5] as Int == 1, l[6] as String)
            },
        )
    }
}

/** Finite, rotating reactions; never the same string twice in a row. */
object Reactions {
    private var last: String? = null
    fun forOverall(context: Context, overall: Float): String {
        val arrayRes = when (Math.round(overall)) {
            1 -> R.array.rating_react_1; 2 -> R.array.rating_react_2; 3 -> R.array.rating_react_3
            4 -> R.array.rating_react_4; else -> R.array.rating_react_5
        }
        val options = context.resources.getStringArray(arrayRes).filter { it != last }
        return options.random().also { last = it }
    }
}

/**
 * The same grid as [RatingSummary], live. [embedded] = inside the Detail card (no Score / Overall
 * lines: the card header carries them); the sheet shows both. [afterScores] goes right under the
 * three score rows (the Detail card's note on what is missing).
 */
@Composable
fun RatingEditor(
    draft: RatingDraft,
    accent: Color,
    onChange: (RatingDraft) -> Unit,
    showNotes: Boolean = true,
    showProfile: Boolean = true,
    embedded: Boolean = false,
    afterScores: @Composable () -> Unit = {},
) {
    val secondary = dev.roastbook.app.ui.theme.onTintVariant()
    Column {
        if (!embedded) Text(stringResource(R.string.rate_score), style = MaterialTheme.typography.labelMedium, color = secondary, modifier = Modifier.semantics { heading() })
        DimRow(stringResource(R.string.sort_aroma), draft.aroma, accent) { onChange(draft.copy(aroma = it)) }
        DimRow(stringResource(R.string.sort_flavor), draft.flavor, accent) { onChange(draft.copy(flavor = it)) }
        DimRow(stringResource(R.string.sort_finish), draft.finish, accent) { onChange(draft.copy(finish = it)) }
        afterScores()
        if (!embedded) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rate_overall), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(draft.overall?.let { English.score(it) } ?: "–", style = RatingStyle)
        }
        if (showProfile) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.rate_profile), style = MaterialTheme.typography.labelMedium, color = secondary, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            DimRow(
                stringResource(R.string.sort_acidity),
                draft.acidity,
                accent,
                ends = stringResource(R.string.profile_acidity_low) to stringResource(R.string.profile_acidity_high),
            ) { onChange(draft.copy(acidity = it)) }
            DimRow(
                stringResource(R.string.sort_body),
                draft.body,
                accent,
                ends = stringResource(R.string.profile_body_light) to stringResource(R.string.profile_body_full),
            ) { onChange(draft.copy(body = it)) }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .focusRing().toggleable(value = draft.buyAgain, role = androidx.compose.ui.semantics.Role.Switch) { onChange(draft.copy(buyAgain = it)) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.rate_buy_again), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = draft.buyAgain, onCheckedChange = null)
        }
        if (showNotes) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.detail_notes_header), style = MaterialTheme.typography.labelMedium, color = secondary, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = draft.notes, onValueChange = { onChange(draft.copy(notes = it)) },
                placeholder = { Text(stringResource(R.string.rate_notes_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(), minLines = 2,
            )
        }
    }
}

/**
 * One row of the grid: label (with low→high ends for profile rows) and five tappable dots. At large
 * font sizes a profile row's ends and dots leave its label too little room, so the label sits above
 * them instead (as the fact rows do).
 */
@Composable
private fun DimRow(label: String, value: Int?, accent: Color, ends: Pair<String, String>? = null, onPick: (Int?) -> Unit) {
    val secondary = dev.roastbook.app.ui.theme.onTintVariant()
    // For TalkBack and switch access the whole row is ONE adjustable control: it reads
    // "Aroma, 3 of 5" and swipe up/down (or the volume keys) move the value.
    val state = if (value == null) stringResource(R.string.rate_state_unset) else englishString(R.string.rate_value, value)
    val rangeLabel = ends?.let { stringResource(R.string.rate_range, it.first, it.second) }
    val rowModifier = Modifier.fillMaxWidth().padding(vertical = 1.dp).semantics(mergeDescendants = true) {
        contentDescription = listOfNotNull(label, rangeLabel).joinToString(", ")
        stateDescription = state
        progressBarRangeInfo = ProgressBarRangeInfo((value ?: 0).toFloat(), 0f..5f, steps = 4)
        // Down to 0 clears the row, like tapping the chosen dot again.
        setProgress { target -> val v = Math.round(target); onPick(if (v <= 0) null else v.coerceAtMost(5)); true }
    }
    @Composable fun picker() {
        if (ends != null) {
            Text(
                ends.first,
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(Modifier.width(6.dp))
        }
        DotPicker(value, accent, onPick)
        if (ends != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                ends.second,
                style = MaterialTheme.typography.labelSmall,
                color = secondary,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
    val stacked = ends != null && androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
    if (stacked) Column(rowModifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics { })
        Row(verticalAlignment = Alignment.CenterVertically) { picker() }
    } else Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).clearAndSetSemantics { })
        picker()
    }
}

/**
 * Five circles; tap to set, tap the chosen one again to clear. Filled up to the value, in the row
 * accent. Each circle sits in a 28 x 44 dp hit box so neighbors don't steal taps; the row above
 * carries the accessibility semantics, so the dots themselves are hidden from TalkBack.
 */
@Composable
private fun DotPicker(value: Int?, accent: Color, onPick: (Int?) -> Unit) {
    Row(Modifier.clearAndSetSemantics { }) {
        for (i in 1..5) {
            val filled = value != null && i <= value
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(width = 28.dp, height = 44.dp)
                    .focusRing(ChipRingShape).clickable(
                        interactionSource = null,
                        indication = androidx.compose.material3.ripple(bounded = false, radius = 16.dp),
                    ) { onPick(if (value == i) null else i) },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, accent, CircleShape)
                        .background(if (filled) accent else Color.Transparent),
                )
            }
        }
    }
}
