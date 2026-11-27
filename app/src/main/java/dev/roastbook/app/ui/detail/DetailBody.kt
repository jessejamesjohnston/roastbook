package dev.roastbook.app.ui.detail

import dev.roastbook.app.ui.components.focusRing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.roastbook.app.R
import dev.roastbook.app.ui.components.ReasonDialog
import dev.roastbook.app.util.englishString
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.CoffeeState
import dev.roastbook.app.data.db.ExtractionSource
import dev.roastbook.app.extraction.ExtractionTrail
import dev.roastbook.app.reader.ReaderNudge
import dev.roastbook.app.ui.components.rememberReaderDownloadStarter
import dev.roastbook.app.ui.components.HeroSquare
import dev.roastbook.app.ui.components.ThemedTint
import dev.roastbook.app.ui.components.fileOrNull
import dev.roastbook.app.ui.rating.RatingDraft
import dev.roastbook.app.ui.rating.RatingEditor
import dev.roastbook.app.ui.theme.LocalHighContrast
import dev.roastbook.app.ui.theme.onTintVariant

/**
 * The page under the top bar: hero and title, the reading banner and note, then the cards. On a
 * phone ([inlineEditing]) a card edits in place and a tap outside the open card saves it.
 */
@Composable
internal fun DetailBody(
    c: CoffeeEntity,
    photos: List<CoffeePhotoEntity>,
    facts: List<Field>,
    prose: List<Field>,
    sourceNames: List<String>,
    tint: ThemedTint,
    vm: DetailViewModel,
    inlineEditing: Boolean,
    session: EditSession?,
    refusedCloses: Int,
    reaction: String?,
    onStartEditing: (section: String) -> Unit,
    onFinishEditing: () -> Unit,
    onTapOutside: () -> Unit,
    onRatingChange: (RatingDraft) -> Unit,
    onDraftChange: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hero = photos.firstOrNull { it.id == c.heroPhotoId } ?: photos.firstOrNull()
    // After a Tier 0 (Auto Backup) landing only thumbs exist; show what we have and say so below.
    val heroFile = listOfNotNull(hero?.square, hero?.original, hero?.thumb).map { vm.photoStore.file(it) }.firstOrNull { it.exists() }
    val thumbnailOnly = hero != null && !vm.photoStore.file(hero.original).exists() &&
        hero.thumb?.let { vm.photoStore.file(it).exists() } == true
    val extracting = c.state == CoffeeState.EXTRACTING
    // Which photo is open full window, if any (by its place in [photos]); kept across a rotation.
    var viewing by rememberSaveable(c.id) { mutableStateOf<Int?>(null) }
    val photoFiles = photos.mapNotNull { p -> listOfNotNull(p.original, p.thumb).map { vm.photoStore.file(it) }.firstOrNull { it.exists() } }
    val openPhoto = { i: Int -> if (photoFiles.isNotEmpty()) viewing = i.coerceIn(0, photoFiles.lastIndex) }
    val heroIndex = photos.indexOf(hero).coerceAtLeast(0)
    val readingTitle = extractingLines(c.extractionTrail, c.nextAttemptAt).first
    val title = when {
        extracting -> readingTitle
        c.coffeeName != null -> c.coffeeName
        c.roaster != null -> c.roaster
        else -> stringResource(R.string.detail_untitled)
    }

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // Wide windows (a pane, a tablet, a phone on its side): hero beside the title, and the
        // cards keep a readable measure instead of stretching.
        val wide = maxWidth >= 600.dp
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .then(if (inlineEditing) Modifier.pointerInput(Unit) { detectTapGestures { onTapOutside() } } else Modifier)
                .padding(bottom = 48.dp),
        ) {
            val heroLabel = stringResource(R.string.detail_hero_description, title)
            if (wide) {
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HeroBox(heroFile, tint.tint, heroLabel, Modifier.width(260.dp)) { openPhoto(heroIndex) }
                    Spacer(Modifier.width(20.dp))
                    TitleBlock(c, title, Modifier.weight(1f))
                }
            } else {
                HeroBox(heroFile, tint.tint, heroLabel, Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { openPhoto(heroIndex) }
                Spacer(Modifier.height(16.dp))
                TitleBlock(c, title, Modifier.padding(horizontal = 20.dp))
            }
            Spacer(Modifier.height(16.dp))

            if (thumbnailOnly) {
                Text(stringResource(R.string.detail_thumbnail_only), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            if (extracting) ReadingBanner(c)
            val readerOffered by vm.readerOffered.collectAsState()
            val nanoFailing by vm.nanoFailing.collectAsState()
            val hasKey by vm.hasCloudKey.collectAsState()
            val noteDismissed by vm.noteDismissed.collectAsState()
            val readerState by vm.readerState.collectAsState()
            val readerSettings by vm.readerSettings.collectAsState()
            val trailNow = ExtractionTrail.decode(c.extractionTrail)
            val rs = readerState
            // Download was tapped (and any notification question answered) but the choice is still
            // being written: the card shows the starting state at once, in the same frame, rather
            // than the offer's buttons until the write lands.
            var accepted by rememberSaveable(c.id) { mutableStateOf(false) }
            // Download and Resume ask for notifications the way Settings does (ReaderDownloadStarter).
            val starter = rememberReaderDownloadStarter { action ->
                if (action == ACCEPT_READER) { accepted = true; vm.acceptReaderOffer() } else vm.retryReaderDownload()
            }
            val waiting = ReaderNudge.waitingForReader(c.id, readerSettings)
            // Once the write shows, the stored state takes over (and a later Cancel can clear the card).
            // A Cancel or Not now counts the bag as answered, which ends the bridge even if the
            // written state came and went between two frames.
            LaunchedEffect(waiting) { if (waiting) accepted = false }
            val justAccepted = accepted && !waiting && c.id !in readerSettings?.offerDeclinedIds.orEmpty()
            val readerCard = rs != null &&
                (waiting || justAccepted || ReaderNudge.shouldOffer(c.id, c.state, c.extractionSource, trailNow, rs, readerSettings))
            if (readerCard) {
                ReaderOfferCard(
                    if (justAccepted) rs.asJustAccepted() else rs, waiting = waiting || justAccepted,
                    onDownload = { starter.start(ACCEPT_READER) }, onNotNow = vm::declineReaderOffer,
                    onRetry = { starter.start(RETRY_READER) }, onCancel = { accepted = false; vm.cancelReaderDownload() },
                )
            }
            starter.ReasonDialog()
            if (!noteDismissed) {
                // With the reader's card on the page, the note adds no second pointer to the reader or a key.
                ExtractionStatusCard(
                    c, readerOffered = readerOffered, nanoFailing = nanoFailing, hasKey = hasKey, readerCardShown = readerCard,
                    onReread = vm::reextract, onDismiss = vm::dismissNote,
                )
            }
            DetailCards(
                c, photos, facts, prose, sourceNames, tint, vm, extracting,
                inlineEditing, session, refusedCloses, reaction,
                onStartEditing, onFinishEditing, onRatingChange, onDraftChange, onOpenPhoto = openPhoto,
            )
        }
    }
    viewing?.let { start -> PhotoViewer(title, photoFiles, start, onClose = { viewing = null }) }
}

