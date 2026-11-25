package dev.roastbook.app.ui.add

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.roastbook.app.R
import dev.roastbook.app.data.db.PhotoRole
import dev.roastbook.app.ui.theme.Honey
import kotlinx.coroutines.delay

/** One staged photo in the strip: the thumbnail with its remove control, and its label chip below. */
@Composable
internal fun StagedThumb(
    photo: StagedPhoto, position: Int, count: Int, width: Dp, enabled: Boolean, onRole: (PhotoRole) -> Unit, onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val roleLabel = stringResource(photo.role.label())
    val roleDescription = stringResource(R.string.add_photo_role, roleLabel)
    // A traversal group per photo: TalkBack reads its image, remove button and label chip together,
    // instead of every image first and every chip after.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(width).semantics { isTraversalGroup = true },
    ) {
        Box {
            AsyncImage(
                model = photo.file, contentDescription = stringResource(R.string.add_photo_position, position, count),
                contentScale = ContentScale.Crop, modifier = Modifier.size(THUMB).clip(RoundedCornerShape(10.dp)),
            )
            RemoveButton(stringResource(R.string.remove_photo, roleLabel), enabled, onRemove, Modifier.align(Alignment.TopEnd))
        }
        // The label is a control: an outlined chip with a drop-down arrow, 48dp to touch (the
        // minimum-size wrapper adds the room above and below). TalkBack: "Front, photo label.
        // Double-tap to change."
        val changeLabel = stringResource(R.string.add_change_role)
        Box {
            Row(
                Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(enabled = enabled, onClickLabel = changeLabel) { menu = true }
                    // One node: the label and arrow are replaced by the description; click and its label stay.
                    .clearAndSetSemantics { contentDescription = roleDescription }
                    .heightIn(min = 32.dp)
                    .padding(start = CHIP_START, end = CHIP_END),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(roleLabel, color = Color.White, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(CHIP_ARROW))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                PhotoRole.entries.forEach { role ->
                    val current = role == photo.role
                    DropdownMenuItem(
                        text = { Text(stringResource(role.label())) }, onClick = { onRole(role); menu = false },
                        trailingIcon = if (current) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null,
                        modifier = Modifier.semantics { selected = current },
                    )
                }
            }
        }
    }
}

/**
 * The thumbnail's remove control. The touch target is the thumbnail's top-end 48dp square, inside
 * the thumbnail (so it can't reach the label chip below it, and the strip can't clip it); the
 * visible part stays a small disc in the corner: dark fill for light photos, white ring for dark
 * ones. Keyboard focus inverts it (white disc, dark cross) with an outer ring.
 */
@Composable
private fun RemoveButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .size(48.dp)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            Modifier
                .padding(2.dp)
                .size(30.dp)
                .border(2.dp, if (focused) Color.White else Color.Transparent, CircleShape)
                .padding(3.dp)
                .clip(CircleShape)
                .indication(interaction, ripple(color = Color.White))
                .background(if (focused) Color.White else Color.Black.copy(alpha = 0.65f))
                .border(1.5.dp, if (focused) Color.Black else Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Close, contentDescription = null, tint = if (focused) Color.Black else Color.White,
                modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * "Photo removed · Undo", inline above the thumbnails, for as long as Android recommends for a
 * message with an action (longer when the user has asked for more time, as Snackbar does). TalkBack
 * hears "Photo removed" through the live region; a keyboard user lands on Undo. Fixed colors, like
 * the rest of the camera screen: white and honey on a near-black pill read over any camera image.
 */
@Composable
internal fun RemovedUndo(removed: List<RemovedPhoto>, onUndo: () -> Unit, onExpired: () -> Unit) {
    val a11y = LocalAccessibilityManager.current
    val inputMode = LocalInputModeManager.current
    val context = LocalContext.current
    val undoFocus = remember { FocusRequester() }
    LaunchedEffect(removed) {
        if (inputMode.inputMode == InputMode.Keyboard) runCatching { undoFocus.requestFocus() }
        // With TalkBack on, reaching Undo takes several swipes, so there is no clock: the offer stays
        // until the next photo, label change, save or exit.
        val touchExploration = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        if (touchExploration) return@LaunchedEffect
        val millis = a11y?.calculateRecommendedTimeoutMillis(
            UNDO_MILLIS, containsIcons = false, containsText = true, containsControls = true,
        )
        delay(millis ?: UNDO_MILLIS)
        onExpired()
    }
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.8f))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(pluralStringResource(R.plurals.add_photos_removed, removed.size, removed.size), color = Color.White,
            style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onUndo, modifier = Modifier.focusRequester(undoFocus),
            colors = ButtonDefaults.textButtonColors(contentColor = Honey)) {
            Text(stringResource(R.string.add_photo_undo), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private const val UNDO_MILLIS = 8_000L
private val THUMB = 64.dp
private val CHIP_START = 10.dp
private val CHIP_END = 4.dp
private val CHIP_ARROW = 20.dp

/**
 * The width of one thumbnail column: the widest label chip any photo could have (every label in the
 * menu, measured in the chip's text style at the current font scale), or the thumbnail if that is
 * wider. Recomputed when the text size or language changes.
 */
@Composable
internal fun rememberThumbColumnWidth(): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val labels = PhotoRole.entries.map { stringResource(it.label()) }
    return remember(labels, style, density.density, density.fontScale) {
        val widest = labels.maxOf { measurer.measure(it, style, maxLines = 1, softWrap = false, density = density).size.width }
        // The chip: start padding, text, arrow, end padding, and its 1dp border on each side; 2dp spare
        // so rounding never wraps or clips the widest label.
        val chip = with(density) { widest.toDp() } + CHIP_START + CHIP_ARROW + CHIP_END + 2.dp + 2.dp
        maxOf(THUMB, chip)
    }
}

/** The string resource naming a photo role ("Front", "Back", ...), in the chip and its menu. */
fun PhotoRole.label(): Int = when (this) {
    PhotoRole.FRONT -> R.string.role_front
    PhotoRole.BACK -> R.string.role_back
    PhotoRole.PAMPHLET -> R.string.role_pamphlet
    PhotoRole.BOX -> R.string.role_box
    PhotoRole.SELLER -> R.string.role_seller
    PhotoRole.OTHER -> R.string.role_other
}
