package dev.roastbook.app.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.roastbook.app.data.db.CoffeeEntity
import dev.roastbook.app.data.db.CoffeeRow
import dev.roastbook.app.ui.rating.RatingDraft
import dev.roastbook.app.ui.rating.RatingHandoff
import dev.roastbook.app.data.db.DateRange
import dev.roastbook.app.data.db.HistoryFilter
import dev.roastbook.app.data.db.SourceEntity
import dev.roastbook.app.data.db.HistorySort
import dev.roastbook.app.data.backup.BackupNudge
import dev.roastbook.app.data.backup.BackupPrefs
import dev.roastbook.app.data.repo.CoffeeRepository
import dev.roastbook.app.reminders.PendingActions
import dev.roastbook.app.ui.nav.History
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Answers to "Have you tried it yet?". TOMORROW and NEXT_WEEK set a rate-it reminder; every other
 * answer clears it.
 */
enum class TriedChoice { RATE_NOW, TOMORROW, NEXT_WEEK, LET_YOU_KNOW, NO_REMINDER }

/**
 * State for the journal: the sort, direction and filters, the paged rows they select (search typing
 * is debounced), counts, the backup banner, picker contents, and the rating and "tried it" sheets.
 * Open sheets are kept in saved state so they come back after process death; sort and filters are
 * not. Every write goes through [CoffeeRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repo: CoffeeRepository,
    private val pending: PendingActions,
    backupPrefs: BackupPrefs,
) : ViewModel() {
    init {
        viewModelScope.launch {
            pending.showRatingSheet.collect { id -> if (id != null) { pending.showRatingSheet.value = null; openRating(id) } }
        }
    }

    /**
     * The "Have you tried it yet?" sheet target, seeded from the route the Add screen navigated with.
     * Lives in saved state so rotation and process death neither lose it nor reopen an answered sheet.
     */
    val triedSheetFor: StateFlow<String?> = savedState.getStateFlow(KEY_TRIED, savedState.toRoute<History>().triedSheetFor)
    fun dismissTried() { savedState[KEY_TRIED] = null }

    /** "Add details" on the sheet: whatever is entered so far rides along to the Detail editor. */
    fun handOffDraft(coffeeId: String, draft: RatingDraft) { pending.ratingDraft.value = RatingHandoff(coffeeId, draft) }
    val sort = MutableStateFlow(HistorySort.RECENT)
    val descending = MutableStateFlow(true)
    val filter = MutableStateFlow(HistoryFilter())

    val rows: Flow<PagingData<CoffeeRow>> = combine(sort, descending, filter.debounce(150)) { s, d, f -> Triple(s, d, f) }
        .flatMapLatest { (s, d, f) -> repo.history(s, f, d) }
        .cachedIn(viewModelScope)

    /** Whether a coffee still exists: true or false as the row comes and goes. */
    fun exists(id: String): Flow<Boolean> = repo.observe(id).map { it != null }.distinctUntilChanged()

    val count: StateFlow<Int> = repo.count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val unrated: StateFlow<Int> = repo.unratedCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** The backup banner: absent while the book is empty, otherwise "backups are off", "no folder" or "stale for N days". */
    val backupNudge: StateFlow<BackupNudge?> = combine(backupPrefs.state, repo.count()) { s, n ->
        BackupNudge.compute(s, n, System.currentTimeMillis())
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The coffee whose rating sheet is open on History, if any. Its id is kept in saved state. */
    val ratingTarget = MutableStateFlow<CoffeeEntity?>(null)
    fun openRating(id: String) {
        savedState[KEY_RATING] = id
        viewModelScope.launch { ratingTarget.value = repo.get(id) }
    }
    fun closeRating() { ratingTarget.value = null; savedState[KEY_RATING] = null }
    // A sheet that was open when Android stopped the app comes back (its draft is in its own saved
    // state). After the declaration above: the reopening coroutine writes to [ratingTarget].
    init { savedState.get<String>(KEY_RATING)?.let(::openRating) }
    fun saveRating(d: RatingDraft) {
        val id = ratingTarget.value?.id ?: return
        viewModelScope.launch { repo.rate(id, d.aroma!!, d.flavor!!, d.finish!!, d.acidity, d.body, d.buyAgain, d.notes) }
    }

    fun setSearch(q: String) = filter.update { it.copy(search = q) }
    /** Tapping the selected chip again flips the direction. */
    fun tapSort(s: HistorySort) {
        if (sort.value == s) descending.value = !descending.value else { sort.value = s; descending.value = true }
    }
    fun clearFilters() = filter.update { HistoryFilter(search = it.search) }
    fun toggleUnrated() = filter.update { it.copy(unratedOnly = !it.unratedOnly) }
    fun toggleBuyAgain() = filter.update { it.copy(buyAgainOnly = !it.buyAgainOnly) }
    fun setSource(id: String?) = filter.update { it.copy(sourceId = id) }
    fun setRoaster(v: String?) = filter.update { it.copy(roaster = v) }
    fun setOrigin(v: String?) = filter.update { it.copy(originCountry = v) }
    fun setProcess(v: String?) = filter.update { it.copy(process = v) }
    fun setDateRange(r: DateRange) = filter.update { it.copy(dateRange = r) }

    /** Picker contents: what the book actually contains, live. */
    val sources: StateFlow<List<SourceEntity>> = repo.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val roasters: StateFlow<List<String>> = repo.roasters().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val origins: StateFlow<List<String>> = repo.origins().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val processes: StateFlow<List<String>> = repo.processes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The "Have you tried it yet?" answer: stores the reminder time the rate-it notification fires at. */
    fun answerTried(coffeeId: String, choice: TriedChoice) {
        viewModelScope.launch {
            val c = repo.get(coffeeId) ?: return@launch
            val day = 24L * 60 * 60 * 1000
            val remindAt = when (choice) {
                TriedChoice.TOMORROW -> System.currentTimeMillis() + day
                TriedChoice.NEXT_WEEK -> System.currentTimeMillis() + 7 * day
                else -> null
            }
            repo.setReminder(c.id, remindAt)
        }
    }

    private companion object {
        const val KEY_TRIED = "triedSheetFor"
        const val KEY_RATING = "ratingTarget"
    }
}
