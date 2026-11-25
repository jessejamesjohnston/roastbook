package dev.roastbook.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import dev.roastbook.app.ui.detail.DetailScreen
import dev.roastbook.app.ui.detail.EditSessions
import dev.roastbook.app.ui.history.HistoryScreen
import dev.roastbook.app.ui.history.HistoryViewModel

/** The coffee open in the right-hand pane on a large screen, and whether it opened on the rating editor. */
internal class PaneSelection(id: MutableState<String?>, editRating: MutableState<Boolean>) {
    var id by id
    var editRating by editRating

    fun open(coffeeId: String, onRatingEditor: Boolean = false) { id = coffeeId; editRating = onRatingEditor }
}

/**
 * The History destination: the list alone on a phone-sized window, or the list beside the open
 * coffee when the window is wide enough for two panes.
 */
@Composable
internal fun HistoryDestination(
    split: PaneSplit,
    selection: PaneSelection,
    sessions: EditSessions,
    onAdd: () -> Unit,
    onOpenPage: (id: String, editRating: Boolean) -> Unit,
    onSettings: () -> Unit,
    layoutSwitched: () -> Boolean,
) {
    if (!split.twoPane) {
        HistoryScreen(
            onAdd = onAdd,
            onOpen = { id -> onOpenPage(id, false) },
            onOpenRatingEditor = { id -> onOpenPage(id, true) },
            onSettings = onSettings,
        )
        return
    }
    val historyVm: HistoryViewModel = hiltViewModel()
    val count by historyVm.count.collectAsState()
    // Back with a coffee open clears the right pane before it leaves the app.
    BackHandler(enabled = selection.id != null) { selection.id = null }
    // The open coffee can disappear under the pane: a restore that replaced the journal, say. Then
    // the pane shows the list's empty state, not a blank page, and an edit still open on that coffee
    // has nothing left to save into.
    selection.id?.let { open ->
        val present by remember(open) { historyVm.exists(open) }.collectAsState(initial = null)
        LaunchedEffect(open, present) {
            if (PaneRules.keepSelection(present)) return@LaunchedEffect
            if (sessions.forCoffee(open) != null) sessions.clear()
            if (selection.id == open) selection.id = null
        }
    }
    ListDetailPanes(
        split,
        list = {
            HistoryScreen(
                onAdd = onAdd,
                onOpen = { id -> selection.open(id) },
                onOpenRatingEditor = { id -> selection.open(id, onRatingEditor = true) },
                onSettings = onSettings,
                selectedId = selection.id,
                vm = historyVm,
            )
        },
        detail = {
            val id = selection.id
            if (id == null) DetailPlaceholder(hasCoffees = count > 0)
            else key(id) {
                DetailScreen(
                    id = id, editRating = selection.editRating, inPane = true, onBack = { selection.id = null },
                    layoutSwitched = layoutSwitched, onRatingRequestTaken = { selection.editRating = false },
                )
            }
        },
    )
}

/**
 * Moves the open coffee when the window changes between one and two panes (rotation, fold,
 * resize): a phone page becomes the right pane, the right pane becomes a phone page. An edit in
 * progress travels too (EditSessions); the page being torn down hands it over.
 */
@Composable
internal fun PaneHandOffEffect(twoPane: Boolean, nav: NavHostController, sessions: EditSessions, selection: PaneSelection) {
    LaunchedEffect(twoPane) {
        val entry = nav.currentBackStackEntry ?: return@LaunchedEffect
        if (twoPane) {
            if (entry.destination.hasRoute<Detail>()) {
                val route = entry.toRoute<Detail>()
                sessions.handingOver = true
                nav.popBackStack<History>(inclusive = false)
                selection.open(route.id)
            }
        } else {
            val id = selection.id
            if (id != null && entry.destination.hasRoute<History>()) {
                sessions.handingOver = true
                nav.navigate(Detail(id))
            }
            selection.id = null
        }
    }
}
