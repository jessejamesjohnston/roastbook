package dev.roastbook.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R

/**
 * How the History screen splits the window. [listWidth] is the left pane; [gap] is the space a
 * physical hinge takes (0 on a seamless fold); the detail pane takes the rest.
 */
data class PaneSplit(val twoPane: Boolean, val listWidth: Dp = 0.dp, val gap: Dp = 0.dp)

/** The pure rules for one pane or two, kept apart from Compose so they are unit-testable. */
object PaneRules {
    /** Width at which the list and a coffee's page sit side by side regardless of orientation. */
    val EXPANDED = 840.dp
    /** Below this width a split pane is too narrow to read. */
    val MEDIUM = 600.dp
    /** Landscape windows shorter than this (a phone on its side) stay single-pane. */
    val MIN_LANDSCAPE_HEIGHT = 480.dp

    /**
     * Two panes on: any window at least [EXPANDED] wide (a tablet in landscape, a desktop
     * window); a landscape window at least [MEDIUM] wide that is not a phone on its side; and any
     * window at least [MEDIUM] wide on a device with a vertical fold, split at the fold.
     *
     * [hingeLeft]/[hingeRight] are a vertical hinge's edges in this window, in dp, or null.
     */
    fun split(width: Dp, height: Dp, hingeLeft: Dp?, hingeRight: Dp?): PaneSplit {
        val hinge = hingeLeft != null && hingeRight != null && hingeLeft > 0.dp && hingeRight < width
        val twoPane = width >= EXPANDED ||
            (width >= MEDIUM && width > height && height >= MIN_LANDSCAPE_HEIGHT) ||
            (width >= MEDIUM && hinge)
        if (!twoPane) return PaneSplit(false)
        return if (hinge) PaneSplit(true, listWidth = hingeLeft, gap = hingeRight - hingeLeft)
        else PaneSplit(true, listWidth = width / 2)
    }

    /**
     * Whether the right pane keeps showing the coffee it has open. [present] is null while its row
     * is still loading, true while it exists, false once it is gone (deleted, or replaced by a
     * restore): only "gone" closes the pane.
     */
    fun keepSelection(present: Boolean?): Boolean = present != false
}

/** The split for this window: size from the caller's constraints, the fold from Jetpack WindowManager. */
@Composable
fun rememberPaneSplit(width: Dp, height: Dp): PaneSplit {
    val hinge = currentWindowAdaptiveInfoV2().windowPosture.hingeList.firstOrNull { it.isVertical }
    val density = LocalDensity.current
    val (left, right) = if (hinge == null) null to null else with(density) { hinge.bounds.left.toDp() to hinge.bounds.right.toDp() }
    return PaneRules.split(width, height, left, right)
}

/** The list on the left, a coffee's page (or the tip) on the right, a hairline or the hinge between. */
@Composable
fun ListDetailPanes(split: PaneSplit, list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(split.listWidth).fillMaxHeight()) { list() }
        if (split.gap > 0.dp) Spacer(Modifier.width(split.gap))
        else Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
    }
}

/** The right pane before a coffee is picked: what to do, in one line. */
@Composable
fun DetailPlaceholder(hasCoffees: Boolean) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (hasCoffees) Icons.AutoMirrored.Filled.MenuBook else Icons.Default.LocalCafe, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(stringResource(if (hasCoffees) R.string.pane_pick_title else R.string.pane_empty_title),
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(if (hasCoffees) R.string.pane_pick_body else R.string.pane_empty_body),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
