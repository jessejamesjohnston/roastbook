package dev.roastbook.app.ui.nav

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.roastbook.app.reminders.PendingActions
import dev.roastbook.app.ui.add.AddScreen
import dev.roastbook.app.ui.detail.DetailScreen
import dev.roastbook.app.ui.detail.rememberEditSessions
import dev.roastbook.app.ui.onboarding.OnboardingScreen
import dev.roastbook.app.ui.settings.LicenseTextScreen
import dev.roastbook.app.ui.settings.LicensesScreen
import dev.roastbook.app.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes, one per screen. [History] can carry a just-added coffee whose "Have
 * you tried it yet?" sheet should open; [Detail] can open straight into the rating editor.
 */
@Serializable data class History(val triedSheetFor: String? = null)
@Serializable object Add
@Serializable data class Detail(val id: String, val editRating: Boolean = false)
@Serializable object Settings
@Serializable object Onboarding
@Serializable object Licenses
/** A license page: [sections] of the license texts joined by ";", or the third-party notices when blank. */
@Serializable data class LicenseText(val title: String, val sections: String = "")

/** Hardware-keyboard shortcuts the activity forwards (Ctrl+N, Ctrl+comma). */
enum class Shortcut { NEW_COFFEE, SETTINGS }

/**
 * The app's navigation graph, with History as the root of the back stack (after onboarding on a
 * first start). Also routes a reminder tap back to History's rating sheet, handles keyboard
 * shortcuts on History when no edit is open, and lays out one or two panes by window size.
 */
@Composable
fun RoastbookNavHost(pending: PendingActions, showOnboarding: Boolean, shortcuts: Flow<Shortcut> = emptyFlow()) {
    val nav = rememberNavController()
    val sessions = rememberEditSessions()
    // Large screens: the coffee open in the right-hand pane. Saved, so it survives rotation and folds.
    val selectedId = rememberSaveable { mutableStateOf<String?>(null) }
    val selectedEditRating = rememberSaveable { mutableStateOf(false) }
    val selection = remember { PaneSelection(selectedId, selectedEditRating) }

    // A notification tap can land while the app sits on Detail, Add or Settings: return to History
    // (always the root of the stack), then hand History's ViewModel the sheet request.
    LaunchedEffect(nav) {
        pending.rateCoffee.collect { id ->
            if (id != null) {
                pending.rateCoffee.value = null
                nav.popBackStack<History>(inclusive = false)
                pending.showRatingSheet.value = id
            }
        }
    }
    LaunchedEffect(nav) {
        shortcuts.collect { s ->
            val onHistory = nav.currentBackStackEntry?.destination?.hasRoute<History>() == true
            if (!onHistory || sessions.session.value != null) return@collect
            when (s) {
                Shortcut.NEW_COFFEE -> nav.navigate(Add)
                Shortcut.SETTINGS -> nav.navigate(Settings)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = rememberPaneSplit(maxWidth, maxHeight)
        val twoPane by rememberUpdatedState(split.twoPane)

        PaneHandOffEffect(split.twoPane, nav, sessions, selection)

        NavHost(navController = nav, startDestination = if (showOnboarding) Onboarding else History()) {
            composable<Onboarding> {
                // The intro shows once, on the first start; History replaces it.
                OnboardingScreen(onDone = { nav.navigate(History()) { popUpTo<Onboarding> { inclusive = true } } })
            }
            composable<History> {
                HistoryDestination(
                    split, selection, sessions,
                    onAdd = { nav.navigate(Add) },
                    onOpenPage = { id, editRating -> nav.navigate(Detail(id, editRating = editRating)) },
                    onSettings = { nav.navigate(Settings) },
                    layoutSwitched = { !twoPane },
                )
            }
            composable<Add> {
                AddScreen(
                    onCancel = { nav.popBackStack() },
                    onCreated = { id ->
                        // On a large screen the new coffee opens beside the list straight away.
                        if (twoPane) selection.open(id)
                        nav.navigate(History(triedSheetFor = id)) { popUpTo<History> { inclusive = true } }
                    },
                )
            }
            composable<Settings> {
                SettingsScreen(onBack = { nav.popBackStack() }, onLicenses = { nav.navigate(Licenses) })
            }
            composable<Licenses> {
                LicensesScreen(
                    onBack = { nav.popBackStack() },
                    onOpen = { title, sections -> nav.navigate(LicenseText(title, sections.joinToString(";"))) },
                )
            }
            composable<LicenseText> { entry ->
                val route = entry.toRoute<LicenseText>()
                LicenseTextScreen(route.title, route.sections.split(';').filter { it.isNotBlank() }, onBack = { nav.popBackStack() })
            }
            composable<Detail> { entry ->
                val route = entry.toRoute<Detail>()
                // In a two-pane window this page is about to move into the right pane (the effect
                // above pops it). Composing it for that one frame would let it claim, then commit,
                // an edit that should carry over, so it stays empty until then.
                if (!split.twoPane) {
                    DetailScreen(id = route.id, editRating = route.editRating, onBack = { nav.popBackStack() }, layoutSwitched = { twoPane })
                }
            }
        }
    }
}
