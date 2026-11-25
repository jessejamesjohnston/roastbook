package dev.roastbook.app.ui.detail

import android.content.res.Resources
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.ui.rating.RatingDraft
import dev.roastbook.app.ui.theme.onTintVariant
import dev.roastbook.app.util.Price

/** One editable fact: how it displays, and how a typed string writes back. */
internal data class Field(
    val key: String,
    val label: Int,
    val value: String?,
    val multiline: Boolean = false,
    val apply: (CoffeeEntity, String?) -> CoffeeEntity,
)

/** Keys of the Detail sections that open for editing; at most one section is edited at a time. */
internal const val RATING = "rating"
internal const val FACTS = "facts"
internal const val PROSE = "prose"

/**
 * The Facts section's editable fields, in display order, with numbers and lists formatted for
 * typing. The source field's write is a no-op: a source is saved on its own, as a picklist entry.
 */
internal fun factFields(c: CoffeeEntity, sourceName: String?, res: Resources): List<Field> = listOf(
    Field("roaster", R.string.field_roaster, c.roaster) { e, v -> e.copy(roaster = v) },
    Field("name", R.string.field_coffee_name, c.coffeeName) { e, v -> e.copy(coffeeName = v) },
    Field("country", R.string.field_origin_country, c.originCountry) { e, v -> e.copy(originCountry = v) },
    Field("region", R.string.field_region, c.region) { e, v -> e.copy(region = v) },
    Field("producer", R.string.field_producer, c.producer) { e, v -> e.copy(producer = v) },
    Field("process", R.string.field_process, c.process) { e, v -> e.copy(process = v) },
    Field("varietal", R.string.field_varietal, c.varietal.joinToString(", ").ifBlank { null }) { e, v -> e.copy(varietal = splitList(v)) },
    Field("elevation", R.string.field_elevation, c.elevationM?.let { res.getString(R.string.unit_masl, it) }) { e, v ->
        e.copy(elevationM = digits(v))
    },
    Field("roastLevel", R.string.field_roast_level, c.roastLevel) { e, v -> e.copy(roastLevel = v) },
    Field("roastDate", R.string.field_roast_date, c.roastDate) { e, v -> e.copy(roastDate = v) },
    Field("bag", R.string.field_bag_size, c.bagSizeG?.let { res.getString(R.string.unit_grams, it) }) { e, v ->
        e.copy(bagSizeG = digits(v))
    },
    Field("notes", R.string.field_tasting_notes, c.tastingNotes.joinToString(", ").ifBlank { null }) { e, v ->
        e.copy(tastingNotes = splitList(v))
    },
    Field("source", R.string.field_source, sourceName) { e, _ -> e },
    Field("price", R.string.field_price, c.priceUsd?.let { Price.format(it) }) { e, v -> e.copy(priceUsd = Price.parse(v)) },
    Field("url", R.string.field_roaster_url, c.roasterUrl) { e, v -> e.copy(roasterUrl = v) },
)

/** The Prose section's editable fields: the bag's and the seller's descriptions, both multiline. */
internal fun proseFields(c: CoffeeEntity): List<Field> = listOf(
    Field("bagDescription", R.string.detail_bag_description, c.bagDescription, multiline = true) { e, v -> e.copy(bagDescription = v) },
    Field("sellerDescription", R.string.detail_seller_description, c.sellerDescription, multiline = true) { e, v ->
        e.copy(sellerDescription = v)
    },
)

/**
 * The open card is a rating the user changed that is missing aroma, flavor or finish: it can't be
 * saved, and closing it would lose the change. An untouched one (Rate it, then nothing picked) can.
 */
internal fun cantSaveRating(s: EditSession, c: CoffeeEntity): Boolean =
    s.section == RATING && s.rating?.let { !it.complete && it != RatingDraft.from(c) } == true

/** True when the user changed this field in the open card (from its value when the card opened). */
internal fun userChanged(s: EditSession, key: String, current: String?): Boolean =
    (s.drafts[key] ?: "") != (s.opened[key] ?: current ?: "")

private fun splitList(v: String?): List<String> = v?.split(",", "·", "|")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
private fun digits(v: String?): Int? = v?.filter(Char::isDigit)?.toIntOrNull()

/** The text fields for a card, used inline on a phone and inside the large-screen dialog. */
@Composable
internal fun FieldsEditor(
    fields: List<Field>,
    drafts: Map<String, String>,
    onChange: (String, String) -> Unit,
    sourceSuggestions: List<String>,
) {
    Column {
        fields.forEach { f ->
            OutlinedTextField(
                value = drafts[f.key] ?: "", onValueChange = { onChange(f.key, it) },
                label = { Text(stringResource(f.label)) }, singleLine = !f.multiline,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (f.multiline) KeyboardCapitalization.Sentences
                    else KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            )
            if (f.key == "source" && sourceSuggestions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    items(sourceSuggestions) { s ->
                        Text(s, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(role = Role.Button) { onChange("source", s) }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}

/**
 * The large-screen editor: centered over both panes, with a scrim that blocks the list until the
 * edit is saved or discarded. Back or Escape with unsaved changes asks before throwing them away;
 * tapping the scrim does nothing, so a stray tap can't lose typing.
 */
@Composable
internal fun EditDialog(
    title: String,
    tint: Color,
    dirty: Boolean,
    canSave: Boolean,
    saveHint: String?,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    content: @Composable () -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { if (dirty) confirmDiscard = true else onDiscard() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        // The dialog window resizes above the keyboard, so the scroll area shrinks and Save stays reachable.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            // Deprecated since Android 11 in favor of handling window insets, but still honored: it
            // resizes this dialog's own window, so the layout inside needs no inset handling.
            @Suppress("DEPRECATION")
            dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        Surface(
            shape = RoundedCornerShape(28.dp), color = tint, tonalElevation = 0.dp, shadowElevation = 8.dp,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(0.92f).heightIn(max = 720.dp),
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                Spacer(Modifier.height(16.dp))
                if (saveHint != null) {
                    Text(saveHint, style = MaterialTheme.typography.bodySmall, color = onTintVariant(),
                        modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDiscard) { Text(stringResource(if (dirty) R.string.edit_discard else R.string.detail_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onSave, enabled = canSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                        )) {
                        Text(stringResource(R.string.detail_save))
                    }
                }
            }
        }
    }
    if (confirmDiscard) DiscardConfirm(onDiscard = { confirmDiscard = false; onDiscard() }, onKeep = { confirmDiscard = false })
}

/** "Discard your changes?", asked before an edit that can't be saved is thrown away. */
@Composable
internal fun DiscardConfirm(onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.edit_discard_title)) },
        text = { Text(stringResource(R.string.edit_discard_body)) },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.edit_discard)) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.edit_keep_editing)) } },
    )
}
