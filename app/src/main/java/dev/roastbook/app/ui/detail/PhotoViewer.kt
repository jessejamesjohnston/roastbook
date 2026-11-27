package dev.roastbook.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.roastbook.app.R
import dev.roastbook.app.util.englishString
import java.io.File
import kotlinx.coroutines.launch

/**
 * One coffee's photos, full window, over everything: swipe, the side buttons or the arrow keys move
 * between them; pinch (or a trackpad pinch), double tap, the scroll wheel or + and - zoom, and a
 * zoomed photo pans with a drag; Escape, Back or Close return to the page. The files are the app's
 * own copies, read in place: nothing is shared and no permission is needed.
 */
@Composable
fun PhotoViewer(title: String, files: List<File>, start: Int, onClose: () -> Unit) {
    if (files.isEmpty()) return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, files.lastIndex)) { files.size }
        val scope = rememberCoroutineScope()
        val zoom = remember { ZoomState() }
        LaunchedEffect(pager.currentPage) { zoom.reset() }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        fun go(page: Int) {
            if (page in files.indices) scope.launch { pager.animateScrollToPage(page) }
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> { go(pager.currentPage + 1); true }
                        Key.DirectionLeft -> { go(pager.currentPage - 1); true }
                        Key.Escape -> { onClose(); true }
                        Key.Plus, Key.Equals, Key.NumPadAdd -> { zoom.zoomBy(1.25f); true }
                        Key.Minus, Key.NumPadSubtract -> { zoom.zoomBy(0.8f); true }
                        Key.Zero -> { zoom.reset(); true }
                        else -> false
                    }
                },
        ) {
            HorizontalPager(state = pager, userScrollEnabled = !zoom.zoomed, modifier = Modifier.fillMaxSize()) { i ->
                val description = englishString(R.string.photo_viewer_description, title, i + 1, files.size)
                val zoomedLabel = stringResource(R.string.photo_viewer_zoomed)
                val active = i == pager.currentPage
                val resting = remember(i) { ZoomState() }
                ZoomableImage(
                    file = files[i], zoom = if (active) zoom else resting,
                    modifier = Modifier.fillMaxSize().semantics {
                        contentDescription = description
                        if (active && zoom.zoomed) stateDescription = zoomedLabel
                    },
                )
            }
            // A dark fade behind the bar keeps Close and the count readable over a light or zoomed photo.
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .safeDrawingPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, stringResource(R.string.photo_viewer_close))
                }
                Text(
                    englishString(R.string.detail_photo_position, pager.currentPage + 1, files.size),
                    color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (files.size > 1) {
                if (pager.currentPage > 0) {
                    FilledTonalIconButton(
                        onClick = { go(pager.currentPage - 1) },
                        modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(8.dp).clip(CircleShape),
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.photo_viewer_previous)) }
                }
                if (pager.currentPage < files.lastIndex) {
                    FilledTonalIconButton(
                        onClick = { go(pager.currentPage + 1) },
                        modifier = Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(8.dp).clip(CircleShape),
                    ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.photo_viewer_next)) }
                }
            }
        }
    }
}

/** How far a photo is zoomed and panned; 1x is the whole photo fitted to the window. */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var bounds = IntSize.Zero

    val zoomed: Boolean get() = scale > 1.01f

    fun reset() { scale = 1f; offset = Offset.Zero }

    /** Zooms by [factor] about [centroid] (the window's center when null), between 1x and 5x. */
    fun zoomBy(factor: Float, centroid: Offset? = null, pan: Offset = Offset.Zero) {
        val next = (scale * factor).coerceIn(1f, MAX_SCALE)
        val center = Offset(bounds.width / 2f, bounds.height / 2f)
        val c = (centroid ?: center) - center
        // Keep the point under the fingers (or the pointer) where it is while the scale changes.
        val moved = (offset - c) * (next / scale) + c + pan
        scale = next
        offset = clamp(if (next <= 1f) Offset.Zero else moved)
    }

    internal fun size(size: IntSize) { bounds = size; offset = clamp(offset) }

    private fun clamp(o: Offset): Offset {
        val maxX = bounds.width * (scale - 1) / 2
        val maxY = bounds.height * (scale - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    private companion object { const val MAX_SCALE = 5f }
}

/**
 * The photo fitted to the window, with [zoom] applied. A pinch zooms; a one-finger drag pans only
 * while zoomed (otherwise it belongs to the pager); double tap toggles 2.5x at the tap.
 */
@Composable
private fun ZoomableImage(file: File, zoom: ZoomState, modifier: Modifier) {
    Box(
        modifier
            .onSizeChanged { zoom.size(it) }
            .pointerInput(zoom) {
                detectTapGestures(onDoubleTap = { at -> if (zoom.zoomed) zoom.reset() else zoom.zoomBy(2.5f, at) })
            }
            .pointerInput(zoom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || zoom.zoomed) {
                            zoom.zoomBy(event.calculateZoom(), event.calculateCentroid(useCurrent = true), event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(zoom) {
                // A mouse wheel (or a two-finger trackpad scroll) zooms about the pointer.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy != 0f) {
                                zoom.zoomBy(if (dy < 0) 1.25f else 0.8f, event.changes.first().position)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = file, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom.scale; scaleY = zoom.scale
                translationX = zoom.offset.x; translationY = zoom.offset.y
            },
        )
    }
}
