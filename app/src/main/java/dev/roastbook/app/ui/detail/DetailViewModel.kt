package dev.roastbook.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeePhotoEntity
import dev.roastbook.app.data.db.SourceEntity
import dev.roastbook.app.data.photo.PhotoStore
import dev.roastbook.app.data.repo.CoffeeRepository
import dev.roastbook.app.reminders.PendingActions
import dev.roastbook.app.cloud.Enricher
import dev.roastbook.app.cloud.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import dev.roastbook.app.ui.rating.RatingDraft
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One coffee's page. Created with its id rather than from a route argument, so the same page runs
 * as its own screen on a phone and as the right-hand pane on a large screen.
 */
@HiltViewModel(assistedFactory = DetailViewModel.Factory::class)
class DetailViewModel @AssistedInject constructor(
    @Assisted val id: String,
    private val repo: CoffeeRepository,
    val photoStore: PhotoStore,
    private val pending: PendingActions,
    private val enricher: Enricher,
    secrets: SecretStore,
    private val reader: dev.roastbook.app.reader.LocalReaderManager,
    private val readNotes: ReadNotes,
    userPrefs: dev.roastbook.app.data.prefs.UserPrefs,
    private val readerPrefs: dev.roastbook.app.reader.ReaderPrefs,
) : ViewModel() {
    /** The person closed this coffee's "what happened" note (until the next read). */
    val noteDismissed: StateFlow<Boolean> = readNotes.dismissed.map { id in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, id in readNotes.dismissed.value)
    fun dismissNote() = readNotes.dismiss(id)

    val hasCloudKey: StateFlow<Boolean> = secrets.hasGeminiKey
    /** Bag photos are also saved to the gallery, where deleting the coffee leaves them (the delete dialog says so). */
    val savesToGallery: StateFlow<Boolean> = userPrefs.settings.map { it.saveSquaresToPhotos }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** The on-device reader could read bags here but has not been downloaded (the card points to Settings). */
    val readerOffered: StateFlow<Boolean> = reader.state.map(::offersReader)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    /** The device's own AI has been failing here, so Settings shows the reader even where Nano is reported available. */
    val nanoFailing: StateFlow<Boolean> = reader.state.map { it.nanoFailing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    /** The reader's download state and the device's reader settings, for the in-flow offer ([dev.roastbook.app.reader.ReaderNudge]). */
    val readerState: StateFlow<dev.roastbook.app.reader.ReaderState?> = reader.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val readerSettings: StateFlow<dev.roastbook.app.reader.ReaderSettings?> = readerPrefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    /** Yes to the in-flow offer: the reader becomes this device's choice, downloads, and reads this bag again when ready. */
    fun acceptReaderOffer() { viewModelScope.launch { readerPrefs.acceptOffer(id); reader.download() } }
    fun declineReaderOffer() { viewModelScope.launch { readerPrefs.declineOffer(id) } }
    /** A failed download, retried from this bag's page. */
    fun retryReaderDownload() { viewModelScope.launch { reader.download() } }
    /** The card's way out: stops the download, as Settings' Cancel does (no bag waits, the switch goes off). */
    fun cancelReaderDownload() { viewModelScope.launch { reader.cancel() } }
    val lookingUp = MutableStateFlow(false)
    /** Null = no message; otherwise the last manual lookup's outcome for the card. */
    val lookupOutcome = MutableStateFlow<dev.roastbook.app.cloud.LookupResult?>(null)
    /** "Look up roaster" with a key: the only way a lookup starts. A grounded search; results land as editable suggestions. */
    fun lookUpRoaster() {
        viewModelScope.launch {
            lookingUp.value = true; lookupOutcome.value = null
            val r = enricher.lookUp(id)
            lookingUp.value = false; lookupOutcome.value = r
        }
    }
    fun wrongRoaster() { viewModelScope.launch { enricher.reset(id); lookupOutcome.value = null } }

    val coffee: StateFlow<CoffeeEntity?> = repo.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val photos: StateFlow<List<CoffeePhotoEntity>> = repo.observePhotos(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sources: StateFlow<List<SourceEntity>> = repo.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Writes are committed inside the repository even if this scope is cleared by navigation a
    // frame later (the top-bar Back arrow commits an open editor on the way out).
    fun edit(transform: (CoffeeEntity) -> CoffeeEntity) {
        viewModelScope.launch { repo.edit(id, transform) }
    }

    /** Null or blank clears where the bag came from. */
    fun setSource(name: String?) {
        viewModelScope.launch { repo.setSource(id, name) }
    }

    /** The partly filled rating the History sheet handed over, if it was for this coffee; consumed once. */
    fun takeRatingHandoff(): RatingDraft? =
        pending.ratingDraft.value?.takeIf { it.coffeeId == id }?.also { pending.ratingDraft.value = null }?.draft

    fun saveRating(d: RatingDraft) {
        viewModelScope.launch { repo.rate(id, d.aroma!!, d.flavor!!, d.finish!!, d.acidity, d.body, d.buyAgain, d.notes) }
    }

    /** Read again: the note comes back for whatever this read turns out to be. */
    fun reextract() { readNotes.clear(id); viewModelScope.launch { repo.reextract(id) } }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { repo.delete(id); onDone() }
    }

    @AssistedFactory
    interface Factory { fun create(id: String): DetailViewModel }

    companion object {
        /** The on-device reader could run on this device and has not been downloaded yet. */
        internal fun offersReader(s: dev.roastbook.app.reader.ReaderState): Boolean =
            s.eligibility == dev.roastbook.app.reader.Eligibility.ELIGIBLE && s.phase != dev.roastbook.app.reader.DownloadPhase.READY
    }
}
