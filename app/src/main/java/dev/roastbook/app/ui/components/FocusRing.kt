package dev.roastbook.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How thick the keyboard focus ring is, everywhere in the app. */
val FOCUS_RING_WIDTH = 3.dp

/** The band of page color between the ring and a filled control's own fill. */
private val FOCUS_RING_GAP = 2.dp

/** The shape of a chip's ring: the chips' own 8dp corners. */
val ChipRingShape = RoundedCornerShape(8.dp)

/**
 * Draws the keyboard focus ring while the control after this modifier has focus: a 3dp line in the
 * accent color (or [color], for a control over a photo or the camera) on the control's own bounds,
 * in its [shape], so no parent clips it. A touch never focuses a control, so the ring shows only
 * when the keyboard reaches it.
 *
 * [onFill]: for a filled control (a filled button, the add button, a selected chip), whose fill
 * reaches its bounds at the sides: a 2dp band of the page color inside the ring keeps an accent
 * ring visible against an accent fill.
 */
fun Modifier.focusRing(shape: Shape = RectangleShape, onFill: Boolean = false, color: Color? = null): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = color ?: MaterialTheme.colorScheme.primary
    val gap = MaterialTheme.colorScheme.surface
    onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (!focused) return@drawWithContent
            val width = FOCUS_RING_WIDTH.toPx()
            if (onFill) stroke(shape, gap, inset = width, FOCUS_RING_GAP)
            stroke(shape, ring, inset = 0f, FOCUS_RING_WIDTH)
        }
}

/** A stroke of [width] in [shape], lying wholly inside the bounds from [inset] pixels in. */
private fun DrawScope.stroke(shape: Shape, color: Color, inset: Float, width: Dp) {
    val w = width.toPx()
    val offset = inset + w / 2
    val box = Size(size.width - offset * 2, size.height - offset * 2)
    if (box.width <= 0f || box.height <= 0f) return
    translate(offset, offset) { drawOutline(shape.createOutline(box, layoutDirection, this), color, style = Stroke(width = w)) }
}
