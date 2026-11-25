package dev.roastbook.app.ui.detail

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.roastbook.app.R
import dev.roastbook.app.cloud.Enricher
import dev.roastbook.app.cloud.LookupResult
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.ui.rating.RatingDraft
import dev.roastbook.app.ui.rating.RatingEditor
import dev.roastbook.app.ui.rating.RatingSummary
import dev.roastbook.app.ui.theme.LocalHighContrast
import dev.roastbook.app.ui.theme.NotesStyle
import dev.roastbook.app.ui.theme.RatingStyle
import dev.roastbook.app.ui.theme.onTintVariant
import dev.roastbook.app.util.WebLinks
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date

/** The square Detail hero: the photo over the bag's tint, or the tint alone without a file. */
@Composable
internal fun HeroBox(file: File?, tint: Color, label: String, modifier: Modifier) {
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(tint)) {
        if (file != null) {
            AsyncImage(model = file, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The Detail title: the coffee's title as a heading, then the roaster (when the title is the coffee
 * name) and logged date, then the tasting notes if any.
 */
@Composable
internal fun TitleBlock(c: CoffeeEntity, title: String, dateFormat: SimpleDateFormat, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(listOfNotNull(c.roaster.takeIf { c.coffeeName != null }, dateFormat.format(Date(c.loggedAt))).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (c.tastingNotes.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(c.tastingNotes.joinToString(" · "), style = NotesStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The shared card frame: tinted in the bag's hue, outlined in high contrast, titled as a heading. */
@Composable
private fun CardFrame(tint: Color, content: @Composable () -> Unit) {
    val hc = LocalHighContrast.current
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tint)
            .then(if (hc) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)) else Modifier)
            .padding(14.dp),
    ) { content() }
}

/** The header of a card: its title (a heading for TalkBack) and the one pencil / check button. */
@Composable
private fun CardHeader(
    title: String,
    editable: Boolean,
    editing: Boolean,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val secondary = onTintVariant()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = secondary, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
        if (editable) IconButton(onClick = { if (editing) onDone() else onEdit() }, modifier = Modifier.size(40.dp)) {
            if (editing) {
                Icon(Icons.Default.Check, stringResource(R.string.detail_done_section, title), Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            else Icon(Icons.Default.Edit, stringResource(R.string.detail_edit_section, title), Modifier.size(18.dp), tint = secondary)
        }
    }
}

/**
 * A tinted card with ONE pencil that becomes a check mark while editing inline. [drafts] non-null =
 * this card is editing in place (phone); on a large screen the card stays read-only and the edit
 * happens in the dialog.
 */
@Composable
internal fun Section(
    title: String,
    tint: Color,
    editable: Boolean,
    fields: List<Field>,
    drafts: Map<String, String>?,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onChange: (String, String) -> Unit,
    sourceSuggestions: List<String> = emptyList(),
    view: @Composable () -> Unit,
) {
    CardFrame(tint) {
        CardHeader(title, editable, editing = drafts != null, onEdit = onEdit, onDone = onDone)
        Spacer(Modifier.height(8.dp))
        if (drafts == null) view() else FieldsEditor(fields, drafts, onChange, sourceSuggestions)
    }
}

/** A card with nothing to edit: its title and content only. */
@Composable
internal fun ReadOnlySection(title: String, tint: Color, view: @Composable () -> Unit) =
    Section(
        title, tint, editable = false, fields = emptyList(), drafts = null,
        onEdit = {}, onDone = {}, onChange = { _, _ -> }, view = view,
    )

/** Rating card: one grid (score rows, then profile rows), a check pill for buy-again, notes with a header. */
@Composable
internal fun RatingSection(
    c: CoffeeEntity,
    tint: Color,
    accent: Color,
    draft: RatingDraft?,
    incomplete: Boolean,
    refusedCloses: Int,
    reaction: String?,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onChange: (RatingDraft) -> Unit,
) {
    val secondary = onTintVariant()
    val editingInline = draft != null
    CardFrame(tint) {
        val shown = if (editingInline) draft.overall else c.overall
        val overallLabel = shown?.let { stringResource(R.string.detail_overall_description, "%.1f".format(it)) }
        CardHeader(
            stringResource(R.string.detail_ratings), editable = c.overall != null || editingInline, editing = editingInline,
            onEdit = onEdit, onDone = onDone,
        ) {
            if (shown != null) Text("%.1f".format(shown), style = RatingStyle,
                modifier = Modifier.semantics { contentDescription = overallLabel ?: "" })
        }
        Spacer(Modifier.height(8.dp))
        when {
            draft != null -> {
                RatingEditor(draft, accent, onChange = onChange, showNotes = true, embedded = true) {
                    // Why the check doesn't close it yet (as the large-screen dialog says under Save),
                    // under the scores; scrolled into view whenever a close is refused.
                    if (incomplete) {
                        val inView = remember { BringIntoViewRequester() }
                        LaunchedEffect(refusedCloses) { if (refusedCloses > 0) inView.bringIntoView() }
                        Text(stringResource(R.string.edit_rating_incomplete), style = MaterialTheme.typography.bodySmall, color = secondary,
                            modifier = Modifier.padding(vertical = 4.dp).bringIntoViewRequester(inView)
                                .semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
            c.overall != null -> {
                RatingSummary(c, accent)
                reaction?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = NotesStyle, color = secondary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            else -> {
                Text(stringResource(R.string.detail_unrated), style = MaterialTheme.typography.bodyMedium, color = secondary)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onEdit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    )) {
                    Text(stringResource(R.string.detail_rate_cta))
                }
            }
        }
    }
}

/**
 * Where to get this coffee again. With a key: grounded suggestions (roaster site, product page,
 * city) and a "Wrong roaster" reset. Without: a web search in a Custom Tab; paste a URL to keep it.
 */
@Composable
internal fun RoasterLinks(c: CoffeeEntity, tint: Color, vm: DetailViewModel) {
    val context = LocalContext.current
    val hasKey by vm.hasCloudKey.collectAsState()
    val lookingUp by vm.lookingUp.collectAsState()
    val lookupOutcome by vm.lookupOutcome.collectAsState()
    val secondary = onTintVariant()
    val query = listOfNotNull(c.roaster, c.coffeeName, "coffee").joinToString(" ")
    fun open(url: String) {
        val uri = WebLinks.uri(url) ?: return
        runCatching { CustomTabsIntent.Builder().build().launchUrl(context, uri) }
    }
    if (c.roaster.isNullOrBlank() && c.roasterUrl == null) return
    CardFrame(tint) {
        Text(stringResource(R.string.detail_roaster_links), style = MaterialTheme.typography.labelMedium, color = secondary,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        val hasLinks = c.roasterUrl != null || c.productUrl != null || c.roasterCity != null
        if (hasLinks) {
            val siteLabel = stringResource(R.string.detail_roaster_site)
            val productLabel = stringResource(R.string.detail_product_page)
            c.roasterUrl?.takeIf { WebLinks.uri(it) != null }?.let { LinkRow(siteLabel, it) { open(it) } }
            c.productUrl?.takeIf { WebLinks.uri(it) != null }?.let { LinkRow(productLabel, it) { open(it) } }
            c.roasterCity?.let { Text(stringResource(R.string.detail_roaster_city, it), style = MaterialTheme.typography.bodyMedium) }
            val status = c.enrichmentStatus
            if (status == Enricher.STATUS_SUGGESTED || status == Enricher.STATUS_SUGGESTED_UNVERIFIED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        if (status == Enricher.STATUS_SUGGESTED) R.string.detail_links_suggested else R.string.detail_links_unverified,
                    ),
                    style = MaterialTheme.typography.bodySmall, color = secondary,
                )
                TextButton(onClick = { vm.wrongRoaster() }) { Text(stringResource(R.string.detail_wrong_roaster)) }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { open("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")) }) {
                    Text(stringResource(R.string.detail_find_roaster))
                }
                if (hasKey) OutlinedButton(onClick = { vm.lookUpRoaster() }, enabled = !lookingUp) {
                    Text(stringResource(if (lookingUp) R.string.detail_looking_up else R.string.detail_look_up_roaster))
                }
            }
            val outcome = lookupOutcome
            when {
                outcome is LookupResult.Failed -> {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(outcome.failure.message, outcome.model), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                outcome is LookupResult.Nothing || (outcome == null && c.enrichmentStatus == Enricher.STATUS_NOTHING) -> {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.detail_lookup_nothing), style = MaterialTheme.typography.bodySmall, color = secondary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = stringResource(R.string.detail_open_link), role = Role.Button, onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 110.dp).padding(end = 8.dp))
        val shown = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        Text(shown, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/**
 * Label and value read as one item ("Process, Washed"). At large font sizes the fixed label column
 * would break words mid-letter, so the label sits above its value instead.
 */
@Composable
internal fun FactRow(label: String, value: String, secondary: Color) {
    val stacked = LocalDensity.current.fontScale >= 1.5f
    val modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}
    if (stacked) Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = secondary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    } else Row(modifier, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = secondary, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
