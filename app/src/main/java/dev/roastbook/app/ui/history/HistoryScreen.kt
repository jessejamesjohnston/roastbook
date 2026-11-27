package dev.roastbook.app.ui.history

import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.foundation.shape.CircleShape
import dev.roastbook.app.ui.components.focusRing
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.roastbook.app.R
import dev.roastbook.app.ui.components.PermissionReasonDialog
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.ui.components.NOTIFICATION_PERMISSION
import dev.roastbook.app.ui.components.NotificationReason
import dev.roastbook.app.ui.components.PermissionAsk
import dev.roastbook.app.ui.components.PermissionAskState
import dev.roastbook.app.ui.components.rememberPermissionAsk
import dev.roastbook.app.ui.components.themedTint
import dev.roastbook.app.ui.rating.RatingSheet

/** Hilt access to the singleton [PhotoStore] from outside injection; see [rememberPhotoStore]. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PhotoStoreEntryPoint { fun photoStore(): PhotoStore }

/** The app's [PhotoStore], for composables that are not given one by a ViewModel. */
@Composable
fun rememberPhotoStore(): PhotoStore {
    val ctx = LocalContext.current.applicationContext
    return remember { EntryPointAccessors.fromApplication(ctx, PhotoStoreEntryPoint::class.java).photoStore() }
}

/**
 * The journal: header, the coffee list, and the sheets the list opens (filters, rating, and "how
 * did you try it" after a coffee is logged). [selectedId] marks the coffee open beside the list on
 * a large screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenRatingEditor: (String) -> Unit,
    onSettings: () -> Unit,
    selectedId: String? = null,
    vm: HistoryViewModel = hiltViewModel(),
) {
    val rows = vm.rows.collectAsLazyPagingItems()
    val count by vm.count.collectAsState()
    val unrated by vm.unrated.collectAsState()
    val backupNudge by vm.backupNudge.collectAsState()
    val sources by vm.sources.collectAsState()
    val roasters by vm.roasters.collectAsState()
    val origins by vm.origins.collectAsState()
    val processes by vm.processes.collectAsState()
    val sort by vm.sort.collectAsState()
    val descending by vm.descending.collectAsState()
    val filter by vm.filter.collectAsState()
    var filterSheet by remember { mutableStateOf(false) }
    // Asked at the moment a reminder is chosen, after one sentence of why, and only when Android would
    // show its dialog. The reminder is stored at the tap either way. Held here, not in the sheet, so
    // the ask survives the sheet closing into the reason dialog.
    val notifAsk = rememberPermissionAsk(NOTIFICATION_PERMISSION)
    var explainNotifications by rememberSaveable { mutableStateOf(false) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifAsk.answered(granted)
        explainNotifications = false
        vm.dismissTried()
    }
    val photoStore = rememberPhotoStore()
    val sheetFor by vm.triedSheetFor.collectAsState()
    val ratingTarget by vm.ratingTarget.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = {
            FloatingActionButton(
                modifier = Modifier.focusRing(FloatingActionButtonDefaults.shape, onFill = true),
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.fab_add_coffee)) }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                HistoryHeader(
                    filter = filter, sources = sources, count = count, unrated = unrated, backupNudge = backupNudge,
                    sort = sort, descending = descending,
                    onClearFilters = vm::clearFilters, onOpenFilters = { filterSheet = true }, onSettings = onSettings,
                    onSearch = vm::setSearch, onSort = vm::tapSort,
                )
            }
            if (rows.itemCount == 0 && count > 0 && rows.loadState.refresh is LoadState.NotLoading) {
                item {
                    Text(stringResource(R.string.history_no_matches), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        textAlign = TextAlign.Center)
                }
            }
            if (rows.itemCount == 0 && count == 0) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.history_empty_subtitle), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(count = rows.itemCount, key = rows.itemKey { it.id }) { index ->
                val row = rows[index] ?: return@items
                CoffeeRowCard(
                    row, photoStore, onClick = { onOpen(row.id) }, onRate = { vm.openRating(row.id) }, selected = row.id == selectedId,
                )
            }
        }
    }

    if (filterSheet) {
        FilterSheet(filter, sources, roasters, origins, processes, vm, onDismiss = { filterSheet = false })
    }

    ratingTarget?.let { target ->
        val accent = themedTint(target.tintLight, target.tintDark, target.accentLight, target.accentDark, target.paletteFallback).accent
        RatingSheet(
            coffee = target, accent = accent, onSave = vm::saveRating, onDismiss = vm::closeRating,
            onMore = { draft ->
                if (draft.complete) vm.saveRating(draft)
                vm.handOffDraft(target.id, draft)
                vm.closeRating()
                onOpenRatingEditor(target.id)
            },
        )
    }

    sheetFor?.let { id ->
        TriedSheet(
            coffeeId = id, vm = vm, notifAsk = notifAsk,
            // The reminder is stored by now; the sheet closes and the reason takes its place.
            onExplain = { vm.dismissTried(); explainNotifications = true },
            onDismiss = { vm.dismissTried() },
        )
    }
    if (explainNotifications) {
        PermissionReasonDialog(
            title = stringResource(R.string.notif_reason_reminder_title),
            reason = stringResource(R.string.notif_why_reminder),
            onContinue = {
                explainNotifications = false
                notifAsk.markAsked()
                notifPermission.launch(NOTIFICATION_PERMISSION)
            },
            onNotNow = { explainNotifications = false; notifAsk.decline(NotificationReason.REMINDER) },
        )
    }
}

/**
 * Asked right after a coffee is logged: rate it now, be reminded tomorrow or next week, or neither.
 * Choosing a reminder the first time, where Android would show its notification dialog, stores the
 * reminder and hands over to the reason dialog ([onExplain]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriedSheet(
    coffeeId: String, vm: HistoryViewModel, notifAsk: PermissionAskState, onExplain: () -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.sheet_tried_title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            val options = listOf(
                TriedChoice.RATE_NOW to R.string.sheet_rate_now,
                TriedChoice.TOMORROW to R.string.sheet_remind_tomorrow,
                TriedChoice.NEXT_WEEK to R.string.sheet_remind_next_week,
                TriedChoice.LET_YOU_KNOW to R.string.sheet_ill_let_you_know,
                TriedChoice.NO_REMINDER to R.string.sheet_no_reminder,
            )
            options.forEach { (choice, label) ->
                TextButton(
                    onClick = {
                        vm.answerTried(coffeeId, choice)
                        notifAsk.refresh()
                        val ask = notifAsk.value
                        // Only the first time: a person who said no, or Not now, isn't asked again on every bag
                        // (Settings says the reminders can't show, with the way back).
                        val remind = choice == TriedChoice.TOMORROW || choice == TriedChoice.NEXT_WEEK
                        if (remind && Build.VERSION.SDK_INT >= 33 && ask == PermissionAsk.FIRST_ASK && !notifAsk.declined(NotificationReason.REMINDER)) {
                            onExplain()
                        } else {
                            vm.dismissTried()
                            if (choice == TriedChoice.RATE_NOW) vm.openRating(coffeeId)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRing(CircleShape),
                ) { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}
