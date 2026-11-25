package dev.roastbook.app.ui.detail

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.ui.rating.RatingDraft
import dev.roastbook.app.ui.rating.Reactions

/**
 * The open-card logic of one coffee's page: starting an edit, saving it, and closing it, or refusing
 * to close a rating that can't be saved. [DetailScreen] lays the page out and calls these.
 *
 * It is created in the page's first composition, which usually runs before the row has loaded, and
 * it outlives that composition: so every call reads the row, the sources and the field lists at the
 * moment it runs, never from a value captured when it was created.
 *
 * [host] identifies the page that owns an open edit in [EditSessions]; it stays the same for the
 * life of the page.
 */
@Stable
class DetailEditing internal constructor(
    private val id: String,
    private val vm: DetailViewModel,
    private val sessions: EditSessions,
    private val host: Any,
) {
    /** True on a large screen, where the card edits in a dialog and a rating is never refused. */
    internal var inPane = false
    internal lateinit var res: Resources
    internal lateinit var context: Context

    /** Counts closes refused for an incomplete rating; each one brings the card's hint into view. */
    var refusedCloses by mutableIntStateOf(0)
        private set

    /** Set while asking whether to discard an incomplete rating: what to do once it is discarded. */
    var afterDiscard by mutableStateOf<(() -> Unit)?>(null)

    /** The line shown after a rating is saved, picked for its overall score. */
    var reaction by mutableStateOf<String?>(null)
        private set

    private fun sourceNameOf(cc: CoffeeEntity) = vm.sources.value.firstOrNull { it.id == cc.sourceId }?.name

    /** Writes what [s] changed: a complete rating that differs from the row, or the fields typed over. */
    fun commit(s: EditSession) {
        val cc = vm.coffee.value ?: return
        when (s.section) {
            RATING -> s.rating?.let { d ->
                if (d.complete && d != RatingDraft.from(cc)) {
                    vm.saveRating(d)
                    reaction = Reactions.forOverall(context, d.overall ?: 3f)
                }
            }
            else -> {
                val fields = if (s.section == FACTS) factFields(cc, sourceNameOf(cc), res) else proseFields(cc)
                // Only what the user typed over: compared with the card's opening values, not with
                // the row now, which a read or a lookup may have filled in meanwhile.
                val edits = fields.filter { it.key in s.drafts && userChanged(s, it.key, it.value) }
                    .associate { it.key to s.drafts[it.key]?.trim()?.ifBlank { null } }
                if (edits.isNotEmpty()) {
                    if ("source" in edits) vm.setSource(edits["source"])
                    val typed = fields.filter { it.key != "source" && it.key in edits }
                    vm.edit { e -> typed.fold(e) { acc, f -> f.apply(acc, edits[f.key]) } }
                }
            }
        }
    }

    /** On a phone, the open card holds a rating that can't be saved and would be lost by closing it. */
    private fun unsavableRating(): Boolean {
        val s = sessions.forCoffee(id) ?: return false
        val cc = vm.coffee.value ?: return false
        return !inPane && cantSaveRating(s, cc)
    }

    /**
     * Save the open card (if it is this coffee's) and close it. False when it stays open instead:
     * an incomplete rating, whose card already says what is missing.
     */
    fun finishEditing(): Boolean {
        if (unsavableRating()) { refusedCloses++; return false }
        sessions.forCoffee(id)?.let { commit(it) }
        if (sessions.forCoffee(id) != null) sessions.clear()
        return true
    }

    /** Back or the back arrow: close the card, or ask first when that would discard a rating. */
    fun finishOrAsk(then: () -> Unit) {
        if (unsavableRating()) afterDiscard = then else { finishEditing(); then() }
    }

    /** Opens [section] for editing, after saving any other open card; nothing happens until the row has loaded. */
    fun startEditing(section: String, rating: RatingDraft? = null) {
        val cc = vm.coffee.value ?: return
        if (!finishEditing()) return
        val fields = when (section) { FACTS -> factFields(cc, sourceNameOf(cc), res); PROSE -> proseFields(cc); else -> emptyList() }
        sessions.start(
            EditSession(
                coffeeId = id, section = section,
                drafts = fields.associate { it.key to (it.value ?: "") },
                rating = if (section == RATING) rating ?: RatingDraft.from(cc) else null,
            ),
        )
        sessions.owner = host
    }

    /** Leaving the page: save and close this coffee's open card, unless [moving] it to another layout. */
    fun saveOnLeave(moving: Boolean) {
        if (!moving) sessions.forCoffee(id)?.let { commit(it); sessions.clear() }
    }
}

/**
 * The [DetailEditing] for coffee [id], kept for as long as the page shows that coffee. The values
 * that can change between compositions (the layout, the resources) are refreshed on every one.
 */
@Composable
internal fun rememberDetailEditing(id: String, vm: DetailViewModel, sessions: EditSessions, host: Any, inPane: Boolean): DetailEditing {
    val editing = remember(id) { DetailEditing(id, vm, sessions, host) }
    editing.inPane = inPane
    editing.res = LocalResources.current
    editing.context = LocalContext.current
    return editing
}