/** The reader card's two download actions, by name, for [rememberReaderDownloadStarter]. */
private const val ACCEPT_READER = "accept"
private const val RETRY_READER = "retry"

/** The one obvious "busy" state while a read runs: a banner above the cards, which are dimmed. */
@Composable
private fun ReadingBanner(c: CoffeeEntity) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary).padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                val (readingTitle, readingSubtitle) = extractingLines(c.extractionTrail, c.nextAttemptAt)
                Text(readingTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
                Text(readingSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
}

/** The rating, facts, roaster, description, photo and OCR cards, dimmed while a read runs. */
@Composable
private fun DetailCards(
    c: CoffeeEntity,
    photos: List<CoffeePhotoEntity>,
    facts: List<Field>,
    prose: List<Field>,
    sourceNames: List<String>,
    tint: ThemedTint,
    vm: DetailViewModel,
    extracting: Boolean,
    inlineEditing: Boolean,
    session: EditSession?,
    refusedCloses: Int,
    reaction: String?,
    onStartEditing: (section: String) -> Unit,
    onFinishEditing: () -> Unit,
    onRatingChange: (RatingDraft) -> Unit,
    onDraftChange: (String, String) -> Unit,
    onOpenPhoto: (Int) -> Unit = {},
) {
    val editingKey = session?.section
    val secondary = onTintVariant()
    // Dimmed while a read runs, except in high contrast, whose text must keep its ratio.
    Column(Modifier.alpha(if (extracting && !LocalHighContrast.current) 0.5f else 1f)) {
        RatingSection(
            c, tint.tint, tint.accent,
            draft = if (inlineEditing && editingKey == RATING) session.rating else null,
            incomplete = inlineEditing && session != null && cantSaveRating(session, c),
            refusedCloses = refusedCloses,
            reaction = reaction,
            onEdit = { onStartEditing(RATING) }, onDone = onFinishEditing,
            onChange = onRatingChange,
        )

        Section(
            title = stringResource(R.string.detail_facts), tint = tint.tint, editable = !extracting, fields = facts,
            drafts = if (inlineEditing && editingKey == FACTS) session.drafts else null,
            onEdit = { onStartEditing(FACTS) }, onDone = onFinishEditing, onChange = onDraftChange,
            sourceSuggestions = sourceNames,
        ) {
            val shown = facts.filter { it.value != null && it.key != "name" }
            // Read again lives in one place: the note above (and the More menu).
            if (shown.isEmpty()) {
                Text(stringResource(R.string.detail_section_empty), style = MaterialTheme.typography.bodyMedium, color = secondary)
            }
            shown.forEach { f -> FactRow(stringResource(f.label), f.value!!, secondary) }
            // Which reader filled these in; nothing when nothing could be read.
            val sourceLine = extractionSourceLabel(c)
            if (!extracting && sourceLine != null) {
                Spacer(Modifier.height(6.dp))
                Text(sourceLine, style = MaterialTheme.typography.labelSmall, color = secondary)
            }
            guessedFieldsLine(c)?.takeIf { !extracting }?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = secondary)
            }
        }

        RoasterLinks(c, tint.tint, vm)

        Section(
            title = stringResource(R.string.detail_descriptions), tint = tint.tint, editable = !extracting, fields = prose,
            drafts = if (inlineEditing && editingKey == PROSE) session.drafts else null,
            onEdit = { onStartEditing(PROSE) }, onDone = onFinishEditing, onChange = onDraftChange,
        ) {
            val shown = prose.filter { it.value != null }
            if (shown.isEmpty()) {
                Text(stringResource(R.string.detail_section_empty), style = MaterialTheme.typography.bodyMedium, color = secondary)
            }
            shown.forEachIndexed { i, f ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(stringResource(f.label), style = MaterialTheme.typography.labelSmall, color = secondary)
                    Spacer(Modifier.height(2.dp))
                    Text(f.value!!, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (photos.isNotEmpty()) ReadOnlySection(stringResource(R.string.detail_photos), tint.tint) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
                    val label = englishString(R.string.detail_photo_position, i + 1, photos.size)
                    val openLabel = stringResource(R.string.detail_photo_open)
                    HeroSquare(vm.photoStore.fileOrNull(p.thumb ?: p.original), 88.dp, 12.dp, stroke = false,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                            .focusRing(RoundedCornerShape(12.dp)).clickable(onClickLabel = openLabel, role = Role.Button) { onOpenPhoto(i) }
                            .semantics { contentDescription = label })
                }
            }
        }

        if (!c.ocrText.isNullOrBlank() && c.extractionSource == ExtractionSource.OCR) {
            ReadOnlySection(stringResource(R.string.detail_ocr_text), tint.tint) {
                Text(c.ocrText, style = MaterialTheme.typography.bodySmall, color = secondary)
            }
        }
    }
}

