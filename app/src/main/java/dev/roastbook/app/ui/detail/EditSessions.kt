package dev.roastbook.app.ui.detail

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.roastbook.app.ui.rating.RatingDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one open edit on a coffee page: which card, and what has been typed so far.
 *
 * [drafts] holds the text fields of the facts or descriptions card, [opened] what they held when
 * the card opened; [rating] holds the rating card's draft. Only one card edits at a time, across the whole app.
 */
data class EditSession(
    val coffeeId: String,
    val section: String,
    val drafts: Map<String, String> = emptyMap(),
    val rating: RatingDraft? = null,
    /**
     * Each field's value when the card opened. Saving writes only the fields the user changed from
     * these, so a read or a lookup that lands while the card is open keeps what it filled in.
     */
    val opened: Map<String, String> = drafts,
)

/**
 * Holds the open edit at ACTIVITY scope, outside any one screen, so it survives the moves a
 * large screen makes: rotating, folding or unfolding, or a window resize that switches between
 * the one-pane and two-pane layouts. The page that shows the coffee next picks the edit up where
 * it was (inline on a phone, in the centered dialog on a large screen).
 *
 * The edit is also written to the activity's saved state, so text typed into an open card is
 * still there when Android stops the app in the background and the user comes back to it.
 */
class EditSessions(private val saved: SavedStateHandle) : ViewModel() {
    private val _session = MutableStateFlow(saved.get<String>(KEY)?.let(::decode))
    val session: StateFlow<EditSession?> = _session

    /**
     * Set by the nav host just before it moves a coffee between the phone page and the right-hand
     * pane. The page being torn down must hand the edit over, not commit it.
     */
    var handingOver: Boolean = false

    /** The page currently showing the open edit; a page that is not the owner never commits it. */
    var owner: Any? = null

    fun start(session: EditSession) = put(session)
    fun setDraft(key: String, value: String) = put(_session.value?.let { it.copy(drafts = it.drafts + (key to value)) })
    fun setRating(draft: RatingDraft) = put(_session.value?.copy(rating = draft))
    fun clear() = put(null)
    fun forCoffee(id: String): EditSession? = _session.value?.takeIf { it.coffeeId == id }

    private fun put(s: EditSession?) {
        _session.value = s
        saved[KEY] = s?.let(::encode)
    }

    companion object {
        private const val KEY = "edit_session"
        private val json = Json { ignoreUnknownKeys = true }

        /** A saved-state copy of [EditSession]: strings and numbers only, so it fits any Bundle. */
        @Serializable
        private data class Saved(
            val coffeeId: String,
            val section: String,
            val drafts: Map<String, String>,
            val opened: Map<String, String>,
            val rating: List<Int?>? = null,
            val buyAgain: Boolean = false,
            val notes: String = "",
        )

        internal fun encode(s: EditSession): String = json.encodeToString(
            Saved.serializer(),
            Saved(s.coffeeId, s.section, s.drafts, s.opened, s.rating?.values, s.rating?.buyAgain ?: false, s.rating?.notes ?: ""),
        )

        /** Null for anything unreadable: a lost draft is better than a crash on the way back in. */
        internal fun decode(raw: String): EditSession? = runCatching {
            val v = json.decodeFromString(Saved.serializer(), raw)
            val r = v.rating?.takeIf { it.size == 5 }?.map { d -> d?.takeIf { it in 1..5 } }
            EditSession(
                coffeeId = v.coffeeId, section = v.section, drafts = v.drafts, opened = v.opened,
                rating = r?.let { RatingDraft(it[0], it[1], it[2], it[3], it[4], v.buyAgain, v.notes) },
            )
        }.getOrNull()
    }
}

/**
 * The activity's [EditSessions], the same instance from every screen. Must be called inside the
 * activity's own content, where the current activity is a ViewModel store owner.
 */
@Composable
fun rememberEditSessions(): EditSessions {
    val owner = LocalActivity.current as ViewModelStoreOwner
    return viewModel(viewModelStoreOwner = owner) { EditSessions(createSavedStateHandle()) }
}
