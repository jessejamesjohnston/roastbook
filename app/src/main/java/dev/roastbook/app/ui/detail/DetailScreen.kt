package dev.roastbook.app.ui.detail

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.roastbook.app.R
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.ui.components.themedTint

/**
 * One coffee's page.
 *
 * On a phone ([inPane] false) it is its own screen and a card edits in place: leaving the card
 * (check, back, a tap elsewhere, another card, leaving the page) saves it. A changed rating that
 * is missing aroma, flavor or finish can't be saved, so it is never closed silently: the card
 * stays open and says what is missing, and Back or the back arrow ask before discarding it.
 *
 * On a large screen ([inPane] true) it is the right-hand pane beside the list, and a card edits
 * in a dialog centered over BOTH panes. The dialog's scrim blocks the list until the edit is saved
 * or discarded, so nothing else can change underneath it.
 *
 * The open edit lives in [EditSessions] at activity scope, so rotating, folding or resizing the
 * window mid-edit carries the typed text across to whichever layout comes next.
 *
 * [layoutSwitched] says, at the moment this page leaves the composition, whether the window has
 * already switched to the other layout. A window can change between one and two panes without a
 * configuration change (a fold's posture, a resize within the same size bucket), and the page is
 * then removed in the same frame, before the nav host gets to mark the move.
 *
 * [editRating] ("Add details" from the rating sheet) opens the rating editor once; a recreated
 * page does not open it again. [onRatingRequestTaken] lets a caller that keeps the request (the
 * right-hand pane) reset it, so the next "Add details" on the same coffee counts as a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    id: String,
    editRating: Boolean = false,
    onBack: () -> Unit,
    inPane: Boolean = false,
    layoutSwitched: () -> Boolean = { false },
    onRatingRequestTaken: () -> Unit = {},
    vm: DetailViewModel = hiltViewModel<DetailViewModel, DetailViewModel.Factory>(key = "detail-$id") { it.create(id) },
) {
    val coffee by vm.coffee.collectAsState()
    val photos by vm.photos.collectAsState()
    val sources by vm.sources.collectAsState()
    val sessions = rememberEditSessions()
    val allSessions by sessions.session.collectAsState()
    val session = allSessions?.takeIf { it.coffeeId == id }
    val editingKey = session?.section
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val handoff = remember(editRating) { vm.takeRatingHandoff() }
    val focus = LocalFocusManager.current
    val activity = LocalActivity.current
    val host = remember { Any() }
    val editing = rememberDetailEditing(id, vm, sessions, host, inPane)
    val c = coffee

    val res = LocalResources.current
    val facts = c?.let { factFields(it, sources.firstOrNull { s -> s.id == it.sourceId }?.name, res) }.orEmpty()
    val prose = c?.let { proseFields(it) }.orEmpty()

    // This page now owns any edit that was open on this coffee (handed over from the other layout).
    LaunchedEffect(Unit) {
        if (sessions.forCoffee(id) != null) sessions.owner = host
        sessions.handingOver = false
    }
    // Opened from the rating sheet's "Add details": start the rating edit once the row has loaded.
    // Keyed on the request too: a pane already showing this coffee gets "Add details" again. Saved,
    // so a rotation after the editor closed does not open it a second time.
    var ratingRequestTaken by rememberSaveable(editRating) { mutableStateOf(false) }
    LaunchedEffect(c != null, editRating) {
        if (c == null || !editRating || ratingRequestTaken) return@LaunchedEffect
        if (sessions.forCoffee(id) == null) editing.startEditing(RATING, handoff)
        ratingRequestTaken = true
        onRatingRequestTaken()
    }
    // Leaving the page saves an open card, unless the edit is moving to another layout (a
    // configuration change, or the nav host handing it to the pane/page) where it continues.
    DisposableEffect(id) {
        onDispose {
            editing.saveOnLeave(
                moving = activity?.isChangingConfigurations == true || sessions.handingOver || sessions.owner !== host || layoutSwitched(),
            )
        }
    }
    BackHandler(enabled = editingKey != null && !inPane) { editing.finishOrAsk {} }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (!inPane) IconButton(onClick = { editing.finishOrAsk(onBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.detail_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.detail_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_read_again)) },
                                leadingIcon = { Icon(Icons.Default.DocumentScanner, null) },
                                enabled = c?.state != CoffeeState.EXTRACTING,
                                onClick = { menu = false; vm.reextract() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_delete)) },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        if (c == null) return@Scaffold
        val tint = themedTint(c.tintLight, c.tintDark, c.accentLight, c.accentDark, c.paletteFallback)
        val sourceNames = sources.map { it.name }
        DetailBody(
            c, photos, facts, prose, sourceNames, tint, vm,
            inlineEditing = !inPane, session = session, refusedCloses = editing.refusedCloses, reaction = editing.reaction,
            onStartEditing = { section -> editing.startEditing(section) }, onFinishEditing = { editing.finishEditing() },
            onTapOutside = { editing.finishEditing(); focus.clearFocus() },
            onRatingChange = sessions::setRating, onDraftChange = sessions::setDraft,
            modifier = Modifier.padding(padding),
        )

        // Large screen: the open card edits in a dialog centered over both panes.
        if (inPane && session != null) {
            DetailEditDialog(
                session, c, facts, prose, sourceNames, tint,
                onSave = { editing.finishEditing() }, onDiscard = { sessions.clear() },
                onRatingChange = sessions::setRating, onDraftChange = sessions::setDraft,
            )
        }
    }

    val savesToGallery by vm.savesToGallery.collectAsState()
    editing.afterDiscard?.let { then ->
        DiscardConfirm(
            onDiscard = { editing.afterDiscard = null; sessions.forCoffee(id)?.let { sessions.clear() }; then() },
            onKeep = { editing.afterDiscard = null },
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.detail_delete_confirm)) },
        text = { Text(stringResource(if (savesToGallery) R.string.detail_delete_body_gallery else R.string.detail_delete_body)) },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; sessions.forCoffee(id)?.let { sessions.clear() }; vm.delete(onBack) }) {
                Text(stringResource(R.string.detail_delete_yes))
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.detail_cancel)) } },
    )
}