/** The large-screen editor for the open card, centered over both panes. */
@Composable
internal fun DetailEditDialog(
    session: EditSession,
    c: CoffeeEntity,
    facts: List<Field>,
    prose: List<Field>,
    sourceNames: List<String>,
    tint: ThemedTint,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onRatingChange: (RatingDraft) -> Unit,
    onDraftChange: (String, String) -> Unit,
) {
        val sectionTitle = when (session.section) {
            RATING -> stringResource(R.string.detail_ratings)
            FACTS -> stringResource(R.string.detail_facts)
            else -> stringResource(R.string.detail_descriptions)
        }
        val fields = when (session.section) { FACTS -> facts; PROSE -> prose; else -> emptyList() }
        val dirty = when (session.section) {
            RATING -> session.rating != null && session.rating != RatingDraft.from(c)
            else -> fields.any { userChanged(session, it.key, it.value) }
        }
        // A rating saves once aroma, flavor, and finish are in, as the rating sheet's Save does; an
        // untouched, unrated draft has nothing to save (closing it is Cancel's job).
        val canSave = if (session.section == RATING) (session.rating ?: RatingDraft.from(c)).complete else true
        EditDialog(
            title = sectionTitle, tint = tint.tint, dirty = dirty, canSave = canSave,
            saveHint = if (!canSave) stringResource(R.string.edit_rating_incomplete) else null,
            onSave = onSave, onDiscard = onDiscard,
        ) {
            if (session.section == RATING) {
                RatingEditor(session.rating ?: RatingDraft.from(c), tint.accent, onChange = onRatingChange, showNotes = true)
            } else {
                FieldsEditor(fields, session.drafts, onDraftChange, if (session.section == FACTS) sourceNames else emptyList())
            }
        }
}
